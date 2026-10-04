import React, { useEffect, useId, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  Armchair, BedDouble, Building2, Home, LoaderCircle, SlidersHorizontal, X
} from 'lucide-react';
import { discoverySearchKey, formatRentDisplay, RentalSearchFilters } from '../utils/rentalSearch';
import {
  QUICK_REFINE_BHK_OPTIONS, QUICK_REFINE_FURNISHING, QUICK_REFINE_PROPERTY_TYPES,
  QUICK_REFINE_CHIP_REMOVE_TARGET_PX, QUICK_REFINE_RENT_SLIDER_MAX_POSITION,
  QuickRefineDimension, clearQuickRefineFilters,
  quickRefineActiveCount, quickRefineChipLabels, quickRefineOptionIsSelected,
  quickRefineResultSummary, updateQuickRefineFilter,
  applyQuickRefineRentSliderChanges, quickRefineRentValues,
  quickRefineRentToSliderPosition, quickRefineSliderPositionToRent,
  updateQuickRefineRentSlider,
  type QuickRefineRentBounds, type QuickRefineRentThumb, type QuickRefineRentValues
} from '../utils/tenantQuickRefine';

type DiscoveryState = 'LOADING' | 'READY' | 'ERROR';

interface QuickRefineBaseProps {
  filters: RentalSearchFilters;
  discoveryCity: string;
  discoveryState: DiscoveryState;
  discoveryLoadedKey: string | null;
  homesLoaded: number;
  rentBounds: QuickRefineRentBounds;
  onSearchHomes: (filters: RentalSearchFilters) => void;
}

const focusClass = 'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-700';

const useRentSliderControls = (
  filters: RentalSearchFilters,
  bounds: QuickRefineRentBounds,
  onSearchHomes: (filters: RentalSearchFilters) => void
) => {
  const initialValues = quickRefineRentValues(filters, bounds);
  const [values, setValues] = useState<QuickRefineRentValues>(initialValues);
  const valuesRef = useRef(values);
  const filtersRef = useRef(filters);
  const boundsRef = useRef(bounds);
  const onSearchHomesRef = useRef(onSearchHomes);
  const timerRef = useRef<number | null>(null);
  const changedThumbsRef = useRef<Set<QuickRefineRentThumb>>(new Set());

  useEffect(() => {
    filtersRef.current = filters;
    boundsRef.current = bounds;
    onSearchHomesRef.current = onSearchHomes;
  }, [bounds, filters, onSearchHomes]);

  const cancelPending = () => {
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    timerRef.current = null;
    changedThumbsRef.current.clear();
  };

  useEffect(() => {
    cancelPending();
    const next = quickRefineRentValues(filters, bounds);
    valuesRef.current = next;
    setValues(next);
    return undefined;
  }, [filters.minRent, filters.maxRent, bounds.min, bounds.max, bounds.step]);

  useEffect(() => () => {
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
  }, []);

  const commitPending = () => {
    if (!changedThumbsRef.current.size) return;
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    const changed = [...changedThumbsRef.current];
    changedThumbsRef.current.clear();
    timerRef.current = null;
    onSearchHomesRef.current(applyQuickRefineRentSliderChanges(
      filtersRef.current, boundsRef.current, valuesRef.current, changed
    ));
  };

  const change = (thumb: QuickRefineRentThumb, rawValue: number, immediate = false) => {
    const updated = updateQuickRefineRentSlider(
      filtersRef.current, boundsRef.current, valuesRef.current, thumb, rawValue
    );
    valuesRef.current = updated.values;
    setValues(updated.values);
    changedThumbsRef.current.add(thumb);
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    if (immediate) {
      commitPending();
    } else {
      timerRef.current = window.setTimeout(commitPending, 320);
    }
  };

  return { values, change, commitPending, cancelPending };
};

