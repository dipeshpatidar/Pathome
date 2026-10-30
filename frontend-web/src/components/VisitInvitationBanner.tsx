import React, { useEffect, useState } from 'react';
import { CalendarClock, Link2, ShieldCheck } from 'lucide-react';
import {
  leadAssistanceService,
  type VisitHandoff
} from '../services/leadAssistanceService';

interface Props {
  propertyId: number;
}

const consumeFragment = (): string | null => {
  const raw = window.location.hash.startsWith('#') ? window.location.hash.slice(1) : '';
  const params = new URLSearchParams(raw);
  const token = params.get('visit') || params.get('token');
  if (token) {
    window.history.replaceState(
      window.history.state,
      '',
      `${window.location.pathname}${window.location.search}`
    );
  }
  return token;
};

export const VisitInvitationBanner: React.FC<Props> = ({ propertyId }) => {
  const [context, setContext] = useState<VisitHandoff | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const abort = new AbortController();
    const token = consumeFragment();
    const load = async () => {
      try {
        const result = token
          ? await leadAssistanceService.exchangeVisitToken(token, abort.signal)
          : await leadAssistanceService.currentVisitContext(abort.signal);
        if (abort.signal.aborted) return;
        if (result && result.property.id !== propertyId) {
          setError('This invitation belongs to a different property. Open the exact property in the invitation.');
          return;
        }
        setContext(result);
      } catch (cause) {
        if (!abort.signal.aborted && token) {
          setError(cause instanceof Error ? cause.message : 'This Visit invitation is no longer available.');
        }
      } finally {
        if (!abort.signal.aborted) setLoading(false);
      }
    };
    void load();
    return () => abort.abort();
  }, [propertyId]);

  if (loading) {
    return (
      <div className="mx-auto mb-4 max-w-5xl px-4" aria-busy="true">
        <div className="min-h-16 rounded-2xl border border-emerald-100 bg-emerald-50/70" />
      </div>
    );
  }
  if (error) {
    return (
      <div className="mx-auto mb-4 max-w-5xl px-4">
        <p role="alert" className="rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-950">{error}</p>
      </div>
    );
  }
  if (!context) return null;
  return (
    <aside className="mx-auto mb-4 max-w-5xl px-4">
      <div className="flex flex-col gap-3 rounded-2xl border border-emerald-200 bg-[#eef5ec] px-4 py-3.5 text-emerald-950 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex min-w-0 items-start gap-3">
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-white text-emerald-800 shadow-sm">
            <Link2 size={19} aria-hidden="true" />
          </span>
          <div className="min-w-0">
            <p className="font-['Outfit'] text-sm font-semibold">This is an exact-property Visit invitation</p>
            <p className="mt-0.5 text-xs leading-5 text-emerald-900">
              Sign in only when you choose Request a Visit. Nothing is requested or booked automatically.
            </p>
          </div>
        </div>
        <div className="flex flex-wrap gap-3 text-xs text-emerald-900">
          <span className="inline-flex items-center gap-1.5"><CalendarClock size={14} aria-hidden="true" />Invitation until {new Intl.DateTimeFormat('en-IN', { timeStyle: 'short', dateStyle: 'medium' }).format(new Date(context.expiresAt))}</span>
          <span className="inline-flex items-center gap-1.5"><ShieldCheck size={14} aria-hidden="true" />Account-specific Request</span>
        </div>
      </div>
    </aside>
  );
};
