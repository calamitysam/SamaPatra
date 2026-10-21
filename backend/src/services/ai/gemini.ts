import { GoogleGenerativeAI, HarmCategory, HarmBlockThreshold } from '@google/generative-ai';
import type { AIProvider, AIConfig, GenerateOptions } from './types.js';
import { withRetry, RateLimiter } from './types.js';

// One limiter for every Gemini call in the app, so the free-tier limit is shared.
let sharedLimiter: RateLimiter | undefined;
function getLimiter(): RateLimiter {
  if (!sharedLimiter) {
    sharedLimiter = new RateLimiter(parseInt(process.env.AI_RATE_LIMIT_PER_MINUTE || '10'));
  }
  return sharedLimiter;
}

/** True for errors that usually clear up when we wait or use another model. */
function isBusy(error: unknown): boolean {
  const status = (error as { status?: number } | null)?.status;
  return status === 503 || status === 429 || status === 500;
}

export class GeminiProvider implements AIProvider {
  private genAI: GoogleGenerativeAI;
  private model: string;
  private fallbackModel?: string;
  private rateLimiter: RateLimiter;
  private maxRetries: number;
  private retryDelay: number;

  constructor(config: AIConfig, fallbackModel?: string) {
    this.genAI = new GoogleGenerativeAI(config.apiKey);
    this.model = config.model;
    this.fallbackModel = fallbackModel && fallbackModel !== config.model ? fallbackModel : undefined;
    this.maxRetries = config.maxRetries || 3;
    this.retryDelay = config.retryDelay || 1000;
    this.rateLimiter = getLimiter();
  }

  /** Tries the main model, and if it stays busy, the backup model. */
  private async callWithFallback<T>(makeCall: (modelName: string) => Promise<T>): Promise<T> {
    const fallback = this.fallbackModel;
    if (!fallback) {
      return withRetry(() => makeCall(this.model), this.maxRetries, this.retryDelay);
    }
    try {
      return await withRetry(() => makeCall(this.model), 2, this.retryDelay);
    } catch (error) {
      if (!isBusy(error)) throw error;
      console.warn(`AI model ${this.model} is busy. Trying ${fallback} instead.`);
      return withRetry(() => makeCall(fallback), this.maxRetries, this.retryDelay);
    }
  }

  async generateText(prompt: string, options?: GenerateOptions): Promise<string> {
    return this.rateLimiter.execute(async () => {
      return this.callWithFallback(async (modelName) => {
        const model = this.genAI.getGenerativeModel({
          model: modelName,
          generationConfig: {
            temperature: options?.temperature ?? 0.7,
            maxOutputTokens: options?.maxTokens ?? 2048,
            stopSequences: options?.stopSequences,
          },
          safetySettings: [
            {
              category: HarmCategory.HARM_CATEGORY_HARASSMENT,
              threshold: HarmBlockThreshold.BLOCK_MEDIUM_AND_ABOVE,
            },
            {
              category: HarmCategory.HARM_CATEGORY_HATE_SPEECH,
              threshold: HarmBlockThreshold.BLOCK_MEDIUM_AND_ABOVE,
            },
          ],
        });

        const fullPrompt = options?.systemPrompt
          ? `${options.systemPrompt}\n\n${prompt}`
          : prompt;

        const result = await model.generateContent(fullPrompt);
        const response = await result.response;
        return response.text();
      });
    });
  }

  async generateStructured<T>(
    prompt: string,
    schema: any,
    options?: GenerateOptions
  ): Promise<T> {
    return this.rateLimiter.execute(async () => {
      return this.callWithFallback(async (modelName) => {
        const model = this.genAI.getGenerativeModel({
          model: modelName,
          generationConfig: {
            temperature: options?.temperature ?? 0.5,
            maxOutputTokens: options?.maxTokens ?? 4096,
            responseMimeType: 'application/json',
            responseSchema: schema,
          },
        });

        const fullPrompt = options?.systemPrompt
          ? `${options.systemPrompt}\n\n${prompt}`
          : prompt;

        const result = await model.generateContent(fullPrompt);
        const response = await result.response;
        return JSON.parse(response.text()) as T;
      });
    });
  }

  /**
   * Reads a PDF directly, as it looks on the page (maths, tables and layout kept),
   * and returns JSON. This one is Gemini-specific. If you ever switch provider,
   * this is the function to replace.
   */
  async generateStructuredFromPdf<T>(
    pdf: Buffer,
    prompt: string,
    schema: any,
    options?: GenerateOptions
  ): Promise<T> {
    return this.rateLimiter.execute(async () => {
      return this.callWithFallback(async (modelName) => {
        const model = this.genAI.getGenerativeModel({
          model: modelName,
          generationConfig: {
            temperature: options?.temperature ?? 0.2,
            maxOutputTokens: options?.maxTokens ?? 32768,
            responseMimeType: 'application/json',
            responseSchema: schema,
          },
        });

        const fullPrompt = options?.systemPrompt
          ? `${options.systemPrompt}\n\n${prompt}`
          : prompt;

        const result = await model.generateContent([
          { inlineData: { mimeType: 'application/pdf', data: pdf.toString('base64') } },
          { text: fullPrompt },
        ]);
        const response = await result.response;
        return JSON.parse(response.text()) as T;
      });
    });
  }

  estimateTokens(text: string): number {
    // Rough estimation: ~4 characters per token for English
    // For Hindi/Kannada, might be different but this is a safe estimate
    return Math.ceil(text.length / 4);
  }
}

export function createGeminiProvider(): GeminiProvider {
  const apiKey = process.env.GOOGLE_AI_API_KEY;
  if (!apiKey) {
    throw new Error('GOOGLE_AI_API_KEY environment variable is required');
  }

  const model = process.env.GOOGLE_AI_MODEL || 'gemini-3.5-flash';
  const fallback = (process.env.GOOGLE_AI_FALLBACK_MODEL ?? 'gemini-3.1-flash-lite').trim();

  return new GeminiProvider(
    {
      apiKey,
      model,
      maxRetries: parseInt(process.env.AI_RETRY_MAX_ATTEMPTS || '3'),
      retryDelay: parseInt(process.env.AI_RETRY_DELAY_MS || '1000'),
    },
    fallback === '' || fallback.toLowerCase() === 'none' ? undefined : fallback
  );
}

// Factory function to create AI provider
export function createAIProvider(): AIProvider {
  return createGeminiProvider();
}