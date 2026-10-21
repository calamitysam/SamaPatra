import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { paperGenerationService } from '../services/paperGeneration.js';
import { query } from '../db/index.js';
import { requireRole } from '../auth/guards.js';
import { effectiveSettings } from '../support/settings.js';

const STATUSES = ['draft', 'published', 'in_progress', 'submitted', 'graded'] as const;

const GeneratePaperSchema = z.object({
  studentId: z.string().uuid(),
  classId: z.string().uuid().optional(),
  subject: z.string().trim().min(1).max(100),
  grade: z.number().int().min(9).max(10),
  totalMarks: z.number().positive().max(200),
  title: z.string().trim().min(1).max(255),
  durationMinutes: z.number().int().positive().max(600).optional(),
});

const PublishPaperSchema = z.object({ dueDate: z.string().optional() });

const AnswerSchema = z.object({
  questionVersionId: z.string().uuid(),
  answerText: z.string().max(20000).optional(),
  answerAudioUrl: z.string().max(500).optional(),
});

const IdParams = z.object({ id: z.string().uuid() });

interface PaperQuestion {
  question_version_id: string;
  base_question_id: string;
  sequence: number;
  marks: number;
}

interface PaperRow {
  id: string;
  student_id: string;
  teacher_id: string;
  class_id: string | null;
  title: string;
  subject: string;
  grade: number;
  total_marks: string;
  duration_minutes: number | null;
  questions: PaperQuestion[];
  status: string;
  generated_at: Date;
  published_at: Date | null;
  started_at: Date | null;
  submitted_at: Date | null;
  due_at: Date | null;
}

type AuthUser = { sub: string; role: 'teacher' | 'student' | 'admin'; studentId?: string };

async function findPaper(id: string): Promise<PaperRow | undefined> {
  const result = await query<PaperRow>('SELECT * FROM papers WHERE id = $1', [id]);
  return result.rows[0];
}

function teacherOwns(paper: PaperRow, user: AuthUser): boolean {
  return user.role === 'admin' || (user.role === 'teacher' && paper.teacher_id === user.sub);
}

function studentOwns(paper: PaperRow, user: AuthUser): boolean {
  return user.role === 'student' && paper.student_id === user.studentId;
}

