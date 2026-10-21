import type { FastifyInstance } from 'fastify';
import { query } from '../db/index.js';
import { requireRole } from '../auth/guards.js';
import { SettingsSchema, effectiveSettings, readOverrides } from '../support/settings.js';

interface StudentRow {
  grade: number;
  language: string;
  accessibility_needs: string[];
  accessibility_profile: unknown;
  current_level: Record<string, unknown> | null;
}

async function loadStudent(studentId: string | undefined): Promise<StudentRow | undefined> {
  if (!studentId) return undefined;
  const result = await query<StudentRow>(
    `SELECT grade, language, accessibility_needs, accessibility_profile, current_level
     FROM students WHERE id = $1`,
    [studentId]
  );
  return result.rows[0];
}

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {};
}

export default async function meRoutes(fastify: FastifyInstance) {
  // The signed-in student's current settings
  fastify.get('/me/settings', { preHandler: requireRole('student') }, async (request, reply) => {
    const row = await loadStudent(request.user.studentId);
    if (!row) {
      reply.code(404);
      return { error: 'Not found', message: 'Student not found.' };
    }
    return { settings: effectiveSettings(row.accessibility_needs, row.accessibility_profile) };
  });

  // Everything the student's home screen needs
  fastify.get('/me/home', { preHandler: requireRole('student') }, async (request, reply) => {
    const studentId = request.user.studentId;
    const row = await loadStudent(studentId);
    if (!row || !studentId) {
      reply.code(404);
      return { error: 'Not found', message: 'Student not found.' };
    }

    const classes = await query<{ subject: string }>(
      `SELECT DISTINCT c.subject
       FROM class_enrollments ce
       JOIN classes c ON c.id = ce.class_id
       WHERE ce.student_id = $1
       ORDER BY c.subject`,
      [studentId]
    );

    const levels: Record<string, number> = {};
    for (const [subject, value] of Object.entries(row.current_level ?? {})) {
      const n = Number(value);
      if (Number.isFinite(n)) levels[subject] = n;
    }

    return {
      language: row.language,
      grade: row.grade,
      subjects: classes.rows.map((c) => c.subject),
      levels,
      settings: effectiveSettings(row.accessibility_needs, row.accessibility_profile),
    };
  });

  // The student changes their own settings. Their choices beat the teacher's defaults.
  fastify.put('/me/preferences', { preHandler: requireRole('student') }, async (request, reply) => {
    const body = SettingsSchema.partial().strict().safeParse(request.body);
    if (!body.success) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Those settings are not valid.' };
    }

    const studentId = request.user.studentId;
    const row = await loadStudent(studentId);
    if (!row || !studentId) {
      reply.code(404);
      return { error: 'Not found', message: 'Student not found.' };
    }

    const profile = asRecord(row.accessibility_profile);
    const nextProfile = {
      ...profile,
      overrides: { ...readOverrides(profile), ...body.data },
    };

    await query(
      'UPDATE students SET accessibility_profile = $1, updated_at = NOW() WHERE id = $2',
      [JSON.stringify(nextProfile), studentId]
    );
    return { settings: effectiveSettings(row.accessibility_needs, nextProfile) };
  });

  // Go back to what the teacher chose
  fastify.delete('/me/preferences', { preHandler: requireRole('student') }, async (request, reply) => {
    const studentId = request.user.studentId;
    const row = await loadStudent(studentId);
    if (!row || !studentId) {
      reply.code(404);
      return { error: 'Not found', message: 'Student not found.' };
    }

    const nextProfile = { ...asRecord(row.accessibility_profile) };
    delete nextProfile.overrides;

    await query(
      'UPDATE students SET accessibility_profile = $1, updated_at = NOW() WHERE id = $2',
      [JSON.stringify(nextProfile), studentId]
    );
    return { settings: effectiveSettings(row.accessibility_needs, nextProfile) };
  });
}