const FilterOption: React.FC<{
  label: string;
  selected: boolean;
  icon: React.ReactNode;
  onClick: () => void;
}> = ({ label, selected, icon, onClick }) => (
  <button type="button" aria-pressed={selected} onClick={onClick}
      className={`min-h-11 rounded-full p-0.5 transition-transform duration-200 hover:-translate-y-px active:translate-y-0 motion-reduce:transform-none motion-reduce:transition-none ${focusClass}`}>
      <span className={`inline-flex h-9 items-center gap-1.5 rounded-full border px-2.5 text-xs font-medium transition-[background-color,border-color,box-shadow,color] duration-200 ${selected
      ? 'border-[#dce5da] bg-[#edf2ed] text-[#355c49] shadow-none'
      : 'border-[#e5e3da] bg-[#fffefa] text-slate-700 hover:border-emerald-800/30 hover:bg-white'}`}>
      <span className={selected ? 'text-[#638267]' : 'text-emerald-800'} aria-hidden="true">{icon}</span>
      {label}
    </span>
  </button>
);

const FilterGroup: React.FC<{
  title: string;
  children: React.ReactNode;
}> = ({ title, children }) => (
  <fieldset className="min-w-0">
    <legend className="mb-2 text-[11px] font-bold uppercase tracking-[0.13em] text-slate-600">{title}</legend>
    <div className="flex min-w-0 flex-wrap gap-1">{children}</div>
  </fieldset>
);

const QuickRefineOptions: React.FC<{
  filters: RentalSearchFilters;
  onToggle: (dimension: 'bhk' | 'propertyType' | 'furnishing', value: string) => void;
}> = ({ filters, onToggle }) => (
  <div className="space-y-3.5">
    <FilterGroup title="BHK">
      {QUICK_REFINE_BHK_OPTIONS.map(option => <FilterOption key={option.value} label={option.label}
        icon={<BedDouble size={13} />} selected={quickRefineOptionIsSelected(filters, 'bhk', option.value)}
        onClick={() => onToggle('bhk', option.value)} />)}
    </FilterGroup>
    <FilterGroup title="Property type">
      {QUICK_REFINE_PROPERTY_TYPES.map((option, index) => <FilterOption key={option.value} label={option.label}
        icon={index === 0 ? <Building2 size={13} /> : <Home size={13} />}
        selected={quickRefineOptionIsSelected(filters, 'propertyType', option.value)}
        onClick={() => onToggle('propertyType', option.value)} />)}
    </FilterGroup>
    <FilterGroup title="Furnishing">
      {QUICK_REFINE_FURNISHING.map(option => <FilterOption key={option.value} label={option.label}
        icon={<Armchair size={13} />} selected={quickRefineOptionIsSelected(filters, 'furnishing', option.value)}
        onClick={() => onToggle('furnishing', option.value)} />)}
    </FilterGroup>
  </div>
);

const rentRangeLabel = (bounds: QuickRefineRentBounds, values: QuickRefineRentValues) => {
  const hasMin = values.min > bounds.min;
  const hasMax = values.max < bounds.max;
  if (hasMin && hasMax) return `₹${formatRentDisplay(values.min)} – ₹${formatRentDisplay(values.max)}`;
  if (hasMin) return `From ₹${formatRentDisplay(values.min)}`;
  if (hasMax) return `Up to ₹${formatRentDisplay(values.max)}`;
  return 'Any monthly rent';
};

