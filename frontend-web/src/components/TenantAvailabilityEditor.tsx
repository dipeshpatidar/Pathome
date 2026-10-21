import React, { FormEvent, useEffect, useRef, useState } from 'react';
import { CalendarClock, LoaderCircle } from 'lucide-react';
import type { TenantVisitRequest } from '../services/tenantVisitService';
import {
  formatTenantAvailability,
  formatTenantAvailabilityWindow,
  TENANT_VISIT_ZONES,
  type TenantAvailabilityDraft,
  type TenantAvailabilityFieldName,
  type TenantAvailabilityFields
} from '../utils/tenantAvailability';

const focusClass = 'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:ring-emerald-700';
const inputClass = 'mt-1 min-h-11 w-full min-w-0 rounded-lg border border-slate-300 bg-white px-3 text-base text-slate-900 outline-none focus:border-emerald-700 focus:ring-2 focus:ring-emerald-100';

interface Props {
  request: TenantVisitRequest;
  draft: TenantAvailabilityDraft | null;
  busy: boolean;
  onToggle: (open: boolean) => void;
  onEdit: (field: TenantAvailabilityFieldName, key: keyof TenantAvailabilityFields, value: string) => void;
  onZoneChange: (zoneId: string) => void;
  onSave: () => void;
  onClear: () => void;
  onReconcile: () => void;
  onRebase: () => void;
  onDiscard: () => void;
}

/**
 * Controlled tenant-self availability editor for pre-booking work.
 *
 * Every editable value and every uncertain outcome lives in the dashboard-owned draft, so a history
 * refresh, a failed write or a Request displaced from page zero cannot destroy the tenant's input.
 * This component only renders that state and forwards explicit user decisions.
 */
