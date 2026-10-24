import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ArrowLeft,
  ArrowRight,
  Check,
  ChevronRight,
  CircleAlert,
  Copy,
  Eye,
  FilePlus2,
  History,
  Inbox,
  Lock,
  MessageCircle,
  Phone,
  Plus,
  RefreshCw,
  Search,
  Send,
  ShieldAlert,
  StickyNote,
  Trash2,
  Users,
  X
} from 'lucide-react';
import { useLocation, useNavigate } from 'react-router-dom';
import type { OperationsAuthority } from '../../hooks/useOperationsCapability';
import {
  operationsLeadService,
  ShareHistoryBoundReachedError,
  type LeadRequestContext
} from '../../services/operationsLeadService';
import { propertyService } from '../../services/propertyService';
import { ApiRequestError } from '../../services/apiError';
import { parseRentalFurnishing } from '../../utils/rentalSearch';
import {
  captureOperationsSession,
  isOperationsSessionActive,
  isOperationsSessionChangedError,
  subscribeOperationsSession,
  type OperationsSession
} from '../../utils/operationsSession';
import type { Property } from '../../types';
import {
  EMPTY_LEAD_QUEUE_FILTERS,
  LEAD_PAGE_SIZE,
  LEAD_STATUS_META,
  SHARE_EVENT_META,
  SHARE_HISTORY_PAGE_SIZE,
  SHORTLIST_MAX,
  hasLeadDiagnosticsAccess,
  hasLeadWorkspaceAccess,
  leadActiveForCrmMutation,
  leadCanEdit,
  leadCanRevealContact,
  leadShareFromHistoryItem,
  leadQualificationGapSummary,
  leadQueueContextFromSearch,
  leadQueueContextQuery,
  leadRequirementSummary,
  leadScopes,
  leadShareEligible,
  normalizeIndianMobile,
  shareLinksText,
  sourceRetryAllowed,
  sourceRetryConflictRequiresRefresh,
  toLeadDetail,
  whatsappUrl,
  type LeadActivity,
  type LeadContactPreference,
  type LeadDetail,
  type LeadDuplicateCandidate,
  type LeadQueueContext,
  type LeadQueueFilters,
  type LeadScope,
  type LeadShare,
  type LeadShareEventType,
  type LeadShareHistoryItem,
  type LeadShortlist,
  type LeadSourceException
} from '../../utils/operationsLeadPresentation';
import {
  consentReceiptRevision,
  forgetPendingConsentOperation,
  preservePendingConsentOperation,
  readPendingConsentOperation,
  type PendingConsentOperation
} from '../../utils/operationsConsentRecovery';
import { createOperationId } from '../../utils/operationsOperationId';
import { classifySharePrepareFailure } from '../../utils/operationsSharePreparation';
import {
  forgetPendingSharePreparation,
  preservePendingSharePreparation,
  readPendingSharePreparation,
  type PendingSharePrepareOperation
} from '../../utils/operationsShareRecovery';
import type { StaffCapabilities } from '../../utils/operationsWorkspacePresentation';
import {
  PageHeader,
  Pagination,
  ScopeLine,
  SkeletonRows,
  StateMessage,
  focusWorkspaceTitle
} from './OperationsWorkspacePrimitives';
import './operationsWorkspace.css';
import './operationsLeads.css';

type LeadRoute =
  | { kind: 'queue' }
  | { kind: 'capture' }
  | { kind: 'detail'; leadId: number }
  | { kind: 'share'; leadId: number; tab: 'share' | 'history' }
  | { kind: 'exceptions' };

interface Props {
  authority: OperationsAuthority;
  embedded?: boolean;
  onShellNavigationGuardChange?: (guard: ((leave: () => void) => void) | null) => void;
}

const parseRoute = (pathname: string, search: string): LeadRoute => {
  const parts = pathname.replace(/\/+$/, '').split('/').filter(Boolean);
  if (parts[0] !== 'operations' || parts[1] !== 'leads') return { kind: 'queue' };
  if (parts.length === 2) return { kind: 'queue' };
  if (parts[2] === 'new') return { kind: 'capture' };
  if (parts[2] === 'source-exceptions') return { kind: 'exceptions' };
  const leadId = Number(parts[2]);
  if (!Number.isSafeInteger(leadId) || leadId <= 0) return { kind: 'queue' };
  if (parts[3] === 'share') {
    const tab = new URLSearchParams(search).get('tab') === 'history' ? 'history' : 'share';
    return { kind: 'share', leadId, tab };
  }
  return { kind: 'detail', leadId };
};

const queueReturnTarget = (search: string): string | null => {
  const candidate = new URLSearchParams(search).get('returnTo');
  return candidate && /^\/operations\/leads(?:\?[^#]*)?$/.test(candidate)
    ? candidate
    : null;
};

const withReturnTo = (path: string, returnTo: string | null, extra?: Record<string, string>): string => {
  const params = new URLSearchParams(extra);
  if (returnTo) params.set('returnTo', returnTo);
  const query = params.toString();
  return `${path}${query ? `?${query}` : ''}`;
};

const formatDateTime = (value: string | null | undefined): string | null => {
  if (!value) return null;
  const instant = new Date(value);
  if (Number.isNaN(instant.getTime())) return value;
  return new Intl.DateTimeFormat('en-IN', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Asia/Kolkata'
  }).format(instant);
};

const statusMeta = (status: string) =>
  LEAD_STATUS_META[status] ?? { label: status, tone: 'attention' as const };

const eventMeta = (type: string) =>
  SHARE_EVENT_META[type] ?? { label: type, tone: 'attention' as const };

type LeadSharePrepareCommand = Parameters<typeof operationsLeadService.prepareShare>[1];

const sourceExceptionTitle = (code: string | null): string => ({
  SOURCE_EXTRACTION_FAILED: 'The enquiry details need review',
  SOURCE_RETRY_EXHAUSTED: 'The import could not be retried again',
  SOURCE_ACTIVATION_NOT_APPROVED: 'This enquiry source is not active',
  LEGACY_FINGERPRINT_RECONCILIATION_REQUIRED: 'This older enquiry needs a duplicate check'
} as Record<string, string>)[code ?? ''] ?? 'This enquiry needs review';

const displayName = (lead: Pick<LeadDetail, 'customerName' | 'id'>): string =>
  lead.customerName || `Lead #${lead.id}`;

const failureStatus = (cause: unknown): number | undefined =>
  cause instanceof ApiRequestError ? cause.status : undefined;

const failureMessage = (cause: unknown, fallback: string): string =>
  cause instanceof ApiRequestError
    ? cause.details || cause.message
    : cause instanceof Error && cause.message
      ? cause.message
      : fallback;

const consentOutcomeIsUnconfirmed = (status: number | undefined): boolean =>
  status === undefined
  || status >= 500
  || (status >= 200 && status < 300);

const consentFailureMessage = (cause: unknown, fallback: string): string => {
  const status = failureStatus(cause);
  if (status === 401) return 'Your session has ended. Sign in again before saving customer permission.';
  if (status === 403) return 'You cannot change customer permission for this Lead with your current access.';
  if (status === 404) return 'This Lead or its current sharing details are no longer available.';
  return failureMessage(cause, fallback);
};

const preDispatchPermissionFailure = (cause: unknown): string => {
  if (cause instanceof Error && cause.message.includes('previous consent command')) {
    return 'Check the earlier permission request before saving another answer.';
  }
  if (cause instanceof Error && cause.message.includes('session')) {
    return 'Your session changed before the permission was saved. Nothing was sent.';
  }
  return 'This browser could not keep the permission request. Nothing was sent. Your answers are still here.';
};

const isConcealed = (cause: unknown): boolean => {
  const status = failureStatus(cause);
  return status === 403 || status === 404;
};

const requestId = (): string => createOperationId();

const Tone: React.FC<{
  tone: 'open' | 'active' | 'attention' | 'closed';
  children: React.ReactNode;
}> = ({ tone, children }) => (
  <span className={`oe-badge oe-badge--${tone}`}>
    <span className="oe-badge-dot" aria-hidden="true" />
    {children}
  </span>
);

const Notice: React.FC<{
  title: string;
  tone?: 'info' | 'warn' | 'success';
  live?: boolean;
  children?: React.ReactNode;
  actions?: React.ReactNode;
}> = ({ title, tone = 'info', live = false, children, actions }) => (
  <div
    className={`ld-notice ld-notice--${tone}`}
    role={live ? (tone === 'warn' ? 'alert' : 'status') : undefined}
  >
    {tone === 'warn'
      ? <ShieldAlert size={18} aria-hidden="true" />
      : tone === 'success'
        ? <Check size={18} aria-hidden="true" />
        : <CircleAlert size={18} aria-hidden="true" />}
    <div>
      <p className="ld-notice-title">{title}</p>
      {children ? <div className="ld-notice-body">{children}</div> : null}
      {actions ? <div className="ld-actions">{actions}</div> : null}
    </div>
  </div>
);

const Modal: React.FC<{
  title: string;
  onClose: () => void;
  children: React.ReactNode;
  footer?: React.ReactNode;
}> = ({ title, onClose, children, footer }) => {
  const reference = useRef<HTMLDivElement>(null);
  const titleId = useRef(`ld-modal-${requestId()}`).current;
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    requestAnimationFrame(() => document.getElementById(titleId)?.focus());
    const keepFocus = (event: FocusEvent) => {
      if (reference.current && event.target instanceof Node
        && !reference.current.contains(event.target)) {
        document.getElementById(titleId)?.focus();
      }
    };
    document.addEventListener('focusin', keepFocus);
    return () => {
      document.removeEventListener('focusin', keepFocus);
      document.body.style.overflow = overflow;
      previous?.focus();
    };
  }, [titleId]);
  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key !== 'Tab' || !reference.current) return;
    const nodes = [...reference.current.querySelectorAll<HTMLElement>(
      'button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
    )].filter((node) => node.getClientRects().length > 0);
    if (!nodes.length) return;
    const index = nodes.indexOf(document.activeElement as HTMLElement);
    if (index < 0) {
      event.preventDefault();
      (event.shiftKey ? nodes[nodes.length - 1] : nodes[0]).focus();
    } else if (event.shiftKey && index === 0) {
      event.preventDefault();
      nodes[nodes.length - 1].focus();
    } else if (!event.shiftKey && index === nodes.length - 1) {
      event.preventDefault();
      nodes[0].focus();
    }
  };
  return (
    <div className="ld-scrim" onPointerDown={(event) => {
      if (event.target === event.currentTarget) onClose();
    }}>
      <div
        ref={reference}
        className="ld-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onKeyDown={onKeyDown}
      >
        <header className="ld-modal-head">
          <h2 id={titleId} tabIndex={-1}>{title}</h2>
          <button type="button" className="oe-icon-btn" aria-label="Close" onClick={onClose}>
            <X size={18} aria-hidden="true" />
          </button>
        </header>
        <div className="ld-modal-body">{children}</div>
        {footer ? <footer className="ld-modal-foot">{footer}</footer> : null}
      </div>
    </div>
  );
};

const AuthorityNotice: React.FC<{
  title: string;
  body: string;
  tone?: 'neutral' | 'warn';
  action?: React.ReactNode;
}> = ({ title, body, tone = 'neutral', action }) => (
  <StateMessage
    tone={tone}
    icon={tone === 'warn' ? <CircleAlert size={20} /> : <Lock size={20} />}
    title={title}
    body={body}
  >
    {action}
  </StateMessage>
);

export const OperationsLeadsWorkspace: React.FC<Props> = ({
  authority,
  embedded = false,
  onShellNavigationGuardChange
}) => {
  const location = useLocation();
  const navigate = useNavigate();
  const route = parseRoute(location.pathname, location.search);
  const shellClass = embedded
    ? 'ld-shell ld-shell--embedded'
    : 'oe oe--page ld-shell';
  const capabilities = authority.capabilities;
  const session = captureOperationsSession();
  const actorId = session?.userId ?? 0;
  const returnTo = queueReturnTarget(location.search);
  const retainedQueue = useRef<{ identity: number; context: LeadQueueContext } | null>(null);
  const captureNavigationGuard = useRef<((leave: () => void) => void) | null>(null);
  const setCaptureNavigationGuard = useCallback(
    (guard: ((leave: () => void) => void) | null) => {
      captureNavigationGuard.current = guard;
    },
    []
  );
  const retainQueueContext = useCallback((context: LeadQueueContext) => {
    retainedQueue.current = { identity: actorId, context };
  }, [actorId]);

  useEffect(() => {
    if (retainedQueue.current && retainedQueue.current.identity !== actorId) {
      retainedQueue.current = null;
    }
  }, [actorId]);

  useEffect(() => {
    if (!onShellNavigationGuardChange) return undefined;
    onShellNavigationGuardChange((leave) => {
      if (captureNavigationGuard.current) captureNavigationGuard.current(leave);
      else leave();
    });
    return () => onShellNavigationGuardChange(null);
  }, [onShellNavigationGuardChange]);

  if (!authority.entryAdmitted
    && authority.state !== 'restricted'
    && authority.state !== 'unavailable') {
    return (
      <section className={shellClass} aria-busy="true">
        <h1 id="oe-page-title" tabIndex={-1} className="oe-sr">Verifying Lead access</h1>
        <StateMessage
          icon={<Lock size={20} />}
          title="Verifying your Lead access"
          body="Confirming the current staff profile and City/Team capability with the server."
        />
        <SkeletonRows rows={4} />
      </section>
    );
  }
  if (authority.state === 'restricted') {
    return (
      <section className={shellClass}>
        <h1 id="oe-page-title" tabIndex={-1} className="oe-sr">No Lead access</h1>
        <AuthorityNotice
          title="You don’t have access to these Leads"
          body="Lead records require a current coordination, supervision, City intake/admin, or global Staff Admin capability."
        />
      </section>
    );
  }
  if (authority.state === 'unavailable') {
    return (
      <section className={shellClass}>
        <h1 id="oe-page-title" tabIndex={-1} className="oe-sr">Lead access unavailable</h1>
        <AuthorityNotice
          tone="warn"
          title="Lead access could not be verified"
          body="Protected records and actions stay hidden until the server confirms current authority."
          action={(
            <button type="button" className="oe-btn oe-btn--primary oe-btn--md" onClick={authority.revalidate}>
              <RefreshCw size={14} aria-hidden="true" />Try again
            </button>
          )}
        />
      </section>
    );
  }

  if (!hasLeadWorkspaceAccess(capabilities)) {
    return (
      <section className={shellClass}>
        <h1 id="oe-page-title" tabIndex={-1} className="oe-sr">No Lead access</h1>
        <AuthorityNotice
          title="You don’t have access to these Leads"
          body="Your current staff grants do not include Lead queue, sharing, or global diagnostic authority."
        />
      </section>
    );
  }

  const diagnostics = hasLeadDiagnosticsAccess(capabilities);
  if (route.kind === 'exceptions' && !diagnostics) {
    return (
      <section className={shellClass}>
        <h1 id="oe-page-title" tabIndex={-1} className="oe-sr">Import issues restricted</h1>
        <AuthorityNotice
          title="Import issues are limited to Staff Admins"
          body="Unchecked enquiries can contain customer information. No enquiry details are loaded for your account."
        />
      </section>
    );
  }

  const go = (path: string, replace = false) => {
    const leave = () => {
      navigate(path, { replace });
      requestAnimationFrame(() => focusWorkspaceTitle());
    };
    if (route.kind === 'capture' && captureNavigationGuard.current) {
      captureNavigationGuard.current(leave);
    } else {
      leave();
    }
  };

  return (
    <section className={shellClass} aria-label="Lead workspace">
      {!embedded ? <a className="oe-skip" href="#lead-main">Skip to Lead content</a> : null}
      {!embedded ? <header className="ld-workspace-nav">
        <div>
          <p className="oe-eyebrow">Operations · Customer requirements</p>
          <p className="ld-workspace-title">Lead workspace</p>
        </div>
        <nav className="ld-workspace-links" aria-label="Lead workspace destinations">
          <button
            type="button"
            className="oe-btn oe-btn--secondary oe-btn--sm"
            aria-current={route.kind === 'queue' ? 'page' : undefined}
            onClick={() => go('/operations/leads')}
          >
            <Inbox size={15} aria-hidden="true" />Leads
          </button>
          {diagnostics ? (
            <button
              type="button"
              className="oe-btn oe-btn--secondary oe-btn--sm"
              aria-current={route.kind === 'exceptions' ? 'page' : undefined}
              onClick={() => go('/operations/leads/source-exceptions')}
            >
              <ShieldAlert size={15} aria-hidden="true" />Import issues
            </button>
          ) : null}
          <button
            type="button"
            className="oe-btn oe-btn--secondary oe-btn--sm"
            onClick={() => go('/operations')}
          >
            Operations overview
          </button>
        </nav>
      </header> : null}

      <div id="lead-main" tabIndex={-1}>
        {route.kind === 'queue' ? (
          <LeadQueueScreen
            actorId={actorId}
            capabilities={capabilities}
            diagnostics={diagnostics}
            initialContext={retainedQueue.current?.identity === actorId
              ? retainedQueue.current.context
              : null}
            onContextChange={retainQueueContext}
            onOpen={(leadId, queuePath) => go(withReturnTo(`/operations/leads/${leadId}`, queuePath))}
            onCapture={(queuePath) => go(withReturnTo('/operations/leads/new', queuePath))}
            onExceptions={() => go('/operations/leads/source-exceptions')}
          />
        ) : route.kind === 'capture' ? (
          <LeadCaptureScreen
            capabilities={capabilities}
            onGuardNavigation={setCaptureNavigationGuard}
            onBack={() => go(returnTo ?? '/operations/leads')}
            onCreated={(lead) => go(withReturnTo(`/operations/leads/${lead.id}`, returnTo), true)}
            onOpenExisting={(leadId) => go(withReturnTo(`/operations/leads/${leadId}`, returnTo), true)}
          />
        ) : route.kind === 'detail' ? (
          <LeadDetailScreen
            key={`${route.leadId}-${session?.identity ?? 'signed-out'}`}
            leadId={route.leadId}
            actorId={actorId}
            capabilities={capabilities}
            onBack={() => go(returnTo ?? '/operations/leads')}
            onShare={(tab) => go(withReturnTo(`/operations/leads/${route.leadId}/share`,
              returnTo, tab === 'history' ? { tab: 'history' } : undefined))}
          />
        ) : route.kind === 'share' ? (
          <LeadShareScreen
            key={`${route.leadId}-${session?.identity ?? 'signed-out'}`}
            leadId={route.leadId}
            initialTab={route.tab}
            actorId={actorId}
            capabilities={capabilities}
            returnTo={returnTo}
            onBack={() => go(withReturnTo(`/operations/leads/${route.leadId}`, returnTo))}
            onQueue={() => go(returnTo ?? '/operations/leads')}
          />
        ) : (
          <LeadSourceExceptionsScreen onBack={() => go('/operations/leads')} />
        )}
      </div>
    </section>
  );
};

