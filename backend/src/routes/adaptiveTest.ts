import type { FastifyInstance } from 'fastify';
import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import { query } from '../db/index.js';
import { adaptiveTestService, type AdaptiveTestQuestion } from '../services/adaptiveTest.js';
import { ensureVersions } from '../services/questionVersions.js';
import type { LanguageCode } from '../services/questionAdapter.js';
import { requireRole } from '../auth/guards.js';
import { canAccessStudent } from '../auth/access.js';
import { effectiveSettings, wordingContext } from '../support/settings.js';
import type { Question } from '../types/schema.js';

const MIN_QUESTIONS = 8;
const MAX_QUESTIONS = 10;
const EARLY_FINISH_MIN = 5;
const SESSION_MS = 45 * 60 * 1000;

const StartTestSchema = z.object({ subject: z.string().trim().min(1).max(100) });
const AnswerSchema = z.object({
  testSessionId: z.string().uuid(),
  questionId: z.string().uuid(),
  answer: z.string().trim().min(1).max(200),
});
const CompleteSchema = z.object({ testSessionId: z.string().uuid() });

/** Everything about a running test is kept here, never trusted from the app. */
interface TestSession {
  id: string;
  studentId: string;
  subject: string;
  grade: number;
  language: LanguageCode;
  context: string;
  skipFigures: boolean;
  level: number;
  levels: number[];
  shown: string[];
  sequence: AdaptiveTestQuestion[];
  currentQuestionId: string | null;
  busy: boolean;
  lastTouched: number;
}

const sessions = new Map<string, TestSession>();

function dropExpired(): void {
  const now = Date.now();
  for (const [id, session] of sessions) {
    if (now - session.lastTouched > SESSION_MS) sessions.delete(id);
  }
}

function toLanguage(value: string): LanguageCode {
  return value === 'hi' || value === 'kn' ? value : 'en';
}

function roundHalf(value: number): number {
  return Math.round(value * 2) / 2;
}

async function presentable(session: TestSession, question: Question) {
  const versions = await ensureVersions([question], session.language, session.context);
  const version = versions.get(question.id);
  if (!version) throw new Error('A question could not be prepared. Please try again.');
  return {
    id: question.id,
    text: version.text,
    options: version.options,
    marks: Number(question.marks),
  };
}

async function finish(session: TestSession, sequence: AdaptiveTestQuestion[], level: number) {
  const answered = sequence.filter((q) => q.wasCorrect !== undefined);
  const attempted = answered.length;
  const correct = answered.filter((q) => q.wasCorrect).length;
  const estimatedLevel = roundHalf(level);

  await adaptiveTestService.saveTestResult(session.studentId, session.subject, {
    estimatedLevel,
    questionsAttempted: attempted,
    correctAnswers: correct,
    questionSequence: answered,
  });
  sessions.delete(session.id);

  return {
    estimatedLevel,
    questionsAttempted: attempted,
    correctAnswers: correct,
    accuracy: attempted > 0 ? Math.round((correct / attempted) * 100) : 0,
  };
}

