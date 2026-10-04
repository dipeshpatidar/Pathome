import React from 'react';
import { CalendarDays, Compass, Heart, Home, SlidersHorizontal } from 'lucide-react';
import type { TenantMobileDockItem } from '../utils/tenantMobileDock';

interface TenantNavigationRailProps {
  activeItem: TenantMobileDockItem;
  quickRefineOpen: boolean;
  onNavigate: (item: TenantMobileDockItem) => void;
  onOpenFilters: () => void;
}

const items: Array<{ item: TenantMobileDockItem; label: string; Icon: React.ElementType }> = [
  { item: 'home', label: 'Explore homes', Icon: Compass },
  { item: 'saved', label: 'Saved homes', Icon: Heart },
  { item: 'visits', label: 'Visit requests', Icon: CalendarDays }
];

/** Desktop presentation of the existing tenant section and Quick Refine actions. */
export const TenantNavigationRail: React.FC<TenantNavigationRailProps> = ({
  activeItem,
  quickRefineOpen,
  onNavigate,
  onOpenFilters
}) => (
  <aside className="tenant-shell-rail fixed left-0 top-[calc(72px+env(safe-area-inset-top))] z-[80] hidden h-[calc(100dvh-72px-env(safe-area-inset-top))] w-[210px] flex-col border-r px-[18px] py-4 lg:flex min-[1101px]:w-[242px]"
    aria-label="Tenant workspace">
    <button type="button" onClick={() => onNavigate('home')} aria-label="Pathome home" title="Pathome home"
      className="tenant-shell-brand flex min-h-12 items-center gap-[9px] rounded-xl px-1 text-left focus-visible:outline-none xl:px-2">
      <span className="tenant-shell-mark grid h-[29px] w-[29px] shrink-0 place-items-center rounded-[9px]" aria-hidden="true">
        <Home size={17} strokeWidth={1.8} />
      </span>
      <span className="hidden min-w-0 lg:block">
        <span className="tenant-shell-brand-name block text-[21px] font-bold leading-6 tracking-[-1.3px]">pathome<span className="tenant-shell-brand-dot">.</span></span>
        <span className="tenant-shell-tagline mt-0.5 block text-[10px] font-medium leading-4">Your Dreams, Our Efforts.</span>
      </span>
    </button>

    <p className="tenant-shell-section-label mt-9 mb-2 ml-2.5 hidden text-[9px] font-bold tracking-[1.45px] lg:block">YOUR SPACE</p>

    <nav className="flex flex-col gap-1" aria-label="Tenant navigation">
      {items.map(({ item, label, Icon }) => {
        const active = activeItem === item;
        return (
          <button key={item} type="button" onClick={() => onNavigate(item)} aria-label={label} title={label}
            aria-current={active ? 'location' : undefined}
            className={`tenant-shell-rail-link flex min-h-11 w-full items-center justify-center gap-3 rounded-lg px-2 text-xs font-semibold focus-visible:outline-none lg:justify-start lg:px-[11px] ${active ? 'is-active' : ''}`}>
            <Icon size={16} strokeWidth={1.7} className="shrink-0" aria-hidden="true" />
            <span className="hidden lg:block">{label}</span>
          </button>
        );
      })}
    </nav>

    <div className="tenant-shell-divider my-4 h-px w-full" aria-hidden="true" />
    <p className="tenant-shell-section-label mb-2 ml-2.5 hidden text-[9px] font-bold tracking-[1.45px] lg:block">QUICK REFINE</p>
    <button type="button" onClick={onOpenFilters} aria-label="Filters" title="Filters"
      aria-haspopup="dialog" aria-expanded={quickRefineOpen}
      className={`tenant-shell-rail-link flex min-h-11 w-full items-center justify-center gap-3 rounded-lg px-2 text-xs font-semibold focus-visible:outline-none lg:justify-start lg:px-[11px] ${activeItem === 'filters' ? 'is-active' : ''}`}>
      <SlidersHorizontal size={16} strokeWidth={1.7} className="shrink-0" aria-hidden="true" />
      <span className="hidden lg:block">Filters</span>
    </button>

  </aside>
);
