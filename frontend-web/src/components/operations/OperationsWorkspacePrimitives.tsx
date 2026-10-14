import React from 'react';
import {
  Activity,
  Archive,
  CalendarClock,
  ChevronLeft,
  ChevronRight,
  CircleAlert,
  CircleDashed,
  FileText,
  Info,
  Layers
} from 'lucide-react';
import {
  operationsStatusMeta,
  tenantConfirmationLabel as describeTenantConfirmation,
  tenantConfirmationTone,
  TENANT_CONFIRMATION_NOT_RECORDED,
  type OperationsStatusTone,
  type OperationsTargetType
} from '../../utils/operationsWorkspacePresentation';

const TONE_ICON: Record<OperationsStatusTone, typeof Activity> = {
  open: CircleDashed,
  scheduled: CalendarClock,
  active: Activity,
  attention: CircleAlert,
  closed: Archive
};

/** Tone drives colour and icon shape together, so status never relies on colour alone. */
export const StatusBadge: React.FC<{ targetType: OperationsTargetType; status: string }> = ({ targetType, status }) => {
  const meta = operationsStatusMeta(targetType, status);
  const Icon = TONE_ICON[meta.tone];
  return (
    <span className={`oe-badge oe-badge--${meta.tone}`}>
      <Icon size={13} strokeWidth={2.25} aria-hidden="true" />
      {meta.label}
    </span>
  );
};

export const TypeTag: React.FC<{ targetType: OperationsTargetType }> = ({ targetType }) => {
  const isSession = targetType === 'SESSION';
  const Icon = isSession ? Layers : FileText;
  return (
    <span className={`oe-type oe-type--${isSession ? 'session' : 'request'}`}>
      <Icon size={12} strokeWidth={2.25} aria-hidden="true" />
      {isSession ? 'Session' : 'Request'}
    </span>
  );
};

/**
 * Tenant confirmation is its own dimension and is never folded into Session status.
 * A missing value means no confirmation is recorded; it does not mean the tenant was not contacted.
 */
export const TenantConfirmationLabel: React.FC<{ value: string | null | undefined }> = ({ value }) => {
  if (!value) return <span className="oe-quiet">{TENANT_CONFIRMATION_NOT_RECORDED}</span>;
  return (
    <span className={`oe-confirm oe-confirm--${tenantConfirmationTone(value)}`}>
      {describeTenantConfirmation(value)}
    </span>
  );
};

export const ScopeLine: React.FC<{ city: string; team: string }> = ({ city, team }) => (
  <span className="oe-scope-line" title={`${city} · ${team}`}>
    <span className="oe-scope-city">{city}</span>
    <span className="oe-scope-team">{team}</span>
  </span>
);

export const StateMessage: React.FC<{
  icon: React.ReactNode;
  title: string;
  body: string;
  children?: React.ReactNode;
  tone?: 'neutral' | 'warn';
}> = ({ icon, title, body, children, tone = 'neutral' }) => (
  <div className={`oe-state oe-state--${tone}`} role={tone === 'warn' ? 'alert' : 'status'}>
    <span className="oe-state-icon" aria-hidden="true">{icon}</span>
    <p className="oe-state-title">{title}</p>
    <p className="oe-state-body">{body}</p>
    {children ? <div className="oe-state-actions">{children}</div> : null}
  </div>
);

export const Pagination: React.FC<{
  page: number;
  totalPages: number;
  totalElements: number;
  pageSize: number;
  onPage: (page: number) => void;
}> = ({ page, totalPages, totalElements, pageSize, onPage }) => {
  const from = totalElements === 0 ? 0 : page * pageSize + 1;
  const to = Math.min(totalElements, (page + 1) * pageSize);
  return (
    <nav className="oe-pager" aria-label="Pagination">
      <p className="oe-pager-info">
        <span className="oe-num">{from}–{to}</span> of <span className="oe-num">{totalElements}</span>
      </p>
      <div className="oe-pager-controls">
        <button
          type="button"
          className="oe-icon-btn"
          onClick={() => onPage(page - 1)}
          disabled={page === 0}
          aria-label="Previous page"
        >
          <ChevronLeft size={16} aria-hidden="true" />
        </button>
        <span className="oe-pager-page">Page {page + 1} of {totalPages}</span>
        <button
          type="button"
          className="oe-icon-btn"
          onClick={() => onPage(page + 1)}
          disabled={page >= totalPages - 1}
          aria-label="Next page"
        >
          <ChevronRight size={16} aria-hidden="true" />
        </button>
      </div>
    </nav>
  );
};

export const SkeletonRows: React.FC<{ rows?: number }> = ({ rows = 6 }) => (
  <div className="oe-list" aria-busy="true" aria-label="Loading work">
    <ul className="oe-rows">
      {Array.from({ length: rows }, (_, index) => (
        <li key={index} className="oe-row oe-row--skeleton">
          <span className="oe-skel" style={{ width: '38%' }} />
          <span className="oe-skel" style={{ width: '64%' }} />
          <span className="oe-skel oe-skel--short" />
        </li>
      ))}
    </ul>
    <span className="oe-sr">Loading…</span>
  </div>
);

export const PageHeader: React.FC<{
  eyebrow?: string;
  title: string;
  description?: React.ReactNode;
  aside?: React.ReactNode;
}> = ({ eyebrow, title, description, aside }) => (
  <header className="oe-page-head">
    <div className="oe-page-head-text">
      {eyebrow ? <p className="oe-eyebrow">{eyebrow}</p> : null}
      <h2 id="oe-page-title" tabIndex={-1} className="oe-h1">{title}</h2>
      {description ? <p className="oe-lede">{description}</p> : null}
    </div>
    {aside ? <div className="oe-page-head-aside">{aside}</div> : null}
  </header>
);

export const Field: React.FC<{ label: string; children: React.ReactNode; wide?: boolean }> = ({
  label,
  children,
  wide = false
}) => (
  <div className={`oe-field${wide ? ' oe-field--wide' : ''}`}>
    <dt>{label}</dt>
    <dd>{children}</dd>
  </div>
);

export const MissingValue: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <span className="oe-quiet">{children}</span>
);

/** Moves focus to the workspace page title after a view change, matching the approved behaviour. */
export const focusWorkspaceTitle = (): void => {
  window.requestAnimationFrame(() => {
    document.getElementById('oe-page-title')?.focus({ preventScroll: false });
  });
};

/**
 * Workspace-level outcome notice. It lives in the workspace itself rather than inside the record
 * detail, so a claim or release rejection stays visible after a detail closes, cannot be covered by
 * the sticky shells around the workspace, and is announced immediately (`role="alert"`).
 */
export const WorkspaceNotice: React.FC<{
  text: string;
  onDismiss: () => void;
  noticeRef?: React.Ref<HTMLDivElement>;
  tone?: 'warn' | 'info';
}> = ({ text, onDismiss, noticeRef, tone = 'warn' }) => (
  <div
    ref={noticeRef}
    tabIndex={-1}
    role={tone === 'warn' ? 'alert' : 'status'}
    className={`oe-banner oe-banner--${tone}`}
  >
    <div className="oe-notice-row">
      {tone === 'warn' ? <CircleAlert size={16} aria-hidden="true" /> : <Info size={16} aria-hidden="true" />}
      <p>{text}</p>
      <button type="button" className="oe-link oe-notice-dismiss" onClick={onDismiss}>
        Dismiss
      </button>
    </div>
  </div>
);
