import React, { FormEvent, KeyboardEvent, useEffect, useRef, useState } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { ArrowRight, CalendarDays, CheckCircle2, Clock3, MapPin, Send, X } from 'lucide-react';
import { Property } from '../types';
import { propertyService } from '../services/propertyService';
import { isCurrentTenantVisitSession, readTenantVisitSession } from '../utils/tenantVisitSession';

interface VisitRequestModalProps {
  property: Property | null;
  isOpen: boolean;
  tenantUserId: number;
  onClose: () => void;
  onViewMyVisits: () => void;
  onKeepBrowsing: () => void;
}

type TimeWindow = 'Morning' | 'Afternoon' | 'Evening';

const TIME_WINDOWS: Array<{ value: TimeWindow; hours: string }> = [
  { value: 'Morning', hours: '10 AM–1 PM' },
  { value: 'Afternoon', hours: '1–4 PM' },
  { value: 'Evening', hours: '4–7 PM' }
];

const dateInputValue = (date: Date): string => {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
};

const dateFromInput = (value: string): Date | null => {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return null;
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return date.getFullYear() === Number(match[1]) && date.getMonth() === Number(match[2]) - 1
    && date.getDate() === Number(match[3]) ? date : null;
};

const formatRequestDate = (value: string): string => {
  const date = dateFromInput(value);
  return date ? new Intl.DateTimeFormat('en-IN', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' }).format(date) : '';
};

const formatRupees = (value?: number) => value && value > 0 ? `₹${value.toLocaleString('en-IN')}` : 'On request';

export const VisitRequestModal: React.FC<VisitRequestModalProps> = ({
  property, isOpen, tenantUserId, onClose, onViewMyVisits, onKeepBrowsing
}) => {
  const [budgetMin, setBudgetMin] = useState('');
  const [budgetMax, setBudgetMax] = useState('');
  const [preferredAreas, setPreferredAreas] = useState('');
  const [moveInTiming, setMoveInTiming] = useState('');
  const [preferredDate, setPreferredDate] = useState('');
  const [preferredWindow, setPreferredWindow] = useState<TimeWindow | ''>('');
  const [note, setNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [acknowledgement, setAcknowledgement] = useState(false);
  const dialogRef = useRef<HTMLElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);
  const inFlightRef = useRef(false);
  const reduceMotion = useReducedMotion();
  const minDate = dateInputValue(new Date(new Date().getFullYear(), new Date().getMonth(), new Date().getDate() + 1));

  useEffect(() => {
    if (!property || !isOpen) return;
    setBudgetMin('');
    setBudgetMax(property.monthlyRent > 0 ? String(property.monthlyRent) : '');
    setPreferredAreas([property.sector, property.city].filter(Boolean).join(', '));
    setMoveInTiming('');
    setPreferredDate('');
    setPreferredWindow('');
    setNote('');
    setError('');
    setAcknowledgement(false);
    inFlightRef.current = false;
  }, [property, isOpen]);

  useEffect(() => {
    if (!isOpen) return undefined;
    returnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const focusTimer = window.setTimeout(() => closeButtonRef.current?.focus(), 0);
    return () => {
      window.clearTimeout(focusTimer);
      document.body.style.overflow = previousOverflow;
      returnFocusRef.current?.focus();
      returnFocusRef.current = null;
    };
  }, [isOpen]);

  if (!isOpen || !property) return null;

  const closeIfIdle = () => {
    if (!inFlightRef.current) onClose();
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (inFlightRef.current || !property) return;
    setError('');
    const selectedWindow = TIME_WINDOWS.find(item => item.value === preferredWindow);
    const selectedDate = formatRequestDate(preferredDate);
    const tomorrow = new Date();
    tomorrow.setHours(0, 0, 0, 0);
    tomorrow.setDate(tomorrow.getDate() + 1);
    if (!selectedDate || preferredDate < dateInputValue(tomorrow) || !selectedWindow) {
      setError('Choose a preferred date starting tomorrow and a time window to continue.');
      return;
    }
    const minimum = budgetMin.trim() ? Number(budgetMin) : undefined;
    const maximum = budgetMax.trim() ? Number(budgetMax) : undefined;
    if ((minimum !== undefined && (!Number.isFinite(minimum) || minimum < 0))
      || (maximum !== undefined && (!Number.isFinite(maximum) || maximum < 0))) {
      setError('Enter a valid non-negative budget, or leave it blank.');
      return;
    }
    if (minimum !== undefined && maximum !== undefined && minimum > maximum) {
      setError('Minimum budget cannot exceed maximum budget.');
      return;
    }
    const requestSession = readTenantVisitSession(tenantUserId);
    if (!requestSession) {
      setError('Your sign-in session changed. Please sign in again to send this request.');
      return;
    }

    inFlightRef.current = true;
    setSubmitting(true);
    try {
      await propertyService.createVisitRequest(property.id, {
        budgetMin: minimum,
        budgetMax: maximum,
        preferredAreas,
        moveInTiming,
        preferredVisitTiming: `${selectedDate} · ${selectedWindow.value} (${selectedWindow.hours})`,
        note: note.trim() || undefined
      });
      if (!isCurrentTenantVisitSession(requestSession)) return;
      setAcknowledgement(true);
      window.dispatchEvent(new Event('pathome_visit_request_created'));
    } catch {
      if (isCurrentTenantVisitSession(requestSession)) {
        setError('We couldn’t send your visit request. Please try again.');
        inFlightRef.current = false;
      }
    } finally {
      if (isCurrentTenantVisitSession(requestSession)) setSubmitting(false);
    }
  };

  const handleDialogKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeIfIdle();
      return;
    }
    if (event.key !== 'Tab' || !dialogRef.current) return;
    const focusable = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(
      'button:not([disabled]), input:not([disabled]), textarea:not([disabled]), summary, [href], [tabindex]:not([tabindex="-1"])'
    )).filter(element => element.offsetParent !== null);
    if (!focusable.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && (document.activeElement === first || !dialogRef.current.contains(document.activeElement))) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && (document.activeElement === last || !dialogRef.current.contains(document.activeElement))) {
      event.preventDefault();
      first.focus();
    }
  };

  const chooseQuickDate = (offset: number) => {
    const date = new Date();
    date.setHours(0, 0, 0, 0);
    date.setDate(date.getDate() + offset);
    setPreferredDate(dateInputValue(date));
  };

  return (
    <AnimatePresence>
      <motion.div
        initial={reduceMotion ? { opacity: 0 } : { opacity: 0 }}
        animate={{ opacity: 1 }} exit={{ opacity: 0 }}
        className="fixed inset-0 z-[10000] flex items-end justify-center overflow-y-auto bg-slate-950/55 px-0 pt-6 backdrop-blur-sm sm:items-center sm:p-4"
        onClick={closeIfIdle}
        onKeyDown={handleDialogKeyDown}
      >
        <motion.section
          role="dialog" aria-modal="true" aria-labelledby="visit-request-title" aria-describedby="visit-request-description"
          ref={dialogRef} tabIndex={-1}
          initial={reduceMotion ? { opacity: 0 } : { opacity: 0, y: 28 }}
          animate={{ opacity: 1, y: 0 }} exit={reduceMotion ? { opacity: 0 } : { opacity: 0, y: 24 }}
          transition={reduceMotion ? { duration: 0 } : { duration: 0.24, ease: [0.16, 1, 0.3, 1] }}
          className="my-0 flex max-h-[94dvh] w-full max-w-xl flex-col overflow-hidden rounded-t-[28px] bg-[#fffefa] shadow-[0_24px_80px_-24px_rgba(15,23,42,.45)] sm:my-auto sm:max-h-[min(90dvh,52rem)] sm:rounded-[26px]"
          onClick={event => event.stopPropagation()}
        >
          <div className="shrink-0 border-b border-[#e7e8df] px-5 pb-4 pt-3 sm:px-7 sm:pt-6">
            <div className="mx-auto mb-3 h-1.5 w-10 rounded-full bg-slate-300 sm:hidden" aria-hidden="true" />
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0">
                <p className="text-[11px] font-bold uppercase tracking-[0.16em] text-emerald-800">A guided visit, arranged around you</p>
                <h2 id="visit-request-title" className="mt-1 font-['Outfit'] text-2xl font-semibold tracking-tight text-slate-950">Request a visit</h2>
                <p id="visit-request-description" className="mt-1 max-w-md text-sm leading-5 text-slate-600">Pathome coordinates with the property owner before confirming your guided visit.</p>
              </div>
              <button ref={closeButtonRef} type="button" onClick={closeIfIdle} disabled={submitting}
                aria-label="Close visit request" className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-slate-600 transition hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-700 disabled:opacity-50">
                <X size={19} aria-hidden="true" />
              </button>
            </div>
          </div>

          {acknowledgement ? (
            <div className="overflow-y-auto p-5 sm:p-7" aria-live="polite">
              <div className="rounded-[22px] bg-[#eef5ec] p-5 sm:p-6">
                <span className="flex h-12 w-12 items-center justify-center rounded-2xl bg-white text-emerald-800 shadow-sm"><CheckCircle2 size={25} aria-hidden="true" /></span>
                <h3 className="mt-4 font-['Outfit'] text-xl font-semibold text-slate-950">Visit request received</h3>
                <p className="mt-2 text-sm leading-6 text-slate-700">We’ll coordinate with the property owner and confirm your visit once availability is verified.</p>
                <p className="mt-3 text-xs leading-5 text-slate-600">Your preferred date and time are requests, not a confirmed appointment.</p>
              </div>
              <div className="mt-5 grid gap-2 sm:grid-cols-2">
                <button type="button" onClick={onViewMyVisits} className="inline-flex min-h-12 items-center justify-center gap-2 rounded-xl bg-emerald-800 px-5 text-sm font-semibold text-white transition hover:bg-emerald-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-700 focus-visible:ring-offset-2">
                  View My Visits <ArrowRight size={16} aria-hidden="true" />
                </button>
                <button type="button" onClick={onKeepBrowsing} className="min-h-12 rounded-xl border border-slate-300 bg-white px-5 text-sm font-semibold text-slate-800 transition hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-700 focus-visible:ring-offset-2">Keep Browsing</button>
              </div>
            </div>
          ) : (
            <form onSubmit={handleSubmit} className="flex min-h-0 flex-1 flex-col" aria-busy={submitting}>
              <div className="min-h-0 flex-1 space-y-5 overflow-y-auto overscroll-contain px-5 py-4 sm:px-7 sm:py-5">
                <div className="flex min-w-0 items-center gap-3 rounded-2xl bg-[#f4f3ed] p-3">
                  {property.images?.[0] && <img src={property.images[0]} alt="" loading="lazy" decoding="async" className="h-16 w-20 shrink-0 rounded-xl object-cover sm:h-[4.5rem] sm:w-24" />}
                  <div className="min-w-0">
                    <h3 className="truncate font-['Outfit'] text-sm font-semibold text-slate-950">{property.title || 'Selected property'}</h3>
                    <p className="mt-1 flex min-w-0 items-center gap-1.5 text-xs text-slate-600"><MapPin size={14} className="shrink-0 text-emerald-800" aria-hidden="true" /><span className="truncate">{[property.sector, property.city].filter(Boolean).join(', ') || 'Locality details unavailable'}</span></p>
                    <p className="mt-1 text-sm font-semibold text-slate-900">{formatRupees(property.monthlyRent)}{property.monthlyRent > 0 ? ' / month' : ''}</p>
                  </div>
                </div>

                <p className="text-sm leading-5 text-slate-600">Tell us when you’d prefer to visit. We’ll confirm availability before scheduling.</p>

                <fieldset>
                  <legend className="text-sm font-semibold text-slate-900">Preferred date <span className="text-rose-700">*</span></legend>
                  <div className="mt-2 flex flex-wrap gap-2">
                    {[1, 2, 3].map(offset => {
                      const date = new Date();
                      date.setHours(0, 0, 0, 0);
                      date.setDate(date.getDate() + offset);
                      const value = dateInputValue(date);
                      const formattedDate = new Intl.DateTimeFormat('en-IN', { weekday: 'short', day: 'numeric', month: 'short' }).format(date);
                      const label = offset === 1 ? `Tomorrow · ${formattedDate}` : formattedDate;
                      return <button key={value} type="button" onClick={() => setPreferredDate(value)} aria-pressed={preferredDate === value}
                        className={`min-h-11 rounded-xl border px-3 text-xs font-semibold transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-700 ${preferredDate === value ? 'border-emerald-800 bg-[#eaf2e9] text-emerald-950' : 'border-slate-300 bg-white text-slate-700 hover:border-emerald-700'}`}>{label}</button>;
                    })}
                    <label className="flex min-h-11 items-center gap-2 rounded-xl border border-slate-300 bg-white px-3 text-xs font-semibold text-slate-700 focus-within:ring-2 focus-within:ring-emerald-700">
                      <CalendarDays size={15} className="shrink-0 text-emerald-800" aria-hidden="true" />
                      <span className="sr-only">Choose another preferred date</span>
                      <input type="date" value={preferredDate} min={minDate} onChange={event => setPreferredDate(event.target.value)} required
                        className="min-w-0 bg-transparent text-base text-slate-900 outline-none" />
                    </label>
                  </div>
                  {preferredDate && <p className="mt-2 text-xs text-slate-600">Selected: {formatRequestDate(preferredDate)}</p>}
                </fieldset>

                <fieldset>
                  <legend className="text-sm font-semibold text-slate-900">Preferred time window <span className="text-rose-700">*</span></legend>
                  <div className="mt-2 grid grid-cols-1 gap-2 sm:grid-cols-3">
                    {TIME_WINDOWS.map(window => <label key={window.value} className={`flex min-h-12 cursor-pointer items-center gap-2 rounded-xl border px-3 py-2.5 transition focus-within:ring-2 focus-within:ring-emerald-700 ${preferredWindow === window.value ? 'border-emerald-800 bg-[#eaf2e9]' : 'border-slate-300 bg-white hover:border-emerald-700'}`}>
                      <input type="radio" name="preferredVisitWindow" value={window.value} checked={preferredWindow === window.value}
                        onChange={() => setPreferredWindow(window.value)} required className="h-4 w-4 accent-emerald-800" />
                      <span className="min-w-0"><span className="block text-sm font-semibold text-slate-900">{window.value}</span><span className="block text-xs text-slate-600">{window.hours}</span></span>
                    </label>)}
                  </div>
                  <p className="mt-2 text-xs leading-5 text-slate-600">These are preferences, not available or confirmed appointment slots.</p>
                </fieldset>

                <label className="block text-sm font-semibold text-slate-900">Anything we should know? <span className="font-normal text-slate-500">Optional</span>
                  <textarea value={note} onChange={event => setNote(event.target.value)} rows={2} maxLength={500}
                    placeholder="Accessibility needs, meeting instructions, or a question about the home"
                    className="mt-2 min-h-[5rem] w-full resize-y rounded-xl border border-slate-300 bg-white px-3 py-2.5 text-base font-normal text-slate-900 outline-none placeholder:text-slate-400 focus:border-emerald-700 focus:ring-2 focus:ring-emerald-100" />
                </label>

                <details className="rounded-xl border border-slate-200 bg-white px-3.5">
                  <summary className="flex min-h-11 cursor-pointer items-center text-sm font-semibold text-slate-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-700">Add more preferences</summary>
                  <div className="space-y-3 pb-4">
                    <div className="grid gap-3 sm:grid-cols-2">
                      <label className="block text-xs font-semibold text-slate-700">Minimum budget
                        <input type="number" inputMode="numeric" min="0" step="1" value={budgetMin} onChange={event => setBudgetMin(event.target.value)} placeholder="Optional" className="mt-1.5 min-h-11 w-full rounded-lg border border-slate-300 px-3 text-base font-normal text-slate-900 outline-none focus:border-emerald-700 focus:ring-2 focus:ring-emerald-100" />
                      </label>
                      <label className="block text-xs font-semibold text-slate-700">Maximum budget
                        <input type="number" inputMode="numeric" min="0" step="1" value={budgetMax} onChange={event => setBudgetMax(event.target.value)} placeholder="Optional" className="mt-1.5 min-h-11 w-full rounded-lg border border-slate-300 px-3 text-base font-normal text-slate-900 outline-none focus:border-emerald-700 focus:ring-2 focus:ring-emerald-100" />
                      </label>
                    </div>
                    <label className="block text-xs font-semibold text-slate-700">Preferred areas
                      <input value={preferredAreas} onChange={event => setPreferredAreas(event.target.value)} className="mt-1.5 min-h-11 w-full rounded-lg border border-slate-300 px-3 text-base font-normal text-slate-900 outline-none focus:border-emerald-700 focus:ring-2 focus:ring-emerald-100" />
                    </label>
                    <label className="block text-xs font-semibold text-slate-700">Move-in timing
                      <input value={moveInTiming} onChange={event => setMoveInTiming(event.target.value)} placeholder="For example, within a month" className="mt-1.5 min-h-11 w-full rounded-lg border border-slate-300 px-3 text-base font-normal text-slate-900 outline-none focus:border-emerald-700 focus:ring-2 focus:ring-emerald-100" />
                    </label>
                  </div>
                </details>
              </div>
              <div className="shrink-0 border-t border-[#e7e8df] bg-[#fffefa]/95 px-5 pb-[calc(.9rem+env(safe-area-inset-bottom))] pt-3 sm:px-7 sm:pb-5">
                {error && <p role="alert" className="mb-2 rounded-xl border border-rose-200 bg-rose-50 px-3 py-2.5 text-sm leading-5 text-rose-900">{error}</p>}
                <button disabled={submitting || !preferredDate || !preferredWindow} type="submit" aria-busy={submitting}
                  className="flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-emerald-800 px-4 py-3 text-sm font-semibold text-white shadow-[0_8px_20px_-10px_rgba(6,95,70,.6)] transition hover:bg-emerald-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-700 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-55">
                  {submitting ? <><Clock3 size={16} className="motion-safe:animate-spin" aria-hidden="true" />Sending request…</> : <><Send size={16} aria-hidden="true" />Send visit request</>}
                </button>
                <p className="mt-2 text-center text-[11px] leading-4 text-slate-500">Submitting does not reserve or confirm a visit.</p>
              </div>
            </form>
          )}
        </motion.section>
      </motion.div>
    </AnimatePresence>
  );
};
