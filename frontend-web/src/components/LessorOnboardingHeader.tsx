import React, { useState, useRef, useEffect } from 'react';
import {
  Building2,
  Check,
  LoaderCircle,
  RefreshCw,
  LogIn,
  AlertCircle,
  ArrowUpRight,
  Trash2,
  MoreHorizontal
} from 'lucide-react';
import type { LessorSaveStatus } from '../services/lessorAutosave';
import { DraftAccessButton, DraftAccessState } from './DraftAccessButton';

export interface LessorOnboardingHeaderProps {
  status?: LessorSaveStatus | null;
  guest?: boolean;
  hasActiveUploads?: boolean;
  onExit: () => Promise<boolean | void> | boolean | void;
  onExitToLanding: () => void;
  onRetrySave?: () => void;
  onRequestAuth?: () => void;
  currentStepLabel?: string;
  currentStepNumber?: number;
  draftCount: number;
  draftState: DraftAccessState;
  onOpenDrafts: () => void;
  onRetryDrafts: () => void;
  canDiscard?: boolean;
  isRevision?: boolean;
  hasServerDraft?: boolean;
  onDiscard?: () => Promise<void> | void;
}

export const LessorOnboardingHeader: React.FC<LessorOnboardingHeaderProps> = ({
  status,
  guest = false,
  hasActiveUploads = false,
  onExit,
  onExitToLanding,
  onRetrySave,
  onRequestAuth,
  currentStepLabel,
  currentStepNumber,
  draftCount,
  draftState,
  onOpenDrafts,
  onRetryDrafts,
  canDiscard = false,
  isRevision = false,
  hasServerDraft = true,
  onDiscard
}) => {
  const [showExitConfirm, setShowExitConfirm] = useState(false);
  const [showUploadsConfirm, setShowUploadsConfirm] = useState(false);
  const [pendingExitAction, setPendingExitAction] = useState<'exit' | 'landing' | 'drafts'>('exit');
  const [isFlushing, setIsFlushing] = useState(false);
  const [showDiscardConfirm, setShowDiscardConfirm] = useState(false);
  const [showMoreActions, setShowMoreActions] = useState(false);
  const [isDiscarding, setIsDiscarding] = useState(false);
  const [discardError, setDiscardError] = useState<string | null>(null);

  const keepEditingBtnRef = useRef<HTMLButtonElement>(null);
  const discardTriggerRef = useRef<HTMLButtonElement>(null);
  const moreTriggerRef = useRef<HTMLButtonElement>(null);
  const moreActionsRef = useRef<HTMLDivElement>(null);
  const discardDialogRef = useRef<HTMLDivElement>(null);
  const exitDialogRef = useRef<HTMLDivElement>(null);
  const uploadsDialogRef = useRef<HTMLDivElement>(null);
  const discardInFlight = useRef(false);
  const restoreDiscardFocus = () => {
    const mobileTrigger = moreTriggerRef.current;
    if (mobileTrigger && mobileTrigger.offsetParent !== null) mobileTrigger.focus();
    else discardTriggerRef.current?.focus();
  };

  useEffect(() => {
    if (!showMoreActions) return;
    const closeOutside = (event: PointerEvent) => {
      if (!moreActionsRef.current?.contains(event.target as Node)) setShowMoreActions(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setShowMoreActions(false);
        moreTriggerRef.current?.focus();
      }
    };
    document.addEventListener('pointerdown', closeOutside);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('pointerdown', closeOutside);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [showMoreActions]);

  // Focus safe button and listen for Escape when Discard modal opens
  useEffect(() => {
    if (!showDiscardConfirm) return;
    if (!isDiscarding) keepEditingBtnRef.current?.focus();
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !isDiscarding) {
        setShowDiscardConfirm(false);
        setDiscardError(null);
        restoreDiscardFocus();
      }
      if (e.key === 'Tab') {
        const buttons = discardDialogRef.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)');
        if (!buttons?.length) {
          e.preventDefault();
          return;
        }
        const first = buttons[0];
        const last = buttons[buttons.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener('keydown', handleKey);
    return () => {
      document.removeEventListener('keydown', handleKey);
    };
  }, [showDiscardConfirm, isDiscarding]);

  useEffect(() => {
    const dialog = showUploadsConfirm ? uploadsDialogRef.current : showExitConfirm ? exitDialogRef.current : null;
    if (!dialog) return;
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const focusable = () => dialog.querySelectorAll<HTMLButtonElement>('button:not(:disabled)');
    focusable()[0]?.focus();
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !isFlushing) {
        setShowUploadsConfirm(false);
        setShowExitConfirm(false);
        return;
      }
      if (event.key === 'Tab') {
        const buttons = focusable();
        if (!buttons.length) {
          event.preventDefault();
        } else if (event.shiftKey && document.activeElement === buttons[0]) {
          event.preventDefault();
          buttons[buttons.length - 1].focus();
        } else if (!event.shiftKey && document.activeElement === buttons[buttons.length - 1]) {
          event.preventDefault();
          buttons[0].focus();
        }
      }
    };
    document.addEventListener('keydown', handleKey);
    return () => {
      document.removeEventListener('keydown', handleKey);
      if (trigger?.isConnected) trigger.focus();
    };
  }, [showUploadsConfirm, showExitConfirm, isFlushing]);

  const finishNavigation = (action: 'exit' | 'landing' | 'drafts') => {
    if (action === 'drafts') onOpenDrafts();
    else onExitToLanding();
  };

  const handleAction = async (action: 'exit' | 'landing' | 'drafts') => {
    setPendingExitAction(action);
    if (hasActiveUploads) {
      setShowUploadsConfirm(true);
      return;
    }
    // If status is already in error or conflict, show recovery dialog directly
    if (status === 'error' || status === 'conflict') {
      setShowExitConfirm(true);
      return;
    }

    setIsFlushing(true);
    try {
      const ok = await onExit();
      if (ok === false) {
        setShowExitConfirm(true);
        return;
      }
      finishNavigation(action);
    } catch {
      setShowExitConfirm(true);
    } finally {
      setIsFlushing(false);
    }
  };

  const handleConfirmExit = () => {
    setShowExitConfirm(false);
    finishNavigation(pendingExitAction);
  };

  const handleRetryAndExit = async () => {
    setIsFlushing(true);
    try {
      if (onRetrySave) {
        onRetrySave();
      }
      const ok = await onExit();
      if (ok !== false) {
        setShowExitConfirm(false);
        finishNavigation(pendingExitAction);
      }
    } finally {
      setIsFlushing(false);
    }
  };

  const handleTriggerDiscard = () => {
    setShowMoreActions(false);
    if (!hasServerDraft) {
      void onDiscard?.();
    } else {
      setDiscardError(null);
      setShowDiscardConfirm(true);
    }
  };

  const handleConfirmDiscard = async () => {
    if (discardInFlight.current) return;
    discardInFlight.current = true;
    setIsDiscarding(true);
    setDiscardError(null);
    try {
      await onDiscard?.();
      setShowDiscardConfirm(false);
    } catch {
      setDiscardError(isRevision
        ? "Couldn't confirm the discard. Check your property before trying again."
        : "Couldn't confirm the discard. Check your drafts before trying again.");
    } finally {
      discardInFlight.current = false;
      setIsDiscarding(false);
    }
  };

  return (
    <>
      <header data-pathome-header="onboarding" className="sticky top-0 z-40 w-full border-b border-slate-200/90 bg-white/95 pt-[env(safe-area-inset-top)] shadow-xs backdrop-blur-xl">
        <div className="mx-auto grid min-h-16 max-w-6xl grid-cols-[minmax(0,1fr)_auto] items-center gap-x-2 gap-y-1 px-3 py-2 sm:flex sm:gap-4 sm:px-6 sm:py-2">
          <button
            type="button"
            onClick={() => void handleAction('landing')}
            className="flex min-w-0 items-center gap-2 rounded-xl transition-opacity hover:opacity-85 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer"
            title="Return to your previous page"
            aria-label="Return to your previous page"
          >
            <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-emerald-700 text-white shadow-sm shadow-emerald-700/20">
              <Building2 className="h-5 w-5" aria-hidden="true" />
            </span>
            <span className="flex min-w-0 items-center gap-2 text-left">
              <span className="shrink-0 font-['Outfit',sans-serif] text-base font-black tracking-tight text-slate-950 sm:text-lg">
                Path<span className="text-emerald-600">ome</span>
              </span>
              <span className="hidden truncate text-[11px] font-semibold text-slate-500 lg:inline">Property onboarding</span>
            </span>
          </button>

          {currentStepLabel && (
            <div className="flex min-w-0 items-center justify-end gap-1.5 text-[11px] font-semibold text-slate-600 sm:flex-1 sm:justify-center sm:text-xs">
              <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-emerald-600" aria-hidden="true" />
              <span className="max-w-24 truncate sm:max-w-none">{currentStepLabel}</span>
              {currentStepNumber && <><span className="shrink-0 text-slate-400 lg:hidden">· {currentStepNumber}/7</span><span className="hidden shrink-0 text-slate-400 lg:inline">· Step {currentStepNumber} of 7</span></>}
            </div>
          )}

          <div className="col-span-2 flex min-w-0 items-center justify-end gap-1 sm:col-span-1 sm:w-auto sm:shrink-0 sm:gap-2">
            <div className="flex min-h-6 min-w-6 items-center text-xs" aria-live="polite">
              {isFlushing || status === 'saving' ? (
                <span className="flex items-center gap-1.5 font-medium text-slate-500">
                  <LoaderCircle className="h-3.5 w-3.5 animate-spin text-slate-400 motion-reduce:animate-none" />
                  <span className="hidden min-[400px]:inline">Saving…</span>
                </span>
              ) : status === 'saved' ? (
                <span className="flex items-center gap-1 font-medium text-emerald-700">
                  <Check className="h-3.5 w-3.5 stroke-[2.5]" aria-hidden="true" />
                  <span className="hidden min-[400px]:inline">Saved</span>
                </span>
              ) : status === 'error' ? (
                <button type="button" onClick={onRetrySave} aria-label="Saving failed. Retry" title="Couldn't save changes. Retry."
                  className="flex min-h-11 items-center gap-1 font-semibold text-rose-700 hover:text-rose-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-500">
                  <RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />
                  <span className="hidden min-[480px]:inline">Retry save</span>
                </button>
              ) : status === 'conflict' ? (
                <span role="status" className="flex items-center gap-1 font-semibold text-amber-700" title="Sync conflict from another session">
                  <AlertCircle className="h-3.5 w-3.5" aria-hidden="true" />
                  <span className="hidden min-[480px]:inline">Needs attention</span>
                </span>
              ) : null}
            </div>

            <DraftAccessButton count={draftCount} state={draftState} onOpen={() => void handleAction('drafts')} onRetry={onRetryDrafts} />

            {guest && onRequestAuth && (
              <button type="button" onClick={onRequestAuth} aria-label="Sign in to save across devices"
                className="inline-flex h-11 min-w-11 items-center justify-center gap-1.5 rounded-xl px-2 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-100 hover:text-emerald-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer">
                <LogIn className="h-3.5 w-3.5 text-slate-500" aria-hidden="true" />
                <span>Sign in</span>
              </button>
            )}

            <button type="button" disabled={isFlushing} onClick={() => void handleAction('exit')}
              className="inline-flex min-h-11 items-center justify-center gap-1 rounded-xl border border-slate-300 bg-white px-2.5 text-xs font-semibold text-slate-700 shadow-2xs transition-colors motion-reduce:transition-none hover:border-slate-400 hover:bg-slate-50 active:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 disabled:opacity-50 sm:px-3 sm:text-sm">
              <span>Exit</span><ArrowUpRight className="hidden h-3.5 w-3.5 text-slate-500 sm:inline" aria-hidden="true" />
            </button>

            {canDiscard && <>
              <div ref={moreActionsRef} className="relative lg:hidden">
                <button ref={moreTriggerRef} type="button" aria-label="More draft actions" aria-expanded={showMoreActions}
                  disabled={isDiscarding} onClick={() => setShowMoreActions(value => !value)}
                  className="inline-flex h-11 w-11 items-center justify-center rounded-xl border border-slate-300 bg-white text-slate-700 transition-colors hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 disabled:opacity-50">
                  <MoreHorizontal className="h-5 w-5" aria-hidden="true" />
                </button>
                {showMoreActions && <div className="absolute right-0 top-full z-50 mt-2 min-w-40 rounded-xl border border-slate-200 bg-white p-1 shadow-lg">
                  <button type="button" onClick={handleTriggerDiscard}
                    className="flex min-h-11 w-full items-center gap-2 rounded-lg px-3 text-left text-sm font-semibold text-rose-700 hover:bg-rose-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-500">
                    <Trash2 className="h-4 w-4" aria-hidden="true" />{isRevision ? 'Discard changes' : 'Discard draft'}
                  </button>
                </div>}
              </div>
              <button ref={discardTriggerRef} type="button" disabled={isDiscarding} onClick={handleTriggerDiscard}
                className="hidden h-11 items-center justify-center gap-2 rounded-xl border border-rose-200 bg-white px-3 text-xs font-semibold text-rose-700 transition-colors hover:border-rose-300 hover:bg-rose-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-500 disabled:opacity-50 lg:inline-flex">
                <Trash2 className="h-4 w-4" aria-hidden="true" />{isRevision ? 'Discard changes' : 'Discard draft'}
              </button>
            </>}
          </div>
        </div>
        {currentStepNumber && <div className="px-3 pb-2 md:hidden" aria-hidden="true">
          <div className="h-1 w-full overflow-hidden rounded-full bg-slate-200"><div className="h-full bg-emerald-600" style={{ width: `${Math.round(currentStepNumber / 7 * 100)}%` }} /></div>
        </div>}
      </header>

      {/* ACTIVE UPLOADS CONFIRMATION DIALOG */}
      {showUploadsConfirm && (
        <div
          ref={uploadsDialogRef}
          role="dialog"
          aria-modal="true"
          aria-labelledby="confirm-uploads-title"
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/50 p-4 backdrop-blur-xs"
        >
          <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-2xl">
            <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-amber-100 text-amber-800">
              <AlertCircle className="h-6 w-6" />
            </div>
            <h2 id="confirm-uploads-title" className="mt-4 font-['Outfit',sans-serif] text-xl font-bold text-slate-950">
              Uploads are still in progress
            </h2>
            <p className="mt-2 text-sm leading-relaxed text-slate-600">
              Leaving now may stop unfinished uploads.
            </p>
            <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
              <button
                type="button"
                onClick={() => setShowUploadsConfirm(false)}
                className="inline-flex min-h-11 items-center justify-center rounded-xl border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-700 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer"
              >
                Stay
              </button>
              <button
                type="button"
                onClick={() => {
                  setShowUploadsConfirm(false);
                  finishNavigation(pendingExitAction);
                }}
                className="inline-flex min-h-11 items-center justify-center rounded-xl bg-amber-700 px-4 text-sm font-semibold text-white hover:bg-amber-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-500 cursor-pointer"
              >
                Leave anyway
              </button>
            </div>
          </div>
        </div>
      )}

      {/* RECOVERY-SAFE EXIT CONFIRMATION DIALOG (Shown only when pending/failed sync exists) */}
      {showExitConfirm && (
        <div
          ref={exitDialogRef}
          role="dialog"
          aria-modal="true"
          aria-labelledby="confirm-exit-title"
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/50 p-4 backdrop-blur-xs"
        >
          <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-2xl">
            <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-amber-100 text-amber-800">
              <AlertCircle className="h-6 w-6" />
            </div>
            <h2 id="confirm-exit-title" className="mt-4 font-['Outfit',sans-serif] text-xl font-bold text-slate-950">
              Your latest changes haven't been saved.
            </h2>
            <p className="mt-2 text-sm leading-relaxed text-slate-600">
              Your edits are safely preserved on this device, but could not be synced to the server. You can retry syncing or leave anyway.
            </p>
            <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
              <button
                type="button"
                onClick={() => setShowExitConfirm(false)}
                className="inline-flex min-h-11 items-center justify-center rounded-xl border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-700 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer"
              >
                Stay and retry
              </button>
              <button
                type="button"
                onClick={handleConfirmExit}
                className="inline-flex min-h-11 items-center justify-center rounded-xl border border-slate-200 bg-slate-100 px-3 text-xs font-medium text-slate-600 hover:bg-slate-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer"
              >
                Leave anyway
              </button>
              <button
                type="button"
                onClick={() => void handleRetryAndExit()}
                className="inline-flex min-h-11 items-center justify-center rounded-xl bg-emerald-700 px-4 text-sm font-semibold text-white hover:bg-emerald-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer"
              >
                Retry & exit
              </button>
            </div>
          </div>
        </div>
      )}


      {/* DESTRUCTIVE DISCARD CONFIRMATION DIALOG */}
      {showDiscardConfirm && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="confirm-discard-title"
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/50 p-4 backdrop-blur-xs"
        >
          <div ref={discardDialogRef} className="max-h-[calc(100dvh-2rem)] w-full max-w-md overflow-y-auto rounded-2xl border border-slate-200 bg-white p-6 shadow-2xl">
            <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-rose-100 text-rose-700">
              <Trash2 className="h-6 w-6" />
            </div>
            <h2 id="confirm-discard-title" className="mt-4 font-['Outfit',sans-serif] text-xl font-bold text-slate-950">
              {isRevision ? 'Discard these changes?' : 'Discard this property?'}
            </h2>
            <p className="mt-2 text-sm leading-relaxed text-slate-600">
              {isRevision
                ? 'Your current published property will stay unchanged.'
                : "This draft and its temporary uploaded media will be permanently removed. This can't be undone."}
            </p>
            {discardError && (
              <div role="alert" className="mt-3 rounded-xl border border-rose-200 bg-rose-50 p-3 text-xs text-rose-700">
                {discardError}
              </div>
            )}
            <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
              <button
                ref={keepEditingBtnRef}
                type="button"
                disabled={isDiscarding}
                onClick={() => {
                  setShowDiscardConfirm(false);
                  setDiscardError(null);
                  restoreDiscardFocus();
                }}
                className="inline-flex min-h-11 items-center justify-center rounded-xl border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-700 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer disabled:opacity-50"
              >
                Keep editing
              </button>
              <button
                type="button"
                disabled={isDiscarding}
                onClick={() => void handleConfirmDiscard()}
                className="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl bg-rose-600 px-4 text-sm font-semibold text-white hover:bg-rose-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-500 cursor-pointer disabled:opacity-50"
              >
                {isDiscarding ? (
                  <>
                    <LoaderCircle className="h-4 w-4 animate-spin" />
                    <span>Discarding…</span>
                  </>
                ) : (
                  <span>{discardError ? 'Try again' : isRevision ? 'Discard changes' : 'Discard property'}</span>
                )}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
};
