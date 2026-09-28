import type { LessorMediaItem } from '../services/lessorMediaService.ts';

export function hasCoverImage(items: LessorMediaItem[]): boolean {
  return items.some(item => item.status === 'UPLOADED' && item.contentType.startsWith('image/') && item.cover);
}

export function movedMediaIds(items: LessorMediaItem[], index: number, direction: -1 | 1): string[] | null {
  const uploaded = items.filter(item => item.status === 'UPLOADED');
  const other = index + direction;
  if (index < 0 || other < 0 || index >= uploaded.length || other >= uploaded.length) return null;
  const ids = uploaded.map(item => item.mediaId);
  [ids[index], ids[other]] = [ids[other], ids[index]];
  return ids;
}
