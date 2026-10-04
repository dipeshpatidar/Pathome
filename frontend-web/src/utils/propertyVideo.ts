import type { Property } from '../types';

const usableVideoUrl = (value: unknown): string | null => {
  if (typeof value !== 'string') return null;
  const url = value.trim();
  if (!url || /\s/.test(url)) return null;
  if (/^https?:\/\//i.test(url)) {
    try {
      const parsed = new URL(url);
      return parsed.hostname && (parsed.protocol === 'http:' || parsed.protocol === 'https:') ? url : null;
    } catch {
      return null;
    }
  }
  if (!url.includes('\\') && /^\/(?!\/)[^\s]*$/.test(url)) return url;
  return null;
};

/** Return only a URL explicitly identified as property video by the real DTO. */
export const resolvePropertyVideoUrl = (
  property: Pick<Property, 'taggedMedia' | 'videoUrl'> | null | undefined
): string | null => {
  if (!property) return null;
  const taggedVideo = property.taggedMedia?.find(media => media.mediaType === 'VIDEO_WALKTHROUGH');
  return usableVideoUrl(taggedVideo?.mediaUrl) ?? usableVideoUrl(property.videoUrl);
};