const LeadQueueScreen: React.FC<{
  actorId: number;
  capabilities: StaffCapabilities | null;
  diagnostics: boolean;
  initialContext: LeadQueueContext | null;
  onContextChange: (context: LeadQueueContext) => void;
  onOpen: (leadId: number, returnTo: string) => void;
  onCapture: (returnTo: string) => void;
  onExceptions: () => void;
}> = ({
  actorId,
  capabilities,
  diagnostics,
  initialContext: retainedContext,
  onContextChange,
  onOpen,
  onCapture,
  onExceptions
}) => {
  const location = useLocation();
  const navigate = useNavigate();
  const initialContext = useRef(
    retainedContext ?? leadQueueContextFromSearch(location.search)
  ).current;
  const [filters, setFilters] = useState<LeadQueueFilters>(initialContext.filters);
  const [page, setPage] = useState(initialContext.page);
  const [state, setState] = useState<'loading' | 'ready' | 'error' | 'denied' | 'signed-out'>('loading');
  const [result, setResult] = useState<{
    items: Awaited<ReturnType<typeof operationsLeadService.list>>['items'];
    totalElements: number;
    totalPages: number;
    page: number;
  } | null>(null);
  const [message, setMessage] = useState('');
  const requestNumber = useRef(0);
  const controller = useRef<AbortController | null>(null);
  const scopes = leadScopes(capabilities);
  const captureScopes = scopes.filter((scope) =>
    scope.teamId !== null
    || capabilities?.capabilities.some((capability) =>
      capability.capability === 'OPS_INTAKE'
      && capability.scopeType === 'CITY'
      && capability.cityId === scope.cityId)
  );

  const load = useCallback((requestedPage = page, nextFilters = filters) => {
    const captured = captureOperationsSession();
    const sequence = requestNumber.current + 1;
    requestNumber.current = sequence;
    controller.current?.abort();
    const next = new AbortController();
    controller.current = next;
    if (!captured) {
      setState('signed-out');
      setResult(null);
      return;
    }
    setState('loading');
    setMessage('');
    operationsLeadService.list(requestedPage, nextFilters, {
      session: captured,
      signal: next.signal
    }).then((response) => {
      if (next.signal.aborted || sequence !== requestNumber.current
        || !isOperationsSessionActive(captured)) return;
      setResult(response);
      setState('ready');
      setPage(response.page);
    }).catch((cause) => {
      if (next.signal.aborted || sequence !== requestNumber.current
        || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(captured)) return;
      if (failureStatus(cause) === 401) {
        setState('signed-out');
        setResult(null);
        return;
      }
      if (isConcealed(cause)) {
        setState('denied');
        setResult(null);
        return;
      }
      setMessage(failureMessage(cause, 'Leads could not be loaded.'));
      setState('error');
    });
  }, [filterKey(filters), page]);

  useEffect(() => {
    load(initialContext.page, initialContext.filters);
    return () => controller.current?.abort();
  }, []);

  useEffect(() => {
    onContextChange({ page, filters });
    const query = leadQueueContextQuery(page, filters);
    navigate(`/operations/leads${query ? `?${query}` : ''}`, { replace: true });
  }, [filters, page, navigate, onContextChange]);

  const updateFilters = (patch: Partial<LeadQueueFilters>) => {
    const next = { ...filters, ...patch };
    setFilters(next);
    setPage(0);
    load(0, next);
  };

  const cities = new Map<number, string>();
  scopes.forEach((scope) => cities.set(scope.cityId, scope.cityName));
  const teams = scopes.filter((scope) => scope.teamId !== null
    && (filters.cityId === null || scope.cityId === filters.cityId));
  const selectedTeam = teams.find((scope) => scope.teamId === filters.teamId) ?? null;

  return (
    <div className="oe-stack">
      <PageHeader
        eyebrow="Customer requirements"
        title="Leads"
        description="Enquiries visible through your current staff capability and Team ownership. A Lead is not a booked visit."
        aside={captureScopes.length ? (
          <button
            type="button"
            className="oe-btn oe-btn--primary oe-btn--md"
            onClick={() => onCapture(`${location.pathname}${location.search}`)}
          >
            <Plus size={16} aria-hidden="true" />New enquiry
          </button>
        ) : null}
      />

      {diagnostics ? (
        <Notice
          title="Import issues are available to Staff Admin"
          actions={(
            <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={onExceptions}>
              <ShieldAlert size={15} aria-hidden="true" />Open import issues
            </button>
          )}
        >
          Staff Admins can review imports across Cities and Teams. Original enquiry details are never shown here.
        </Notice>
      ) : null}

      <form className="ld-filters" aria-label="Filter Leads" onSubmit={(event) => event.preventDefault()}>
        <label className="oe-field-stack ld-search">
          <span className="oe-sr">Search Leads</span>
          <Search size={16} aria-hidden="true" />
          <input
            className="oe-input ld-input"
            type="search"
            value={filters.q}
            maxLength={80}
            autoComplete="off"
            placeholder="Name, Lead #reference, locality or last 4 digits"
            onChange={(event) => updateFilters({ q: event.target.value })}
          />
        </label>
        <label className="oe-field-stack">
          <span className="oe-field-label">City</span>
          <select
            className="oe-input ld-input"
            value={filters.cityId ?? ''}
            onChange={(event) => updateFilters({
              cityId: event.target.value ? Number(event.target.value) : null,
              teamId: null
            })}
          >
            <option value="">All authorized cities</option>
            {[...cities.entries()].map(([cityId, cityName]) => (
              <option key={cityId} value={cityId}>{cityName}</option>
            ))}
          </select>
          <span className="ld-filter-selected">
            {filters.cityId === null
              ? 'All authorized cities'
              : cities.get(filters.cityId) ?? 'Selected city'}
          </span>
        </label>
        <label className="oe-field-stack">
          <span className="oe-field-label">Team</span>
          <select
            className="oe-input ld-input"
            value={filters.teamId ?? ''}
            onChange={(event) => updateFilters({
              teamId: event.target.value ? Number(event.target.value) : null
            })}
          >
            <option value="">All authorized Teams</option>
            {teams.map((scope) => (
              <option key={`${scope.cityId}-${scope.teamId}`} value={scope.teamId ?? ''}>
                {scope.cityName} · {scope.teamName}
              </option>
            ))}
          </select>
          <span className="ld-filter-selected">
            {filters.teamId === null
              ? 'All authorized Teams'
              : selectedTeam
                ? `${selectedTeam.cityName} · ${selectedTeam.teamName}`
                : 'Selected Team'}
          </span>
        </label>
        <label className="oe-field-stack">
          <span className="oe-field-label">Status</span>
          <select
            className="oe-input ld-input"
            value={filters.status ?? ''}
            onChange={(event) => updateFilters({ status: event.target.value || null })}
          >
            <option value="">Any current status</option>
            {Object.entries(LEAD_STATUS_META).map(([status, meta]) => (
              <option key={status} value={status}>{meta.label}</option>
            ))}
          </select>
          <span className="ld-filter-selected">
            {filters.status === null
              ? 'Any current status'
              : LEAD_STATUS_META[filters.status]?.label ?? 'Selected status'}
          </span>
        </label>
        <label className="oe-field-stack">
          <span className="oe-field-label">Follow-up</span>
          <select
            className="oe-input ld-input"
            value={filters.followUp}
            onChange={(event) => updateFilters({
              followUp: event.target.value as LeadQueueFilters['followUp']
            })}
          >
            <option value="ANY">Any follow-up</option>
            <option value="OVERDUE">Overdue</option>
            <option value="TODAY">Due today</option>
            <option value="NONE">No follow-up</option>
          </select>
          <span className="ld-filter-selected">
            {{
              ANY: 'Any follow-up',
              OVERDUE: 'Overdue',
              TODAY: 'Due today',
              NONE: 'No follow-up'
            }[filters.followUp]}
          </span>
        </label>
        <div className="ld-filter-checks">
          <label className="oe-check">
            <input
              type="checkbox"
              checked={filters.ownerMe}
              onChange={(event) => updateFilters({ ownerMe: event.target.checked })}
            />
            Owned by me
          </label>
        </div>
      </form>

      <p className="oe-footnote">
        Showing Leads for {scopes.length
          ? scopes.map((scope) => scope.teamName
            ? `${scope.cityName} · ${scope.teamName}`
            : scope.cityName).join(', ')
          : 'your current global diagnostic scope'}.
        {actorId > 0 ? ' Actions still depend on the exact Lead owner and current grant.' : ''}
      </p>

      {state === 'loading' ? <SkeletonRows /> : state === 'signed-out' ? (
        <StateMessage icon={<Lock size={20} />} title="Your session has ended" body="Sign in again to reload your authorized Leads. Nothing was changed." />
      ) : state === 'denied' ? (
        <StateMessage icon={<Lock size={20} />} title="Lead access is no longer available" body="The previous list has been cleared. Your current staff authority does not admit this Lead scope." />
      ) : state === 'error' ? (
        <StateMessage tone="warn" icon={<CircleAlert size={20} />} title="We couldn’t load your Leads" body={message || 'Check your connection and try again. Nothing was changed.'}>
          <button type="button" className="oe-btn oe-btn--primary oe-btn--md" onClick={() => load(page, filters)}>
            <RefreshCw size={14} aria-hidden="true" />Try again
          </button>
        </StateMessage>
      ) : result?.items.length === 0 ? (
        <StateMessage icon={<Inbox size={20} />} title="No Leads match this scope" body="Change the filters, or capture an enquiry if your current capabilities allow it.">
          {captureScopes.length ? (
            <button
              type="button"
              className="oe-btn oe-btn--secondary oe-btn--md"
              onClick={() => onCapture(`${location.pathname}${location.search}`)}
            >
              <FilePlus2 size={15} aria-hidden="true" />Capture an enquiry
            </button>
          ) : null}
        </StateMessage>
      ) : result ? (
        <>
          <p className="ld-count" role="status">
            <strong>{result.totalElements}</strong> {result.totalElements === 1 ? 'Lead' : 'Leads'} in the current server result
          </p>
          <ul className="ld-rows" aria-label="Leads">
            {result.items.map((lead) => {
              const status = statusMeta(lead.status);
              const due = lead.followUpAt ? formatDateTime(lead.followUpAt) : null;
              return (
                <li key={lead.id}>
                  <button
                    type="button"
                    className="ld-row"
                    data-row-key={lead.id}
                    onClick={() => onOpen(lead.id, `${location.pathname}${location.search}`)}
                  >
                    <span className="ld-row-main">
                      <span className="ld-row-title">
                        <strong>{lead.customerName || 'Name not captured'}</strong>
                        <span className="oe-mono">Lead #{lead.id}</span>
                      </span>
                      <span className="ld-row-sum">
                        {lead.qualificationGaps.length
                          ? `${lead.qualificationGaps.length} qualification ${lead.qualificationGaps.length === 1 ? 'gap' : 'gaps'}`
                          : 'No recorded qualification gaps'}
                      </span>
                      <ScopeLine
                        city={lead.cityName || 'City not recorded'}
                        team={lead.teamName || 'Unassigned Team'}
                      />
                    </span>
                    <span className="ld-row-side">
                      <Tone tone={status.tone}>{status.label}</Tone>
                      <span className="oe-mono">{lead.maskedContact || 'Contact unavailable'}</span>
                      <span className="oe-quiet">
                        Owner: {lead.ownerDisplayName || 'Unassigned'}
                      </span>
                      {due ? <span>Follow-up {due}</span> : null}
                    </span>
                    <ChevronRight size={16} aria-hidden="true" className="ld-row-chev" />
                  </button>
                </li>
              );
            })}
          </ul>
          {result.totalPages > 0 ? (
            <Pagination
              page={result.page}
              totalPages={result.totalPages}
              totalElements={result.totalElements}
              pageSize={LEAD_PAGE_SIZE}
              onPage={(nextPage) => {
                setPage(nextPage);
                load(nextPage, filters);
              }}
            />
          ) : null}
        </>
      ) : null}
    </div>
  );
};

const filterKey = (filters: LeadQueueFilters): string =>
  JSON.stringify(filters);

type CaptureOpportunity = {
  key: string;
  cityId: number;
  teamId: number | null;
  cityName: string;
  teamName: string | null;
};

interface CaptureDraft {
  phone: string;
  customerName: string;
  customerSaid: string;
  intent: 'RENT' | 'BUY' | 'UNSPECIFIED';
  propertyType: string;
  bhk: string;
  budgetMin: string;
  budgetMax: string;
  targetMoveInDate: string;
  furnishing: string;
  tenantProfile: string;
  parkingRequired: '' | 'true' | 'false';
  petsAllowed: '' | 'true' | 'false';
  liftRequired: '' | 'true' | 'false';
  floorRequirement: string;
  sourceChannel: string;
  campaignRef: string;
  referralRef: string;
}

const EMPTY_CAPTURE: CaptureDraft = {
  phone: '',
  customerName: '',
  customerSaid: '',
  intent: 'UNSPECIFIED',
  propertyType: '',
  bhk: '',
  budgetMin: '',
  budgetMax: '',
  targetMoveInDate: '',
  furnishing: '',
  tenantProfile: '',
  parkingRequired: '',
  petsAllowed: '',
  liftRequired: '',
  floorRequirement: '',
  sourceChannel: '',
  campaignRef: '',
  referralRef: ''
};

const captureOpportunities = (
  capabilities: StaffCapabilities | null
): CaptureOpportunity[] => {
  if (!capabilities?.staffActive) return [];
  const options = new Map<string, CaptureOpportunity>();
  capabilities.capabilities.forEach((capability) => {
    if (capability.cityId === null) return;
    if (capability.capability === 'OPS_COORDINATE'
      && capability.scopeType === 'TEAM'
      && capability.teamId !== null) {
      const key = `${capability.cityId}:${capability.teamId}`;
      options.set(key, {
        key,
        cityId: capability.cityId,
        teamId: capability.teamId,
        cityName: capability.cityDisplayName || 'City name unavailable',
        teamName: capability.teamDisplayName || 'Team name unavailable'
      });
    }
    if (capability.capability === 'OPS_INTAKE' && capability.scopeType === 'CITY') {
      const key = `${capability.cityId}:city`;
      options.set(key, {
        key,
        cityId: capability.cityId,
        teamId: null,
        cityName: capability.cityDisplayName || 'City name unavailable',
        teamName: null
      });
    }
  });
  return [...options.values()].sort((left, right) =>
    left.cityName.localeCompare(right.cityName)
    || (left.teamName ?? '').localeCompare(right.teamName ?? '')
  );
};

