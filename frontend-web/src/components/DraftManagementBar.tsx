import React, { useState, useEffect, useRef, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { motion, AnimatePresence } from 'framer-motion';
import {
  FileEdit,
  Trash2,
  Plus,
  ChevronDown,
  CheckCircle2,
  RefreshCw,
  AlertTriangle,
  Clock,
  Layers,
  FileText,
  X,
  Sparkles,
  ArrowRight
} from 'lucide-react';
import { DraftSummary } from '../services/draftService';

export type AutosaveStatus = 'idle' | 'saving' | 'saved' | 'offline' | 'conflict';

interface DraftManagementBarProps {
  currentDraftId: string | null;
  draftType: 'SINGLE' | 'BATCH';
  autosaveStatus: AutosaveStatus;
  lastSavedAt: Date | null;
  conflictMessage?: string | null;
  drafts: DraftSummary[];
  isLoadingDrafts?: boolean;
  onSelectDraft: (draftId: string) => void;
  onStartNewDraft: () => void;
  onDiscardDraft: (draftId: string) => Promise<void> | void;
  onResolveConflictKeepLocal?: () => void;
  onResolveConflictReloadServer?: () => void;
  compact?: boolean;
  fetchingMediaProgress?: { loaded: number; total: number } | null;
}

const formatRelativeTime = (dateStrOrDate: string | Date | null): string => {
  if (!dateStrOrDate) return 'Just now';
  const time = typeof dateStrOrDate === 'string' ? new Date(dateStrOrDate).getTime() : dateStrOrDate.getTime();
  if (isNaN(time)) return 'Recently';

  const diffMs = Date.now() - time;
  const diffSec = Math.floor(diffMs / 1000);
  if (diffSec < 15) return 'Just now';
  if (diffSec < 60) return `${diffSec}s ago`;

  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin}m ago`;

  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) return `${diffHr}h ago`;

  const diffDays = Math.floor(diffHr / 24);
  if (diffDays === 1) return 'Yesterday';
  if (diffDays < 7) return `${diffDays}d ago`;

  return new Date(time).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
};

export const DraftManagementBar: React.FC<DraftManagementBarProps> = ({
  currentDraftId,
  draftType,
  autosaveStatus,
  lastSavedAt,
  conflictMessage,
  drafts,
  isLoadingDrafts = false,
  onSelectDraft,
  onStartNewDraft,
  onDiscardDraft,
  onResolveConflictKeepLocal,
  onResolveConflictReloadServer,
  compact = false,
  fetchingMediaProgress = null
}) => {
  const [isDropdownOpen, setIsDropdownOpen] = useState(false);
  const [draftToDiscard, setDraftToDiscard] = useState<DraftSummary | null>(null);
  const [isDiscarding, setIsDiscarding] = useState(false);
  const [dropdownCoords, setDropdownCoords] = useState<{ top: number; right: number } | null>(null);
  const dropdownButtonRef = useRef<HTMLButtonElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);
  const keepDraftBtnRef = useRef<HTMLButtonElement>(null);

  // Responsive breakpoint tracking: <640px is mobile bottom sheet, >=640px is desktop/tablet popover
  const [isMobile, setIsMobile] = useState<boolean>(() => {
    if (typeof window === 'undefined') return false;
    return window.innerWidth < 640;
  });

  useEffect(() => {
    const handleResize = () => {
      setIsMobile(window.innerWidth < 640);
    };
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  // Position popover anchored to Drafts button in viewport coordinates (desktop only)
  const updateDropdownPosition = useCallback(() => {
    if (!dropdownButtonRef.current) return;
    const rect = dropdownButtonRef.current.getBoundingClientRect();
    const rightOffset = Math.max(12, Math.min(window.innerWidth - 296, window.innerWidth - rect.right));
    const topOffset = rect.bottom + 6;
    setDropdownCoords({ top: topOffset, right: rightOffset });
  }, []);

  // Close dropdown on outside click, window scroll/resize, or Escape
  useEffect(() => {
    if (!isDropdownOpen) return;

    if (isMobile) {
      // Mobile bottom sheet handles its own backdrop taps & Escape key
      const handleKeyDown = (e: KeyboardEvent) => {
        if (e.key === 'Escape') {
          setIsDropdownOpen(false);
          dropdownButtonRef.current?.focus();
        }
      };
      window.addEventListener('keydown', handleKeyDown);
      return () => {
        window.removeEventListener('keydown', handleKeyDown);
      };
    }

    // Desktop popover handlers
    updateDropdownPosition();

    const handleScroll = () => {
      updateDropdownPosition();
    };
    const handleResize = () => {
      updateDropdownPosition();
    };
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setIsDropdownOpen(false);
        dropdownButtonRef.current?.focus();
      }
    };
    const handleClickOutside = (event: MouseEvent) => {
      const target = event.target as Node;
      if (dropdownButtonRef.current && dropdownButtonRef.current.contains(target)) {
        return;
      }
      if (popoverRef.current && popoverRef.current.contains(target)) {
        return;
      }
      setIsDropdownOpen(false);
    };

    window.addEventListener('scroll', handleScroll, { passive: true });
    window.addEventListener('resize', handleResize);
    window.addEventListener('keydown', handleKeyDown);
    document.addEventListener('mousedown', handleClickOutside);

    return () => {
      window.removeEventListener('scroll', handleScroll);
      window.removeEventListener('resize', handleResize);
      window.removeEventListener('keydown', handleKeyDown);
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [isDropdownOpen, isMobile, updateDropdownPosition]);

  // Mobile Bottom Sheet: Lock background page scroll & restore exact position on close
  useEffect(() => {
    if (!isDropdownOpen || !isMobile) return;
    const scrollY = window.scrollY;
    const originalOverflow = document.body.style.overflow;
    const originalPosition = document.body.style.position;
    const originalTop = document.body.style.top;
    const originalWidth = document.body.style.width;

    document.body.style.overflow = 'hidden';
    document.body.style.position = 'fixed';
    document.body.style.top = `-${scrollY}px`;
    document.body.style.width = '100%';

    return () => {
      document.body.style.overflow = originalOverflow;
      document.body.style.position = originalPosition;
      document.body.style.top = originalTop;
      document.body.style.width = originalWidth;
      window.scrollTo(0, scrollY);
    };
  }, [isDropdownOpen, isMobile]);

  // Robust viewport modal: lock background page scrolling & support Escape
  useEffect(() => {
    if (!draftToDiscard) return;
    const scrollY = window.scrollY;
    const originalOverflow = document.body.style.overflow;
    const originalPosition = document.body.style.position;
    const originalTop = document.body.style.top;
    const originalWidth = document.body.style.width;

    document.body.style.overflow = 'hidden';
    document.body.style.position = 'fixed';
    document.body.style.top = `-${scrollY}px`;
    document.body.style.width = '100%';

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !isDiscarding) {
        setDraftToDiscard(null);
      }
    };
    window.addEventListener('keydown', handleKeyDown);

    // Focus safe action initially
    const timer = setTimeout(() => {
      keepDraftBtnRef.current?.focus();
    }, 50);

    return () => {
      clearTimeout(timer);
      window.removeEventListener('keydown', handleKeyDown);
      document.body.style.overflow = originalOverflow;
      document.body.style.position = originalPosition;
      document.body.style.top = originalTop;
      document.body.style.width = originalWidth;
      window.scrollTo(0, scrollY);
    };
  }, [draftToDiscard, isDiscarding]);

  const handleConfirmDiscard = async () => {
    if (!draftToDiscard) return;
    setIsDiscarding(true);
    try {
      await onDiscardDraft(draftToDiscard.draftId);
      setDraftToDiscard(null);
      setIsDropdownOpen(false);
    } catch {
      // Handled in parent
    } finally {
      setIsDiscarding(false);
    }
  };

  const currentDraftSummary = drafts.find((d) => d.draftId === currentDraftId);

  // VIEWPORT-ATTACHED DISCARD MODAL (PORTAL TO DOCUMENT.BODY)
  const renderDiscardModal = () => {
    if (typeof document === 'undefined') return null;
    return createPortal(
      <AnimatePresence>
        {draftToDiscard && (
          <div
            className="fixed inset-0 z-[9999] flex items-center justify-center p-4 bg-slate-950/80 backdrop-blur-md min-h-[100dvh]"
            role="dialog"
            aria-modal="true"
            aria-labelledby="discard-draft-dialog-title"
            onClick={() => {
              if (!isDiscarding) setDraftToDiscard(null);
            }}
          >
            <motion.div
              initial={{ opacity: 0, scale: 0.95, y: 8 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 8 }}
              transition={{ duration: 0.15 }}
              className="w-full max-w-md rounded-3xl bg-slate-900 border border-slate-800 p-6 text-white shadow-2xl relative my-auto max-h-[90dvh] overflow-y-auto"
              onClick={(e) => e.stopPropagation()}
            >
              <div className="flex items-center gap-3 text-rose-400 mb-3">
                <div className="w-10 h-10 rounded-2xl bg-rose-500/10 border border-rose-500/20 flex items-center justify-center shrink-0">
                  <Trash2 className="w-5 h-5 text-rose-400" />
                </div>
                <div className="min-w-0">
                  <h3 id="discard-draft-dialog-title" className="text-base font-black font-['Outfit'] text-white">
                    Discard this draft?
                  </h3>
                  <p className="text-xs text-slate-400 font-mono truncate">
                    {draftToDiscard.titleSummary || 'Untitled Draft'}
                  </p>
                </div>
              </div>

              <p className="text-xs text-slate-300 leading-relaxed my-4">
                Your unpublished property details and temporary staged draft media will be permanently removed.
                This action cannot be undone.
              </p>

              <div className="flex items-center justify-end gap-3 mt-6">
                <button
                  ref={keepDraftBtnRef}
                  type="button"
                  disabled={isDiscarding}
                  onClick={() => setDraftToDiscard(null)}
                  className="min-h-[44px] px-4 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-xs font-bold text-slate-300 transition-colors disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-slate-500"
                >
                  Keep draft
                </button>
                <button
                  type="button"
                  disabled={isDiscarding}
                  onClick={handleConfirmDiscard}
                  className="min-h-[44px] px-4 py-2 rounded-xl bg-rose-600 hover:bg-rose-500 text-xs font-bold text-white transition-colors flex items-center gap-1.5 disabled:opacity-50 shadow-lg shadow-rose-900/30 focus:outline-none focus:ring-2 focus:ring-rose-500"
                >
                  {isDiscarding ? (
                    <>
                      <RefreshCw className="w-3.5 h-3.5 animate-spin" /> Discarding…
                    </>
                  ) : (
                    <>
                      <Trash2 className="w-3.5 h-3.5" /> Confirm discard
                    </>
                  )}
                </button>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>,
      document.body
    );
  };

  // MOBILE NATIVE-FEELING BOTTOM SHEET (PORTAL TO DOCUMENT.BODY)
  // Adaptive content height up to max 85dvh, fixed header & footer, scrollable draft list
  const renderMobileBottomSheet = () => {
    return createPortal(
      <AnimatePresence>
        {isDropdownOpen && (
          <div
            className="fixed inset-0 z-[9990] flex flex-col justify-end bg-slate-950/80 backdrop-blur-sm"
            role="dialog"
            aria-modal="true"
            aria-label="Saved Drafts"
            onClick={() => setIsDropdownOpen(false)}
          >
            <motion.div
              initial={{ y: '100%' }}
              animate={{ y: 0 }}
              exit={{ y: '100%' }}
              transition={{ type: 'spring', damping: 28, stiffness: 300 }}
              onClick={(e) => e.stopPropagation()}
              className="w-full bg-slate-900 border-t border-slate-800 rounded-t-3xl shadow-2xl flex flex-col max-h-[85dvh] text-slate-200 overflow-hidden"
              style={{
                paddingBottom: 'max(1rem, env(safe-area-inset-bottom, 0px))',
                paddingLeft: 'max(0.75rem, env(safe-area-inset-left, 0px))',
                paddingRight: 'max(0.75rem, env(safe-area-inset-right, 0px))',
              }}
            >
              {/* GRAB INDICATOR & FIXED HEADER */}
              <div className="shrink-0 px-4 pt-3 pb-2.5 border-b border-slate-800/80">
                <div className="w-12 h-1.5 rounded-full bg-slate-700/80 mx-auto mb-3" aria-hidden="true" />
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <FileText className="w-4 h-4 text-emerald-400" />
                    <h2 className="text-sm font-bold font-['Outfit'] text-white">
                      Saved Drafts ({drafts.length})
                    </h2>
                  </div>
                  <button
                    type="button"
                    onClick={() => setIsDropdownOpen(false)}
                    className="min-h-[44px] min-w-[44px] -mr-2 flex items-center justify-center rounded-xl text-slate-400 hover:text-white hover:bg-slate-800 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400 cursor-pointer"
                    aria-label="Close saved drafts"
                  >
                    <X className="w-5 h-5" />
                  </button>
                </div>
              </div>

              {/* SCROLLABLE DRAFT LIST (FLEX-1 OVERFLOW-Y-AUTO) */}
              <div className="flex-1 overflow-y-auto overscroll-contain px-3 py-2 divide-y divide-slate-800/60 min-h-0">
                {isLoadingDrafts ? (
                  <div className="py-8 text-center text-xs text-slate-400 flex items-center justify-center gap-2">
                    <RefreshCw className="w-4 h-4 animate-spin text-emerald-400" /> Loading drafts…
                  </div>
                ) : drafts.length === 0 ? (
                  <div className="py-8 text-center px-4">
                    <p className="text-sm font-semibold text-slate-300">No unfinished drafts</p>
                    <p className="text-xs text-slate-400 mt-1">
                      Any property details or photos you type will autosave here automatically.
                    </p>
                  </div>
                ) : (
                  drafts.map((draft) => {
                    const isCurrent = draft.draftId === currentDraftId;
                    return (
                      <div
                        key={draft.draftId}
                        className={`flex items-center justify-between gap-3 py-3 px-2 rounded-xl transition-colors ${
                          isCurrent ? 'bg-emerald-950/30' : 'active:bg-slate-800/50'
                        }`}
                      >
                        <div
                          className="min-w-0 flex-1 cursor-pointer"
                          onClick={() => {
                            onSelectDraft(draft.draftId);
                            setIsDropdownOpen(false);
                          }}
                        >
                          <div className="flex items-center gap-2">
                            <span className="text-xs font-bold text-slate-100 truncate block">
                              {draft.titleSummary || 'Untitled Draft'}
                            </span>
                            {isCurrent ? (
                              <span className="text-[9px] font-mono px-1.5 py-0.5 rounded bg-emerald-500/20 text-emerald-400 border border-emerald-500/30 shrink-0">
                                Active
                              </span>
                            ) : draft.status === 'PUBLISHING' ? (
                              <span className="text-[9px] font-mono px-1.5 py-0.5 rounded bg-amber-500/20 text-amber-300 border border-amber-500/30 shrink-0">
                                Publishing interrupted
                              </span>
                            ) : null}
                          </div>
                          <div className="flex items-center gap-1.5 mt-1 text-[11px] text-slate-400 font-mono flex-wrap">
                            <span className="uppercase">{draft.draftType}</span>
                            <span>•</span>
                            <span>{formatRelativeTime(draft.updatedAt)}</span>
                            {draft.mediaCount > 0 && (
                              <>
                                <span>•</span>
                                <span>{draft.mediaCount} media</span>
                              </>
                            )}
                          </div>
                        </div>

                        <div className="flex items-center gap-2 shrink-0">
                          {!isCurrent && (
                            <button
                              type="button"
                              onClick={() => {
                                onSelectDraft(draft.draftId);
                                setIsDropdownOpen(false);
                              }}
                              className="min-h-[44px] px-3.5 py-2 rounded-xl bg-gradient-to-r from-emerald-600 to-teal-600 hover:from-emerald-500 hover:to-teal-500 text-white font-bold text-xs shadow-sm shadow-emerald-950/40 border border-emerald-400/30 flex items-center gap-1.5 transition-all active:scale-95 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400 cursor-pointer"
                            >
                              <span>{draft.status === 'PUBLISHING' ? 'Resume' : 'Continue'}</span>
                              <ArrowRight className="w-3.5 h-3.5 text-emerald-100 shrink-0" />
                            </button>
                          )}
                          <button
                            type="button"
                            title="Discard draft"
                            aria-label={`Discard draft ${draft.titleSummary || 'Untitled'}`}
                            onClick={(e) => {
                              e.stopPropagation();
                              setDraftToDiscard(draft);
                            }}
                            className="min-h-[44px] min-w-[44px] flex items-center justify-center rounded-xl text-slate-400 hover:text-rose-400 hover:bg-rose-950/40 border border-transparent hover:border-rose-800/40 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-400 cursor-pointer"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        </div>
                      </div>
                    );
                  })
                )}
              </div>

              {/* FIXED FOOTER */}
              <div className="shrink-0 p-3 pt-2 border-t border-slate-800/80 bg-slate-900/95 flex flex-col gap-2">
                <button
                  type="button"
                  onClick={() => {
                    setIsDropdownOpen(false);
                    onStartNewDraft();
                  }}
                  className="w-full min-h-[44px] px-4 py-2.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-xs font-bold text-emerald-400 hover:text-emerald-300 flex items-center justify-center gap-1.5 border border-slate-700/80 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400 cursor-pointer"
                >
                  <Plus className="w-4 h-4" />
                  <span>New draft</span>
                </button>
                <p className="text-[11px] text-slate-500 text-center font-mono leading-tight">
                  Drafts are automatically deleted after 15 days of inactivity.
                </p>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>,
      document.body
    );
  };

  // DESKTOP VIEWPORT-ATTACHED DROPDOWN POPOVER (PORTAL TO DOCUMENT.BODY)
  // Preserved exactly as approved on desktop/tablet (>=640px)
  const renderDesktopPopover = () => {
    return createPortal(
      <AnimatePresence>
        {isDropdownOpen && dropdownCoords && (
          <motion.div
            ref={popoverRef}
            initial={{ opacity: 0, y: -4, scale: 0.98 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -4, scale: 0.98 }}
            transition={{ duration: 0.15 }}
            style={{
              position: 'fixed',
              top: `${dropdownCoords.top}px`,
              right: `${dropdownCoords.right}px`,
              zIndex: 9998,
              maxWidth: 'calc(100vw - 24px)'
            }}
            className="w-[380px] max-h-[min(480px,80vh)] overflow-y-auto rounded-2xl bg-slate-900 border border-slate-800 shadow-2xl p-2 text-slate-200"
            role="menu"
            aria-label="Saved Property Drafts"
          >
            <div className="flex items-center justify-between px-3 py-2 border-b border-slate-800">
              <span className="text-xs font-black uppercase font-mono tracking-wider text-slate-400 flex items-center gap-1.5">
                <FileText className="w-3.5 h-3.5 text-emerald-400" /> Saved Property Drafts ({drafts.length})
              </span>
              <button
                type="button"
                onClick={() => {
                  setIsDropdownOpen(false);
                  onStartNewDraft();
                }}
                className="text-[11px] font-bold text-emerald-400 hover:text-emerald-300 flex items-center gap-1 min-h-[40px] px-2.5 rounded-xl focus:outline-none focus:ring-1 focus:ring-emerald-500 cursor-pointer transition-colors"
              >
                <Plus className="w-3.5 h-3.5" /> New draft
              </button>
            </div>

            {isLoadingDrafts ? (
              <div className="py-8 text-center text-xs text-slate-400 flex items-center justify-center gap-2">
                <RefreshCw className="w-4 h-4 animate-spin text-emerald-400" /> Loading drafts…
              </div>
            ) : drafts.length === 0 ? (
              <div className="py-8 text-center px-4">
                <p className="text-xs font-semibold text-slate-400">No unfinished drafts</p>
                <p className="text-[11px] text-slate-500 mt-1">
                  Any property details or photos you type will autosave here automatically.
                </p>
              </div>
            ) : (
              <div className="divide-y divide-slate-800/60 py-1">
                {drafts.map((draft) => {
                  const isCurrent = draft.draftId === currentDraftId;
                  return (
                    <div
                      key={draft.draftId}
                      className={`flex items-center justify-between gap-3 p-2.5 rounded-xl transition-colors ${
                        isCurrent ? 'bg-emerald-950/40 border border-emerald-800/60' : 'hover:bg-slate-800/60'
                      }`}
                    >
                      <div
                        className="min-w-0 flex-1 cursor-pointer"
                        onClick={() => {
                          onSelectDraft(draft.draftId);
                          setIsDropdownOpen(false);
                        }}
                      >
                        <div className="flex items-center gap-2">
                          <span className="text-xs font-bold text-slate-200 truncate">
                            {draft.titleSummary || 'Untitled Draft'}
                          </span>
                          {isCurrent ? (
                            <span className="text-[9px] font-mono px-1.5 py-0.5 rounded bg-emerald-500/20 text-emerald-400 border border-emerald-500/30">
                              Active
                            </span>
                          ) : draft.status === 'PUBLISHING' ? (
                            <span className="text-[9px] font-mono px-1.5 py-0.5 rounded bg-amber-500/20 text-amber-300 border border-amber-500/30">
                              Publishing interrupted
                            </span>
                          ) : null}
                        </div>
                        <div className="flex items-center gap-2 mt-1 text-[10px] text-slate-400 font-mono">
                          <span className="uppercase">{draft.draftType}</span>
                          <span>•</span>
                          <span>{formatRelativeTime(draft.updatedAt)}</span>
                          {draft.mediaCount > 0 && (
                            <>
                              <span>•</span>
                              <span>{draft.mediaCount} media</span>
                            </>
                          )}
                        </div>
                      </div>

                      <div className="flex items-center gap-1.5 shrink-0">
                        {!isCurrent && (
                          <button
                            type="button"
                            onClick={() => {
                              onSelectDraft(draft.draftId);
                              setIsDropdownOpen(false);
                            }}
                            className="min-h-[44px] px-3 py-2 rounded-xl bg-gradient-to-r from-emerald-600 to-teal-600 hover:from-emerald-500 hover:to-teal-500 text-white font-bold text-xs shadow-sm shadow-emerald-950/40 border border-emerald-400/30 flex items-center gap-1.5 transition-all active:scale-95 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400 cursor-pointer"
                          >
                            <span>{draft.status === 'PUBLISHING' ? 'Resume' : 'Continue'}</span>
                            <ArrowRight className="w-3.5 h-3.5 text-emerald-100 shrink-0" />
                          </button>
                        )}
                        <button
                          type="button"
                          title="Discard draft"
                          aria-label={`Discard draft ${draft.titleSummary || 'Untitled'}`}
                          onClick={(e) => {
                            e.stopPropagation();
                            setDraftToDiscard(draft);
                          }}
                          className="min-h-[44px] min-w-[44px] flex items-center justify-center rounded-xl text-slate-400 hover:text-rose-400 hover:bg-rose-950/40 border border-transparent hover:border-rose-800/40 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-400 cursor-pointer"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}

            <div className="px-3 py-2 border-t border-slate-800 text-[11px] text-slate-500 text-center font-mono">
              Drafts are automatically deleted after 15 days of inactivity.
            </div>
          </motion.div>
        )}
      </AnimatePresence>,
      document.body
    );
  };

  const renderDropdownContent = () => {
    if (typeof document === 'undefined') return null;
    return isMobile ? renderMobileBottomSheet() : renderDesktopPopover();
  };

  // COMPACT INLINE MODE
  if (compact) {
    return (
      <div className="flex items-center gap-2">
        {/* Autosave quiet indicator badge */}
        <div className="hidden sm:flex items-center gap-1.5 text-xs font-mono shrink-0 px-2.5 py-1 rounded-xl bg-slate-950/60 border border-slate-800/80">
          {fetchingMediaProgress ? (
            <span className="flex items-center gap-1.5 text-cyan-300 animate-pulse">
              <RefreshCw className="w-3 h-3 animate-spin text-cyan-400" />
              <span className="text-[11px] font-bold">Fetching media {fetchingMediaProgress.loaded}/{fetchingMediaProgress.total}</span>
            </span>
          ) : autosaveStatus === 'saving' ? (
            <span className="flex items-center gap-1.5 text-cyan-400">
              <RefreshCw className="w-3 h-3 animate-spin text-cyan-400" />
              <span className="text-[11px]">Saving…</span>
            </span>
          ) : autosaveStatus === 'saved' ? (
            <span className="flex items-center gap-1.5 text-emerald-400" title={`Saved ${formatRelativeTime(lastSavedAt)}`}>
              <CheckCircle2 className="w-3 h-3" />
              <span className="text-[11px]">Saved</span>
            </span>
          ) : autosaveStatus === 'offline' ? (
            <span className="flex items-center gap-1.5 text-amber-400" title="Saved locally in browser">
              <Clock className="w-3 h-3" />
              <span className="text-[11px]">Offline</span>
            </span>
          ) : (
            <span className="flex items-center gap-1.5 text-slate-500" title="Autosave active">
              <Sparkles className="w-3 h-3" />
              <span className="text-[11px]">Autosave</span>
            </span>
          )}
        </div>

        {/* Drafts Dropdown Trigger Button */}
        <div className="relative shrink-0">
          <button
            ref={dropdownButtonRef}
            type="button"
            onClick={() => setIsDropdownOpen((prev) => !prev)}
            aria-expanded={isDropdownOpen}
            className="flex items-center gap-1.5 min-h-[44px] px-3 sm:px-3.5 py-2 sm:py-1.5 rounded-xl bg-slate-900 hover:bg-slate-800 border border-slate-700 text-white text-xs font-bold transition-all shadow-sm active:scale-95 cursor-pointer"
          >
            <FileEdit className="w-3.5 h-3.5 text-emerald-400" />
            <span>Drafts</span>
            {drafts.length > 0 && (
              <span className="ml-0.5 px-1.5 py-0.5 rounded-full bg-emerald-950 text-emerald-400 border border-emerald-800 text-[10px] font-mono font-bold">
                {drafts.length}
              </span>
            )}
            <ChevronDown className={`w-3.5 h-3.5 text-slate-400 transition-transform ${isDropdownOpen ? 'rotate-180' : ''}`} />
          </button>
        </div>

        {renderDropdownContent()}
        {renderDiscardModal()}
      </div>
    );
  }

  // STANDARD FULL BAR (legacy / fallback)
  return (
    <div className="relative z-30 mb-5">
      {/* CONFLICT BANNER */}
      {autosaveStatus === 'conflict' && (
        <motion.div
          initial={{ opacity: 0, y: -8 }}
          animate={{ opacity: 1, y: 0 }}
          className="mb-3 p-3.5 sm:p-4 rounded-2xl bg-amber-950/80 border border-amber-500/50 text-amber-200 text-xs shadow-lg"
        >
          <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3">
            <div className="flex items-start gap-2.5">
              <AlertTriangle className="w-5 h-5 text-amber-400 shrink-0 mt-0.5" />
              <div>
                <p className="font-bold text-amber-100 text-sm">Draft updated elsewhere</p>
                <p className="text-amber-300/90 text-xs mt-0.5">
                  {conflictMessage || 'A newer version was saved in another tab or device. Choose how to proceed:'}
                </p>
              </div>
            </div>
            <div className="flex items-center gap-2 w-full sm:w-auto shrink-0">
              {onResolveConflictReloadServer && (
                <button
                  type="button"
                  onClick={onResolveConflictReloadServer}
                  className="flex-1 sm:flex-none min-h-[44px] px-3.5 py-2 rounded-xl bg-amber-500 text-slate-950 font-bold text-xs hover:bg-amber-400 transition-colors"
                >
                  Load server version
                </button>
              )}
              {onResolveConflictKeepLocal && (
                <button
                  type="button"
                  onClick={onResolveConflictKeepLocal}
                  className="flex-1 sm:flex-none min-h-[44px] px-3.5 py-2 rounded-xl bg-slate-800 text-white font-bold text-xs hover:bg-slate-700 transition-colors border border-slate-700"
                >
                  Overwrite with my edits
                </button>
              )}
            </div>
          </div>
        </motion.div>
      )}

      {/* TOP BAR */}
      <div className="flex flex-wrap items-center justify-between gap-2.5 py-2.5 px-3 sm:px-4 rounded-2xl bg-slate-950/70 border border-slate-800/90 backdrop-blur-md shadow-sm">
        {/* LEFT: Autosave Quiet Status & Current Draft Summary */}
        <div className="flex items-center gap-2.5 min-w-0">
          <div className="flex items-center gap-1.5 shrink-0">
            {autosaveStatus === 'saving' && (
              <span className="flex items-center gap-1.5 text-xs text-cyan-400 font-mono">
                <RefreshCw className="w-3.5 h-3.5 animate-spin text-cyan-400" />
                <span className="hidden sm:inline">Saving draft…</span>
                <span className="sm:hidden">Saving…</span>
              </span>
            )}
            {autosaveStatus === 'saved' && (
              <span className="flex items-center gap-1.5 text-xs text-emerald-400 font-mono">
                <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />
                <span>Draft saved {formatRelativeTime(lastSavedAt)}</span>
              </span>
            )}
            {autosaveStatus === 'offline' && (
              <span className="flex items-center gap-1.5 text-xs text-amber-400 font-mono">
                <Clock className="w-3.5 h-3.5 text-amber-400" />
                <span>Offline • saved locally</span>
              </span>
            )}
            {autosaveStatus === 'idle' && (
              <span className="flex items-center gap-1.5 text-xs text-slate-400 font-mono">
                <Sparkles className="w-3.5 h-3.5 text-slate-500" />
                <span>Autosave active</span>
              </span>
            )}
          </div>

          {currentDraftSummary && (
            <div className="hidden md:flex items-center gap-2 border-l border-slate-800 pl-2.5 min-w-0">
              <span className="text-xs font-semibold text-slate-300 truncate max-w-[220px]">
                {currentDraftSummary.titleSummary || 'Untitled Draft'}
              </span>
              <span className="text-[10px] font-mono uppercase px-2 py-0.5 rounded-full bg-slate-800 text-slate-400 border border-slate-700">
                {currentDraftSummary.draftType}
              </span>
            </div>
          )}
        </div>

        {/* RIGHT: Action Controls (Drafts Drawer Pill) */}
        <div className="flex items-center gap-2 ml-auto shrink-0">
          {/* Drafts Dropdown Button */}
          <div className="relative">
            <button
              ref={dropdownButtonRef}
              type="button"
              onClick={() => setIsDropdownOpen((prev) => !prev)}
              aria-expanded={isDropdownOpen}
              className="flex items-center gap-1.5 min-h-[44px] px-3 sm:px-3.5 py-1.5 rounded-xl bg-slate-900 hover:bg-slate-800 border border-slate-700 text-white text-xs font-bold transition-all shadow-sm active:scale-95"
            >
              <FileEdit className="w-3.5 h-3.5 text-emerald-400" />
              <span>Drafts</span>
              {drafts.length > 0 && (
                <span className="ml-1 px-1.5 py-0.2 rounded-full bg-emerald-950 text-emerald-400 border border-emerald-800 text-[10px] font-mono font-bold">
                  {drafts.length}
                </span>
              )}
              <ChevronDown className={`w-3.5 h-3.5 text-slate-400 transition-transform ${isDropdownOpen ? 'rotate-180' : ''}`} />
            </button>
          </div>
        </div>
      </div>

      {renderDropdownContent()}
      {renderDiscardModal()}
    </div>
  );
};
