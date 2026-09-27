import React from 'react';
import { motion } from 'framer-motion';
import { ShieldCheck, Gift, Sparkles } from 'lucide-react';

const containerVariants = {
  hidden: { opacity: 0 },
  visible: {
    opacity: 1,
    transition: { staggerChildren: 0.12 }
  }
};

const cardVariants = {
  hidden: { opacity: 0, y: 24, scale: 0.98 },
  visible: { 
    opacity: 1, 
    y: 0, 
    scale: 1,
    transition: { duration: 0.5, ease: [0.16, 1, 0.3, 1] as const } 
  }
};

export const ValueBanner: React.FC = () => {
  return (
    <section className="py-10 bg-slate-50 border-b border-slate-200/80">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        
        <motion.div
          initial={{ opacity: 0, y: 16 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true }}
          transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
          className="text-center max-w-xl mx-auto mb-8"
        >
          <span className="text-xs font-bold text-emerald-700 uppercase tracking-wider bg-emerald-50 px-3.5 py-1 rounded-full border border-emerald-200">
            Why Pathome?
          </span>
          <h2 className="pathome-tagline text-2xl sm:text-3xl font-extrabold text-slate-900 font-['Outfit',sans-serif] mt-2.5">
            Your Dreams, Our Efforts.
          </h2>
          <p className="text-slate-500 text-xs sm:text-sm mt-1.5 leading-relaxed">
            Built for transparent rental discovery without broker markups or ghost listings.
          </p>
        </motion.div>

        {/* 3-COLUMN TRUST ARCHITECTURE */}
        <motion.div 
          variants={containerVariants}
          initial="hidden"
          whileInView="visible"
          viewport={{ once: true, margin: "-50px" }}
          className="grid grid-cols-1 md:grid-cols-3 gap-6"
        >
          
          <motion.div
            variants={cardVariants}
            whileHover={{ y: -4 }}
            className="bg-white p-7 rounded-3xl border border-slate-200 shadow-sm hover:shadow-lg hover:shadow-slate-200/60 transition-all duration-300 flex flex-col justify-between"
          >
            <div>
              <div className="w-11 h-11 rounded-2xl bg-emerald-50 text-emerald-600 flex items-center justify-center mb-5 font-bold border border-emerald-200/60 shadow-xs">
                <Sparkles className="w-5 h-5 text-emerald-600" />
              </div>
              <h3 className="text-xl font-bold text-slate-900 font-['Outfit',sans-serif] mb-2">
                Zero Brokerage for Tenants
              </h3>
              <p className="text-xs text-slate-600 leading-relaxed mb-6">
                Explore rental listings and submit property visit requests without middleman commissions or hidden registration markups.
              </p>
            </div>
            <div>
              <div className="text-[11px] font-bold text-emerald-700 bg-emerald-50 px-3 py-1 rounded-lg inline-block border border-emerald-200">
                Direct Owner Listings
              </div>
            </div>
          </motion.div>

          <motion.div
            variants={cardVariants}
            whileHover={{ y: -4 }}
            className="bg-white p-7 rounded-3xl border border-slate-200 shadow-sm hover:shadow-lg hover:shadow-slate-200/60 transition-all duration-300 flex flex-col justify-between"
          >
            <div>
              <div className="w-11 h-11 rounded-2xl bg-emerald-50 text-emerald-600 flex items-center justify-center mb-5 font-bold border border-emerald-200/60 shadow-xs">
                <ShieldCheck className="w-5 h-5 text-emerald-600" />
              </div>
              <h3 className="text-xl font-bold text-slate-900 font-['Outfit',sans-serif] mb-2">
                Grounded Property Details
              </h3>
              <p className="text-xs text-slate-600 leading-relaxed mb-6">
                Every listing displays key rental information, photos, amenities, and accurate locality mapping so you can browse with confidence.
              </p>
            </div>
            <div>
              <div className="text-[11px] font-bold text-emerald-700 bg-emerald-50 px-3 py-1 rounded-lg inline-block border border-emerald-200">
                Clear Information
              </div>
            </div>
          </motion.div>

          <motion.div
            variants={cardVariants}
            whileHover={{ y: -4 }}
            className="bg-white p-7 rounded-3xl border border-slate-200 shadow-sm hover:shadow-lg hover:shadow-slate-200/60 transition-all duration-300 flex flex-col justify-between"
          >
            <div>
              <div className="w-11 h-11 rounded-2xl bg-emerald-50 text-emerald-600 flex items-center justify-center mb-5 font-bold border border-emerald-200/60 shadow-xs">
                <Gift className="w-5 h-5 text-emerald-600" />
              </div>
              <h3 className="text-xl font-bold text-slate-900 font-['Outfit',sans-serif] mb-2">
                Transparent Pricing
              </h3>
              <p className="text-xs text-slate-600 leading-relaxed mb-6">
                View monthly rent, security deposit, and maintenance upfront. No surprise fees, arbitrary markups, or hidden broker charges.
              </p>
            </div>
            <div>
              <div className="text-[11px] font-bold text-amber-800 bg-amber-50 px-3 py-1 rounded-lg inline-block border border-amber-200 font-mono">
                Upfront Pricing
              </div>
            </div>
          </motion.div>

        </motion.div>

      </div>
    </section>
  );
};
