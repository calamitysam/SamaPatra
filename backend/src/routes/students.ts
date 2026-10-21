import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { query } from '../db/index.js';
import { requireRole } from '../auth/guards.js';
import { canAccessStudent } from '../auth/access.js';

const IdParams = z.object({ id: z.string().uuid() });

export default async function studentRoutes(fastify: FastifyInstance) {
  // One student: themselves, or a student in the teacher's class
  fastify.get('/students/:id', async (request, reply) => {
    const params = IdParams.safeParse(request.params);
    if (!params.success || !(await canAccessStudent(request.user, params.data.id))) {
      reply.code(404);
      return { error: 'Not found', message: 'Student not found.' };
    }

    const result = await query<{
      id: string;
      name: string;
      grade: number;
      language: string;
      accessibility_needs: string[];
      current_level: Record<string, unknown> | null;
    }>(
      `SELECT s.id, u.name, s.grade, s.language, s.accessibility_needs, s.current_level
       FROM students s
       JOIN users u ON u.id = s.user_id
       WHERE s.id = $1`,
      [params.data.id]
    );
    if (result.rows.length === 0) {
      reply.code(404);
      return { error: 'Not found', message: 'Student not found.' };
    }

    const s = result.rows[0];
    return {
      student: {
        id: s.id,
        name: s.name,
        grade: s.grade,
        language: s.language,
        accessibilityNeeds: s.accessibility_needs,
        currentLevel: s.current_level ?? {},
      },
    };
  });

  // Delete a student completely: every class, their papers and their level
  fastify.delete(
    '/students/:id',
    { preHandler: requireRole('teacher', 'admin') },
    async (request, reply) => {
      const params = IdParams.safeParse(request.params);
      if (!params.success || !(await canAccessStudent(request.user, params.data.id))) {
        reply.code(404);
        return { error: 'Not found', message: 'Student not found.' };
      }

      // A student who is also in another teacher's class is not ours to delete
      const elsewhere = await query(
        `SELECT 1
         FROM class_enrollments ce
         JOIN classes c ON c.id = ce.class_id
         WHERE ce.student_id = $1 AND c.teacher_id <> $2
         LIMIT 1`,
        [params.data.id, request.user.sub]
      );
      if (request.user.role !== 'admin' && elsewhere.rows.length > 0) {
        reply.code(409);
        return {
          error: 'Shared student',
          message: 'This student is also in another teacher’s class, so only an admin can delete them.',
        };
      }

      // Deleting the sign-in account removes the student, their classes, papers and level with it
      const result = await query(
        `DELETE FROM users
         WHERE id = (SELECT user_id FROM students WHERE id = $1) AND role = 'student'
         RETURNING id`,
        [params.data.id]
      );
      if (result.rowCount === 0) {
        reply.code(404);
        return { error: 'Not found', message: 'Student not found.' };
      }
      return { success: true };
    }
  );
}