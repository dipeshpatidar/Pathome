export type TenantSearchMode = 'hero' | 'sticky' | 'beacon';
export type TenantSearchMorphDirection = 'collapse' | 'expand' | 'crossfade' | 'hero-to-sticky' | 'none';

export interface TenantSearchMorphPlan {
  direction: TenantSearchMorphDirection;
  durationMs: number;
  beaconSide: 'left' | 'right';
  travels: boolean;
}

export type TenantSearchCollapseReason = 'inactivity' | 'discovery';

export interface TenantSearchCollapseContext {
  mode: TenantSearchMode;
  heroVisible: boolean;
  engaged: boolean;
  discoveryCollision: boolean;
  manualOpenGrace: boolean;
}

export interface TenantSearchCollisionBand {
  top: number;
  bottom: number;
}

export function tenantSearchCollisionBand(searchBottom: number, viewportHeight: number): TenantSearchCollisionBand {
  const maxBottom = Math.max(1, Math.floor(viewportHeight));
  if (!Number.isFinite(searchBottom) || !Number.isFinite(viewportHeight)) {
    return { top: maxBottom - 1, bottom: maxBottom };
  }
  const top = Math.max(0, Math.min(maxBottom - 1, Math.ceil(searchBottom + 12)));
  const bottom = Math.max(top + 1, Math.min(maxBottom, Math.ceil(searchBottom + 24)));
  return { top, bottom };
}

export function shouldCollapseTenantSearch(
  reason: TenantSearchCollapseReason,
  context: TenantSearchCollapseContext
): boolean {
  if (context.mode !== 'sticky' || context.heroVisible || context.engaged) return false;
  if (reason === 'discovery') return context.discoveryCollision && !context.manualOpenGrace;
  return true;
}

export interface TenantSearchMorphOverlay {
  animateTo(target: HTMLElement, targetMode: TenantSearchMode, onComplete: () => void): void;
  cancel(): void;
}

interface TenantSearchSurfaceStyle {
  backgroundColor: string;
  borderColor: string;
  borderStyle: string;
  borderWidth: string;
  borderRadius: string;
  boxShadow: string;
  backdropFilter: string;
}

function readVisualSurface(mode: 'sticky' | 'beacon'): TenantSearchSurfaceStyle {
  return mode === 'sticky'
    ? {
      backgroundColor: 'rgba(255,253,248,.95)', borderColor: 'rgba(220,228,216,.9)',
      borderStyle: 'solid', borderWidth: '1px', borderRadius: '18px',
      boxShadow: '0 7px 18px -8px rgba(13,44,33,.2)', backdropFilter: 'blur(16px)'
    }
    : {
      backgroundColor: 'rgba(255,255,255,.86)', borderColor: 'rgba(255,255,255,.75)',
      borderStyle: 'solid', borderWidth: '1px', borderRadius: '9999px',
      boxShadow: '0 5px 20px rgba(9,40,29,.15)', backdropFilter: 'blur(12px)'
    };
}

function applySurfaceStyle(target: HTMLElement, sourceStyle: TenantSearchSurfaceStyle): void {
  target.style.backgroundColor = sourceStyle.backgroundColor;
  target.style.borderColor = sourceStyle.borderColor;
  target.style.borderStyle = sourceStyle.borderStyle;
  target.style.borderWidth = sourceStyle.borderWidth;
  target.style.borderRadius = sourceStyle.borderRadius;
  target.style.boxShadow = sourceStyle.boxShadow;
  target.style.backdropFilter = sourceStyle.backdropFilter;
}

function removeDuplicateIds(element: HTMLElement): void {
  element.removeAttribute('id');
  element.querySelectorAll<HTMLElement>('[id]').forEach(child => child.removeAttribute('id'));
}

