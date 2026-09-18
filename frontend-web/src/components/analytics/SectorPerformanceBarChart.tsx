import React, { useState } from 'react';
import {
  motion,
  AnimatePresence
} from 'framer-motion';
import {
  MapPin,
  Award,
  BarChart3,
  LayoutGrid,
  Sparkles,
  Filter,
  Zap,
  Activity,
  Clock
} from 'lucide-react';

export interface SectorData {
  id: string;
  sector: string;
  activeListings: number;
  avgRent: number;
  toursCount: number;
  conversionPct: number;
  demandScore: number;
  avgDomDays: number; // Average Days on Market
  yieldPct: number; // Rental Yield %
  tag: string;
  color: string;
  sparkline: number[]; // 7-day trend history points
}

const INDORE_SECTOR_DATA: SectorData[] = [
  {
    id: 'vijay-nagar',
    sector: 'Vijay Nagar',
    activeListings: 48,
    avgRent: 22500,
    toursCount: 84,
    conversionPct: 42.5,
    demandScore: 98,
    avgDomDays: 4.2,
    yieldPct: 8.4,
    tag: 'Extreme Surge 🔥',
    color: 'emerald',
    sparkline: [32, 35, 38, 41, 45, 46, 48]
  },
  {
    id: 'bhawarkua',
    sector: 'Bhawarkua',
    activeListings: 42,
    avgRent: 18000,
    toursCount: 76,
    conversionPct: 39.1,
    demandScore: 92,
    avgDomDays: 5.1,
    yieldPct: 7.9,
    tag: 'High Student Volume ⚡',
    color: 'teal',
    sparkline: [28, 30, 33, 36, 39, 40, 42]
  },
  {
    id: 'palasia',
    sector: 'Palasia & Old City',
    activeListings: 31,
    avgRent: 26500,
    toursCount: 58,
    conversionPct: 34.8,
    demandScore: 88,
    avgDomDays: 7.8,
    yieldPct: 7.2,
    tag: 'Premium Prime 👑',
    color: 'indigo',
    sparkline: [22, 24, 25, 27, 29, 30, 31]
  },
  {
    id: 'super-corridor',
    sector: 'Super Corridor',
    activeListings: 28,
    avgRent: 16500,
    toursCount: 49,
    conversionPct: 28.4,
    demandScore: 85,
    avgDomDays: 9.4,
    yieldPct: 8.1,
    tag: 'IT Hub Growth 🚀',
    color: 'cyan',
    sparkline: [18, 20, 22, 23, 25, 26, 28]
  },
  {
    id: 'nipania',
    sector: 'Nipania & Bypass',
    activeListings: 22,
    avgRent: 24000,
    toursCount: 41,
    conversionPct: 31.0,
    demandScore: 82,
    avgDomDays: 8.6,
    yieldPct: 7.5,
    tag: 'Emerging Elite ✨',
    color: 'amber',
    sparkline: [15, 16, 18, 19, 20, 21, 22]
  },
  {
    id: 'rau',
    sector: 'Rau & AB Road',
    activeListings: 19,
    avgRent: 14500,
    toursCount: 35,
    conversionPct: 25.6,
    demandScore: 76,
    avgDomDays: 11.2,
    yieldPct: 6.8,
    tag: 'Steady Growth 📈',
    color: 'rose',
    sparkline: [12, 13, 14, 15, 17, 18, 19]
  }
];

type MetricKey = 'activeListings' | 'avgRent' | 'toursCount' | 'conversionPct';
type ViewMode = 'vertical' | 'horizontal' | 'matrix';

