import { z } from 'zod';

/** What a teacher can tick. "none" means nothing chosen yet. */
export const NeedsSchema = z
  .array(z.enum(['blind', 'low_vision', 'dyslexia', 'adhd', 'motor_difficulty', 'none']))
  .min(1)
  .max(6);

export const SettingsSchema = z.object({
  textScale: z.number().min(1).max(2),
  lineSpacing: z.enum(['normal', 'wide']),
  readableFont: z.boolean(),
  tintedBackground: z.boolean(),
  highContrast: z.boolean(),
  reduceMotion: z.boolean(),
  oneQuestionPerScreen: z.boolean(),
  readAloudButton: z.boolean(),
  audioFirst: z.boolean(),
  voiceAnswers: z.boolean(),
  largeTouchTargets: z.boolean(),
  // Wording switches: these need the AI to make a different version of a question
  plainLanguage: z.boolean(),
  conciseText: z.boolean(),
  listenableWording: z.boolean(),
});

export type Settings = z.infer<typeof SettingsSchema>;

type FlagKey = Exclude<keyof Settings, 'textScale' | 'lineSpacing'>;

const FLAG_KEYS: FlagKey[] = [
  'readableFont',
  'tintedBackground',
  'highContrast',
  'reduceMotion',
  'oneQuestionPerScreen',
  'readAloudButton',
  'audioFirst',
  'voiceAnswers',
  'largeTouchTargets',
  'plainLanguage',
  'conciseText',
  'listenableWording',
];

export const DEFAULT_SETTINGS: Settings = {
  textScale: 1,
  lineSpacing: 'normal',
  readableFont: false,
  tintedBackground: false,
  highContrast: false,
  reduceMotion: false,
  oneQuestionPerScreen: false,
  readAloudButton: false,
  audioFirst: false,
  voiceAnswers: false,
  largeTouchTargets: false,
  plainLanguage: false,
  conciseText: false,
  listenableWording: false,
};

// Starting points only. A student can switch any of these on or off themselves.
const BUNDLES: Record<string, Partial<Settings>> = {
  blind: {
    audioFirst: true,
    readAloudButton: true,
    voiceAnswers: true,
    oneQuestionPerScreen: true,
    largeTouchTargets: true,
    listenableWording: true,
  },
  low_vision: {
    textScale: 1.5,
    highContrast: true,
    lineSpacing: 'wide',
    readAloudButton: true,
    largeTouchTargets: true,
  },
  dyslexia: {
    readableFont: true,
    lineSpacing: 'wide',
    tintedBackground: true,
    readAloudButton: true,
    plainLanguage: true,
  },
  adhd: {
    oneQuestionPerScreen: true,
    reduceMotion: true,
    conciseText: true,
  },
  motor_difficulty: {
    largeTouchTargets: true,
    voiceAnswers: true,
  },
};

/** Combines the bundles of every ticked support. Switches add up, the biggest text size wins. */
export function bundleFor(needs: readonly string[]): Settings {
  const result: Settings = { ...DEFAULT_SETTINGS };
  for (const need of needs) {
    const bundle = BUNDLES[need];
    if (!bundle) continue;
    if (bundle.textScale !== undefined) {
      result.textScale = Math.max(result.textScale, bundle.textScale);
    }
    if (bundle.lineSpacing === 'wide') result.lineSpacing = 'wide';
    for (const key of FLAG_KEYS) {
      if (bundle[key]) result[key] = true;
    }
  }
  return result;
}

/** The student's own saved choices, stored in accessibility_profile.overrides. */
export function readOverrides(profile: unknown): Partial<Settings> {
  if (typeof profile !== 'object' || profile === null) return {};
  const raw = (profile as Record<string, unknown>).overrides;
  const parsed = SettingsSchema.partial().strict().safeParse(raw);
  return parsed.success ? parsed.data : {};
}

/** Teacher-ticked supports first, then the student's own choices on top. */
export function effectiveSettings(needs: readonly string[], profile: unknown): Settings {
  return { ...bundleFor(needs), ...readOverrides(profile) } as Settings;
}

/**
 * Only the wording switches need a different AI-written version of a question.
 * Everything else is display only, so any mix of supports shares a few cached versions.
 */
export function wordingContext(settings: Settings): string {
  const parts: string[] = [];
  if (settings.listenableWording) parts.push('audio');
  if (settings.plainLanguage) parts.push('plain');
  if (settings.conciseText) parts.push('concise');
  return parts.length > 0 ? parts.join('+') : 'standard';
}