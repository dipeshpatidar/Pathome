import React, { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import {Sparkles} from 'lucide-react';

export type TimeGranularity = 'DAY' | 'WEEK' | 'MONTH' | 'YEAR';

interface DataPoint {
  label: string;
  revenueLakhs: number;
  tours: number;
  leads: number;
  conversion: number;
}

const DAY_WISE_DATA: DataPoint[] = [
  { label: 'Mon 09:00', revenueLakhs: 0.4, tours: 8, leads: 22, conversion: 36.4 },
  { label: 'Tue 12:00', revenueLakhs: 0.7, tours: 14, leads: 38, conversion: 36.8 },
  { label: 'Wed 15:00', revenueLakhs: 1.1, tours: 21, leads: 54, conversion: 38.8 },
  { label: 'Thu 18:00', revenueLakhs: 1.6, tours: 29, leads: 76, conversion: 38.1 },
  { label: 'Fri 21:00', revenueLakhs: 2.2, tours: 38, leads: 98, conversion: 38.7 },
  { label: 'Sat 23:59', revenueLakhs: 2.8, tours: 46, leads: 118, conversion: 39.0 },
];

const WEEK_WISE_DATA: DataPoint[] = [
  { label: 'Week 1 (Aug 1-7)', revenueLakhs: 3.1, tours: 42, leads: 110, conversion: 38.1 },
  { label: 'Week 2 (Aug 8-14)', revenueLakhs: 3.6, tours: 48, leads: 128, conversion: 37.5 },
  { label: 'Week 3 (Aug 15-21)', revenueLakhs: 4.1, tours: 54, leads: 142, conversion: 38.0 },
  { label: 'Week 4 (Aug 22-31)', revenueLakhs: 4.8, tours: 62, leads: 165, conversion: 37.6 },
];

const MONTH_WISE_DATA: DataPoint[] = [
  { label: 'Apr 2026', revenueLakhs: 6.2, tours: 74, leads: 210, conversion: 28.5 },
  { label: 'May 2026', revenueLakhs: 8.4, tours: 98, leads: 280, conversion: 31.2 },
  { label: 'Jun 2026', revenueLakhs: 10.1, tours: 124, leads: 340, conversion: 34.0 },
  { label: 'Jul 2026', revenueLakhs: 11.8, tours: 146, leads: 395, conversion: 35.8 },
  { label: 'Aug 2026', revenueLakhs: 13.0, tours: 168, leads: 430, conversion: 37.1 },
  { label: 'Sep 2026', revenueLakhs: 14.2, tours: 184, leads: 482, conversion: 38.1 },
];

const YEAR_WISE_DATA: DataPoint[] = [
  { label: 'FY 2023-24', revenueLakhs: 45.0, tours: 620, leads: 1800, conversion: 24.5 },
  { label: 'FY 2024-25', revenueLakhs: 88.5, tours: 1150, leads: 3200, conversion: 31.8 },
  { label: 'FY 2025-26', revenueLakhs: 142.0, tours: 1840, leads: 4900, conversion: 37.5 },
  { label: 'FY 2026-27 (Proj)', revenueLakhs: 210.0, tours: 2600, leads: 6800, conversion: 42.0 },
];

export const RevenueAreaChart: React.FC = () => {
  const [timeGranularity, setTimeGranularity] = useState<TimeGranularity>('MONTH');
  const [activeMetric, setActiveMetric] = useState<'revenue' | 'tours' | 'leads'>('revenue');
  const [hoveredPoint, setHoveredPoint] = useState<DataPoint | null>(null);

  const getDataset = () => {
    switch (timeGranularity) {
      case 'DAY': return DAY_WISE_DATA;
      case 'WEEK': return WEEK_WISE_DATA;
      case 'MONTH': return MONTH_WISE_DATA;
      case 'YEAR': return YEAR_WISE_DATA;
    }
  };

  const dataset = getDataset();
  const maxVal = Math.max(...dataset.map(d => activeMetric === 'revenue' ? d.revenueLakhs : activeMetric === 'tours' ? d.tours : d.leads)) * 1.25;

  // Generate SVG Path for smooth curved area graph
  const getCoordinates = () => {
    const width = 650;
    const height = 200;
    const padding = 30;

    return dataset.map((d, idx) => {
      const x = padding + (idx / (dataset.length - 1)) * (width - 2 * padding);
      const val = activeMetric === 'revenue' ? d.revenueLakhs : activeMetric === 'tours' ? d.tours : d.leads;
      const y = height - padding - (val / maxVal) * (height - 2 * padding);
      return { x, y, data: d };
    });
  };

  const points = getCoordinates();

  // Construct SVG Bezier Smooth Curve Path
  const makePath = () => {
    if (points.length === 0) return '';
    let path = `M ${points[0].x} ${points[0].y}`;
    for (let i = 0; i < points.length - 1; i++) {
      const curr = points[i];
      const next = points[i + 1];
      const cpX = (curr.x + next.x) / 2;
      path += ` C ${cpX} ${curr.y}, ${cpX} ${next.y}, ${next.x} ${next.y}`;
    }
    return path;
  };

  const linePath = makePath();
  const areaPath = `${linePath} L ${points[points.length - 1].x} 170 L ${points[0].x} 170 Z`;

  return (
    <div className="bg-white rounded-3xl p-6 sm:p-7 border border-slate-200/90 shadow-sm space-y-6 select-none">
      
      {/* 1. HEADER & GRANULARITY SELECTOR */}
      <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4 pb-4 border-b border-slate-100">
        <div>
          <div className="flex items-center gap-2">
            <span className="text-[10px] font-black text-emerald-800 bg-emerald-50 px-2.5 py-1 rounded-full border border-emerald-200 uppercase font-mono flex items-center gap-1">
              <Sparkles className="w-3.5 h-3.5 text-emerald-600 animate-pulse" /> Google Analytics Visual Engine
            </span>
            <span className="text-xs text-slate-400 font-mono">
              {timeGranularity === 'DAY' ? '24-Hour Day Analysis' : timeGranularity === 'WEEK' ? 'Weekly Granularity' : timeGranularity === 'MONTH' ? 'Monthly Trajectory' : 'Multi-Year Annual Projection'}
            </span>
          </div>
          <h3 className="text-xl sm:text-2xl font-black text-slate-900 font-['Outfit'] mt-1">
            Revenue & Conversion Trajectory Analysis
          </h3>
        </div>

        {/* TIME GRANULARITY SWITCHER */}
        <div className="flex items-center gap-1 bg-slate-900 p-1.5 rounded-2xl border border-slate-800 shadow-inner">
          {[
            { id: 'DAY', label: '📅 Day-wise' },
            { id: 'WEEK', label: '📆 Week-wise' },
            { id: 'MONTH', label: '🗓️ Month-wise' },
            { id: 'YEAR', label: '📊 Year-wise' }
          ].map(g => (
            <button
              key={g.id}
              onClick={() => setTimeGranularity(g.id as TimeGranularity)}
              className={`px-3 py-1.5 rounded-xl text-xs font-extrabold transition-all cursor-pointer ${
                timeGranularity === g.id
                  ? 'bg-emerald-500 text-slate-950 shadow-md shadow-emerald-500/20 font-black scale-105'
                  : 'text-slate-400 hover:text-white'
              }`}
            >
              {g.label}
            </button>
          ))}
        </div>
      </div>

      {/* METRIC TOGGLE BUTTONS */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          {[
            { id: 'revenue', label: '💰 Revenue (₹)', color: 'emerald' },
            { id: 'tours', label: '🚶 Escorted Tours', color: 'indigo' },
            { id: 'leads', label: '🎯 Meta Leads', color: 'amber' }
          ].map(m => (
            <button
              key={m.id}
              onClick={() => setActiveMetric(m.id as any)}
              className={`px-3.5 py-1.5 rounded-xl text-xs font-bold transition-all cursor-pointer border ${
                activeMetric === m.id
                  ? 'bg-slate-900 text-white border-slate-800 shadow-sm font-black'
                  : 'bg-slate-50 text-slate-600 border-slate-200 hover:bg-slate-100'
              }`}
            >
              {m.label}
            </button>
          ))}
        </div>

        <div className="text-xs font-mono font-bold text-slate-600">
          Peak Level: <strong className="text-emerald-700">
            {activeMetric === 'revenue' ? `₹${Math.max(...dataset.map(d => d.revenueLakhs))} Lakhs` : Math.max(...dataset.map(d => d.tours))}
          </strong>
        </div>
      </div>

      {/* SVG GRAPH AREA */}
      <div className="relative overflow-x-auto no-scrollbar">
        <div className="min-w-[650px] relative">
          <svg viewBox="0 0 650 200" className="w-full h-56 overflow-visible">
            <defs>
              <linearGradient id="emeraldAreaGradient" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="#10b981" stopOpacity="0.35" />
                <stop offset="100%" stopColor="#10b981" stopOpacity="0.0" />
              </linearGradient>
              <linearGradient id="indigoAreaGradient" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="#6366f1" stopOpacity="0.35" />
                <stop offset="100%" stopColor="#6366f1" stopOpacity="0.0" />
              </linearGradient>
            </defs>

            {/* Horizontal Grid Lines */}
            {[40, 80, 120, 160].map((y, idx) => (
              <line
                key={idx}
                x1="30"
                y1={y}
                x2="620"
                y2={y}
                stroke="#e2e8f0"
                strokeDasharray="4 4"
                strokeWidth="1"
              />
            ))}

            {/* Area Fill */}
            <motion.path
              key={timeGranularity + activeMetric + 'area'}
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              transition={{ duration: 0.5 }}
              d={areaPath}
              fill={activeMetric === 'revenue' ? "url(#emeraldAreaGradient)" : "url(#indigoAreaGradient)"}
            />

            {/* Smooth Bezier Curve Line */}
            <motion.path
              key={timeGranularity + activeMetric + 'line'}
              initial={{ pathLength: 0 }}
              animate={{ pathLength: 1 }}
              transition={{ duration: 0.8, ease: "easeInOut" }}
              d={linePath}
              fill="none"
              stroke={activeMetric === 'revenue' ? "#059669" : activeMetric === 'tours' ? "#4f46e5" : "#d97706"}
              strokeWidth="3.5"
              strokeLinecap="round"
            />

            {/* Data Points */}
            {points.map((pt, idx) => (
              <g key={idx} className="cursor-pointer" onMouseEnter={() => setHoveredPoint(pt.data)} onMouseLeave={() => setHoveredPoint(null)}>
                <circle
                  cx={pt.x}
                  cy={pt.y}
                  r="6"
                  className={activeMetric === 'revenue' ? 'fill-emerald-600 stroke-white' : 'fill-indigo-600 stroke-white'}
                  strokeWidth="2.5"
                />
                <text
                  x={pt.x}
                  y="192"
                  textAnchor="middle"
                  className="text-[10px] font-bold fill-slate-500 font-mono"
                >
                  {pt.data.label.split(' ')[0]}
                </text>
              </g>
            ))}
          </svg>

          {/* HOVER TOOLTIP FLOATING CARD */}
          <AnimatePresence>
            {hoveredPoint && (
              <motion.div
                initial={{ opacity: 0, y: 6, scale: 0.95 }}
                animate={{ opacity: 1, y: 0, scale: 1 }}
                exit={{ opacity: 0, y: 4, scale: 0.95 }}
                className="absolute top-2 right-4 bg-slate-900 text-white px-4 py-3 rounded-2xl shadow-xl border border-slate-800 space-y-1 font-mono text-xs z-30"
              >
                <div className="flex items-center justify-between gap-4 border-b border-slate-800 pb-1 font-['Outfit'] font-bold text-emerald-400">
                  <span>{hoveredPoint.label}</span>
                  <span className="text-[10px] bg-emerald-500/20 px-2 py-0.5 rounded text-emerald-300">
                    +{hoveredPoint.conversion}% Conv
                  </span>
                </div>
                <div className="flex items-center justify-between gap-4 text-[11px] pt-1">
                  <span className="text-slate-400">Revenue:</span>
                  <strong className="text-emerald-400">₹{hoveredPoint.revenueLakhs} Lakhs</strong>
                </div>
                <div className="flex items-center justify-between gap-4 text-[11px]">
                  <span className="text-slate-400">Tours Completed:</span>
                  <strong className="text-indigo-400">{hoveredPoint.tours} Visits</strong>
                </div>
                <div className="flex items-center justify-between gap-4 text-[11px]">
                  <span className="text-slate-400">Meta Leads:</span>
                  <strong className="text-amber-400">{hoveredPoint.leads} Leads</strong>
                </div>
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      </div>

    </div>
  );
};
