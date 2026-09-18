import React, { useCallback, useEffect, useRef, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import {
  AlertTriangle,
  RefreshCw,
  CheckCircle2,
  XCircle,
  UploadCloud,
  ExternalLink,
  Clock,
  RotateCcw,
  FileImage,
  Video
} from 'lucide-react';
import { failedUploadService, FailedUpload } from '../services/failedUploadService';
import { getErrorMessage } from '../services/apiError';

interface FailedUploadsPanelProps {
  /** Called after a successful retry or dismiss so the parent can refresh counts. */
  onCountChange?: () => void;
}

const STAGE_LABELS: Record<string, string> = {
  VALIDATION: 'File validation',
  CLOUDINARY_UPLOAD: 'Storage upload',
  DB_PERSIST: 'Database save'
};

const STATUS_CONFIG: Record<string, { label: string; color: string; bgColor: string }> = {
  FAILED: { label: 'Failed', color: 'text-rose-700', bgColor: 'bg-rose-50 border-rose-200' },
  RETRYING: { label: 'Retrying…', color: 'text-amber-700', bgColor: 'bg-amber-50 border-amber-200' },
  RESOLVED: { label: 'Resolved', color: 'text-emerald-700', bgColor: 'bg-emerald-50 border-emerald-200' },
  DISMISSED: { label: 'Dismissed', color: 'text-slate-500', bgColor: 'bg-slate-50 border-slate-200' }
};

const formatFileSize = (bytes: number | null): string => {
  if (!bytes) return '';
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
};

const formatRelativeTime = (isoString: string): string => {
  const diffMs = Date.now() - new Date(isoString).getTime();
  const diffMin = Math.floor(diffMs / 60000);
  if (diffMin < 1) return 'Just now';
  if (diffMin < 60) return `${diffMin} min ago`;
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) return `${diffHr}h ago`;
  return `${Math.floor(diffHr / 24)}d ago`;
};

