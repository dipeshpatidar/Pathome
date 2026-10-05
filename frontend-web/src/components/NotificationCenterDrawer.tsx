import React, { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  motion,
  AnimatePresence,
  useReducedMotion
} from 'framer-motion';
import {
  Bell,
  CheckCircle2,
  AlertCircle,
  Info,
  XCircle,
  Sparkles,
  X,
  CheckCheck,
  Trash2,
  ArrowRight,
  RefreshCw
} from 'lucide-react';
import { useNotification, NotificationCategory, NotificationHistoryItem } from '../context/NotificationContext';
import { activateNotificationItem, resolveNotificationActionLabel } from '../utils/notificationPolicy';

function formatTimeAgo(date: Date): string {
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffSec = Math.floor(diffMs / 1000);
  const diffMin = Math.floor(diffSec / 60);
  const diffHours = Math.floor(diffMin / 60);
  const diffDays = Math.floor(diffHours / 24);

  if (diffSec < 45) return 'Just now';
  if (diffMin < 60) return `${diffMin} min ago`;
  if (diffHours < 24) return `${diffHours} hour${diffHours > 1 ? 's' : ''} ago`;
  if (diffDays === 1) return 'Yesterday';
  if (diffDays < 7) return `${diffDays} days ago`;
  return date.toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
}