export const SectorPerformanceBarChart: React.FC = () => {
  const [timeGranularity, setTimeGranularity] = useState<'DAY' | 'WEEK' | 'MONTH' | 'YEAR'>('MONTH');
  const [activeMetric, setActiveMetric] = useState<MetricKey>('activeListings');
  const [viewMode, setViewMode] = useState<ViewMode>('vertical');
  const [hoveredSector, setHoveredSector] = useState<SectorData | null>(null);
  const [tappedSector, setTappedSector] = useState<SectorData | null>(null);
  const [sortBy, setSortBy] = useState<'default' | 'highest'>('highest');
  
  // AI Predictor Simulator State
  const [listingGrowthPct, setListingGrowthPct] = useState<number>(15);
  const [rentDeltaAmount, setRentDeltaAmount] = useState<number>(1500);

  // Compute metric specifics
  const getMetricMeta = (key: MetricKey) => {
    switch (key) {
      case 'activeListings':
        return {
          label: 'Active Listings',
          unit: 'Homes',
          prefix: '',
          suffix: ' Listings',
          maxVal: 55,
          gradient: 'from-emerald-500 via-teal-500 to-cyan-400',
          bgHighlight: 'bg-emerald-500/10 border-emerald-500/30 text-emerald-700',
          accentHex: '#10b981'
        };
      case 'avgRent':
        return {
          label: 'Average Monthly Rent',
          unit: '₹/mo',
          prefix: '₹',
          suffix: '/mo',
          maxVal: 30000,
          gradient: 'from-amber-500 via-orange-500 to-yellow-400',
          bgHighlight: 'bg-amber-500/10 border-amber-500/30 text-amber-700',
          accentHex: '#f59e0b'
        };
      case 'toursCount':
        return {
          label: 'Escorted Site Tours',
          unit: 'Visits',
          prefix: '',
          suffix: ' Tours',
          maxVal: 100,
          gradient: 'from-indigo-500 via-purple-500 to-pink-400',
          bgHighlight: 'bg-indigo-500/10 border-indigo-500/30 text-indigo-700',
          accentHex: '#6366f1'
        };
      case 'conversionPct':
        return {
          label: 'Lead Conversion Rate',
          unit: '%',
          prefix: '',
          suffix: '%',
          maxVal: 50,
          gradient: 'from-cyan-500 via-teal-500 to-emerald-400',
          bgHighlight: 'bg-cyan-500/10 border-cyan-500/30 text-cyan-700',
          accentHex: '#06b6d4'
        };
    }
  };

  const meta = getMetricMeta(activeMetric);

  // Sorted sector list
  const sectors = [...INDORE_SECTOR_DATA].sort((a, b) => {
    if (sortBy === 'highest') {
      return b[activeMetric] - a[activeMetric];
    }
    return 0;
  });

  const activeSectorFocus = tappedSector || hoveredSector;

  // AI Predictor calculations
  const totalBaseMonthlyRevenue = INDORE_SECTOR_DATA.reduce((acc, curr) => acc + (curr.activeListings * curr.avgRent * (curr.conversionPct / 100)), 0);
  const simulatedMonthlyRevenue = INDORE_SECTOR_DATA.reduce((acc, curr) => {
    const simListings = Math.round(curr.activeListings * (1 + listingGrowthPct / 100));
    const simRent = curr.avgRent + rentDeltaAmount;
    return acc + (simListings * simRent * (curr.conversionPct / 100));
  }, 0);

  const revenueDelta = simulatedMonthlyRevenue - totalBaseMonthlyRevenue;
  const simulatedEscortsNeeded = Math.ceil((INDORE_SECTOR_DATA.reduce((acc, s) => acc + s.toursCount, 0) * (1 + listingGrowthPct / 100)) / 45);

  return (
    <div className="bg-white rounded-3xl p-4 sm:p-7 border border-slate-200/90 shadow-sm space-y-6 select-none relative overflow-hidden font-['Inter',sans-serif]">
      
      {/* 1. TOP HEADER & CONTROLS BAR */}
      <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4 pb-6 border-b border-slate-100">
        <div>
          <div className="flex items-center gap-2">
            <span className="text-[10px] font-black text-emerald-800 bg-emerald-50 px-2.5 py-1 rounded-full border border-emerald-200 uppercase font-mono flex items-center gap-1.5 shadow-2xs">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-ping"></span>
              Indore Micro-Market Radar
            </span>
            <span className="text-xs text-slate-500 font-mono hidden sm:inline">6 Core Hubs</span>
          </div>

          <h3 className="text-xl sm:text-2xl font-black text-slate-900 font-['Outfit'] mt-1 tracking-tight flex items-center gap-2">
            Indore Sector Analytics & Yield Graph
          </h3>
          <p className="text-xs text-slate-500 mt-0.5">
            Real-time sector comparison, AI revenue projection simulator, and touch-friendly mobile bar deck.
          </p>
        </div>

        {/* CONTROLS: SORT TOGGLE & VIEW MODE SWITCHER */}
        <div className="flex flex-wrap items-center gap-2 shrink-0">
          
          {/* SORT TOGGLE */}
          <button
            onClick={() => setSortBy(sortBy === 'highest' ? 'default' : 'highest')}
            className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-all border flex items-center gap-1.5 cursor-pointer active:scale-95 ${
              sortBy === 'highest'
                ? 'bg-slate-900 text-white border-slate-800 shadow-xs'
                : 'bg-slate-50 text-slate-700 border-slate-200 hover:bg-slate-100'
            }`}
          >
            <Filter className="w-3.5 h-3.5" />
            <span>{sortBy === 'highest' ? 'Sorted High → Low' : 'Default Order'}</span>
          </button>

          {/* VIEW SWITCHER: VERTICAL, HORIZONTAL, MATRIX */}
          <div className="bg-slate-100 p-1 rounded-xl border border-slate-200 flex items-center gap-1">
            <button
              onClick={() => setViewMode('vertical')}
              className={`p-1.5 rounded-lg text-xs font-bold transition-all flex items-center gap-1 cursor-pointer ${
                viewMode === 'vertical'
                  ? 'bg-white text-slate-900 shadow-2xs border border-slate-200/80 font-black'
                  : 'text-slate-500 hover:text-slate-900'
              }`}
              title="3D Vertical Column Graph"
            >
              <BarChart3 className="w-4 h-4" />
              <span className="hidden sm:inline">Columns</span>
            </button>
            
            <button
              onClick={() => setViewMode('horizontal')}
              className={`p-1.5 rounded-lg text-xs font-bold transition-all flex items-center gap-1 cursor-pointer ${
                viewMode === 'horizontal'
                  ? 'bg-white text-slate-900 shadow-2xs border border-slate-200/80 font-black'
                  : 'text-slate-500 hover:text-slate-900'
              }`}
              title="Horizontal Tracks"
            >
              <LayoutGrid className="w-4 h-4" />
              <span className="hidden sm:inline">Bars</span>
            </button>

            <button
              onClick={() => setViewMode('matrix')}
              className={`p-1.5 rounded-lg text-xs font-bold transition-all flex items-center gap-1 cursor-pointer ${
                viewMode === 'matrix'
                  ? 'bg-white text-slate-900 shadow-2xs border border-slate-200/80 font-black'
                  : 'text-slate-500 hover:text-slate-900'
              }`}
              title="Micro-Market Heat Matrix"
            >
              <Activity className="w-4 h-4" />
              <span className="hidden sm:inline">Yield Matrix</span>
            </button>
          </div>

        </div>
      </div>

      {/* 1.5. TIME HORIZON GRANULARITY SWITCHER */}
      <div className="flex flex-wrap items-center justify-between gap-3 bg-slate-900 text-white p-2 sm:p-2.5 rounded-2xl border border-slate-800 shadow-inner">
        <div className="flex items-center gap-1.5 text-xs font-mono font-bold text-slate-300 pl-1">
          <Clock className="w-3.5 h-3.5 text-emerald-400 animate-spin" style={{ animationDuration: '6s' }} />
          <span>Analysis Time Horizon:</span>
        </div>

        <div className="flex items-center gap-1 overflow-x-auto no-scrollbar">
          {[
            { id: 'DAY', label: '📅 Day-wise' },
            { id: 'WEEK', label: '📆 Week-wise' },
            { id: 'MONTH', label: '🗓️ Month-wise' },
            { id: 'YEAR', label: '📊 Year-wise' }
          ].map(g => (
            <button
              key={g.id}
              onClick={() => setTimeGranularity(g.id as any)}
              className={`px-3 py-1 rounded-xl text-xs font-extrabold transition-all cursor-pointer whitespace-nowrap ${
                timeGranularity === g.id
                  ? 'bg-emerald-500 text-slate-950 shadow-md shadow-emerald-500/30 scale-105'
                  : 'text-slate-400 hover:text-white hover:bg-slate-800'
              }`}
            >
              {g.label}
            </button>
          ))}
        </div>
      </div>

      {/* 2. METRIC SELECTOR PILLS */}
      <div className="flex items-center gap-2 overflow-x-auto pb-1 no-scrollbar">
        {[
          { key: 'activeListings', label: '🏠 Active Listings' },
          { key: 'avgRent', label: '💰 Avg Monthly Rent' },
          { key: 'toursCount', label: '🚶 Escorted Tours' },
          { key: 'conversionPct', label: '🎯 Lead Conversion %' }
        ].map((item) => {
          const isActive = activeMetric === item.key;
          return (
            <button
              key={item.key}
              onClick={() => setActiveMetric(item.key as MetricKey)}
              className={`px-3.5 py-2 rounded-2xl text-xs font-extrabold transition-all duration-200 flex items-center gap-2 border cursor-pointer shrink-0 active:scale-95 ${
                isActive
                  ? 'bg-slate-900 text-white border-slate-800 shadow-md shadow-slate-900/20 scale-[1.02]'
                  : 'bg-slate-50 text-slate-600 border-slate-200 hover:bg-slate-100 hover:text-slate-900'
              }`}
            >
              <span className={isActive ? 'text-emerald-400 font-black' : 'text-slate-500'}>
                {item.label}
              </span>
            </button>
          );
        })}
      </div>

      {/* 3. DYNAMIC CHART DISPLAY (COLUMNS / BARS / MATRIX) */}
      {viewMode === 'vertical' && (
        
        /* VERTICAL BAR COLUMNS GRAPH WITH SVG TREND SPARKLINES */
        <div className="bg-slate-950 text-white rounded-3xl p-5 sm:p-7 border border-slate-800 space-y-6 relative overflow-hidden shadow-2xl">
          
          {/* HEADER STRIP */}
          <div className="flex items-center justify-between text-xs font-mono border-b border-slate-800/80 pb-3">
            <span className="text-slate-400 flex items-center gap-1.5 font-bold">
              <Sparkles className="w-3.5 h-3.5 text-emerald-400 animate-pulse" />
              Metric: <strong className="text-white font-['Outfit']">{meta.label}</strong>
            </span>
            <span className="text-slate-400">
              Peak: <strong className="text-emerald-400">{meta.prefix}{Math.max(...sectors.map(s => s[activeMetric])).toLocaleString('en-IN')}{meta.suffix}</strong>
            </span>
          </div>

          {/* CANVAS GRAPH AREA */}
          <div className="relative h-72 sm:h-80 w-full flex items-end justify-between pt-8 pb-8 px-1 sm:px-4 touch-pan-x overflow-x-auto no-scrollbar">
            
            {/* BACKGROUND SVG GRID LINES */}
            <div className="absolute inset-x-4 top-8 bottom-8 flex flex-col justify-between pointer-events-none z-0">
              {[100, 75, 50, 25, 0].map((level) => (
                <div key={level} className="border-b border-slate-800/80 w-full flex items-center justify-between text-[10px] font-mono text-slate-600">
                  <span className="-translate-y-2">{Math.round((meta.maxVal * level) / 100).toLocaleString()}</span>
                  <span className="-translate-y-2 opacity-40">{level}%</span>
                </div>
              ))}
            </div>

            {/* COLUMNS */}
            {sectors.map((s, idx) => {
              const rawVal = s[activeMetric];
              const heightPct = Math.min(Math.max((rawVal / meta.maxVal) * 100, 10), 100);
              const isSelected = activeSectorFocus?.id === s.id;

              return (
                <div
                  key={s.id}
                  onMouseEnter={() => setHoveredSector(s)}
                  onMouseLeave={() => setHoveredSector(null)}
                  onClick={() => setTappedSector(tappedSector?.id === s.id ? null : s)}
                  className="relative flex-1 min-w-[50px] max-w-[90px] flex flex-col items-center justify-end h-full group z-10 px-1 sm:px-2 cursor-pointer"
                >
                  {/* VALUE BADGE */}
                  <motion.div
                    initial={{ opacity: 0, y: 10 }}
                    animate={{ opacity: 1, y: 0 }}
                    className={`mb-2 px-2 py-0.5 rounded-lg text-[10px] font-extrabold font-mono transition-all shadow-md whitespace-nowrap ${
                      isSelected
                        ? 'bg-emerald-500 text-slate-950 scale-110 shadow-emerald-500/30'
                        : 'bg-slate-900 text-slate-200 border border-slate-800'
                    }`}
                  >
                    {meta.prefix}{rawVal.toLocaleString('en-IN')}{meta.suffix}
                  </motion.div>

                  {/* COLUMN BAR CONTAINER */}
                  <div className="w-full max-w-[44px] bg-slate-900/90 rounded-2xl p-1 border border-slate-800/80 flex flex-col justify-end h-full overflow-hidden relative shadow-inner">
                    
                    {/* MINI SVG SPARKLINE OVERLAY INSIDE BAR */}
                    <div className="absolute inset-0 opacity-20 pointer-events-none flex items-center justify-center p-1">
                      <svg className="w-full h-full" viewBox="0 0 100 50" preserveAspectRatio="none">
                        <path
                          d={`M 0 ${50 - s.sparkline[0]} L 16 ${50 - s.sparkline[1]} L 33 ${50 - s.sparkline[2]} L 50 ${50 - s.sparkline[3]} L 66 ${50 - s.sparkline[4]} L 83 ${50 - s.sparkline[5]} L 100 ${50 - s.sparkline[6]}`}
                          fill="none"
                          stroke="#ffffff"
                          strokeWidth="3"
                        />
                      </svg>
                    </div>

                    <motion.div
                      initial={{ height: 0 }}
                      animate={{ height: `${heightPct}%` }}
                      transition={{ duration: 0.7, delay: idx * 0.08, ease: [0.16, 1, 0.3, 1] }}
                      className={`w-full rounded-xl bg-gradient-to-t ${meta.gradient} transition-all duration-300 relative shadow-lg ${
                        isSelected ? 'brightness-125 shadow-[0_0_24px_rgba(16,185,129,0.6)] scale-[1.03]' : 'group-hover:brightness-110'
                      }`}
                    >
                      {/* TOP GLOW REFLECTION */}
                      <div className="absolute top-0 inset-x-0 h-1.5 bg-white/40 rounded-t-xl" />
                    </motion.div>
                  </div>

                  {/* BOTTOM SECTOR LABEL */}
                  <span className={`mt-3 text-[11px] font-extrabold font-['Outfit'] truncate max-w-[80px] text-center transition-colors ${
                    isSelected ? 'text-emerald-400 font-black' : 'text-slate-400 group-hover:text-slate-200'
                  }`}>
                    {s.sector.split(' ')[0]}
                  </span>
                </div>
              );
            })}
          </div>

          {/* FOOTER TIP */}
          <div className="flex flex-wrap items-center justify-between text-[11px] font-mono text-slate-400 pt-3 border-t border-slate-800/80">
            <span>📱 Mobile Friendly: Tap any column to lock metadata details.</span>
            <span className="text-emerald-400 font-bold">Updated Live from Geofences</span>
          </div>

        </div>
      )}

      {viewMode === 'horizontal' && (
        
        /* HORIZONTAL COMPARISON TRACK DECK */
        <div className="space-y-3 pt-1">
          {sectors.map((s, idx) => {
            const rawVal = s[activeMetric];
            const widthPct = Math.min(Math.max((rawVal / meta.maxVal) * 100, 6), 100);
            const isSelected = activeSectorFocus?.id === s.id;

            return (
              <motion.div
                key={s.id}
                onMouseEnter={() => setHoveredSector(s)}
                onMouseLeave={() => setHoveredSector(null)}
                onClick={() => setTappedSector(tappedSector?.id === s.id ? null : s)}
                initial={{ opacity: 0, x: -12 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ duration: 0.3, delay: idx * 0.05 }}
                className={`p-4 rounded-2xl border transition-all cursor-pointer ${
                  isSelected
                    ? 'bg-slate-950 text-white border-slate-800 shadow-xl shadow-slate-900/30 scale-[1.01]'
                    : 'bg-slate-50 text-slate-900 border-slate-200/90 hover:bg-slate-100/80'
                }`}
              >
                <div className="flex justify-between items-center text-xs mb-2">
                  <div className="flex items-center gap-2">
                    <span className="font-black font-['Outfit'] text-sm sm:text-base">{s.sector}</span>
                    <span className={`px-2 py-0.5 rounded-full text-[10px] font-mono font-bold border ${
                      isSelected ? 'bg-emerald-500/20 text-emerald-300 border-emerald-500/30' : 'bg-slate-200/80 text-slate-700 border-slate-300'
                    }`}>
                      {s.tag}
                    </span>
                  </div>

                  <div className="font-mono text-xs font-black">
                    <span className={isSelected ? 'text-emerald-400' : 'text-slate-900'}>
                      {meta.prefix}{rawVal.toLocaleString('en-IN')}{meta.suffix}
                    </span>
                  </div>
                </div>

                {/* PROGRESS TRACK */}
                <div className="w-full bg-slate-200/80 h-3.5 rounded-full overflow-hidden p-0.5 border border-slate-300/60 relative">
                  <motion.div
                    initial={{ width: 0 }}
                    animate={{ width: `${widthPct}%` }}
                    transition={{ duration: 0.7, delay: idx * 0.08, ease: [0.16, 1, 0.3, 1] }}
                    className={`h-full rounded-full bg-gradient-to-r ${meta.gradient} shadow-sm relative ${
                      isSelected ? 'brightness-110 shadow-emerald-500/40' : ''
                    }`}
                  />
                </div>

                {/* METRIC STRIP */}
                <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-[11px] font-mono mt-2.5 text-slate-500">
                  <span>Listings: <strong className={isSelected ? 'text-white' : 'text-slate-800'}>{s.activeListings}</strong></span>
                  <span>Avg Rent: <strong className="text-emerald-600 font-bold">₹{s.avgRent.toLocaleString('en-IN')}</strong></span>
                  <span>DOM: <strong className="text-amber-600 font-bold">{s.avgDomDays} Days</strong></span>
                  <span>Yield: <strong className="text-indigo-600 font-bold">{s.yieldPct}%</strong></span>
                </div>
              </motion.div>
            );
          })}
        </div>
      )}

      {viewMode === 'matrix' && (
        
        /* MICRO-MARKET HEAT & YIELD MATRIX DECK */
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 pt-1">
          {sectors.map((s) => (
            <div
              key={s.id}
              onClick={() => setTappedSector(tappedSector?.id === s.id ? null : s)}
              className="bg-slate-950 text-white rounded-3xl p-5 border border-slate-800 space-y-4 hover:border-emerald-500/50 transition-all cursor-pointer shadow-lg relative overflow-hidden"
            >
              <div className="flex items-center justify-between border-b border-slate-800 pb-3">
                <div>
                  <h4 className="font-black font-['Outfit'] text-base text-white">{s.sector}</h4>
                  <span className="text-[10px] text-emerald-400 font-mono font-bold">{s.tag}</span>
                </div>
                <div className="w-9 h-9 rounded-2xl bg-emerald-500/20 text-emerald-400 border border-emerald-500/30 flex items-center justify-center font-bold font-mono text-xs">
                  {s.demandScore}
                </div>
              </div>

              <div className="grid grid-cols-2 gap-2 text-xs font-mono">
                <div className="bg-slate-900/90 p-2.5 rounded-2xl border border-slate-800">
                  <span className="text-[10px] text-slate-400 block uppercase">Active Homes</span>
                  <span className="text-sm font-black text-white">{s.activeListings} Units</span>
                </div>
                <div className="bg-slate-900/90 p-2.5 rounded-2xl border border-slate-800">
                  <span className="text-[10px] text-slate-400 block uppercase">Avg Rent</span>
                  <span className="text-sm font-black text-emerald-400">₹{s.avgRent.toLocaleString('en-IN')}</span>
                </div>
                <div className="bg-slate-900/90 p-2.5 rounded-2xl border border-slate-800">
                  <span className="text-[10px] text-slate-400 block uppercase">Turnaround DOM</span>
                  <span className="text-sm font-black text-amber-400">{s.avgDomDays} Days</span>
                </div>
                <div className="bg-slate-900/90 p-2.5 rounded-2xl border border-slate-800">
                  <span className="text-[10px] text-slate-400 block uppercase">Rental Yield</span>
                  <span className="text-sm font-black text-indigo-400">{s.yieldPct}% / yr</span>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* 4. ACTIVE SECTOR METADATA MODAL / TOOLTIP CARD */}
      <AnimatePresence>
        {activeSectorFocus && (
          <motion.div
            initial={{ opacity: 0, y: 10, scale: 0.98 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 8, scale: 0.98 }}
            className="bg-slate-900 text-white rounded-3xl p-5 sm:p-6 border border-slate-800 shadow-2xl space-y-4 relative"
          >
            <button
              onClick={() => { setHoveredSector(null); setTappedSector(null); }}
              className="absolute top-4 right-4 text-xs text-slate-400 hover:text-white bg-slate-800 px-2.5 py-1 rounded-xl border border-slate-700"
            >
              ✕ Close
            </button>

            <div className="flex items-center gap-3 border-b border-slate-800 pb-3">
              <div className="w-10 h-10 rounded-2xl bg-emerald-500/20 text-emerald-400 border border-emerald-500/30 flex items-center justify-center font-bold">
                <MapPin className="w-5 h-5" />
              </div>
              <div>
                <h4 className="font-black font-['Outfit'] text-base sm:text-lg text-white leading-none">
                  {activeSectorFocus.sector} Detailed Telemetry
                </h4>
                <span className="text-xs text-emerald-400 font-mono font-bold mt-1 block">
                  {activeSectorFocus.tag} • Demand Rating: {activeSectorFocus.demandScore}/100
                </span>
              </div>
            </div>

            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs font-mono">
              <div className="bg-slate-800/80 p-3 rounded-2xl border border-slate-700">
                <span className="text-[10px] text-slate-400 uppercase font-bold block">Active Listings</span>
                <span className="text-base font-black text-white mt-0.5 block">{activeSectorFocus.activeListings} Homes</span>
              </div>
              <div className="bg-slate-800/80 p-3 rounded-2xl border border-slate-700">
                <span className="text-[10px] text-slate-400 uppercase font-bold block">Average Rent</span>
                <span className="text-base font-black text-emerald-400 mt-0.5 block">₹{activeSectorFocus.avgRent.toLocaleString('en-IN')}/mo</span>
              </div>
              <div className="bg-slate-800/80 p-3 rounded-2xl border border-slate-700">
                <span className="text-[10px] text-slate-400 uppercase font-bold block">Turnaround Speed</span>
                <span className="text-base font-black text-amber-400 mt-0.5 block">{activeSectorFocus.avgDomDays} Days on Market</span>
              </div>
              <div className="bg-slate-800/80 p-3 rounded-2xl border border-slate-700">
                <span className="text-[10px] text-slate-400 uppercase font-bold block">Annual Rental Yield</span>
                <span className="text-base font-black text-indigo-400 mt-0.5 block">{activeSectorFocus.yieldPct}% p.a.</span>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* 5. AI REVENUE & YIELD PREDICTOR SIMULATOR */}
      <div className="bg-slate-900 text-white rounded-3xl p-6 border border-slate-800 space-y-6 shadow-xl relative overflow-hidden">
        
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4 border-b border-slate-800">
          <div>
            <div className="flex items-center gap-2">
              <span className="text-[10px] font-mono font-bold text-amber-300 bg-amber-500/10 px-2.5 py-1 rounded-full border border-amber-500/30 flex items-center gap-1.5">
                <Zap className="w-3.5 h-3.5 text-amber-400" />
                Executive Business Simulator
              </span>
            </div>
            <h4 className="text-lg font-black font-['Outfit'] mt-1 text-white flex items-center gap-2">
              🔮 AI Revenue & Escort Capacity Predictor
            </h4>
            <p className="text-xs text-slate-400">
              Simulate inventory growth and rent rate adjustments to project monthly platform earnings.
            </p>
          </div>

          <div className="bg-slate-950 px-4 py-2.5 rounded-2xl border border-slate-800 text-right font-mono shrink-0">
            <span className="text-[10px] text-slate-400 uppercase font-bold block">Projected Revenue Lift</span>
            <span className="text-lg font-black text-emerald-400">
              +₹{(revenueDelta / 100000).toFixed(2)} Lakhs/mo
            </span>
          </div>
        </div>

        {/* SIMULATOR SLIDERS */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          
          {/* SLIDER 1: LISTING INVENTORY GROWTH */}
          <div className="space-y-2">
            <div className="flex justify-between text-xs font-mono">
              <span className="text-slate-300 font-bold">1. Simulate Listing Inventory Expansion:</span>
              <span className="text-emerald-400 font-black">+{listingGrowthPct}% Inventory</span>
            </div>
            <input
              type="range"
              min="0"
              max="50"
              step="5"
              value={listingGrowthPct}
              onChange={(e) => setListingGrowthPct(Number(e.target.value))}
              className="w-full accent-emerald-500 cursor-pointer h-2 bg-slate-800 rounded-lg"
            />
            <div className="flex justify-between text-[10px] text-slate-500 font-mono">
              <span>0% (Current)</span>
              <span>+25%</span>
              <span>+50% (Surge)</span>
            </div>
          </div>

          {/* SLIDER 2: RENT RATE ADJUSTMENT */}
          <div className="space-y-2">
            <div className="flex justify-between text-xs font-mono">
              <span className="text-slate-300 font-bold">2. Simulate Average Rent Adjustment (₹):</span>
              <span className="text-amber-400 font-black">+₹{rentDeltaAmount.toLocaleString('en-IN')}/mo</span>
            </div>
            <input
              type="range"
              min="0"
              max="5000"
              step="500"
              value={rentDeltaAmount}
              onChange={(e) => setRentDeltaAmount(Number(e.target.value))}
              className="w-full accent-amber-500 cursor-pointer h-2 bg-slate-800 rounded-lg"
            />
            <div className="flex justify-between text-[10px] text-slate-500 font-mono">
              <span>₹0</span>
              <span>+₹2,500</span>
              <span>+₹5,000</span>
            </div>
          </div>

        </div>

        {/* SIMULATION PREDICTED SUMMARY CARDS */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 pt-2">
          <div className="bg-slate-950 p-3.5 rounded-2xl border border-slate-800">
            <span className="text-[10px] text-slate-400 uppercase font-bold block">Base Platform Revenue</span>
            <span className="text-base font-black text-slate-200 mt-0.5 block font-mono">
              ₹{(totalBaseMonthlyRevenue / 100000).toFixed(2)} Lakhs/mo
            </span>
          </div>
          <div className="bg-slate-950 p-3.5 rounded-2xl border border-slate-800">
            <span className="text-[10px] text-slate-400 uppercase font-bold block">Simulated Total Revenue</span>
            <span className="text-base font-black text-emerald-400 mt-0.5 block font-mono">
              ₹{(simulatedMonthlyRevenue / 100000).toFixed(2)} Lakhs/mo
            </span>
          </div>
          <div className="bg-slate-950 p-3.5 rounded-2xl border border-slate-800">
            <span className="text-[10px] text-slate-400 uppercase font-bold block">Field Escort Staff Needed</span>
            <span className="text-base font-black text-indigo-400 mt-0.5 block font-mono">
              {simulatedEscortsNeeded} Escorts Total
            </span>
          </div>
        </div>

      </div>

      {/* 6. AI BUSINESS STRATEGY & INSIGHTS DECK */}
      <div className="bg-slate-50 rounded-3xl p-6 border border-slate-200/90 space-y-4">
        
        <div className="flex items-center justify-between pb-3 border-b border-slate-200/80">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-xl bg-emerald-600 text-white flex items-center justify-center font-bold">
              <Award className="w-4 h-4" />
            </div>
            <div>
              <h4 className="font-black font-['Outfit'] text-base text-slate-900 leading-none">
                AI Automated Business Action Recommendations
              </h4>
              <span className="text-[11px] text-slate-500 font-medium mt-0.5 block">
                Algorithmic insights derived from real-time tenant tour velocity & inventory demand.
              </span>
            </div>
          </div>
          <span className="text-xs font-mono font-extrabold text-emerald-800 bg-emerald-50 px-3 py-1 rounded-full border border-emerald-200">
            3 High Impact Actions
          </span>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          
          <div className="bg-white p-4 rounded-2xl border border-slate-200/90 space-y-2 shadow-2xs hover:shadow-md transition-shadow">
            <span className="text-[10px] font-mono font-bold text-emerald-700 bg-emerald-50 px-2 py-0.5 rounded border border-emerald-200">
              💎 Vijay Nagar (#1 Surge)
            </span>
            <h5 className="font-extrabold text-xs text-slate-900 font-['Outfit']">
              Deploy +2 Dedicated Escorts
            </h5>
            <p className="text-[11px] text-slate-600 leading-relaxed">
              Vijay Nagar has a <strong>98/100 demand score</strong> with 4.2 days DOM. Adding 2 field escorts will prevent tour booking backlogs.
            </p>
          </div>

          <div className="bg-white p-4 rounded-2xl border border-slate-200/90 space-y-2 shadow-2xs hover:shadow-md transition-shadow">
            <span className="text-[10px] font-mono font-bold text-indigo-700 bg-indigo-50 px-2 py-0.5 rounded border border-indigo-200">
              🚀 Super Corridor (IT Belt)
            </span>
            <h5 className="font-extrabold text-xs text-slate-900 font-['Outfit']">
              Attach 3D MP4 Walkthroughs
            </h5>
            <p className="text-[11px] text-slate-600 leading-relaxed">
              Super Corridor IT tours have 28.4% conversion. Uploading 3D MP4 video walkthroughs is projected to increase conversions by <strong>+12.5%</strong>.
            </p>
          </div>

          <div className="bg-white p-4 rounded-2xl border border-slate-200/90 space-y-2 shadow-2xs hover:shadow-md transition-shadow">
            <span className="text-[10px] font-mono font-bold text-amber-700 bg-amber-50 px-2 py-0.5 rounded border border-amber-200">
              ⚡ Bhawarkua (Student Hub)
            </span>
            <h5 className="font-extrabold text-xs text-slate-900 font-['Outfit']">
              Enable Instant Cashback Passes
            </h5>
            <p className="text-[11px] text-slate-600 leading-relaxed">
              High volume of student tours (76 visits). Instant ₹1,000 lease cashbacks will boost 48-hour lease locking velocity.
            </p>
          </div>

        </div>

      </div>

    </div>
  );
};
