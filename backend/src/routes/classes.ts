import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { query, transaction } from '../db/index.js';
import { generateJoinCode } from '../auth/codes.js';

const CreateClassSchema = z.object({
  name: z.string().trim().min(1).max(100),
  grade: z.number().int().min(9).max(10),
  subject: z.string().trim().min(1).max(100),
});

const UpdateClassSchema = z.object({
  name: z.string().trim().min(1).max(100).optional(),
  grade: z.number().int().min(9).max(10).optional(),
  subject: z.string().trim().min(1).max(100).optional(),
});

const IdParams = z.object({ id: z.string().uuid() });
const DeleteQuery = z.object({ deleteStudents: z.enum(['true', 'false']).optional() });

type DeleteOutcome =
  | { kind: 'missing' }
  | { kind: 'orphans'; count: number }
  | { kind: 'deleted' };

async function ownedClass(user: { sub: string; role: string }, id: string): Promise<boolean> {
  const result = await query(
    `SELECT 1 FROM classes WHERE id = $1 AND (teacher_id = $2 OR $3 = 'admin')`,
    [id, user.sub, user.role]
  );
  return result.rows.length > 0;
}

export default async function classRoutes(fastify: FastifyInstance) {
  // Create class (the teacher is whoever is signed in)
  fastify.post('/classes', async (request, reply) => {
    const validation = CreateClassSchema.safeParse(request.body);
    if (!validation.success) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Check the class details.' };
    }

    const { name, grade, subject } = validation.data;
    const teacherId = request.user.sub;

    for (let attempt = 0; attempt < 5; attempt++) {
      try {
        const result = await query<{ id: string; join_code: string }>(
          `INSERT INTO classes (teacher_id, name, grade, subject, join_code)
           VALUES ($1, $2, $3, $4, $5)
           RETURNING id, join_code`,
          [teacherId, name, grade, subject, generateJoinCode()]
        );
        reply.code(201);
        return { classId: result.rows[0].id, joinCode: result.rows[0].join_code };
      } catch (error: any) {
        if (error.code !== '23505') throw error; // code already taken, try another
      }
    }

    reply.code(500);
    return { error: 'Failed', message: 'Could not make a class code. Please try again.' };
  });

  // One of the teacher's own classes
  fastify.get('/classes/:id', async (request, reply) => {
    const params = IdParams.safeParse(request.params);
    if (!params.success) {
      reply.code(404);
      return { error: 'Not found', message: 'Class not found.' };
    }

    const result = await query<{
      id: string;
      name: string;
      grade: number;
      subject: string;
      join_code: string | null;
      teacher_name: string;
    }>(
      `SELECT c.id, c.name, c.grade, c.subject, c.join_code, u.name AS teacher_name
       FROM classes c
       JOIN users u ON u.id = c.teacher_id
       WHERE c.id = $1 AND (c.teacher_id = $2 OR $3 = 'admin')`,
      [params.data.id, request.user.sub, request.user.role]
    );
    if (result.rows.length === 0) {
      reply.code(404);
      return { error: 'Not found', message: 'Class not found.' };
    }

    const c = result.rows[0];
    return {
      class: {
        id: c.id,
        name: c.name,
        grade: c.grade,
        subject: c.subject,
        joinCode: c.join_code ?? '',
        teacherName: c.teacher_name,
      },
    };
  });

  // A teacher's classes, newest first. Only your own.
  fastify.get('/teachers/:teacherId/classes', async (request, reply) => {
    const params = z.object({ teacherId: z.string().uuid() }).safeParse(request.params);
    if (
      !params.success ||
      (request.user.role !== 'admin' && params.data.teacherId !== request.user.sub)
    ) {
      reply.code(403);
      return { error: 'Forbidden', message: 'You can only see your own classes.' };
    }

    const result = await query(
      `SELECT c.*,
        (SELECT COUNT(*) FROM class_enrollments WHERE class_id = c.id) as student_count
       FROM classes c
       WHERE c.teacher_id = $1
       ORDER BY c.created_at DESC`,
      [params.data.teacherId]
    );
    return { classes: result.rows };
  });

  // Update one of your classes
  fastify.put('/classes/:id', async (request, reply) => {
    const params = IdParams.safeParse(request.params);
    const body = UpdateClassSchema.safeParse(request.body);
    if (!params.success || !body.success) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Check the class details.' };
    }

    const updates: string[] = [];
    const values: any[] = [];
    let paramCount = 1;
    if (body.data.name !== undefined) {
      updates.push(`name = $${paramCount++}`);
      values.push(body.data.name);
    }
    if (body.data.grade !== undefined) {
      updates.push(`grade = $${paramCount++}`);
      values.push(body.data.grade);
    }
    if (body.data.subject !== undefined) {
      updates.push(`subject = $${paramCount++}`);
      values.push(body.data.subject);
    }
    if (updates.length === 0) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Nothing to change.' };
    }

    updates.push('updated_at = NOW()');
    values.push(params.data.id, request.user.sub, request.user.role);

    const result = await query(
      `UPDATE classes SET ${updates.join(', ')}
       WHERE id = $${paramCount}
         AND (teacher_id = $${paramCount + 1} OR $${paramCount + 2} = 'admin')
       RETURNING id`,
      values
    );
    if (result.rowCount === 0) {
      reply.code(404);
      return { error: 'Not found', message: 'Class not found.' };
    }
    return { success: true };
  });

  // What deleting a class would do, so the app can say so before anything is removed
  fastify.get('/classes/:id/delete-preview', async (request, reply) => {
    const params = IdParams.safeParse(request.params);
    if (!params.success || !(await ownedClass(request.user, params.data.id))) {
      reply.code(404);
      return { error: 'Not found', message: 'Class not found.' };
    }
    const id = params.data.id;

    const students = await query<{ students: string; only_here: string }>(
      `SELECT COUNT(*) AS students,
              COUNT(*) FILTER (WHERE NOT EXISTS (
                SELECT 1 FROM class_enrollments o
                WHERE o.student_id = ce.student_id AND o.class_id <> ce.class_id
              )) AS only_here
       FROM class_enrollments ce
       WHERE ce.class_id = $1`,
      [id]
    );
    const papers = await query<{ drafts: string; shared: string }>(
      `SELECT COUNT(*) FILTER (WHERE status = 'draft') AS drafts,
              COUNT(*) FILTER (WHERE status <> 'draft') AS shared
       FROM papers
       WHERE class_id = $1`,
      [id]
    );

    return {
      students: Number(students.rows[0].students),
      onlyHere: Number(students.rows[0].only_here),
      draftPapers: Number(papers.rows[0].drafts),
      sharedPapers: Number(papers.rows[0].shared),
    };
  });

  // Delete one of your classes. Students who are only in it must be deleted with it, or moved first.
  fastify.delete('/classes/:id', async (request, reply) => {
    const params = IdParams.safeParse(request.params);
    const options = DeleteQuery.safeParse(request.query);
    if (!params.success || !options.success) {
      reply.code(404);
      return { error: 'Not found', message: 'Class not found.' };
    }
    const id = params.data.id;
    const alsoDeleteStudents = options.data.deleteStudents === 'true';

    const outcome = await transaction<DeleteOutcome>(async (client) => {
      const found = await client.query(
        `SELECT id FROM classes WHERE id = $1 AND (teacher_id = $2 OR $3 = 'admin') FOR UPDATE`,
        [id, request.user.sub, request.user.role]
      );
      if (found.rows.length === 0) return { kind: 'missing' };

      const onlyHere = await client.query<{ user_id: string }>(
        `SELECT s.user_id
         FROM class_enrollments ce
         JOIN students s ON s.id = ce.student_id
         WHERE ce.class_id = $1
           AND NOT EXISTS (
             SELECT 1 FROM class_enrollments o
             WHERE o.student_id = ce.student_id AND o.class_id <> ce.class_id
           )`,
        [id]
      );
      if (onlyHere.rows.length > 0 && !alsoDeleteStudents) {
        return { kind: 'orphans', count: onlyHere.rows.length };
      }

      // Papers made for this class that were never shared go with it. Shared ones stay with their students.
      await client.query(`DELETE FROM papers WHERE class_id = $1 AND status = 'draft'`, [id]);
      if (onlyHere.rows.length > 0) {
        await client.query('DELETE FROM users WHERE id = ANY($1::uuid[])', [
          onlyHere.rows.map((r) => r.user_id),
        ]);
      }
      await client.query('DELETE FROM classes WHERE id = $1', [id]);
      return { kind: 'deleted' };
    });

    if (outcome.kind === 'missing') {
      reply.code(404);
      return { error: 'Not found', message: 'Class not found.' };
    }
    if (outcome.kind === 'orphans') {
      reply.code(409);
      return {
        error: 'Students would be left without a class',
        message:
          outcome.count === 1
            ? '1 student is only in this class. Add them to another class first, or delete them with the class.'
            : `${outcome.count} students are only in this class. Add them to another class first, or delete them with the class.`,
      };
    }
    return { success: true };
  });
}