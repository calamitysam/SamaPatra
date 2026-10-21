import { z } from 'zod';

// User roles
export enum UserRole {
  TEACHER = 'teacher',
  STUDENT = 'student',
  ADMIN = 'admin'
}

// Languages supported
export enum Language {
  ENGLISH = 'en',
  HINDI = 'hi',
  KANNADA = 'kn'
}

// Accessibility needs
export enum AccessibilityNeed {
  BLIND = 'blind',
  LOW_VISION = 'low_vision',
  DYSLEXIA = 'dyslexia',
  ADHD = 'adhd',
  MOTOR_DIFFICULTY = 'motor_difficulty',
  NONE = 'none'
}

// Question types
export enum QuestionType {
  MCQ = 'mcq',
  SHORT_ANSWER = 'short_answer',
  LONG_ANSWER = 'long_answer',
  ASSERTION_REASON = 'assertion_reason',
  CASE_STUDY = 'case_study'
}

// Difficulty levels (1-5)
export const DifficultyLevel = z.number().int().min(1).max(5);

// User schema
export const UserSchema = z.object({
  id: z.string().uuid(),
  email: z.string().email(),
  name: z.string(),
  role: z.nativeEnum(UserRole),
  created_at: z.date(),
  updated_at: z.date()
});

export type User = z.infer<typeof UserSchema>;

// Student schema
export const StudentSchema = z.object({
  id: z.string().uuid(),
  user_id: z.string().uuid(),
  grade: z.number().int().min(9).max(10),
  language: z.nativeEnum(Language),
  accessibility_needs: z.array(z.nativeEnum(AccessibilityNeed)),
  accessibility_profile: z.record(z.any()).optional(),
  current_level: z.record(z.number()).optional(), // subject -> level mapping
  created_at: z.date(),
  updated_at: z.date()
});

export type Student = z.infer<typeof StudentSchema>;

// Question schema
export const QuestionSchema = z.object({
  id: z.string().uuid(),
  type: z.nativeEnum(QuestionType),
  grade: z.number().int().min(9).max(10),
  subject: z.string(),
  chapter: z.string(),
  difficulty: DifficultyLevel,
  marks: z.number().positive(),
  content: z.object({
    text: z.string(),
    options: z.array(z.string()).optional(), // for MCQ
    correct_answer: z.string().optional(), // for MCQ
    model_answer: z.string().optional(), // for descriptive
    image_url: z.string().optional(),
    marking_scheme: z.string().optional()
  }),
  metadata: z.record(z.any()).optional(),
  created_at: z.date(),
  updated_at: z.date()
});

export type Question = z.infer<typeof QuestionSchema>;

// Question version (rephrased for specific language/need)
export const QuestionVersionSchema = z.object({
  id: z.string().uuid(),
  base_question_id: z.string().uuid(),
  language: z.nativeEnum(Language),
  accessibility_context: z.string().optional(),
  content: z.object({
    text: z.string(),
    options: z.array(z.string()).optional(),
    image_url: z.string().optional()
  }),
  created_at: z.date()
});

export type QuestionVersion = z.infer<typeof QuestionVersionSchema>;

// Paper schema
export const PaperSchema = z.object({
  id: z.string().uuid(),
  student_id: z.string().uuid(),
  teacher_id: z.string().uuid(),
  title: z.string(),
  subject: z.string(),
  grade: z.number().int().min(9).max(10),
  total_marks: z.number().positive(),
  duration_minutes: z.number().int().positive().optional(),
  questions: z.array(z.object({
    question_version_id: z.string().uuid(),
    base_question_id: z.string().uuid(),
    sequence: z.number().int(),
    marks: z.number().positive()
  })),
  status: z.enum(['draft', 'published', 'completed', 'graded']),
  generated_at: z.date(),
  published_at: z.date().optional(),
  due_at: z.date().optional()
});

export type Paper = z.infer<typeof PaperSchema>;

// Student answer schema
export const AnswerSchema = z.object({
  id: z.string().uuid(),
  paper_id: z.string().uuid(),
  student_id: z.string().uuid(),
  question_version_id: z.string().uuid(),
  base_question_id: z.string().uuid(),
  answer_text: z.string().optional(),
  answer_audio_url: z.string().optional(),
  marks_obtained: z.number().optional(),
  ai_suggested_marks: z.number().optional(),
  ai_feedback: z.string().optional(),
  teacher_feedback: z.string().optional(),
  is_graded: z.boolean().default(false),
  answered_at: z.date(),
  graded_at: z.date().optional()
});

export type Answer = z.infer<typeof AnswerSchema>;

// Class schema
export const ClassSchema = z.object({
  id: z.string().uuid(),
  teacher_id: z.string().uuid(),
  name: z.string(),
  grade: z.number().int().min(9).max(10),
  subject: z.string(),
  created_at: z.date(),
  updated_at: z.date()
});

export type Class = z.infer<typeof ClassSchema>;

// Adaptive test result
export const AdaptiveTestResultSchema = z.object({
  student_id: z.string().uuid(),
  subject: z.string(),
  estimated_level: z.number().min(1).max(5),
  questions_attempted: z.number().int(),
  correct_answers: z.number().int(),
  completed_at: z.date()
});

export type AdaptiveTestResult = z.infer<typeof AdaptiveTestResultSchema>;
