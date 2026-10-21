import { query, transaction } from '../db/index.js';
import { adaptQuestions, type LanguageCode } from './questionAdapter.js';
import { effectiveSettings, wordingContext, type Settings } from '../support/settings.js';
import type { Question, Paper } from '../types/schema.js';

export interface PaperGenerationRequest {
  studentId: string;
  teacherId: string;
  classId?: string;
  subject: string;
  grade: number;
  totalMarks: number;
  title: string;
  durationMinutes?: number;
}

export interface GeneratedPaper {
  paperId: string;
  totalMarks: number;
  warnings: string[];
  settings: Settings;
  questions: Array<{
    questionVersionId: string;
    baseQuestionId: string;
    sequence: number;
    marks: number;
    content: {
      text: string;
      options?: string[];
    };
  }>;
}

interface StudentRow {
  language: string;
  accessibility_needs: string[];
  accessibility_profile: unknown;
  current_level: Record<string, unknown> | null;
}

/** Marks are counted in half-marks so everything is a whole number. */
interface Candidate {
  question: Question;
  type: string;
  units: number;
}

interface PreparedVersion {
  candidate: Candidate;
  versionId: string | null;
  text: string;
  options?: string[];
}

// Rough CBSE balance: how much of the paper's marks each question type should take.
const TYPE_SHARE: Array<[string, number]> = [
  ['mcq', 0.4],
  ['assertion_reason', 0.05],
  ['short_answer', 0.25],
  ['long_answer', 0.2],
  ['case_study', 0.1],
];
const TYPE_ORDER = TYPE_SHARE.map(([type]) => type);

function shuffle<T>(items: T[]): T[] {
  const copy = [...items];
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy;
}

function sumUnits(items: Candidate[]): number {
  return items.reduce((total, c) => total + c.units, 0);
}

/** Finds questions that add up to the target, or as close below it as possible. */
function bestSubset(items: Candidate[], target: number): Candidate[] {
  if (target <= 0 || items.length === 0) return [];
  const from = new Int32Array(target + 1).fill(-1);
  const reachable = new Uint8Array(target + 1);
  reachable[0] = 1;
  items.forEach((item, index) => {
    for (let s = target; s >= item.units; s--) {
      if (!reachable[s] && reachable[s - item.units]) {
        reachable[s] = 1;
        from[s] = index;
      }
    }
  });
  let best = target;
  while (best > 0 && !reachable[best]) best--;
  const chosen: Candidate[] = [];
  let s = best;
  while (s > 0) {
    const item = items[from[s]];
    chosen.push(item);
    s -= item.units;
  }
  return chosen;
}

function pickQuestions(candidates: Candidate[], target: number): Candidate[] {
  // First a balanced pick by question type
  const greedy: Candidate[] = [];
  let used = 0;
  for (const [type, share] of TYPE_SHARE) {
    const budget = Math.floor(target * share);
    let typeUsed = 0;
    for (const c of candidates) {
      if (c.type !== type) continue;
      if (typeUsed + c.units <= budget && used + c.units <= target) {
        greedy.push(c);
        typeUsed += c.units;
        used += c.units;
      }
    }
  }

  // Then top up to the exact total, letting go of some picks if that is what it takes
  let best: Candidate[] = [];
  let bestUnits = -1;
  for (let drop = 0; drop <= greedy.length; drop++) {
    const base = greedy.slice(0, greedy.length - drop);
    const pool = candidates.filter((c) => !base.includes(c));
    const all = [...base, ...bestSubset(pool, target - sumUnits(base))];
    const units = sumUnits(all);
    if (units > bestUnits) {
      best = all;
      bestUnits = units;
    }
    if (units === target) break;
  }
  return best;
}

function toLanguage(value: string): LanguageCode {
  return value === 'hi' || value === 'kn' ? value : 'en';
}

