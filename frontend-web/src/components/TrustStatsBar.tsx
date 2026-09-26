import React from 'react';
import { motion } from 'framer-motion';
import { ShieldCheck, Sparkles, MapPin, Gift, TrendingUp, CheckCircle2 } from 'lucide-react';

const stats = [
  {
    id: 'passes',
    icon: Sparkles,
    value: '184+',
    label: 'Free Passes Claimed Today',
    subtext: 'Zero tenant booking fee or brokerage',
    accent: 'from-emerald-500/10 to-emerald-500/5',
    border: 'border-emerald-200/80',
    iconBg: 'bg-emerald-50 text-emerald-600 border-emerald-200',
    badge: 'Live Activity'
  },
  {
    id: 'escort',
    icon: ShieldCheck,
    value: '100%',
    label: 'On-Site Ground Escort',
    subtext: 'Physical verification by local on-site team',
    accent: 'from-blue-500/10 to-blue-500/5',
    border: 'border-blue-200/80',
    iconBg: 'bg-blue-50 text-blue-600 border-blue-200',
    badge: 'Double-OTP'
  },
  {
    id: 'gis',
    icon: MapPin,
    value: '150m',
    label: 'PostGIS Geofence Precision',
    subtext: 'GPS radius validated on site',
    accent: 'from-purple-500/10 to-purple-500/5',
    border: 'border-purple-200/80',
    iconBg: 'bg-purple-50 text-purple-600 border-purple-200',
    badge: 'Spatial Engine'
  },
  {
    id: 'cashback',
    icon: Gift,
    value: '₹1,000',
    label: 'Lease Closing Cashback',
    subtext: 'Direct UPI transfer on rent agreement',
    accent: 'from-amber-500/10 to-amber-500/5',
    border: 'border-amber-200/80',
    iconBg: 'bg-amber-50 text-amber-600 border-amber-200',
    badge: 'Instant Transfer'
  }
];

export const TrustStatsBar: React.FC = () => {
  return (
    <section id="trust-stats" className="py-8 bg-slate-900 text-white relative overflow-hidden border-y border-slate-800">
      {/* Background glow effects */}
      <div className="absolute -top-24 left-1/4 w-72 h-72 bg-emerald-500/10 blur-3xl rounded-full pointer-events-none" />
      <div className="absolute -bottom-24 right-1/4 w-72 h-72 bg-amber-500/10 blur-3xl rounded-full pointer-events-none" />

      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 relative z-10">
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 sm:gap-6">
          {stats.map((stat, index) => {
            const Icon = stat.icon;
            return (
              <motion.div
                key={stat.id}
                initial={{ opacity: 0, y: 16 }}
                whileInView={{ opacity: 1, y: 0 }}
                viewport={{ once: true }}
                transition={{ duration: 0.4, delay: index * 0.1, ease: [0.16, 1, 0.3, 1] }}
                whileHover={{ y: -3, scale: 1.01 }}
                className="bg-slate-800/80 backdrop-blur-md p-5 rounded-2xl border border-slate-700/70 hover:border-slate-600 transition-all duration-300 shadow-md relative group flex flex-col justify-between"
              >
                <div>
                  <div className="flex items-center justify-between mb-3">
                    <div className={`w-10 h-10 rounded-xl flex items-center justify-center border ${stat.iconBg} shadow-xs`}>
                      <Icon className="w-5 h-5" />
                    </div>
                    <span className="text-[10px] font-bold tracking-wider uppercase px-2.5 py-0.5 rounded-full bg-slate-700/80 text-slate-300 border border-slate-600/60 font-mono">
                      {stat.badge}
                    </span>
                  </div>

                  <div className="flex items-baseline gap-2">
                    <h3 className="text-2xl sm:text-3xl font-black font-['Outfit',sans-serif] text-white tracking-tight">
                      {stat.value}
                    </h3>
                    <TrendingUp className="w-4 h-4 text-emerald-400 opacity-80" />
                  </div>

                  <p className="text-xs font-bold text-slate-200 mt-1 font-['Outfit',sans-serif]">
                    {stat.label}
                  </p>
                </div>

                <div className="mt-3 pt-3 border-t border-slate-700/50 flex items-center gap-1.5 text-[11px] text-slate-400">
                  <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400 shrink-0" />
                  <span>{stat.subtext}</span>
                </div>
              </motion.div>
            );
          })}
        </div>
      </div>
    </section>
  );
};
