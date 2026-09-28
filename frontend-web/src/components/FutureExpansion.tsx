import React from 'react';
import { motion, useReducedMotion } from 'framer-motion';
import { Sparkles } from 'lucide-react';
import { LANDING_EASE, landingEntrance } from '../utils/landingMotion';

export const FutureExpansion: React.FC = () => {
  const reduceMotion = useReducedMotion();
  return (
    <section className="relative overflow-hidden border-t border-slate-800 bg-slate-950 py-16 text-white sm:py-20">
      
      {/* BACKGROUND IMAGE OVERLAY */}
      <div className="absolute inset-0 z-0 opacity-30">
        <motion.img
          src="/assets/spatial_gis.jpg" 
          alt="Indore Plot Spatial Map" 
          initial={reduceMotion ? false : { scale: 1.035, clipPath: 'inset(0 0 24% 0)' }}
          whileInView={{ scale: 1, clipPath: 'inset(0 0 0% 0)' }}
          viewport={{ once: true }}
          transition={{ duration: reduceMotion ? 0 : 0.8, ease: LANDING_EASE }}
          className="w-full h-full object-cover"
        />
        <div className="absolute inset-0 bg-gradient-to-r from-slate-950 via-slate-950/85 to-slate-950/55"></div>
      </div>

      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 relative z-10">
        <div
          className="flex min-h-[210px] flex-col justify-between gap-10 lg:min-h-[250px] lg:flex-row lg:items-end"
        >
          
          <motion.div {...landingEntrance(reduceMotion, 'section', 0, 'left')} className="max-w-2xl space-y-3 relative z-10">
            <div className="inline-flex items-center gap-2 text-xs font-bold text-amber-300">
              <Sparkles className="w-3.5 h-3.5 text-amber-400" /> Phase 2 — Land & Plots
            </div>
            
            <h2 className="text-3xl sm:text-4xl font-black font-['Outfit',sans-serif] text-white tracking-tight leading-tight">
              Planning to build your dream home from the ground up?
            </h2>
            
            <p className="text-slate-300 text-xs sm:text-sm leading-relaxed">
              Planned support for land and plot listings.
            </p>
          </motion.div>

          <motion.div {...landingEntrance(reduceMotion, 'section', 0.08, 'right')} className="relative z-10 shrink-0 border-t border-emerald-400/50 pt-4 lg:max-w-52">
            <div className="inline-flex items-center gap-2.5 text-xs font-bold text-emerald-300">
              <span className="h-2 w-2 shrink-0 rounded-full bg-emerald-400" />
              <span className="uppercase tracking-wider">Phase 2 Plot Sales Launching Soon</span>
            </div>
          </motion.div>

        </div>
      </div>
    </section>
  );
};