const LeadCaptureScreen: React.FC<{
  capabilities: StaffCapabilities | null;
  onGuardNavigation: (guard: ((leave: () => void) => void) | null) => void;
  onBack: () => void;
  onCreated: (lead: LeadDetail) => void;
  onOpenExisting: (leadId: number) => void;
}> = ({ capabilities, onGuardNavigation, onBack, onCreated, onOpenExisting }) => {
  const opportunities = captureOpportunities(capabilities);
  const [scopeKey, setScopeKey] = useState(opportunities[0]?.key ?? '');
  const [draft, setDraft] = useState<CaptureDraft>(EMPTY_CAPTURE);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [phase, setPhase] = useState<'edit' | 'checking-duplicates' | 'duplicates' | 'saving' | 'uncertain' | 'checking'>('edit');
  const [duplicateCandidates, setDuplicateCandidates] = useState<LeadDuplicateCandidate[]>([]);
  const [existingAccountMatch, setExistingAccountMatch] = useState(false);
  const [selectedCandidateId, setSelectedCandidateId] = useState<number | null>(null);
  const [pendingNavigation, setPendingNavigation] = useState<
    { kind: 'back' } | { kind: 'open'; leadId: number }
    | { kind: 'external'; leave: () => void }
    | { kind: 'browser-back' } | null
  >(null);
  const [notice, setNotice] = useState<string | null>(null);
  const operationId = useRef<string | null>(null);
  const dispatched = useRef(false);
  const inFlight = useRef(false);
  const lookupInFlight = useRef(false);
  const draftGeneration = useRef(0);
  const duplicateController = useRef<AbortController | null>(null);
  const allowNavigation = useRef(false);
  const browserBackGuardPushed = useRef(false);
  const completed = useRef(false);
  const mounted = useRef(true);
  const scope = opportunities.find((entry) => entry.key === scopeKey) ?? null;

  const dirty = Object.values(draft).some((value) => value !== '')
    || duplicateCandidates.length > 0
    || phase !== 'edit';

  useEffect(() => {
    mounted.current = true;
    const unsubscribe = subscribeOperationsSession(() => {
      draftGeneration.current += 1;
      duplicateController.current?.abort();
      lookupInFlight.current = false;
      inFlight.current = false;
      operationId.current = null;
      allowNavigation.current = true;
      setDraft(EMPTY_CAPTURE);
      setErrors({});
      setDuplicateCandidates([]);
      setSelectedCandidateId(null);
      setExistingAccountMatch(false);
      setPendingNavigation(null);
      setPhase('edit');
      setNotice('Your account changed. The previous enquiry draft was cleared.');
    });
    return () => {
      unsubscribe();
      mounted.current = false;
      draftGeneration.current += 1;
      duplicateController.current?.abort();
    };
  }, []);

  useEffect(() => {
    if (!dirty || completed.current) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  useEffect(() => {
    if (!dirty || completed.current || browserBackGuardPushed.current) return;
    window.history.pushState({
      ...(window.history.state ?? {}),
      pathomeLeadCaptureGuard: true
    }, '');
    browserBackGuardPushed.current = true;
  }, [dirty]);

  useEffect(() => {
    const onPopState = () => {
      if (!browserBackGuardPushed.current) return;
      browserBackGuardPushed.current = false;
      if (!dirty || completed.current || allowNavigation.current) return;
      setPendingNavigation({ kind: 'browser-back' });
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, [dirty]);

  useEffect(() => {
    onGuardNavigation((leave) => {
      if (!dirty || completed.current || allowNavigation.current) {
        allowNavigation.current = false;
        leave();
        return;
      }
      setPendingNavigation({ kind: 'external', leave });
    });
    return () => onGuardNavigation(null);
  }, [dirty, onGuardNavigation]);

  const retireLookup = () => {
    draftGeneration.current += 1;
    duplicateController.current?.abort();
    duplicateController.current = null;
    lookupInFlight.current = false;
    setDuplicateCandidates([]);
    setSelectedCandidateId(null);
    setExistingAccountMatch(false);
    setPhase((current) => current === 'checking-duplicates' || current === 'duplicates'
      ? 'edit' : current);
    if (phase !== 'uncertain' && phase !== 'saving' && phase !== 'checking') {
      operationId.current = null;
    }
  };

  const set = (field: keyof CaptureDraft, value: string) => {
    retireLookup();
    setDraft((current) => ({ ...current, [field]: value }));
    setErrors((current) => {
      if (!current[field]) return current;
      const next = { ...current };
      delete next[field];
      return next;
    });
  };

  const validate = (): Record<string, string> => {
    const next: Record<string, string> = {};
    if (draft.phone.trim() && !normalizeIndianMobile(draft.phone)) {
      next.phone = 'Enter a valid mobile number with a supported country code.';
    }
    if (!draft.sourceChannel.trim()) {
      next.sourceChannel = 'Record where this enquiry came from.';
    }
    if (draft.budgetMin && draft.budgetMax
      && Number(draft.budgetMin) > Number(draft.budgetMax)) {
      next.budgetMax = 'Maximum budget must be at least the minimum.';
    }
    if (!scope) next.scope = 'A current City/Team intake capability is required.';
    return next;
  };

  const buildCommand = (
    decision?: 'ATTACH_ENQUIRY' | 'KEEP_DISTINCT',
    candidate?: LeadDuplicateCandidate | null
  ): Record<string, unknown> => ({
    cityId: scope?.cityId ?? null,
    teamId: scope?.teamId ?? null,
    customerName: draft.customerName.trim() || null,
    contactPhone: normalizeIndianMobile(draft.phone),
    customerSaid: draft.customerSaid.trim() || null,
    intent: draft.intent,
    propertyType: draft.propertyType.trim() || null,
    bhk: draft.bhk.trim() || null,
    budgetMin: draft.budgetMin ? Number(draft.budgetMin) : null,
    budgetMax: draft.budgetMax ? Number(draft.budgetMax) : null,
    currency: 'INR',
    targetMoveInDate: draft.targetMoveInDate || null,
    furnishing: draft.furnishing.trim() || null,
    tenantProfile: draft.tenantProfile.trim() || null,
    parkingRequired: draft.parkingRequired ? draft.parkingRequired === 'true' : null,
    petsAllowed: draft.petsAllowed ? draft.petsAllowed === 'true' : null,
    liftRequired: draft.liftRequired ? draft.liftRequired === 'true' : null,
    floorRequirement: draft.floorRequirement.trim() || null,
    preferredLocalityIds: [],
    followUpAt: null,
    followUpZoneId: null,
    sourceChannel: draft.sourceChannel.trim(),
    campaignRef: draft.campaignRef.trim() || null,
    referralRef: draft.referralRef.trim() || null,
    ...(decision ? {
      duplicateDecision: decision,
      existingLeadId: candidate?.leadId ?? null,
      expectedExistingLeadVersion: candidate?.version ?? null,
      reasonCode: decision === 'KEEP_DISTINCT'
        ? 'OPERATOR_CONFIRMED_DISTINCT_ENQUIRY'
        : 'OPERATOR_CONFIRMED_EXISTING_ENQUIRY'
    } : {})
  });

  const checkDuplicates = async () => {
    if (inFlight.current || lookupInFlight.current) return;
    const nextErrors = validate();
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length || !scope) return;
    const submission = buildCommand();
    const generation = draftGeneration.current + 1;
    draftGeneration.current = generation;
    const phone = normalizeIndianMobile(draft.phone);
    if (!phone) {
      await save(submission, generation);
      return;
    }
    const session = captureOperationsSession();
    if (!session) {
      setNotice('Your session has ended. Sign in again before saving this enquiry.');
      return;
    }
    lookupInFlight.current = true;
    duplicateController.current?.abort();
    const controller = new AbortController();
    duplicateController.current = controller;
    setPhase('checking-duplicates');
    setNotice(null);
    try {
      const result = await operationsLeadService.duplicateCandidates(
        scope.cityId,
        phone,
        { session, signal: controller.signal }
      );
      if (!mounted.current || controller.signal.aborted
        || generation !== draftGeneration.current
        || !isOperationsSessionActive(session)) return;
      setDuplicateCandidates(result.candidates);
      setExistingAccountMatch(result.existingAccountMatchAvailable);
      if (result.candidates.length) {
        setPhase('duplicates');
        requestAnimationFrame(() => document.getElementById('ld-dup-title')?.focus());
      } else {
        lookupInFlight.current = false;
        await save(submission, generation);
      }
    } catch (cause) {
      if (!mounted.current || controller.signal.aborted
        || generation !== draftGeneration.current
        || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)) return;
      setPhase('edit');
      setNotice(failureMessage(cause, 'Duplicate review could not be completed.'));
    } finally {
      if (generation === draftGeneration.current) {
        lookupInFlight.current = false;
      }
    }
  };

  const save = async (
    command = buildCommand(),
    generation = draftGeneration.current
  ) => {
    if (inFlight.current) return;
    if (generation !== draftGeneration.current) return;
    const session = captureOperationsSession();
    if (!session) {
      setNotice('Your session has ended. Sign in again before saving this enquiry.');
      return;
    }
    operationId.current ??= requestId();
    dispatched.current = false;
    inFlight.current = true;
    setPhase('saving');
    setNotice(null);
    try {
      const lead = await operationsLeadService.capture(
        command,
        operationId.current,
        {
          session,
          onDispatch: () => { dispatched.current = true; }
        }
      );
      if (!mounted.current || !isOperationsSessionActive(session)) return;
      completed.current = true;
      operationId.current = null;
      onCreated(lead);
    } catch (cause) {
      if (!mounted.current || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)) return;
      const status = failureStatus(cause);
      if (dispatched.current && (status === undefined || status >= 500)) {
        setPhase('uncertain');
        setNotice('We couldn’t confirm the save. Check the same request before trying again.');
      } else {
        setPhase(duplicateCandidates.length ? 'duplicates' : 'edit');
        setNotice(failureMessage(cause, 'The enquiry was not saved. Your entries are still here.'));
      }
    } finally {
      inFlight.current = false;
    }
  };

  const checkSaved = async () => {
    if (!operationId.current || inFlight.current) return;
    const session = captureOperationsSession();
    if (!session) {
      setNotice('Your session has ended.');
      return;
    }
    inFlight.current = true;
    setPhase('checking');
    try {
      const receipt = await operationsLeadService.commandReceipt(
        operationId.current,
        'LEAD_CAPTURE',
        { session }
      );
      if (!mounted.current || !isOperationsSessionActive(session)) return;
      if (receipt.state === 'RECORDED' && receipt.resultReference) {
        const leadId = Number(receipt.resultReference);
        if (!Number.isSafeInteger(leadId) || leadId <= 0) {
          throw new Error('The saved Lead reference is invalid.');
        }
        const lead = await operationsLeadService.detail(leadId, { session });
        if (!mounted.current || !isOperationsSessionActive(session)) return;
        operationId.current = null;
        onCreated(lead);
        return;
      }
      setPhase('duplicates');
      setNotice('No saved result exists for this request. You can safely submit the same enquiry again.');
    } catch (cause) {
      if (!mounted.current || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)) return;
      setPhase('uncertain');
      setNotice(failureMessage(cause,
        'The saved result could not be checked. Do not create a second enquiry yet.'));
    } finally {
      inFlight.current = false;
    }
  };

  const requestNavigation = (navigation: { kind: 'back' } | { kind: 'open'; leadId: number }) => {
    if (dirty && !completed.current) {
      setPendingNavigation(navigation);
      return;
    }
    if (navigation.kind === 'open') onOpenExisting(navigation.leadId);
    else onBack();
  };

  const clearForm = () => {
    retireLookup();
    operationId.current = null;
    dispatched.current = false;
    setDraft(EMPTY_CAPTURE);
    setErrors({});
    setDuplicateCandidates([]);
    setSelectedCandidateId(null);
    setExistingAccountMatch(false);
    setNotice(null);
    setPhase('edit');
  };

  const keepEditing = () => {
    const restoreBrowserGuard = pendingNavigation?.kind === 'browser-back';
    setPendingNavigation(null);
    if (restoreBrowserGuard && !completed.current) {
      window.history.pushState({
        ...(window.history.state ?? {}),
        pathomeLeadCaptureGuard: true
      }, '');
      browserBackGuardPushed.current = true;
    }
  };

  if (!opportunities.length) {
    return (
      <div className="oe-stack">
        <button type="button" className="oe-back" onClick={() => requestNavigation({ kind: 'back' })}>
          <ArrowLeft size={14} aria-hidden="true" />Back to Leads
        </button>
        <StateMessage
          icon={<Lock size={20} />}
          title="Enquiry capture is unavailable"
          body="Capturing an enquiry requires a current Team or City assignment. Cross-City review alone does not provide a City and Team."
        />
      </div>
    );
  }

  return (
    <div className="oe-stack">
      <button type="button" className="oe-back" onClick={() => requestNavigation({ kind: 'back' })}>
        <ArrowLeft size={14} aria-hidden="true" />Back to Leads
      </button>
      <PageHeader
        eyebrow="New enquiry"
        title="Capture an enquiry"
        description="Record only what the customer said. Unknown fields stay empty and are never guessed."
      />

      {notice ? <Notice tone="warn" live title={notice} /> : null}

      {phase === 'duplicates' ? (
        <section className="ld-card" aria-labelledby="ld-dup-title">
          <h2 id="ld-dup-title" tabIndex={-1}>
            {duplicateCandidates.length
              ? `This number appears on ${duplicateCandidates.length} visible Lead${duplicateCandidates.length === 1 ? '' : 's'}`
              : 'Review the duplicate lookup result'}
          </h2>
          <p className="oe-lede">
            A phone match does not prove the same person. Existing Leads remain separate records;
            Pathome does not merge customers or accounts from a matching number.
          </p>
          {existingAccountMatch ? (
            <Notice title="A Pathome account also has this mobile number">
              Account matching is diagnostic only. This action does not create or link a customer account.
            </Notice>
          ) : null}
          <ul className="ld-candidate-list">
            {duplicateCandidates.map((candidate) => (
              <li
                key={candidate.leadId}
                className="ld-candidate"
                data-selected={selectedCandidateId === candidate.leadId || undefined}
              >
                <div>
                  <strong>{candidate.customerName || 'Name not captured'}</strong>
                  <p>
                    Lead #{candidate.leadId} · {statusMeta(candidate.status).label}
                    {candidate.currentlyOwnedByActor ? ' · Owned by you' : ''}
                  </p>
                  <p className="oe-mono">{candidate.maskedContact || 'Masked contact unavailable'}</p>
                </div>
                <div className="ld-actions">
                  <button
                    type="button"
                    className="oe-btn oe-btn--primary oe-btn--sm"
                    aria-pressed={selectedCandidateId === candidate.leadId}
                    onClick={() => setSelectedCandidateId(candidate.leadId)}
                  >
                    {selectedCandidateId === candidate.leadId ? 'Selected to attach' : 'Attach this enquiry'}
                  </button>
                  <button
                    type="button"
                    className="oe-btn oe-btn--secondary oe-btn--sm"
                    onClick={() => requestNavigation({ kind: 'open', leadId: candidate.leadId })}
                  >
                    Open Lead
                  </button>
                </div>
              </li>
            ))}
          </ul>
          <Notice title="Choose how to resolve this matching enquiry">
            Attaching keeps the selected Lead’s existing requirements, contact, ownership and history,
            and records these newly entered requirements as protected enquiry evidence. Keeping a separate
            Lead never merges customers or accounts.
          </Notice>
          <div className="ld-actions">
            <button
              type="button"
              className="oe-btn oe-btn--primary oe-btn--md"
              disabled={selectedCandidateId === null}
              onClick={() => {
                const candidate = duplicateCandidates.find(
                  (entry) => entry.leadId === selectedCandidateId
                ) ?? null;
                if (!candidate) return;
                void save(
                  buildCommand('ATTACH_ENQUIRY', candidate),
                  draftGeneration.current
                );
              }}
            >
              Attach to selected Lead
            </button>
            <button
              type="button"
              className="oe-btn oe-btn--secondary oe-btn--md"
              onClick={() => {
                void save(
                  buildCommand('KEEP_DISTINCT', selectedCandidateId === null
                    ? null
                    : duplicateCandidates.find(
                        (entry) => entry.leadId === selectedCandidateId
                      ) ?? null),
                  draftGeneration.current
                );
              }}
            >
              Keep as a separate Lead
            </button>
            <button
              type="button"
              className="oe-btn oe-btn--ghost oe-btn--md"
              onClick={() => requestNavigation({ kind: 'back' })}
            >
              Back to Leads
            </button>
          </div>
        </section>
      ) : phase === 'uncertain' || phase === 'checking' ? (
        <Notice
          tone="warn"
          live
          title="The saved result is not confirmed"
          actions={(
            <button
              type="button"
              className="oe-btn oe-btn--primary oe-btn--md"
              disabled={phase === 'checking'}
              onClick={() => void checkSaved()}
            >
              {phase === 'checking' ? 'Checking…' : 'Check whether it was saved'}
            </button>
          )}
        >
          Checking the saved result cannot create a second Lead.
        </Notice>
      ) : null}

      <form
        className="ld-card ld-form"
        noValidate
        aria-label="Capture enquiry"
        onSubmit={(event) => {
          event.preventDefault();
          if (phase === 'duplicates') return;
          void checkDuplicates();
        }}
      >
        <fieldset className="ld-fieldset" disabled={phase === 'saving' || phase === 'uncertain' || phase === 'checking'}>
          <legend>City and team</legend>
          {opportunities.length > 1 ? (
            <label className="oe-field-stack">
              <span className="oe-field-label">City and Team</span>
              <select
                className="oe-input ld-input"
                value={scopeKey}
                onChange={(event) => {
                  retireLookup();
                  setScopeKey(event.target.value);
                }}
                aria-invalid={Boolean(errors.scope)}
              >
                {opportunities.map((opportunity) => (
                  <option key={opportunity.key} value={opportunity.key}>
                    {opportunity.cityName}
                    {opportunity.teamName ? ` · ${opportunity.teamName}` : ' · City intake'}
                  </option>
                ))}
              </select>
            </label>
          ) : (
            <p className="ld-scope-readback">
              {scope?.cityName}{scope?.teamName ? ` · ${scope.teamName}` : ' · City intake'}
            </p>
          )}
          {errors.scope ? <p className="oe-add-err" role="alert">{errors.scope}</p> : null}
        </fieldset>

        <fieldset className="ld-fieldset" disabled={phase === 'saving' || phase === 'uncertain' || phase === 'checking'}>
          <legend>Customer and source</legend>
          <div className="ld-grid">
            <label className="oe-field-stack">
              <span className="oe-field-label">Phone number</span>
              <input
                className="oe-input ld-input"
                type="tel"
                inputMode="tel"
                autoComplete="off"
                value={draft.phone}
                aria-invalid={Boolean(errors.phone)}
                onChange={(event) => set('phone', event.target.value)}
              />
              {errors.phone ? <span className="oe-add-err">{errors.phone}</span> : null}
              <span className="oe-footnote">
                Leave this blank when the customer has not provided a number. Sharing remains unavailable
                until a number and destination are confirmed.
              </span>
            </label>
            <label className="oe-field-stack">
              <span className="oe-field-label">Customer name</span>
              <input
                className="oe-input ld-input"
                autoComplete="off"
                value={draft.customerName}
                onChange={(event) => set('customerName', event.target.value)}
              />
            </label>
            <label className="oe-field-stack">
              <span className="oe-field-label">Source *</span>
              <input
                className="oe-input ld-input"
                value={draft.sourceChannel}
                placeholder="Phone call, walk-in, referral…"
                aria-invalid={Boolean(errors.sourceChannel)}
                onChange={(event) => set('sourceChannel', event.target.value)}
              />
              {errors.sourceChannel ? <span className="oe-add-err">{errors.sourceChannel}</span> : null}
            </label>
            <label className="oe-field-stack">
              <span className="oe-field-label">Campaign reference</span>
              <input
                className="oe-input ld-input"
                value={draft.campaignRef}
                onChange={(event) => set('campaignRef', event.target.value)}
              />
            </label>
            <label className="oe-field-stack ld-span">
              <span className="oe-field-label">What the customer said</span>
              <textarea
                className="oe-input ld-input"
                value={draft.customerSaid}
                onChange={(event) => set('customerSaid', event.target.value)}
              />
            </label>
          </div>
        </fieldset>

        <fieldset className="ld-fieldset" disabled={phase === 'saving' || phase === 'uncertain' || phase === 'checking'}>
          <legend>Requirements</legend>
          <div className="ld-grid">
            <label className="oe-field-stack">
              <span className="oe-field-label">Intent</span>
              <select
                className="oe-input ld-input"
                value={draft.intent}
                onChange={(event) => set('intent', event.target.value)}
              >
                <option value="UNSPECIFIED">Unknown</option>
                <option value="RENT">Rent</option>
                <option value="BUY">Buy</option>
              </select>
            </label>
            <label className="oe-field-stack">
              <span className="oe-field-label">Property type</span>
              <input
                className="oe-input ld-input"
                value={draft.propertyType}
                onChange={(event) => set('propertyType', event.target.value)}
              />
            </label>
            <label className="oe-field-stack">
              <span className="oe-field-label">BHK</span>
              <input
                className="oe-input ld-input"
                value={draft.bhk}
                placeholder="Unknown"
                onChange={(event) => set('bhk', event.target.value)}
              />
            </label>
            <label className="oe-field-stack">
              <span className="oe-field-label">Furnishing</span>
              <input
                className="oe-input ld-input"
                value={draft.furnishing}
                placeholder="Unknown"
                onChange={(event) => set('furnishing', event.target.value)}
              />
            </label>
            <label className="oe-field-stack">
              <span className="oe-field-label">Budget from (INR)</span>
              <input
                className="oe-input ld-input"
                inputMode="numeric"
                value={draft.budgetMin}
                onChange={(event) => set('budgetMin', event.target.value.replace(/\D/g, ''))}
              />
            </label>
            <label className="oe-field-stack">
              <span className="oe-field-label">Budget up to (INR)</span>
              <input
                className="oe-input ld-input"
                inputMode="numeric"
                value={draft.budgetMax}
                aria-invalid={Boolean(errors.budgetMax)}
                onChange={(event) => set('budgetMax', event.target.value.replace(/\D/g, ''))}
              />
              {errors.budgetMax ? <span className="oe-add-err">{errors.budgetMax}</span> : null}
            </label>
            <label className="oe-field-stack">
              <span className="oe-field-label">Move-in date</span>
              <input
                className="oe-input ld-input"
                type="date"
                value={draft.targetMoveInDate}
                onChange={(event) => set('targetMoveInDate', event.target.value)}
              />
            </label>
            <label className="oe-field-stack">
              <span className="oe-field-label">Tenant profile</span>
              <input
                className="oe-input ld-input"
                value={draft.tenantProfile}
                onChange={(event) => set('tenantProfile', event.target.value)}
              />
            </label>
            {([
              ['parkingRequired', 'Parking required'],
              ['petsAllowed', 'Pets allowed'],
              ['liftRequired', 'Lift required']
            ] as const).map(([field, label]) => (
              <label key={field} className="oe-field-stack">
                <span className="oe-field-label">{label}</span>
                <select
                  className="oe-input ld-input"
                  value={draft[field]}
                  onChange={(event) => set(field, event.target.value)}
                >
                  <option value="">Unknown</option>
                  <option value="true">Yes</option>
                  <option value="false">No</option>
                </select>
              </label>
            ))}
            <label className="oe-field-stack ld-span">
              <span className="oe-field-label">Floor requirement</span>
              <input
                className="oe-input ld-input"
                value={draft.floorRequirement}
                onChange={(event) => set('floorRequirement', event.target.value)}
              />
            </label>
          </div>
        </fieldset>

        <div className="ld-actions ld-actions--sticky">
          <button
            type="submit"
            className="oe-btn oe-btn--primary oe-btn--md"
            disabled={phase === 'checking-duplicates' || phase === 'saving' || phase === 'uncertain' || phase === 'checking'}
          >
            {phase === 'checking-duplicates'
              ? 'Checking duplicates…'
              : phase === 'saving'
                ? 'Saving…'
                : 'Review and save enquiry'}
          </button>
          <button
            type="button"
            className="oe-btn oe-btn--secondary oe-btn--md"
            disabled={phase === 'saving' || phase === 'uncertain' || phase === 'checking'}
            onClick={clearForm}
          >
            Clear form
          </button>
        </div>
      </form>

      {pendingNavigation ? (
        <Modal
          title="Leave this unsaved enquiry?"
          onClose={keepEditing}
          footer={(
            <>
              <button
                type="button"
                className="oe-btn oe-btn--secondary oe-btn--md"
                onClick={keepEditing}
              >
                Keep editing
              </button>
              <button
                type="button"
                className="oe-btn oe-btn--danger-outline oe-btn--md"
                onClick={() => {
                  const navigation = pendingNavigation;
                  setPendingNavigation(null);
                  draftGeneration.current += 1;
                  duplicateController.current?.abort();
                  allowNavigation.current = true;
                  if (navigation.kind === 'external') navigation.leave();
                  else if (navigation.kind === 'browser-back') window.history.back();
                  else if (navigation.kind === 'open') onOpenExisting(navigation.leadId);
                  else onBack();
                }}
              >
                Discard and leave
              </button>
            </>
          )}
        >
          <p>
            Your draft has not been saved. Leaving now discards every field in this enquiry.
          </p>
        </Modal>
      ) : null}
    </div>
  );
};

const LeadDetailScreen: React.FC<{
  leadId: number;
  actorId: number;
  capabilities: StaffCapabilities | null;
  onBack: () => void;
  onShare: (tab: 'share' | 'history') => void;
}> = ({ leadId, actorId, capabilities, onBack, onShare }) => {
  const [state, setState] = useState<'loading' | 'ready' | 'missing' | 'error' | 'signed-out'>('loading');
  const [lead, setLead] = useState<LeadDetail | null>(null);
  const [activities, setActivities] = useState<LeadActivity[]>([]);
  const [activityPage, setActivityPage] = useState(0);
  const [activityPages, setActivityPages] = useState(0);
  const [preferences, setPreferences] = useState<LeadContactPreference[]>([]);
  const [shareCount, setShareCount] = useState(0);
  const [message, setMessage] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [revealOpen, setRevealOpen] = useState(false);
  const mounted = useRef(true);
  const requestNumber = useRef(0);
  const controller = useRef<AbortController | null>(null);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      controller.current?.abort();
    };
  }, []);

  const load = useCallback(async (quiet = false): Promise<LeadDetail | null> => {
    const session = captureOperationsSession();
    const sequence = requestNumber.current + 1;
    requestNumber.current = sequence;
    controller.current?.abort();
    const next = new AbortController();
    controller.current = next;
    if (!session) {
      setState('signed-out');
      setLead(null);
      return null;
    }
    if (!quiet) setState('loading');
    setMessage('');
    try {
      const [detail, activity, preferenceHistory, history] = await Promise.all([
        operationsLeadService.detail(leadId, { session, signal: next.signal }),
        operationsLeadService.activities(leadId, 0, { session, signal: next.signal }),
        operationsLeadService.contactPreferences(leadId, { session, signal: next.signal }),
        operationsLeadService.shareHistory(leadId, 0, 1, { session, signal: next.signal })
      ]);
      if (!mounted.current || next.signal.aborted || sequence !== requestNumber.current
        || !isOperationsSessionActive(session)) return null;
      setLead(detail);
      setActivities(activity.items);
      setActivityPage(0);
      setActivityPages(activity.totalPages);
      setPreferences(preferenceHistory);
      setShareCount(history.totalElements);
      setState('ready');
      return detail;
    } catch (cause) {
      if (!mounted.current || next.signal.aborted || sequence !== requestNumber.current
        || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)) return null;
      if (failureStatus(cause) === 401) {
        setState('signed-out');
        setLead(null);
      } else if (isConcealed(cause)) {
        setState('missing');
        setLead(null);
        setActivities([]);
        setPreferences([]);
      } else {
        setMessage(failureMessage(cause, 'This Lead could not be loaded.'));
        setState('error');
      }
      return null;
    }
  }, [leadId]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadOlderActivity = async () => {
    if (!lead || activityPage + 1 >= activityPages) return;
    const session = captureOperationsSession();
    if (!session) return;
    const page = activityPage + 1;
    try {
      const result = await operationsLeadService.activities(leadId, page, { session });
      if (!mounted.current || !isOperationsSessionActive(session)) return;
      setActivities((current) => [...current, ...result.items]);
      setActivityPage(page);
      setActivityPages(result.totalPages);
    } catch (cause) {
      if (mounted.current && isOperationsSessionActive(session)) {
        setNotice(failureMessage(cause, 'Older activity could not be loaded.'));
      }
    }
  };

  if (state === 'loading') {
    return (
      <div className="oe-stack" aria-busy="true">
        <button type="button" className="oe-back" onClick={onBack}>
          <ArrowLeft size={14} aria-hidden="true" />Back to Leads
        </button>
        <SkeletonRows rows={3} />
        <SkeletonRows rows={4} />
      </div>
    );
  }
  if (state === 'signed-out') {
    return (
      <StateMessage
        icon={<Lock size={20} />}
        title="Your session has ended"
        body="Sign in again to reload this Lead. Nothing was changed."
      />
    );
  }
  if (state === 'missing') {
    return (
      <div className="oe-stack">
        <button type="button" className="oe-back" onClick={onBack}>
          <ArrowLeft size={14} aria-hidden="true" />Back to Leads
        </button>
        <StateMessage
          icon={<Lock size={20} />}
          title="This Lead isn’t available"
          body="It may not exist, or it may no longer be visible through your current ownership and Team access."
        />
      </div>
    );
  }
  if (state === 'error' || !lead) {
    return (
      <StateMessage
        tone="warn"
        icon={<CircleAlert size={20} />}
        title={`We couldn’t load Lead #${leadId}`}
        body={message || 'Check your connection and try again. Nothing was changed.'}
      >
        <button type="button" className="oe-btn oe-btn--primary oe-btn--md" onClick={() => void load()}>
          <RefreshCw size={14} aria-hidden="true" />Try again
        </button>
      </StateMessage>
    );
  }

  const status = statusMeta(lead.status);
  const active = leadActiveForCrmMutation(lead);
  const editable = active && leadCanEdit(lead, actorId, capabilities);
  const revealable = leadCanRevealContact(lead, actorId, capabilities);
  const shareable = leadShareEligible(lead, actorId, capabilities);
  const latestPreference = preferences[0] ?? null;
  const manualEnquiry = lead.sourceAttributions.find((source) => source.manualEnquiry)?.manualEnquiry;
  const qualificationGapSummary = leadQualificationGapSummary(lead.qualificationGaps);

  return (
    <div className="oe-stack">
      <button type="button" className="oe-back" onClick={onBack}>
        <ArrowLeft size={14} aria-hidden="true" />Back to Leads
      </button>

      <header className="ld-head">
        <div className="ld-head-text">
          <p className="oe-eyebrow oe-mono">Lead #{lead.id} · version {lead.version}</p>
          <h1 id="oe-page-title" tabIndex={-1} className="oe-h1">{displayName(lead)}</h1>
          <div className="ld-head-meta">
            <Tone tone={status.tone}>{status.label}</Tone>
            <ScopeLine city={lead.cityName || 'City not recorded'} team={lead.teamName || 'Team not assigned'} />
          </div>
        </div>
        <div className="ld-head-actions">
          <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" onClick={() => void load(true)}>
            <RefreshCw size={14} aria-hidden="true" />Refresh
          </button>
          {shareable ? (
            <button type="button" className="oe-btn oe-btn--primary oe-btn--md" onClick={() => onShare('share')}>
              <MessageCircle size={16} aria-hidden="true" />Share properties
            </button>
          ) : null}
        </div>
      </header>

      {notice ? (
        <Notice
          tone="success"
          live
          title={notice}
          actions={<button type="button" className="oe-link" onClick={() => setNotice(null)}>Dismiss</button>}
        />
      ) : null}
      {manualEnquiry ? (
        <Notice tone="success" title="A manual enquiry is attached to this Lead">
          Captured {formatDateTime(manualEnquiry.capturedAt) || 'at an unrecorded time'} from
          {' '}{manualEnquiry.channel || 'the operator-recorded source'}. Its newly entered requirements
          stay separate and do not overwrite this Lead’s current requirements.
        </Notice>
      ) : null}
      {!active ? (
        <Notice title="This Lead is no longer editable in its current lifecycle state">
          History and source evidence remain readable. No closed or legacy state is reopened by this screen.
        </Notice>
      ) : !editable ? (
        <Notice title="View only with your current access">
          Editing and sharing are limited by the Lead owner, Team access and current staff profile.
          Pathome checks this access when each action is saved.
        </Notice>
      ) : null}
      {lead.qualificationGaps.length ? (
        <Notice
          title={`${lead.qualificationGaps.length} ${lead.qualificationGaps.length === 1 ? 'detail is' : 'details are'} still unknown`}
          actions={editable ? (
            <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={() => setEditing(true)}>
              Fill in requirements
            </button>
          ) : undefined}
        >
          {qualificationGapSummary}. Ask on the next call. Unknown values are never guessed.
        </Notice>
      ) : null}

      <div className="ld-layout">
        <div className="ld-col">
          <LeadNextAction lead={lead} editable={editable} onEdit={() => setEditing(true)} />

          <section className="ld-card" aria-labelledby="ld-requirements">
            <header className="ld-card-head">
              <h2 id="ld-requirements">Requirements</h2>
              {editable && !editing ? (
                <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={() => setEditing(true)}>
                  Edit
                </button>
              ) : null}
            </header>
            {editing ? (
              <LeadRequirementsEditor
                lead={lead}
                onCancel={() => setEditing(false)}
                onUpdated={(updated) => {
                  setLead(updated);
                  setEditing(false);
                  setNotice('Requirements saved');
                  void load(true);
                }}
                onConflict={async () => {
                  await load(true);
                }}
                onAccessLost={() => {
                  setLead(null);
                  setEditing(false);
                  setState('missing');
                }}
              />
            ) : (
              <dl className="oe-fields">
                <LeadField label="Customer said" wide>{lead.customerSaid || <span className="oe-quiet">Not captured</span>}</LeadField>
                <LeadField label="Intent">{lead.intent === 'UNSPECIFIED' ? <span className="oe-quiet">Unknown</span> : lead.intent}</LeadField>
                <LeadField label="Property type">{lead.propertyType || <span className="oe-quiet">Unknown</span>}</LeadField>
                <LeadField label="BHK">{lead.bhk || <span className="oe-quiet">Unknown</span>}</LeadField>
                <LeadField label="Budget">{budgetLabel(lead)}</LeadField>
                <LeadField label="Furnishing">{lead.furnishing || <span className="oe-quiet">Unknown</span>}</LeadField>
                <LeadField label="Move-in">{lead.targetMoveInDate || <span className="oe-quiet">Unknown</span>}</LeadField>
                <LeadField label="Tenant profile">{lead.tenantProfile || <span className="oe-quiet">Unknown</span>}</LeadField>
                <LeadField label="Floor">{lead.floorRequirement || <span className="oe-quiet">Unknown</span>}</LeadField>
                <LeadField label="Parking">{booleanLabel(lead.parkingRequired)}</LeadField>
                <LeadField label="Pets">{booleanLabel(lead.petsAllowed)}</LeadField>
                <LeadField label="Lift">{booleanLabel(lead.liftRequired)}</LeadField>
                <LeadField label="Preferred locality IDs" wide>
                  {lead.preferredLocalityIds.length
                    ? lead.preferredLocalityIds.map((id) => `#${id}`).join(', ')
                    : <span className="oe-quiet">Unknown</span>}
                </LeadField>
              </dl>
            )}
          </section>

          <LeadActivityComposer
            lead={lead}
            disabled={!editable}
            onSaved={() => {
              setNotice('Call or note saved');
              void load(true);
            }}
            onConflict={async () => {
              setNotice('This Lead changed before the call or note could be saved. Current state was refreshed.');
              await load(true);
            }}
          />

          <section className="ld-card" aria-labelledby="ld-activity">
            <h2 id="ld-activity">Activity</h2>
            {activities.length ? (
              <ol className="ld-timeline">
                {activities.map((activity) => (
                  <li key={activity.id} className="ld-timeline-item">
                    <span className="ld-timeline-dot" aria-hidden="true" />
                    <p className="ld-timeline-title">{activityTypeLabel(activity.type)}</p>
                    {activity.note ? <p>{activityNoteLabel(activity.note)}</p> : null}
                    <p className="ld-meta">{formatDateTime(activity.occurredAt) || 'Time not recorded'}</p>
                  </li>
                ))}
              </ol>
            ) : <p className="oe-quiet">No activity recorded.</p>}
            {activityPage + 1 < activityPages ? (
              <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" onClick={() => void loadOlderActivity()}>
                Load older activity
              </button>
            ) : null}
          </section>
        </div>

        <aside className="ld-col" aria-label="Lead summary">
          <section className="ld-card" aria-labelledby="ld-contact">
            <h2 id="ld-contact">Contact</h2>
            <p className="ld-contact-number oe-mono">{lead.maskedContact || 'Masked contact unavailable'}</p>
            {revealable ? (
              <button type="button" className="oe-btn oe-btn--secondary oe-btn--md oe-btn--block" onClick={() => setRevealOpen(true)}>
                <Eye size={16} aria-hidden="true" />Show phone number
              </button>
            ) : (
              <p className="oe-locked"><Lock size={14} aria-hidden="true" />Only the current Lead owner with the exact Team grant can reveal the number.</p>
            )}
            <p className="oe-footnote">The full number appears only in the reveal dialog and is cleared when the dialog closes.</p>
          </section>

          <section className="ld-card" aria-labelledby="ld-sharing-summary">
            <h2 id="ld-sharing-summary">Property sharing</h2>
            {latestPreference ? (
              <dl className="ld-mini">
                <div><dt>WhatsApp</dt><dd>{latestPreference.destinationChoice === 'NONE' ? 'No WhatsApp number' : latestPreference.destinationChoice === 'SAME' ? 'Same as contact number' : latestPreference.destinationChoice === 'DIFFERENT' ? 'Different number' : 'Not recorded'}</dd></div>
                <div><dt>Number</dt><dd className="oe-mono">{latestPreference.maskedDestination || 'Not recorded'}</dd></div>
                <div><dt>Customer permission</dt><dd>{latestPreference.consentStatus === 'GRANTED' ? 'Agreed to receive property links' : latestPreference.consentStatus === 'REVOKED' ? 'Withdrawn' : latestPreference.consentStatus === 'DISPUTED' ? 'Disputed' : 'Not confirmed'}</dd></div>
                <div><dt>Recorded</dt><dd>{formatDateTime(latestPreference.recordedAt) || 'Time not recorded'}</dd></div>
              </dl>
            ) : <p className="oe-quiet">No customer permission recorded.</p>}
            <button type="button" className="oe-btn oe-btn--secondary oe-btn--md oe-btn--block" onClick={() => onShare('history')}>
              <History size={15} aria-hidden="true" />
              Share history {shareCount ? `(${shareCount})` : ''}
            </button>
          </section>

          <section className="ld-card" aria-labelledby="ld-source">
            <h2 id="ld-source">Enquiry source</h2>
            {lead.sourceAttributions.length ? (
              <ul className="ld-source-list">
                {lead.sourceAttributions.map((source) => (
                  <li key={source.sourceEventId}>
                    <strong>{source.channel || source.eventKind}</strong>
                    <p>{source.eventKind} · {source.state}</p>
                    <p className="oe-meta">{formatDateTime(source.receivedAt) || 'Received time not recorded'}</p>
                    {source.campaignRef ? <p>Campaign: {source.campaignRef}</p> : null}
                    {source.referralRef ? <p>Referral: {source.referralRef}</p> : null}
                  </li>
                ))}
              </ul>
            ) : <p className="oe-quiet">No enquiry source recorded.</p>}
          </section>

          <section className="ld-card" aria-labelledby="ld-owner">
            <h2 id="ld-owner">Ownership</h2>
            <dl className="ld-mini">
              <div><dt>Owner</dt><dd>{lead.ownerDisplayName || 'Unassigned'}</dd></div>
              <div><dt>Team</dt><dd>{lead.teamName || 'Not assigned'}</dd></div>
              <div><dt>Last activity</dt><dd>{formatDateTime(lead.lastActivityAt) || 'Not recorded'}</dd></div>
              <div><dt>Follow-up</dt><dd>{formatDateTime(lead.followUpAt) || 'Not scheduled'}</dd></div>
            </dl>
          </section>
        </aside>
      </div>

      {revealOpen ? (
        <RevealContactDialog
          leadId={lead.id}
          customerName={displayName(lead)}
          onClose={() => setRevealOpen(false)}
        />
      ) : null}
    </div>
  );
};

