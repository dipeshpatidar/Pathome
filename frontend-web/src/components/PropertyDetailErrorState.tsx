import React from 'react';
import { ArrowLeft, CircleAlert, RefreshCw } from 'lucide-react';

export function PropertyDetailErrorState({ error, onRetry, onBack }: {
  error: string;
  onRetry: () => void;
  onBack: () => void;
}) {
  return <main className="mx-auto flex min-h-[60vh] max-w-xl items-center px-4">
    <section role="alert" className="w-full rounded-3xl border border-rose-200 bg-white p-6 text-center shadow-sm">
      <CircleAlert aria-hidden="true" className="mx-auto h-10 w-10 text-rose-600" />
      <h1 className="mt-3 font-['Outfit'] text-2xl font-black text-slate-900">Property unavailable</h1>
      <p className="mt-2 break-words text-sm leading-relaxed text-slate-600">{error || 'This property is no longer available.'}</p>
      <div className="mt-5 flex flex-col-reverse justify-center gap-3 sm:flex-row">
        <button type="button" onClick={onBack}
          className="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-3 text-sm font-semibold text-slate-700 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600">
          <ArrowLeft aria-hidden="true" className="h-4 w-4" />Back to discovery
        </button>
        <button type="button" onClick={onRetry}
          className="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl bg-emerald-800 px-4 py-3 text-sm font-bold text-white hover:bg-emerald-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-600 focus-visible:ring-offset-2">
          <RefreshCw aria-hidden="true" className="h-4 w-4" />Retry
        </button>
      </div>
    </section>
  </main>;
}
