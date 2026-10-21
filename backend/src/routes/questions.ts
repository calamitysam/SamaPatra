import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { query, transaction } from '../db/index.js';
import { questionParserService } from '../services/questionParser.js';
import { importPaperFromPdf } from '../services/paperImport.js';
import type { Question } from '../types/schema.js';
import { randomUUID } from 'node:crypto';

const QUESTION_TYPES = ['mcq', 'short_answer', 'long_answer', 'assertion_reason', 'case_study'] as const;

const QuestionInputSchema = z.object({
  type: z.enum(QUESTION_TYPES),
  grade: z.number().int().min(9).max(10),
  subject: z.string().trim().min(1).max(100),
  chapter: z.string().trim().min(1).max(255),
  difficulty: z.number().int().min(1).max(5),
  marks: z.number().positive().max(100),
  content: z.object({
    text: z.string().trim().min(1).max(10000),
    options: z.array(z.string().max(1000)).max(8).optional(),
    correct_answer: z.string().max(2000).optional(),
    model_answer: z.string().max(10000).optional(),
    marking_scheme: z.string().max(10000).optional(),
  }),
  metadata: z.record(z.any()).optional(),
});

const BulkImportSchema = z.object({
  paperName: z.string().trim().min(1).max(150),
  questions: z.array(QuestionInputSchema).min(1).max(150),
});

const ParsePaperSchema = z.object({
  paperText: z.string().min(1).max(200000),
  grade: z.number().int().min(9).max(10),
  subject: z.string().trim().min(1).max(100),
});

const ImportFieldsSchema = z.object({
  grade: z.number().int().min(9).max(10),
  subject: z.string().trim().min(1).max(100),
});

const IdParams = z.object({ id: z.string().uuid() });

