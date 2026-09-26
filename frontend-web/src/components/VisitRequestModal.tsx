import React, { FormEvent, KeyboardEvent, useEffect, useRef, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { CheckCircle2, Clock3, MapPin, Send, X } from 'lucide-react';
import { Property } from '../types';
import { propertyService } from '../services/propertyService';

interface VisitRequestModalProps {
  property: Property | null;
  isOpen: boolean;
  onClose: () => void;
}

const formatRupees = (value?: number) => value ? `₹${value.toLocaleString('en-IN')}` : 'Not specified';

export const VisitRequestModal: React.FC<VisitRequestModalProps> = ({ property, isOpen, onClose }) => {
  const [budgetMin, setBudgetMin] = useState('');
  const [budgetMax, setBudgetMax] = useState('');
  const [preferredAreas, setPreferredAreas] = useState('');
  const [moveInTiming, setMoveInTiming] = useState('');
  const [preferredVisitTiming, setPreferredVisitTiming] = useState('');
  const [note, setNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [acknowledgement, setAcknowledgement] = useState<string | null>(null);
  const dialogRef = useRef<HTMLElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!property || !isOpen) return;
    setBudgetMin('');
    setBudgetMax(property.monthlyRent > 0 ? String(property.monthlyRent) : '');
    setPreferredAreas([property.sector, property.city].filter(Boolean).join(', '));
    setMoveInTiming('');
    setPreferredVisitTiming('');
    setNote('');
    setError('');
    setAcknowledgement(null);
  }, [property, isOpen]);

  useEffect(() => {
    if (!isOpen) return;
    returnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const focusTimer = window.setTimeout(() => closeButtonRef.current?.focus(), 0);
    return () => {
      window.clearTimeout(focusTimer);
      returnFocusRef.current?.focus();
      returnFocusRef.current = null;
    };
  }, [isOpen]);

  if (!isOpen || !property) return null;

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    if (!preferredVisitTiming.trim()) {
      setError('Please share a preferred date or time window. It is a preference, not a confirmed appointment.');
      return;
    }
    const minimum = budgetMin.trim() ? Number(budgetMin) : undefined;
    const maximum = budgetMax.trim() ? Number(budgetMax) : undefined;
    if ((minimum !== undefined && (!Number.isFinite(minimum) || minimum < 0))
      || (maximum !== undefined && (!Number.isFinite(maximum) || maximum < 0))) {
      setError('Budget values must be valid non-negative numbers.');
      return;
    }
    if (minimum !== undefined && maximum !== undefined && minimum > maximum) {
      setError('Minimum budget cannot exceed maximum budget.');
      return;
    }
    setSubmitting(true);
    try {
      const response = await propertyService.createVisitRequest(property.id, {
        budgetMin: minimum,
        budgetMax: maximum,
        preferredAreas,
        moveInTiming,
        preferredVisitTiming,
        note
      });
      setAcknowledgement(response.message);
    } catch (requestError: any) {
      setError(requestError?.message || 'Unable to send your visit request. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  const handleDialogKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key !== 'Tab' || !dialogRef.current) return;
    const focusable = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(
      'button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [href]'
    )).filter((element) => element.offsetParent !== null);
    if (focusable.length === 0) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  return (
    <AnimatePresence>
      <motion.div
        initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
        className="fixed inset-0 z-[10000] flex items-start justify-center overflow-y-auto bg-slate-950/75 p-3 backdrop-blur-md sm:items-center sm:p-4"
        onClick={onClose}
        onKeyDown={handleDialogKeyDown}
      >
        <motion.section
          role="dialog" aria-modal="true" aria-labelledby="visit-request-title"
          ref={dialogRef}
          initial={{ opacity: 0, y: 20, scale: 0.97 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, y: 20, scale: 0.97 }}
          className="my-auto w-full max-w-xl overflow-hidden rounded-3xl border border-slate-200 bg-white shadow-2xl"
          onClick={(event) => event.stopPropagation()}
        >
          <div className="flex items-start justify-between gap-4 border-b border-slate-100 p-5 sm:p-6">
            <div>
              <p className="text-xs font-bold uppercase tracking-wider text-emerald-700">Assisted visit request</p>
              <h2 id="visit-request-title" className="mt-1 font-['Outfit'] text-xl font-black text-slate-900 sm:text-2xl">Request a Visit</h2>
              <p className="mt-1 text-sm leading-relaxed text-slate-600">Share your preferences. A Visit Session is considered only after availability is checked.</p>
            </div>
            <button ref={closeButtonRef} type="button" onClick={onClose} aria-label="Close visit request" className="rounded-xl p-2 text-slate-500 transition hover:bg-slate-100 hover:text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600">
              <X className="h-5 w-5" />
            </button>
          </div>

          {acknowledgement ? (
            <div className="p-5 sm:p-6">
              <div className="rounded-2xl border border-emerald-200 bg-emerald-50 p-5 text-center">
                <CheckCircle2 className="mx-auto h-11 w-11 text-emerald-600" />
                <h3 className="mt-3 font-['Outfit'] text-lg font-black text-emerald-950">Visit request received</h3>
                <p className="mt-2 text-sm leading-relaxed text-emerald-900">{acknowledgement}</p>
              </div>
              <button type="button" onClick={onClose} className="mt-4 min-h-11 w-full rounded-xl bg-slate-900 px-4 py-3 text-sm font-bold text-white transition hover:bg-slate-800">Done</button>
            </div>
          ) : (
            <form onSubmit={handleSubmit} className="max-h-[calc(100dvh-11rem)] space-y-4 overflow-y-auto p-5 sm:max-h-[calc(100dvh-12rem)] sm:p-6">
              <div className="rounded-2xl border border-slate-200 bg-slate-50 p-4">
                <p className="text-xs font-bold uppercase tracking-wider text-slate-500">Selected property</p>
                <p className="mt-1 font-['Outfit'] text-base font-black text-slate-900">{property.title}</p>
                <p className="mt-1 flex items-center gap-1.5 text-sm text-slate-600"><MapPin className="h-4 w-4 text-emerald-600" />{property.sector}, {property.city}</p>
                <p className="mt-2 text-sm font-bold text-slate-900">{formatRupees(property.monthlyRent)}{property.monthlyRent > 0 ? ' / month' : ''}</p>
              </div>

              <p className="rounded-xl border border-amber-200 bg-amber-50 px-3.5 py-3 text-xs leading-relaxed text-amber-950">
                You are signed in with the current email-based account flow. Mobile OTP verification is not implemented in this phase and no appointment is confirmed here.
              </p>

              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <label className="block text-sm font-semibold text-slate-700">Minimum budget
                  <input inputMode="numeric" value={budgetMin} onChange={(event) => setBudgetMin(event.target.value)} placeholder="Optional" className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-200 bg-white px-3 text-base text-slate-900 outline-none focus:border-emerald-600 focus:ring-2 focus:ring-emerald-100" />
                </label>
                <label className="block text-sm font-semibold text-slate-700">Maximum budget
                  <input inputMode="numeric" value={budgetMax} onChange={(event) => setBudgetMax(event.target.value)} placeholder="Optional" className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-200 bg-white px-3 text-base text-slate-900 outline-none focus:border-emerald-600 focus:ring-2 focus:ring-emerald-100" />
                </label>
              </div>
              <label className="block text-sm font-semibold text-slate-700">Preferred areas
                <input value={preferredAreas} onChange={(event) => setPreferredAreas(event.target.value)} className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-200 bg-white px-3 text-base text-slate-900 outline-none focus:border-emerald-600 focus:ring-2 focus:ring-emerald-100" />
              </label>
              <label className="block text-sm font-semibold text-slate-700">Move-in timing
                <input value={moveInTiming} onChange={(event) => setMoveInTiming(event.target.value)} placeholder="For example, within a month" className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-200 bg-white px-3 text-base text-slate-900 outline-none focus:border-emerald-600 focus:ring-2 focus:ring-emerald-100" />
              </label>
              <label className="block text-sm font-semibold text-slate-700">Preferred visit date or time <span className="text-rose-600">*</span>
                <span className="mt-0.5 block text-xs font-normal text-slate-500">This is a preference, not a confirmed slot.</span>
                <input value={preferredVisitTiming} onChange={(event) => setPreferredVisitTiming(event.target.value)} placeholder="For example, Saturday afternoon" required className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-200 bg-white px-3 text-base text-slate-900 outline-none focus:border-emerald-600 focus:ring-2 focus:ring-emerald-100" />
              </label>
              <label className="block text-sm font-semibold text-slate-700">Additional requirement or note
                <textarea value={note} onChange={(event) => setNote(event.target.value)} rows={3} placeholder="For example, parking or furnishing needs" className="mt-1.5 w-full rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-base text-slate-900 outline-none focus:border-emerald-600 focus:ring-2 focus:ring-emerald-100" />
              </label>
              {error && <p role="alert" className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-800">{error}</p>}
              <button disabled={submitting} type="submit" className="safe-area-bottom flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-emerald-600 px-4 py-3 text-sm font-black text-white shadow-lg shadow-emerald-600/20 transition hover:bg-emerald-700 disabled:cursor-not-allowed disabled:opacity-60">
                {submitting ? <Clock3 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
                {submitting ? 'Sending request…' : 'Send Visit Request'}
              </button>
            </form>
          )}
        </motion.section>
      </motion.div>
    </AnimatePresence>
  );
};