const LeadNextAction: React.FC<{
  lead: LeadDetail;
  editable: boolean;
  onEdit: () => void;
}> = ({ lead, editable, onEdit }) => {
  const gapSummary = leadQualificationGapSummary(lead.qualificationGaps);
  const followUp = formatDateTime(lead.followUpAt);
  return (
    <section className="ld-card ld-next" aria-labelledby="ld-next-title">
      <header className="ld-card-head">
        <h2 id="ld-next-title">Next action</h2>
        {editable ? (
          <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={onEdit}>
            Edit details & follow-up
          </button>
        ) : null}
      </header>
      <p className="ld-next-text">
        {lead.qualificationGaps.length
          ? `Ask about ${gapSummary} with the customer.`
          : 'No qualification gaps recorded.'}
      </p>
      <p className="oe-footnote">
        {followUp ? `Follow-up ${followUp}` : 'No follow-up scheduled.'}
      </p>
    </section>
  );
};

const LeadField: React.FC<{
  label: string;
  wide?: boolean;
  children: React.ReactNode;
}> = ({ label, wide = false, children }) => (
  <div className={`oe-field${wide ? ' oe-field--wide' : ''}`}>
    <dt>{label}</dt>
    <dd>{children}</dd>
  </div>
);

const budgetLabel = (lead: LeadDetail): React.ReactNode => {
  if (lead.budgetMin === null && lead.budgetMax === null) return <span className="oe-quiet">Unknown</span>;
  const money = (value: number) => `${lead.currency || 'INR'} ${value.toLocaleString('en-IN')}`;
  if (lead.budgetMin !== null && lead.budgetMax !== null) {
    return `${money(lead.budgetMin)} – ${money(lead.budgetMax)}`;
  }
  return lead.budgetMin !== null ? `From ${money(lead.budgetMin)}` : `Up to ${money(lead.budgetMax as number)}`;
};

const booleanLabel = (value: boolean | null): React.ReactNode =>
  value === null ? <span className="oe-quiet">Unknown</span> : value ? 'Yes' : 'No';

const activityTypeLabel = (type: string): string => ({
  LEAD_CAPTURED: 'Enquiry captured',
  LEAD_UPDATED: 'Lead requirements updated',
  CALL_LOGGED: 'Call logged',
  NOTE_ADDED: 'Note added',
  CONTACT_PREFERENCE_RECORDED: 'Sharing consent recorded',
  PROPERTY_SHORTLIST_CREATED: 'Property shortlist saved',
  PROPERTY_SHORTLIST_UPDATED: 'Property shortlist updated',
  MANUAL_ENQUIRY_ATTACHED: 'Manual enquiry attached',
  MANUAL_ENQUIRY_KEPT_DISTINCT: 'Enquiry kept distinct',
  SHARE_PREPARED: 'Share prepared',
  SHARE_ACTIVITY: 'Share handoff activity'
} as Record<string, string>)[type] ?? type;

const activityNoteLabel = (note: string): string => ({
  OPERATOR_CONFIRMED_EXISTING_ENQUIRY: 'The operator confirmed this was the same existing enquiry.',
  OPERATOR_CONFIRMED_DISTINCT_ENQUIRY: 'The operator confirmed this was a different enquiry.',
  DUPLICATE_REVIEW_ATTACH: 'Duplicate review confirmed an existing Lead.'
} as Record<string, string>)[note] ?? note;

