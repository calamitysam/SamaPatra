import { aiService } from './ai/index.js';

export type LanguageCode = 'en' | 'hi' | 'kn';

export interface AdaptInput {
  id: string;
  subject: string;
  grade: number;
  text: string;
  options?: string[];
}

export interface AdaptOutput {
  id: string;
  text: string;
  options?: string[];
}

const LANGUAGE_NAMES: Record<LanguageCode, string> = {
  en: 'English',
  hi: 'Hindi',
  kn: 'Kannada',
};

// Small batches keep each AI reply short enough to finish. Kannada text is long.
const CHUNK_SIZE = 6;

const RESPONSE_SCHEMA = {
  type: 'array',
  items: {
    type: 'object',
    properties: {
      id: { type: 'string' },
      text: { type: 'string' },
      options: { type: 'array', items: { type: 'string' } },
    },
    required: ['id', 'text'],
  },
};

/** The context is 'standard' or wording switches joined by +, like 'audio+plain'. */
function wordingRules(context: string): string[] {
  const parts = context.split('+');
  const rules: string[] = [];
  if (parts.includes('audio')) {
    rules.push(
      'The question will be read aloud by a screen reader. Write mathematical symbols and expressions as words wherever a listener could misunderstand them (for example "x squared" or "three over four"), and keep the sentence order easy to follow by ear.'
    );
  }
  if (parts.includes('plain')) {
    rules.push(
      'Use short, simple sentences in the active voice and everyday words. Keep every subject term the student must know (for example "polynomial" or "hypotenuse").'
    );
  }
  if (parts.includes('concise')) {
    rules.push(
      'Remove filler words. Split long scenarios into short sentences. Keep every number, condition and instruction.'
    );
  }
  return rules;
}

function looksValid(chunk: AdaptInput[], output: any): boolean {
  if (!Array.isArray(output) || output.length !== chunk.length) return false;
  return chunk.every((q) => {
    const o = output.find((x: any) => x && x.id === q.id);
    if (!o || typeof o.text !== 'string' || o.text.trim() === '') return false;
    if (q.options && q.options.length > 0) {
      return (
        Array.isArray(o.options) &&
        o.options.length === q.options.length &&
        o.options.every((x: unknown) => typeof x === 'string' && x.trim() !== '')
      );
    }
    return true;
  });
}

async function adaptChunk(
  chunk: AdaptInput[],
  language: LanguageCode,
  context: string
): Promise<AdaptOutput[]> {
  const rules = wordingRules(context);
  const systemPrompt = `You are an experienced CBSE ${chunk[0].subject} teacher who writes fluently in English, Hindi and Kannada.
You rewrite exam questions for individual students without changing what is being asked.
Rules that always apply:
- Keep every number, unit, variable name, formula and condition exactly the same.
- Keep the same difficulty and the same instruction words (for example find, prove, show that, calculate).
- Never include or hint at the answer.
- Keep multiple-choice options in the same order and the same count, with the same A), B), C), D) labels.
- Write mathematical notation the way a CBSE paper would in the target language.
${rules.map((r) => `- ${r}`).join('\n')}
Return ONLY a JSON array.`;

  const payload = chunk.map((q) => ({ id: q.id, text: q.text, options: q.options }));
  const prompt = `Rewrite these Grade ${chunk[0].grade} questions in ${LANGUAGE_NAMES[language]}.

Questions (JSON):
${JSON.stringify(payload)}

Return a JSON array with one object per question: {"id": the same id, "text": the rewritten question, "options": the rewritten options, only if the question has options}.`;

  for (let attempt = 1; attempt <= 2; attempt++) {
    const output = await aiService.generateStructured<AdaptOutput[]>(prompt, RESPONSE_SCHEMA, {
      systemPrompt,
      temperature: 0.3,
      maxTokens: 8192,
      useCache: attempt === 1,
      cacheContext: { ids: chunk.map((q) => q.id).join(','), language, context },
    });
    if (looksValid(chunk, output)) {
      return chunk.map((q) => {
        const o = output.find((x) => x.id === q.id)!;
        return { id: q.id, text: o.text.trim(), options: o.options?.map((x) => x.trim()) };
      });
    }
  }
  throw new Error(
    `Could not prepare these questions in ${LANGUAGE_NAMES[language]}. Please try again.`
  );
}

/** One AI call per small batch, never one per question. English standard needs no AI. */
export async function adaptQuestions(
  inputs: AdaptInput[],
  language: LanguageCode,
  context: string
): Promise<Map<string, AdaptOutput>> {
  const results = new Map<string, AdaptOutput>();

  if (language === 'en' && context === 'standard') {
    for (const q of inputs) results.set(q.id, { id: q.id, text: q.text, options: q.options });
    return results;
  }

  for (let start = 0; start < inputs.length; start += CHUNK_SIZE) {
    const adapted = await adaptChunk(inputs.slice(start, start + CHUNK_SIZE), language, context);
    for (const item of adapted) results.set(item.id, item);
  }
  return results;
}