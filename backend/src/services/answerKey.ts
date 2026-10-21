/** Reads a multiple-choice answer such as "B", "b", "(B)", "B)" or "B) 3x + 1" as a letter. */
export function choiceLetter(value: unknown): string | null {
  const match = String(value ?? '')
    .trim()
    .match(/^\(?([A-Ha-h])(?:[).:]|\s|$)/);
  return match ? match[1].toUpperCase() : null;
}

/** Compares a student's choice with the key. Letters are compared when both can be read as letters. */
export function isChoiceCorrect(studentAnswer: unknown, correctAnswer: unknown): boolean {
  const key = String(correctAnswer ?? '').trim();
  if (!key) return false;
  const keyLetter = choiceLetter(key);
  const givenLetter = choiceLetter(studentAnswer);
  if (keyLetter && givenLetter) return keyLetter === givenLetter;
  return String(studentAnswer ?? '').trim().toLowerCase() === key.toLowerCase();
}

export type KeyStatus = 'ready' | 'missing' | 'unverified';

/** Whether a question's answer key can be used for marking. */
export function keyStatus(question: {
  content: { correct_answer?: string | null };
  metadata?: Record<string, any> | null;
}): KeyStatus {
  if (!question.content.correct_answer?.trim()) return 'missing';
  // Keys written by the AI are marked answerVerified = false until a teacher confirms them
  if (question.metadata?.answerVerified === false) return 'unverified';
  return 'ready';
}