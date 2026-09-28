import React, { useState, useRef, useEffect } from 'react';
import {
  Building2,
  Check,
  LoaderCircle,
  RefreshCw,
  LogIn,
  AlertCircle,
  ArrowUpRight,
  Trash2
} from 'lucide-react';
import type { LessorSaveStatus } from '../services/lessorAutosave';

export interface LessorOnboardingHeaderProps {
  status?: LessorSaveStatus | null;
  guest?: boolean;
  onExit: () => Promise<boolean | void> | boolean | void;
  onExitToLanding: () => void;
  onRetrySave?: () => void;
  onRequestAuth?: () => void;
  currentStepLabel?: string;
  canDiscard?: boolean;
  isRevision?: boolean;
  hasServerDraft?: boolean;
  onDiscard?: () => Promise<void> | void;
}

export const LessorOnboardingHeader: React.FC<LessorOnboardingHeaderProps> = ({
  status,
  guest = false,
  onExit,
  onExitToLanding,
  onRetrySave,
  onRequestAuth,
  currentStepLabel,
  canDiscard = false,
  isRevision = false,
  hasServerDraft = true,
  onDiscard
}) => {
  const [showExitConfirm, setShowExitConfirm] = useState(false);
  const [pendingExitAction, setPendingExitAction] = useState<'exit' | 'landing'>('exit');
  const [isFlushing, setIsFlushing] = useState(false);
  const [showDiscardConfirm, setShowDiscardConfirm] = useState(false);
  const [isDiscarding, setIsDiscarding] = useState(false);
  const [discardError, setDiscardError] = useState<string | null>(null);

  const keepEditingBtnRef = useRef<HTMLButtonElement>(null);
  const discardTriggerRef = useRef<HTMLButtonElement>(null);
  const discardDialogRef = useRef<HTMLDivElement>(null);
  const discardInFlight = useRef(false);

  // Focus safe button and listen for Escape when Discard modal opens
  useEffect(() => {
    if (!showDiscardConfirm) return;
    if (!isDiscarding) keepEditingBtnRef.current?.focus();
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !isDiscarding) {
        setShowDiscardConfirm(false);
        setDiscardError(null);
        discardTriggerRef.current?.focus();
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

  const handleAction = async (action: 'exit' | 'landing') => {
    // If status is already in error or conflict, show recovery dialog directly
    if (status === 'error' || status === 'conflict') {
      setPendingExitAction(action);
      setShowExitConfirm(true);
      return;
    }

    setIsFlushing(true);
    try {
      const ok = await onExit();
      if (ok === false) {
        setPendingExitAction(action);
        setShowExitConfirm(true);
        return;
      }
      if (action === 'landing') {
        onExitToLanding();
      }
    } catch {
      setPendingExitAction(action);
      setShowExitConfirm(true);
    } finally {
      setIsFlushing(false);
    }
  };

  const handleConfirmExit = () => {
    setShowExitConfirm(false);
    onExitToLanding();
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
        if (pendingExitAction === 'landing') {
          onExitToLanding();
        }
      }
    } finally {
      setIsFlushing(false);
    }
  };

  const handleTriggerDiscard = () => {
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
      <header className="sticky top-0 z-40 w-full border-b border-slate-200/90 bg-white/95 pt-[env(safe-area-inset-top)] shadow-xs backdrop-blur-xl">
        <div className="mx-auto flex min-h-16 max-w-6xl flex-wrap items-center justify-between gap-2 px-4 py-2 sm:h-16 sm:flex-nowrap sm:gap-3 sm:px-6 sm:py-0">
          {/* BRAND LOGO & TAGLINE BLOCK */}
          <button
            type="button"
            onClick={() => void handleAction('landing')}
            className="flex items-center gap-2.5 rounded-xl transition-opacity hover:opacity-85 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer min-w-0"
            title="Return to Pathome home"
            aria-label="Return to Pathome home"
          >
            <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-emerald-700 text-white shadow-sm shadow-emerald-700/20 shrink-0">
              <Building2 className="h-5 w-5" />
            </div>
            <div className="text-left flex flex-col justify-center min-w-0">
              <div className="flex items-center gap-2">
                <span className="font-['Outfit',sans-serif] text-base font-black tracking-tight text-slate-950 sm:text-lg leading-tight">
                  Path<span className="text-emerald-600">ome</span>
                </span>
                <span className="hidden sm:inline-block text-[11px] font-bold text-emerald-800 rounded-full bg-emerald-50 px-2 py-0.5 border border-emerald-200 leading-tight">
                  Property Onboarding
                </span>
              </div>
              <span className="text-[10px] sm:text-[11px] font-medium text-slate-500 leading-tight truncate">
                Your Dreams, Our Efforts.
              </span>
            </div>
          </button>

          {/* CENTER: CURRENT STEP (hidden on mobile, visible on tablet/desktop) */}
          {currentStepLabel && (
            <div className="hidden lg:flex items-center gap-2 text-xs font-semibold text-slate-600">
              <span className="h-1.5 w-1.5 rounded-full bg-emerald-600" />
              <span>{currentStepLabel}</span>
            </div>
          )}

          {/* RIGHT ACTIONS: AUTOSAVE STATUS, GUEST SIGN-IN, EXIT, DISCARD */}
          <div className="flex w-full flex-wrap items-center justify-end gap-2 sm:w-auto sm:flex-nowrap sm:gap-3">
            {/* AUTOSAVE MICRO-UX — Strictly truthful status */}
            <div className="flex items-center text-xs min-h-6 min-w-0" aria-live="polite">
              {isFlushing || status === 'saving' ? (
                <span className="flex items-center gap-1.5 font-medium text-slate-500 transition-opacity duration-150">
                  <LoaderCircle className="h-3.5 w-3.5 animate-spin text-slate-400" />
                  <span className="hidden min-[400px]:inline">Saving…</span>
                </span>
              ) : status === 'saved' ? (
                <span className="flex items-center gap-1 font-medium text-emerald-700 transition-opacity duration-150">
                  <Check className="h-3.5 w-3.5 stroke-[2.5]" />
                  <span className="hidden min-[400px]:inline">Saved</span>
                </span>
              ) : status === 'error' ? (
                <button
                  type="button"
                  onClick={onRetrySave}
                  className="flex items-center gap-1 font-semibold text-rose-700 hover:text-rose-800 transition-colors cursor-pointer"
                  title="Couldn't save changes to server. Tap to retry."
                >
                  <RefreshCw className="h-3.5 w-3.5" />
                  <span className="hidden min-[480px]:inline">Couldn't save · Retry</span>
                  <span className="min-[480px]:hidden">Retry</span>
                </button>
              ) : status === 'conflict' ? (
                <span className="flex items-center gap-1 font-semibold text-amber-700" title="Sync conflict from another session">
                  <AlertCircle className="h-3.5 w-3.5" />
                  <span className="hidden min-[480px]:inline">Needs attention</span>
                  <span className="min-[480px]:hidden">Conflict</span>
                </span>
              ) : null}
            </div>

            {/* GUEST SIGN-IN BUTTON */}
            {guest && onRequestAuth && (
              <button
                type="button"
                onClick={onRequestAuth}
                className="inline-flex min-h-11 items-center gap-1.5 rounded-xl px-2.5 py-1.5 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-100 hover:text-emerald-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer"
                title="Sign in to save across devices"
              >
                <LogIn className="h-3.5 w-3.5 text-slate-500" />
                <span>Sign in</span>
                <span className="hidden lg:inline text-[11px] font-normal text-slate-400">
                  to save across devices
                </span>
              </button>
            )}

            {/* SECONDARY EXIT CONTROL */}
            <button
              type="button"
              disabled={isFlushing}
              onClick={() => void handleAction('exit')}
              className="inline-flex min-h-11 items-center justify-center gap-1.5 rounded-xl border border-slate-300 bg-white px-3 sm:px-3.5 text-xs sm:text-sm font-semibold text-slate-700 shadow-2xs transition-all duration-150 motion-reduce:transition-none hover:border-slate-400 hover:bg-slate-50 active:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 disabled:opacity-50 cursor-pointer"
            >
              <span>Exit</span>
              <ArrowUpRight className="hidden sm:inline h-3.5 w-3.5 text-slate-500" />
            </button>

            {/* DISCARD IS VISIBLE WITHOUT HIDING THE DESTRUCTIVE ACTION IN A MENU */}
            {canDiscard && (
              <button
                ref={discardTriggerRef}
                type="button"
                disabled={isDiscarding}
                onClick={handleTriggerDiscard}
                className="inline-flex min-h-11 items-center justify-center gap-1.5 rounded-xl border border-rose-200 bg-white px-3 text-xs font-semibold text-rose-700 transition-colors hover:border-rose-300 hover:bg-rose-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-500 disabled:opacity-50"
              >
                <Trash2 className="h-4 w-4 shrink-0" aria-hidden="true" />
                <span>{isRevision ? 'Discard changes' : 'Discard draft'}</span>
              </button>
            )}
          </div>
        </div>
      </header>

      {/* RECOVERY-SAFE EXIT CONFIRMATION DIALOG (Shown only when pending/failed sync exists) */}
      {showExitConfirm && (
        <div
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
              We couldn't save your latest changes.
            </h2>
            <p className="mt-2 text-sm leading-relaxed text-slate-600">
              Your edits are safely preserved on this device, but could not be synced to the server. You can retry syncing or keep editing.
            </p>
            <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
              <button
                type="button"
                onClick={() => setShowExitConfirm(false)}
                className="inline-flex min-h-11 items-center justify-center rounded-xl border border-slate-300 bg-white px-4 text-sm font-semibold text-slate-700 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer"
              >
                Keep editing
              </button>
              <button
                type="button"
                onClick={handleConfirmExit}
                className="inline-flex min-h-11 items-center justify-center rounded-xl border border-slate-200 bg-slate-100 px-3 text-xs font-medium text-slate-600 hover:bg-slate-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer"
              >
                Exit anyway
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
                  discardTriggerRef.current?.focus();
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
