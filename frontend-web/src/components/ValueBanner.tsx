import React from 'react';
import { motion, useReducedMotion } from 'framer-motion';
import { ShieldCheck, Gift, Sparkles } from 'lucide-react';
import { landingEntrance } from '../utils/landingMotion';

export const ValueBanner: React.FC = () => {
  const reduceMotion = useReducedMotion();
  return (
    <section className="border-b border-emerald-900/10 bg-emerald-50/50 py-16 sm:py-20">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        
        <motion.div
          {...landingEntrance(reduceMotion, 'heading', 0, 'right')}
          className="mb-10 max-w-2xl"
        >
          <span className="text-xs font-bold uppercase tracking-wider text-emerald-700">
            Why Pathome?
          </span>
          <h2 className="pathome-tagline text-2xl sm:text-3xl font-extrabold text-slate-900 font-['Outfit',sans-serif] mt-2.5">
            Your Dreams, Our Efforts.
          </h2>
          <p className="text-slate-500 text-xs sm:text-sm mt-1.5 leading-relaxed">
            Built for transparent rental discovery without broker markups or ghost listings.
          </p>
        </motion.div>

        {/* An editorial value row gives the page a quiet interval after the step story. */}
        <div className="grid border-y border-emerald-900/10 md:grid-cols-3 md:divide-x md:divide-emerald-900/10">
          
          <motion.div
            {...landingEntrance(reduceMotion, 'card')}
            whileHover={reduceMotion ? undefined : { y: -3 }}
            className="flex flex-col justify-between border-b border-emerald-900/10 px-1 py-7 last:border-b-0 md:border-b-0 md:px-7 md:first:pl-0 md:last:pr-0"
          >
            <div>
              <div className="mb-5 flex h-9 w-9 items-center justify-center text-emerald-700">
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
              <div className="inline-block text-[11px] font-bold uppercase tracking-wide text-emerald-700">
                Direct Owner Listings
              </div>
            </div>
          </motion.div>

          <motion.div
            {...landingEntrance(reduceMotion, 'card', 0.06)}
            whileHover={reduceMotion ? undefined : { y: -3 }}
            className="flex flex-col justify-between border-b border-emerald-900/10 px-1 py-7 last:border-b-0 md:border-b-0 md:px-7 md:first:pl-0 md:last:pr-0"
          >
            <div>
              <div className="mb-5 flex h-9 w-9 items-center justify-center text-emerald-700">
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
              <div className="inline-block text-[11px] font-bold uppercase tracking-wide text-emerald-700">
                Clear Information
              </div>
            </div>
          </motion.div>

          <motion.div
            {...landingEntrance(reduceMotion, 'card', 0.12)}
            whileHover={reduceMotion ? undefined : { y: -3 }}
            className="flex flex-col justify-between border-b border-emerald-900/10 px-1 py-7 last:border-b-0 md:border-b-0 md:px-7 md:first:pl-0 md:last:pr-0"
          >
            <div>
              <div className="mb-5 flex h-9 w-9 items-center justify-center text-emerald-700">
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
              <div className="inline-block text-[11px] font-bold uppercase tracking-wide text-emerald-700">
                Upfront Pricing
              </div>
            </div>
          </motion.div>

        </div>

      </div>
    </section>
  );
};
