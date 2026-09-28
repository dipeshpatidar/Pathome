import type { LessorMediaItem } from '../services/lessorMediaService.ts';

export function hasCoverImage(items: LessorMediaItem[]): boolean {
  return items.some(item => (item.status === 'UPLOADED' || item.status === 'STAGED') && item.contentType.startsWith('image/') && item.cover);
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
