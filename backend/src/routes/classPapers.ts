import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { query } from '../db/index.js';
import { ownsClass } from '../auth/access.js';

const ClassParams = z.object({ classId: z.string().uuid() });
const ShareSchema = z.object({
  title: z.string().trim().min(1).max(255),
  dueDate: z.string().optional(),
});

export default async function classPaperRoutes(fastify: FastifyInstance) {
  // The paper sets made for a class, newest first
  fastify.get('/classes/:classId/papers', async (request, reply) => {
    const params = ClassParams.safeParse(request.params);
    if (!params.success || !(await ownsClass(request.user, params.data.classId))) {
      reply.code(404);
      return { error: 'Not found', message: 'Class not found.' };
    }

    const result = await query<{
      title: string;
      subject: string;
      total_marks: string;
      papers: string;
      drafts: string;
      shared: string;
    }>(
      `SELECT title, subject, MAX(total_marks) AS total_marks,
              COUNT(*) AS papers,
              COUNT(*) FILTER (WHERE status = 'draft') AS drafts,
              COUNT(*) FILTER (WHERE status <> 'draft') AS shared
       FROM papers
       WHERE class_id = $1 AND teacher_id = $2
       GROUP BY title, subject
       ORDER BY MAX(generated_at) DESC`,
      [params.data.classId, request.user.sub]
    );

    return {
      sets: result.rows.map((r) => ({
        title: r.title,
        subject: r.subject,
        totalMarks: Number(r.total_marks),
        papers: Number(r.papers),
        drafts: Number(r.drafts),
        shared: Number(r.shared),
      })),
    };
  });

  // Share every unshared paper in a set with its students
  fastify.post('/classes/:classId/papers/publish', async (request, reply) => {
    const params = ClassParams.safeParse(request.params);
    const body = ShareSchema.safeParse(request.body);
    if (!params.success || !(await ownsClass(request.user, params.data.classId))) {
      reply.code(404);
      return { error: 'Not found', message: 'Class not found.' };
    }
    if (!body.success) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Check the paper title.' };
    }
    const dueDate = body.data.dueDate ? new Date(body.data.dueDate) : null;
    if (dueDate && Number.isNaN(dueDate.getTime())) {
      reply.code(400);
      return { error: 'Invalid request', message: 'That due date is not valid.' };
    }

    const result = await query(
      `UPDATE papers
       SET status = 'published', published_at = NOW(), due_at = $3
       WHERE class_id = $1 AND teacher_id = $2 AND title = $4 AND status = 'draft'
       RETURNING id`,
      [params.data.classId, request.user.sub, dueDate, body.data.title]
    );
    if (result.rowCount === 0) {
      reply.code(409);
      return { error: 'Nothing to share', message: 'There are no unshared papers with that title.' };
    }
    return { published: result.rowCount };
  });
}