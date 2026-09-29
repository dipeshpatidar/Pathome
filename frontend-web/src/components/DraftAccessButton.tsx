import React from 'react';
import { AlertCircle, FileText } from 'lucide-react';

export type DraftAccessState = 'loading' | 'ready' | 'error';

export function DraftAccessButton({
  count,
  state,
  onOpen,
  onRetry,
  dark = false
}: {
  count: number;
  state: DraftAccessState;
  onOpen: () => void;
  onRetry: () => void;
  dark?: boolean;
}) {
  const base = `relative inline-flex h-11 min-w-11 items-center justify-center gap-2 rounded-xl px-2.5 text-xs font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-2 motion-reduce:transition-none ${
    dark ? 'text-white/90 hover:bg-white/10 focus-visible:ring-offset-slate-950' : 'text-slate-700 hover:bg-slate-100'
  }`;

  if (state === 'loading' || state === 'ready' && count < 1) {
    return null;
  }

  const isError = state === 'error';
  return (
    <button
      type="button"
      onClick={isError ? onRetry : onOpen}
      className={base}
      aria-label={isError ? 'Drafts could not be checked. Retry' : `Drafts, ${count} resumable ${count === 1 ? 'draft' : 'drafts'}`}
      title={isError ? 'Retry draft check' : 'Drafts'}
    >
      {isError ? <AlertCircle className="h-4 w-4 text-amber-500" aria-hidden="true" /> : <FileText className="h-4 w-4" aria-hidden="true" />}
      <span className="hidden sm:inline">Drafts</span>
      {!isError && count > 0 && (
        <span className="absolute -right-0.5 -top-0.5 inline-flex min-h-5 min-w-5 items-center justify-center rounded-full border-2 border-white bg-emerald-700 px-1 text-[10px] font-bold leading-none text-white shadow-sm">
          {count > 99 ? '99+' : count}
        </span>
      )}
    </button>
  );
}
