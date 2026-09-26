import React from 'react';
import { motion } from 'framer-motion';
import { Sparkles } from 'lucide-react';

export const FutureExpansion: React.FC = () => {
  return (
    <section className="py-10 bg-slate-950 text-white relative overflow-hidden border-t border-slate-800">
      
      {/* BACKGROUND IMAGE OVERLAY */}
      <div className="absolute inset-0 z-0 opacity-20">
        <img 
          src="/assets/spatial_gis.jpg" 
          alt="Indore Plot Spatial Map" 
          className="w-full h-full object-cover scale-105"
        />
        <div className="absolute inset-0 bg-gradient-to-r from-slate-950 via-slate-950/90 to-slate-950/70"></div>
      </div>

      {/* Ambient Glow Pulse */}
      <motion.div 
        animate={{ scale: [1, 1.25, 1], opacity: [0.2, 0.35, 0.2] }}
        transition={{ duration: 6, repeat: Infinity, ease: "easeInOut" }}
        className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-96 h-96 bg-emerald-500/20 blur-3xl rounded-full pointer-events-none"
      />

      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 relative z-10">
        <motion.div 
          initial={{ opacity: 0, y: 20, scale: 0.98 }}
          whileInView={{ opacity: 1, y: 0, scale: 1 }}
          viewport={{ once: true }}
          transition={{ duration: 0.55, ease: [0.16, 1, 0.3, 1] }}
          className="bg-slate-900/90 backdrop-blur-xl p-8 sm:p-12 rounded-3xl border border-slate-800 flex flex-col lg:flex-row items-center justify-between gap-8 shadow-2xl relative overflow-hidden"
        >
          
          <div className="max-w-2xl space-y-3 relative z-10">
            <div className="inline-flex items-center gap-2 bg-amber-500/10 text-amber-400 text-xs font-bold px-3.5 py-1 rounded-full border border-amber-500/30 shadow-sm font-mono">
              <Sparkles className="w-3.5 h-3.5 text-amber-400" /> Phase 2 Land & Plot Registry
            </div>
            
            <h2 className="text-3xl sm:text-4xl font-black font-['Outfit',sans-serif] text-white tracking-tight leading-tight">
              Planning to build your dream home from the ground up?
            </h2>
            
            <p className="text-slate-300 text-xs sm:text-sm leading-relaxed">
              Prime Indore commercial plots & residential land in Super Corridor, AB Road & Nipania coming soon. Verified title deeds backed by PostGIS spatial geofencing.
            </p>
          </div>

          <div className="flex flex-col sm:flex-row gap-3 w-full lg:w-auto shrink-0 relative z-10">
            <div className="inline-flex items-center justify-center gap-2.5 rounded-2xl border border-emerald-500/30 bg-emerald-950/60 px-6 py-4 text-xs font-bold text-emerald-300 backdrop-blur-md">
              <span className="h-2 w-2 rounded-full bg-emerald-400 animate-pulse motion-reduce:animate-none" />
              <span className="uppercase tracking-wider">Phase 2 Plot Sales Launching Soon</span>
            </div>
          </div>

        </motion.div>
      </div>
    </section>
  );
};
