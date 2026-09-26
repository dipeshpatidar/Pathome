/**
 * Media delivery and URL transformation utilities for Pathome Spaces.
 *
 * Architecture:
 *   Cloudinary handles: responsive resizing, format negotiation, quality
 *   optimization, DPR-aware delivery.
 *   Frontend handles: cover vs contain, adaptive presentation geometry,
 *   blurred/darkened backdrop for mismatched aspect ratios.
 *
 * Originals are never modified — transformations are applied at the
 * Cloudinary delivery layer via URL manipulation only.
 */

/** Named presets that encapsulate transformation intent. */
export type MediaPreset =
  | 'DISCOVERY_CARD'   // Discovery thumbnail: cover-fit, ~800px wide, auto quality
  | 'DETAIL_MAIN'      // Property detail main viewer: up to ~1200px, best quality
  | 'DETAIL_THUMBNAIL' // Thumbnail strip: ~160px, cover-fit, low quality
  | 'FULLSCREEN';      // Lightbox: up to ~1920px, maximum quality

interface CloudinaryTransformOptions {
  width?: number;
  height?: number;
  crop?: string;
  quality?: string;
  format?: string;
}

const PRESET_OPTIONS: Record<MediaPreset, CloudinaryTransformOptions> = {
  DISCOVERY_CARD:    { width: 800,  crop: 'limit',  quality: 'auto',      format: 'auto' },
  DETAIL_MAIN:       { width: 1200, crop: 'limit',  quality: 'auto:best', format: 'auto' },
  DETAIL_THUMBNAIL:  { width: 160,  height: 90, crop: 'fill', quality: 'auto:low', format: 'auto' },
  FULLSCREEN:        { width: 1920, crop: 'limit',  quality: 'auto:best', format: 'auto' },
};

/**
 * Applies a named delivery preset to a Cloudinary image URL.
 * Non-Cloudinary URLs are returned unchanged (safe for local/other assets).
 */
export const buildCloudinaryUrl = (url: string | undefined | null, preset: MediaPreset): string => {
  if (!url || !url.trim()) return '';
  const match = url.match(/^(https?:\/\/res\.cloudinary\.com\/[^/]+\/image\/upload\/)(.*)$/);
  if (!match) return url; // Non-Cloudinary: return as-is

  const opts = PRESET_OPTIONS[preset];
  const parts: string[] = [];
  if (opts.width)   parts.push(`w_${opts.width}`);
  if (opts.height)  parts.push(`h_${opts.height}`);
  if (opts.crop)    parts.push(`c_${opts.crop}`);
  if (opts.quality) parts.push(`q_${opts.quality}`);
  if (opts.format)  parts.push(`f_${opts.format}`);

  const transformation = parts.join(',');
  return `${match[1]}${transformation}/${match[2]}`;
};

/**
 * Derives a lightweight JPEG poster frame from a Cloudinary video URL without downloading the video.
 * Applies so_0,w_800,c_limit,f_auto,q_auto transformation and updates extension to .jpg.
 */
export const deriveVideoPosterUrl = (videoUrl?: string | null): string | null => {
  if (!videoUrl || !videoUrl.trim()) return null;
  const match = videoUrl.match(/^(https?:\/\/res\.cloudinary\.com\/[^/]+\/video\/upload\/)(.*)$/);
  if (match) {
    const base = match[1];
    const rest = match[2].replace(/\.(mp4|webm|mov|m4v|ogv)(?:[?#].*)?$/i, '.jpg');
    return `${base}so_0,w_800,c_limit,f_auto,q_auto/${rest}`;
  }
  return null;
};