const LeadRequirementsEditor: React.FC<{
  lead: LeadDetail;
  onCancel: () => void;
  onUpdated: (lead: LeadDetail) => void;
  onConflict: () => Promise<void>;
  onAccessLost: () => void;
}> = ({ lead, onCancel, onUpdated, onConflict, onAccessLost }) => {
  const [values, setValues] = useState({
    customerName: lead.customerName ?? '',
    customerSaid: lead.customerSaid ?? '',
    intent: lead.intent,
    propertyType: lead.propertyType ?? '',
    bhk: lead.bhk ?? '',
    budgetMin: lead.budgetMin === null ? '' : String(lead.budgetMin),
    budgetMax: lead.budgetMax === null ? '' : String(lead.budgetMax),
    targetMoveInDate: lead.targetMoveInDate ?? '',
    furnishing: lead.furnishing ?? '',
    tenantProfile: lead.tenantProfile ?? '',
    parkingRequired: lead.parkingRequired === null ? '' : String(lead.parkingRequired),
    petsAllowed: lead.petsAllowed === null ? '' : String(lead.petsAllowed),
    liftRequired: lead.liftRequired === null ? '' : String(lead.liftRequired),
    floorRequirement: lead.floorRequirement ?? '',
    followUpAt: lead.followUpAt ?? '',
    followUpZoneId: lead.followUpZoneId ?? 'Asia/Kolkata'
  });
  const [expectedVersion, setExpectedVersion] = useState(lead.version);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [unknownOutcome, setUnknownOutcome] = useState(false);
  const dispatched = useRef(false);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  useEffect(() => {
    if (conflict) setExpectedVersion(lead.version);
  }, [conflict, lead.version]);

  const set = (field: keyof typeof values, value: string) => {
    setValues((current) => ({ ...current, [field]: value }));
    setMessage(null);
  };

  const save = async () => {
    if (busy || unknownOutcome) return;
    if (values.budgetMin && values.budgetMax
      && Number(values.budgetMin) > Number(values.budgetMax)) {
      setMessage('Maximum budget must be at least the minimum.');
      return;
    }
    const session = captureOperationsSession();
    if (!session) {
      setMessage('Your session has ended.');
      return;
    }
    const changes: Record<string, unknown> = {
      customerName: values.customerName.trim() || null,
      customerSaid: values.customerSaid.trim() || null,
      intent: values.intent,
      propertyType: values.propertyType.trim() || null,
      bhk: values.bhk.trim() || null,
      budgetMin: values.budgetMin ? Number(values.budgetMin) : null,
      budgetMax: values.budgetMax ? Number(values.budgetMax) : null,
      currency: lead.currency || 'INR',
      targetMoveInDate: values.targetMoveInDate || null,
      furnishing: values.furnishing.trim() || null,
      tenantProfile: values.tenantProfile.trim() || null,
      parkingRequired: values.parkingRequired ? values.parkingRequired === 'true' : null,
      petsAllowed: values.petsAllowed ? values.petsAllowed === 'true' : null,
      liftRequired: values.liftRequired ? values.liftRequired === 'true' : null,
      floorRequirement: values.floorRequirement.trim() || null,
      followUpAt: values.followUpAt || null,
      followUpZoneId: values.followUpAt ? values.followUpZoneId : null
    };
    dispatched.current = false;
    setBusy(true);
    setMessage(null);
    setConflict(false);
    try {
      const updated = await operationsLeadService.patch(
        lead.id,
        expectedVersion,
        changes,
        {
          session,
          onDispatch: () => { dispatched.current = true; }
        }
      );
      if (!mounted.current || !isOperationsSessionActive(session)) return;
      onUpdated(updated);
    } catch (cause) {
      if (!mounted.current || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)) return;
      const status = failureStatus(cause);
      if (status === 409) {
        setConflict(true);
        setMessage('This Lead changed while you were editing. Your entries are still here; current state was refreshed.');
        await onConflict();
      } else if (isConcealed(cause)) {
        onAccessLost();
      } else if (dispatched.current && (status === undefined || status >= 500)) {
        setUnknownOutcome(true);
        setMessage('We couldn’t confirm the save. Refresh the Lead before trying again so a successful change is not repeated.');
      } else {
        setMessage(failureMessage(cause, 'Requirements could not be saved.'));
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="ld-form" onSubmit={(event) => { event.preventDefault(); void save(); }}>
      {message ? <Notice tone={conflict || unknownOutcome ? 'warn' : 'info'} live title={message} /> : null}
      {unknownOutcome ? (
        <Notice
          tone="warn"
          title="The write outcome is unconfirmed"
          actions={(
            <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={() => void onConflict()}>
              Refresh current Lead
            </button>
          )}
        >
          Compare the refreshed values before saving again.
        </Notice>
      ) : null}
      <div className="ld-grid">
        <label className="oe-field-stack">
          <span className="oe-field-label">Customer name</span>
          <input className="oe-input ld-input" value={values.customerName} onChange={(event) => set('customerName', event.target.value)} />
        </label>
        <label className="oe-field-stack">
          <span className="oe-field-label">Intent</span>
          <select className="oe-input ld-input" value={values.intent} onChange={(event) => set('intent', event.target.value)}>
            <option value="UNSPECIFIED">Unknown</option>
            <option value="RENT">Rent</option>
            <option value="BUY">Buy</option>
          </select>
        </label>
        <label className="oe-field-stack ld-span">
          <span className="oe-field-label">What the customer said</span>
          <textarea className="oe-input ld-input" value={values.customerSaid} onChange={(event) => set('customerSaid', event.target.value)} />
        </label>
        <label className="oe-field-stack">
          <span className="oe-field-label">Property type</span>
          <input className="oe-input ld-input" value={values.propertyType} onChange={(event) => set('propertyType', event.target.value)} />
        </label>
        <label className="oe-field-stack">
          <span className="oe-field-label">BHK</span>
          <input className="oe-input ld-input" value={values.bhk} onChange={(event) => set('bhk', event.target.value)} />
        </label>
        <label className="oe-field-stack">
          <span className="oe-field-label">Budget from (INR)</span>
          <input className="oe-input ld-input" inputMode="numeric" value={values.budgetMin} onChange={(event) => set('budgetMin', event.target.value.replace(/\D/g, ''))} />
        </label>
        <label className="oe-field-stack">
          <span className="oe-field-label">Budget up to (INR)</span>
          <input className="oe-input ld-input" inputMode="numeric" value={values.budgetMax} onChange={(event) => set('budgetMax', event.target.value.replace(/\D/g, ''))} />
        </label>
        <label className="oe-field-stack">
          <span className="oe-field-label">Furnishing</span>
          <input className="oe-input ld-input" value={values.furnishing} onChange={(event) => set('furnishing', event.target.value)} />
        </label>
        <label className="oe-field-stack">
          <span className="oe-field-label">Move-in</span>
          <input className="oe-input ld-input" type="date" value={values.targetMoveInDate} onChange={(event) => set('targetMoveInDate', event.target.value)} />
        </label>
        <label className="oe-field-stack">
          <span className="oe-field-label">Tenant profile</span>
          <input className="oe-input ld-input" value={values.tenantProfile} onChange={(event) => set('tenantProfile', event.target.value)} />
        </label>
        <label className="oe-field-stack">
          <span className="oe-field-label">Floor requirement</span>
          <input className="oe-input ld-input" value={values.floorRequirement} onChange={(event) => set('floorRequirement', event.target.value)} />
        </label>
        <label className="oe-field-stack">
          <span className="oe-field-label">Follow-up (IST)</span>
          <input className="oe-input ld-input" type="datetime-local" value={toLocalDateTime(values.followUpAt)} onChange={(event) => set('followUpAt', fromLocalDateTime(event.target.value))} />
        </label>
        {([
          ['parkingRequired', 'Parking required'],
          ['petsAllowed', 'Pets allowed'],
          ['liftRequired', 'Lift required']
        ] as const).map(([field, label]) => (
          <label key={field} className="oe-field-stack">
            <span className="oe-field-label">{label}</span>
            <select className="oe-input ld-input" value={values[field]} onChange={(event) => set(field, event.target.value)}>
              <option value="">Unknown</option>
              <option value="true">Yes</option>
              <option value="false">No</option>
            </select>
          </label>
        ))}
      </div>
      <div className="ld-actions">
        <button type="submit" className="oe-btn oe-btn--primary oe-btn--md" disabled={busy || unknownOutcome}>
          {busy ? 'Saving…' : `Save version ${expectedVersion}`}
        </button>
        <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
};

const toLocalDateTime = (value: string): string => {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const offset = date.getTime() + 330 * 60_000;
  return new Date(offset).toISOString().slice(0, 16);
};

const fromLocalDateTime = (value: string): string => {
  if (!value) return '';
  const date = new Date(`${value}:00+05:30`);
  return Number.isNaN(date.getTime()) ? '' : date.toISOString();
};

const LeadActivityComposer: React.FC<{
  lead: LeadDetail;
  disabled: boolean;
  onSaved: () => void;
  onConflict: () => Promise<void>;
}> = ({ lead, disabled, onSaved, onConflict }) => {
  const [type, setType] = useState<'CALL_LOGGED' | 'NOTE_ADDED'>('CALL_LOGGED');
  const [callOutcome, setCallOutcome] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const inFlight = useRef(false);

  const save = async () => {
    if (disabled || inFlight.current) return;
    if (type === 'NOTE_ADDED' && !note.trim()) {
      setMessage('Write the note before saving.');
      return;
    }
    if (type === 'CALL_LOGGED' && !callOutcome) {
      setMessage('Choose how the call went.');
      return;
    }
    const session = captureOperationsSession();
    if (!session) {
      setMessage('Your session has ended.');
      return;
    }
    inFlight.current = true;
    setBusy(true);
    setMessage(null);
    try {
      await operationsLeadService.logActivity(
        lead.id,
        {
          expectedVersion: lead.version,
          type,
          note: type === 'CALL_LOGGED'
            ? `Call: ${callOutcome}${note.trim() ? `. ${note.trim()}` : ''}`
            : note.trim(),
          followUpAt: null,
          followUpZoneId: null
        },
        { session }
      );
      if (!isOperationsSessionActive(session)) return;
      setNote('');
      setCallOutcome('');
      onSaved();
    } catch (cause) {
      if (isOperationsSessionChangedError(cause) || !isOperationsSessionActive(session)) return;
      if (failureStatus(cause) === 409) {
        await onConflict();
      } else {
        setMessage(failureMessage(cause, 'The call or note could not be saved.'));
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  };

  if (disabled) {
    return (
      <section className="ld-card">
        <h2>Calls and notes</h2>
        <p className="oe-locked"><Lock size={14} aria-hidden="true" />Calls and notes are available only to the current editable Lead owner.</p>
      </section>
    );
  }

  return (
    <section className="ld-card" aria-labelledby="ld-calls">
      <h2 id="ld-calls">Calls and notes</h2>
      {message ? <Notice tone="warn" live title={message} /> : null}
      <div className="oe-seg-radios" role="radiogroup" aria-label="Activity type">
        <label className="oe-seg-radio">
          <input type="radio" checked={type === 'CALL_LOGGED'} onChange={() => setType('CALL_LOGGED')} />
          <Phone size={14} aria-hidden="true" />Log a call
        </label>
        <label className="oe-seg-radio">
          <input type="radio" checked={type === 'NOTE_ADDED'} onChange={() => setType('NOTE_ADDED')} />
          <StickyNote size={14} aria-hidden="true" />Add a note
        </label>
      </div>
      <form
        className="ld-form"
        onSubmit={(event) => {
          event.preventDefault();
          void save();
        }}
      >
        {type === 'CALL_LOGGED' ? (
          <label className="oe-field-stack">
            <span className="oe-field-label">Call outcome</span>
            <select className="oe-input ld-input" value={callOutcome} onChange={(event) => setCallOutcome(event.target.value)}>
              <option value="">Choose an outcome</option>
              {['Connected', 'No answer', 'Busy', 'Wrong number'].map((outcome) => (
                <option key={outcome} value={outcome}>{outcome}</option>
              ))}
            </select>
          </label>
        ) : null}
        <label className="oe-field-stack">
          <span className="oe-field-label">{type === 'CALL_LOGGED' ? 'Discussion summary (optional)' : 'Note'}</span>
          <textarea className="oe-input ld-input" value={note} onChange={(event) => setNote(event.target.value)} />
        </label>
        <div className="ld-actions">
          <button type="submit" className="oe-btn oe-btn--primary oe-btn--md" disabled={busy}>
            {busy ? 'Saving…' : type === 'CALL_LOGGED' ? 'Save call' : 'Save note'}
          </button>
        </div>
      </form>
    </section>
  );
};

const RevealContactDialog: React.FC<{
  leadId: number;
  customerName: string;
  onClose: () => void;
}> = ({ leadId, customerName, onClose }) => {
  const [contact, setContact] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const reveal = async () => {
    if (busy) return;
    const session = captureOperationsSession();
    if (!session) {
      setMessage('Your session has ended.');
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const result = await operationsLeadService.revealContact(leadId, { session });
      if (!isOperationsSessionActive(session)) return;
      setContact(result.contact);
    } catch (cause) {
      if (isOperationsSessionChangedError(cause) || !isOperationsSessionActive(session)) return;
      setMessage(failureMessage(cause, 'The customer contact could not be revealed.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title="Reveal number to call"
      onClose={() => {
        setContact(null);
        onClose();
      }}
      footer={contact ? (
        <button type="button" className="oe-btn oe-btn--primary oe-btn--md" onClick={onClose}>
          Hide and close
        </button>
      ) : (
        <>
          <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" onClick={onClose}>Cancel</button>
          <button type="button" className="oe-btn oe-btn--primary oe-btn--md" disabled={busy} onClick={() => void reveal()}>
            {busy ? 'Revealing…' : 'Reveal number'}
          </button>
        </>
      )}
    >
      {message ? <Notice tone="warn" live title={message} /> : null}
      {contact ? (
        <div className="ld-reveal">
          <p className="oe-field-label">{customerName}</p>
          <p className="ld-reveal-number oe-mono">{contact}</p>
          <p className="oe-footnote">
            This owner-only disclosure is audited. It disappears when this dialog closes.
          </p>
        </div>
      ) : (
        <>
          <p className="oe-lede">
            Current ownership and Team access are checked before this disclosure is recorded.
          </p>
          <Notice title="Customer privacy">
            Do not copy the full number into notes, URLs, or share messages. Use it only for this customer contact.
          </Notice>
        </>
      )}
    </Modal>
  );
};

const LeadShareScreen: React.FC<{
  leadId: number;
  initialTab: 'share' | 'history';
  actorId: number;
  capabilities: StaffCapabilities | null;
  returnTo: string | null;
  onBack: () => void;
  onQueue: () => void;
}> = ({ leadId, initialTab, actorId, capabilities, returnTo, onBack, onQueue }) => {
  const location = useLocation();
  const navigate = useNavigate();
  const shortlistIdFromRoute = (() => {
    const value = Number(new URLSearchParams(location.search).get('shortlist'));
    return Number.isSafeInteger(value) && value > 0 ? value : null;
  })();
  const [state, setState] = useState<'loading' | 'ready' | 'missing' | 'error' | 'signed-out'>('loading');
  const [lead, setLead] = useState<LeadDetail | null>(null);
  const [preferences, setPreferences] = useState<LeadContactPreference[]>([]);
  const [shortlist, setShortlist] = useState<LeadShortlist | null>(null);
  const [history, setHistory] = useState<Awaited<ReturnType<typeof operationsLeadService.shareHistory>> | null>(null);
  const [historyPage, setHistoryPage] = useState(0);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [tab, setTab] = useState<'share' | 'history'>(initialTab);
  const [step, setStep] = useState<'properties' | 'recipient' | 'prepare' | 'handoff'>('properties');
  const [preparedShare, setPreparedShare] = useState<LeadShare | null>(null);
  const [shareEpoch, setShareEpoch] = useState(0);
  const [message, setMessage] = useState('');
  const mounted = useRef(true);
  const requestNumber = useRef(0);
  const controller = useRef<AbortController | null>(null);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      controller.current?.abort();
    };
  }, []);

  const writeShortlistRoute = useCallback((nextShortlistId: number | null) => {
    const params = new URLSearchParams();
    if (tab === 'history') params.set('tab', 'history');
    if (nextShortlistId !== null) params.set('shortlist', String(nextShortlistId));
    if (returnTo) params.set('returnTo', returnTo);
    const query = params.toString();
    navigate(`/operations/leads/${leadId}/share${query ? `?${query}` : ''}`, { replace: true });
  }, [leadId, navigate, returnTo, tab]);

  const load = useCallback(async (quiet = false): Promise<LeadDetail | null> => {
    const session = captureOperationsSession();
    const sequence = requestNumber.current + 1;
    requestNumber.current = sequence;
    controller.current?.abort();
    const next = new AbortController();
    controller.current = next;
    if (!session) {
      setState('signed-out');
      return null;
    }
    if (!quiet) setState('loading');
    setMessage('');
    try {
      let pendingPreparation: PendingSharePrepareOperation | null = null;
      let pendingReadMessage = '';
      try {
        pendingPreparation = readPendingSharePreparation(session, leadId);
      } catch (cause) {
        pendingReadMessage = failureMessage(
          cause,
          'The earlier property message could not be checked in this browser.'
        );
      }
      const shortlistIdToLoad = shortlistIdFromRoute ?? pendingPreparation?.shortlistId ?? null;
      const [detail, preferenceHistory, page] = await Promise.all([
        operationsLeadService.detail(leadId, { session, signal: next.signal }),
        operationsLeadService.contactPreferences(leadId, { session, signal: next.signal }),
        operationsLeadService.shareHistory(leadId, 0, SHARE_HISTORY_PAGE_SIZE, {
          session,
          signal: next.signal
        })
      ]);
      let currentShortlist: LeadShortlist | null = null;
      if (shortlistIdToLoad !== null) {
        try {
          currentShortlist = await operationsLeadService.shortlist(
            leadId,
            shortlistIdToLoad,
            { session, signal: next.signal }
          );
        } catch (cause) {
          if (!isConcealed(cause)) throw cause;
        }
      }
      if (!mounted.current || next.signal.aborted || sequence !== requestNumber.current
        || !isOperationsSessionActive(session)) return null;
      setLead(detail);
      setPreferences(preferenceHistory);
      setHistory(page);
      setHistoryPage(0);
      setShortlist(currentShortlist);
      setState('ready');
      if (pendingPreparation
        && currentShortlist?.id === pendingPreparation.shortlistId) {
        setStep('prepare');
      }
      if (pendingReadMessage) setMessage(pendingReadMessage);
      return detail;
    } catch (cause) {
      if (!mounted.current || next.signal.aborted || sequence !== requestNumber.current
        || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)) return null;
      if (failureStatus(cause) === 401) {
        setState('signed-out');
      } else if (isConcealed(cause)) {
        setState('missing');
        setLead(null);
        setPreferences([]);
        setHistory(null);
      } else {
        setMessage(failureMessage(cause, 'This Lead could not be prepared for sharing.'));
        setState('error');
      }
      return null;
    }
  }, [leadId, shortlistIdFromRoute]);

  useEffect(() => {
    void load();
  }, [load]);

  const refreshHistory = useCallback(async (page = 0) => {
    const session = captureOperationsSession();
    if (!session) return;
    setHistoryLoading(true);
    try {
      const next = await operationsLeadService.shareHistory(
        leadId,
        page,
        SHARE_HISTORY_PAGE_SIZE,
        { session }
      );
      if (!mounted.current || !isOperationsSessionActive(session)) return;
      setHistory(next);
      setHistoryPage(page);
    } catch (cause) {
      if (mounted.current && isOperationsSessionActive(session)) {
        setMessage(failureMessage(cause, 'Share history could not be refreshed.'));
      }
    } finally {
      if (mounted.current) setHistoryLoading(false);
    }
  }, [leadId]);

  const selectTab = (nextTab: 'share' | 'history') => {
    setTab(nextTab);
    const params = new URLSearchParams();
    if (nextTab === 'history') params.set('tab', 'history');
    if (shortlist?.id) params.set('shortlist', String(shortlist.id));
    if (returnTo) params.set('returnTo', returnTo);
    const query = params.toString();
    navigate(`/operations/leads/${leadId}/share${query ? `?${query}` : ''}`, { replace: true });
  };

  if (state === 'loading') {
    return (
      <div className="oe-stack" aria-busy="true">
        <button type="button" className="oe-back" onClick={onBack}><ArrowLeft size={14} aria-hidden="true" />Back to Lead</button>
        <SkeletonRows rows={4} />
      </div>
    );
  }
  if (state === 'signed-out') {
    return <StateMessage icon={<Lock size={20} />} title="Your session has ended" body="Sign in again to continue. No share was sent or copied." />;
  }
  if (state === 'missing') {
    return (
      <div className="oe-stack">
        <button type="button" className="oe-back" onClick={onQueue}><ArrowLeft size={14} aria-hidden="true" />Back to Leads</button>
        <StateMessage icon={<Lock size={20} />} title="This Lead isn’t available" body="Current ownership or Team access does not admit its sharing history." />
      </div>
    );
  }
  if (state === 'error' || !lead) {
    return (
      <StateMessage tone="warn" icon={<CircleAlert size={20} />} title="Sharing could not be loaded" body={message || 'Check your connection and try again.'}>
        <button type="button" className="oe-btn oe-btn--primary oe-btn--md" onClick={() => void load()}>
          <RefreshCw size={14} aria-hidden="true" />Try again
        </button>
      </StateMessage>
    );
  }

  const canCreateShare = leadShareEligible(lead, actorId, capabilities);
  const latestPreference = preferences[0] ?? null;
  const activeTab = canCreateShare ? tab : 'history';

  return (
    <div className="oe-stack">
      <button type="button" className="oe-back" onClick={onBack}>
        <ArrowLeft size={14} aria-hidden="true" />Back to {displayName(lead)}
      </button>
      <PageHeader
        eyebrow={`Lead #${lead.id} · ${lead.cityName || 'City not recorded'}`}
        title={`Share property details with ${lead.customerName || 'this customer'}`}
        description="Choose the properties, confirm the customer’s WhatsApp number and permission, then review and send the message yourself."
      />

      <div className="ld-tabs" role="tablist" aria-label="Property sharing">
        <button
          type="button"
          role="tab"
          id="ld-tab-share"
          aria-selected={activeTab === 'share'}
          aria-controls="ld-new-share"
          className="ld-tab"
          disabled={!canCreateShare}
          onClick={() => selectTab('share')}
        >
          New message
        </button>
        <button
          type="button"
          role="tab"
          id="ld-tab-history"
          aria-selected={activeTab === 'history'}
          aria-controls="ld-share-history"
          className="ld-tab"
          onClick={() => selectTab('history')}
        >
          History {history ? `(${history.totalElements})` : ''}
        </button>
      </div>

      {activeTab === 'history' ? (
        <div id="ld-share-history" role="tabpanel" aria-labelledby="ld-tab-history">
          <ShareHistoryView
            history={history}
            loading={historyLoading}
            page={historyPage}
            onPage={(page) => void refreshHistory(page)}
          />
        </div>
      ) : !canCreateShare ? (
        <StateMessage
          icon={<Lock size={20} />}
          title="New property messages aren’t available for this Lead"
          body="You need current ownership of an active rental enquiry with a City before you can send property details."
        />
      ) : (
        <div id="ld-new-share" role="tabpanel" aria-labelledby="ld-tab-share" className="oe-stack">
          <ShareStepper step={step} />
          {message ? <Notice tone="warn" live title={message} /> : null}
          {step === 'properties' ? (
            <SharePropertiesStep
              lead={lead}
              shortlist={shortlist}
              onShortlist={(next) => {
                setShortlist(next);
                setLead((current) => current ? { ...current, version: next.leadVersion } : current);
                writeShortlistRoute(next.id);
              }}
              onContinue={() => setStep('recipient')}
              onLeadChanged={async () => {
                const refreshed = await load(true);
                if (refreshed) setLead(refreshed);
              }}
            />
          ) : step === 'recipient' ? (
            <ShareRecipientStep
              lead={lead}
              existingPreference={latestPreference}
              onBack={() => setStep('properties')}
              onConsent={(preference, refreshedLead) => {
                setPreferences((current) => [preference, ...current.filter((item) => item.revision !== preference.revision)]);
                setLead(refreshedLead);
                setStep('prepare');
              }}
              onConflict={async () => {
                const refreshed = await load(true);
                if (refreshed) setLead(refreshed);
              }}
            />
          ) : step === 'prepare' && shortlist && latestPreference ? (
            <SharePrepareStep
              lead={lead}
              shortlist={shortlist}
              preference={latestPreference}
              onBack={() => setStep('recipient')}
              onPrepared={(share) => {
                setPreparedShare(share);
                setShareEpoch((value) => value + 1);
                setStep('handoff');
                void refreshHistory(0);
              }}
              onConflict={async () => {
                setMessage('The Lead, selected properties or customer permission changed. Confirm the recipient and permission again.');
                setStep('recipient');
                const refreshed = await load(true);
                if (refreshed) setLead(refreshed);
              }}
            />
          ) : step === 'handoff' && preparedShare ? (
            <ShareHandoffStep
              key={`${preparedShare.id}-${shareEpoch}`}
              lead={lead}
              share={preparedShare}
              history={history}
              onHistoryRefresh={() => refreshHistory(0)}
              onOpenHistory={() => selectTab('history')}
              onConflict={() => {
                setPreparedShare(null);
                setStep('recipient');
                setMessage('The customer’s permission or the public property details changed. Review them before preparing a new message.');
              }}
              onWrongRecipient={() => {
                setPreparedShare(null);
                setStep('recipient');
              }}
              onDone={onBack}
            />
          ) : (
            <Notice
              tone="warn"
              title="An earlier sharing step is required"
              actions={(
                <button type="button" className="oe-btn oe-btn--secondary oe-btn--sm" onClick={() => setStep(shortlist ? 'recipient' : 'properties')}>
                  Return to the required step
                </button>
              )}
            />
          )}
        </div>
      )}
    </div>
  );
};

const ShareHandoffStep: React.FC<{
  lead: LeadDetail;
  share: LeadShare;
  history: Awaited<ReturnType<typeof operationsLeadService.shareHistory>> | null;
  onHistoryRefresh: () => Promise<void>;
  onOpenHistory: () => void;
  onConflict: () => void;
  onWrongRecipient: () => void;
  onDone: () => void;
}> = ({
  lead,
  share,
  history,
  onHistoryRefresh,
  onOpenHistory,
  onConflict,
  onWrongRecipient,
  onDone
}) => {
  const initialHistoryItem = history?.items.find((item) => item.id === share.id) ?? null;
  const [current, setCurrent] = useState<LeadShareHistoryItem | null>(initialHistoryItem);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [handoffNotice, setHandoffNotice] = useState<string | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [fullDestination, setFullDestination] = useState<string | null>(null);
  const [waUrl, setWaUrl] = useState<string | null>(null);
  const [copyFallback, setCopyFallback] = useState(false);
  const [pendingEvent, setPendingEvent] = useState<string | null>(null);
  const [launchVerifying, setLaunchVerifying] = useState(false);
  const eventId = useRef<string | null>(null);
  const eventKind = useRef<LeadShareEventType | null>(null);
  const dispatched = useRef(false);
  const mounted = useRef(true);
  const activationGeneration = useRef(0);
  const activationInFlight = useRef(false);
  const reservedTarget = useRef<Window | null>(null);

  const closeReservedTarget = useCallback(() => {
    const target = reservedTarget.current;
    reservedTarget.current = null;
    if (!target) return;
    try {
      target.close();
    } catch {
      // Browser policy may already have discarded the reserved blank context.
    }
  }, []);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      activationGeneration.current += 1;
      activationInFlight.current = false;
      closeReservedTarget();
    };
  }, [closeReservedTarget]);
  useEffect(() => subscribeOperationsSession(() => {
    activationGeneration.current += 1;
    activationInFlight.current = false;
    closeReservedTarget();
    setConfirmOpen(false);
    setFullDestination(null);
    setWaUrl(null);
    setLaunchVerifying(false);
  }), [closeReservedTarget]);
  useEffect(() => {
    activationGeneration.current += 1;
    activationInFlight.current = false;
    closeReservedTarget();
    setConfirmOpen(false);
    setFullDestination(null);
    setWaUrl(null);
  }, [
    closeReservedTarget,
    lead.id,
    share.id,
    share.shortlistVersion,
    share.contactPreferenceRevision
  ]);
  useEffect(() => {
    const found = history?.items.find((item) => item.id === share.id);
    if (found) setCurrent(found);
  }, [history, share.id]);

  const refreshCurrent = async (session: OperationsSession): Promise<LeadShareHistoryItem | null> => {
    const item = await operationsLeadService.preparedShareHistoryFromHistory(
      lead.id,
      share.id,
      { session }
    );
    if (!mounted.current || !isOperationsSessionActive(session)) return null;
    setCurrent(item);
    return item;
  };

  const preflight = async (
    expectedSession?: OperationsSession
  ): Promise<LeadShareHistoryItem | null> => {
    if (pendingEvent) {
      setMessage('An earlier share event is unconfirmed. Check history before any new copy or handoff.');
      return null;
    }
    const session = expectedSession ?? captureOperationsSession();
    if (!session || !isOperationsSessionActive(session)) {
      setMessage('Your session has ended.');
      return null;
    }
    setBusy(true);
    setMessage(null);
    try {
      const item = await refreshCurrent(session);
      if (!item) {
        setMessage('This saved message is no longer available with your current access.');
        return null;
      }
      if (!item.currentAuthorityMatches) {
        setMessage('Current Lead ownership or Team authority no longer permits this share action. Nothing was opened or copied.');
        onConflict();
        return null;
      }
      if (!item.currentConsentMatches) {
        setMessage('The customer’s current permission no longer matches this saved message. Nothing will be opened or copied.');
        onConflict();
        return null;
      }
      if (!item.currentDestinationMatches) {
        setMessage('The confirmed WhatsApp number no longer matches this saved message. Confirm the number again.');
        onConflict();
        return null;
      }
      if (!item.currentFactsMatch) {
        setMessage('The selected properties or their public details changed. Prepare a new message before continuing.');
        onConflict();
        return null;
      }
      if (!item.actionReady) {
        setMessage('Current sharing conditions no longer permit a new action. Nothing was opened or copied.');
        onConflict();
        return null;
      }
      return item;
    } catch (cause) {
      if (isOperationsSessionChangedError(cause) || !isOperationsSessionActive(session)) return null;
      if (cause instanceof ShareHistoryBoundReachedError) {
        setCurrent(null);
        setMessage('This saved message is older than the history this screen can safely check. Nothing was opened or copied. Open Message history and review the current details before trying again.');
        return null;
      }
      if (isConcealed(cause)) {
        setCurrent(null);
        setMessage('This share is no longer visible through your current Lead access.');
      } else {
        setMessage(failureMessage(cause, 'Current sharing conditions could not be verified. Nothing was opened or copied.'));
      }
      return null;
    } finally {
      if (mounted.current) setBusy(false);
    }
  };

  type EventWriteOutcome = 'recorded' | 'recovered' | null;

  const record = async (
    type: LeadShareEventType,
    expectedSession?: OperationsSession
  ): Promise<EventWriteOutcome> => {
    if (pendingEvent) {
      setMessage('An earlier action is still unconfirmed. Check the saved history before copying or opening WhatsApp again.');
      return null;
    }
    const session = expectedSession ?? captureOperationsSession();
    if (!session || !isOperationsSessionActive(session)) {
      setMessage('Your session has ended.');
      return null;
    }
    if (!eventId.current) {
      eventId.current = requestId();
      eventKind.current = type;
    } else if (eventKind.current !== type) {
      setMessage('An earlier share action is still unconfirmed. Check history before recording a different outcome.');
      return null;
    }
    dispatched.current = false;
    try {
      const recorded = await operationsLeadService.recordShareEvent(
        lead.id,
        share.id,
        eventId.current,
        type,
        {
          session,
          onDispatch: () => { dispatched.current = true; }
        }
      );
      if (!mounted.current || !isOperationsSessionActive(session)) return null;
      eventId.current = null;
      eventKind.current = null;
      setPendingEvent(null);
      setCurrent((existing) => existing ? {
        ...existing,
        events: existing.events.some((event) => event.eventId === recorded.id)
          ? existing.events
          : [...existing.events, {
              eventId: recorded.id,
              type: recorded.type,
              occurredAt: recorded.occurredAt
            }]
      } : existing);
      void onHistoryRefresh();
      return 'recorded';
    } catch (cause) {
      if (!mounted.current || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)) return null;
      const status = failureStatus(cause);
      if (dispatched.current && (status === undefined || status >= 500)) {
        const pendingId = eventId.current;
        setPendingEvent(pendingId);
        if (await reconcilePendingEvent(session)) return 'recovered';
      } else {
        eventId.current = null;
        eventKind.current = null;
        setPendingEvent(null);
        setMessage(failureMessage(cause, 'The share event could not be recorded.'));
        if (status === 409
            && (type === 'HANDOFF_ATTEMPTED' || type === 'CLIPBOARD_COPIED')) {
          onConflict();
        }
      }
      return null;
    }
  };

  const reconcilePendingEvent = async (session: OperationsSession): Promise<boolean> => {
    const pendingId = eventId.current;
    if (!pendingId) return true;
    setBusy(true);
    try {
      const item = await refreshCurrent(session);
      if (!mounted.current || !isOperationsSessionActive(session)) return false;
      const recovered = item?.events.find((event) => event.eventId === pendingId) ?? null;
      if (recovered) {
        eventId.current = null;
        eventKind.current = null;
        setPendingEvent(null);
        setMessage(`Share history confirmed ${eventMeta(recovered.type).label.toLowerCase()}. No action was repeated.`);
        void onHistoryRefresh();
        return true;
      }
      setPendingEvent(pendingId);
      setMessage('Read-only history does not show the earlier event yet. No new copy or handoff will be allowed until it is confirmed.');
      return false;
    } catch (cause) {
      if (mounted.current && !isOperationsSessionChangedError(cause)
        && isOperationsSessionActive(session)) {
        setPendingEvent(pendingId);
        setMessage(failureMessage(cause,
          'Share history could not confirm the earlier event. No new copy or handoff will be allowed.'));
      }
      return false;
    } finally {
      if (mounted.current) setBusy(false);
    }
  };

  const openWhatsAppConfirmation = async () => {
    if (busy || pendingEvent) return;
    activationGeneration.current += 1;
    setMessage(null);
    setFullDestination(null);
    setWaUrl(null);
    setConfirmOpen(true);
  };

  const copyPreparedText = async (kind: 'message' | 'links') => {
    if (busy || pendingEvent) return;
    const session = captureOperationsSession();
    if (!session || !isOperationsSessionActive(session)) return;
    const item = await preflight(session);
    if (!item) return;
    if (!isOperationsSessionActive(session)) return;
    const currentShare = leadShareFromHistoryItem(item, lead.id);
    const text = kind === 'message'
      ? currentShare.previewText
      : shareLinksText(currentShare.publicLinks);
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      setCopyFallback(true);
      setMessage('Clipboard access failed. The prepared text is shown below so you can select and copy it manually.');
      return;
    }
    if (!mounted.current) return;
    setCopyFallback(false);
    const recorded = await record('CLIPBOARD_COPIED', session);
    setMessage(recorded === 'recorded'
      ? `${kind === 'message' ? 'Prepared message' : 'Public links'} copied and the successful copy was recorded.`
      : recorded === 'recovered'
        ? `${kind === 'message' ? 'Prepared message' : 'Public links'} copied once; saved history confirmed the earlier event without another copy.`
        : 'The clipboard operation succeeded, but its history event is not yet confirmed.');
  };

  const closeConfirmation = () => {
    activationGeneration.current += 1;
    closeReservedTarget();
    setConfirmOpen(false);
    setFullDestination(null);
    setWaUrl(null);
  };

  const verifyLaunch = async () => {
    if (busy || pendingEvent) return;
    const generation = activationGeneration.current;
    const session = captureOperationsSession();
    if (!session) {
      setMessage('Your session has ended.');
      return;
    }
    setLaunchVerifying(true);
    setMessage(null);
    try {
      const item = await preflight(session);
      if (!item) return;
      const reveal = await operationsLeadService.revealDestination(
        lead.id,
        share.contactPreferenceRevision,
        { session }
      );
      if (!mounted.current || !isOperationsSessionActive(session)
          || generation !== activationGeneration.current) return;
      const url = whatsappUrl(reveal.fullDestination, share.previewText);
      if (!url) {
        setFullDestination(null);
        setWaUrl(null);
        setMessage('The confirmed destination is not a usable international WhatsApp number. Nothing was opened.');
        return;
      }
      setFullDestination(reveal.fullDestination);
      setWaUrl(url);
    } catch (cause) {
      if (!mounted.current || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)) return;
      setWaUrl(null);
      setFullDestination(null);
      setMessage(failureMessage(cause, 'The confirmed destination could not be verified. Nothing was opened.'));
    } finally {
      if (mounted.current) setLaunchVerifying(false);
    }
  };

  const launch = async () => {
    if (activationInFlight.current || busy || pendingEvent) return;
    activationInFlight.current = true;
    const generation = activationGeneration.current + 1;
    activationGeneration.current = generation;
    const session = captureOperationsSession();
    if (!session) {
      activationInFlight.current = false;
      setMessage('Your session has ended.');
      return;
    }
    const target = window.open('', '_blank');
    if (!target) {
      activationInFlight.current = false;
      setMessage('The browser blocked the empty WhatsApp tab. Allow pop-ups for Pathome and try again.');
      return;
    }
    reservedTarget.current = target;
    try {
      target.opener = null;
    } catch {
      closeReservedTarget();
      activationInFlight.current = false;
      setMessage('The browser could not isolate the reserved WhatsApp tab. Nothing was opened.');
      return;
    }
    setLaunchVerifying(true);
    setMessage(null);
    setHandoffNotice(null);
    try {
      const outcome = await record('HANDOFF_ATTEMPTED', session);
      const stillCurrent = mounted.current
        && isOperationsSessionActive(session)
        && generation === activationGeneration.current
        && reservedTarget.current === target
        && !target.closed;
      if (!stillCurrent) {
        closeReservedTarget();
        return;
      }
      if (outcome !== 'recorded') {
        closeReservedTarget();
        setConfirmOpen(false);
        setFullDestination(null);
        setWaUrl(null);
        if (outcome === 'recovered') {
          setHandoffNotice('We recorded your attempt to open WhatsApp, but couldn’t confirm that it opened. Check WhatsApp before trying again.');
        }
        return;
      }
      if (!waUrl) {
        closeReservedTarget();
        setMessage('The WhatsApp number is no longer available. Check the current details and try again.');
        return;
      }
      try {
        target.location.replace(waUrl);
      } catch {
        closeReservedTarget();
        setHandoffNotice('Pathome recorded the attempt, but the browser could not open the reserved tab. Check the saved history before trying again.');
        return;
      }
      reservedTarget.current = null;
      setConfirmOpen(false);
      setFullDestination(null);
      setWaUrl(null);
      setHandoffNotice(
        'Pathome asked the browser to open WhatsApp in a new tab and recorded the attempt. Pathome cannot confirm whether WhatsApp opened, whether Send was pressed, or whether the message was delivered.'
      );
    } finally {
      activationInFlight.current = false;
      if (mounted.current) setLaunchVerifying(false);
    }
  };

  const events = current?.events ?? [];
  const hasAttempt = events.some((event) => event.type === 'HANDOFF_ATTEMPTED');
  const hasCopy = events.some((event) => event.type === 'CLIPBOARD_COPIED');
  const latest = events[events.length - 1];
  const latestMeta = latest ? eventMeta(latest.type) : { label: 'Message prepared', tone: 'open' as const };
  const isWhatsApp = share.mode === 'MANUAL_BUSINESS_HANDOFF';

  return (
    <section className="ld-card" aria-labelledby="ld-handoff-title">
      <header className="ld-card-head">
        <div>
          <h2 id="ld-handoff-title" tabIndex={-1}>
            {isWhatsApp ? 'Open WhatsApp and send it yourself' : 'Copy the message through the agreed alternative'}
          </h2>
          <p className="oe-footnote">
            {share.maskedDestination || 'No WhatsApp number recorded'}
          </p>
        </div>
        <Tone tone={latestMeta.tone}>{latestMeta.label}</Tone>
      </header>

      {message ? <Notice tone="warn" live title={message} /> : null}
      {handoffNotice ? <Notice tone="warn" live title={handoffNotice} /> : null}
      {pendingEvent ? (
        <Notice
          tone="warn"
          live
          title="An earlier action is waiting for confirmation"
          actions={(
            <button
              type="button"
              className="oe-btn oe-btn--secondary oe-btn--md"
              disabled={busy}
              onClick={() => {
                const session = captureOperationsSession();
                if (session) void reconcilePendingEvent(session);
              }}
            >
              {busy ? 'Checking…' : 'Check if it was recorded'}
            </button>
          )}
        >
          Copy and WhatsApp stay disabled until the saved history shows that earlier action.
        </Notice>
      ) : null}
      {current && (!current.currentFactsMatch || !current.currentConsentMatches
        || !current.currentDestinationMatches || !current.currentAuthorityMatches) ? (
        <Notice tone="warn" title="Current details no longer match this saved message">
          Actions are blocked until you prepare a new message with the customer’s current permission and current property details.
        </Notice>
      ) : null}

      {isWhatsApp ? (
        <ol className="ld-steps">
          <li>Confirm WhatsApp on this device is signed in to the Pathome business account, not a personal account.</li>
          <li>Review the WhatsApp number and prepared message.</li>
          <li>Open WhatsApp, read the filled message, and press Send yourself.</li>
          <li>Return here and tell Pathome whether you sent it. Pathome does not claim WhatsApp delivered it.</li>
        </ol>
      ) : (
        <p className="oe-lede">
          No WhatsApp window will be opened. Copy the exact prepared message or public links and send them
          through the alternative agreed with the customer.
        </p>
      )}

      <div className="ld-snapshot">
        <p className="oe-field-label">Saved message and links</p>
        <pre>{share.previewText}</pre>
        <ul>
          {share.publicLinks.map((link) => (
            <li key={link}><a href={link} target="_blank" rel="noopener noreferrer">{link}</a></li>
          ))}
        </ul>
      </div>

      {events.length ? (
        <ol className="ld-history-events" aria-label="Saved property message actions">
          {events.map((event) => (
            <li key={event.eventId}>
              <strong>{eventMeta(event.type).label}</strong>
              <span>{formatDateTime(event.occurredAt) || 'Time not recorded'}</span>
            </li>
          ))}
        </ol>
      ) : <p className="oe-footnote">No action has been recorded after the message was prepared.</p>}

      {hasAttempt || hasCopy ? (
        <div className="ld-report-send">
          <h3>Did you actually send this prepared message?</h3>
          <div className="ld-actions">
            <button
              type="button"
              className="oe-btn oe-btn--primary oe-btn--md"
              disabled={busy || pendingEvent !== null}
              onClick={() => void record('OPERATOR_REPORTED_MANUAL_SEND').then((recorded) => {
                if (recorded) setMessage('Saved: you reported sending the message. Pathome has not confirmed that WhatsApp delivered it.');
              })}
            >
              Yes, I sent it
            </button>
            <button
              type="button"
              className="oe-btn oe-btn--secondary oe-btn--md"
              disabled={busy || pendingEvent !== null}
              onClick={() => void record('HANDOFF_CANCELLED').then((recorded) => {
                if (recorded) setMessage('Saved: you did not send this message.');
              })}
            >
              No, I didn’t send it
            </button>
          </div>
          <p className="oe-footnote">
            “I sent it” records your report only. It is not evidence that WhatsApp delivered or read the message.
          </p>
        </div>
      ) : null}

      {copyFallback ? (
        <label className="oe-field-stack">
            <span className="oe-field-label">Message to copy</span>
          <textarea
            className="oe-input ld-input"
            readOnly
            rows={8}
            value={share.previewText}
            onFocus={(event) => event.currentTarget.select()}
          />
        </label>
      ) : null}

      <div className="ld-handoff-actions">
        {isWhatsApp ? (
          <button
            type="button"
            className="oe-btn oe-btn--primary oe-btn--md ld-wa"
            disabled={busy || pendingEvent !== null}
            onClick={() => void openWhatsAppConfirmation()}
          >
            <MessageCircle size={18} aria-hidden="true" />{busy ? 'Checking…' : 'Open WhatsApp'}
          </button>
        ) : null}
        <button
          type="button"
          className={`oe-btn oe-btn--md ${isWhatsApp ? 'oe-btn--secondary' : 'oe-btn--primary'}`}
          disabled={busy || pendingEvent !== null}
          onClick={() => void copyPreparedText('message')}
        >
          <Copy size={16} aria-hidden="true" />Copy message
        </button>
        <button
          type="button"
          className="oe-btn oe-btn--secondary oe-btn--md"
          disabled={busy || pendingEvent !== null}
          onClick={() => void copyPreparedText('links')}
        >
          <Copy size={16} aria-hidden="true" />Copy property links
        </button>
        <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" onClick={onOpenHistory}>
          <History size={16} aria-hidden="true" />Message history
        </button>
      </div>

      <div className="ld-actions ld-actions--split">
        <button type="button" className="oe-btn oe-btn--ghost oe-btn--md" onClick={onDone}>Done</button>
        <button
          type="button"
          className="oe-btn oe-btn--danger-outline oe-btn--md"
          onClick={() => {
            void record('HANDOFF_CANCELLED').then((recorded) => {
              if (recorded) onWrongRecipient();
            });
          }}
        >
          Wrong number or permission — change it
        </button>
      </div>

      {confirmOpen ? (
        <Modal
          title="Confirm the exact WhatsApp recipient"
          onClose={closeConfirmation}
          footer={(
            <>
              <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" onClick={closeConfirmation}>
                Cancel
              </button>
              {waUrl ? (
                <button
                  type="button"
                  className="oe-btn oe-btn--primary oe-btn--md"
                  disabled={launchVerifying || pendingEvent !== null}
                  onClick={() => void launch()}
                >
                  <MessageCircle size={16} aria-hidden="true" />
                  {launchVerifying ? 'Verifying final access…' : 'Open WhatsApp'}
                </button>
              ) : (
                <button
                  type="button"
                  className="oe-btn oe-btn--primary oe-btn--md"
                  disabled={busy || launchVerifying || pendingEvent !== null}
                  onClick={() => void verifyLaunch()}
                >
                  <ShieldAlert size={16} aria-hidden="true" />
                  {launchVerifying || busy ? 'Verifying…' : 'Verify current conditions'}
                </button>
              )}
            </>
          )}
        >
          <div className="ld-reveal">
            <p className="oe-field-label">WhatsApp for {displayName(lead)}</p>
            <p className="ld-reveal-number oe-mono">{fullDestination || share.maskedDestination}</p>
            <p>
              {fullDestination
                ? 'The current saved WhatsApp number is shown. Read it back to the customer before opening WhatsApp.'
                : 'Pathome checks your current ownership, the customer’s permission and current property details before showing the full number.'}
              {' '}This destination is cleared when the dialog closes.
            </p>
            <p className="oe-footnote">
              The new tab receives no usable <code>window.opener</code>. Pathome cannot confirm opening, sending, or delivery.
            </p>
          </div>
        </Modal>
      ) : null}
    </section>
  );
};