export const FailedUploadsPanel: React.FC<FailedUploadsPanelProps> = ({ onCountChange }) => {
  const [failures, setFailures] = useState<FailedUpload[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionInProgress, setActionInProgress] = useState<Record<number, 'retrying' | 'dismissing'>>({});
  const [actionError, setActionError] = useState<Record<number, string>>({});
  const [actionSuccess, setActionSuccess] = useState<Record<number, string>>({});
  const retryFileInputRef = useRef<HTMLInputElement>(null);
  const [pendingRetryId, setPendingRetryId] = useState<number | null>(null);

  const load = useCallback(async () => {
    setIsLoading(true);
    setLoadError(null);
    try {
      const data = await failedUploadService.fetchUnresolved();
      setFailures(data);
    } catch (err) {
      setLoadError(getErrorMessage(err, 'Unable to load failed uploads.'));
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleDismiss = async (id: number) => {
    setActionInProgress(prev => ({ ...prev, [id]: 'dismissing' }));
    setActionError(prev => ({ ...prev, [id]: '' }));
    try {
      await failedUploadService.dismiss(id);
      setActionSuccess(prev => ({ ...prev, [id]: 'Dismissed' }));
      setTimeout(() => {
        setFailures(prev => prev.filter(f => f.id !== id));
        onCountChange?.();
      }, 800);
    } catch (err) {
      setActionError(prev => ({ ...prev, [id]: getErrorMessage(err, 'Unable to dismiss. Try again.') }));
    } finally {
      setActionInProgress(prev => { const next = { ...prev }; delete next[id]; return next; });
    }
  };

  const handleRetry = async (id: number, file?: File) => {
    setActionInProgress(prev => ({ ...prev, [id]: 'retrying' }));
    setActionError(prev => ({ ...prev, [id]: '' }));
    try {
      await failedUploadService.retry(id, file);
      setActionSuccess(prev => ({ ...prev, [id]: 'Uploaded successfully' }));
      setTimeout(() => {
        setFailures(prev => prev.filter(f => f.id !== id));
        onCountChange?.();
      }, 1000);
    } catch (err) {
      const msg = getErrorMessage(err, 'Unable to retry this upload.');
      setActionError(prev => ({ ...prev, [id]: msg }));
      // If file was needed, prompt the user to select one
      if (msg.toLowerCase().includes('replacement file') || msg.toLowerCase().includes('select')) {
        setPendingRetryId(id);
        retryFileInputRef.current?.click();
      }
    } finally {
      setActionInProgress(prev => { const next = { ...prev }; delete next[id]; return next; });
    }
  };

  const handleFileSelected = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file && pendingRetryId !== null) {
      handleRetry(pendingRetryId, file);
    }
    e.target.value = '';
    setPendingRetryId(null);
  };

  return (
    <div className="space-y-6">
      {/* Hidden file input for replacement file selection */}
      <input
        ref={retryFileInputRef}
        type="file"
        accept="image/*,video/*"
        className="hidden"
        onChange={handleFileSelected}
      />

      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-rose-50 border border-rose-200 flex items-center justify-center">
            <AlertTriangle className="w-5 h-5 text-rose-600" />
          </div>
          <div>
            <h2 className="text-lg font-black font-['Outfit'] text-slate-900">Failed Uploads</h2>
            <p className="text-xs text-slate-500 font-medium">
              {isLoading ? 'Loading…' : `${failures.length} unresolved ${failures.length === 1 ? 'issue' : 'issues'}`}
            </p>
          </div>
        </div>
        <button
          onClick={load}
          disabled={isLoading}
          className="flex items-center gap-2 px-3 py-2 text-xs font-bold text-slate-600 bg-white border border-slate-200 rounded-xl hover:bg-slate-50 active:scale-95 transition-all disabled:opacity-50 cursor-pointer"
        >
          <RefreshCw className={`w-3.5 h-3.5 ${isLoading ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>

      {/* Load Error */}
      {loadError && (
        <div className="flex items-center gap-3 p-4 bg-rose-50 border border-rose-200 rounded-2xl text-sm text-rose-700">
          <XCircle className="w-4 h-4 shrink-0" />
          {loadError}
        </div>
      )}

      {/* Empty State */}
      {!isLoading && !loadError && failures.length === 0 && (
        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          className="flex flex-col items-center justify-center py-20 text-center"
        >
          <div className="w-16 h-16 rounded-2xl bg-emerald-50 border border-emerald-200 flex items-center justify-center mb-4">
            <CheckCircle2 className="w-8 h-8 text-emerald-500" />
          </div>
          <h3 className="text-base font-black font-['Outfit'] text-slate-800 mb-1">All uploads are healthy</h3>
          <p className="text-sm text-slate-500 max-w-xs">
            No upload issues at this time. Failed uploads will appear here automatically.
          </p>
        </motion.div>
      )}

      {/* Failure List */}
      <AnimatePresence>
        {failures.map((failure, index) => {
          const statusCfg = STATUS_CONFIG[failure.status] ?? STATUS_CONFIG.FAILED;
          const isActing = failure.id in actionInProgress;
          const successMsg = actionSuccess[failure.id];
          const errorMsg = actionError[failure.id];

          return (
            <motion.div
              key={failure.id}
              initial={{ opacity: 0, y: 12 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.97 }}
              transition={{ delay: index * 0.04 }}
              className="bg-white border border-slate-200/80 rounded-2xl overflow-hidden shadow-sm hover:shadow-md transition-shadow"
            >
              {/* Card Header */}
              <div className="px-5 pt-5 pb-4">
                <div className="flex items-start justify-between gap-4">
                  <div className="flex items-start gap-3 min-w-0">
                    <div className="w-9 h-9 rounded-xl bg-slate-50 border border-slate-200 flex items-center justify-center shrink-0">
                      {failure.mediaType === 'VIDEO_WALKTHROUGH'
                        ? <Video className="w-4 h-4 text-indigo-500" />
                        : <FileImage className="w-4 h-4 text-teal-600" />}
                    </div>
                    <div className="min-w-0">
                      <p className="text-sm font-bold text-slate-900 truncate leading-tight">
                        {failure.originalFilename || 'Unnamed file'}
                      </p>
                      <p className="text-xs text-slate-500 mt-0.5">
                        {failure.mediaType === 'VIDEO_WALKTHROUGH' ? 'Video' : 'Image'}
                        {failure.fileSizeBytes ? ` · ${formatFileSize(failure.fileSizeBytes)}` : ''}
                        {failure.roomTag ? ` · ${failure.roomTag.replace('_', ' ').toLowerCase()}` : ''}
                      </p>
                    </div>
                  </div>

                  <span className={`shrink-0 px-2.5 py-1 rounded-full text-[10px] font-bold border ${statusCfg.bgColor} ${statusCfg.color}`}>
                    {statusCfg.label}
                  </span>
                </div>

                {/* Failure Reason */}
                <div className="mt-3 p-3 bg-rose-50/70 border border-rose-100 rounded-xl">
                  <p className="text-xs font-semibold text-rose-800 leading-snug">{failure.failureReason}</p>
                  <p className="text-[10px] text-rose-500 mt-1 font-medium">
                    Stage: {STAGE_LABELS[failure.failureStage] ?? failure.failureStage}
                    {failure.retryCount > 0 ? ` · ${failure.retryCount} retry attempt${failure.retryCount > 1 ? 's' : ''}` : ''}
                  </p>
                </div>

                {/* Meta Row */}
                <div className="flex items-center gap-3 mt-3 flex-wrap">
                  {failure.listingId && (
                    <span className="inline-flex items-center gap-1 text-[10px] font-mono text-slate-500 bg-slate-50 border border-slate-200 px-2 py-0.5 rounded-lg">
                      Listing #{failure.listingId}
                    </span>
                  )}
                  <span className="inline-flex items-center gap-1 text-[10px] text-slate-400">
                    <Clock className="w-3 h-3" />
                    {formatRelativeTime(failure.createdAt)}
                  </span>
                </div>

                {/* Action Feedback */}
                <AnimatePresence>
                  {successMsg && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      exit={{ opacity: 0, height: 0 }}
                      className="mt-3 flex items-center gap-2 text-xs text-emerald-700 bg-emerald-50 border border-emerald-200 px-3 py-2 rounded-xl font-medium"
                    >
                      <CheckCircle2 className="w-3.5 h-3.5" />
                      {successMsg}
                    </motion.div>
                  )}
                  {errorMsg && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      exit={{ opacity: 0, height: 0 }}
                      className="mt-3 flex items-center gap-2 text-xs text-rose-700 bg-rose-50 border border-rose-200 px-3 py-2 rounded-xl font-medium"
                    >
                      <XCircle className="w-3.5 h-3.5" />
                      {errorMsg}
                    </motion.div>
                  )}
                </AnimatePresence>
              </div>

              {/* Action Footer */}
              <div className="px-5 pb-5 flex items-center gap-2 flex-wrap">
                {/* Retry */}
                <button
                  id={`retry-failed-upload-${failure.id}`}
                  onClick={() => handleRetry(failure.id)}
                  disabled={isActing}
                  className="flex items-center gap-1.5 px-3 py-2 text-xs font-bold text-white bg-gradient-to-r from-emerald-600 to-teal-600 rounded-xl hover:from-emerald-700 hover:to-teal-700 active:scale-95 transition-all disabled:opacity-50 cursor-pointer shadow-sm"
                >
                  {actionInProgress[failure.id] === 'retrying'
                    ? <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                    : <UploadCloud className="w-3.5 h-3.5" />}
                  {actionInProgress[failure.id] === 'retrying' ? 'Retrying…' : 'Retry upload'}
                </button>

                {/* Retry with replacement file */}
                <button
                  id={`retry-with-file-${failure.id}`}
                  onClick={() => { setPendingRetryId(failure.id); retryFileInputRef.current?.click(); }}
                  disabled={isActing}
                  className="flex items-center gap-1.5 px-3 py-2 text-xs font-bold text-slate-700 bg-white border border-slate-200 rounded-xl hover:bg-slate-50 active:scale-95 transition-all disabled:opacity-50 cursor-pointer"
                >
                  <RotateCcw className="w-3.5 h-3.5" />
                  Replace file
                </button>

                {/* Open property */}
                {failure.listingId && (
                  <a
                    href={`/admin/property/${failure.listingId}`}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="flex items-center gap-1.5 px-3 py-2 text-xs font-bold text-indigo-700 bg-indigo-50 border border-indigo-200 rounded-xl hover:bg-indigo-100 transition-all"
                  >
                    <ExternalLink className="w-3.5 h-3.5" />
                    Open property
                  </a>
                )}

                {/* Dismiss */}
                <button
                  id={`dismiss-failed-upload-${failure.id}`}
                  onClick={() => handleDismiss(failure.id)}
                  disabled={isActing}
                  className="ml-auto flex items-center gap-1.5 px-3 py-2 text-xs font-bold text-slate-500 hover:text-rose-600 hover:bg-rose-50 border border-transparent hover:border-rose-200 rounded-xl transition-all disabled:opacity-50 cursor-pointer"
                >
                  {actionInProgress[failure.id] === 'dismissing'
                    ? <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                    : <XCircle className="w-3.5 h-3.5" />}
                  Dismiss
                </button>
              </div>
            </motion.div>
          );
        })}
      </AnimatePresence>
    </div>
  );
};