/** Makes a short-lived, inert visual copy for the Sticky Search ↔ Beacon handoff. */
export function createTenantSearchMorphOverlay(
  source: HTMLElement,
  sourceMode: 'sticky' | 'beacon'
): TenantSearchMorphOverlay | null {
  const sourceRect = source.getBoundingClientRect();
  const sourceIcon = source.querySelector<HTMLElement>(`[data-tenant-search-mode-icon="${sourceMode}"]`);
  if (sourceRect.width <= 0 || sourceRect.height <= 0 || !sourceIcon) return null;

  const sourceIconRect = sourceIcon.getBoundingClientRect();
  const sourceVisual = readVisualSurface(sourceMode);
  const shell = source.cloneNode(true) as HTMLElement;
  shell.dataset.tenantSearchMorphShell = 'true';
  const details = shell.querySelector<HTMLElement>('#compact-search-bar');
  removeDuplicateIds(shell);
  shell.setAttribute('aria-hidden', 'true');
  shell.setAttribute('inert', '');
  shell.style.position = 'fixed';
  shell.style.left = `${sourceRect.left}px`;
  shell.style.top = `${sourceRect.top}px`;
  shell.style.right = 'auto';
  shell.style.bottom = 'auto';
  shell.style.width = `${sourceRect.width}px`;
  shell.style.height = `${sourceRect.height}px`;
  shell.style.maxWidth = 'none';
  shell.style.margin = '0';
  shell.style.transform = 'none';
  shell.style.translate = 'none';
  shell.style.boxSizing = 'border-box';
  shell.style.overflow = 'hidden';
  shell.style.pointerEvents = 'none';
  shell.style.zIndex = '98';
  shell.style.willChange = 'left,top,width,height,border-radius,background-color,box-shadow';
  applySurfaceStyle(shell, sourceVisual);
  shell.querySelectorAll('[data-tenant-search-mode-icon]').forEach(icon => icon.remove());
  shell.querySelectorAll<HTMLElement>('[aria-hidden="false"]').forEach(element => element.setAttribute('aria-hidden', 'true'));
  if (sourceMode === 'beacon') {
    const button = shell.querySelector<HTMLElement>('button[aria-label="Reopen home search"]');
    if (button) {
      button.style.background = 'transparent';
      button.style.border = '0';
      button.style.boxShadow = 'none';
      button.style.backdropFilter = 'none';
    }
  }

  if (details) details.style.opacity = '1';
  document.body.append(shell);

  const movingIcon = document.createElement('span');
  movingIcon.dataset.tenantSearchMorphIcon = 'true';
  movingIcon.setAttribute('aria-hidden', 'true');
  movingIcon.style.position = 'fixed';
  movingIcon.style.left = `${sourceIconRect.left}px`;
  movingIcon.style.top = `${sourceIconRect.top}px`;
  movingIcon.style.width = `${sourceIconRect.width}px`;
  movingIcon.style.height = `${sourceIconRect.height}px`;
  movingIcon.style.display = 'grid';
  movingIcon.style.placeItems = 'center';
  movingIcon.style.pointerEvents = 'none';
  movingIcon.style.zIndex = '99';
  const sourceSvg = sourceIcon.querySelector<SVGElement>('svg');
  if (!sourceSvg) {
    shell.remove();
    return null;
  }
  const iconSvg = sourceSvg.cloneNode(true) as SVGElement;
  iconSvg.style.color = getComputedStyle(sourceSvg).color;
  movingIcon.append(iconSvg);
  document.body.append(movingIcon);

  let shellAnimation: Animation | null = null;
  let iconAnimation: Animation | null = null;
  let detailsAnimation: Animation | null = null;
  let fallbackTimer: number | null = null;
  let cancelled = false;

  const cancel = () => {
    if (cancelled) return;
    cancelled = true;
    shellAnimation?.cancel();
    iconAnimation?.cancel();
    detailsAnimation?.cancel();
    if (fallbackTimer !== null) window.clearTimeout(fallbackTimer);
    shell.remove();
    movingIcon.remove();
  };

  return {
    animateTo(target, targetMode, onComplete) {
      const targetRect = target.getBoundingClientRect();
      const targetIcon = target.querySelector<HTMLElement>(`[data-tenant-search-mode-icon="${targetMode}"]`);
      if (cancelled || !targetIcon || targetRect.width <= 0 || targetRect.height <= 0) {
        cancel();
        onComplete();
        return;
      }
      const targetIconRect = targetIcon.getBoundingClientRect();
      const targetVisual = readVisualSurface(targetMode === 'beacon' ? 'beacon' : 'sticky');
      const duration = sourceMode === 'sticky' ? 500 : 400;
      const easing = 'cubic-bezier(0.22, 1, 0.36, 1)';
      const sourceRadius = sourceVisual.borderRadius;
      const targetRadius = targetMode === 'beacon' ? '9999px' : targetVisual.borderRadius;
      const sourceFrame = {
        left: `${sourceRect.left}px`, top: `${sourceRect.top}px`,
        width: `${sourceRect.width}px`, height: `${sourceRect.height}px`,
        borderRadius: sourceRadius, backgroundColor: sourceVisual.backgroundColor,
        borderColor: sourceVisual.borderColor, boxShadow: sourceVisual.boxShadow
      };
      const targetFrame = {
        left: `${targetRect.left}px`, top: `${targetRect.top}px`,
        width: `${targetRect.width}px`, height: `${targetRect.height}px`,
        borderRadius: targetRadius, backgroundColor: targetVisual.backgroundColor,
        borderColor: targetVisual.borderColor, boxShadow: targetVisual.boxShadow
      };
      const targetIconFrame = {
        left: `${targetIconRect.left}px`, top: `${targetIconRect.top}px`,
        width: `${targetIconRect.width}px`, height: `${targetIconRect.height}px`
      };

      if (typeof shell.animate === 'function' && typeof movingIcon.animate === 'function') {
        shellAnimation = shell.animate([sourceFrame, targetFrame], { duration, easing, fill: 'forwards' });
        iconAnimation = movingIcon.animate([
          { left: `${sourceIconRect.left}px`, top: `${sourceIconRect.top}px`, width: `${sourceIconRect.width}px`, height: `${sourceIconRect.height}px` },
          targetIconFrame
        ], { duration, easing, fill: 'forwards' });
        if (sourceMode === 'sticky' && details) {
          detailsAnimation = details.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 145, delay: 35, easing: 'ease-out', fill: 'forwards' });
        }
        shellAnimation.onfinish = onComplete;
      } else {
        Object.assign(shell.style, targetFrame);
        Object.assign(movingIcon.style, targetIconFrame);
        fallbackTimer = window.setTimeout(onComplete, duration);
      }
    },
    cancel
  };
}