export class PaperGenerationService {
  /**
   * Builds one student's paper from the shared question bank. Difficulty follows the
   * student's level, the wording follows their language and wording supports, and any
   * mix of supports works. Display settings are applied by the app, not baked in.
   */
  async generatePaper(request: PaperGenerationRequest): Promise<GeneratedPaper> {
    const student = await this.loadStudent(request.studentId, request.teacherId);
    const settings = effectiveSettings(student.accessibility_needs, student.accessibility_profile);
    const context = wordingContext(settings);
    const language = toLanguage(student.language);
    const level = this.levelFor(student.current_level, request.subject);

        const { selected, warnings } = await this.selectQuestions(
      request.subject,
      request.grade,
      request.totalMarks,
      level,
      settings.audioFirst
    );
    if (settings.audioFirst) {
      warnings.push('Questions that depend on a figure are left out, because this student listens to the paper.');
    }

    // All AI work happens here, before any database transaction is opened
    const prepared = await this.prepareVersions(selected, language, context);
    const totalMarks = sumUnits(selected) / 2;

    return transaction(async (client) => {
      for (const item of prepared) {
        if (item.versionId !== null) continue;
        const saved = await client.query<{ id: string }>(
          `INSERT INTO question_versions (base_question_id, language, accessibility_context, content)
           VALUES ($1, $2, $3, $4)
           ON CONFLICT (base_question_id, language, accessibility_context)
           DO UPDATE SET content = EXCLUDED.content
           RETURNING id`,
          [
            item.candidate.question.id,
            language,
            context,
            JSON.stringify({
              text: item.text,
              options: item.options,
              image_url: item.candidate.question.content.image_url,
            }),
          ]
        );
        item.versionId = saved.rows[0].id;
      }

      const paperQuestions = prepared.map((item, index) => ({
        question_version_id: item.versionId,
        base_question_id: item.candidate.question.id,
        sequence: index + 1,
        marks: item.candidate.units / 2,
      }));

      const paperResult = await client.query<{ id: string }>(
        `INSERT INTO papers
         (student_id, teacher_id, class_id, title, subject, grade, total_marks, duration_minutes, questions, status)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, 'draft')
         RETURNING id`,
        [
          request.studentId,
          request.teacherId,
          request.classId ?? null,
          request.title,
          request.subject,
          request.grade,
          totalMarks,
          request.durationMinutes ?? null,
          JSON.stringify(paperQuestions),
        ]
      );

      return {
        paperId: paperResult.rows[0].id,
        totalMarks,
        warnings,
        settings,
        questions: prepared.map((item, index) => ({
          questionVersionId: item.versionId as string,
          baseQuestionId: item.candidate.question.id,
          sequence: index + 1,
          marks: item.candidate.units / 2,
          content: { text: item.text, options: item.options },
        })),
      };
    });
  }

  private async loadStudent(studentId: string, teacherId: string): Promise<StudentRow> {
    // The student must belong to a class this teacher owns
    const result = await query<StudentRow>(
      `SELECT s.language, s.accessibility_needs, s.accessibility_profile, s.current_level
       FROM students s
       WHERE s.id = $1
         AND EXISTS (
           SELECT 1 FROM class_enrollments ce
           JOIN classes c ON c.id = ce.class_id
           WHERE ce.student_id = s.id AND c.teacher_id = $2
         )`,
      [studentId, teacherId]
    );
    if (result.rows.length === 0) {
      throw new Error('Student not found');
    }
    return result.rows[0];
  }

  private levelFor(currentLevel: Record<string, unknown> | null, subject: string): number {
    const raw = Number(currentLevel?.[subject]);
    if (!Number.isFinite(raw) || raw < 1) return 3; // no level check done yet
    return Math.min(5, Math.max(1, Math.round(raw)));
  }