export default async function adaptiveTestRoutes(fastify: FastifyInstance) {
  // A student starts a level check for a subject
  fastify.post('/adaptive-test/start', { preHandler: requireRole('student') }, async (request, reply) => {
    const body = StartTestSchema.safeParse(request.body);
    const studentId = request.user.studentId;
    if (!body.success || !studentId) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Choose a subject.' };
    }
    const { subject } = body.data;

    const found = await query<{
      grade: number;
      language: string;
      accessibility_needs: string[];
      accessibility_profile: unknown;
      current_level: Record<string, unknown> | null;
    }>(
      `SELECT grade, language, accessibility_needs, accessibility_profile, current_level
       FROM students WHERE id = $1`,
      [studentId]
    );
    const row = found.rows[0];
    if (!row) {
      reply.code(404);
      return { error: 'Not found', message: 'Student not found.' };
    }

    const settings = effectiveSettings(row.accessibility_needs, row.accessibility_profile);
    const known = Number(row.current_level?.[subject]);
    const level = Number.isFinite(known) && known >= 1 ? Math.min(5, roundHalf(known)) : 3;
    const skipFigures = settings.audioFirst;

    const first = await adaptiveTestService.selectNextQuestion(subject, row.grade, level, [], skipFigures);
    if (!first) {
      reply.code(404);
      return {
        error: 'No questions',
        message: 'There are no level-check questions for this subject yet. Please ask your teacher.',
      };
    }

    dropExpired();
    for (const [id, existing] of sessions) {
      if (existing.studentId === studentId && existing.subject === subject) sessions.delete(id);
    }

    const session: TestSession = {
      id: randomUUID(),
      studentId,
      subject,
      grade: row.grade,
      language: toLanguage(row.language),
      context: wordingContext(settings),
      skipFigures,
      level,
      levels: [],
      shown: [],
      sequence: [],
      currentQuestionId: null,
      busy: false,
      lastTouched: Date.now(),
    };

    try {
      const question = await presentable(session, first);
      session.shown.push(first.id);
      session.sequence.push({ questionId: first.id, difficulty: first.difficulty });
      session.currentQuestionId = first.id;
      sessions.set(session.id, session);
      return { testSessionId: session.id, questionNumber: 1, totalQuestions: MAX_QUESTIONS, question };
    } catch (error) {
      fastify.log.error(error, 'Level check could not start');
      reply.code(502);
      return { error: 'Try again', message: 'Couldn’t get your first question ready. Please try again.' };
    }
  });

  // A student answers the current question and gets the next one
  fastify.post('/adaptive-test/answer', { preHandler: requireRole('student') }, async (request, reply) => {
    const body = AnswerSchema.safeParse(request.body);
    if (!body.success) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Choose an answer.' };
    }
    const { testSessionId, questionId, answer } = body.data;

    dropExpired();
    const session = sessions.get(testSessionId);
    if (!session || session.studentId !== request.user.studentId) {
      reply.code(404);
      return { error: 'Not found', message: 'This level check has timed out. Please start it again.' };
    }
    if (session.busy) {
      reply.code(409);
      return { error: 'Busy', message: 'One moment, your last answer is still being checked.' };
    }
    if (session.currentQuestionId !== questionId) {
      reply.code(409);
      return { error: 'Wrong question', message: 'That isn’t the question you are on.' };
    }

    session.busy = true;
    try {
      const checked = await adaptiveTestService.processAnswer(questionId, answer, session.level);
      const sequence = session.sequence.map((q) =>
        q.questionId === questionId ? { ...q, wasCorrect: checked.isCorrect } : q
      );
      const levels = [...session.levels, checked.newLevel];
      const answeredCount = sequence.filter((q) => q.wasCorrect !== undefined).length;

      // Stop after the most questions, or once the level has settled
      const recent = levels.slice(-3);
      const settled =
        answeredCount >= MIN_QUESTIONS &&
        recent.length === 3 &&
        Math.max(...recent) - Math.min(...recent) <= 0.5;

      let next: Question | null = null;
      if (answeredCount < MAX_QUESTIONS && !settled) {
        next = await adaptiveTestService.selectNextQuestion(
          session.subject,
          session.grade,
          checked.newLevel,
          session.shown,
          session.skipFigures
        );
      }

      if (!next) {
        const result = await finish(session, sequence, checked.newLevel);
        return { finished: true, wasCorrect: checked.isCorrect, feedback: checked.feedback, result };
      }

      // Everything that can fail has happened. Only now change the session.
      const nextQuestion = await presentable(session, next);
      session.sequence = [...sequence, { questionId: next.id, difficulty: next.difficulty }];
      session.levels = levels;
      session.level = checked.newLevel;
      session.shown.push(next.id);
      session.currentQuestionId = next.id;
      session.lastTouched = Date.now();

      return {
        finished: false,
        wasCorrect: checked.isCorrect,
        feedback: checked.feedback,
        questionNumber: answeredCount + 1,
        totalQuestions: MAX_QUESTIONS,
        nextQuestion,
      };
    } catch (error) {
      fastify.log.error(error, 'Level check answer failed');
      reply.code(502);
      return { error: 'Try again', message: 'Something went wrong. Please send your answer again.' };
    } finally {
      session.busy = false;
    }
  });

  // A student stops early. Needs a few answers first.
  fastify.post('/adaptive-test/complete', { preHandler: requireRole('student') }, async (request, reply) => {
    const body = CompleteSchema.safeParse(request.body);
    if (!body.success) {
      reply.code(400);
      return { error: 'Invalid request', message: 'Check the request.' };
    }

    dropExpired();
    const session = sessions.get(body.data.testSessionId);
    if (!session || session.studentId !== request.user.studentId) {
      reply.code(404);
      return { error: 'Not found', message: 'This level check has timed out. Please start it again.' };
    }
    const attempted = session.sequence.filter((q) => q.wasCorrect !== undefined).length;
    if (attempted < EARLY_FINISH_MIN) {
      reply.code(409);
      return { error: 'Too early', message: 'Answer a few more questions first.' };
    }
    if (session.busy) {
      reply.code(409);
      return { error: 'Busy', message: 'One moment, your last answer is still being checked.' };
    }

    session.busy = true;
    try {
      const result = await finish(session, session.sequence, session.level);
      return { success: true, result };
    } catch (error) {
      fastify.log.error(error, 'Level check could not be saved');
      reply.code(502);
      return { error: 'Try again', message: 'Couldn’t save your result. Please try again.' };
    } finally {
      session.busy = false;
    }
  });

  // A student's past level checks (the student, or their teacher)
  fastify.get('/adaptive-test/history/:studentId', async (request, reply) => {
    const params = z.object({ studentId: z.string().uuid() }).safeParse(request.params);
    if (!params.success || !(await canAccessStudent(request.user, params.data.studentId))) {
      reply.code(404);
      return { error: 'Not found', message: 'Student not found.' };
    }

    const result = await query<{
      id: string;
      subject: string;
      estimated_level: string;
      questions_attempted: number;
      correct_answers: number;
      completed_at: Date;
    }>(
      `SELECT id, subject, estimated_level, questions_attempted, correct_answers, completed_at
       FROM adaptive_test_results
       WHERE student_id = $1
       ORDER BY completed_at DESC`,
      [params.data.studentId]
    );

    return {
      tests: result.rows.map((r) => ({
        id: r.id,
        subject: r.subject,
        estimatedLevel: Number(r.estimated_level),
        questionsAttempted: r.questions_attempted,
        correctAnswers: r.correct_answers,
        completedAt: r.completed_at,
      })),
    };
  });

  // A student's current level in one subject
  fastify.get('/adaptive-test/level/:studentId/:subject', async (request, reply) => {
    const params = z
      .object({ studentId: z.string().uuid(), subject: z.string().trim().min(1).max(100) })
      .safeParse(request.params);
    if (!params.success || !(await canAccessStudent(request.user, params.data.studentId))) {
      reply.code(404);
      return { error: 'Not found', message: 'Student not found.' };
    }

    const result = await query<{ current_level: Record<string, unknown> | null }>(
      'SELECT current_level FROM students WHERE id = $1',
      [params.data.studentId]
    );
    if (result.rows.length === 0) {
      reply.code(404);
      return { error: 'Not found', message: 'Student not found.' };
    }

    const raw = Number(result.rows[0].current_level?.[params.data.subject]);
    const currentLevel = Number.isFinite(raw) ? raw : null;
    return {
      subject: params.data.subject,
      currentLevel,
      hasCompletedTest: currentLevel !== null,
    };
  });
}