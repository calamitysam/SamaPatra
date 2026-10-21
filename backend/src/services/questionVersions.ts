import { query } from '../db/index.js';
import { adaptQuestions, type LanguageCode } from './questionAdapter.js';
import type { Question } from '../types/schema.js';

export interface VersionText {
  versionId: string;
  text: string;
  options?: string[];
}

/** Reuses saved versions and makes the missing ones, then saves them for next time. */
export async function ensureVersions(
  questions: Question[],
  language: LanguageCode,
  context: string
): Promise<Map<string, VersionText>> {
  const result = new Map<string, VersionText>();
  if (questions.length === 0) return result;

  const existing = await query<{
    id: string;
    base_question_id: string;
    content: { text: string; options?: string[] };
  }>(
    `SELECT id, base_question_id, content FROM question_versions
     WHERE base_question_id = ANY($1::uuid[])
       AND language = $2
       AND accessibility_context = $3`,
    [questions.map((q) => q.id), language, context]
  );
  for (const row of existing.rows) {
    result.set(row.base_question_id, {
      versionId: row.id,
      text: row.content.text,
      options: row.content.options,
    });
  }

  const missing = questions.filter((q) => !result.has(q.id));
  if (missing.length === 0) return result;

  const adapted = await adaptQuestions(
    missing.map((q) => ({
      id: q.id,
      subject: q.subject,
      grade: q.grade,
      text: q.content.text,
      options: q.content.options,
    })),
    language,
    context
  );

  for (const q of missing) {
    const made = adapted.get(q.id);
    if (!made) {
      throw new Error('A question could not be prepared. Please try again.');
    }
    const saved = await query<{ id: string }>(
      `INSERT INTO question_versions (base_question_id, language, accessibility_context, content)
       VALUES ($1, $2, $3, $4)
       ON CONFLICT (base_question_id, language, accessibility_context)
       DO UPDATE SET content = EXCLUDED.content
       RETURNING id`,
      [
        q.id,
        language,
        context,
        JSON.stringify({ text: made.text, options: made.options, image_url: q.content.image_url }),
      ]
    );
    result.set(q.id, { versionId: saved.rows[0].id, text: made.text, options: made.options });
  }
  return result;
}