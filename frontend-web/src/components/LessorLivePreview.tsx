import React from 'react';
import { Building2, Camera, MapPin, ShieldCheck } from 'lucide-react';
import type { ResidentialType } from '../services/lessorDraftService';
import { lessorFurnishingLabel } from '../utils/lessorDetails';

const MONEY = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  maximumFractionDigits: 0
});

export interface LessorLivePreviewProps {
  propertyType: ResidentialType | null;
  bhkCount: string | null;
  monthlyRent: number | null;
  securityDeposit: number | null;
  locality?: string;
  city?: string;
  coverUrl?: string | null;
  mediaCount?: number;
  availableFrom?: string | null;
  furnishingStatus?: string | null;
  totalAreaSqFt?: number | null;
}

const TYPE_NAMES: Record<ResidentialType, string> = {
  FLAT: 'Flat',
  HOUSE: 'House',
  STUDIO: 'Studio Apartment',
  PENTHOUSE: 'Penthouse',
  SERVICED_APARTMENT: 'Serviced Apartment'
};

export const LessorLivePreview: React.FC<LessorLivePreviewProps> = ({
  propertyType,
  bhkCount,
  monthlyRent,
  securityDeposit,
  locality,
  city,
  coverUrl,
  mediaCount = 0,
  availableFrom,
  furnishingStatus,
  totalAreaSqFt
}) => {
  const typeLabel = propertyType ? TYPE_NAMES[propertyType] : 'Rental Property';
  const title = bhkCount ? `${bhkCount} ${typeLabel}` : typeLabel;
  const locationLabel = [locality, city].filter(Boolean).join(', ') || 'Location not added yet';
  const rentLabel = monthlyRent !== null ? `${MONEY.format(monthlyRent)} / month` : 'Rent not set';
  const depositLabel = securityDeposit !== null ? `Deposit: ${MONEY.format(securityDeposit)}` : 'Deposit to add';

  return (
    <aside
      aria-label="Live property preview"
      className="sticky top-24 rounded-2xl border border-slate-200/90 bg-white p-5 shadow-sm"
    >
      {/* PREVIEW HEADER */}
      <div className="flex items-center justify-between border-b border-slate-100 pb-3">
        <div className="flex items-center gap-2">
          <div className="flex h-6 w-6 items-center justify-center rounded-lg bg-emerald-100 text-emerald-800">
            <Building2 className="h-3.5 w-3.5" />
          </div>
          <span className="text-xs font-bold uppercase tracking-wider text-slate-900">
            Live Preview
          </span>
        </div>
        <span className="rounded-full border border-emerald-200 bg-emerald-50 px-2 py-0.5 text-[11px] font-semibold text-emerald-800">
          Tenant view
        </span>
      </div>

      {/* COVER MEDIA OR PLACEHOLDER */}
      <div className="relative mt-4 aspect-[16/10] overflow-hidden rounded-xl bg-slate-100">
        {coverUrl ? (
          <img
            src={coverUrl}
            alt="Property cover preview"
            className="h-full w-full object-cover"
          />
        ) : (
          <div className="flex h-full flex-col items-center justify-center gap-2 text-slate-400">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-slate-200/70 text-slate-500">
              <Camera className="h-5 w-5" />
            </div>
            <span className="text-xs font-medium text-slate-500">
              {mediaCount > 0 ? `${mediaCount} photos staged` : 'Photos will appear here'}
            </span>
          </div>
        )}
        {coverUrl && (
          <span className="absolute bottom-2.5 left-2.5 rounded-md bg-slate-950/75 px-2 py-0.5 text-[10px] font-semibold text-white backdrop-blur-xs">
            Cover Photo
          </span>
        )}
      </div>

      {/* PROPERTY IDENTITY */}
      <div className="mt-4">
        <p className="text-[11px] font-bold uppercase tracking-wider text-emerald-700">
          {typeLabel} · Long-term
        </p>
        <h3 className="mt-1 line-clamp-1 break-words font-['Outfit',sans-serif] text-xl font-bold text-slate-950">
          {title}
        </h3>
        <p className="mt-1 flex items-center gap-1.5 truncate text-xs text-slate-600">
          <MapPin className="h-3.5 w-3.5 shrink-0 text-slate-400" />
          <span className="truncate">{locationLabel}</span>
        </p>
      </div>

      {/* RENT & DEPOSIT */}
      <div className="mt-4 flex items-baseline justify-between border-t border-slate-100 pt-3">
        <div>
          <span className="text-lg font-black text-slate-950">{rentLabel}</span>
        </div>
        <span className="text-xs text-slate-500">{depositLabel}</span>
      </div>

      {/* KEY ATTRIBUTES SUMMARY */}
      <div className="mt-3 grid grid-cols-2 gap-2 rounded-xl border border-slate-100 bg-slate-50 p-2.5 text-xs">
        <div>
          <span className="block text-[10px] font-bold uppercase text-slate-400">Available</span>
          <span className="font-semibold text-slate-800">
            {availableFrom || 'Not added yet'}
          </span>
        </div>
        <div>
          <span className="block text-[10px] font-bold uppercase text-slate-400">Furnishing</span>
          <span className="font-semibold text-slate-800">
            {lessorFurnishingLabel(furnishingStatus) ?? 'Not added yet'}
          </span>
        </div>
        <div>
          <span className="block text-[10px] font-bold uppercase text-slate-400">Area</span>
          <span className="font-semibold text-slate-800">
            {totalAreaSqFt ? `${totalAreaSqFt} sq ft` : 'Not added yet'}
          </span>
        </div>
        <div>
          <span className="block text-[10px] font-bold uppercase text-slate-400">Photos</span>
          <span className="font-semibold text-slate-800">
            {mediaCount} {mediaCount === 1 ? 'photo' : 'photos'}
          </span>
        </div>
      </div>

      {/* PRIVACY ASSURANCE */}
      <div className="mt-4 flex items-center gap-2 rounded-xl bg-slate-50 px-3 py-2 text-[11px] text-slate-500">
        <ShieldCheck className="h-4 w-4 shrink-0 text-emerald-600" />
        <span>Your full address and contact stay private until booking confirmation.</span>
      </div>
    </aside>
  );
};
