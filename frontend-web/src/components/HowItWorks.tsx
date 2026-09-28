import React from 'react';
import { motion, useReducedMotion } from 'framer-motion';
import { Search, UserCheck, Gift, ArrowRight, ShieldCheck, CheckCircle2 } from 'lucide-react';
import { landingEntrance, landingHeadingMask } from '../utils/landingMotion';

const steps = [
  {
    number: '01',
    title: 'Search & Discover Rental Homes',
    description: 'Filter flats and residential properties with transparent pricing, accurate locality details, and direct property information.',
    icon: Search,
    color: 'emerald',
    badge: 'Direct Listings',
    bullets: ['Search by city, locality, and BHK', 'Detailed photos, amenities, and pricing', 'Direct property details without middleman markups']
  },
  {
    number: '02',
    title: 'Request a Property Visit Online',
    description: 'Choose your preferred visit timing and submit your request online. View property details and schedule visits directly through the platform.',
    icon: UserCheck,
    color: 'blue',
    badge: 'Online Scheduling',
    bullets: ['Submit visit requests online', 'Flexible date and timing preferences', 'Direct property visit requests']
  },
  {
    number: '03',
    title: 'Connect Directly & Finalize Lease',
    description: 'Discuss and agree on rental terms directly with the property owner. Move forward with your rental agreement with full transparency and zero broker commissions.',
    icon: Gift,
    color: 'amber',
    badge: 'Direct Agreement',
    bullets: ['Direct owner discussions', 'Clear rent and deposit terms', 'Zero middleman commission traps']
  }
];

export const HowItWorks: React.FC = () => {
  const reduceMotion = useReducedMotion();
  return (
    <section id="how-it-works" className="relative border-b border-slate-200/80 bg-white py-16 sm:py-20 lg:py-24">
      <div className="relative mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="grid gap-10 md:grid-cols-[minmax(0,0.8fr)_minmax(0,1.2fr)] md:gap-12 lg:gap-20">
          {/* A short desktop editorial anchor while the three existing steps pass beside it. */}
          <motion.div
            {...landingEntrance(reduceMotion, 'heading', 0, 'left')}
            className="max-w-lg self-start lg:sticky lg:top-28 motion-reduce:lg:static"
          >
            <span className="text-xs font-extrabold uppercase tracking-wider text-emerald-700">Simple 3-Step Journey</span>
            <motion.h2 {...landingHeadingMask(reduceMotion, 0.06)} className="mt-3 font-['Outfit',sans-serif] text-3xl font-extrabold tracking-tight text-slate-900 sm:text-4xl">
              How Pathome Works
            </motion.h2>
            <p className="mt-3 text-sm leading-relaxed text-slate-600">
              Eliminating broker markups and ghost listings with transparent pricing & direct visit requests.
            </p>
            <div className="mt-7 h-px w-14 bg-emerald-500" aria-hidden="true" />
          </motion.div>

          <div className="border-l border-slate-200 pl-5 sm:pl-8">
            {steps.map((step, index) => {
              const Icon = step.icon;
              return (
                <motion.article
                  key={step.number}
                  {...landingEntrance(reduceMotion, 'section', index * 0.06, 'right')}
                  className="relative border-b border-slate-200 py-8 first:pt-0 last:border-b-0 last:pb-0"
                >
                  <span className="absolute -left-[1.55rem] top-9 h-2 w-2 rounded-full bg-emerald-600 ring-4 ring-white first:top-1 sm:-left-[2.3rem]" aria-hidden="true" />
                  <div className="mb-4 flex items-center justify-between gap-4">
                    <span className="font-['Outfit',sans-serif] text-3xl font-bold text-emerald-700/50">{step.number}</span>
                    <Icon className="h-5 w-5 text-emerald-700" aria-hidden="true" />
                  </div>
                  <span className="text-[10px] font-bold uppercase tracking-wider text-emerald-700">{step.badge}</span>
                  <h3 className="mt-2 font-['Outfit',sans-serif] text-xl font-bold leading-snug text-slate-900 sm:text-2xl">{step.title}</h3>
                  <p className="mt-2.5 text-sm leading-relaxed text-slate-600">{step.description}</p>
                  <div className="mt-5 grid gap-2 border-t border-slate-100 pt-4 sm:grid-cols-2">
                    {step.bullets.map((bullet, idx) => (
                      <div key={idx} className="flex items-start gap-2 text-xs font-medium leading-relaxed text-slate-700">
                        <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-emerald-600" aria-hidden="true" />
                        <span>{bullet}</span>
                      </div>
                    ))}
                  </div>
                </motion.article>
              );
            })}
          </div>
        </div>

        {/* CTA Banner bottom of how it works */}
        <motion.div
          {...landingEntrance(reduceMotion, 'section', 0, 'right')}
          className="mt-16 flex flex-col items-center justify-between gap-6 rounded-2xl bg-slate-900 p-6 text-white sm:flex-row sm:p-8"
        >
          <div className="flex items-center gap-4">
            <div className="w-12 h-12 rounded-2xl bg-emerald-500/20 text-emerald-400 flex items-center justify-center shrink-0 border border-emerald-500/30">
              <ShieldCheck className="w-6 h-6" />
            </div>
            <div>
              <h4 className="text-lg font-bold font-['Outfit',sans-serif]">Ready to explore available rental homes?</h4>
              <p className="text-xs text-slate-300 mt-0.5">Browse active listings across Indore and request a property visit online.</p>
            </div>
          </div>

          <a 
            href="#listings"
            className="bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs px-6 py-3.5 rounded-xl transition-all shadow-md shadow-emerald-600/30 flex items-center gap-2 whitespace-nowrap shrink-0"
          >
            <span>Browse Rental Listings</span>
            <ArrowRight className="w-4 h-4" />
          </a>
        </motion.div>

      </div>
    </section>
  );
};
