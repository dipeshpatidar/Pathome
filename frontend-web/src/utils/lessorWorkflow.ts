export interface WorkflowStatusDetails {
  label: string;
  guidance: string;
  badgeClass: string;
  dotClass: string;
}

export const WORKFLOW_STATUS_CONFIG: Record<string, WorkflowStatusDetails> = {
  DRAFT: {
    label: 'Draft',
    guidance: "Finish your listing when you're ready.",
    badgeClass: 'bg-amber-50 text-amber-900 border-amber-200/80',
    dotClass: 'bg-amber-500'
  },
  SUBMITTED: {
    label: 'Submitted for review',
    guidance: 'Pathome has received your property for review.',
    badgeClass: 'bg-sky-50 text-sky-900 border-sky-200/80',
    dotClass: 'bg-sky-500'
  },
  UNDER_REVIEW: {
    label: 'Under review',
    guidance: 'Our team is reviewing your property.',
    badgeClass: 'bg-indigo-50 text-indigo-900 border-indigo-200/80',
    dotClass: 'bg-indigo-500'
  },
  CHANGES_REQUIRED: {
    label: 'Changes required',
    guidance: 'Updates are needed before this property can be published.',
    badgeClass: 'bg-rose-50 text-rose-900 border-rose-200/80',
    dotClass: 'bg-rose-500'
  },
  PUBLISHED: {
    label: 'Published',
    guidance: 'Your property is live.',
    badgeClass: 'bg-emerald-50 text-emerald-900 border-emerald-200/80',
    dotClass: 'bg-emerald-500'
  },
  PAUSED: {
    label: 'Paused',
    guidance: 'This property is currently not visible to renters.',
    badgeClass: 'bg-slate-100 text-slate-800 border-slate-300',
    dotClass: 'bg-slate-500'
  },
  ARCHIVED: {
    label: 'Archived',
    guidance: 'This property has been archived.',
    badgeClass: 'bg-slate-100 text-slate-600 border-slate-200',
    dotClass: 'bg-slate-400'
  }
};
