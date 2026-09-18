import React from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { AlertTriangle, RotateCcw, X } from 'lucide-react';
import { useNotification } from '../context/NotificationContext';

const TECHNICAL_ERROR_DETAIL_PATTERN = /\b(?:sql|jdbc|postgres|hibernate|exception|stack\s*trace|org\.|java\.|constraint|failed\s+row|insert\s+into|select\s+.+\s+from|update\s+.+\s+set|delete\s+from|could\s+not\s+execute\s+statement)\b/i;

const getCustomerSafeDetails = (details?: string): string | undefined => {
  if (!details || TECHNICAL_ERROR_DETAIL_PATTERN.test(details)) return undefined;
  return details;
};

export const AppErrorDialog: React.FC = () => {
  const { errorDialog, dismissErrorDialog } = useNotification();
  const safeDetails = getCustomerSafeDetails(errorDialog?.details);

  return (
    <AnimatePresence>
      {errorDialog && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          className="fixed inset-0 z-[10000] flex items-start justify-center overflow-y-auto bg-slate-950/80 p-3 backdrop-blur-md sm:items-center sm:p-4"
          onClick={dismissErrorDialog}
        >
          <motion.section
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="app-error-dialog-title"
            initial={{ opacity: 0, scale: 0.84, rotateX: 14, y: 18 }}
            animate={{ opacity: 1, scale: 1, rotateX: 0, y: 0 }}
            exit={{ opacity: 0, scale: 0.84, rotateX: 14, y: 18 }}
            transition={{ type: 'spring', stiffness: 480, damping: 24 }}
            className="my-auto max-h-[calc(100dvh-1.5rem)] w-full max-w-lg overflow-x-hidden overflow-y-auto rounded-3xl border border-rose-500/50 bg-slate-900 text-white shadow-2xl shadow-rose-950/50 sm:max-h-[calc(100dvh-2rem)]"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="h-1 bg-gradient-to-r from-rose-500 via-amber-400 to-rose-500" />

            <div className="p-5 sm:p-6">
              <div className="flex items-start justify-between gap-4">
                <div className="flex items-start gap-3">
                  <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl border border-rose-400/40 bg-rose-500/15 text-rose-300">
                    <AlertTriangle className="h-5 w-5" />
                  </div>
                  <div>
                    <h2 id="app-error-dialog-title" className="font-['Outfit'] text-lg font-black text-white">
                      {errorDialog.title}
                    </h2>
                    <p className="mt-1 text-sm leading-relaxed text-slate-300">{errorDialog.message}</p>
                  </div>
                </div>

                <button
                  type="button"
                  onClick={dismissErrorDialog}
                  aria-label="Close message"
                  className="rounded-xl p-2 text-slate-400 transition-colors hover:bg-slate-800 hover:text-white"
                >
                  <X className="h-4 w-4" />
                </button>
              </div>

              {safeDetails && (
                <div className="mt-4 rounded-2xl border border-slate-700 bg-slate-950/70 p-3 text-xs leading-relaxed text-slate-300">
                  {safeDetails}
                </div>
              )}

              <div className="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
                <button
                  type="button"
                  onClick={dismissErrorDialog}
                  className="rounded-xl border border-slate-700 bg-slate-800 px-4 py-2.5 text-xs font-bold text-slate-200 transition-colors hover:bg-slate-700"
                >
                  Close
                </button>
                {errorDialog.action && (
                  <button
                    type="button"
                    onClick={() => {
                      const action = errorDialog.action;
                      if (!action) return;
                      dismissErrorDialog();
                      action.onClick();
                    }}
                    className="flex items-center justify-center gap-2 rounded-xl bg-rose-600 px-4 py-2.5 text-xs font-black text-white shadow-lg shadow-rose-950/40 transition-colors hover:bg-rose-500"
                  >
                    <RotateCcw className="h-3.5 w-3.5" />
                    {errorDialog.action.label}
                  </button>
                )}
              </div>
            </div>
          </motion.section>
        </motion.div>
      )}
    </AnimatePresence>
  );
};