const BudgetSlider: React.FC<{
  bounds: QuickRefineRentBounds;
  values: QuickRefineRentValues;
  onChange: (thumb: QuickRefineRentThumb, value: number) => void;
  onCommit: () => void;
}> = ({ bounds, values, onChange, onCommit }) => {
  const [activeThumb, setActiveThumb] = useState<QuickRefineRentThumb>('min');
  const canAdjust = bounds.max > bounds.min;
  const maxLabel = 'Any';
  const minPosition = quickRefineRentToSliderPosition(values.min, bounds, 'min');
  const maxPosition = quickRefineRentToSliderPosition(values.max, bounds, 'max');
  const left = canAdjust ? `${(minPosition / QUICK_REFINE_RENT_SLIDER_MAX_POSITION) * 100}%` : '0%';
  const width = canAdjust
    ? `${((maxPosition - minPosition) / QUICK_REFINE_RENT_SLIDER_MAX_POSITION) * 100}%` : '0%';

  return <fieldset className="min-w-0">
    <legend className="mb-1 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-600">Monthly rent</legend>
    <div className="mb-1 flex min-w-0 items-center justify-between gap-2 text-[10px] font-medium text-slate-500">
      <span>₹{formatRentDisplay(bounds.min)}</span>
      <span className="truncate rounded-full border border-[#e9e7e1] bg-[#f8f7f4] px-2.5 py-1 text-[11px] font-semibold text-[#355c49]" aria-live="polite">
        {rentRangeLabel(bounds, values)}
      </span>
      <span>{maxLabel}</span>
    </div>
    <div className={`relative mx-2 h-11 ${canAdjust ? '' : 'opacity-50'}`}>
      <div className="absolute inset-x-0 top-1/2 h-[3px] -translate-y-1/2 rounded-full bg-[#dddcd2]" aria-hidden="true" />
      <div className="absolute top-1/2 h-[3px] -translate-y-1/2 rounded-full bg-gradient-to-r from-[#6aa88b] to-[#087151] shadow-[0_0_7px_rgba(16,112,78,.18)]" style={{ left, width }} aria-hidden="true" />
      <input type="range" min={0} max={QUICK_REFINE_RENT_SLIDER_MAX_POSITION - 1}
        step={1} value={minPosition}
        disabled={!canAdjust} aria-label="Minimum monthly rent" aria-valuetext={values.min <= bounds.min
          ? 'No minimum rent' : `Minimum monthly rent ${formatRentDisplay(values.min)} rupees`}
        onFocus={() => setActiveThumb('min')} onChange={event => onChange('min',
          quickRefineSliderPositionToRent(Number(event.currentTarget.value), bounds, 'min'))}
        onPointerUp={onCommit} onPointerCancel={onCommit}
        className={`tenant-quick-refine-slider ${activeThumb === 'min' ? 'is-active' : ''}`} />
      <input type="range" min={0} max={QUICK_REFINE_RENT_SLIDER_MAX_POSITION}
        step={1} value={maxPosition}
        disabled={!canAdjust} aria-label="Maximum monthly rent" aria-valuetext={values.max >= bounds.max
          ? 'No maximum rent' : `Maximum monthly rent ${formatRentDisplay(values.max)} rupees`}
        onFocus={() => setActiveThumb('max')} onChange={event => onChange('max',
          quickRefineSliderPositionToRent(Number(event.currentTarget.value), bounds, 'max'))}
        onPointerUp={onCommit} onPointerCancel={onCommit}
        className={`tenant-quick-refine-slider ${activeThumb === 'max' ? 'is-active' : ''}`} />
    </div>
  </fieldset>;
};

const ResultSummary: React.FC<QuickRefineBaseProps> = props => {
  const updating = props.discoveryState !== 'ERROR' && (props.discoveryState === 'LOADING'
    || props.discoveryLoadedKey !== discoverySearchKey(props.filters));
  return <span className="inline-flex min-w-0 items-center gap-1.5">
    {updating && <LoaderCircle size={12} className="shrink-0 motion-safe:animate-spin" aria-hidden="true" />}
    <span>{quickRefineResultSummary(props.filters, props.discoveryState, props.discoveryLoadedKey, props.homesLoaded)}</span>
  </span>;
};

const useQuickRefineActions = (filters: RentalSearchFilters, discoveryCity: string, onSearchHomes: (filters: RentalSearchFilters) => void) => {
  const toggle = (dimension: 'bhk' | 'propertyType' | 'furnishing', value: string) => {
    onSearchHomes(updateQuickRefineFilter(filters, dimension, value as never));
  };
  const clearDimension = (dimension: QuickRefineDimension) => {
    if (dimension === 'budget') {
      onSearchHomes({ ...filters, minRent: undefined, maxRent: undefined });
    } else if (dimension === 'bhk' || dimension === 'propertyType' || dimension === 'furnishing') {
      const value = filters[dimension];
      if (value) onSearchHomes(updateQuickRefineFilter(filters, dimension, value as never));
    }
  };
  const clearAll = () => {
    onSearchHomes(clearQuickRefineFilters(filters, discoveryCity));
  };
  return { toggle, clearDimension, clearAll };
};

