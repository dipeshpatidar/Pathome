import type { LessorMediaItem } from '../services/lessorMediaService.ts';

export const ALLOWED_MEDIA_MIME_TYPES = new Set([
  'image/jpeg',
  'image/png',
  'image/webp',
  'image/heic',
  'image/heif',
  'video/mp4',
  'video/quicktime'
]);

export function generateMediaId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    bytes[6] = (bytes[6] & 0x0f) | 0x40; // Version 4
    bytes[8] = (bytes[8] & 0x3f) | 0x80; // Variant 10xx
    const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function normalizeMediaType(file: { name?: string; type?: string }): {
  mime: string;
  isVideo: boolean;
  supported: boolean;
} {
  let type = (file.type || '').toLowerCase().split(';')[0].trim();
  if (type === 'image/jpg' || type === 'image/pjpeg') {
    type = 'image/jpeg';
  }
  if (!type || type === 'application/octet-stream') {
    const ext = file.name ? file.name.split('.').pop()?.toLowerCase() : '';
    switch (ext) {
      case 'jpg':
      case 'jpeg':
        type = 'image/jpeg';
        break;
      case 'png':
        type = 'image/png';
        break;
      case 'webp':
        type = 'image/webp';
        break;
      case 'heic':
        type = 'image/heic';
        break;
      case 'heif':
        type = 'image/heif';
        break;
      case 'mp4':
        type = 'video/mp4';
        break;
      case 'mov':
        type = 'video/quicktime';
        break;
      default:
        break;
    }
  }
  const ext = file.name ? file.name.split('.').pop()?.toLowerCase() : '';
  const isVideo = type.startsWith('video/') || ext === 'mp4' || ext === 'mov';
  const supported = ALLOWED_MEDIA_MIME_TYPES.has(type);
  return { mime: type, isVideo, supported };
}

export function classifyMediaError(
  cause: unknown,
  file: { size: number; name?: string; type?: string },
  isVideo: boolean
): { message: string; retryable: boolean } {
  const maxBytes = (isVideo ? 100 : 10) * 1024 * 1024;
  if (file.size === 0) {
    return { message: "We couldn't read this file. Choose another file.", retryable: false };
  }
  if (file.size > maxBytes) {
    return {
      message: isVideo
        ? 'This video is too large. Maximum size is 100 MB.'
        : 'This image is too large. Maximum size is 10 MB.',
      retryable: false
    };
  }

  if (typeof cause === 'object' && cause !== null) {
    const status = (cause as { status?: number }).status;
    const rawMsg = (cause as { message?: string }).message || '';

    if (status === 401) {
      return { message: 'Your session has expired. Sign in again to continue.', retryable: false };
    }
    if (status === 403) {
      return { message: "You don't have permission to upload media to this property.", retryable: false };
    }
    if (status === 413) {
      return {
        message: isVideo
          ? 'This video is too large. Maximum size is 100 MB.'
          : 'This image is too large. Maximum size is 10 MB.',
        retryable: false
      };
    }
    if (status === 400) {
      if (/format|content-type|mime|unsupported/i.test(rawMsg)) {
        return {
          message: isVideo
            ? "This video format isn't supported. Choose another video."
            : "This file type isn't supported. Choose another image.",
          retryable: false
        };
      }
      if (/size|too large|exceeds/i.test(rawMsg)) {
        return {
          message: isVideo
            ? 'This video is too large. Maximum size is 100 MB.'
            : 'This image is too large. Maximum size is 10 MB.',
          retryable: false
        };
      }
      if (/verified|corrupt|read|empty/i.test(rawMsg)) {
        return { message: "We couldn't read this file. Choose another file.", retryable: false };
      }
      return {
        message: isVideo
          ? "This video format isn't supported. Choose another video."
          : "This file type isn't supported. Choose another image.",
        retryable: false
      };
    }
    if (status === 408 || status === 504 || /timeout/i.test(rawMsg)) {
      return { message: "We couldn't upload this file right now. Try again.", retryable: true };
    }
    if (typeof status === 'number' && status >= 500) {
      return { message: "We couldn't upload this file right now. Try again.", retryable: true };
    }
  }

  const errStr = String((cause as { message?: string })?.message || cause || '');
  if (/connection|network|offline|failed to fetch|lost/i.test(errStr)) {
    return { message: 'Upload was interrupted. Check your connection and try again.', retryable: true };
  }

  return { message: "We couldn't upload this file. Try again.", retryable: true };
}

export function hasCoverImage(items: LessorMediaItem[]): boolean {
  return items.some(item => (item.status === 'UPLOADED' || item.status === 'STAGED') && item.contentType.startsWith('image/') && item.cover);
}

export function displayMediaName(item: LessorMediaItem, index: number): string {
  return displayFilename(item.filename, item.contentType, index);
}

export function displayFilename(filename: string | null, contentType: string, index: number): string {
  const name = filename?.trim() || '';
  if (name && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?:\.[a-z0-9]+)?$/i.test(name)
      && !/^lessor-\d+-[0-9a-f]{32}(?:\.[a-z0-9]+)?$/i.test(name)) return name;
  return contentType.startsWith('video/') ? `Video ${index + 1}` : `Photo ${index + 1}`;
}

export function mediaUrl(url: string, apiRoot: string): string {
  if (url.startsWith('/api/v1/') && apiRoot.startsWith('http')) {
    return `${apiRoot}${url.slice('/api/v1'.length)}`;
  }
  return url;
}

export function movedMediaIds(items: LessorMediaItem[], index: number, direction: -1 | 1): string[] | null {
  const uploaded = items.filter(item => item.status === 'UPLOADED' || item.status === 'STAGED');
  const other = index + direction;
  if (index < 0 || other < 0 || index >= uploaded.length || other >= uploaded.length) return null;
  const ids = uploaded.map(item => item.mediaId);
  [ids[index], ids[other]] = [ids[other], ids[index]];
  return ids;
}

export function selectedUploadOrder(items: LessorMediaItem[], selectedIds: string[]): string[] | null {
  const ready = items.filter(item => item.status === 'UPLOADED' || item.status === 'STAGED');
  const current = ready.map(item => item.mediaId);
  const readyIds = new Set(current);
  const selected = selectedIds.filter(id => readyIds.has(id));
  const selectedSet = new Set(selected);
  let next = 0;
  const ordered = current.map(id => selectedSet.has(id) ? selected[next++] : id);
  return ordered.some((id, index) => id !== current[index]) ? ordered : null;
}

export function mergeUploadedMedia(items: LessorMediaItem[], saved: LessorMediaItem,
                                   coverChangedDuringUpload: boolean): LessorMediaItem[] {
  const current = items.find(item => item.mediaId === saved.mediaId);
  const incoming = coverChangedDuringUpload
    ? { ...saved, cover: current?.cover === true }
    : saved;
  const others = items.filter(item => item.mediaId !== saved.mediaId)
    .map(item => incoming.cover ? { ...item, cover: false } : item);
  return [...others, incoming].sort((a, b) => a.sortOrder - b.sortOrder);
}
