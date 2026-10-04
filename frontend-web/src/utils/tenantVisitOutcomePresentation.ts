import type { TenantVisitOutcome, TenantVisitOutcomeProperty } from '../services/visitExecutionService';

const lifecycleLabels: Record<string, string> = {
  BEING_ARRANGED: 'Visit being arranged',
  ACTION_REQUIRED: 'Confirmation needed',
  UPCOMING: 'Confirmed guided visit',
  IN_PROGRESS: 'Visit in progress',
  DETAILS_PENDING: 'Visit ended · details pending',
  RESULTS_NOT_RECORDED: 'Results not recorded',
  COMPLETED: 'Visit completed · All homes viewed',
  PARTIALLY_COMPLETED: 'Visit completed · Some homes viewed',
  NO_PROPERTIES_VIEWED: 'Visit completed · No homes viewed',
  OUTCOME_REVIEW_REQUIRED: 'Visit details under review',
  ARRIVAL_REVIEW: 'Arrival under review',
  NO_SHOW: 'Visit marked no-show',
  CANCELLED: 'Visit cancelled',
  EXPIRED: 'Visit expired',
  INTERRUPTED: 'Visit interrupted',
  RECOVERY_REQUIRED: 'Visit update in progress'
};

const physicalLabels: Record<string, string> = {
  DRAFT: 'Visit being arranged',
  SCHEDULED: 'Scheduled',
  STARTED: 'In progress',
  COMPLETED: 'Visit ended',
  EXPIRED: 'Expired',
  CANCELLED: 'Cancelled',
  NO_SHOW: 'No-show',
  PROVISIONAL_NO_SHOW: 'Under attendance review',
  REPAIR_REQUIRED: 'Time under review',
  INTERRUPTED: 'Interrupted'
};

export const tenantVisitOutcomeStatusLabel = (outcome?: TenantVisitOutcome | null, physicalState?: string): string =>
  outcome ? lifecycleLabels[outcome.lifecycle] ?? 'Visit details' : physicalLabels[physicalState || ''] ?? 'Visit details';

export const tenantVisitOutcomeSummaryText = (outcome: TenantVisitOutcome): string => {
  if (outcome.lifecycle === 'DETAILS_PENDING') return 'Your Ground Executive is recording the visit outcome.';
  if (outcome.lifecycle === 'RESULTS_NOT_RECORDED') return 'Results were not recorded for this visit.';
  if (outcome.viewedProperties !== null && outcome.totalProperties !== null) {
    return `${outcome.viewedProperties} of ${outcome.totalProperties} homes viewed.`;
  }
  if (outcome.lifecycle === 'IN_PROGRESS') return 'Property results will appear after visit details are recorded.';
  return 'No property outcome details are available for this visit.';
};

export const tenantPropertyOutcomeLabel = (property: TenantVisitOutcomeProperty): string => {
  if (property.outcome === 'VIEWED') return 'Viewed';
  if (property.outcome === 'NOT_VIEWED') return 'Not viewed';
  return 'Pending';
};

export const tenantOutcomeSummaryLabel = (summary: TenantVisitOutcome['outcomeSummary']): string | null => {
  switch (summary) {
    case 'ALL_VIEWED': return 'Visit completed · All homes viewed';
    case 'PARTLY_VIEWED': return 'Visit completed · Some homes viewed';
    case 'NONE_VIEWED': return 'Visit completed · No homes viewed';
    case 'RESULTS_NOT_RECORDED': return 'Results not recorded';
    default: return null;
  }
};