export const NotificationCenterDrawer: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const consumerSurface = /^\/(tenant|lessor|property)(?:\/|$)/.test(location.pathname);
  const pageMode = location.pathname.replace(/\/+$/, '') === '/tenant' && location.hash === '#notifications';
  const [pageTarget, setPageTarget] = useState<HTMLElement | null>(null);
  const shouldReduceMotion = useReducedMotion();

  const {
    history,
    unreadCount,
    isLoading,
    fetchError,
    markReadError,
    clearMarkReadError,
    refetchNotifications,
    isDrawerOpen,
    setIsDrawerOpen,
    clearHistory,
    markAllAsRead,
    markAsRead
  } = useNotification();

  const [selectedCategory, setSelectedCategory] = useState<string>('ALL');

  useEffect(() => {
    setPageTarget(pageMode ? document.getElementById('tenant-notifications-root') : null);
    if (pageMode) setSelectedCategory('ALL');
  }, [pageMode]);

  // Handle ESC key to close drawer and restore focus
  useEffect(() => {
    if (!isDrawerOpen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setIsDrawerOpen(false);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isDrawerOpen, setIsDrawerOpen]);

  const filteredHistory = history.filter(item => {
    if (selectedCategory === 'ALL') return true;
    return item.category === selectedCategory;
  });

  const handleNotificationClick = async (item: NotificationHistoryItem) => {
    await activateNotificationItem(item, {
      markAsRead,
      closeDrawer: () => setIsDrawerOpen(false),
      navigate
    });
  };

  const getIcon = (type: string) => {
    switch (type) {
      case 'success':
        return <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" aria-hidden="true" />;
      case 'ai_magic':
        return <Sparkles className="w-4 h-4 text-cyan-300 shrink-0" aria-hidden="true" />;
      case 'warning':
        return <AlertCircle className="w-4 h-4 text-amber-400 shrink-0" aria-hidden="true" />;
      case 'error':
        return <XCircle className="w-4 h-4 text-rose-400 shrink-0" aria-hidden="true" />;
      case 'info':
      default:
        return <Info className="w-4 h-4 text-sky-400 shrink-0" aria-hidden="true" />;
    }
  };

  const getCategoryBadgeStyle = (category: NotificationCategory) => {
    switch (category) {
      case 'AI_ENGINE':
        return 'text-cyan-300 bg-cyan-950/80 border-cyan-700/80';
      case 'PROPERTY':
        return 'text-emerald-300 bg-emerald-950/80 border-emerald-700/80';
      case 'PAYROLL':
        return 'text-amber-300 bg-amber-950/80 border-amber-700/80';
      case 'APPROVAL':
        return 'text-purple-300 bg-purple-950/80 border-purple-700/80';
      case 'SYSTEM':
      default:
        return 'text-slate-300 bg-slate-900 border-slate-700';
    }
  };

  const getActionLabel = (item: NotificationHistoryItem) => {
    return resolveNotificationActionLabel(item);
  };

  const panel = (
          <motion.aside
            initial={pageMode ? false : shouldReduceMotion ? { opacity: 0 } : { x: '100%' }}
            animate={pageMode ? undefined : shouldReduceMotion ? { opacity: 1 } : { x: 0 }}
            exit={pageMode ? undefined : shouldReduceMotion ? { opacity: 0 } : { x: '100%' }}
            transition={{ type: 'spring', stiffness: 350, damping: 30 }}
            role={pageMode ? undefined : 'dialog'}
            aria-modal={pageMode ? undefined : true}
            aria-label={pageMode ? 'Notifications' : 'Notifications Panel'}
            className={`${pageMode ? 'tenant-v0-notifications-page' : 'fixed top-0 right-0 h-full w-full max-w-md border-l shadow-2xl z-[9995]'} bg-slate-950 text-white border-slate-800 flex flex-col justify-between overflow-hidden ${consumerSurface ? 'tenant-v0-notifications' : ''}`}
          >
            {/* AMBIENT AURORA BACKGROUND MESH */}
            <div className="absolute -top-32 -right-32 w-80 h-80 bg-emerald-500/10 rounded-full blur-3xl pointer-events-none" />
            <div className="absolute -bottom-32 -left-32 w-80 h-80 bg-cyan-500/10 rounded-full blur-3xl pointer-events-none" />

            {/* HEADER */}
            <div className="relative z-10 border-b border-slate-800/80 p-4 sm:p-5">
              {pageMode && <div className="tenant-v0-notifications-count">{unreadCount} unread</div>}
              {!pageMode && <div className="flex items-center justify-between gap-3">
                <div className="flex min-w-0 items-center gap-2.5 sm:gap-3">
                  <div className="w-9 h-9 shrink-0 rounded-2xl bg-emerald-500/20 text-emerald-400 border border-emerald-500/40 flex items-center justify-center font-bold shadow-lg shadow-emerald-500/20 sm:h-10 sm:w-10">
                    <Bell className="w-5 h-5" aria-hidden="true" />
                  </div>
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-1.5 sm:gap-2">
                      <h2 className="text-base font-black font-['Outfit'] text-white sm:text-lg">Notifications</h2>
                      {unreadCount > 0 && (
                        <span className="text-[10px] font-mono font-black text-emerald-300 bg-emerald-950 px-2 py-0.5 rounded-full border border-emerald-500/40">
                          {unreadCount} New
                        </span>
                      )}
                    </div>
                    <span className="block truncate text-[11px] text-slate-400 font-mono sm:text-xs">Updates and activity history</span>
                  </div>
                </div>

                <button
                  type="button"
                  onClick={() => setIsDrawerOpen(false)}
                  aria-label="Close notifications panel"
                  className="min-h-11 min-w-11 p-2.5 rounded-xl bg-slate-900 text-slate-400 hover:text-white hover:bg-slate-800 border border-slate-800 transition-all cursor-pointer flex items-center justify-center focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500"
                >
                  <X className="w-5 h-5" aria-hidden="true" />
                </button>
              </div>}

              {/* ACTION BAR */}
              <div className="flex items-center justify-between gap-2 mt-4 pt-3 border-t border-slate-800/60">
                <button
                  type="button"
                  onClick={markAllAsRead}
                  disabled={unreadCount === 0}
                  className="min-h-11 px-3 py-1.5 bg-slate-900 hover:bg-slate-800 disabled:opacity-50 text-emerald-300 text-xs font-bold font-mono rounded-xl border border-slate-800 transition-all cursor-pointer flex items-center gap-1.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500"
                >
                  <CheckCheck className="w-3.5 h-3.5" aria-hidden="true" />
                  <span>Mark All Read</span>
                </button>

                {consumerSurface ? <button type="button" onClick={() => void refetchNotifications()}
                  disabled={isLoading}
                  className="min-h-11 px-3 py-1.5 bg-slate-900 hover:bg-slate-800 disabled:opacity-50 text-emerald-300 text-xs font-bold font-mono rounded-xl border border-slate-800 transition-all cursor-pointer flex items-center gap-1.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500">
                  <RefreshCw className="w-3.5 h-3.5" aria-hidden="true" /><span>Refresh</span>
                </button> : <button type="button" onClick={clearHistory} disabled={history.length === 0}
                  className="min-h-11 px-3 py-1.5 bg-slate-900 hover:bg-slate-800 disabled:opacity-50 text-rose-300 text-xs font-bold font-mono rounded-xl border border-slate-800 transition-all cursor-pointer flex items-center gap-1.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-500">
                  <Trash2 className="w-3.5 h-3.5" aria-hidden="true" /><span>Clear Log</span>
                </button>}
              </div>

              {/* CATEGORY FILTER TABS */}
              <div className="flex items-center gap-1.5 overflow-x-auto pb-1 mt-3 no-scrollbar" role="tablist">
                {(consumerSurface ? ['ALL', 'VISIT_SESSION', 'PROPERTY', 'SYSTEM', 'AI_ENGINE'] : ['ALL', 'PROPERTY', 'AI_ENGINE', 'SYSTEM']).map(cat => {
                  const isActive = selectedCategory === cat;
                  return (
                    <button
                      key={cat}
                      type="button"
                      role="tab"
                      aria-selected={isActive}
                      onClick={() => setSelectedCategory(cat)}
                      className={`min-h-9 px-3 py-1 rounded-xl text-[10px] font-mono font-bold transition-all cursor-pointer whitespace-nowrap border focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 ${
                        isActive
                          ? 'bg-emerald-950 text-emerald-300 border-emerald-500/60 font-black shadow-sm'
                          : 'bg-slate-900/80 text-slate-400 border-slate-800 hover:text-slate-200'
                      }`}
                    >
                      {cat === 'ALL' ? 'All' :
                       cat === 'VISIT_SESSION' ? 'Visits' :
                       cat === 'PROPERTY' ? 'Properties' :
                       cat === 'AI_ENGINE' ? 'AI Engine' : 'System'}
                    </button>
                  );
                })}
              </div>
            </div>

            {/* ERROR FEEDBACK BANNER */}
            {markReadError && (
              <div role="alert" className="mx-4 mt-3 p-3 rounded-xl border border-rose-500/40 bg-rose-950/80 text-rose-200 text-xs flex items-center justify-between gap-2">
                <span>{markReadError}</span>
                <button
                  type="button"
                  onClick={clearMarkReadError}
                  aria-label="Dismiss error"
                  className="p-1 rounded-lg hover:bg-rose-900/60 text-rose-300"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
            )}

            {/* NOTIFICATION HISTORY ITEM LIST */}
            <div className="flex-1 overflow-y-auto p-4 space-y-3 relative z-10">
              {/* LOADING SKELETON */}
              {isLoading && history.length === 0 ? (
                <div className="space-y-3" aria-busy="true" aria-label="Loading notifications">
                  {[1, 2, 3].map(i => (
                    <div key={i} className="p-4 rounded-2xl border border-slate-800/80 bg-slate-900/40 animate-pulse space-y-2.5">
                      <div className="h-4 bg-slate-800 rounded w-1/3" />
                      <div className="h-3 bg-slate-800/60 rounded w-3/4" />
                      <div className="h-3 bg-slate-800/40 rounded w-1/4" />
                    </div>
                  ))}
                </div>
              ) : fetchError && history.length === 0 ? (
                /* FETCH ERROR STATE */
                <div role="alert" className="h-64 flex flex-col items-center justify-center text-center p-6 text-slate-400 font-mono space-y-3">
                  <AlertCircle className="w-10 h-10 text-rose-400" aria-hidden="true" />
                  <p className="text-sm font-bold text-white">We couldn't load your notifications.</p>
                  <button
                    type="button"
                    onClick={() => void refetchNotifications()}
                    className="min-h-11 px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-slate-950 text-xs font-bold rounded-xl flex items-center gap-2 cursor-pointer transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400"
                  >
                    <RefreshCw className="w-4 h-4" />
                    <span>Retry</span>
                  </button>
                </div>
              ) : filteredHistory.length === 0 ? (
                /* EMPTY STATE — EXACT PRODUCT SPEC */
                <div className="h-64 flex flex-col items-center justify-center text-center p-6 text-slate-500 font-mono space-y-2">
                  <Bell className="w-10 h-10 text-slate-700" aria-hidden="true" />
                  <p className="text-sm font-bold text-slate-300">You're all caught up.</p>
                  <span className="text-xs text-slate-500 max-w-xs">Updates about your {consumerSurface ? 'visits, homes, and account' : 'properties'} will appear here.</span>
                </div>
              ) : (
                /* NOTIFICATION CARDS */
                filteredHistory.map(item => (
                  <motion.article
                    key={item.id}
                    onClick={() => void handleNotificationClick(item)}
                    initial={shouldReduceMotion ? { opacity: 0 } : { opacity: 0, y: 8 }}
                    animate={{ opacity: 1, y: 0 }}
                    tabIndex={0}
                    role="button"
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        void handleNotificationClick(item);
                      }
                    }}
                    className={`p-4 rounded-2xl border transition-all cursor-pointer relative focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 ${
                      item.read
                        ? 'bg-slate-950/60 border-slate-800/80 opacity-75'
                        : 'bg-slate-900/90 border-slate-700 shadow-md ring-1 ring-emerald-500/20'
                    }`}
                  >
                    <div className="flex items-start gap-3">
                      <div className="mt-0.5">{getIcon(item.type)}</div>
                      <div className="space-y-1.5 min-w-0 flex-1">
                        <div className="flex items-center gap-2 flex-wrap justify-between pr-2">
                          <div className="flex items-center gap-2">
                            {!item.read && (
                              <span className="flex items-center gap-1">
                                <span className="h-2 w-2 rounded-full bg-emerald-400" aria-hidden="true" />
                                <span className="sr-only">Unread: </span>
                              </span>
                            )}
                            <span className="font-['Outfit'] font-extrabold text-xs text-white">
                              {item.title}
                            </span>
                          </div>
                          <span className={`text-[9px] font-mono font-bold px-2 py-0.5 rounded-md border ${getCategoryBadgeStyle(item.category)}`}>
                            {item.category}
                          </span>
                        </div>

                        <p className="text-xs text-slate-300 font-sans leading-relaxed">
                          {item.message}
                        </p>

                        {item.details && (
                          <div className="mt-1.5 p-2 bg-slate-950/80 rounded-xl border border-slate-800/80 text-[10px] font-mono text-slate-400 leading-relaxed">
                            {item.details}
                          </div>
                        )}

                        <div className="flex items-center justify-between gap-2 pt-1 text-[10px] font-mono text-slate-500">
                          <span>{formatTimeAgo(new Date(item.createdAt))}</span>

                          {(item.actionTarget || item.actionType || (item.category === 'VISIT_SESSION' && item.targetRole === 'TENANT')) && (
                            <span className="inline-flex min-h-[44px] items-center gap-1 font-semibold text-emerald-400 hover:text-emerald-300">
                              <span>{getActionLabel(item)}</span>
                              <ArrowRight className="w-3.5 h-3.5" aria-hidden="true" />
                            </span>
                          )}
                        </div>
                      </div>
                    </div>
                  </motion.article>
                ))
              )}
            </div>

            {/* FOOTER */}
            <div className="p-4 border-t border-slate-800/80 bg-slate-950/90 text-center font-mono text-[10px] text-slate-500 relative z-10">
              Your Dreams, Our Efforts.
            </div>
          </motion.aside>
  );
  if (pageMode) return pageTarget ? createPortal(panel, pageTarget) : null;
  return (
    <AnimatePresence>
      {isDrawerOpen && <>
        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
          onClick={() => setIsDrawerOpen(false)}
          className="fixed inset-0 bg-slate-950/70 backdrop-blur-md z-[9990]" aria-hidden="true" />
        {panel}
      </>}
    </AnimatePresence>
  );
};
