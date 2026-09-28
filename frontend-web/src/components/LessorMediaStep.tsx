import React, { useEffect, useMemo, useState } from 'react';
import {
  AlertCircle,
  ArrowDown,
  ArrowUp,
  Camera,
  Check,
  Film,
  LoaderCircle,
  RefreshCw,
  Star,
  Trash2
} from 'lucide-react';
import { getErrorMessage } from '../services/apiError';
import { LessorMediaItem, lessorMediaService } from '../services/lessorMediaService';
import {
  classifyMediaError,
  generateMediaId,
  hasCoverImage,
  movedMediaIds,
  normalizeMediaType
} from '../utils/lessorMedia';
import { LessorMediaAsset } from './LessorMediaAsset';

interface LocalUpload {
  id: string;
  file: File;
  previewUrl?: string;
  progress: number;
  status: 'preparing' | 'uploading' | 'retrying' | 'failed';
  error?: string;
  retryable?: boolean;
  isVideo: boolean;
}

const SECONDARY =
  'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-3 text-sm font-semibold text-slate-700 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 disabled:opacity-50 cursor-pointer';

export function LessorMediaStep({
  draftId,
  onNext,
  guest = false,
  onMediaChange,
  onActiveUploadsChange
}: {
  draftId: string;
  onNext: () => void;
  guest?: boolean;
  onMediaChange?: (items: LessorMediaItem[]) => void;
  onActiveUploadsChange?: (hasActive: boolean) => void;
}) {
  const [items, setItems] = useState<LessorMediaItem[]>([]);
  const [local, setLocal] = useState<LocalUpload[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const uploaded = useMemo(
    () => items.filter(item => item.status === 'UPLOADED' || item.status === 'STAGED'),
    [items]
  );
  const hasImage = hasCoverImage(uploaded);

  useEffect(() => {
    onMediaChange?.(items);
  }, [items, onMediaChange]);

  useEffect(() => {
    const hasActive = local.some(
      row => row.status === 'uploading' || row.status === 'preparing' || row.status === 'retrying'
    );
    onActiveUploadsChange?.(hasActive);
  }, [local, onActiveUploadsChange]);

  useEffect(() => {
    return () => {
      local.forEach(row => {
        if (row.previewUrl) URL.revokeObjectURL(row.previewUrl);
      });
    };
  }, [local]);

  const refresh = async () => {
    const loaded = await lessorMediaService.list(draftId, guest);
    setItems(loaded);
    return loaded;
  };

  useEffect(() => {
    let live = true;
    setLoading(true);
    lessorMediaService
      .list(draftId, guest)
      .then(async loaded => {
        if (!live) return;
        setItems(loaded);
        const recoverable = loaded.filter(item => item.status === 'FAILED' || item.status === 'PENDING');
        if (recoverable.length) {
          await Promise.allSettled(recoverable.map(item => lessorMediaService.recover(draftId, item.mediaId, guest)));
          if (live) setItems(await lessorMediaService.list(draftId, guest));
        }
      })
      .catch(cause => {
        if (live) setError(getErrorMessage(cause, 'Could not load your media.'));
      })
      .finally(() => {
        if (live) setLoading(false);
      });
    return () => {
      live = false;
    };
  }, [draftId, guest]);

  const uploadOne = async (row: LocalUpload) => {
    setLocal(previous =>
      previous.map(item => (item.id === row.id ? { ...item, status: 'uploading', error: undefined } : item))
    );
    try {
      const saved = await lessorMediaService.upload(
        draftId,
        row.id,
        row.file,
        progress => {
          setLocal(previous =>
            previous.map(item => (item.id === row.id ? { ...item, progress } : item))
          );
        },
        guest
      );
      if (row.previewUrl) URL.revokeObjectURL(row.previewUrl);
      setItems(previous =>
        [...previous.filter(item => item.mediaId !== saved.mediaId), saved].sort(
          (a, b) => a.sortOrder - b.sortOrder
        )
      );
      setLocal(previous => previous.filter(item => item.id !== row.id));
    } catch (cause) {
      const { message, retryable } = classifyMediaError(cause, row.file, row.isVideo);
      setLocal(previous =>
        previous.map(item =>
          item.id === row.id ? { ...item, status: 'failed', error: message, retryable } : item
        )
      );
    }
  };

  const addFiles = (files: FileList | null) => {
    if (!files) return;
    for (const file of Array.from(files)) {
      const id = generateMediaId();
      const { isVideo, supported } = normalizeMediaType(file);
      let previewUrl: string | undefined;
      if (!isVideo) {
        try {
          previewUrl = URL.createObjectURL(file);
        } catch {
          // Object URL not supported or failed
        }
      }
      const row: LocalUpload = {
        id,
        file,
        previewUrl,
        progress: 0,
        status: 'preparing',
        isVideo
      };

      if (!supported) {
        const errorMsg = isVideo
          ? "This video format isn't supported. Choose another video."
          : "This file type isn't supported. Choose another image.";
        setLocal(previous => [...previous, { ...row, status: 'failed', error: errorMsg, retryable: false }]);
        continue;
      }

      if (file.size === 0) {
        setLocal(previous => [
          ...previous,
          { ...row, status: 'failed', error: "We couldn't read this file. Choose another file.", retryable: false }
        ]);
        continue;
      }

      const maxBytes = (isVideo ? 100 : 10) * 1024 * 1024;
      if (file.size > maxBytes) {
        const errorMsg = isVideo
          ? 'This video is too large. Maximum size is 100 MB.'
          : 'This image is too large. Maximum size is 10 MB.';
        setLocal(previous => [...previous, { ...row, status: 'failed', error: errorMsg, retryable: false }]);
        continue;
      }

      setLocal(previous => [...previous, row]);
      void uploadOne(row);
    }
  };

  const retry = async (row: LocalUpload) => {
    if (row.retryable === false) return;
    setLocal(previous =>
      previous.map(item => (item.id === row.id ? { ...item, status: 'retrying', error: undefined } : item))
    );
    try {
      const recovered = await lessorMediaService.recover(draftId, row.id, guest);
      if (row.previewUrl) URL.revokeObjectURL(row.previewUrl);
      setItems(previous =>
        [...previous.filter(item => item.mediaId !== recovered.mediaId), recovered].sort(
          (a, b) => a.sortOrder - b.sortOrder
        )
      );
      setLocal(previous => previous.filter(item => item.id !== row.id));
    } catch {
      await uploadOne(row);
    }
  };

  const removeLocal = (id: string) => {
    setLocal(previous => {
      const target = previous.find(item => item.id === id);
      if (target?.previewUrl) URL.revokeObjectURL(target.previewUrl);
      return previous.filter(item => item.id !== id);
    });
  };

  const changeCover = async (mediaId: string) => {
    setBusy(true);
    setError('');
    try {
      setItems(await lessorMediaService.cover(draftId, mediaId, guest));
    } catch (cause) {
      setError(getErrorMessage(cause, 'Could not change the cover.'));
    } finally {
      setBusy(false);
    }
  };

  const move = async (index: number, direction: -1 | 1) => {
    const ids = movedMediaIds(uploaded, index, direction);
    if (!ids) return;
    setBusy(true);
    setError('');
    try {
      setItems(await lessorMediaService.reorder(draftId, ids, guest));
    } catch (cause) {
      setError(getErrorMessage(cause, 'Could not change media order.'));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (mediaId: string) => {
    if (!window.confirm('Remove this file from your property draft?')) return;
    setBusy(true);
    setError('');
    try {
      await lessorMediaService.remove(draftId, mediaId, guest);
      await refresh();
    } catch (cause) {
      setError(getErrorMessage(cause, 'Could not remove this file. Retry to finish removal.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mt-2">
      <h1 className="font-['Outfit',sans-serif] text-3xl font-bold text-slate-950">Show the home</h1>
      <p className="mt-2 text-sm text-slate-600">
        Add at least one photo. The first successful photo becomes the cover; you can change it.
      </p>
      <label className="mt-6 flex min-h-24 cursor-pointer flex-col items-center justify-center gap-2 rounded-2xl border border-dashed border-emerald-400 bg-emerald-50 px-4 py-5 text-center text-sm font-semibold text-emerald-900 hover:bg-emerald-100 focus-within:ring-2 focus-within:ring-emerald-500">
        <Camera className="h-6 w-6" />
        Add photos or a video
        <input
          type="file"
          multiple
          accept="image/jpeg,image/png,image/webp,image/heic,image/heif,video/mp4,video/quicktime"
          className="sr-only"
          onChange={event => {
            addFiles(event.target.files);
            event.target.value = '';
          }}
        />
      </label>
      <p className="mt-2 text-xs text-slate-500">
        Photos up to 10 MB each; video up to 100 MB. One photo is required to submit.
      </p>
      {loading && (
        <div className="mt-6 flex items-center gap-2 text-sm text-slate-600">
          <LoaderCircle className="h-4 w-4 animate-spin" />
          Loading media…
        </div>
      )}
      {error && (
        <p role="alert" className="mt-5 rounded-xl bg-rose-50 p-3 text-sm text-rose-800">
          {error}{' '}
          <button
            className="underline"
            onClick={() => {
              void refresh().catch(cause => setError(getErrorMessage(cause, 'Still unavailable.')));
            }}
          >
            Retry
          </button>
        </p>
      )}
      {!guest && uploaded.some(item => item.status === 'STAGED') && (
        <button
          type="button"
          disabled={busy}
          className={`${SECONDARY} mt-5`}
          onClick={() => {
            setBusy(true);
            setError('');
            void lessorMediaService
              .promote(draftId)
              .then(refresh)
              .catch(cause => setError(getErrorMessage(cause, 'Could not prepare your photos. Retry.')))
              .finally(() => setBusy(false));
          }}
        >
          Prepare your photos
        </button>
      )}
      <div className="mt-6 grid gap-4 sm:grid-cols-2">
        {uploaded.map((item, index) => (
          <article
            key={item.mediaId}
            className="min-w-0 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm"
          >
            <div className="aspect-[4/3] bg-slate-100">
              <LessorMediaAsset
                url={item.url}
                contentType={item.contentType}
                alt={
                  item.contentType.startsWith('video/')
                    ? `Property video ${index + 1}`
                    : `Property photo ${index + 1}`
                }
                className={`h-full w-full ${
                  item.contentType.startsWith('video/') ? 'object-contain' : 'object-cover'
                }`}
              />
            </div>
            <div className="p-3">
              <div className="flex items-center justify-between gap-2">
                <p className="min-w-0 truncate text-xs text-slate-600" title={item.filename}>
                  {item.filename}
                </p>
                {item.cover && (
                  <span className="inline-flex items-center gap-1 rounded-full bg-emerald-700 px-2.5 py-0.5 text-xs font-bold text-white shadow-xs">
                    <Check className="h-3 w-3 stroke-[3]" />
                    Cover photo
                  </span>
                )}
              </div>
              <div className="mt-3 flex flex-wrap gap-2">
                {item.contentType.startsWith('image/') && !item.cover && (
                  <button
                    type="button"
                    disabled={busy}
                    className={SECONDARY}
                    onClick={() => {
                      void changeCover(item.mediaId);
                    }}
                  >
                    <Star className="h-4 w-4" />
                    Make cover
                  </button>
                )}
                <button
                  type="button"
                  disabled={busy || index === 0}
                  className={SECONDARY}
                  aria-label={`Move ${item.filename} up`}
                  onClick={() => {
                    void move(index, -1);
                  }}
                >
                  <ArrowUp className="h-4 w-4" />
                </button>
                <button
                  type="button"
                  disabled={busy || index === uploaded.length - 1}
                  className={SECONDARY}
                  aria-label={`Move ${item.filename} down`}
                  onClick={() => {
                    void move(index, 1);
                  }}
                >
                  <ArrowDown className="h-4 w-4" />
                </button>
                <button
                  type="button"
                  disabled={busy}
                  className={SECONDARY}
                  onClick={() => {
                    void remove(item.mediaId);
                  }}
                >
                  <Trash2 className="h-4 w-4" />
                  Remove
                </button>
              </div>
            </div>
          </article>
        ))}

        {local.map(row => (
          <article
            key={row.id}
            className={`min-w-0 overflow-hidden rounded-2xl border bg-white shadow-sm ${
              row.status === 'failed' ? 'border-rose-300 ring-1 ring-rose-200' : 'border-slate-200'
            }`}
          >
            <div className="aspect-[4/3] bg-slate-100 relative flex items-center justify-center overflow-hidden">
              {row.previewUrl ? (
                <img src={row.previewUrl} alt={row.file.name} className="h-full w-full object-cover" />
              ) : (
                <div className="flex flex-col items-center justify-center p-4 text-slate-400">
                  <Film className="h-10 w-10 text-slate-400 mb-1" />
                  <span className="text-xs text-slate-500 truncate max-w-[200px]">{row.file.name}</span>
                </div>
              )}
              <div className="absolute top-2.5 left-2.5">
                {row.status === 'uploading' && (
                  <span className="inline-flex items-center gap-1.5 rounded-full bg-slate-900/80 px-2.5 py-1 text-xs font-semibold text-white shadow-xs backdrop-blur-xs">
                    <LoaderCircle className="h-3 w-3 animate-spin text-emerald-400" />
                    <span>Uploading {row.progress > 0 ? `${row.progress}%` : '…'}</span>
                  </span>
                )}
                {row.status === 'retrying' && (
                  <span className="inline-flex items-center gap-1.5 rounded-full bg-slate-900/80 px-2.5 py-1 text-xs font-semibold text-white shadow-xs backdrop-blur-xs">
                    <RefreshCw className="h-3 w-3 animate-spin text-amber-400" />
                    <span>Retrying…</span>
                  </span>
                )}
                {row.status === 'preparing' && (
                  <span className="inline-flex items-center gap-1.5 rounded-full bg-slate-900/80 px-2.5 py-1 text-xs font-semibold text-white shadow-xs backdrop-blur-xs">
                    <LoaderCircle className="h-3 w-3 animate-spin text-slate-300" />
                    <span>Preparing…</span>
                  </span>
                )}
                {row.status === 'failed' && (
                  <span className="inline-flex items-center gap-1 rounded-full bg-rose-600 px-2.5 py-1 text-xs font-semibold text-white shadow-xs">
                    <AlertCircle className="h-3 w-3" />
                    <span>Upload failed</span>
                  </span>
                )}
              </div>
            </div>
            <div className="p-3">
              <p className="min-w-0 truncate text-xs font-medium text-slate-700" title={row.file.name}>
                {row.file.name}
              </p>
              {row.status === 'uploading' && (
                <div className="mt-2">
                  <div className="w-full bg-slate-100 rounded-full h-1.5 overflow-hidden">
                    <div
                      className="bg-emerald-600 h-1.5 rounded-full transition-all duration-200"
                      style={{ width: `${Math.max(5, row.progress)}%` }}
                    />
                  </div>
                </div>
              )}
              {row.status === 'failed' && (
                <div className="mt-2">
                  <p role="alert" className="text-xs font-medium text-rose-700 leading-relaxed break-words">
                    {row.error}
                  </p>
                  <div className="mt-3 flex flex-wrap gap-2">
                    {row.retryable !== false && (
                      <button
                        type="button"
                        className={SECONDARY}
                        onClick={() => {
                          void retry(row);
                        }}
                      >
                        <RefreshCw className="h-4 w-4" />
                        Retry
                      </button>
                    )}
                    <button type="button" className={SECONDARY} onClick={() => removeLocal(row.id)}>
                      <Trash2 className="h-4 w-4" />
                      Remove
                    </button>
                  </div>
                </div>
              )}
            </div>
          </article>
        ))}

        {items
          .filter(
            item =>
              item.status !== 'UPLOADED' && item.status !== 'STAGED' && !local.some(row => row.id === item.mediaId)
          )
          .map(item => (
            <div key={item.mediaId} className="rounded-xl border border-amber-200 bg-amber-50 p-4">
              <p className="truncate text-sm font-semibold text-slate-800">{item.filename}</p>
              <p className="mt-1 text-xs text-amber-800">
                {item.status === 'DELETING' ? 'Removal needs retry' : 'Upload needs recovery or original file'}
              </p>
              <div className="mt-3 flex flex-wrap gap-2">
                {item.status !== 'DELETING' && (
                  <button
                    className={SECONDARY}
                    onClick={() => {
                      void lessorMediaService
                        .recover(draftId, item.mediaId, guest)
                        .then(refresh)
                        .catch(cause =>
                          setError(
                            getErrorMessage(
                              cause,
                              'Automatic recovery unavailable. Select the original file to retry.'
                            )
                          )
                        );
                    }}
                  >
                    <RefreshCw className="h-4 w-4" />
                    Check upload
                  </button>
                )}
                <button
                  className={SECONDARY}
                  onClick={() => {
                    void remove(item.mediaId);
                  }}
                >
                  {item.status === 'DELETING' ? 'Retry removal' : 'Remove failed item'}
                </button>
              </div>
            </div>
          ))}
      </div>
      {hasImage && (
        <div className="mt-5 flex items-center gap-2 rounded-xl border border-emerald-200/80 bg-emerald-50 px-4 py-2.5 text-xs font-semibold text-emerald-950">
          <Check className="h-4 w-4 text-emerald-700 stroke-[3]" />
          <span>Cover photo ready</span>
        </div>
      )}
      <button
        type="button"
        disabled={
          !hasImage ||
          local.some(row => row.status === 'uploading' || row.status === 'preparing' || row.status === 'retrying')
        }
        onClick={onNext}
        className="mt-8 inline-flex min-h-11 w-full items-center justify-center rounded-xl bg-emerald-700 px-6 text-sm font-semibold text-white hover:bg-emerald-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 disabled:cursor-not-allowed disabled:opacity-50 sm:w-auto cursor-pointer"
      >
        Continue
      </button>
    </div>
  );
}
