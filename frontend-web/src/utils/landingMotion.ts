import type { MotionProps } from 'framer-motion';

export const LANDING_EASE = [0.16, 1, 0.3, 1] as const;

const ENTRANCES = {
  section: { y: 12, opacity: 0.9, duration: 0.54 },
  heading: { y: 18, opacity: 0.65, duration: 0.52 },
  card: { y: 12, opacity: 0.78, duration: 0.48 }
} as const;

/** Related, once-only entrances for the landing page's text, media, and cards. */
export const landingEntrance = (
  reduceMotion: boolean | null,
  level: keyof typeof ENTRANCES,
  delay = 0,
  direction: 'up' | 'left' | 'right' = 'up'
): MotionProps => {
  const { y, opacity, duration } = ENTRANCES[level];
  const showImmediately = reduceMotion || typeof IntersectionObserver === 'undefined';
  const offset = direction === 'up' ? { y } : { x: direction === 'left' ? -24 : 24 };
  const settled = direction === 'up' ? { y: 0 } : { x: 0 };
  return {
    initial: showImmediately ? false : { opacity, ...offset, ...(level === 'card' ? { scale: 0.992 } : {}) },
    whileInView: { opacity: 1, ...settled, ...(level === 'card' ? { scale: 1 } : {}) },
    viewport: { once: true, margin: '-40px' },
    transition: { duration: showImmediately ? 0 : duration, delay: showImmediately ? 0 : delay, ease: LANDING_EASE }
  };
};

/** A restrained heading reveal; the heading stays in its final layout throughout. */
export const landingHeadingMask = (reduceMotion: boolean | null, delay = 0): MotionProps => {
  const showImmediately = reduceMotion || typeof IntersectionObserver === 'undefined';
  return {
    initial: showImmediately ? false : { clipPath: 'inset(0 0 18% 0)', opacity: 0.8 },
    whileInView: { clipPath: 'inset(0 0 0% 0)', opacity: 1 },
    viewport: { once: true, margin: '-40px' },
    transition: { duration: showImmediately ? 0 : 0.58, delay: showImmediately ? 0 : delay, ease: LANDING_EASE }
  };
};
