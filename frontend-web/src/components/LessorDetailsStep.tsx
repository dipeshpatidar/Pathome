import React from 'react';
import type { LessorDetails } from '../services/lessorDraftService';

const FIELD = 'mt-2 min-h-11 w-full rounded-xl border border-slate-300 bg-white px-3 text-base text-slate-900 outline-none focus:border-emerald-600 focus:ring-2 focus:ring-emerald-200';

export function LessorDetailsStep({ value, onChange, onBlur }: {
  value: LessorDetails; onChange: (next: LessorDetails) => void; onBlur: () => void;
}) {
  return <div className="mt-2">
    <h1 className="font-['Outfit',sans-serif] text-3xl font-bold text-slate-950">When is it available?</h1>
    <p className="mt-2 text-sm text-slate-600">Choose a date to finish the essentials. You can add more details below.</p>
    <div className="mt-7 max-w-sm"><label htmlFor="lessor-available" className="text-sm font-semibold text-slate-800">Available from</label><input id="lessor-available" type="date" className={FIELD} value={value.availableFrom || ''} onChange={event => onChange({ ...value, availableFrom: event.target.value || null })} onBlur={onBlur}/></div>
    <details className="mt-8 rounded-2xl border border-slate-200 bg-white p-5 open:pb-6"><summary className="min-h-11 cursor-pointer content-center text-sm font-semibold text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500">Add more details <span className="font-normal text-slate-500">(optional)</span></summary>
      <div className="mt-4 grid gap-5 sm:grid-cols-2"><div><label htmlFor="lessor-furnishing" className="text-sm font-semibold text-slate-800">Furnishing</label><select id="lessor-furnishing" className={FIELD} value={value.furnishingStatus} onChange={event => onChange({ ...value, furnishingStatus: event.target.value })} onBlur={onBlur}><option value="">Choose if known</option><option value="UNFURNISHED">Unfurnished</option><option value="SEMI_FURNISHED">Semi-furnished</option><option value="FULLY_FURNISHED">Fully furnished</option></select></div>
      <div><label htmlFor="lessor-area" className="text-sm font-semibold text-slate-800">Area in sq ft</label><input id="lessor-area" type="number" min={1} inputMode="decimal" className={FIELD} value={value.totalAreaSqFt ?? ''} onChange={event => onChange({ ...value, totalAreaSqFt: event.target.value ? Number(event.target.value) : null })} onBlur={onBlur}/></div>
      <div><label htmlFor="lessor-floor" className="text-sm font-semibold text-slate-800">Floor number</label><input id="lessor-floor" type="number" min={0} inputMode="numeric" className={FIELD} value={value.floorNumber ?? ''} onChange={event => onChange({ ...value, floorNumber: event.target.value ? Number(event.target.value) : null })} onBlur={onBlur}/></div>
      <div><label htmlFor="lessor-total-floors" className="text-sm font-semibold text-slate-800">Floors in building</label><input id="lessor-total-floors" type="number" min={1} inputMode="numeric" className={FIELD} value={value.totalFloors ?? ''} onChange={event => onChange({ ...value, totalFloors: event.target.value ? Number(event.target.value) : null })} onBlur={onBlur}/></div>
      <div className="sm:col-span-2"><label htmlFor="lessor-amenities" className="text-sm font-semibold text-slate-800">Amenities</label><input id="lessor-amenities" maxLength={2000} className={FIELD} placeholder="For example, lift, balcony" value={value.amenities} onChange={event => onChange({ ...value, amenities: event.target.value })} onBlur={onBlur}/></div>
      <div className="sm:col-span-2"><label htmlFor="lessor-description" className="text-sm font-semibold text-slate-800">Description</label><textarea id="lessor-description" maxLength={4000} rows={4} className={`${FIELD} py-3`} value={value.description} onChange={event => onChange({ ...value, description: event.target.value })} onBlur={onBlur}/></div></div>
    </details>
  </div>;
}
