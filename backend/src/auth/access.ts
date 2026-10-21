import { query } from '../db/index.js';

export interface AuthUser {
  sub: string;
  role: 'teacher' | 'student' | 'admin';
  studentId?: string;
}

/** Students can see themselves. Teachers can see students in their own classes. */
export async function canAccessStudent(user: AuthUser, studentId: string): Promise<boolean> {
  if (user.role === 'admin') return true;
  if (user.role === 'student') return user.studentId === studentId;
  const result = await query(
    `SELECT 1
     FROM class_enrollments ce
     JOIN classes c ON c.id = ce.class_id
     WHERE ce.student_id = $1 AND c.teacher_id = $2
     LIMIT 1`,
    [studentId, user.sub]
  );
  return result.rows.length > 0;
}

/** The paper, if this teacher made it. */
export async function findOwnedPaper(
  user: AuthUser,
  paperId: string
): Promise<{ status: string } | null> {
  const result = await query<{ status: string }>(
    `SELECT status FROM papers WHERE id = $1 AND (teacher_id = $2 OR $3 = 'admin')`,
    [paperId, user.sub, user.role]
  );
  return result.rows[0] ?? null;
}

/** The answer's paper, if this teacher made it. */
export async function findOwnedAnswer(
  user: AuthUser,
  answerId: string
): Promise<{ paperId: string; paperStatus: string } | null> {
  const result = await query<{ paper_id: string; status: string }>(
    `SELECT p.id AS paper_id, p.status
     FROM answers a
     JOIN papers p ON p.id = a.paper_id
     WHERE a.id = $1 AND (p.teacher_id = $2 OR $3 = 'admin')`,
    [answerId, user.sub, user.role]
  );
  const row = result.rows[0];
  return row ? { paperId: row.paper_id, paperStatus: row.status } : null;
}

/** True if this teacher owns the class (admins own everything). */
export async function ownsClass(user: AuthUser, classId: string): Promise<boolean> {
  const result = await query(
    `SELECT 1 FROM classes WHERE id = $1 AND (teacher_id = $2 OR $3 = 'admin')`,
    [classId, user.sub, user.role]
  );
  return result.rows.length > 0;
}
