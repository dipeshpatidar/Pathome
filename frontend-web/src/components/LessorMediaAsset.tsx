import React, { useEffect, useState } from 'react';
import { API_ROOT_URL } from '../config/endpoints';
import { mediaUrl } from '../utils/lessorMedia';

/** Authenticated staged photos need a bearer fetch; image elements cannot send that header. */
export function LessorMediaAsset({ url, contentType, alt, className }: {
  url: string | null; contentType: string; alt: string; className: string;
}) {
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    setFailed(false);
    if (!url) { setSrc(null); return; }
    const resolved = mediaUrl(url, API_ROOT_URL);
    if (!url.startsWith('/api/v1/lessor/properties/drafts/') || !url.endsWith('/content')) {
      setSrc(contentType.startsWith('image/')
        ? resolved.replace('/image/upload/', '/image/upload/f_auto,q_auto,c_limit,w_800,h_600/')
        : resolved);
      return;
    }
    // A 100 MB staged video stays represented by its filename until promotion.
    if (contentType.startsWith('video/')) { setSrc(null); return; }
    const token = localStorage.getItem('pathome_auth_token');
    if (!token) { setSrc(null); setFailed(true); return; }
    const controller = new AbortController();
    let objectUrl: string | null = null;
    setSrc(null);
    fetch(resolved, { headers: { Authorization: `Bearer ${token}` }, cache: 'no-store', signal: controller.signal })
      .then(response => {
        if (!response.ok) throw new Error('Private photo unavailable');
        return response.blob();
      })
      .then(blob => {
        if (controller.signal.aborted) return;
        objectUrl = URL.createObjectURL(blob);
        setSrc(objectUrl);
      })
      .catch(() => { if (!controller.signal.aborted) setFailed(true); });
    return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [url, contentType]);

  if (!src) return <div className="flex h-full items-center justify-center p-4 text-center text-sm text-slate-600">
    {failed ? 'Private photo unavailable. Retry photo preparation.' : contentType.startsWith('video/')
      ? 'Private video awaiting preparation' : 'Private photo loading…'}
  </div>;
  return contentType.startsWith('image/')
    ? <img src={src} alt={alt} loading="lazy" className={className}/>
    : <video src={src} controls preload="metadata" className={className} aria-label={alt}/>;
}
