import React from 'react';
import { CalendarDays, ChevronDown, Compass, Heart, Home, KeyRound, ShieldCheck } from 'lucide-react';
import type { TenantMobileDockItem } from '../utils/tenantMobileDock';

interface TenantNavigationRailProps {
  activeItem: TenantMobileDockItem | 'lessor' | 'account' | 'notifications';
  onNavigate: (item: TenantMobileDockItem) => void;
  onOpenLessor: () => void;
  onOpenAccount: () => void;
  hasLessorCapability: boolean | null | 'error';
  savedCount: number | null;
  accountName: string;
}

const items: Array<{ item: TenantMobileDockItem; label: string; Icon: React.ElementType }> = [
  { item: 'home', label: 'Explore homes', Icon: Compass },
  { item: 'saved', label: 'Saved homes', Icon: Heart },
  { item: 'visits', label: 'Visit requests', Icon: CalendarDays }
];

/** Desktop presentation of the existing tenant section and Quick Refine actions. */
export const TenantNavigationRail: React.FC<TenantNavigationRailProps> = ({
  activeItem,
  onNavigate,
  onOpenLessor,
  onOpenAccount,
  hasLessorCapability,
  savedCount,
  accountName
}) => (
  <aside className="tenant-shell-rail fixed inset-y-0 left-0 z-[110] flex-col border-r px-[18px] pb-[17px] pt-[29px]"
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
        const itemCount = item === 'saved' && savedCount !== null && savedCount > 0 ? savedCount : null;
        return (
          <button key={item} type="button" onClick={() => onNavigate(item)}
            aria-label={itemCount ? `${label}, ${itemCount} ${itemCount === 1 ? 'property' : 'properties'}` : label} title={label}
            aria-current={active ? 'location' : undefined}
            className={`tenant-shell-rail-link tenant-shell-primary-link min-h-11 w-full rounded-lg text-xs font-semibold focus-visible:outline-none ${active ? 'is-active' : ''}`}>
            <span className="tenant-shell-rail-icon"><Icon size={16} strokeWidth={1.7} aria-hidden="true" /></span>
            <span className="tenant-shell-rail-label hidden lg:block">{label}</span>
            {itemCount !== null && <small className="tenant-shell-rail-count" aria-hidden="true">{itemCount}</small>}
          </button>
        );
      })}
    </nav>

    <div className="tenant-shell-divider my-4 h-px w-full" aria-hidden="true" />
    <p className="tenant-shell-section-label mb-2 ml-2.5 text-[9px] font-bold tracking-[1.45px]">FOR LESSORS</p>
    <button type="button" onClick={onOpenLessor} aria-current={activeItem === 'lessor' ? 'location' : undefined}
      className={`tenant-shell-rail-link flex min-h-11 w-full items-center gap-3 rounded-lg px-[11px] text-left text-xs font-semibold focus-visible:outline-none ${activeItem === 'lessor' ? 'is-active' : ''}`}>
      <KeyRound size={16} strokeWidth={1.7} aria-hidden="true" />
      <span>{hasLessorCapability === true ? 'Your listings' : 'List your home'}</span>
    </button>
    <div className="flex-1" />
    <div className="tenant-shell-note flex gap-2.5 rounded-[10px] border border-[#eeece7] p-3">
      <span className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-[#edf2ed] text-[#45664f]"><ShieldCheck size={16} aria-hidden="true" /></span>
      <span><strong className="block text-[10px] text-[#39483d]">A more considered move.</strong><small className="mt-1 block text-[9px] leading-4 text-[#85877f]">Thoughtful homes, clearer next steps.</small></span>
    </div>
    <button type="button" onClick={onOpenAccount} aria-current={activeItem === 'account' ? 'page' : undefined} className="tenant-shell-account mt-4 flex min-h-11 w-full items-center gap-2 border-t border-[#eeece7] pt-3 text-left focus-visible:outline-none">
      <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-[#e8ded4] text-[10px] font-bold text-[#675145]">{accountName.trim().charAt(0).toUpperCase() || 'T'}</span>
      <span className="min-w-0 flex-1"><strong className="block truncate text-[10px] text-[#39483d]">{accountName || 'Your account'}</strong><small className="block text-[9px] text-[#85877f]">Tenant account</small></span>
      <ChevronDown size={15} className="text-[#85877f]" aria-hidden="true" />
    </button>
  </aside>
);
