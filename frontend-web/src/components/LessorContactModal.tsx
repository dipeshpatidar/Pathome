import React, { useEffect, useRef, useState } from 'react';
import { LoaderCircle, Phone, User, ShieldCheck } from 'lucide-react';
import { getErrorMessage } from '../services/apiError';
import { lessorContactService } from '../services/lessorContactService';

interface LessorContactModalProps {
  isOpen: boolean;
  initialFullName?: string;
  initialPhoneNumber?: string;
  onSuccess: () => void;
  onCancel: () => void;
}

const INDIAN_MOBILE = /^(?:\+?91[\-\s]?)?([6-9]\d{9})$/;

export const LessorContactModal: React.FC<LessorContactModalProps> = ({
  isOpen,
  initialFullName = '',
  initialPhoneNumber = '',
  onSuccess,
  onCancel
}) => {
  const [fullName, setFullName] = useState(initialFullName);
  const [phoneNumber, setPhoneNumber] = useState(initialPhoneNumber);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const nameInputRef = useRef<HTMLInputElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const busyRef = useRef(false);

  useEffect(() => {
    if (initialFullName) setFullName(initialFullName);
    if (initialPhoneNumber) setPhoneNumber(initialPhoneNumber);
  }, [initialFullName, initialPhoneNumber]);

  useEffect(() => {
    if (!isOpen) return;
    setError('');
    const prevActive = document.activeElement as HTMLElement | null;
    const originalOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    // Focus input
    setTimeout(() => {
      nameInputRef.current?.focus();
    }, 50);

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !busyRef.current) {
        e.preventDefault();
        onCancel();
        return;
      }
      if (e.key !== 'Tab') return;
      const focusable = dialogRef.current?.querySelectorAll<HTMLElement>(
        'button:not([disabled]),input:not([disabled])'
      );
      if (!focusable?.length) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };

    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.body.style.overflow = originalOverflow;
      document.removeEventListener('keydown', onKeyDown);
      prevActive?.focus?.();
    };
  }, [isOpen, onCancel]);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busyRef.current) return;

    const trimmedName = fullName.trim();
    if (trimmedName.length < 2 || trimmedName.length > 100) {
      setError('Enter your full name between 2 and 100 characters.');
      return;
    }

    const cleanPhone = phoneNumber.trim().replace(/[\s\-()]/g, '');
    const phoneMatch = cleanPhone.match(/^(?:\+?91)?([6-9]\d{9})$/);
    if (!phoneMatch) {
      setError('Enter a valid 10-digit Indian mobile number.');
      return;
    }

    busyRef.current = true;
    setBusy(true);
    setError('');

    try {
      await lessorContactService.updateContact({
        fullName: trimmedName,
        phoneNumber: cleanPhone
      });
      onSuccess();
    } catch (cause) {
      setError(getErrorMessage(cause, 'Could not save your contact details. Please retry.'));
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="contact-modal-title"
      aria-describedby="contact-modal-desc"
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/50 p-4 backdrop-blur-xs"
    >
      <div
        ref={dialogRef}
        className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-2xl transition-all"
      >
        <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-emerald-100 text-emerald-800">
          <Phone className="h-6 w-6" aria-hidden="true" />
        </div>

        <h2 id="contact-modal-title" className="mt-4 font-['Outfit',sans-serif] text-2xl font-bold text-slate-950">
          Almost done
        </h2>
        <p id="contact-modal-desc" className="mt-1 text-sm font-medium text-slate-600">
          How can Pathome reach you?
        </p>

        {error && (
          <div role="alert" className="mt-4 rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-800">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="mt-5 space-y-4">
          <div>
            <label htmlFor="lessor-full-name" className="block text-xs font-semibold uppercase tracking-wider text-slate-700">
              Full name
            </label>
            <div className="relative mt-1.5">
              <User className="absolute left-3 top-3 h-5 w-5 text-slate-400" aria-hidden="true" />
              <input
                ref={nameInputRef}
                id="lessor-full-name"
                type="text"
                required
                disabled={busy}
                placeholder="e.g. Ramesh Sharma"
                value={fullName}
                onChange={e => setFullName(e.target.value)}
                className="min-h-11 w-full rounded-xl border border-slate-300 bg-white pl-10 pr-3 text-base text-slate-900 outline-none focus:border-emerald-600 focus:ring-2 focus:ring-emerald-200 disabled:bg-slate-100 disabled:opacity-70"
              />
            </div>
          </div>

          <div>
            <label htmlFor="lessor-mobile-number" className="block text-xs font-semibold uppercase tracking-wider text-slate-700">
              Mobile number
            </label>
            <div className="relative mt-1.5 flex rounded-xl border border-slate-300 bg-white focus-within:border-emerald-600 focus-within:ring-2 focus-within:ring-emerald-200">
              <span className="inline-flex items-center px-3 text-sm font-medium text-slate-500 border-r border-slate-200 bg-slate-50 rounded-l-xl">
                +91
              </span>
              <input
                id="lessor-mobile-number"
                type="tel"
                required
                disabled={busy}
                placeholder="98260 12345"
                value={phoneNumber}
                onChange={e => setPhoneNumber(e.target.value)}
                className="min-h-11 w-full rounded-r-xl bg-transparent px-3 text-base text-slate-900 outline-none disabled:bg-slate-100 disabled:opacity-70"
              />
            </div>
          </div>

          <div className="flex items-start gap-2 pt-1 text-xs text-slate-500">
            <ShieldCheck className="h-4 w-4 shrink-0 text-emerald-600 mt-0.5" aria-hidden="true" />
            <p>We'll use these details to contact you about your property. They won't be shown publicly.</p>
          </div>

          <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <button
              type="button"
              disabled={busy}
              onClick={onCancel}
              className="inline-flex min-h-11 items-center justify-center rounded-xl border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-700 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={busy}
              className="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl bg-emerald-700 px-5 text-sm font-semibold text-white hover:bg-emerald-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer disabled:opacity-50"
            >
              {busy ? (
                <>
                  <LoaderCircle className="h-4 w-4 animate-spin" />
                  <span>Saving…</span>
                </>
              ) : (
                <span>Continue</span>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
