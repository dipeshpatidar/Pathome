import React, { useState } from 'react';
import {
  motion,
  AnimatePresence
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
  Trash2
} from 'lucide-react';
import { useNotification, NotificationCategory } from '../context/NotificationContext';

export const NotificationCenterDrawer: React.FC = () => {
  const {
    history,
    unreadCount,
    isDrawerOpen,
    setIsDrawerOpen,
    clearHistory,
    markAllAsRead,
    markAsRead
  } = useNotification();

  const [selectedCategory, setSelectedCategory] = useState<string>('ALL');

  const filteredHistory = history.filter(item => {
    if (selectedCategory === 'ALL') return true;
    return item.category === selectedCategory;
  });

  const getIcon = (type: string) => {
    switch (type) {
      case 'success':
        return <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />;
      case 'ai_magic':
        return <Sparkles className="w-4 h-4 text-cyan-300 animate-pulse shrink-0" />;
      case 'warning':
        return <AlertCircle className="w-4 h-4 text-amber-400 shrink-0" />;
      case 'error':
        return <XCircle className="w-4 h-4 text-rose-400 shrink-0" />;
      case 'info':
      default:
        return <Info className="w-4 h-4 text-sky-400 shrink-0" />;
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

  return (
    <AnimatePresence>
      {isDrawerOpen && (
        <>
          {/* BACKDROP OVERLAY */}
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={() => setIsDrawerOpen(false)}
            className="fixed inset-0 bg-slate-950/70 backdrop-blur-md z-[9990]"
          />

          {/* SLIDE-OVER NOTIFICATION CENTER DRAWER */}
          <motion.aside
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ type: 'spring', stiffness: 400, damping: 32 }}
            className="fixed top-0 right-0 h-full w-full max-w-md bg-slate-950 text-white border-l border-slate-800 shadow-2xl z-[9995] flex flex-col justify-between overflow-hidden"
          >
            {/* AMBIENT AURORA BACKGROUND MESH */}
            <div className="absolute -top-32 -right-32 w-80 h-80 bg-emerald-500/10 rounded-full blur-3xl pointer-events-none" />
            <div className="absolute -bottom-32 -left-32 w-80 h-80 bg-cyan-500/10 rounded-full blur-3xl pointer-events-none" />

            {/* HEADER */}
            <div className="relative z-10 border-b border-slate-800/80 p-4 sm:p-5">
              <div className="flex items-center justify-between gap-3">
                <div className="flex min-w-0 items-center gap-2.5 sm:gap-3">
                  <div className="w-9 h-9 shrink-0 rounded-2xl bg-emerald-500/20 text-emerald-400 border border-emerald-500/40 flex items-center justify-center font-bold shadow-lg shadow-emerald-500/20 sm:h-10 sm:w-10">
                    <Bell className="w-5 h-5" />
                  </div>
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-1.5 sm:gap-2">
                      <h2 className="text-base font-black font-['Outfit'] text-white sm:text-lg">Notifications</h2>
                      {unreadCount > 0 && (
                        <span className="text-[10px] font-mono font-black text-emerald-300 bg-emerald-950 px-2 py-0.5 rounded-full border border-emerald-500/40 animate-pulse">
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
                  className="p-2 rounded-xl bg-slate-900 text-slate-400 hover:text-white hover:bg-slate-800 border border-slate-800 transition-all cursor-pointer"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>

              {/* ACTION BAR */}
              <div className="flex items-center justify-between gap-2 mt-4 pt-3 border-t border-slate-800/60">
                <button
                  type="button"
                  onClick={markAllAsRead}
                  disabled={unreadCount === 0}
                  className="px-3 py-1.5 bg-slate-900 hover:bg-slate-800 disabled:opacity-50 text-emerald-300 text-xs font-bold font-mono rounded-xl border border-slate-800 transition-all cursor-pointer flex items-center gap-1.5"
                >
                  <CheckCheck className="w-3.5 h-3.5" />
                  <span>Mark All Read</span>
                </button>

                <button
                  type="button"
                  onClick={clearHistory}
                  disabled={history.length === 0}
                  className="px-3 py-1.5 bg-slate-900 hover:bg-slate-800 disabled:opacity-50 text-rose-300 text-xs font-bold font-mono rounded-xl border border-slate-800 transition-all cursor-pointer flex items-center gap-1.5"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                  <span>Clear Log</span>
                </button>
              </div>

              {/* CATEGORY FILTER TABS */}
              <div className="flex items-center gap-1.5 overflow-x-auto pb-1 mt-3 no-scrollbar">
                {['ALL', 'AI_ENGINE', 'PROPERTY', 'PAYROLL', 'APPROVAL', 'SYSTEM'].map(cat => {
                  const isActive = selectedCategory === cat;
                  return (
                    <button
                      key={cat}
                      type="button"
                      onClick={() => setSelectedCategory(cat)}
                      className={`px-3 py-1 rounded-xl text-[10px] font-mono font-bold transition-all cursor-pointer whitespace-nowrap border ${
                        isActive
                          ? 'bg-emerald-950 text-emerald-300 border-emerald-500/60 font-black shadow-sm'
                          : 'bg-slate-900/80 text-slate-400 border-slate-800 hover:text-slate-200'
                      }`}
                    >
                      {cat === 'ALL' ? '🌐 All' :
                       cat === 'AI_ENGINE' ? '⚡ AI Engine' :
                       cat === 'PROPERTY' ? '🏢 Properties' :
                       cat === 'PAYROLL' ? '💸 Payroll' :
                       cat === 'APPROVAL' ? '💰 Approvals' : '⚙️ System'}
                    </button>
                  );
                })}
              </div>
            </div>

            {/* NOTIFICATION HISTORY ITEM LIST */}
            <div className="flex-1 overflow-y-auto p-4 space-y-3 relative z-10">
              {filteredHistory.length === 0 ? (
                <div className="h-64 flex flex-col items-center justify-center text-center p-6 text-slate-500 font-mono space-y-2">
                  <Bell className="w-10 h-10 text-slate-700 animate-pulse" />
                  <p className="text-xs font-bold text-slate-400">No notifications in history</p>
                  <span className="text-[11px]">System events and action alerts will appear here in real-time</span>
                </div>
              ) : (
                filteredHistory.map(item => (
                  <motion.div
                    key={item.id}
                    onClick={() => markAsRead(item.id)}
                    initial={{ opacity: 0, y: 10 }}
                    animate={{ opacity: 1, y: 0 }}
                    className={`p-4 rounded-2xl border transition-all cursor-pointer relative ${
                      item.read
                        ? 'bg-slate-950/60 border-slate-800/80 opacity-75'
                        : 'bg-slate-900/90 border-slate-700 shadow-md ring-1 ring-emerald-500/20'
                    }`}
                  >
                    {!item.read && (
                      <span className="absolute top-4 right-4 w-2 h-2 rounded-full bg-emerald-400 animate-ping" />
                    )}

                    <div className="flex items-start gap-3">
                      <div className="mt-0.5">{getIcon(item.type)}</div>
                      <div className="space-y-1 min-w-0 flex-1">
                        <div className="flex items-center gap-2 flex-wrap justify-between pr-4">
                          <span className="font-['Outfit'] font-extrabold text-xs text-white">
                            {item.title}
                          </span>
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

                        <div className="text-[9px] font-mono text-slate-500 pt-1">
                          {new Date(item.createdAt).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                        </div>
                      </div>
                    </div>
                  </motion.div>
                ))
              )}
            </div>

            {/* FOOTER */}
            <div className="p-4 border-t border-slate-800/80 bg-slate-950/90 text-center font-mono text-[10px] text-slate-500 relative z-10">
              Pathome Centralized Event Bus & Real-time Notification Engine Active
            </div>
          </motion.aside>
        </>
      )}
    </AnimatePresence>
  );
};
