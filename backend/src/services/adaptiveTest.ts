import { query } from '../db/index.js';
import { isChoiceCorrect, keyStatus } from './answerKey.js';
import type { Question } from '../types/schema.js';

export interface AdaptiveTestQuestion {
  questionId: string;
  difficulty: number;
  wasCorrect?: boolean;
}

export interface AdaptiveTestResult {
  estimatedLevel: number;
  questionsAttempted: number;
  correctAnswers: number;
  questionSequence: AdaptiveTestQuestion[];
}

export class AdaptiveTestService {
  /**
   * Select the next question near the current estimated level.
   * Only multiple-choice questions with a checked answer key are used, so a wrong key
   * can't drag a student's level up or down. Figure questions can be skipped.
   */
  async selectNextQuestion(
    subject: string,
    grade: number,
    currentLevel: number,
    previousQuestions: string[],
    skipFigures = false
  ): Promise<Question | null> {
    // Widen the search if there is nothing close to the level
    for (const radius of [1, 2, 4]) {
      // difficulty is a whole number, so the limits must be whole numbers too
      const low = Math.max(1, Math.floor(currentLevel - radius));
      const high = Math.min(5, Math.ceil(currentLevel + radius));

      const result = await query<Question>(
        `SELECT * FROM questions
         WHERE subject = $1
           AND grade = $2
           AND difficulty BETWEEN $3 AND $4
           AND type = 'mcq'
           AND COALESCE(content->>'correct_answer', '') <> ''
           AND COALESCE(metadata->>'answerVerified', 'true') <> 'false'
           AND id <> ALL($5::uuid[])
           AND ($6::boolean = false OR COALESCE(metadata->>'needsFigure', 'false') <> 'true')
         ORDER BY RANDOM()
         LIMIT 1`,
        [subject, grade, low, high, previousQuestions, skipFigures]
      );

      if (result.rows[0]) return result.rows[0];
    }
    return null;
  }

  /**
   * Calculate new estimated level based on answer correctness
   * Simple adaptive algorithm: adjust by 0.5 in the direction of performance
   */
  calculateNewLevel(
    currentLevel: number,
    questionDifficulty: number,
    wasCorrect: boolean
  ): number {
    if (wasCorrect) {
      if (questionDifficulty >= currentLevel) {
        return Math.min(5, currentLevel + 0.5);
      }
      return Math.min(5, currentLevel + 0.3);
    } else {
      if (questionDifficulty <= currentLevel) {
        return Math.max(1, currentLevel - 0.5);
      }
      return Math.max(1, currentLevel - 0.3);
    }
  }

  /**
   * Not used for real tests. A real test is interactive, one answer at a time,
   * through selectNextQuestion and processAnswer. This only walks the question picker.
   */
  async conductAdaptiveTest(
    studentId: string,
    subject: string,
    grade: number
  ): Promise<AdaptiveTestResult> {
    const maxQuestions = 10;
    const currentLevel = 3;
    const questionSequence: AdaptiveTestQuestion[] = [];
    const previousQuestions: string[] = [];

    for (let i = 0; i < maxQuestions; i++) {
      const question = await this.selectNextQuestion(subject, grade, currentLevel, previousQuestions);
      if (!question) break;
      previousQuestions.push(question.id);
      questionSequence.push({ questionId: question.id, difficulty: question.difficulty });
    }

    return {
      estimatedLevel: currentLevel,
      questionsAttempted: questionSequence.length,
      correctAnswers: 0,
      questionSequence,
    };
  }

  /**
   * Process a student's answer and update the level estimate
   */
  async processAnswer(
    questionId: string,
    studentAnswer: string,
    currentLevel: number
  ): Promise<{ isCorrect: boolean; newLevel: number; feedback?: string }> {
    const result = await query<Question>('SELECT * FROM questions WHERE id = $1', [questionId]);
    if (result.rows.length === 0) {
      throw new Error('Question not found');
    }
    const question = result.rows[0];

    if (question.type !== 'mcq' || keyStatus(question) !== 'ready') {
      throw new Error('This question can’t be marked automatically');
    }

    const isCorrect = isChoiceCorrect(studentAnswer, question.content.correct_answer);
    const newLevel = this.calculateNewLevel(currentLevel, question.difficulty, isCorrect);

    return {
      isCorrect,
      newLevel,
      feedback: isCorrect ? 'Correct!' : 'Not quite right.',
    };
  }

  /**
   * Save adaptive test result to database
   */
  async saveTestResult(
    studentId: string,
    subject: string,
    result: AdaptiveTestResult
  ): Promise<void> {
    await query(
      `INSERT INTO adaptive_test_results
       (student_id, subject, estimated_level, questions_attempted, correct_answers, question_sequence)
       VALUES ($1, $2, $3, $4, $5, $6)`,
      [
        studentId,
        subject,
        result.estimatedLevel,
        result.questionsAttempted,
        result.correctAnswers,
        JSON.stringify(result.questionSequence),
      ]
    );

    // Update student's current level for this subject
    await query(
      `UPDATE students
       SET current_level = jsonb_set(
         COALESCE(current_level, '{}'::jsonb),
         $1,
         $2::text::jsonb
       )
       WHERE id = $3`,
      [`{${subject}}`, result.estimatedLevel.toString(), studentId]
    );
  }
}

export const adaptiveTestService = new AdaptiveTestService();