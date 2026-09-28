import React, { useEffect, useMemo, useState } from 'react';
import { ArrowDown, ArrowUp, Camera, Check, LoaderCircle, RefreshCw, Star, Trash2 } from 'lucide-react';
import { getErrorMessage } from '../services/apiError';
import { LessorMediaItem, lessorMediaService } from '../services/lessorMediaService';
import { hasCoverImage, movedMediaIds } from '../utils/lessorMedia';
import { LessorMediaAsset } from './LessorMediaAsset';

interface LocalUpload { id: string; file: File; progress: number; status: 'preparing' | 'uploading' | 'retrying' | 'failed'; error?: string; invalid?: boolean }
const SECONDARY = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-3 text-sm font-semibold text-slate-700 hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 disabled:opacity-50';

export function LessorMediaStep({ draftId, onNext, guest = false }: { draftId: string; onNext: () => void; guest?: boolean }) {
  const [items, setItems] = useState<LessorMediaItem[]>([]);
  const [local, setLocal] = useState<LocalUpload[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const uploaded = useMemo(() => items.filter(item => item.status === 'UPLOADED' || item.status === 'STAGED'), [items]);
  const hasImage = hasCoverImage(uploaded);

  const refresh = async () => {
    const loaded = await lessorMediaService.list(draftId, guest);
    setItems(loaded);
    return loaded;
  };

  useEffect(() => {
    let live = true;
    setLoading(true);
    lessorMediaService.list(draftId, guest).then(async loaded => {
      if (!live) return;
      setItems(loaded);
      const recoverable = loaded.filter(item => item.status === 'FAILED' || item.status === 'PENDING');
      if (recoverable.length) {
        await Promise.allSettled(recoverable.map(item => lessorMediaService.recover(draftId, item.mediaId, guest)));
        if (live) setItems(await lessorMediaService.list(draftId, guest));
      }
    }).catch(cause => { if (live) setError(getErrorMessage(cause, 'Could not load your media.')); })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [draftId, guest]);

  const uploadOne = async (row: LocalUpload) => {
    setLocal(previous => previous.map(item => item.id === row.id ? { ...item, status: 'uploading', error: undefined } : item));
    try {
      const saved = await lessorMediaService.upload(draftId, row.id, row.file, progress => {
        setLocal(previous => previous.map(item => item.id === row.id ? { ...item, progress } : item));
      }, guest);
      setItems(previous => [...previous.filter(item => item.mediaId !== saved.mediaId), saved].sort((a, b) => a.sortOrder - b.sortOrder));
      setLocal(previous => previous.filter(item => item.id !== row.id));
    } catch (cause) {
      setLocal(previous => previous.map(item => item.id === row.id ? { ...item, status: 'failed', error: getErrorMessage(cause, 'Upload failed.') } : item));
    }
  };

  const addFiles = (files: FileList | null) => {
    if (!files) return;
    for (const file of Array.from(files)) {
      const row: LocalUpload = { id: crypto.randomUUID(), file, progress: 0, status: 'preparing' };
      setLocal(previous => [...previous, row]);
      const allowed = ['image/jpeg', 'image/png', 'image/webp', 'image/heic', 'image/heif', 'video/mp4', 'video/quicktime'];
      const video = file.type.startsWith('video/');
      if (!allowed.includes(file.type) || file.size === 0 || file.size > (video ? 100 : 10) * 1024 * 1024) {
        setLocal(previous => previous.map(item => item.id === row.id
          ? { ...item, status: 'failed', invalid: true, error: 'Choose a supported, nonempty photo up to 10 MB or video up to 100 MB.' }
          : item));
      } else void uploadOne(row);
    }
  };

  const retry = async (row: LocalUpload) => {
    setLocal(previous => previous.map(item => item.id === row.id ? { ...item, status: 'retrying' } : item));
    try {
      const recovered = await lessorMediaService.recover(draftId, row.id, guest);
      setItems(previous => [...previous.filter(item => item.mediaId !== recovered.mediaId), recovered].sort((a, b) => a.sortOrder - b.sortOrder));
      setLocal(previous => previous.filter(item => item.id !== row.id));
    } catch { await uploadOne(row); }
  };

  const changeCover = async (mediaId: string) => {
    setBusy(true); setError('');
    try { setItems(await lessorMediaService.cover(draftId, mediaId, guest)); }
    catch (cause) { setError(getErrorMessage(cause, 'Could not change the cover.')); }
    finally { setBusy(false); }
  };

  const move = async (index: number, direction: -1 | 1) => {
    const ids = movedMediaIds(uploaded, index, direction);
    if (!ids) return;
    setBusy(true); setError('');
    try { setItems(await lessorMediaService.reorder(draftId, ids, guest)); }
    catch (cause) { setError(getErrorMessage(cause, 'Could not change media order.')); }
    finally { setBusy(false); }
  };

  const remove = async (mediaId: string) => {
    if (!window.confirm('Remove this file from your property draft?')) return;
    setBusy(true); setError('');
    try { await lessorMediaService.remove(draftId, mediaId, guest); await refresh(); }
    catch (cause) { setError(getErrorMessage(cause, 'Could not remove this file. Retry to finish removal.')); }
    finally { setBusy(false); }
  };

  return <div className="mt-2">
    <h1 className="font-['Outfit',sans-serif] text-3xl font-bold text-slate-950">Show the home</h1>
    <p className="mt-2 text-sm text-slate-600">Add at least one photo. The first successful photo becomes the cover; you can change it.</p>
    <label className="mt-6 flex min-h-24 cursor-pointer flex-col items-center justify-center gap-2 rounded-2xl border border-dashed border-emerald-400 bg-emerald-50 px-4 py-5 text-center text-sm font-semibold text-emerald-900 hover:bg-emerald-100 focus-within:ring-2 focus-within:ring-emerald-500"><Camera className="h-6 w-6"/>Add photos or a video<input type="file" multiple accept="image/jpeg,image/png,image/webp,image/heic,image/heif,video/mp4,video/quicktime" className="sr-only" onChange={event => { addFiles(event.target.files); event.target.value = ''; }}/></label>
    <p className="mt-2 text-xs text-slate-500">Photos up to 10 MB each; video up to 100 MB. One photo is required to submit.</p>
    {loading && <div className="mt-6 flex items-center gap-2 text-sm text-slate-600"><LoaderCircle className="h-4 w-4 animate-spin"/>Loading media…</div>}
    {error && <p role="alert" className="mt-5 rounded-xl bg-rose-50 p-3 text-sm text-rose-800">{error} <button className="underline" onClick={() => { void refresh().catch(cause => setError(getErrorMessage(cause, 'Still unavailable.'))); }}>Retry</button></p>}
    {!guest && uploaded.some(item => item.status === 'STAGED') && <button type="button" disabled={busy} className={`${SECONDARY} mt-5`} onClick={() => { setBusy(true); setError(''); void lessorMediaService.promote(draftId).then(refresh).catch(cause => setError(getErrorMessage(cause, 'Could not prepare your photos. Retry.'))).finally(() => setBusy(false)); }}>Prepare your photos</button>}
    <div className="mt-6 grid gap-4 sm:grid-cols-2">{uploaded.map((item, index) => <article key={item.mediaId} className="min-w-0 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
      <div className="aspect-[4/3] bg-slate-100"><LessorMediaAsset url={item.url} contentType={item.contentType} alt={item.contentType.startsWith('video/') ? `Property video ${index + 1}` : `Property photo ${index + 1}`} className={`h-full w-full ${item.contentType.startsWith('video/') ? 'object-contain' : 'object-cover'}`}/></div>
      <div className="p-3"><div className="flex items-center justify-between gap-2"><p className="min-w-0 truncate text-xs text-slate-600" title={item.filename}>{item.filename}</p>{item.cover && <span className="flex shrink-0 items-center gap-1 text-xs font-semibold text-emerald-800"><Check className="h-3 w-3"/>Cover</span>}</div>
      <div className="mt-3 flex flex-wrap gap-2">{item.contentType.startsWith('image/') && !item.cover && <button type="button" disabled={busy} className={SECONDARY} onClick={() => { void changeCover(item.mediaId); }}><Star className="h-4 w-4"/>Make cover</button>}<button type="button" disabled={busy || index === 0} className={SECONDARY} aria-label={`Move ${item.filename} up`} onClick={() => { void move(index, -1); }}><ArrowUp className="h-4 w-4"/></button><button type="button" disabled={busy || index === uploaded.length - 1} className={SECONDARY} aria-label={`Move ${item.filename} down`} onClick={() => { void move(index, 1); }}><ArrowDown className="h-4 w-4"/></button><button type="button" disabled={busy} className={SECONDARY} onClick={() => { void remove(item.mediaId); }}><Trash2 className="h-4 w-4"/>Remove</button></div></div>
    </article>)}
    {local.map(row => <div key={row.id} className="rounded-2xl border border-slate-200 bg-white p-4"><p className="truncate text-sm font-semibold text-slate-800">{row.file.name}</p><p aria-live="polite" className="mt-2 text-xs text-slate-600">{row.status === 'preparing' ? 'Preparing…' : row.status === 'retrying' ? 'Retrying…' : row.status === 'uploading' ? `Uploading ${row.progress}%` : 'Upload failed'}</p>{row.status === 'uploading' && <progress value={row.progress} max={100} className="mt-2 w-full accent-emerald-700"/>}{row.status === 'failed' && <><p role="alert" className="mt-1 text-xs text-rose-700">{row.error}</p><div className="mt-3 flex flex-wrap gap-2">{!row.invalid && <button className={SECONDARY} onClick={() => { void retry(row); }}><RefreshCw className="h-4 w-4"/>Retry</button>}<button className={SECONDARY} onClick={() => setLocal(previous => previous.filter(item => item.id !== row.id))}>Dismiss</button></div></>}</div>)}
    {items.filter(item => item.status !== 'UPLOADED' && item.status !== 'STAGED' && !local.some(row => row.id === item.mediaId)).map(item => <div key={item.mediaId} className="rounded-xl border border-amber-200 bg-amber-50 p-4"><p className="truncate text-sm font-semibold text-slate-800">{item.filename}</p><p className="mt-1 text-xs text-amber-800">{item.status === 'DELETING' ? 'Removal needs retry' : 'Upload needs recovery or original file'}</p><div className="mt-3 flex flex-wrap gap-2">{item.status !== 'DELETING' && <button className={SECONDARY} onClick={() => { void lessorMediaService.recover(draftId, item.mediaId, guest).then(refresh).catch(cause => setError(getErrorMessage(cause, 'Automatic recovery unavailable. Select the original file to retry.'))); }}><RefreshCw className="h-4 w-4"/>Check upload</button>}<button className={SECONDARY} onClick={() => { void remove(item.mediaId); }}>{item.status === 'DELETING' ? 'Retry removal' : 'Remove failed item'}</button></div></div>)}
    </div>
    <button type="button" disabled={!hasImage || local.some(row => row.status === 'uploading' || row.status === 'preparing')} onClick={onNext} className="mt-8 inline-flex min-h-11 w-full items-center justify-center rounded-xl bg-emerald-700 px-6 text-sm font-semibold text-white hover:bg-emerald-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 disabled:cursor-not-allowed disabled:opacity-50 sm:w-auto">Continue</button>
  </div>;
}
