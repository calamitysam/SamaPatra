import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { query, transaction } from '../db/index.js';

// Every import has an importId. Anything older than that is grouped by subject and grade.
const GROUP_KEY = `COALESCE(metadata->>'importId', 'single-' || subject || '-' || grade)`;

const KeyParams = z.object({ key: z.string().min(1).max(200) });

interface QuestionRow {
  id: string;
  type: string;
  marks: string;
  chapter: string;
  difficulty: number;
  content: { text: string; options?: string[]; correct_answer?: string };
  metadata: Record<string, any> | null;
  created_by: string | null;
}

type DeleteOutcome =
  | { kind: 'missing' }
  | { kind: 'inUse'; shared: number }
  | { kind: 'deleted'; questions: number; papers: number };

export default async function bankRoutes(fastify: FastifyInstance) {
  // Every imported paper in the question bank
  fastify.get('/bank/papers', async (request) => {
    const result = await query<{
      key: string;
      name: string | null;
      subject: string;
      grade: number;
      questions: string;
      total_marks: string;
      level_check_ready: string;
      unchecked: string;
      created_at: Date;
      mine: boolean;
    }>(
      `SELECT ${GROUP_KEY} AS key,
              MAX(metadata->>'paperName') AS name,
              subject, grade,
              COUNT(*) AS questions,
              SUM(marks) AS total_marks,
              COUNT(*) FILTER (
                WHERE type = 'mcq'
                  AND COALESCE(content->>'correct_answer', '') <> ''
                  AND COALESCE(metadata->>'answerVerified', 'true') <> 'false'
              ) AS level_check_ready,
              COUNT(*) FILTER (WHERE metadata->>'answerVerified' = 'false') AS unchecked,
              MIN(created_at) AS created_at,
              BOOL_OR(created_by = $1 OR $2 = 'admin') AS mine
       FROM questions
       GROUP BY 1, subject, grade
       ORDER BY MIN(created_at) DESC`,
      [request.user.sub, request.user.role]
    );

    return {
      papers: result.rows.map((r) => ({
        key: r.key,
        name: r.name,
        subject: r.subject,
        grade: r.grade,
        questions: Number(r.questions),
        totalMarks: Number(r.total_marks),
        levelCheckReady: Number(r.level_check_ready),
        unchecked: Number(r.unchecked),
        createdAt: r.created_at,
        mine: r.mine === true,
      })),
    };
  });

  // The questions of one imported paper, and how many student papers use them
  fastify.get('/bank/papers/:key', async (request, reply) => {
    const params = KeyParams.safeParse(request.params);
    if (!params.success) {
      reply.code(404);
      return { error: 'Not found', message: 'Paper not found.' };
    }

    const rows = await query<QuestionRow>(
      `SELECT id, type, marks, chapter, difficulty, content, metadata, created_by
       FROM questions
       WHERE ${GROUP_KEY} = $1
       ORDER BY NULLIF(regexp_replace(COALESCE(metadata->>'questionNumber', ''), '\\D', '', 'g'), '')::int NULLS LAST,
                created_at, id`,
      [params.data.key]
    );
    if (rows.rows.length === 0) {
      reply.code(404);
      return { error: 'Not found', message: 'Paper not found.' };
    }

    const used = await query<{ status: string; n: string }>(
      `SELECT p.status, COUNT(DISTINCT p.id) AS n
       FROM papers p, jsonb_array_elements(p.questions) AS e
       WHERE e->>'base_question_id' = ANY($1::text[])
       GROUP BY p.status`,
      [rows.rows.map((r) => r.id)]
    );
    let unshared = 0;
    let shared = 0;
    for (const row of used.rows) {
      if (row.status === 'draft') unshared += Number(row.n);
      else shared += Number(row.n);
    }

    const first = rows.rows[0];
    const mine =
      request.user.role === 'admin' || rows.rows.some((r) => r.created_by === request.user.sub);

    const gradeSubject = await query<{ subject: string; grade: number }>(
      'SELECT subject, grade FROM questions WHERE id = $1',
      [first.id]
    );

    return {
      paper: {
        key: params.data.key,
        name: first.metadata?.paperName ?? null,
        subject: gradeSubject.rows[0]?.subject ?? '',
        grade: gradeSubject.rows[0]?.grade ?? 0,
        mine,
        unsharedPapers: unshared,
        sharedPapers: shared,
      },
      questions: rows.rows.map((r) => ({
        id: r.id,
        number: String(r.metadata?.questionNumber ?? ''),
        type: r.type,
        marks: Number(r.marks),
        chapter: r.chapter,
        difficulty: r.difficulty,
        text: r.content.text,
        options: r.content.options ?? [],
        correctAnswer: r.content.correct_answer ?? null,
        answerChecked: r.metadata?.answerVerified !== false,
        needsFigure: r.metadata?.needsFigure === true,
      })),
    };
  });

  // Delete a whole imported paper. Refused if students already have papers made from it.
  fastify.delete('/bank/papers/:key', async (request, reply) => {
    const params = KeyParams.safeParse(request.params);
    if (!params.success) {
      reply.code(404);
      return { error: 'Not found', message: 'Paper not found.' };
    }

    try {
      const outcome = await transaction<DeleteOutcome>(async (client) => {
        const found = await client.query<{ id: string }>(
          `SELECT id FROM questions
           WHERE ${GROUP_KEY} = $1 AND (created_by = $2 OR $3 = 'admin')`,
          [params.data.key, request.user.sub, request.user.role]
        );
        if (found.rows.length === 0) return { kind: 'missing' };
        const ids = found.rows.map((r) => r.id);

        const used = await client.query<{ id: string; status: string }>(
          `SELECT DISTINCT p.id, p.status
           FROM papers p, jsonb_array_elements(p.questions) AS e
           WHERE e->>'base_question_id' = ANY($1::text[])`,
          [ids]
        );
        const shared = used.rows.filter((r) => r.status !== 'draft');
        if (shared.length > 0) return { kind: 'inUse', shared: shared.length };

        const drafts = used.rows.map((r) => r.id);
        if (drafts.length > 0) {
          await client.query('DELETE FROM papers WHERE id = ANY($1::uuid[])', [drafts]);
        }
        await client.query('DELETE FROM questions WHERE id = ANY($1::uuid[])', [ids]);
        return { kind: 'deleted', questions: ids.length, papers: drafts.length };
      });

      if (outcome.kind === 'missing') {
        reply.code(404);
        return { error: 'Not found', message: 'Paper not found, or it was imported by another teacher.' };
      }
      if (outcome.kind === 'inUse') {
        reply.code(409);
        return {
          error: 'In use',
          message:
            outcome.shared === 1
              ? '1 paper made from these questions is already shared with a student, so this paper can’t be deleted.'
              : `${outcome.shared} papers made from these questions are already shared with students, so this paper can’t be deleted.`,
        };
      }
      return { success: true, questions: outcome.questions, papers: outcome.papers };
    } catch (error: any) {
      if (error.code === '23503') {
        reply.code(409);
        return { error: 'In use', message: 'Students have already answered these questions, so they can’t be deleted.' };
      }
      throw error;
    }
  });
}