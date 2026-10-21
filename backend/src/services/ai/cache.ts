import { query } from '../../db/index.js';
import crypto from 'crypto';

export interface CacheOptions {
  ttlHours?: number;
  forceRefresh?: boolean;
}

export class AICache {
  private enabled: boolean;
  private defaultTTL: number;

  constructor() {
    this.enabled = process.env.ENABLE_AI_CACHE === 'true';
    this.defaultTTL = parseInt(process.env.CACHE_TTL_HOURS || '24');
  }

  private generateCacheKey(prompt: string, context?: Record<string, any>): string {
    const data = context ? `${prompt}:${JSON.stringify(context)}` : prompt;
    return crypto.createHash('sha256').update(data).digest('hex');
  }

  async get<T>(
    prompt: string,
    context?: Record<string, any>
  ): Promise<T | null> {
    if (!this.enabled) return null;

    const cacheKey = this.generateCacheKey(prompt, context);

    try {
      const result = await query<{ response: any }>(
        `SELECT response FROM ai_cache
         WHERE cache_key = $1
         AND expires_at > NOW()`,
        [cacheKey]
      );

      if (result.rows.length > 0) {
        console.log('AI cache hit:', cacheKey.substring(0, 8));
        return result.rows[0].response as T;
      }
    } catch (error) {
      console.error('Cache retrieval error:', error);
    }

    return null;
  }

  async set(
    prompt: string,
    response: any,
    provider: string,
    model: string,
    context?: Record<string, any>,
    options?: CacheOptions
  ): Promise<void> {
    if (!this.enabled) return;

    const cacheKey = this.generateCacheKey(prompt, context);
    const promptHash = crypto.createHash('md5').update(prompt).digest('hex');
    const ttlHours = options?.ttlHours ?? this.defaultTTL;

    try {
      await query(
        `INSERT INTO ai_cache (cache_key, prompt_hash, response, provider, model, expires_at)
         VALUES ($1, $2, $3, $4, $5, NOW() + INTERVAL '${ttlHours} hours')
         ON CONFLICT (cache_key)
         DO UPDATE SET
           response = EXCLUDED.response,
           expires_at = EXCLUDED.expires_at`,
        [cacheKey, promptHash, JSON.stringify(response), provider, model]
      );
      console.log('AI cache set:', cacheKey.substring(0, 8));
    } catch (error) {
      console.error('Cache storage error:', error);
    }
  }

  async clear(olderThanHours?: number): Promise<void> {
    try {
      if (olderThanHours) {
        await query(
          `DELETE FROM ai_cache WHERE created_at < NOW() - INTERVAL '${olderThanHours} hours'`
        );
      } else {
        await query('DELETE FROM ai_cache WHERE expires_at < NOW()');
      }
      console.log('AI cache cleared');
    } catch (error) {
      console.error('Cache clear error:', error);
    }
  }
}

export const aiCache = new AICache();