export default async function paperRoutes(fastify: FastifyInstance) {
  // Teacher makes a paper for one of their students
  fastify.post(
    '/papers/generate',
    { preHandler: requireRole('teacher', 'admin') },
    async (request, reply) => {
      const validation = GeneratePaperSchema.safeParse(request.body);
      if (!validation.success) {
        reply.code(400);
        return { error: 'Invalid request', message: 'Check the paper details.' };
      }

      if (validation.data.classId) {
        const owned = await query('SELECT 1 FROM classes WHERE id = $1 AND teacher_id = $2', [
          validation.data.classId,
          request.user.sub,
        ]);
        if (owned.rows.length === 0) {
          reply.code(404);
          return { error: 'Not found', message: 'Class not found.' };
        }
      }
            const duplicate = await query(
        'SELECT 1 FROM papers WHERE student_id = $1 AND teacher_id = $2 AND title = $3',
        [validation.data.studentId, request.user.sub, validation.data.title]
      );
      if (duplicate.rows.length > 0) {
        reply.code(409);
        return { error: 'Already made', message: 'A paper with this title was already made for this student.' };
      }

      try {
        const paper = await paperGenerationService.generatePaper({
          ...validation.data,
          teacherId: request.user.sub,
        });
        reply.code(201);
        return { success: true, paper };
      } catch (error: any) {
        const message = String(error?.message ?? '');
        if (message === 'Student not found') {
          reply.code(404);
          return { error: 'Not found', message: 'That student isn’t in one of your classes.' };
        }
        if (message.startsWith('No suitable questions')) {
          reply.code(422);
          return {
            error: 'No questions',
            message: 'The question bank has no questions for this subject and grade yet.',
          };
        }
                const aiStatus = (error as { status?: number } | null)?.status;
        if (aiStatus === 503 || aiStatus === 429) {
          reply.code(503);
          return {
            error: 'AI busy',
            message: 'The AI service is busy right now. Please try again in a minute. Papers already made are kept.',
          };
        }
        fastify.log.error(error, 'Paper generation error');
        reply.code(500);
        return { error: 'Failed', message: 'Could not make the paper. Please try again.' };
      }
    }
  );

  // One paper: its questions in order, the student's settings, and any saved answers
  fastify.get('/papers/:id', async (request, reply) => {
    const params = IdParams.safeParse(request.params);
    const paper = params.success ? await findPaper(params.data.id) : undefined;
    const user = request.user;

    const asTeacher = paper !== undefined && teacherOwns(paper, user);
    const asStudent = paper !== undefined && studentOwns(paper, user) && paper.status !== 'draft';
    if (!paper || (!asTeacher && !asStudent)) {
      reply.code(404);
      return { error: 'Not found', message: 'Paper not found.' };
    }

    const ordered = [...paper.questions].sort((a, b) => a.sequence - b.sequence);
    const versions = await query<{
      id: string;
      type: string;
      content: { text: string; options?: string[] };
    }>(
      `SELECT qv.id, q.type, qv.content
       FROM question_versions qv
       JOIN questions q ON q.id = qv.base_question_id
       WHERE qv.id = ANY($1::uuid[])`,
      [ordered.map((q) => q.question_version_id)]
    );
    const byId = new Map(versions.rows.map((v) => [v.id, v]));

    const student = await query<{ accessibility_needs: string[]; accessibility_profile: unknown }>(
      'SELECT accessibility_needs, accessibility_profile FROM students WHERE id = $1',
      [paper.student_id]
    );
    const settings = student.rows[0]
      ? effectiveSettings(student.rows[0].accessibility_needs, student.rows[0].accessibility_profile)
      : null;

    const saved = await query<{ question_version_id: string; answer_text: string | null }>(
      'SELECT question_version_id, answer_text FROM answers WHERE paper_id = $1',
      [paper.id]
    );
    const answers: Record<string, string> = {};
    for (const row of saved.rows) answers[row.question_version_id] = row.answer_text ?? '';

    return {
      paper: {
        id: paper.id,
        studentId: paper.student_id,
        title: paper.title,
        subject: paper.subject,
        grade: paper.grade,
        totalMarks: Number(paper.total_marks),
        durationMinutes: paper.duration_minutes,
        status: paper.status,
        dueAt: paper.due_at,
        startedAt: paper.started_at,
        submittedAt: paper.submitted_at,
      },
      questions: ordered.map((item) => {
        const version = byId.get(item.question_version_id);
        return {
          questionVersionId: item.question_version_id,
          baseQuestionId: item.base_question_id,
          sequence: item.sequence,
          marks: Number(item.marks),
          type: version?.type ?? 'short_answer',
          content: { text: version?.content.text ?? '', options: version?.content.options },
        };
      }),
      settings,
      answers,
    };
  });

  // A student's papers. Students see their own published ones, teachers see ones they made.
  fastify.get('/papers/student/:studentId', async (request, reply) => {
    const params = z.object({ studentId: z.string().uuid() }).safeParse(request.params);
    const filter = z.object({ status: z.enum(STATUSES).optional() }).safeParse(request.query);
    if (!params.success || !filter.success) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Check the request.' };
    }
    const { studentId } = params.data;
    const user = request.user;

    let queryText = 'SELECT * FROM papers WHERE student_id = $1';
    const values: any[] = [studentId];

    if (user.role === 'student') {
      if (user.studentId !== studentId) {
        reply.code(403);
        return { error: 'Forbidden', message: 'You can only see your own papers.' };
      }
      queryText += " AND status <> 'draft'";
    } else if (user.role === 'teacher') {
      queryText += ' AND teacher_id = $2';
      values.push(user.sub);
    }

    if (filter.data.status) {
      values.push(filter.data.status);
      queryText += ` AND status = $${values.length}`;
    }
    queryText += ' ORDER BY generated_at DESC';

    const result = await query<PaperRow>(queryText, values);
    return {
      papers: result.rows.map((p) => ({
        id: p.id,
        title: p.title,
        subject: p.subject,
        grade: p.grade,
        totalMarks: Number(p.total_marks),
        status: p.status,
        dueAt: p.due_at,
        generatedAt: p.generated_at,
      })),
    };
  });

  // Papers a teacher made
  fastify.get(
    '/papers/teacher/:teacherId',
    { preHandler: requireRole('teacher', 'admin') },
    async (request, reply) => {
      const params = z.object({ teacherId: z.string().uuid() }).safeParse(request.params);
      const filter = z
        .object({ status: z.enum(STATUSES).optional(), classId: z.string().uuid().optional() })
        .safeParse(request.query);
      if (!params.success || !filter.success) {
        reply.code(400);
        return { error: 'Invalid request', message: 'Check the request.' };
      }
      if (request.user.role !== 'admin' && params.data.teacherId !== request.user.sub) {
        reply.code(403);
        return { error: 'Forbidden', message: 'You can only see your own papers.' };
      }

      let queryText = 'SELECT * FROM papers WHERE teacher_id = $1';
      const values: any[] = [params.data.teacherId];
      if (filter.data.status) {
        values.push(filter.data.status);
        queryText += ` AND status = $${values.length}`;
      }
      if (filter.data.classId) {
        values.push(filter.data.classId);
        queryText += ` AND class_id = $${values.length}`;
      }
      queryText += ' ORDER BY generated_at DESC';

      const result = await query<PaperRow>(queryText, values);
      return {
        papers: result.rows.map((p) => ({
          id: p.id,
          studentId: p.student_id,
          classId: p.class_id,
          title: p.title,
          subject: p.subject,
          grade: p.grade,
          totalMarks: Number(p.total_marks),
          status: p.status,
          dueAt: p.due_at,
          generatedAt: p.generated_at,
        })),
      };
    }
  );

  // Publish a draft so the student can see it
  fastify.post(
    '/papers/:id/publish',
    { preHandler: requireRole('teacher', 'admin') },
    async (request, reply) => {
      const params = IdParams.safeParse(request.params);
      const body = PublishPaperSchema.safeParse(request.body ?? {});
      if (!params.success || !body.success) {
        reply.code(400);
        return { error: 'Invalid request', message: 'Check the request.' };
      }
      const dueDate = body.data.dueDate ? new Date(body.data.dueDate) : null;
      if (dueDate && Number.isNaN(dueDate.getTime())) {
        reply.code(400);
        return { error: 'Invalid request', message: 'That due date is not valid.' };
      }

      const result = await query(
        `UPDATE papers
         SET status = 'published', published_at = NOW(), due_at = $2
         WHERE id = $1 AND teacher_id = $3 AND status = 'draft'
         RETURNING id`,
        [params.data.id, dueDate, request.user.sub]
      );
      if (result.rowCount === 0) {
        reply.code(409);
        return { error: 'Cannot publish', message: 'Only your own draft papers can be published.' };
      }
      return { success: true };
    }
  );

  // Student starts a published paper
  fastify.post('/papers/:id/start', { preHandler: requireRole('student') }, async (request, reply) => {
    const params = IdParams.safeParse(request.params);
    if (!params.success) {
      reply.code(404);
      return { error: 'Not found', message: 'Paper not found.' };
    }
    const result = await query(
      `UPDATE papers
       SET status = 'in_progress', started_at = NOW()
       WHERE id = $1 AND student_id = $2 AND status = 'published'
       RETURNING id`,
      [params.data.id, request.user.studentId]
    );
    if (result.rowCount === 0) {
      reply.code(409);
      return { error: 'Cannot start', message: 'This paper isn’t ready to start, or it has already been started.' };
    }
    return { success: true };
  });

  // Student saves an answer
  fastify.post('/papers/:id/answer', { preHandler: requireRole('student') }, async (request, reply) => {
    const params = IdParams.safeParse(request.params);
    const body = AnswerSchema.safeParse(request.body);
    if (!params.success || !body.success) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Check your answer.' };
    }

    const paper = await findPaper(params.data.id);
    if (!paper || !studentOwns(paper, request.user)) {
      reply.code(404);
      return { error: 'Not found', message: 'Paper not found.' };
    }
    if (paper.status !== 'in_progress') {
      reply.code(409);
      return { error: 'Not open', message: 'This paper isn’t open for answers.' };
    }

    // The question's base ID comes from the paper, never from the app
    const item = paper.questions.find((q) => q.question_version_id === body.data.questionVersionId);
    if (!item) {
      reply.code(400);
      return { error: 'Invalid request', message: 'That question isn’t part of this paper.' };
    }

    const saved = await query<{ id: string }>(
      `INSERT INTO answers
       (paper_id, student_id, question_version_id, base_question_id, answer_text, answer_audio_url)
       VALUES ($1, $2, $3, $4, $5, $6)
       ON CONFLICT (paper_id, question_version_id)
       DO UPDATE SET
         answer_text = EXCLUDED.answer_text,
         answer_audio_url = EXCLUDED.answer_audio_url,
         answered_at = NOW()
       RETURNING id`,
      [
        paper.id,
        paper.student_id,
        item.question_version_id,
        item.base_question_id,
        body.data.answerText ?? null,
        body.data.answerAudioUrl ?? null,
      ]
    );
    return { success: true, answerId: saved.rows[0].id };
  });

  // Student hands in the paper
  fastify.post('/papers/:id/submit', { preHandler: requireRole('student') }, async (request, reply) => {
    const params = IdParams.safeParse(request.params);
    if (!params.success) {
      reply.code(404);
      return { error: 'Not found', message: 'Paper not found.' };
    }
    const result = await query(
      `UPDATE papers
       SET status = 'submitted', submitted_at = NOW()
       WHERE id = $1 AND student_id = $2 AND status = 'in_progress'
       RETURNING id`,
      [params.data.id, request.user.studentId]
    );
    if (result.rowCount === 0) {
      reply.code(409);
      return { error: 'Cannot submit', message: 'This paper isn’t in progress, or it was already handed in.' };
    }
    return { success: true };
  });

  // Teacher's numbers for one paper
  fastify.get(
    '/papers/:id/stats',
    { preHandler: requireRole('teacher', 'admin') },
    async (request, reply) => {
      const params = IdParams.safeParse(request.params);
      if (!params.success) {
        reply.code(404);
        return { error: 'Not found', message: 'Paper not found.' };
      }
      const result = await query<{
        total_marks: string;
        status: string;
        answers_submitted: string;
        answers_graded: string;
        total_marks_obtained: string | null;
      }>(
        `SELECT
          p.total_marks,
          p.status,
          COUNT(a.id) AS answers_submitted,
          COUNT(CASE WHEN a.is_graded THEN 1 END) AS answers_graded,
          SUM(a.marks_obtained) AS total_marks_obtained
         FROM papers p
         LEFT JOIN answers a ON a.paper_id = p.id
         WHERE p.id = $1 AND (p.teacher_id = $2 OR $3 = 'admin')
         GROUP BY p.id, p.total_marks, p.status`,
        [params.data.id, request.user.sub, request.user.role]
      );
      if (result.rows.length === 0) {
        reply.code(404);
        return { error: 'Not found', message: 'Paper not found.' };
      }
      const row = result.rows[0];
      return {
        stats: {
          totalMarks: Number(row.total_marks),
          status: row.status,
          answersSubmitted: Number(row.answers_submitted),
          answersGraded: Number(row.answers_graded),
          totalMarksObtained: row.total_marks_obtained === null ? null : Number(row.total_marks_obtained),
        },
      };
    }
  );

  // Delete a draft paper
  fastify.delete(
    '/papers/:id',
    { preHandler: requireRole('teacher', 'admin') },
    async (request, reply) => {
      const params = IdParams.safeParse(request.params);
      if (!params.success) {
        reply.code(404);
        return { error: 'Not found', message: 'Paper not found.' };
      }
      const result = await query(
        `DELETE FROM papers
         WHERE id = $1 AND (teacher_id = $2 OR $3 = 'admin') AND status = 'draft'
         RETURNING id`,
        [params.data.id, request.user.sub, request.user.role]
      );
      if (result.rowCount === 0) {
        reply.code(409);
        return { error: 'Cannot delete', message: 'Only your own draft papers can be deleted.' };
      }
      return { success: true };
    }
  );
}