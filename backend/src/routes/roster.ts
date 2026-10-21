import type { FastifyInstance } from 'fastify';
import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import { query, transaction } from '../db/index.js';
import { hashSecret } from '../auth/passwords.js';
import { generatePin } from '../auth/codes.js';
import { canAccessStudent } from '../auth/access.js';
import { NeedsSchema } from '../support/settings.js';

const NewStudentSchema = z.object({
  name: z.string().trim().min(2).max(100),
  grade: z.number().int().min(9).max(10).optional(), // the class decides the grade
  language: z.enum(['en', 'hi', 'kn']),
  accessibilityNeeds: z
    .array(z.enum(['blind', 'low_vision', 'dyslexia', 'adhd', 'motor_difficulty', 'none']))
    .min(1)
    .default(['none']),
  alsoClassIds: z.array(z.string().uuid()).max(20).default([]),
});

const ClassParams = z.object({ classId: z.string().uuid() });
const StudentParams = z.object({ classId: z.string().uuid(), studentId: z.string().uuid() });

export default async function rosterRoutes(fastify: FastifyInstance) {
  // Teacher views one of their own classes, with its students
  fastify.get('/classes/:classId/roster', async (request, reply) => {
    const params = ClassParams.safeParse(request.params);
    if (!params.success) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Class not found.' };
    }
    const { classId } = params.data;

    const cls = await query<{
      id: string;
      name: string;
      grade: number;
      subject: string;
      join_code: string | null;
    }>(
      'SELECT id, name, grade, subject, join_code FROM classes WHERE id = $1 AND teacher_id = $2',
      [classId, request.user.sub]
    );
    if (cls.rows.length === 0) {
      reply.code(404);
      return { error: 'Not found', message: 'Class not found.' };
    }

    const students = await query<{
      id: string;
      name: string;
      grade: number;
      language: string;
      accessibility_needs: string[];
      current_level: Record<string, unknown> | null;
      class_ids: string[];
    }>(
      `SELECT s.id, u.name, s.grade, s.language, s.accessibility_needs, s.current_level,
              (SELECT COALESCE(array_agg(ce2.class_id::text), '{}'::text[])
               FROM class_enrollments ce2
               JOIN classes c2 ON c2.id = ce2.class_id
               WHERE ce2.student_id = s.id AND c2.teacher_id = $2) AS class_ids
       FROM class_enrollments ce
       JOIN students s ON s.id = ce.student_id
       JOIN users u ON u.id = s.user_id
       WHERE ce.class_id = $1
       ORDER BY u.name`,
      [classId, request.user.sub]
    );

    const c = cls.rows[0];
    return {
      class: {
        id: c.id,
        name: c.name,
        grade: c.grade,
        subject: c.subject,
        joinCode: c.join_code ?? '',
      },
      students: students.rows.map((s) => {
        const raw = Number(s.current_level?.[c.subject]);
        return {
          id: s.id,
          name: s.name,
          grade: s.grade,
          language: s.language,
          accessibilityNeeds: s.accessibility_needs,
          level: Number.isFinite(raw) && raw >= 1 ? raw : null,
          classIds: s.class_ids,
        };
      }),
    };
  });

  // Teacher adds a new student to a class, and to more of their classes of the same grade.
  // The PIN is shown once.
  fastify.post('/classes/:classId/students', async (request, reply) => {
    const params = ClassParams.safeParse(request.params);
    const body = NewStudentSchema.safeParse(request.body);
    if (!params.success || !body.success) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Check the student’s details.' };
    }
    const { classId } = params.data;
    const { name, language, accessibilityNeeds, alsoClassIds } = body.data;

    const mainClass = await query<{ grade: number }>(
      'SELECT grade FROM classes WHERE id = $1 AND teacher_id = $2',
      [classId, request.user.sub]
    );
    if (mainClass.rows.length === 0) {
      reply.code(404);
      return { error: 'Not found', message: 'Class not found.' };
    }
    const grade = mainClass.rows[0].grade;

    // The other classes must be this teacher's own, for the same grade
    const extraIds = [...new Set(alsoClassIds)].filter((id) => id !== classId);
    if (extraIds.length > 0) {
      const allowed = await query<{ id: string }>(
        'SELECT id FROM classes WHERE id = ANY($1::uuid[]) AND teacher_id = $2 AND grade = $3',
        [extraIds, request.user.sub, grade]
      );
      if (allowed.rows.length !== extraIds.length) {
        reply.code(400);
        return {
          error: 'Invalid request',
          message: 'One of those classes isn’t yours, or it is for a different grade.',
        };
      }
    }

    const pin = generatePin();
    const pinHash = await hashSecret(pin);
    const everyClass = [classId, ...extraIds];

    const studentId = await transaction(async (client) => {
      // Students sign in by class code and PIN, so their email is only a placeholder.
      const email = `student-${randomUUID()}@students.samapatra.invalid`;
      const user = await client.query<{ id: string }>(
        `INSERT INTO users (email, password_hash, name, role)
         VALUES ($1, $2, $3, 'student') RETURNING id`,
        [email, pinHash, name]
      );
      const student = await client.query<{ id: string }>(
        `INSERT INTO students (user_id, grade, language, accessibility_needs)
         VALUES ($1, $2, $3, $4) RETURNING id`,
        [user.rows[0].id, grade, language, accessibilityNeeds]
      );
      for (const id of everyClass) {
        await client.query(
          'INSERT INTO class_enrollments (class_id, student_id) VALUES ($1, $2)',
          [id, student.rows[0].id]
        );
      }
      return student.rows[0].id;
    });

    reply.code(201);
    return { studentId, name, pin, classes: everyClass.length };
  });

  // Teacher chooses which of their classes a student is in
  fastify.put('/students/:studentId/classes', async (request, reply) => {
    const params = z.object({ studentId: z.string().uuid() }).safeParse(request.params);
    const body = z
      .object({ classIds: z.array(z.string().uuid()).min(1).max(20) })
      .safeParse(request.body);
    if (!params.success || !body.success) {
      reply.code(400);
      return {
        error: 'Invalid request',
        message: 'Choose at least one class. To remove a student completely, delete them instead.',
      };
    }
    const { studentId } = params.data;
    if (!(await canAccessStudent(request.user, studentId))) {
      reply.code(404);
      return { error: 'Not found', message: 'Student not found.' };
    }

    const student = await query<{ grade: number }>('SELECT grade FROM students WHERE id = $1', [
      studentId,
    ]);
    if (student.rows.length === 0) {
      reply.code(404);
      return { error: 'Not found', message: 'Student not found.' };
    }

    const wanted = [...new Set(body.data.classIds)];
    const allowed = await query<{ id: string }>(
      'SELECT id FROM classes WHERE id = ANY($1::uuid[]) AND teacher_id = $2 AND grade = $3',
      [wanted, request.user.sub, student.rows[0].grade]
    );
    if (allowed.rows.length !== wanted.length) {
      reply.code(400);
      return {
        error: 'Invalid request',
        message: 'One of those classes isn’t yours, or it is for a different grade.',
      };
    }

    await transaction(async (client) => {
      // Take them out of this teacher's other classes, then put them in the chosen ones
      await client.query(
        `DELETE FROM class_enrollments
         WHERE student_id = $1
           AND class_id IN (SELECT id FROM classes WHERE teacher_id = $2)
           AND class_id <> ALL($3::uuid[])`,
        [studentId, request.user.sub, wanted]
      );
      for (const id of wanted) {
        await client.query(
          `INSERT INTO class_enrollments (class_id, student_id)
           VALUES ($1, $2)
           ON CONFLICT (class_id, student_id) DO NOTHING`,
          [id, studentId]
        );
      }
    });
    return { success: true };
  });

  // Teacher gives a student a new PIN
  fastify.post('/classes/:classId/students/:studentId/reset-pin', async (request, reply) => {
    const params = StudentParams.safeParse(request.params);
    if (!params.success) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Student not found.' };
    }
    const { classId, studentId } = params.data;

    const found = await query<{ user_id: string }>(
      `SELECT s.user_id
       FROM class_enrollments ce
       JOIN classes c ON c.id = ce.class_id
       JOIN students s ON s.id = ce.student_id
       WHERE ce.class_id = $1 AND ce.student_id = $2 AND c.teacher_id = $3`,
      [classId, studentId, request.user.sub]
    );
    if (found.rows.length === 0) {
      reply.code(404);
      return { error: 'Not found', message: 'Student not found in your class.' };
    }

    const pin = generatePin();
    await query('UPDATE users SET password_hash = $1 WHERE id = $2', [
      await hashSecret(pin),
      found.rows[0].user_id,
    ]);
    return { pin };
  });

  // Teacher changes which supports a student has
  fastify.put('/classes/:classId/students/:studentId/supports', async (request, reply) => {
    const params = StudentParams.safeParse(request.params);
    const body = z.object({ accessibilityNeeds: NeedsSchema }).safeParse(request.body);
    if (!params.success || !body.success) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Check the supports you chose.' };
    }
    const { classId, studentId } = params.data;

    const result = await query(
      `UPDATE students s
       SET accessibility_needs = $1, updated_at = NOW()
       FROM class_enrollments ce
       JOIN classes c ON c.id = ce.class_id
       WHERE s.id = $2 AND ce.student_id = s.id AND ce.class_id = $3 AND c.teacher_id = $4
       RETURNING s.id`,
      [body.data.accessibilityNeeds, studentId, classId, request.user.sub]
    );
    if (result.rows.length === 0) {
      reply.code(404);
      return { error: 'Not found', message: 'Student not found in your class.' };
    }
    return { success: true };
  });
}