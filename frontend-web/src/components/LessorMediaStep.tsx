import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertCircle,
  ArrowLeft,
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
  mergeUploadedMedia,
  movedMediaIds,
  normalizeMediaType,
  selectedUploadOrder
} from '../utils/lessorMedia';
import { LessorMediaAsset } from './LessorMediaAsset';
import { ROOM_TAG_OPTIONS, RoomTag } from '../types';
import { displayFilename, displayMediaName } from '../utils/lessorMedia';

interface LocalUpload {
  id: string;
  file: File;
  previewUrl?: string;
  progress: number;
  status: 'queued' | 'uploading' | 'retrying' | 'failed';
  error?: string;
  retryable?: boolean;
  isVideo: boolean;
  roomTag: RoomTag;
}

const SECONDARY =
  'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-3 text-sm font-semibold text-slate-700 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 disabled:opacity-50 cursor-pointer';

export function LessorMediaStep({
  draftId,
  onBack,
  onNext,
  guest = false,
  onMediaChange,
  onActiveUploadsChange
}: {
  draftId: string;
  onBack: () => void;
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
  const [removingMediaId, setRemovingMediaId] = useState<string | null>(null);
  const [taggingMediaIds, setTaggingMediaIds] = useState<Set<string>>(() => new Set());
  const [finalizingOrder, setFinalizingOrder] = useState(false);
  const localRef = useRef<LocalUpload[]>([]);
  const queuedRef = useRef<LocalUpload[]>([]);
  const activeRef = useRef(0);
  const refreshRef = useRef(0);
  const coverRevisionRef = useRef(0);
  const selectionOrderRef = useRef<string[]>([]);
  const selectedTagRef = useRef<Map<string, RoomTag>>(new Map());
  const uploaded = useMemo(
    () => items.filter(item => item.status === 'UPLOADED' || item.status === 'STAGED'),
    [items]
  );
  const mediaCards = useMemo(
    () => items.filter(item => item.status === 'UPLOADED' || item.status === 'STAGED' || item.status === 'DELETING'),
    [items]
  );
  const hasImage = hasCoverImage(uploaded);

  useEffect(() => {
    onMediaChange?.(items);
  }, [items, onMediaChange]);

  useEffect(() => {
    const hasActive = local.some(
      row => row.status === 'uploading' || row.status === 'queued' || row.status === 'retrying'
    );
    onActiveUploadsChange?.(hasActive || finalizingOrder);
  }, [local, finalizingOrder, onActiveUploadsChange]);

  useEffect(() => { localRef.current = local; }, [local]);
  useEffect(() => {
    return () => {
      localRef.current.forEach(row => {
        if (row.previewUrl) URL.revokeObjectURL(row.previewUrl);
      });
    };
  }, []);

  const refresh = async () => {
    const revision = ++refreshRef.current;
    const loaded = await lessorMediaService.list(draftId, guest);
    if (revision === refreshRef.current) setItems(loaded);
    return loaded;
  };

  useEffect(() => {
    let live = true;
    setLoading(true);
    refresh()
      .then(async loaded => {
        if (!live) return;
        const recoverable = loaded.filter(item => item.status === 'FAILED');
        if (recoverable.length) {
          await Promise.allSettled(recoverable.map(item => lessorMediaService.recover(draftId, item.mediaId, guest)));
          if (live) await refresh();
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
    const coverRevision = coverRevisionRef.current;
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
      const selectedTag = selectedTagRef.current.get(row.id) ?? row.roomTag;
      let savedTag = saved.roomTag;
      if (selectedTag !== 'GENERAL') {
        try { await lessorMediaService.tag(draftId, row.id, selectedTag, guest); savedTag = selectedTag; }
        catch (cause) { setError(getErrorMessage(cause, 'The photo uploaded, but its category was not saved.')); }
      }
      refreshRef.current += 1;
      setItems(previous => mergeUploadedMedia(previous, { ...saved, roomTag: savedTag }, coverRevision !== coverRevisionRef.current));
      selectedTagRef.current.delete(row.id);
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

  const pump = () => {
    while (activeRef.current < 2 && queuedRef.current.length) {
      const row = queuedRef.current.shift()!;
      activeRef.current += 1;
      void uploadOne(row).finally(() => {
        activeRef.current -= 1;
        pump();
      });
    }
    if (activeRef.current === 0 && queuedRef.current.length === 0 && selectionOrderRef.current.length) {
      const selectedIds = selectionOrderRef.current;
      selectionOrderRef.current = [];
      setFinalizingOrder(true);
      void lessorMediaService.list(draftId, guest).then(async rows => {
        const ordered = selectedUploadOrder(rows, selectedIds);
        if (ordered) {
          await lessorMediaService.reorder(draftId, ordered, guest);
          await refresh();
        }
      }).catch(cause => setError(getErrorMessage(cause, 'Could not save the photo order.')))
        .finally(() => setFinalizingOrder(false));
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
        status: 'queued',
        isVideo,
        roomTag: 'GENERAL'
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
      selectionOrderRef.current.push(id);
      queuedRef.current.push(row);
    }
    pump();
  };

  const retry = async (row: LocalUpload) => {
    if (row.retryable === false) return;
    const coverRevision = coverRevisionRef.current;
    setLocal(previous =>
      previous.map(item => (item.id === row.id ? { ...item, status: 'retrying', error: undefined } : item))
    );
    try {
      const recovered = await lessorMediaService.recover(draftId, row.id, guest);
      if (row.previewUrl) URL.revokeObjectURL(row.previewUrl);
      const selectedTag = selectedTagRef.current.get(row.id) ?? row.roomTag;
      let savedTag = recovered.roomTag;
      if (selectedTag !== 'GENERAL') {
        try { await lessorMediaService.tag(draftId, row.id, selectedTag, guest); savedTag = selectedTag; }
        catch (cause) { setError(getErrorMessage(cause, 'The photo uploaded, but its category was not saved.')); }
      }
      refreshRef.current += 1;
      setItems(previous => mergeUploadedMedia(previous, { ...recovered, roomTag: savedTag }, coverRevision !== coverRevisionRef.current));
      selectedTagRef.current.delete(row.id);
      setLocal(previous => previous.filter(item => item.id !== row.id));
    } catch {
      await uploadOne(row);
    }
  };

  const removeLocal = (id: string) => {
    queuedRef.current = queuedRef.current.filter(row => row.id !== id);
    selectedTagRef.current.delete(id);
    setLocal(previous => {
      const target = previous.find(item => item.id === id);
      if (target?.previewUrl) URL.revokeObjectURL(target.previewUrl);
      return previous.filter(item => item.id !== id);
    });
  };

  const changeCover = async (mediaId: string) => {
    coverRevisionRef.current += 1;
    setBusy(true);
    setError('');
    try {
      const updated = await lessorMediaService.cover(draftId, mediaId, guest);
      refreshRef.current += 1;
      setItems(updated);
    } catch (cause) {
      setError(getErrorMessage(cause, 'Could not change the cover.'));
      void refresh().catch(() => {});
    } finally {
      setBusy(false);
    }
  };

  const move = async (index: number, direction: -1 | 1) => {
    if (removingMediaId) return;
    const ids = movedMediaIds(uploaded, index, direction);
    if (!ids) return;
    const previousSelectionOrder = selectionOrderRef.current;
    const readySelection = ids.filter(id => previousSelectionOrder.includes(id));
    selectionOrderRef.current = [
      ...readySelection,
      ...previousSelectionOrder.filter(id => !readySelection.includes(id))
    ];
    setBusy(true);
    setError('');
    try {
      const updated = await lessorMediaService.reorder(draftId, ids, guest);
      refreshRef.current += 1;
      setItems(updated);
    } catch (cause) {
      selectionOrderRef.current = previousSelectionOrder;
      setError(getErrorMessage(cause, 'Could not change media order.'));
    } finally {
      setBusy(false);
    }
  };

  const changeTag = async (mediaId: string, tag: RoomTag) => {
    if (removingMediaId === mediaId) return;
    setTaggingMediaIds(previous => new Set(previous).add(mediaId));
    setError('');
    try {
      await lessorMediaService.tag(draftId, mediaId, tag, guest);
      refreshRef.current += 1;
      setItems(previous => previous.map(item => item.mediaId === mediaId ? { ...item, roomTag: tag } : item));
    } catch (cause) {
      setError(getErrorMessage(cause, 'Could not save the photo category.'));
    } finally {
      setTaggingMediaIds(previous => {
        const next = new Set(previous);
        next.delete(mediaId);
        return next;
      });
    }
  };

  const remove = async (mediaId: string) => {
    if (removingMediaId || taggingMediaIds.has(mediaId)) return;
    if (!window.confirm('Remove this file from your property draft?')) return;
    setRemovingMediaId(mediaId);
    setError('');
    setItems(previous => previous.map(item => item.mediaId === mediaId ? { ...item, status: 'DELETING' } : item));
    try {
      await lessorMediaService.remove(draftId, mediaId, guest);
      await refresh();
    } catch (cause) {
      setError(getErrorMessage(cause, 'Could not remove this file. Retry to finish removal.'));
      void refresh().catch(() => {});
    } finally {
      setRemovingMediaId(null);
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
      <div className="mt-6 grid gap-4 sm:grid-cols-2">
        {mediaCards.map((item, mediaIndex) => {
          const readyIndex = uploaded.findIndex(ready => ready.mediaId === item.mediaId);
          const isDeleting = item.status === 'DELETING';
          return (
          <article
            key={item.mediaId}
            className="min-w-0 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm"
          >
            <div className="relative aspect-[4/3] bg-slate-100">
              <LessorMediaAsset
                url={item.url}
                contentType={item.contentType}
                alt={
                  item.contentType.startsWith('video/')
                    ? `Property video ${mediaIndex + 1}`
                    : `Property photo ${mediaIndex + 1}`
                }
                className={`h-full w-full ${
                  item.contentType.startsWith('video/') ? 'object-contain' : 'object-cover'
                }`}
              />
              {removingMediaId === item.mediaId && (
                <div role="status" aria-live="polite" className="absolute inset-0 z-10 flex items-center justify-center bg-slate-950/45 p-3">
                  <span className="inline-flex min-h-11 items-center gap-2 rounded-full bg-slate-950/90 px-4 text-sm font-semibold text-white shadow-lg">
                    <LoaderCircle className="h-4 w-4 animate-spin text-emerald-300" aria-hidden="true" />
                    Removing photo…
                  </span>
                </div>
              )}
            </div>
            <div className="p-3">
              <div className="flex items-center justify-between gap-2">
                <p className="min-w-0 truncate text-xs text-slate-600" title={displayMediaName(item, mediaIndex)}>
                  {displayMediaName(item, mediaIndex)}
                </p>
                {item.cover && (
                  <span className="inline-flex items-center gap-1 rounded-full bg-emerald-700 px-2.5 py-0.5 text-xs font-bold text-white shadow-xs">
                    <Check className="h-3 w-3 stroke-[3]" />
                    Cover photo
                  </span>
                )}
              </div>
              {item.contentType.startsWith('image/') && (
                <label className="mt-3 block text-xs font-semibold text-slate-700">
                  Photo category
                  <select
                    className="mt-1 block min-h-11 w-full rounded-xl border border-slate-300 bg-white px-3 text-sm text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500"
                    value={item.roomTag || 'GENERAL'}
                    disabled={isDeleting || taggingMediaIds.has(item.mediaId)}
                    onChange={event => { void changeTag(item.mediaId, event.target.value as RoomTag); }}
                  >
                    {ROOM_TAG_OPTIONS.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
                  </select>
                </label>
              )}
              <div className="mt-3 flex flex-wrap gap-2">
                {item.contentType.startsWith('image/') && !item.cover && (
                  <button
                    type="button"
                    disabled={busy || isDeleting}
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
                  disabled={busy || removingMediaId !== null || readyIndex < 0 || readyIndex === 0}
                  className={SECONDARY}
                  aria-label={`Move ${displayMediaName(item, mediaIndex)} up`}
                  onClick={() => {
                    void move(readyIndex, -1);
                  }}
                >
                  <ArrowUp className="h-4 w-4" />
                </button>
                <button
                  type="button"
                  disabled={busy || removingMediaId !== null || readyIndex < 0 || readyIndex === uploaded.length - 1}
                  className={SECONDARY}
                  aria-label={`Move ${displayMediaName(item, mediaIndex)} down`}
                  onClick={() => {
                    void move(readyIndex, 1);
                  }}
                >
                  <ArrowDown className="h-4 w-4" />
                </button>
                <button
                  type="button"
                  disabled={busy || taggingMediaIds.has(item.mediaId) || removingMediaId !== null}
                  className={SECONDARY}
                  onClick={() => {
                    void remove(item.mediaId);
                  }}
                >
                  <Trash2 className="h-4 w-4" />
                  {removingMediaId === item.mediaId ? 'Removing…' : isDeleting ? 'Retry removal' : 'Remove'}
                </button>
              </div>
            </div>
          </article>
        );})}

        {local.map((row, localIndex) => (
          <article
            key={row.id}
            className={`min-w-0 overflow-hidden rounded-2xl border bg-white shadow-sm ${
              row.status === 'failed' ? 'border-rose-300 ring-1 ring-rose-200' : 'border-slate-200'
            }`}
          >
            <div className="aspect-[4/3] bg-slate-100 relative flex items-center justify-center overflow-hidden">
              {row.previewUrl ? (
                <img src={row.previewUrl} alt={displayFilename(row.file.name, row.isVideo ? 'video/' : 'image/', uploaded.length + localIndex)} className="h-full w-full object-cover" />
              ) : (
                <div className="flex flex-col items-center justify-center p-4 text-slate-400">
                  <Film className="h-10 w-10 text-slate-400 mb-1" />
                  <span className="text-xs text-slate-500 truncate max-w-[200px]">{displayFilename(row.file.name, row.isVideo ? 'video/' : 'image/', uploaded.length + localIndex)}</span>
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
                {row.status === 'queued' && (
                  <span className="inline-flex items-center gap-1.5 rounded-full bg-slate-900/80 px-2.5 py-1 text-xs font-semibold text-white shadow-xs backdrop-blur-xs">
                    <LoaderCircle className="h-3 w-3 animate-spin text-slate-300" />
                    <span>Waiting to upload…</span>
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
              <p className="min-w-0 truncate text-xs font-medium text-slate-700" title={displayFilename(row.file.name, row.isVideo ? 'video/' : 'image/', uploaded.length + localIndex)}>
                {displayFilename(row.file.name, row.isVideo ? 'video/' : 'image/', uploaded.length + localIndex)}
              </p>
              {!row.isVideo && row.status !== 'failed' && (
                <label className="mt-3 block text-xs font-semibold text-slate-700">
                  Photo category
                  <select
                    className="mt-1 block min-h-11 w-full rounded-xl border border-slate-300 bg-white px-3 text-sm text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500"
                    value={row.roomTag}
                    onChange={event => {
                      const tag = event.target.value as RoomTag;
                      selectedTagRef.current.set(row.id, tag);
                      setLocal(previous => previous.map(item => item.id === row.id
                        ? { ...item, roomTag: tag } : item));
                    }}
                  >
                    {ROOM_TAG_OPTIONS.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
                  </select>
                </label>
              )}
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
              item.status !== 'UPLOADED' && item.status !== 'STAGED' && item.status !== 'DELETING' && !local.some(row => row.id === item.mediaId)
          )
          .map(item => (
            <div key={item.mediaId} className="rounded-xl border border-amber-200 bg-amber-50 p-4">
              <p className="truncate text-sm font-semibold text-slate-800">{displayMediaName(item, 0)}</p>
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
                  {removingMediaId === item.mediaId ? 'Removing…' : item.status === 'DELETING' ? 'Retry removal' : 'Remove failed item'}
                </button>
              </div>
            </div>
          ))}
      </div>
      {hasImage && (
        <div className="mt-5 flex items-center gap-2 rounded-xl border border-emerald-200/80 bg-emerald-50 px-4 py-2.5 text-xs font-semibold text-emerald-950">
          <Check className="h-4 w-4 text-emerald-700 stroke-[3]" />
          <span>{uploaded.some(item => item.cover && item.status === 'UPLOADED') ? 'Cover photo ready' : 'Cover photo selected'}</span>
        </div>
      )}
      <div className="mt-8 flex items-center justify-between gap-3 border-t border-slate-100 pt-5">
        <button type="button" onClick={onBack} className={SECONDARY}>
          <ArrowLeft className="h-4 w-4" aria-hidden="true" />Back
        </button>
        <button
          type="button"
          disabled={
            !hasImage && !local.some(row => !row.isVideo && row.status !== 'failed')
          }
          onClick={onNext}
          className="inline-flex min-h-11 items-center justify-center rounded-xl bg-emerald-700 px-5 text-sm font-semibold text-white hover:bg-emerald-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 disabled:cursor-not-allowed disabled:opacity-50 cursor-pointer"
        >
          Continue
        </button>
      </div>
    </div>
  );
}