export function tenantSearchBeaconSide(viewportWidth: number): 'left' | 'right' {
  return viewportWidth >= 1024 ? 'right' : 'left';
}

export function tenantSearchMorphPlan(
  from: TenantSearchMode,
  to: TenantSearchMode,
  reducedMotion: boolean,
  viewportWidth: number
): TenantSearchMorphPlan {
  const beaconSide = tenantSearchBeaconSide(viewportWidth);
  if (from === to) return { direction: 'none', durationMs: 0, beaconSide, travels: false };
  if (to === 'hero') return { direction: 'none', durationMs: 0, beaconSide, travels: false };

  // Mobile keeps Beacon ↔ Sticky changes local to the Search surfaces instead
  // of animating a geometric clone across the viewport.
  if (viewportWidth < 768 && (from === 'beacon' || to === 'beacon')) {
    return { direction: 'crossfade', durationMs: reducedMotion ? 100 : 160, beaconSide, travels: false };
  }

  if (from === 'sticky' && to === 'beacon') {
    return reducedMotion
      ? { direction: 'crossfade', durationMs: 100, beaconSide, travels: false }
      : { direction: 'collapse', durationMs: 500, beaconSide, travels: true };
  }
  if (from === 'beacon' && to === 'sticky') {
    return reducedMotion
      ? { direction: 'crossfade', durationMs: 100, beaconSide, travels: false }
      : { direction: 'expand', durationMs: 400, beaconSide, travels: true };
  }
  if (from === 'hero' && to === 'sticky') {
    return { direction: 'hero-to-sticky', durationMs: reducedMotion ? 0 : 300, beaconSide, travels: false };
  }
  return { direction: 'none', durationMs: 0, beaconSide, travels: false };
}
