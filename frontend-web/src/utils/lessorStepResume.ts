import type { LessorDraftData } from '../services/lessorDraftService.ts';
import { pricingReady } from './lessorConfiguration.ts';
import { locationValidationError } from './lessorLocationState.ts';

export type LessorResumeStep = 'basics' | 'pricing' | 'location' | 'media' | 'details' | 'preview';

const STEPS: readonly LessorResumeStep[] = ['basics', 'pricing', 'location', 'media', 'details', 'preview'];

export function lessorStepStorageKey(draftId: string, userId: number | null, guest: boolean): string | null {
  if (guest) return `pathome_guest_step_${draftId}`;
  return userId === null ? null : `pathome_lessor_step_${userId}_${draftId}`;
}

/** Restores only as far as the draft's current data still allows the existing flow to reach. */
export function resolveLessorResumeStep(
  savedStep: string | null,
  data: LessorDraftData,
  supportedCities: string[],
  hasReadyCover: boolean
): LessorResumeStep {
  const targetIndex = STEPS.indexOf(savedStep as LessorResumeStep);
  if (targetIndex < 0) return 'basics';

  let resolved: LessorResumeStep = 'basics';
  for (const next of STEPS.slice(1, targetIndex + 1)) {
    if (next === 'pricing' && !data.basics?.bhkCount) break;
    if (next === 'location' && !pricingReady(data.pricing?.monthlyRent ?? null, data.pricing?.securityDeposit ?? null)) break;
    if (next === 'media' && (!data.location || locationValidationError(data.location, supportedCities))) break;
    if (next === 'details' && !hasReadyCover) break;
    if (next === 'preview' && !data.details?.availableFrom) break;
    resolved = next;
  }
  return resolved;
}