export default async function questionRoutes(fastify: FastifyInstance) {
  // Get all questions
  fastify.get('/questions', async (request) => {
    const { subject, grade, difficulty } = request.query as Record<string, string | undefined>;

    let queryText = 'SELECT * FROM questions WHERE 1=1';
    const params: any[] = [];
    let paramCount = 1;

    if (subject) {
      queryText += ` AND subject = $${paramCount++}`;
      params.push(subject);
    }
    const gradeNumber = Number.parseInt(grade ?? '', 10);
    if (Number.isFinite(gradeNumber)) {
      queryText += ` AND grade = $${paramCount++}`;
      params.push(gradeNumber);
    }
    const difficultyNumber = Number.parseInt(difficulty ?? '', 10);
    if (Number.isFinite(difficultyNumber)) {
      queryText += ` AND difficulty = $${paramCount++}`;
      params.push(difficultyNumber);
    }

    queryText += ' ORDER BY created_at DESC';

    const result = await query<Question>(queryText, params);
    return { questions: result.rows };
  });

  // Get single question
  fastify.get('/questions/:id', async (request, reply) => {
    const params = IdParams.safeParse(request.params);
    if (!params.success) {
      reply.code(404);
      return { error: 'Not found', message: 'Question not found.' };
    }

    const result = await query<Question>('SELECT * FROM questions WHERE id = $1', [params.data.id]);
    if (result.rows.length === 0) {
      reply.code(404);
      return { error: 'Not found', message: 'Question not found.' };
    }
    return { question: result.rows[0] };
  });

  // Create question manually
  fastify.post('/questions', async (request, reply) => {
    const validation = QuestionInputSchema.safeParse(request.body);
    if (!validation.success) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Check the question details.' };
    }
    const data = validation.data;

    const result = await query<{ id: string }>(
      `INSERT INTO questions
       (type, grade, subject, chapter, difficulty, marks, content, metadata, created_by)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
       RETURNING id`,
      [
        data.type,
        data.grade,
        data.subject,
        data.chapter,
        data.difficulty,
        data.marks,
        JSON.stringify(data.content),
        JSON.stringify(data.metadata ?? {}),
        request.user.sub,
      ]
    );

    reply.code(201);
    return { questionId: result.rows[0].id };
  });

  // Update question (its creator, or an admin)
  fastify.put('/questions/:id', async (request, reply) => {
    const params = IdParams.safeParse(request.params);
    const validation = QuestionInputSchema.safeParse(request.body);
    if (!params.success || !validation.success) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Check the question details.' };
    }
    const data = validation.data;

    const result = await query(
      `UPDATE questions
       SET type = $1, grade = $2, subject = $3, chapter = $4,
           difficulty = $5, marks = $6, content = $7,
           metadata = COALESCE($8::jsonb, metadata), updated_at = NOW()
       WHERE id = $9 AND (created_by = $10 OR created_by IS NULL OR $11 = 'admin')
       RETURNING id`,
      [
        data.type,
        data.grade,
        data.subject,
        data.chapter,
        data.difficulty,
        data.marks,
        JSON.stringify(data.content),
        data.metadata ? JSON.stringify(data.metadata) : null,
        params.data.id,
        request.user.sub,
        request.user.role,
      ]
    );

    if (result.rowCount === 0) {
      reply.code(404);
      return { error: 'Not found', message: 'Question not found, or it belongs to another teacher.' };
    }
    return { success: true };
  });

  // Delete question (its creator, or an admin)
  fastify.delete('/questions/:id', async (request, reply) => {
    const params = IdParams.safeParse(request.params);
    if (!params.success) {
      reply.code(404);
      return { error: 'Not found', message: 'Question not found.' };
    }

    try {
      const result = await query(
        `DELETE FROM questions
         WHERE id = $1 AND (created_by = $2 OR created_by IS NULL OR $3 = 'admin')`,
        [params.data.id, request.user.sub, request.user.role]
      );
      if (result.rowCount === 0) {
        reply.code(404);
        return { error: 'Not found', message: 'Question not found, or it belongs to another teacher.' };
      }
      return { success: true };
    } catch (error: any) {
      if (error.code === '23503') {
        reply.code(409);
        return {
          error: 'In use',
          message: 'Students have already answered this question, so it can’t be deleted.',
        };
      }
      throw error;
    }
  });

  // Read a question paper PDF with AI. Nothing is saved: the teacher reviews the draft first.
  fastify.post('/questions/import-pdf', async (request, reply) => {
    const file = await request.file();
    if (!file) {
      reply.code(400);
      return { error: 'No file', message: 'Attach a PDF file.' };
    }

    // The text fields must be sent before the file
    const fields = file.fields as Record<string, any>;
    const input = ImportFieldsSchema.safeParse({
      grade: Number(fields.grade?.value),
      subject: String(fields.subject?.value ?? ''),
    });
    if (!input.success) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Send the grade (9 or 10) and the subject before the file.' };
    }
    if (file.mimetype !== 'application/pdf') {
      reply.code(400);
      return { error: 'Wrong file', message: 'Only PDF files are accepted.' };
    }

    let buffer: Buffer;
    try {
      buffer = await file.toBuffer();
    } catch {
      reply.code(413);
      return { error: 'Too big', message: 'That file is too big. The limit is 10 MB.' };
    }
    if (buffer.subarray(0, 4).toString() !== '%PDF') {
      reply.code(400);
      return { error: 'Wrong file', message: 'That file is not a valid PDF.' };
    }

    try {
      return await importPaperFromPdf(buffer, input.data.grade, input.data.subject);
    } catch (error) {
      fastify.log.error(error, 'PDF import failed');
      reply.code(502);
      return { error: 'Import failed', message: 'Could not read that paper. Please try again.' };
    }
  });

  // Save reviewed questions to the question bank. All or nothing.
  fastify.post('/questions/bulk-import', async (request, reply) => {
    const validation = BulkImportSchema.safeParse(request.body);
    if (!validation.success) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Check the questions. You can import up to 150 at once.' };
    }
    const importId = randomUUID();
    const ids = await transaction(async (client) => {
      const saved: string[] = [];
      for (const q of validation.data.questions) {
        const result = await client.query<{ id: string }>(
          `INSERT INTO questions
           (type, grade, subject, chapter, difficulty, marks, content, metadata, created_by)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
           RETURNING id`,
          [
            q.type,
            q.grade,
            q.subject,
            q.chapter,
            q.difficulty,
            q.marks,
            JSON.stringify(q.content),
                        JSON.stringify({
              ...(q.metadata ?? {}),
              importId,
              paperName: validation.data.paperName,
            }),
            request.user.sub,
          ]
        );
        saved.push(result.rows[0].id);
      }
      return saved;
    });

    reply.code(201);
    return { imported: ids.length, questionIds: ids, importId };
  });

  // Parse pasted paper text (older route, kept)
  fastify.post('/questions/parse-paper', async (request, reply) => {
    const validation = ParsePaperSchema.safeParse(request.body);
    if (!validation.success) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Send the paper text, grade and subject.' };
    }
    const { paperText, grade, subject } = validation.data;

    try {
      const questions = await questionParserService.parsePaperFromText(paperText, grade, subject);
      return { questions };
    } catch (error) {
      fastify.log.error(error, 'Paper parsing error');
      reply.code(500);
      return { error: 'Failed', message: 'Could not parse that paper. Please try again.' };
    }
  });

  // Generate a model answer for a question
  fastify.post('/questions/:id/generate-model-answer', async (request, reply) => {
    const params = IdParams.safeParse(request.params);
    const body = z
      .object({ language: z.enum(['en', 'hi', 'kn']).optional() })
      .safeParse(request.body ?? {});
    if (!params.success || !body.success) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Check the request.' };
    }
    const { id } = params.data;

    const questionResult = await query<Question>('SELECT * FROM questions WHERE id = $1', [id]);
    if (questionResult.rows.length === 0) {
      reply.code(404);
      return { error: 'Not found', message: 'Question not found.' };
    }

    try {
      const modelAnswer = await questionParserService.generateModelAnswer(
        questionResult.rows[0],
        (body.data.language ?? 'en') as any
      );

      await query(
        `UPDATE questions
         SET content = jsonb_set(content, '{model_answer}', $1::jsonb)
         WHERE id = $2`,
        [JSON.stringify(modelAnswer), id]
      );
      return { modelAnswer };
    } catch (error) {
      fastify.log.error(error, 'Model answer generation error');
      reply.code(500);
      return { error: 'Failed', message: 'Could not write a model answer. Please try again.' };
    }
  });
}