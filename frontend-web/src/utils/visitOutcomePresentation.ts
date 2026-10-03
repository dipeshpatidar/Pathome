import type { GroundVisitOutcomeItem, GroundVisitOutcomeReport } from '../services/visitExecutionService';

export const outcomeSkipReasons = [
  { value: 'PROPERTY_UNAVAILABLE', label: 'Property unavailable' },
  { value: 'ACCESS_DENIED', label: 'Could not access property' },
  { value: 'TENANT_DECLINED', label: 'Tenant chose not to view' },
  { value: 'TENANT_LEFT_EARLY', label: 'Tenant ended the visit early' },
  { value: 'PROPERTY_MISMATCH', label: 'Property details did not match' },
  { value: 'OTHER', label: 'Other' }
] as const;

export const recordedOutcomeCount = (report: GroundVisitOutcomeReport): number =>
  report.items.filter(item => item.outcome !== 'UNRECORDED').length;

export const canSubmitOutcomeReport = (report: GroundVisitOutcomeReport): boolean =>
  report.reportState === 'OPEN' && report.items.length > 0 && report.items.every(item => item.outcome !== 'UNRECORDED');

export const canSaveSkip = (reason: GroundVisitOutcomeItem['skipReason'], privateNote: string): boolean =>
  reason !== null && reason !== undefined && (reason !== 'OTHER' || privateNote.trim().length > 0);

export const outcomeStateLabel = (outcome: GroundVisitOutcomeItem['outcome']): string => {
  if (outcome === 'VISITED') return 'Viewed';
  if (outcome === 'SKIPPED') return 'Not viewed';
  return 'Needs an outcome';
};

export const skipReasonLabel = (reason: GroundVisitOutcomeItem['skipReason']): string =>
  outcomeSkipReasons.find(option => option.value === reason)?.label ?? 'Not viewed';

export const outcomeSaveFailureAction = (status?: number): 'REFRESH' | 'RETRY' | 'ACCESS_LOST' =>
  status === 409 ? 'REFRESH' : status === 403 ? 'ACCESS_LOST' : 'RETRY';

export const physicalFinishAvailable = (sessionState: string): boolean => sessionState === 'STARTED';
