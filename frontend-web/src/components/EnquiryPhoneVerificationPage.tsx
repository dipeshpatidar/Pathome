import React, { FormEvent, useEffect, useRef, useState } from 'react';
import { ArrowLeft, CheckCircle2, KeyRound, LoaderCircle, ShieldCheck } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import {
  leadAssistanceService,
  type PhoneConfirmation
} from '../services/leadAssistanceService';

const readFragmentToken = (): string | null => {
  const raw = window.location.hash.startsWith('#') ? window.location.hash.slice(1) : '';
  const token = new URLSearchParams(raw).get('token');
  if (token) {
    window.history.replaceState(
      window.history.state,
      '',
      `${window.location.pathname}${window.location.search}`
    );
  }
  return token;
};

export const EnquiryPhoneVerificationPage: React.FC = () => {
  const navigate = useNavigate();
  const tokenRef = useRef<string | null>(null);
  const [confirmation, setConfirmation] = useState<PhoneConfirmation | null>(null);
  const [code, setCode] = useState('');
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const mounted = useRef(true);
  const controller = useRef<AbortController | null>(null);

  useEffect(() => {
    mounted.current = true;
    const robots = document.createElement('meta');
    robots.name = 'robots';
    robots.content = 'noindex,nofollow,noarchive';
    const referrer = document.createElement('meta');
    referrer.name = 'referrer';
    referrer.content = 'no-referrer';
    document.head.append(robots, referrer);
    tokenRef.current = readFragmentToken();
    if (!tokenRef.current) {
      setLoading(false);
      setError('This confirmation link is incomplete. Open the link from the enquiry SMS or ask Pathome to send a new code.');
      return () => {
        mounted.current = false;
        robots.remove();
        referrer.remove();
      };
    }
    const abort = new AbortController();
    controller.current = abort;
    leadAssistanceService.exchangePhoneToken(tokenRef.current, abort.signal)
      .then(result => {
        if (mounted.current && !abort.signal.aborted) setConfirmation(result);
      })
      .catch(cause => {
        if (mounted.current && !abort.signal.aborted) {
          setError(cause instanceof Error ? cause.message : 'This confirmation link is unavailable.');
        }
      })
      .finally(() => {
        if (mounted.current && !abort.signal.aborted) setLoading(false);
      });
    return () => {
      mounted.current = false;
      abort.abort();
      robots.remove();
      referrer.remove();
    };
  }, []);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting || !tokenRef.current) return;
    if (!/^\d{6}$/.test(code)) {
      setError('Enter the six-digit code from the Pathome SMS.');
      return;
    }
    setSubmitting(true);
    setError('');
    try {
      const result = await leadAssistanceService.confirmPhoneToken(tokenRef.current, code);
      if (!mounted.current) return;
      setConfirmation(result);
      setCode('');
    } catch (cause) {
      if (mounted.current) setError(cause instanceof Error ? cause.message : 'The enquiry code could not be confirmed.');
    } finally {
      if (mounted.current) setSubmitting(false);
    }
  };

  return (
    <main className="flex min-h-[100dvh] items-center justify-center bg-[#f7f5ef] px-4 py-10 text-slate-950">
      <section className="w-full max-w-md rounded-[24px] border border-[#e5e2da] bg-[#fffefa] p-6 shadow-[0_24px_80px_-36px_rgba(15,23,42,.35)] sm:p-8" aria-labelledby="enquiry-phone-title">
        <button type="button" onClick={() => navigate('/')} className="mb-6 inline-flex min-h-11 items-center gap-2 rounded-xl px-2 text-sm font-semibold text-emerald-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-700">
          <ArrowLeft size={16} aria-hidden="true" />Pathome
        </button>
        <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-emerald-50 text-emerald-800">
          {confirmation?.state === 'VERIFIED'
            ? <CheckCircle2 size={24} aria-hidden="true" />
            : <KeyRound size={24} aria-hidden="true" />}
        </div>
        <h1 id="enquiry-phone-title" className="mt-5 font-['Outfit'] text-3xl font-semibold tracking-tight">Confirm your enquiry number.</h1>
        <p className="mt-2 text-sm leading-6 text-slate-600">
          This confirms possession of the number used for this Pathome property enquiry. It does not sign you in,
          verify a Pathome account, or book a visit.
        </p>

        {loading ? (
          <div className="mt-7 flex min-h-32 items-center justify-center gap-2 rounded-2xl bg-white text-sm text-slate-600" role="status">
            <LoaderCircle size={18} className="motion-safe:animate-spin" aria-hidden="true" />Checking this secure link…
          </div>
        ) : confirmation?.state === 'VERIFIED' ? (
          <div className="mt-7 rounded-2xl border border-emerald-200 bg-emerald-50 p-4" role="status">
            <p className="font-semibold text-emerald-950">Enquiry number confirmed</p>
            <p className="mt-1 text-sm leading-6 text-emerald-900">{confirmation.maskedDestination || 'This enquiry number'} is confirmed for this enquiry. This is not a verified Pathome identity.</p>
          </div>
        ) : confirmation ? (
          <form className="mt-7 space-y-4" onSubmit={submit} aria-busy={submitting}>
            <p className="rounded-xl bg-[#f4f2ec] px-3 py-3 text-sm text-slate-700">
              Code sent to <strong className="oe-mono">{confirmation.maskedDestination || 'your enquiry number'}</strong>
            </p>
            <label className="block text-sm font-semibold text-slate-900">
              Six-digit enquiry code
              <input
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                pattern="[0-9]{6}"
                maxLength={6}
                required
                value={code}
                onChange={event => {
                  setCode(event.target.value.replace(/\D/g, '').slice(0, 6));
                  setError('');
                }}
                className="mt-2 min-h-12 w-full rounded-xl border border-slate-300 bg-white px-4 text-center font-['Outfit'] text-xl tracking-[.35em] text-slate-950 outline-none focus:border-emerald-700 focus:ring-2 focus:ring-emerald-100"
              />
            </label>
            {confirmation.state === 'PENDING' ? (
              <p className="text-xs leading-5 text-slate-600">{confirmation.attemptsRemaining} attempts remain. The code expires at {new Intl.DateTimeFormat('en-IN', { timeStyle: 'short' }).format(new Date(confirmation.expiresAt))}.</p>
            ) : <p className="text-sm text-rose-800">{confirmation.message}</p>}
            {error ? <p role="alert" className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-2.5 text-sm text-rose-900">{error}</p> : null}
            <button
              type="submit"
              disabled={submitting || code.length !== 6 || confirmation.state !== 'PENDING'}
              className="flex min-h-12 w-full items-center justify-center rounded-xl bg-emerald-800 px-5 text-sm font-semibold text-white transition hover:bg-emerald-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-700 focus-visible:ring-offset-2 disabled:opacity-55"
            >
              {submitting ? 'Checking…' : 'Confirm enquiry number'}
            </button>
          </form>
        ) : (
          <p role="alert" className="mt-7 rounded-xl border border-rose-200 bg-rose-50 px-3 py-3 text-sm leading-6 text-rose-900">{error}</p>
        )}
        <p className="mt-6 flex items-start gap-2 text-xs leading-5 text-slate-600">
          <ShieldCheck size={15} className="mt-0.5 shrink-0 text-emerald-800" aria-hidden="true" />
          Never share this code in a public message. Pathome never asks for it as an account password.
        </p>
      </section>
    </main>
  );
};