const LeadSourceExceptionsScreen: React.FC<{ onBack: () => void }> = ({ onBack }) => {
  const [state, setState] = useState<'loading' | 'ready' | 'error' | 'denied' | 'signed-out'>('loading');
  const [result, setResult] = useState<Awaited<ReturnType<typeof operationsLeadService.sourceExceptions>> | null>(null);
  const [page, setPage] = useState(0);
  const [message, setMessage] = useState('');
  const [busyId, setBusyId] = useState<string | null>(null);
  const [refreshRequired, setRefreshRequired] = useState<Set<string>>(new Set());
  const mounted = useRef(true);
  const requestNumber = useRef(0);
  const controller = useRef<AbortController | null>(null);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      controller.current?.abort();
    };
  }, []);

  const load = useCallback(async (requestedPage = page) => {
    const session = captureOperationsSession();
    const sequence = requestNumber.current + 1;
    requestNumber.current = sequence;
    controller.current?.abort();
    const next = new AbortController();
    controller.current = next;
    if (!session) {
      setState('signed-out');
      setResult(null);
      return;
    }
    setState('loading');
    setMessage('');
    try {
      const response = await operationsLeadService.sourceExceptions(requestedPage, LEAD_PAGE_SIZE, {
        session,
        signal: next.signal
      });
      if (!mounted.current || next.signal.aborted || sequence !== requestNumber.current
        || !isOperationsSessionActive(session)) return;
      setResult(response);
      setPage(response.page);
      setRefreshRequired(new Set());
      setState('ready');
    } catch (cause) {
      if (!mounted.current || next.signal.aborted || sequence !== requestNumber.current
        || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)) return;
      if (failureStatus(cause) === 401) {
        setState('signed-out');
        setResult(null);
      } else if (isConcealed(cause)) {
        setState('denied');
        setResult(null);
      } else {
        setMessage(failureMessage(cause, 'Import issues could not be loaded.'));
        setState('error');
      }
    }
  }, [page]);

  useEffect(() => {
    void load(0);
  }, []);

  const retry = async (event: LeadSourceException) => {
    if (busyId) return;
    const session = captureOperationsSession();
    if (!session) {
      setMessage('Your session has ended.');
      return;
    }
    setBusyId(event.eventId);
    setMessage('');
    try {
      const updated = await operationsLeadService.retrySource(event.eventId, { session });
      if (!mounted.current || !isOperationsSessionActive(session)) return;
      setResult((current) => current ? {
        ...current,
        items: current.items.map((item) => item.eventId === updated.eventId ? updated : item)
      } : current);
      setRefreshRequired((current) => {
        const next = new Set(current);
        next.delete(event.eventId);
        return next;
      });
      setMessage('The enquiry was imported again. The current details have been refreshed.');
    } catch (cause) {
      if (!mounted.current || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)) return;
      const status = failureStatus(cause);
      if (sourceRetryConflictRequiresRefresh(status)) {
        setRefreshRequired((current) => new Set(current).add(event.eventId));
      setMessage('This enquiry changed. Refresh the list before trying again.');
      } else {
      setMessage(failureMessage(cause, 'This enquiry could not be imported again.'));
      }
    } finally {
      if (mounted.current) setBusyId(null);
    }
  };

  return (
    <div className="oe-stack">
      <button type="button" className="oe-back" onClick={onBack}>
        <ArrowLeft size={14} aria-hidden="true" />Back to Leads
      </button>
      <PageHeader
        eyebrow="Staff Admin · across Cities and Teams"
        title="Import issues"
        description="Enquiries that need a Staff Admin’s review. Original enquiry details and sign-in credentials are never shown."
      />
      <Notice title="Some imports can be retried">
        Pathome checks access, retry limits and whether the source is active before an import is tried again.
        This screen cannot discard an enquiry and never shows its original content.
      </Notice>
      {message ? <Notice tone="warn" live title={message} /> : null}

      {state === 'loading' ? <SkeletonRows rows={5} /> : state === 'signed-out' ? (
        <StateMessage icon={<Lock size={20} />} title="Your session has ended" body="Sign in again to reload import issues. Nothing was retried." />
      ) : state === 'denied' ? (
        <StateMessage icon={<Lock size={20} />} title="Staff Admin access is required" body="No enquiry details are kept or shown for this account." />
      ) : state === 'error' ? (
        <StateMessage tone="warn" icon={<CircleAlert size={20} />} title="Import issues could not be loaded" body={message || 'Nothing was retried or changed.'}>
          <button type="button" className="oe-btn oe-btn--primary oe-btn--md" onClick={() => void load(page)}>
            <RefreshCw size={14} aria-hidden="true" />Try again
          </button>
        </StateMessage>
      ) : !result?.items.length ? (
        <StateMessage icon={<Check size={20} />} title="No import issues were returned" body="No enquiries need review on this page." />
      ) : result ? (
        <>
          <ul className="ld-exception-list" aria-label="Import issues">
            {result.items.map((event) => {
              const retryable = sourceRetryAllowed(event, refreshRequired.has(event.eventId));
              return (
                <li key={event.eventId} className="ld-exception-card">
                  <details>
                    <summary>
                      <span className="ld-exception-main">
                        <strong>{sourceExceptionTitle(event.errorCode)}</strong>
                        <span>
                          {event.sourceChannel || event.eventKind}
                          {' · '}
                          {event.receivedAt ? formatDateTime(event.receivedAt) : 'Time not recorded'}
                          {' · '}
                          {event.attempts} {event.attempts === 1 ? 'attempt' : 'attempts'}
                        </span>
                        <span>
                          {event.attachedLeadId
                            ? `Attached to Lead #${event.attachedLeadId}`
                            : event.resolutionLeadId
                              ? `Resolved against Lead #${event.resolutionLeadId}`
                              : 'No Lead attached'}
                        </span>
                      </span>
                      <Tone tone={event.state === 'QUARANTINED' ? 'attention' : 'closed'}>
                        {event.state === 'QUARANTINED' ? 'Needs review' : 'Review in progress'}
                      </Tone>
                      <ChevronRight className="ld-exception-chevron" size={16} aria-hidden="true" />
                    </summary>
                    <div className="ld-exception-detail">
                    <dl className="ld-review-grid">
                    <div><dt>Source</dt><dd>{event.sourceChannel || event.eventKind}</dd></div>
                    <div><dt>Received</dt><dd>{formatDateTime(event.receivedAt) || 'Not recorded'}</dd></div>
                    <div><dt>Attempts</dt><dd>{event.attempts} of 3</dd></div>
                    <div><dt>Issue code</dt><dd>{event.errorCode || 'Not recorded'}</dd></div>
                    <div><dt>Duplicate-check version</dt><dd>{event.fingerprintVersion || 'Not recorded'}</dd></div>
                    <div><dt>Original enquiry kept</dt><dd>{event.extractedPayloadRetained ? 'Yes (protected, not displayed)' : 'No'}</dd></div>
                    <div><dt>Lead</dt><dd>{event.attachedLeadId ? `#${event.attachedLeadId}` : event.resolutionLeadId ? `Resolved to #${event.resolutionLeadId}` : 'Not attached'}</dd></div>
                    </dl>
                    <div className="ld-actions">
                    <button
                      type="button"
                      className="oe-btn oe-btn--primary oe-btn--md"
                      disabled={!retryable || busyId !== null}
                      onClick={() => void retry(event)}
                    >
                      {busyId === event.eventId ? 'Retrying import…' : 'Retry import'}
                    </button>
                    {refreshRequired.has(event.eventId) ? (
                      <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" onClick={() => void load(page)}>
                        <RefreshCw size={14} aria-hidden="true" />Refresh before retrying
                      </button>
                    ) : null}
                    </div>
                    {!retryable && !refreshRequired.has(event.eventId) ? (
                      <p className="oe-footnote">
                        Retry is unavailable for this stored state, attempt count, or resolved Lead attachment.
                      </p>
                    ) : null}
                    </div>
                  </details>
                </li>
              );
            })}
          </ul>
          {result.totalPages > 0 ? (
            <Pagination
              page={result.page}
              totalPages={result.totalPages}
              totalElements={result.totalElements}
              pageSize={LEAD_PAGE_SIZE}
              onPage={(nextPage) => void load(nextPage)}
            />
          ) : null}
        </>
      ) : null}
    </div>
  );
};

const ShareStepper: React.FC<{ step: 'properties' | 'recipient' | 'prepare' | 'handoff' }> = ({ step }) => {
  const steps = [
    ['properties', 'Properties'],
    ['recipient', 'WhatsApp number'],
    ['prepare', 'Review message'],
    ['handoff', 'Send in WhatsApp']
  ] as const;
  const current = steps.findIndex(([key]) => key === step);
  return (
    <ol className="ld-stepper" aria-label="Sharing steps">
      {steps.map(([key, label], index) => (
        <li key={key} aria-current={key === step ? 'step' : undefined} data-done={index < current || undefined}>
          <span className="ld-step-number">{index + 1}</span>
          {label}
        </li>
      ))}
    </ol>
  );
};

