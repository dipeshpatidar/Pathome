import React from 'react';
import { getLastUpdatedInfo } from '../utils/lessorFormatting';

export interface LastUpdatedMetaProps {
  updatedAt?: string | number | Date | null;
  className?: string;
  now?: Date;
}

/**
 * Renders subtle listing freshness metadata with a small Pathome-green activity dot:
 * ● Updated today · 10:30 AM
 *
 * Behavior:
 * - TODAY: gentle breathing pulse halo (~2.8s) around a 6px green core.
 * - YESTERDAY or OLDER: same 6px green dot, strictly static (no pulse).
 * - Null / invalid: returns null (omitted completely).
 * - Accessibility: dot has aria-hidden="true", respects prefers-reduced-motion.
 */
export const LastUpdatedMeta: React.FC<LastUpdatedMetaProps> = ({
  updatedAt,
  className = '',
  now
}) => {
  const info = getLastUpdatedInfo(updatedAt, now);
  if (!info) return null;

  const { formatted, isToday } = info;

  return (
    <p
      className={`inline-flex items-center gap-1.5 text-xs text-slate-500 tabular-nums ${className}`.trim()}
    >
      <span
        aria-hidden="true"
        data-testid="activity-dot"
        className="relative flex h-[6px] w-[6px] shrink-0 items-center justify-center"
      >
        {isToday && (
          <span
            data-testid="activity-dot-pulse"
            className="activity-dot-halo absolute -inset-[3px] rounded-full bg-emerald-500/30 motion-reduce:hidden"
          />
        )}
        <span
          data-testid="activity-dot-core"
          className="relative h-[6px] w-[6px] rounded-full bg-emerald-600"
        />
      </span>
      <span>{formatted}</span>
    </p>
  );
};
