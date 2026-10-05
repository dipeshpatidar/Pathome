import React from 'react';
import { Building2, CalendarDays, Heart, Home } from 'lucide-react';
import type { TenantMobileDockItem } from '../utils/tenantMobileDock';

type SectionItem = Extract<TenantMobileDockItem, 'home' | 'saved' | 'visits'>;

interface TenantMobileDockProps {
  activeItem: TenantMobileDockItem | 'lessor' | 'account' | 'notifications';
  savedCount: number | null;
  visitCount?: number | null;
  hasLessorCapability: boolean | null | 'error';
  onNavigate: (item: SectionItem) => void;
  onOpenListings: () => void;
}

const items = [
  { item: 'home', label: 'Explore', Icon: Home },
  { item: 'saved', label: 'Saved', Icon: Heart },
  { item: 'visits', label: 'Visits', Icon: CalendarDays }
] as const;

export const TenantMobileDock: React.FC<TenantMobileDockProps> = ({
  activeItem,
  savedCount,
  visitCount = null,
  hasLessorCapability,
  onNavigate,
  onOpenListings
}) => (
  <nav className="tenant-v0-mobile-nav" aria-label="Mobile navigation">
    {items.map(({ item, label, Icon }) => {
      const count = item === 'saved' ? savedCount : item === 'visits' ? visitCount : null;
      return <button key={item} type="button" aria-current={activeItem === item ? 'page' : undefined}
        aria-label={item === 'saved' && count !== null && count > 0
          ? `Saved homes, ${count} ${count === 1 ? 'property' : 'properties'}` : label}
        onClick={() => onNavigate(item)}>
        <Icon size={18} strokeWidth={1.8} aria-hidden="true" />
        {count !== null && count > 0 && <i aria-hidden="true">{count > 99 ? '99+' : count}</i>}
        <span>{label}</span>
      </button>;
    })}
    <button type="button" aria-current={activeItem === 'lessor' ? 'page' : undefined} onClick={onOpenListings}>
      <Building2 size={18} strokeWidth={1.8} aria-hidden="true" />
      <span>{hasLessorCapability === true ? 'Listings' : 'List home'}</span>
    </button>
  </nav>
);
