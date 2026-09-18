import React from 'react';
import { motion } from 'framer-motion';
import {
  Users,
  PhoneCall,
  Key,
  CheckCircle2,
  TrendingDown
} from 'lucide-react';

interface FunnelStage {
  id: string;
  stageName: string;
  count: number;
  percent: number;
  dropCount: number;
  icon: React.ElementType;
  color: string;
  bgColor: string;
  borderColor: string;
}

const FUNNEL_STAGES: FunnelStage[] = [
  {
    id: 'lead',
    stageName: '1. Meta Ad Leads',
    count: 482,
    percent: 100,
    dropCount: 92,
    icon: Users,
    color: 'text-slate-900',
    bgColor: 'bg-slate-100',
    borderColor: 'border-slate-300'
  },
  {
    id: 'screening',
    stageName: '2. WFH Screening & Routing',
    count: 390,
    percent: 80.9,
    dropCount: 206,
    icon: PhoneCall,
    color: 'text-indigo-700',
    bgColor: 'bg-indigo-50',
    borderColor: 'border-indigo-200'
  },
  {
    id: 'tour',
    stageName: '3. Ground Escort Visit',
    count: 184,
    percent: 38.1,
    dropCount: 122,
    icon: Key,
    color: 'text-emerald-700',
    bgColor: 'bg-emerald-50',
    borderColor: 'border-emerald-200'
  },
  {
    id: 'lease',
    stageName: '4. Lease Signed & Cashback',
    count: 62,
    percent: 12.8,
    dropCount: 0,
    icon: CheckCircle2,
    color: 'text-amber-700',
    bgColor: 'bg-amber-50',
    borderColor: 'border-amber-200'
  }
];

export const FunnelStepGraph: React.FC = () => {
  return (
    <div className="bg-white rounded-3xl p-6 sm:p-7 border border-slate-200/90 shadow-sm space-y-6">
      
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4 border-b border-slate-100">
        <div>
          <h3 className="text-xl font-black text-slate-900 font-['Outfit']">
            Visual Lead Conversion Flow Graph
          </h3>
          <p className="text-xs text-slate-500 mt-0.5">
            Step-down funnel tracking conversion efficiency and drop-off points across Indore sectors.
          </p>
        </div>
        <div className="bg-emerald-50 text-emerald-800 border border-emerald-200 px-3.5 py-1.5 rounded-full font-mono text-xs font-bold">
          Average Turnaround: 4.2 Minutes
        </div>
      </div>

      {/* VISUAL STEPPED FUNNEL DIAGRAM */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        {FUNNEL_STAGES.map((stage, idx) => {
          const Icon = stage.icon;
          return (
            <motion.div
              key={stage.id}
              initial={{ opacity: 0, y: 16 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.4, delay: idx * 0.1 }}
              className={`p-5 rounded-2xl border ${stage.bgColor} ${stage.borderColor} flex flex-col justify-between space-y-4 relative group hover:shadow-md transition-all`}
            >
              <div>
                <div className="flex items-center justify-between mb-3">
                  <div className={`w-9 h-9 rounded-xl ${stage.bgColor} ${stage.color} flex items-center justify-center font-bold border ${stage.borderColor}`}>
                    <Icon className="w-4.5 h-4.5" />
                  </div>
                  <span className={`font-mono text-xs font-black ${stage.color}`}>
                    {stage.percent}%
                  </span>
                </div>

                <h4 className="text-xs font-bold text-slate-900 uppercase font-mono tracking-tight">
                  {stage.stageName}
                </h4>
                <div className="text-2xl font-black text-slate-900 font-mono mt-1">
                  {stage.count} <span className="text-xs font-semibold text-slate-500">Leads</span>
                </div>
              </div>

              {/* Dropoff Indicator */}
              {stage.dropCount > 0 && (
                <div className="pt-3 border-t border-slate-200/80 flex items-center justify-between text-[10px] font-mono text-rose-600 font-bold">
                  <span className="flex items-center gap-1"><TrendingDown className="w-3 h-3" /> Drop-off:</span>
                  <span>-{stage.dropCount} leads</span>
                </div>
              )}
            </motion.div>
          );
        })}
      </div>

    </div>
  );
};