const SharePropertiesStep: React.FC<{
  lead: LeadDetail;
  shortlist: LeadShortlist | null;
  onShortlist: (shortlist: LeadShortlist) => void;
  onContinue: () => void;
  onLeadChanged: () => Promise<void>;
}> = ({ lead, shortlist, onShortlist, onContinue, onLeadChanged }) => {
  const [selected, setSelected] = useState<number[]>(shortlist?.listingIds ?? []);
  const [properties, setProperties] = useState<Property[]>([]);
  const [query, setQuery] = useState('');
  const [bhk, setBhk] = useState('');
  const [maxRent, setMaxRent] = useState('');
  const [furnishing, setFurnishing] = useState('');
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [searchAttempt, setSearchAttempt] = useState(0);
  const searchController = useRef<AbortController | null>(null);
  const searchGeneration = useRef(0);
  const activeQueryKey = useRef('');
  const loadMoreController = useRef<AbortController | null>(null);
  const loadMoreInFlight = useRef(false);
  const createOperationId = useRef<string | null>(null);
  const dispatched = useRef(false);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      searchController.current?.abort();
      loadMoreController.current?.abort();
    };
  }, []);

  useEffect(() => {
    const queryKey = JSON.stringify({
      city: lead.cityName ?? null,
      query: query.trim(),
      bhk,
      maxRent,
      furnishing,
      searchAttempt
    });
    const generation = searchGeneration.current + 1;
    searchGeneration.current = generation;
    activeQueryKey.current = queryKey;
    let controller: AbortController | null = null;
    const timer = window.setTimeout(() => {
      searchController.current?.abort();
      loadMoreController.current?.abort();
      loadMoreInFlight.current = false;
      controller = new AbortController();
      searchController.current = controller;
      setProperties([]);
      setPage(0);
      setHasMore(false);
      setLoading(true);
      setMessage(null);
      propertyService.fetchDiscoveryPage(
        0,
        undefined,
        lead.cityName || undefined,
        controller.signal,
        {
          q: query.trim() || undefined,
          bhk: bhk ? `${bhk}BHK` : undefined,
          maxRent: maxRent ? Number(maxRent) : undefined,
          furnishing: parseRentalFurnishing(furnishing),
          rentalOnly: true
        }
      ).then((result) => {
        if (controller?.signal.aborted || !mounted.current
          || generation !== searchGeneration.current
          || queryKey !== activeQueryKey.current) return;
        const ids = new Set<number>();
        setProperties(result.properties.filter((property) => {
          const sameCity = !lead.cityName || !property.city
            || property.city.trim().toLowerCase() === lead.cityName.trim().toLowerCase();
          if (!sameCity || ids.has(property.id)) return false;
          ids.add(property.id);
          return true;
        }));
        setPage(0);
        setHasMore(result.hasMore);
        setLoading(false);
      }).catch((cause) => {
        if (controller?.signal.aborted || !mounted.current
          || generation !== searchGeneration.current
          || queryKey !== activeQueryKey.current) return;
        setProperties([]);
        setHasMore(false);
        setLoading(false);
        setMessage(failureMessage(cause, 'Public property search is unavailable.'));
      });
    }, 350);
    return () => {
      window.clearTimeout(timer);
      controller?.abort();
      if (generation === searchGeneration.current) {
        loadMoreController.current?.abort();
      }
    };
  }, [lead.cityName, query, bhk, maxRent, furnishing, searchAttempt]);

  const loadMore = async () => {
    if (loading || loadMoreInFlight.current || !hasMore) return;
    const generation = searchGeneration.current;
    const queryKey = activeQueryKey.current;
    const requestedPage = page + 1;
    loadMoreInFlight.current = true;
    const controller = new AbortController();
    loadMoreController.current?.abort();
    loadMoreController.current = controller;
    setLoading(true);
    try {
      const result = await propertyService.fetchDiscoveryPage(
        requestedPage,
        undefined,
        lead.cityName || undefined,
        controller.signal,
        {
          q: query.trim() || undefined,
          bhk: bhk ? `${bhk}BHK` : undefined,
          maxRent: maxRent ? Number(maxRent) : undefined,
          furnishing: parseRentalFurnishing(furnishing),
          rentalOnly: true
        }
      );
      if (!mounted.current || controller.signal.aborted
        || generation !== searchGeneration.current
        || queryKey !== activeQueryKey.current) return;
      setProperties((current) => {
        const existing = new Set(current.map((property) => property.id));
        return [
          ...current,
          ...result.properties.filter((property) => {
            const sameCity = !lead.cityName || !property.city
              || property.city.trim().toLowerCase() === lead.cityName.trim().toLowerCase();
            if (!sameCity || existing.has(property.id)) return false;
            existing.add(property.id);
            return true;
          })
        ];
      });
      setPage(requestedPage);
      setHasMore(result.hasMore);
    } catch (cause) {
      if (mounted.current && !controller.signal.aborted
        && generation === searchGeneration.current
        && queryKey === activeQueryKey.current) {
        setMessage(failureMessage(cause, 'More public properties could not be loaded.'));
      }
    } finally {
      if (generation === searchGeneration.current && queryKey === activeQueryKey.current) {
        loadMoreInFlight.current = false;
        if (mounted.current) setLoading(false);
      }
    }
  };

  const add = (listingId: number) => {
    if (selected.includes(listingId)) {
      setSelected((current) => current.filter((id) => id !== listingId));
      return;
    }
    if (selected.length >= SHORTLIST_MAX) {
      setMessage(`The shortlist is full at ${SHORTLIST_MAX} properties.`);
      return;
    }
    setSelected((current) => [...current, listingId]);
  };

  const move = (index: number, delta: number) => {
    setSelected((current) => {
      const next = [...current];
      const target = index + delta;
      if (target < 0 || target >= next.length) return current;
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
  };

  const saveAndContinue = async () => {
    if (saving) return;
    if (!selected.length) {
      setMessage('Add at least one current public rental property.');
      return;
    }
    if (shortlist
      && shortlist.listingIds.length === selected.length
      && shortlist.listingIds.every((id, index) => id === selected[index])) {
      onContinue();
      return;
    }
    const session = captureOperationsSession();
    if (!session) {
      setMessage('Your session has ended.');
      return;
    }
    setSaving(true);
    setMessage(null);
    dispatched.current = false;
    try {
      let saved: LeadShortlist;
      if (shortlist) {
        saved = await operationsLeadService.updateShortlist(
          lead.id,
          shortlist.id,
          lead.version,
          shortlist.version,
          selected,
          {
            session,
            onDispatch: () => { dispatched.current = true; }
          }
        );
      } else {
        createOperationId.current ??= requestId();
        saved = await operationsLeadService.createShortlist(
          lead.id,
          lead.version,
          selected,
          createOperationId.current,
          {
            session,
            onDispatch: () => { dispatched.current = true; }
          }
        );
      }
      if (!mounted.current || !isOperationsSessionActive(session)) return;
      createOperationId.current = null;
      onShortlist(saved);
      onContinue();
    } catch (cause) {
      if (!mounted.current || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)) return;
      const status = failureStatus(cause);
      if (status === 409) {
        setMessage('The Lead or shortlist changed while you were saving. Reload the current Lead and review the ordered selection.');
        await onLeadChanged();
      } else if (dispatched.current && (status === undefined || status >= 500)) {
        if (shortlist) {
          try {
            const current = await operationsLeadService.shortlist(lead.id, shortlist.id, { session });
            if (current.listingIds.length === selected.length
              && current.listingIds.every((id, index) => id === selected[index])) {
              onShortlist(current);
              onContinue();
              return;
            }
          } catch { /* Keep the explicit unknown outcome below. */ }
        } else if (createOperationId.current) {
          try {
            const receipt = await operationsLeadService.commandReceipt(
              createOperationId.current,
              'LEAD_SHORTLIST_CREATE',
              { session }
            );
            const id = Number(receipt.resultReference);
            if (receipt.state === 'RECORDED' && Number.isSafeInteger(id) && id > 0) {
              const recovered = await operationsLeadService.shortlist(lead.id, id, { session });
              onShortlist(recovered);
              onContinue();
              return;
            }
          } catch { /* Keep the explicit unknown outcome below. */ }
        }
        setMessage('We couldn’t confirm the shortlist save. Refresh before trying again.');
      } else {
        setMessage(failureMessage(cause, 'The shortlist could not be saved.'));
      }
    } finally {
      if (mounted.current) setSaving(false);
    }
  };

  return (
    <div className="ld-share-properties">
      <div className="ld-col">
        <form className="ld-card ld-search-card" onSubmit={(event) => { event.preventDefault(); setSearchAttempt((value) => value + 1); }}>
          <h2>Find current public rentals</h2>
          <label className="oe-field-stack">
            <span className="oe-field-label">Locality or property</span>
            <span className="ld-search-field">
              <Search size={16} aria-hidden="true" />
              <input
                className="oe-input ld-input"
                type="search"
                value={query}
                placeholder="Locality, title, or reference"
                onChange={(event) => setQuery(event.target.value)}
              />
            </span>
          </label>
          <div className="ld-filter-row">
            <label className="oe-field-stack">
              <span className="oe-field-label">BHK</span>
              <select className="oe-input ld-input" value={bhk} onChange={(event) => setBhk(event.target.value)}>
                <option value="">Any</option>
                {[1, 2, 3, 4].map((value) => <option key={value} value={value}>{value} BHK</option>)}
              </select>
            </label>
            <label className="oe-field-stack">
              <span className="oe-field-label">Rent up to (INR)</span>
              <input className="oe-input ld-input" inputMode="numeric" value={maxRent} onChange={(event) => setMaxRent(event.target.value.replace(/\D/g, ''))} />
            </label>
            <label className="oe-field-stack">
              <span className="oe-field-label">Furnishing</span>
              <select className="oe-input ld-input" value={furnishing} onChange={(event) => setFurnishing(event.target.value)}>
                <option value="">Any</option>
                <option value="FURNISHED">Furnished</option>
                <option value="SEMI_FURNISHED">Semi-furnished</option>
                <option value="UNFURNISHED">Unfurnished</option>
              </select>
            </label>
          </div>
          <p className="oe-footnote">
            Searches Pathome’s existing public rental discovery in {lead.cityName || 'the Lead City'}.
            Only active public listings are returned.
          </p>
          <div className="ld-actions">
            <button type="submit" className="oe-btn oe-btn--secondary oe-btn--md">
              <Search size={15} aria-hidden="true" />Search public homes
            </button>
            <button
              type="button"
              className="oe-btn oe-btn--ghost oe-btn--md"
              onClick={() => {
                setQuery('');
                setBhk('');
                setMaxRent('');
                setFurnishing('');
              }}
            >
              Clear filters
            </button>
          </div>
        </form>

        {message ? <Notice tone="warn" live title={message} /> : null}
        {loading && !properties.length ? (
          <div className="ld-property-grid" aria-busy="true">
            {[0, 1, 2].map((key) => <span key={key} className="oe-skel ld-property-skeleton" />)}
          </div>
        ) : !properties.length ? (
          <StateMessage
            icon={<Search size={20} />}
            title="No public rentals match"
            body="Try a nearby locality, a higher rent limit, or fewer filters. No private or unpublished listing is substituted."
          />
        ) : (
          <>
            <ul className="ld-property-grid" aria-label="Public rental properties">
              {properties.map((property) => {
                const position = selected.indexOf(property.id);
                return (
                  <li key={property.id} className="ld-property-card" data-added={position >= 0 || undefined}>
                    {property.images[0] ? (
                      <img src={property.images[0]} alt="" loading="lazy" />
                    ) : <span className="ld-property-no-image">No public cover image</span>}
                    <div className="ld-property-body">
                      <p className="ld-property-place">{property.sector} · {property.city}</p>
                      <h3>{property.title}</h3>
                      <p>{property.bhk || 'BHK not recorded'} · {property.furnishingStatus || 'Furnishing not recorded'}</p>
                      <p className="ld-property-rent"><strong>{formatMoney(property.monthlyRent)}</strong> / month</p>
                      <div className="ld-actions">
                        <a
                          className="oe-btn oe-btn--secondary oe-btn--sm"
                          href={`/property/${property.id}`}
                          target="_blank"
                          rel="noopener noreferrer"
                        >
                          Preview
                        </a>
                        <button
                          type="button"
                          className={`oe-btn oe-btn--sm ${position >= 0 ? 'oe-btn--secondary' : 'oe-btn--primary'}`}
                          aria-pressed={position >= 0}
                          onClick={() => add(property.id)}
                        >
                          {position >= 0 ? `Added #${position + 1}` : 'Add'}
                        </button>
                      </div>
                    </div>
                  </li>
                );
              })}
            </ul>
            {hasMore ? (
              <button type="button" className="oe-btn oe-btn--secondary oe-btn--md ld-more" disabled={loading} onClick={() => void loadMore()}>
                {loading ? 'Loading…' : 'Load more public homes'}
              </button>
            ) : null}
          </>
        )}
      </div>

      <aside className="ld-card ld-shortlist" aria-labelledby="ld-shortlist-title">
        <header className="ld-card-head">
          <h2 id="ld-shortlist-title">Ordered shortlist</h2>
          <span>{selected.length} / {SHORTLIST_MAX}</span>
        </header>
        <p className="oe-footnote">The server validates every selected listing again when this order is saved.</p>
        {selected.length ? (
          <ol className="ld-shortlist-list">
            {selected.map((listingId, index) => {
              const property = properties.find((item) => item.id === listingId);
              return (
                <li key={listingId}>
                  <span className="ld-order-number">{index + 1}</span>
                  <div>
                    <strong>{property?.title || `Property #${listingId}`}</strong>
                    <p>{property ? `${property.sector} · ${formatMoney(property.monthlyRent)}` : 'Current public facts load with search'}</p>
                  </div>
                  <div className="ld-order-actions">
                    <button type="button" className="oe-icon-btn" disabled={index === 0} aria-label="Move up" onClick={() => move(index, -1)}>
                      <ArrowLeft size={14} aria-hidden="true" />
                    </button>
                    <button type="button" className="oe-icon-btn" disabled={index === selected.length - 1} aria-label="Move down" onClick={() => move(index, 1)}>
                      <ArrowRight size={14} aria-hidden="true" />
                    </button>
                    <button type="button" className="oe-icon-btn" aria-label="Remove" onClick={() => add(listingId)}>
                      <Trash2 size={14} aria-hidden="true" />
                    </button>
                  </div>
                </li>
              );
            })}
          </ol>
        ) : <p className="ld-empty-shortlist">No properties selected.</p>}
        <div className="ld-actions">
          <button type="button" className="oe-btn oe-btn--primary oe-btn--md" disabled={saving || !selected.length} onClick={() => void saveAndContinue()}>
            {saving ? 'Saving…' : 'Save shortlist and continue'}
          </button>
        </div>
      </aside>
    </div>
  );
};

const ShareHistoryView: React.FC<{
  history: Awaited<ReturnType<typeof operationsLeadService.shareHistory>> | null;
  loading: boolean;
  page: number;
  onPage: (page: number) => void;
}> = ({ history, loading, page, onPage }) => {
  if (loading && !history) return <SkeletonRows rows={4} />;
  if (!history || history.items.length === 0) {
    return (
      <StateMessage
        icon={<History size={20} />}
        title="No property messages prepared yet"
        body="Prepared messages and the actions you record appear here. Reading history never opens WhatsApp or creates a message."
      />
    );
  }
  return (
    <div className="oe-stack">
      <ol className="ld-history" aria-label="Property message history, newest first">
        {history.items.map((share) => (
          <ShareHistoryCard key={share.id} share={share} />
        ))}
      </ol>
      {history.totalPages > 0 ? (
        <Pagination
          page={page}
          totalPages={history.totalPages}
          totalElements={history.totalElements}
          pageSize={SHARE_HISTORY_PAGE_SIZE}
          onPage={onPage}
        />
      ) : null}
    </div>
  );
};

const ShareHistoryCard: React.FC<{ share: LeadShareHistoryItem }> = ({ share }) => {
  const latest = share.events[share.events.length - 1];
  const current = latest ? eventMeta(latest.type) : { label: 'Prepared', tone: 'open' as const };
  return (
    <li className="ld-history-card">
      <header>
        <div>
          <h3>{share.publicLinks.length} {share.publicLinks.length === 1 ? 'public property' : 'public properties'}</h3>
        </div>
        <Tone tone={current.tone}>{current.label}</Tone>
      </header>
      <p className="ld-meta">
        Prepared {formatDateTime(share.recordedAt) || 'time not recorded'} · {share.mode === 'MANUAL_BUSINESS_HANDOFF' ? 'Send in WhatsApp yourself' : 'Copy links'}
        {share.maskedDestination ? ` · ${share.maskedDestination}` : ' · No WhatsApp destination'}
      </p>
      {!share.actionReady ? (
        <Notice tone="warn" title="This saved message no longer matches the current details">
          {!share.currentAuthorityMatches ? 'Your current ownership or Team access does not permit a new property message. ' : ''}
          {!share.currentConsentMatches ? 'The customer’s current permission differs from this message. ' : ''}
          {!share.currentDestinationMatches ? 'The confirmed WhatsApp number differs from this message. ' : ''}
          {!share.currentFactsMatch ? 'The selected properties or their public details have changed.' : ''}
          You can read this history, but the saved message cannot be changed.
        </Notice>
      ) : null}
      {share.events.length ? (
        <ul className="ld-history-events">
          {share.events.map((event) => (
            <li key={event.eventId}>
              <strong>{eventMeta(event.type).label}</strong>
              <span>{formatDateTime(event.occurredAt) || 'Time not recorded'}</span>
            </li>
          ))}
        </ul>
      ) : <p className="oe-footnote">No operator event recorded after preparation.</p>}
      <details className="ld-history-snapshot">
        <summary>View the saved message and links</summary>
        <pre>{share.previewText}</pre>
        <ul>
          {share.publicLinks.map((link) => (
            <li key={link}><a href={link} target="_blank" rel="noopener noreferrer">{link}</a></li>
          ))}
        </ul>
      </details>
    </li>
  );
};

const formatMoney = (value: number): string =>
  new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0
  }).format(value);