  /** Picks questions near the student's level that add up to the paper's total marks. */
    private async selectQuestions(
    subject: string,
    grade: number,
    totalMarks: number,
    level: number,
    skipFigures: boolean
  ): Promise<{ selected: Candidate[]; warnings: string[] }> {
    const warnings: string[] = [];
    const target = Math.round(totalMarks * 2);
    let best: Candidate[] = [];
    let bestUnits = -1;
    let usedRadius = 1;

    for (const radius of [1, 2, 4]) {
            const result = await query<Question>(
        `SELECT * FROM questions
         WHERE subject = $1 AND grade = $2 AND difficulty BETWEEN $3 AND $4
           AND ($5::boolean = false OR COALESCE(metadata->>'needsFigure', 'false') <> 'true')`,
        [subject, grade, Math.max(1, level - radius), Math.min(5, level + radius), skipFigures]
      );
      const candidates = shuffle(
        result.rows
          .map((row) => ({
            question: row,
            type: String(row.type),
            // marks come back from the database as text such as "2.00"
            units: Math.round(Number(row.marks) * 2),
          }))
          .filter((c) => c.units > 0)
      );
      const picked = pickQuestions(candidates, target);
      const units = sumUnits(picked);
      if (units > bestUnits) {
        best = picked;
        bestUnits = units;
        usedRadius = radius;
      }
      if (bestUnits === target) break;
    }

    if (bestUnits !== target) {
      warnings.push(
        `The question bank could only fill ${Math.max(bestUnits, 0) / 2} of ${totalMarks} marks. Add more questions to reach the full total.`
      );
    }
    if (usedRadius > 1 && best.length > 0) {
      warnings.push(
        'Some questions are further from this student’s level because the bank has too few at their level.'
      );
    }

    // CBSE order: multiple choice first, case studies last
    const rank = (c: Candidate) => {
      const index = TYPE_ORDER.indexOf(c.type);
      return index === -1 ? TYPE_ORDER.length : index;
    };
    const ordered = [...best].sort((a, b) => rank(a) - rank(b));
    return { selected: ordered, warnings };
  }

  /** Reuses saved versions and makes the missing ones in a few batched AI calls. */
  private async prepareVersions(
    selected: Candidate[],
    language: LanguageCode,
    context: string
  ): Promise<PreparedVersion[]> {
    const existing = await query<{
      id: string;
      base_question_id: string;
      content: { text: string; options?: string[] };
    }>(
      `SELECT id, base_question_id, content FROM question_versions
       WHERE base_question_id = ANY($1::uuid[])
         AND language = $2
         AND accessibility_context = $3`,
      [selected.map((c) => c.question.id), language, context]
    );
    const saved = new Map(existing.rows.map((row) => [row.base_question_id, row]));

    const missing = selected.filter((c) => !saved.has(c.question.id));
    const adapted = await adaptQuestions(
      missing.map((c) => ({
        id: c.question.id,
        subject: c.question.subject,
        grade: c.question.grade,
        text: c.question.content.text,
        options: c.question.content.options,
      })),
      language,
      context
    );

    return selected.map((candidate) => {
      const row = saved.get(candidate.question.id);
      if (row) {
        return {
          candidate,
          versionId: row.id,
          text: row.content.text,
          options: row.content.options,
        };
      }
      const made = adapted.get(candidate.question.id);
      if (!made) {
        throw new Error('A question could not be prepared. Please try again.');
      }
      return { candidate, versionId: null, text: made.text, options: made.options };
    });
  }

  /**
   * Get a generated paper for a student
   */
  async getPaper(paperId: string): Promise<Paper | null> {
    const result = await query<Paper>('SELECT * FROM papers WHERE id = $1', [paperId]);
    return result.rows[0] || null;
  }

  /**
   * Publish a paper (make it available to student)
   */
  async publishPaper(paperId: string, dueDate?: Date): Promise<void> {
    await query(
      `UPDATE papers
       SET status = 'published',
           published_at = NOW(),
           due_at = $2
       WHERE id = $1`,
      [paperId, dueDate]
    );
  }
}

export const paperGenerationService = new PaperGenerationService();