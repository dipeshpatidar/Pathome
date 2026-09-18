import React from 'react';
import { motion } from 'framer-motion';
import {SlidersHorizontal} from 'lucide-react';

interface BhkGauge {
  bhk: string;
  label: string;
  score: number; // 0 to 100
  avgRent: string;
  demandTag: string;
}

const BHK_GAUGES: BhkGauge[] = [
  { bhk: '2BHK', label: '2 BHK Family Flat', score: 98, avgRent: '₹17,500', demandTag: '🔥 Peak Demand' },
  { bhk: '3BHK', label: '3 BHK Gated Flat', score: 95, avgRent: '₹24,000', demandTag: '⚡ High Volume' },
  { bhk: '1BHK', label: '1 BHK Apartment', score: 92, avgRent: '₹11,000', demandTag: '📈 Steady' },
  { bhk: '1RK', label: '1 RK Studio', score: 88, avgRent: '₹8,500', demandTag: '🎓 Student Choice' }
];

export const BhkDemandGaugeGrid: React.FC = () => {
  return (
    <div className="bg-white rounded-3xl p-6 sm:p-7 border border-slate-200/90 shadow-sm space-y-6">
      
      <div className="flex items-center justify-between pb-4 border-b border-slate-100">
        <div>
          <h3 className="text-xl font-black text-slate-900 font-['Outfit'] flex items-center gap-2">
            <SlidersHorizontal className="w-5 h-5 text-purple-600" />
            BHK Category Demand Score Gauges
          </h3>
          <p className="text-xs text-slate-500 mt-0.5">
            Visual circular score gauges tracking tenant search demand metrics across Indore.
          </p>
        </div>
      </div>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {BHK_GAUGES.map((item, idx) => {
          const radius = 32;
          const circumference = 2 * Math.PI * radius;
          const strokeDashoffset = circumference - (item.score / 100) * circumference;

          return (
            <motion.div
              key={idx}
              initial={{ opacity: 0, scale: 0.94 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={{ duration: 0.35, delay: idx * 0.08 }}
              className="bg-slate-50 p-4 rounded-2xl border border-slate-200 text-center space-y-3 flex flex-col items-center justify-between"
            >
              <div className="relative w-20 h-20 flex items-center justify-center">
                <svg className="w-20 h-20 -rotate-90 transform">
                  <circle
                    cx="40"
                    cy="40"
                    r={radius}
                    stroke="#e2e8f0"
                    strokeWidth="6"
                    fill="transparent"
                  />
                  <motion.circle
                    cx="40"
                    cy="40"
                    r={radius}
                    stroke="#059669"
                    strokeWidth="6"
                    strokeDasharray={circumference}
                    initial={{ strokeDashoffset: circumference }}
                    animate={{ strokeDashoffset }}
                    transition={{ duration: 1, ease: 'easeOut', delay: idx * 0.1 }}
                    strokeLinecap="round"
                    fill="transparent"
                  />
                </svg>
                <div className="absolute inset-0 flex flex-col items-center justify-center">
                  <span className="font-mono font-black text-sm text-slate-900">{item.score}%</span>
                </div>
              </div>

              <div>
                <h4 className="font-extrabold text-xs text-slate-900 font-['Outfit']">{item.label}</h4>
                <div className="text-[11px] font-mono font-bold text-emerald-700 mt-0.5">{item.avgRent}/mo</div>
                <span className="inline-block text-[9px] font-bold text-amber-800 bg-amber-50 px-2 py-0.5 rounded-full border border-amber-200 mt-1">
                  {item.demandTag}
                </span>
              </div>
            </motion.div>
          );
        })}
      </div>

    </div>
  );
};