const ShareRecipientStep: React.FC<{
  lead: LeadDetail;
  existingPreference: LeadContactPreference | null;
  onBack: () => void;
  onConsent: (preference: LeadContactPreference, lead: LeadDetail) => void;
  onConflict: () => Promise<void>;
}> = ({ lead, existingPreference, onBack, onConsent, onConflict }) => {
  const [choice, setChoice] = useState<'SAME' | 'DIFFERENT' | 'NONE' | ''>('');
  const [countryCode, setCountryCode] = useState('+91');
  const [number, setNumber] = useState('');
  const [agreed, setAgreed] = useState(false);
  const [evidence, setEvidence] = useState<'CUSTOMER_VERBAL' | 'CUSTOMER_DIGITAL' | ''>('');
  const [busy, setBusy] = useState(false);
  const [checking, setChecking] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [pendingOperation, setPendingOperation] = useState<PendingConsentOperation | null>(null);
  const [recoveredConsent, setRecoveredConsent] = useState<{
    preference: LeadContactPreference;
    lead: LeadDetail;
    operationId: string;
  } | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const dispatched = useRef(false);
  const mounted = useRef(true);

  const reconcileConsentCommand = async (
    pending: PendingConsentOperation,
    session: NonNullable<ReturnType<typeof captureOperationsSession>>,
    unconfirmedReason: 'SERVICE_UNAVAILABLE' | 'SERVER_RESPONSE_LOST' | null = null
  ): Promise<{ preference: LeadContactPreference; lead: LeadDetail } | null> => {
    setChecking(true);
    try {
      const [receipt, current] = await Promise.all([
        operationsLeadService.commandReceipt(
          pending.operationId,
          'LEAD_CONTACT_PREFERENCE_RECORD',
          { session }
        ),
        operationsLeadService.contactPreferences(lead.id, { session })
      ]);
      if (!mounted.current || !isOperationsSessionActive(session)) return null;
      const latest = current[0] ?? null;
      if (receipt.state !== 'RECORDED') {
        setRecoveredConsent(null);
        setMessage(unconfirmedReason === 'SERVICE_UNAVAILABLE'
          ? 'The earlier response reported that the permission service was unavailable. The saved request is not recorded yet; it may still be finishing, and no second request was sent.'
          : unconfirmedReason === 'SERVER_RESPONSE_LOST'
            ? 'The saved permission request is not recorded yet after an unconfirmed response. It may still be finishing; no second request was sent.'
            : 'The saved permission request is not recorded yet. It may still be finishing; Pathome did not send another request. Check again.');
        return null;
      }

      const recordedRevision = consentReceiptRevision(receipt.resultReference);
      if (recordedRevision === null) {
        setRecoveredConsent(null);
        setMessage(
          'We couldn’t identify the saved permission. Sharing stays blocked; check the saved request again.'
        );
        return null;
      }
      if (!latest || latest.revision !== recordedRevision) {
        forgetPendingConsentOperation(session, lead.id, pending.operationId);
        setPendingOperation(null);
        setRecoveredConsent(null);
        setMessage(
          `The customer’s permission was saved, but it has since changed.`
          + (latest
            ? ` Current permission: ${latest.consentStatus === 'GRANTED' ? 'agreed' : latest.consentStatus === 'REVOKED' ? 'withdrawn' : latest.consentStatus === 'DISPUTED' ? 'disputed' : 'unknown'}.`
            : ' No current permission remains.')
          + ' Property sharing stays blocked until the customer’s current permission is confirmed.'
        );
        return null;
      }
      if (latest.consentStatus !== 'GRANTED') {
        forgetPendingConsentOperation(session, lead.id, pending.operationId);
        setPendingOperation(null);
        setRecoveredConsent(null);
        setMessage(
          `The customer’s current permission is ${latest.consentStatus === 'REVOKED' ? 'withdrawn' : latest.consentStatus === 'DISPUTED' ? 'disputed' : 'not approved'}. Sharing is blocked.`
        );
        await onConflict();
        return null;
      }

      const refreshed = await operationsLeadService.detail(lead.id, { session });
      if (!mounted.current || !isOperationsSessionActive(session)) return null;
      setPendingOperation(pending);
      setRecoveredConsent({
        preference: latest,
        lead: refreshed,
        operationId: pending.operationId
      });
      setMessage(
        'Pathome confirmed that the customer has agreed to receive property links.'
      );
      return { preference: latest, lead: refreshed };
    } catch (cause) {
      if (!mounted.current || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)) return null;
      setRecoveredConsent(null);
      if (failureStatus(cause) === 503) {
        setMessage(
          'The permission service is unavailable, so the result and current permission could not be confirmed. No second request was sent.'
        );
      } else {
        setMessage(failureMessage(cause,
          'The saved request and current permission could not be checked. Sharing stays blocked; check again.'));
      }
      return null;
    } finally {
      if (mounted.current) setChecking(false);
    }
  };

  useEffect(() => {
    mounted.current = true;
    const session = captureOperationsSession();
    if (session) {
      try {
        const pending = readPendingConsentOperation(session, lead.id);
        if (pending) {
          setPendingOperation(pending);
          void reconcileConsentCommand(pending, session);
        }
      } catch (cause) {
        setMessage(failureMessage(cause,
          'An earlier permission check could not be read. Confirm the current permission before saving another answer.'));
      }
    }
    return () => {
      mounted.current = false;
    };
  }, []);

  const submit = async () => {
    if (busy || checking) return;
    const nextErrors: Record<string, string> = {};
    if (!choice) nextErrors.choice = 'Ask the customer and choose a destination answer.';
    if (choice === 'DIFFERENT') {
      const cleanCode = countryCode.trim();
      const localDigits = number.replace(/\D/g, '');
      if (!/^\+[1-9]\d{0,3}$/.test(cleanCode)) nextErrors.countryCode = 'Use a valid calling code, such as +91.';
      if (!/^[1-9]\d{6,14}$/.test(localDigits)) nextErrors.number = 'Enter the number without a local trunk prefix.';
      if (/^[1-9]\d{0,3}$/.test(cleanCode)
        && localDigits
        && !new RegExp(`^\\+[1-9]\\d{7,14}$`).test(`${cleanCode}${localDigits}`)) {
        nextErrors.number = 'The combined destination must be a valid E.164 number.';
      }
    }
    if (!agreed) nextErrors.agreed = 'Confirm the customer agreed to receive property links.';
    if (!evidence) nextErrors.evidence = 'Record how the customer gave this agreement.';
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length || !choice) return;
    const session = captureOperationsSession();
    if (!session) {
      setMessage('Your session has ended.');
      return;
    }
    try {
      const pending = readPendingConsentOperation(session, lead.id);
      if (pending) {
        setPendingOperation(pending);
        setMessage('Check the previous consent command before recording another response.');
        await reconcileConsentCommand(pending, session);
        return;
      }
    } catch (cause) {
      setMessage(failureMessage(cause,
        'An earlier permission check could not be read. Confirm the current permission before saving another answer.'));
      return;
    }
    let pending: PendingConsentOperation;
    try {
      pending = preservePendingConsentOperation(session, lead.id, {
        operationId: requestId(),
        leadVersion: lead.version,
        preferenceRevision: existingPreference?.revision ?? 0
      });
    } catch (cause) {
      setMessage(preDispatchPermissionFailure(cause));
      return;
    }
    setPendingOperation(pending);
    setBusy(true);
    setMessage(null);
    dispatched.current = false;
    try {
      const preference = await operationsLeadService.recordContactPreference(
        lead.id,
        {
          expectedLeadVersion: lead.version,
          expectedPreferenceRevision: existingPreference?.revision ?? 0,
          channel: choice === 'NONE' ? 'OTHER' : 'WHATSAPP',
          destinationChoice: choice,
          countryCallingCode: choice === 'DIFFERENT' ? countryCode.trim() : null,
          number: choice === 'DIFFERENT' ? number.replace(/\D/g, '') : null,
          consentStatus: 'GRANTED',
          consentWordingVersion: 'property-sharing-v1',
          evidenceMethod: evidence || null
        },
        pending.operationId,
        {
          session,
          onDispatch: () => { dispatched.current = true; }
        }
      );
      if (!mounted.current || !isOperationsSessionActive(session)) return;
      const reconciled = await reconcileConsentCommand(pending, session);
      if (!reconciled || !mounted.current || !isOperationsSessionActive(session)) return;
      forgetPendingConsentOperation(session, lead.id, pending.operationId);
      setPendingOperation(null);
      setRecoveredConsent(null);
      onConsent(reconciled.preference, reconciled.lead);
    } catch (cause) {
      if (!mounted.current || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)) return;
      const status = failureStatus(cause);
      if (status === 409) {
        forgetPendingConsentOperation(session, lead.id, pending.operationId);
        setPendingOperation(null);
        setRecoveredConsent(null);
        setMessage('The Lead or customer permission changed. Current details were refreshed; confirm the WhatsApp number and permission again.');
        setAgreed(false);
        await onConflict();
      } else if (status === 503) {
        setMessage(
          'The permission service is unavailable, so the result could not be confirmed. Pathome will check the saved permission request now; no second request will be sent.'
        );
        await reconcileConsentCommand(pending, session, 'SERVICE_UNAVAILABLE');
      } else if (dispatched.current && consentOutcomeIsUnconfirmed(status)) {
        setMessage(
          'Pathome did not confirm the permission response. It will check the saved request and current permission before another save.'
        );
        await reconcileConsentCommand(pending, session, 'SERVER_RESPONSE_LOST');
      } else {
        forgetPendingConsentOperation(session, lead.id, pending.operationId);
        setPendingOperation(null);
        setRecoveredConsent(null);
        setMessage(consentFailureMessage(cause, 'Sharing consent could not be recorded.'));
      }
    } finally {
      if (mounted.current) setBusy(false);
    }
  };

  const recordNonGranted = async (consentStatus: 'REVOKED' | 'DISPUTED') => {
    if (busy || checking || !existingPreference) return;
    const session = captureOperationsSession();
    if (!session) {
      setMessage('Your session has ended.');
      return;
    }
    let pending: PendingConsentOperation;
    try {
      pending = preservePendingConsentOperation(session, lead.id, {
        operationId: requestId(),
        leadVersion: lead.version,
        preferenceRevision: existingPreference.revision
      });
    } catch (cause) {
      setMessage(preDispatchPermissionFailure(cause));
      return;
    }
    setPendingOperation(pending);
    setBusy(true);
    setMessage(null);
    try {
      const preference = await operationsLeadService.recordContactPreference(
        lead.id,
        {
          expectedLeadVersion: lead.version,
          expectedPreferenceRevision: existingPreference.revision,
          channel: 'OTHER',
          destinationChoice: 'NONE',
          countryCallingCode: null,
          number: null,
          consentStatus,
          consentWordingVersion: null,
          evidenceMethod: null
        },
        pending.operationId,
        { session }
      );
      if (!mounted.current || !isOperationsSessionActive(session)) return;
      setChoice('');
      setAgreed(false);
      setEvidence('');
      forgetPendingConsentOperation(session, lead.id, pending.operationId);
      setPendingOperation(null);
      setRecoveredConsent(null);
      setMessage(consentStatus === 'REVOKED'
        ? 'Customer withdrawal saved. New copy and WhatsApp actions are blocked.'
        : 'Customer dispute saved. New copy and WhatsApp actions are blocked.');
      await onConflict();
    } catch (cause) {
      if (!mounted.current || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)) return;
      const status = failureStatus(cause);
      if (status === 409) {
        forgetPendingConsentOperation(session, lead.id, pending.operationId);
        setPendingOperation(null);
        setMessage(consentFailureMessage(cause,
          'The customer consent change could not be recorded.'));
        await onConflict();
      } else if (status === 503 || consentOutcomeIsUnconfirmed(status)) {
        setMessage(
          'The permission change could not be confirmed. Pathome will check the saved request and current permission; no second request is sent automatically.'
        );
        await reconcileConsentCommand(
          pending,
          session,
          status === 503 ? 'SERVICE_UNAVAILABLE' : 'SERVER_RESPONSE_LOST'
        );
      } else {
        forgetPendingConsentOperation(session, lead.id, pending.operationId);
        setPendingOperation(null);
        setMessage(consentFailureMessage(cause,
          'The customer consent change could not be recorded.'));
      }
    } finally {
      if (mounted.current) setBusy(false);
    }
  };

  const checkPendingAgain = async () => {
    if (!pendingOperation || checking) return;
    const session = captureOperationsSession();
    if (!session) {
      setMessage('Your session has ended.');
      return;
    }
    await reconcileConsentCommand(pendingOperation, session);
  };

  const continueRecoveredConsent = () => {
    if (!pendingOperation || !recoveredConsent) return;
    const session = captureOperationsSession();
    if (!session || !isOperationsSessionActive(session)) {
      setMessage('Your session has ended. No sharing action was opened.');
      return;
    }
    forgetPendingConsentOperation(session, lead.id, pendingOperation.operationId);
    setPendingOperation(null);
    setRecoveredConsent(null);
    onConsent(recoveredConsent.preference, recoveredConsent.lead);
  };

  const abandonPendingCheck = () => {
    const session = captureOperationsSession();
    if (session && pendingOperation) {
      forgetPendingConsentOperation(session, lead.id, pendingOperation.operationId);
    }
    setPendingOperation(null);
    setRecoveredConsent(null);
    setMessage(
      'The earlier request was left unchanged and is no longer being checked here. Confirm the current permission before entering a new customer answer.'
    );
  };

  return (
    <section className="ld-card ld-form" aria-labelledby="ld-recipient-title">
      <h2 id="ld-recipient-title" tabIndex={-1}>Confirm the customer’s WhatsApp number</h2>
      <p className="oe-lede">
        Ask the customer where they want to receive the property links. Pathome saves this answer and
        the customer’s permission together.
      </p>
      {existingPreference ? (
        <Notice
          tone={existingPreference.consentStatus === 'GRANTED' ? 'info' : 'warn'}
          title={existingPreference.consentStatus === 'GRANTED'
            ? 'Customer has agreed to receive property links'
            : existingPreference.consentStatus === 'REVOKED'
              ? 'Customer withdrew permission'
              : existingPreference.consentStatus === 'DISPUTED'
                ? 'Customer disputed permission'
                : 'Customer permission is unknown'}
        >
          {existingPreference.maskedDestination ? ` · ${existingPreference.maskedDestination}` : ' · no WhatsApp destination'}
          {existingPreference.recordedAt ? ` · saved ${formatDateTime(existingPreference.recordedAt)}` : ''}.
          Confirm again for this property message.
        </Notice>
      ) : null}
      {message ? <Notice tone="warn" live title={message} /> : null}
      {pendingOperation ? (
        <div className="ld-card" aria-label="Unconfirmed consent command">
          <p className="oe-footnote">
            This check only reads the saved request. It never saves another permission or opens WhatsApp.
          </p>
          <div className="ld-actions">
            {recoveredConsent ? (
              <button
                type="button"
                className="oe-btn oe-btn--primary oe-btn--md"
                disabled={checking}
                onClick={continueRecoveredConsent}
              >
                Continue with saved permission
              </button>
            ) : (
              <button
                type="button"
                className="oe-btn oe-btn--primary oe-btn--md"
                disabled={checking}
                onClick={() => void checkPendingAgain()}
              >
                {checking ? 'Checking saved request…' : 'Check saved request again'}
              </button>
            )}
            <button
              type="button"
              className="oe-btn oe-btn--secondary oe-btn--md"
              disabled={checking}
              onClick={abandonPendingCheck}
            >
              Stop checking this request
            </button>
          </div>
        </div>
      ) : null}
      {existingPreference?.consentStatus === 'GRANTED' ? (
        <div className="ld-card ld-consent-change" aria-label="Save customer permission change">
          <p>
            If the customer now withdraws or disputes the property-sharing agreement, record that change
            before any further copy or WhatsApp action.
          </p>
          <div className="ld-actions">
            <button
              type="button"
              className="oe-btn oe-btn--danger-outline oe-btn--md"
              disabled={busy || checking || pendingOperation !== null}
              onClick={() => void recordNonGranted('REVOKED')}
            >
              Customer withdrew permission
            </button>
            <button
              type="button"
              className="oe-btn oe-btn--secondary oe-btn--md"
              disabled={busy || checking || pendingOperation !== null}
              onClick={() => void recordNonGranted('DISPUTED')}
            >
              Customer disputes permission
            </button>
          </div>
        </div>
      ) : null}

      <fieldset className="ld-choice-list" disabled={busy || checking || pendingOperation !== null}>
        <legend className="oe-field-label">Where will WhatsApp open?</legend>
        <label className="ld-choice">
          <input type="radio" name="ld-destination" checked={choice === 'SAME'} onChange={() => setChoice('SAME')} />
          <span>
            <strong>Same as the customer’s contact number</strong>
            <small className="oe-mono">{lead.maskedContact || 'Masked contact unavailable'}</small>
          </span>
        </label>
        <label className="ld-choice">
          <input type="radio" name="ld-destination" checked={choice === 'DIFFERENT'} onChange={() => setChoice('DIFFERENT')} />
          <span>
            <strong>Different WhatsApp number</strong>
            <small>Enter the number the customer gives you now.</small>
          </span>
        </label>
        {choice === 'DIFFERENT' ? (
          <div className="ld-choice-detail">
            <label className="oe-field-stack">
              <span className="oe-field-label">Country calling code</span>
              <input
                className="oe-input ld-input"
                inputMode="tel"
                value={countryCode}
                aria-invalid={Boolean(errors.countryCode)}
                onChange={(event) => setCountryCode(event.target.value)}
              />
              {errors.countryCode ? <span className="oe-add-err">{errors.countryCode}</span> : null}
            </label>
            <label className="oe-field-stack">
              <span className="oe-field-label">Destination number</span>
              <input
                className="oe-input ld-input"
                type="tel"
                inputMode="tel"
                value={number}
                aria-invalid={Boolean(errors.number)}
                onChange={(event) => setNumber(event.target.value)}
              />
              {errors.number ? <span className="oe-add-err">{errors.number}</span> : null}
            </label>
          </div>
        ) : null}
        <label className="ld-choice">
          <input type="radio" name="ld-destination" checked={choice === 'NONE'} onChange={() => setChoice('NONE')} />
          <span>
            <strong>No WhatsApp / agreed alternative</strong>
            <small>The operator copies the prepared message and sends it through the agreed alternative.</small>
          </span>
        </label>
        {errors.choice ? <p className="oe-add-err" role="alert">{errors.choice}</p> : null}
      </fieldset>

      {choice ? (
        <fieldset className="ld-consent" disabled={busy || checking || pendingOperation !== null}>
          <legend>Customer permission</legend>
          <p>
            Confirm that the customer agreed to receive these property links
            {choice === 'SAME' ? ' on the contact number' : choice === 'DIFFERENT' ? ' on the number entered above' : ' through the agreed alternative'} for this enquiry.
          </p>
          <label className="oe-check">
            <input type="checkbox" checked={agreed} onChange={(event) => setAgreed(event.target.checked)} />
            The customer clearly gave this agreement now.
          </label>
          {errors.agreed ? <p className="oe-add-err" role="alert">{errors.agreed}</p> : null}
          <fieldset className="ld-subfield">
            <legend className="oe-field-label">How was agreement given?</legend>
            <div className="oe-seg-radios">
              <label className="oe-seg-radio">
                <input type="radio" name="ld-evidence" checked={evidence === 'CUSTOMER_VERBAL'} onChange={() => setEvidence('CUSTOMER_VERBAL')} />
                Verbal on this contact
              </label>
              <label className="oe-seg-radio">
                <input type="radio" name="ld-evidence" checked={evidence === 'CUSTOMER_DIGITAL'} onChange={() => setEvidence('CUSTOMER_DIGITAL')} />
                Digital evidence
              </label>
            </div>
            {errors.evidence ? <p className="oe-add-err" role="alert">{errors.evidence}</p> : null}
          </fieldset>
        </fieldset>
      ) : null}

      <div className="ld-actions">
        <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" onClick={onBack}>Back to properties</button>
        <button
          type="button"
          className="oe-btn oe-btn--primary oe-btn--md"
          disabled={busy || checking || pendingOperation !== null}
          onClick={() => void submit()}
        >
          {busy ? 'Saving permission…' : 'Save permission and continue'}
        </button>
      </div>
    </section>
  );
};

const SharePrepareStep: React.FC<{
  lead: LeadDetail;
  shortlist: LeadShortlist;
  preference: LeadContactPreference;
  onBack: () => void;
  onPrepared: (share: LeadShare) => void;
  onConflict: () => Promise<void>;
}> = ({ lead, shortlist, preference, onBack, onPrepared, onConflict }) => {
  const [busy, setBusy] = useState(false);
  const [checking, setChecking] = useState(false);
  const [pendingOperation, setPendingOperation] =
    useState<PendingSharePrepareOperation | null>(null);
  const [definitiveRejection, setDefinitiveRejection] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const mounted = useRef(true);
  const controller = useRef<AbortController | null>(null);
  const prepareInFlight = useRef(false);
  const checkInFlight = useRef(false);
  const generation = useRef(0);

  const commandFor = (
    pending: PendingSharePrepareOperation
  ): LeadSharePrepareCommand => ({
    operationId: pending.operationId,
    expectedLeadVersion: pending.expectedLeadVersion,
    shortlistId: pending.shortlistId,
    expectedShortlistVersion: pending.expectedShortlistVersion,
    contactPreferenceRevision: pending.contactPreferenceRevision,
    mode: pending.mode
  });

  useEffect(() => {
    mounted.current = true;
    const session = captureOperationsSession();
    if (session) {
      try {
        const pending = readPendingSharePreparation(session, lead.id);
        if (pending) {
          setPendingOperation(pending);
          setMessage(
            'We couldn’t confirm that the property message is ready. No message has been sent to the customer.'
          );
        }
      } catch (cause) {
        setMessage(failureMessage(
          cause,
          'The earlier property message could not be checked in this browser.'
        ));
      }
    }
    return () => {
      mounted.current = false;
      generation.current += 1;
      prepareInFlight.current = false;
      checkInFlight.current = false;
      controller.current?.abort();
    };
  }, [lead.id]);

  useEffect(() => subscribeOperationsSession(() => {
    generation.current += 1;
    prepareInFlight.current = false;
    checkInFlight.current = false;
    controller.current?.abort();
    if (!mounted.current) return;
    setPendingOperation(null);
    setBusy(false);
    setChecking(false);
    setDefinitiveRejection(false);
    setMessage('Your signed-in account changed. The earlier property-message check was stopped. No message was sent.');
  }), [lead.id]);

  const prepare = async () => {
    if (prepareInFlight.current || busy || checking || pendingOperation) return;
    const session = captureOperationsSession();
    if (!session) {
      setMessage('Your session has ended.');
      return;
    }
    prepareInFlight.current = true;
    const operationGeneration = generation.current;
    let pending: PendingSharePrepareOperation;
    try {
      pending = preservePendingSharePreparation(session, {
        operationId: requestId(),
        leadId: lead.id,
        expectedLeadVersion: lead.version,
        shortlistId: shortlist.id,
        expectedShortlistVersion: shortlist.version,
        contactPreferenceRevision: preference.revision,
        mode: preference.destinationChoice === 'NONE'
          ? 'COPY_LINKS'
          : 'MANUAL_BUSINESS_HANDOFF'
      });
    } catch (cause) {
      prepareInFlight.current = false;
      setMessage(failureMessage(
        cause,
        'This browser could not keep the property message request. Nothing was sent.'
      ));
      return;
    }
    setPendingOperation(pending);
    setDefinitiveRejection(false);
    setBusy(true);
    setMessage(null);
    const next = new AbortController();
    controller.current = next;
    try {
      const share = await operationsLeadService.prepareShare(
        lead.id,
        commandFor(pending),
        {
          session,
          signal: next.signal
        }
      );
      if (!mounted.current || !isOperationsSessionActive(session)
          || operationGeneration !== generation.current) return;
      forgetPendingSharePreparation(session, lead.id, pending.operationId);
      setPendingOperation(null);
      onPrepared(share);
    } catch (cause) {
      if (!mounted.current || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)
        || operationGeneration !== generation.current) return;
      const failure = classifySharePrepareFailure(cause);
      if (failure.kind === 'conflict') {
        forgetPendingSharePreparation(session, lead.id, pending.operationId);
        setPendingOperation(null);
        setDefinitiveRejection(false);
        await onConflict();
      } else if (failure.kind === 'definitive-rejection') {
        forgetPendingSharePreparation(session, lead.id, pending.operationId);
        setPendingOperation(null);
        setDefinitiveRejection(true);
        setMessage(failure.message);
      } else if (failure.kind === 'uncertain') {
        setDefinitiveRejection(false);
        setMessage(failure.message);
      } else {
        forgetPendingSharePreparation(session, lead.id, pending.operationId);
        setPendingOperation(null);
        setDefinitiveRejection(false);
        setMessage(failure.message);
        const status = failureStatus(cause);
        if (status === 401 || status === 403 || status === 404) await onConflict();
      }
    } finally {
      prepareInFlight.current = false;
      if (mounted.current) setBusy(false);
    }
  };

  const checkPrepared = async () => {
    if (!pendingOperation || checkInFlight.current || busy || checking) return;
    const session = captureOperationsSession();
    if (!session) {
      setMessage('Your session has ended. No message was sent.');
      return;
    }
    checkInFlight.current = true;
    const operationGeneration = generation.current;
    setChecking(true);
    setMessage(null);
    const next = new AbortController();
    controller.current = next;
    try {
      const receipt = await operationsLeadService.commandReceipt(
        pendingOperation.operationId,
        'LEAD_SHARE_PREPARE',
        { session, signal: next.signal }
      );
      if (!mounted.current || !isOperationsSessionActive(session)
          || operationGeneration !== generation.current) return;
      if (receipt.state !== 'RECORDED') {
        setMessage(
          'This property message is not ready yet. It may still be finishing. No message has been sent to the customer.'
        );
        return;
      }
      const shareId = receipt.resultReference;
      if (!shareId) {
        setMessage(
          'We couldn’t identify the prepared property message. No message has been sent. Please check again.'
        );
        return;
      }
      const share = await operationsLeadService.preparedShareHistoryFromHistory(
        lead.id,
        shareId,
        { session, signal: next.signal }
      );
      if (!mounted.current || !isOperationsSessionActive(session)
          || operationGeneration !== generation.current) return;
      if (!share) {
        setMessage(
          'We couldn’t find this prepared message in the available history. No message has been sent. Please check again.'
        );
        return;
      }
      if (share.id !== shareId
        || share.shortlistId !== pendingOperation.shortlistId
        || share.shortlistVersion !== pendingOperation.expectedShortlistVersion
        || share.contactPreferenceRevision !== pendingOperation.contactPreferenceRevision
        || share.mode !== pendingOperation.mode) {
        setMessage(
          'The saved property message does not match this request. Nothing was opened. Review the request before trying again.'
        );
        return;
      }
      if (!share.currentAuthorityMatches) {
        forgetPendingSharePreparation(session, lead.id, pendingOperation.operationId);
        setPendingOperation(null);
        setMessage('This Lead is no longer assigned to you under the current access. Nothing was opened.');
        await onConflict();
        return;
      }
      if (!share.currentConsentMatches) {
        forgetPendingSharePreparation(session, lead.id, pendingOperation.operationId);
        setPendingOperation(null);
        setMessage('The customer’s current permission no longer matches this saved message. Nothing was opened.');
        await onConflict();
        return;
      }
      if (!share.currentDestinationMatches) {
        forgetPendingSharePreparation(session, lead.id, pendingOperation.operationId);
        setPendingOperation(null);
        setMessage('The confirmed WhatsApp number changed after this message was prepared. Confirm the number again.');
        await onConflict();
        return;
      }
      if (!share.currentFactsMatch) {
        forgetPendingSharePreparation(session, lead.id, pendingOperation.operationId);
        setPendingOperation(null);
        setMessage('The selected properties or their public details changed. Prepare a new message before continuing.');
        await onConflict();
        return;
      }
      if (!share.actionReady) {
        forgetPendingSharePreparation(session, lead.id, pendingOperation.operationId);
        setPendingOperation(null);
        setMessage('This saved message can no longer be used for a new action. Review the current details before preparing another.');
        await onConflict();
        return;
      }
      forgetPendingSharePreparation(session, lead.id, pendingOperation.operationId);
      setPendingOperation(null);
      setDefinitiveRejection(false);
      onPrepared(leadShareFromHistoryItem(share, lead.id));
    } catch (cause) {
      if (!mounted.current || isOperationsSessionChangedError(cause)
        || !isOperationsSessionActive(session)
        || operationGeneration !== generation.current) return;
      const status = failureStatus(cause);
      if (cause instanceof ShareHistoryBoundReachedError) {
        setMessage('The saved result exists, but this message is older than the history this screen can safely check. No message has been sent. Review Message history before trying again.');
      } else if (status === 401) {
        setMessage('Your session has ended. No message was sent.');
      } else if (status === 403 || status === 404) {
        setMessage('This Lead is no longer available with your current access. No message was sent.');
        await onConflict();
      } else if (status === 503) {
        setMessage(
          'We couldn’t check whether the property message is ready because the service is unavailable. No message has been sent. Please check again.'
        );
      } else {
        setMessage(
          'We couldn’t check whether the property message is ready. No message has been sent. Please check again.'
        );
      }
    } finally {
      checkInFlight.current = false;
      if (mounted.current) setChecking(false);
    }
  };

  return (
    <section className="ld-card" aria-labelledby="ld-prepare-title">
      <h2 id="ld-prepare-title" tabIndex={-1}>Prepare the property message</h2>
      <p className="oe-lede">
        Pathome will check the selected properties, the customer’s permission and the current public
        links before saving one message. This does not contact the customer.
      </p>
      {message ? <Notice tone={pendingOperation ? 'warn' : 'info'} live title={message} /> : null}
      <dl className="ld-review-grid">
        <div>
          <dt>Lead</dt>
          <dd>{displayName(lead)}</dd>
        </div>
        <div>
          <dt>Properties</dt>
          <dd>{shortlist.listingIds.length} selected public {shortlist.listingIds.length === 1 ? 'property' : 'properties'}</dd>
        </div>
        <div>
          <dt>WhatsApp number</dt>
          <dd>{preference.destinationChoice === 'NONE'
            ? 'No WhatsApp number — copy only'
            : preference.maskedDestination || 'Customer-confirmed number'}</dd>
        </div>
        <div>
          <dt>Customer permission</dt>
          <dd>{preference.consentStatus === 'GRANTED'
            ? 'Customer has agreed to receive property links'
            : 'Current permission must be confirmed again'}</dd>
        </div>
        <div>
          <dt>Next action</dt>
          <dd>{preference.destinationChoice === 'NONE'
            ? 'Copy the message through the agreed alternative'
            : 'Open WhatsApp and send it yourself'}</dd>
        </div>
      </dl>
      <Notice title="No message has been sent to the customer">
        Preparing saves the exact property message and links for you to review. It does not open
        WhatsApp or contact the customer.
      </Notice>
      <div className="ld-actions">
        <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" onClick={onBack}>
          Back to WhatsApp number
        </button>
        {pendingOperation ? (
          <button
            type="button"
            className="oe-btn oe-btn--primary oe-btn--md"
            disabled={checking}
            onClick={() => void checkPrepared()}
          >
            {checking ? 'Checking…' : 'Check if message is ready'}
          </button>
        ) : (
          <button
            type="button"
            className="oe-btn oe-btn--primary oe-btn--md"
            disabled={busy}
            onClick={() => void prepare()}
          >
            {busy
              ? 'Preparing…'
              : definitiveRejection
                ? 'Prepare message'
                : 'Prepare property message'}
          </button>
        )}
      </div>
    </section>
  );
};