export const TenantQuickRefinePanel: React.FC<QuickRefineBaseProps> = props => {
  const { filters, discoveryCity, onSearchHomes } = props;
  const id = useId();
  const budget = useRentSliderControls(filters, props.rentBounds, onSearchHomes);
  const { toggle, clearAll } = useQuickRefineActions(filters, discoveryCity, onSearchHomes);
  const activeCount = quickRefineActiveCount(filters);
  const clearFilters = () => {
    budget.cancelPending();
    clearAll();
  };

  return (
    <section aria-labelledby={`${id}-title`} className="tenant-quick-refine-panel relative mt-3 hidden min-w-0 rounded-[10px] border border-[#e9e7e1] bg-white p-3.5 shadow-[0_8px_22px_-18px_rgba(38,48,39,.25)] lg:flex xl:p-4">
      <div className="tenant-quick-refine-panel-content relative z-10">
        <div className="flex min-w-0 shrink-0 items-center gap-2.5">
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-[#e9e7e1] bg-[#f3f5f0] text-[#355c49]"><SlidersHorizontal size={16} aria-hidden="true" /></span>
          <div className="min-w-0 flex-1">
            <h2 id={`${id}-title`} className="whitespace-nowrap font-serif text-[17px] font-medium leading-5 tracking-tight text-slate-950">Quick Refine</h2>
            <p className="mt-0.5 min-w-0 truncate text-[11px] leading-4 text-slate-600" aria-live="polite"><ResultSummary {...props} /></p>
          </div>
          {activeCount > 0 && <button type="button" onClick={clearFilters} className={`min-h-11 shrink-0 rounded-lg px-1.5 text-[11px] font-semibold text-emerald-900 underline decoration-emerald-700/35 underline-offset-4 hover:bg-white/70 ${focusClass}`}>Clear all</button>}
        </div>

        <div className={`tenant-quick-refine-scroll-body mt-3.5 pr-1 pb-2 ${focusClass}`} tabIndex={0} aria-label="Quick Refine filters">
          <QuickRefineOptions filters={filters} onToggle={toggle} />
          <div className="mt-3.5 border-t border-emerald-950/10 pt-2.5">
            <BudgetSlider bounds={props.rentBounds} values={budget.values}
              onChange={budget.change} onCommit={budget.commitPending} />
          </div>
          {activeCount > 0 && <p className="relative mt-1 text-right text-[10px] font-medium text-slate-500">{activeCount} {activeCount === 1 ? 'filter' : 'filters'} applied</p>}
        </div>
      </div>
    </section>
  );
};

interface TenantQuickRefineMobileProps extends QuickRefineBaseProps {
  openRequestRef: React.MutableRefObject<(() => void) | null>;
  onSheetOpenChange: (isOpen: boolean) => void;
}

export const TenantQuickRefineMobile: React.FC<TenantQuickRefineMobileProps> = props => {
  const { filters, discoveryCity, onSearchHomes } = props;
  const id = useId();
  const budget = useRentSliderControls(filters, props.rentBounds, onSearchHomes);
  const { toggle, clearDimension, clearAll } = useQuickRefineActions(filters, discoveryCity, onSearchHomes);
  const activeCount = quickRefineActiveCount(filters);
  const chips = useMemo(() => quickRefineChipLabels(filters), [filters]);
  const [isOpen, setIsOpen] = useState(false);
  const sheetRef = useRef<HTMLDivElement>(null);

  const closeSheet = (restoreFocus: boolean) => {
    props.onSheetOpenChange(false);
    setIsOpen(false);
    if (restoreFocus) window.setTimeout(() => document.getElementById('tenant-mobile-quick-refine-trigger')?.focus(), 0);
  };

  useEffect(() => {
    const open = () => {
      props.onSheetOpenChange(true);
      setIsOpen(true);
    };
    props.openRequestRef.current = open;
    return () => {
      if (props.openRequestRef.current === open) props.openRequestRef.current = null;
    };
  }, [props.openRequestRef, props.onSheetOpenChange]);

  const clearFilters = () => {
    budget.cancelPending();
    clearAll();
  };

  useEffect(() => {
    if (!isOpen) return undefined;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    window.setTimeout(() => sheetRef.current?.querySelector<HTMLElement>('button, input')?.focus(), 0);

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        closeSheet(true);
        return;
      }
      if (event.key !== 'Tab' || !sheetRef.current) return;
      const focusable = [...sheetRef.current.querySelectorAll<HTMLElement>(
        'button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])'
      )].filter(element => element.offsetParent !== null);
      if (!focusable.length) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.body.style.overflow = previousOverflow;
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [isOpen]);

  return <>
    {chips.length > 0 && <div className="mb-4 min-w-0 lg:hidden" aria-label="Active search filters">
      <div className="flex min-w-0 items-center gap-2 overflow-x-auto overscroll-x-contain pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
        {chips.map(chip => <span key={chip.dimension} className="relative inline-flex min-h-10 shrink-0 items-center rounded-full border border-emerald-800/20 bg-[#edf5ec] pl-3 pr-11 text-xs font-medium text-emerald-950">
          {chip.label}
          <button type="button" onClick={() => clearDimension(chip.dimension)} aria-label={`Remove ${chip.label} filter`}
            style={{ width: QUICK_REFINE_CHIP_REMOVE_TARGET_PX, height: QUICK_REFINE_CHIP_REMOVE_TARGET_PX }}
            className={`absolute right-0 top-1/2 flex -translate-y-1/2 items-center justify-center rounded-full text-emerald-900 hover:bg-white/80 ${focusClass}`}><X size={14} aria-hidden="true" /></button>
        </span>)}
      </div>
    </div>}

    {isOpen && createPortal(
      <div className="fixed inset-0 z-[100] flex items-end justify-center lg:hidden" role="presentation">
        <button type="button" tabIndex={-1} aria-label="Close filters" onClick={() => closeSheet(true)} className="absolute inset-0 bg-[#101b17]/45 backdrop-blur-[2px]" />
        <section ref={sheetRef} id="tenant-mobile-quick-refine" role="dialog" aria-modal="true" aria-labelledby={`${id}-title`}
          className="relative flex max-h-[min(92dvh,56rem)] w-full flex-col overflow-hidden rounded-t-[15px] border border-[#e9e7e1] bg-white shadow-[0_-16px_48px_-24px_rgba(37,43,37,.3)]">
          <div className="relative z-10 shrink-0 border-b border-[#eeece7] px-5 pb-4 pt-3" style={{ paddingTop: 'max(.75rem, env(safe-area-inset-top))' }}>
            <div className="mx-auto mb-3 h-1 w-10 rounded-full bg-emerald-900/20" aria-hidden="true" />
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-start gap-3">
                <span className="mt-0.5 flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-[#f3f5f0] text-[#355c49]"><SlidersHorizontal size={18} aria-hidden="true" /></span>
                <div className="relative z-10"><h2 id={`${id}-title`} className="font-serif text-2xl font-medium tracking-tight text-slate-950">Quick Refine</h2>
                  <p className="mt-1 text-xs text-slate-600" aria-live="polite"><ResultSummary {...props} /></p>
                </div>
              </div>
              <div className="flex items-center gap-1">
                {activeCount > 0 && <button type="button" onClick={clearFilters} className={`relative z-10 min-h-11 rounded-lg px-2 text-xs font-semibold text-emerald-900 underline underline-offset-4 ${focusClass}`}>Clear all</button>}
                <button type="button" onClick={() => closeSheet(true)} aria-label="Close Quick Refine" className={`flex h-11 w-11 items-center justify-center rounded-full border border-[#e3e4dc] bg-white/80 text-slate-700 hover:bg-white ${focusClass}`}><X size={18} aria-hidden="true" /></button>
              </div>
            </div>
          </div>

          <div className="relative z-10 min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 py-5">
            <QuickRefineOptions filters={filters} onToggle={toggle} />
            <div className="mt-5 border-t border-[#eeece7] pt-4">
          <BudgetSlider bounds={props.rentBounds} values={budget.values}
                onChange={budget.change} onCommit={budget.commitPending} />
            </div>
            {activeCount > 0 && <p className="mt-5 text-xs font-medium text-slate-600">{activeCount} {activeCount === 1 ? 'filter' : 'filters'} applied</p>}
          </div>
          <div className="safe-area-bottom shrink-0 bg-[#fffefa]/75 px-5 pt-2" aria-hidden="true" />
        </section>
      </div>, document.body
    )}
  </>;
};