export const TenantAvailabilityEditor: React.FC<Props> = ({
  request,
  draft,
  busy,
  onToggle,
  onEdit,
  onZoneChange,
  onSave,
  onClear,
  onReconcile,
  onRebase,
  onDiscard
}) => {
  const [confirmClear, setConfirmClear] = useState(false);
  const errorRef = useRef<HTMLParagraphElement | null>(null);
  const noticeRef = useRef<HTMLParagraphElement | null>(null);
  const priorError = useRef('');
  const priorReviewRequired = useRef(false);
  const current = formatTenantAvailability(request);
  const currentFacts = draft?.currentFacts
    ? formatTenantAvailabilityWindow(draft.currentFacts.startAt, draft.currentFacts.endAt, draft.currentFacts.zoneId)
    : null;
  const open = draft?.editorOpen === true;
  const form = draft?.form ?? null;
  const zoneOptions = form && form.zoneId && !TENANT_VISIT_ZONES.includes(form.zoneId as typeof TENANT_VISIT_ZONES[number])
    ? [form.zoneId, ...TENANT_VISIT_ZONES]
    : TENANT_VISIT_ZONES;
  const saving = draft?.status === 'saving';
  const reading = draft?.reconciliation === 'reading';
  const locked = busy || saving || reading;
  const canSave = form !== null && !locked && draft?.reviewRequired !== true;
  useEffect(() => {
    const error = draft?.error ?? '';
    const reviewRequired = draft?.reviewRequired === true && !reading;
    if (error && error !== priorError.current) errorRef.current?.focus({ preventScroll: true });
    else if (reviewRequired && !priorReviewRequired.current) noticeRef.current?.focus({ preventScroll: true });
    priorError.current = error;
    priorReviewRequired.current = reviewRequired;
  }, [draft?.error, draft?.reviewRequired, reading, draft]);

  const change = (field: TenantAvailabilityFieldName, key: keyof TenantAvailabilityFields, value: string) => {
    onEdit(field, key, value);
  };

  return (
    <div className="mt-3 border-t border-[#e7e8df] pt-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="text-xs font-semibold uppercase tracking-[0.1em] text-slate-500">Available times</p>
          <p className="mt-1 text-sm leading-5 text-slate-800">
            {current ?? 'No structured availability is saved yet.'}
          </p>
          <p className="mt-1 text-xs leading-5 text-slate-600">
            Scheduling uses this current window. Your original preferred timing stays as a separate record.
          </p>
        </div>
        <button
          type="button"
          aria-expanded={open}
          onClick={() => {
            setConfirmClear(false);
            onToggle(!open);
          }}
          className={`inline-flex min-h-11 items-center gap-1.5 rounded-lg px-2 text-xs font-semibold text-emerald-900 underline underline-offset-2 hover:bg-emerald-50 ${focusClass}`}
        >
          <CalendarClock size={15} aria-hidden="true" />
          {open ? 'Close editor' : 'Update available times'}
        </button>
      </div>

      {open && form && draft ? (
        <form
          onSubmit={(event: FormEvent<HTMLFormElement>) => {
            event.preventDefault();
            if (canSave) onSave();
          }}
          aria-busy={locked}
          className="mt-3 rounded-xl border border-[#e7e8df] bg-[#fbfaf5] p-3"
        >
          <label className="block text-xs font-semibold text-slate-700">
            Visit timezone
            <select
              value={form.zoneId}
              onChange={event => onZoneChange(event.target.value)}
              disabled={locked}
              className={inputClass}
            >
              <option value="">Choose a timezone</option>
              {zoneOptions.map(zone => <option key={zone} value={zone}>{zone}</option>)}
            </select>
          </label>
          <p className="mt-1 text-[11px] leading-4 text-slate-600">
            These clocks are read in the selected visit timezone. Changing it re-labels untouched saved times without moving them.
          </p>

          <fieldset className="mt-3 min-w-0">
            <legend className="text-xs font-semibold text-slate-700">Available window</legend>
            <div className="mt-1 grid gap-2 sm:grid-cols-2">
              <label className="block text-xs font-semibold text-slate-700">
                Start date
                <input type="date" value={form.startDate} disabled={locked}
                  onChange={event => change('start', 'startDate', event.target.value)} className={inputClass} />
              </label>
              <label className="block text-xs font-semibold text-slate-700">
                Start time
                <input type="time" value={form.startTime} disabled={locked}
                  onChange={event => change('start', 'startTime', event.target.value)} className={inputClass} />
              </label>
              <label className="block text-xs font-semibold text-slate-700">
                End date
                <input type="date" value={form.endDate} disabled={locked}
                  onChange={event => change('end', 'endDate', event.target.value)} className={inputClass} />
              </label>
              <label className="block text-xs font-semibold text-slate-700">
                End time
                <input type="time" value={form.endTime} disabled={locked}
                  onChange={event => change('end', 'endTime', event.target.value)} className={inputClass} />
              </label>
            </div>
          </fieldset>

          <fieldset className="mt-3 min-w-0">
            <legend className="text-xs font-semibold text-slate-700">Preferred time <span className="font-normal text-slate-500">Optional</span></legend>
            <div className="mt-1 grid gap-2 sm:grid-cols-2">
              <label className="block text-xs font-semibold text-slate-700">
                Preferred date
                <input type="date" value={form.preferredDate} disabled={locked}
                  onChange={event => change('preferred', 'preferredDate', event.target.value)} className={inputClass} />
              </label>
              <label className="block text-xs font-semibold text-slate-700">
                Preferred time
                <input type="time" value={form.preferredTime} disabled={locked}
                  onChange={event => change('preferred', 'preferredTime', event.target.value)} className={inputClass} />
              </label>
            </div>
          </fieldset>

          <p className="mt-2 text-[11px] leading-4 text-slate-600">
            Saving a window does not book or confirm an appointment. The original request text is never overwritten.
          </p>

          {draft.error ? (
            <p ref={errorRef} tabIndex={-1} role="alert"
              className="mt-2 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs leading-5 text-rose-900 outline-none">
              {draft.error}
            </p>
          ) : null}
          {draft.notice ? (
            <p ref={noticeRef} tabIndex={-1} role="status" aria-live="polite"
              className="mt-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-950 outline-none">
              {draft.notice}
            </p>
          ) : null}
          {draft.currentFacts && (draft.reconciliation === 'mismatch' || draft.reconciliation === 'unresolved') ? (
            <p className="mt-2 text-xs leading-5 text-slate-700">
              <span className="font-semibold">Current saved times:</span> {currentFacts ?? 'No window saved.'}
            </p>
          ) : null}

          {draft.reviewRequired ? (
            <div className="mt-2 flex flex-wrap gap-2">
              {draft.currentFacts ? (
                <button type="button" onClick={onRebase}
                  className={`min-h-11 rounded-lg border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-800 hover:bg-slate-50 ${focusClass}`}>
                  Use saved times as my starting point
                </button>
              ) : null}
              <button type="button" onClick={onReconcile} disabled={reading}
                className={`min-h-11 rounded-lg border border-emerald-300 bg-white px-4 text-sm font-semibold text-emerald-900 hover:bg-emerald-50 disabled:opacity-60 ${focusClass}`}>
                {reading ? 'Checking saved times…' : 'Recheck saved times'}
              </button>
            </div>
          ) : null}

          <div className="mt-3 flex flex-wrap gap-2">
            <button
              type="submit"
              disabled={!canSave}
              className={`inline-flex min-h-11 items-center gap-2 rounded-lg bg-emerald-800 px-4 text-sm font-semibold text-white hover:bg-emerald-900 disabled:cursor-wait disabled:opacity-60 ${focusClass}`}
            >
              {saving ? <LoaderCircle size={15} className="motion-safe:animate-spin" aria-hidden="true" /> : null}
              {saving ? 'Saving…' : 'Save available times'}
            </button>
            <button
              type="button"
              disabled={locked || draft.reviewRequired}
              onClick={() => setConfirmClear(true)}
              className={`min-h-11 rounded-lg border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-800 hover:bg-slate-50 disabled:opacity-60 ${focusClass}`}
            >
              Clear saved times
            </button>
            <button
              type="button"
              disabled={locked}
              onClick={() => {
                setConfirmClear(false);
                onDiscard();
              }}
              className={`min-h-11 rounded-lg px-3 text-sm font-semibold text-slate-700 underline underline-offset-2 disabled:opacity-60 ${focusClass}`}
            >
              Discard editing
            </button>
          </div>

          {confirmClear ? (
            <div className="mt-3 rounded-lg border border-amber-200 bg-amber-50 p-3" role="group" aria-label="Confirm clearing available times">
              <p className="text-xs leading-5 text-amber-950">
                Clear the saved window? Scheduling will stop using these times until you add a new window.
              </p>
              <div className="mt-2 flex flex-wrap gap-2">
                <button
                  type="button"
                  disabled={locked || draft.reviewRequired}
                  onClick={() => {
                    setConfirmClear(false);
                    onClear();
                  }}
                  className={`min-h-11 rounded-lg bg-amber-900 px-4 text-sm font-semibold text-white hover:bg-amber-950 disabled:opacity-60 ${focusClass}`}
                >
                  Yes, clear times
                </button>
                <button
                  type="button"
                  disabled={locked}
                  onClick={() => setConfirmClear(false)}
                  className={`min-h-11 rounded-lg border border-amber-300 bg-white px-4 text-sm font-semibold text-amber-950 hover:bg-amber-100 ${focusClass}`}
                >
                  Keep times
                </button>
              </div>
            </div>
          ) : null}
        </form>
      ) : null}
    </div>
  );
};
