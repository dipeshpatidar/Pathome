import React from 'react';
import { ArrowLeft, RefreshCw } from 'lucide-react';

export function LessorListingErrorState({ error, onRetry, onBack }: {
  error: string;
  onRetry: () => void;
  onBack: () => void;
}) {
  return <section role="alert" className="rounded-xl border border-rose-200 bg-rose-50 p-5 text-rose-800">
    <p className="break-words">{error}</p>
    <div className="mt-4 flex flex-wrap gap-3">
      <button type="button" onClick={onBack}
        className="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-700 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500">
        <ArrowLeft aria-hidden="true" className="h-4 w-4" />Back to My Properties
      </button>
      <button type="button" onClick={onRetry}
        className="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-700 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500">
        <RefreshCw aria-hidden="true" className="h-4 w-4" />Retry
      </button>
    </div>
  </section>;
}
