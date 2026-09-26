import React from 'react';
import { motion } from 'framer-motion';
import { Search, UserCheck, Gift, ArrowRight, ShieldCheck, CheckCircle2 } from 'lucide-react';

const steps = [
  {
    number: '01',
    title: 'Search & Pick Verified Homes',
    description: 'Filter 100% verified flats, independent houses, or land plots with transparent pricing and zero broker involvement.',
    icon: Search,
    color: 'emerald',
    badge: '100% Direct Owner',
    bullets: ['PostGIS 150m spatial radius check', 'Real photo galleries & amenities', 'Zero brokerage list pricing']
  },
  {
    number: '02',
    title: 'Free Escorted Tour with On-Site Escort',
    description: 'Request a visit pass (first 5 visits are completely free). Our local on-site escort accompanies you on the visit and verifies property access via secure Double-OTP.',
    icon: UserCheck,
    color: 'blue',
    badge: 'Physical Escort Pass',
    bullets: ['On-site escort meeting', 'Double-OTP tenant & owner sign-off', '5 free visit passes guaranteed']
  },
  {
    number: '03',
    title: 'Finalize Lease & Claim ₹1,000 Cashback',
    description: 'Finalize rent directly with the owner. Upload your executed lease agreement on Pathome to get ₹1,000 credited directly to your UPI bank account.',
    icon: Gift,
    color: 'amber',
    badge: 'Direct UPI Payout',
    bullets: ['No hidden processing deductions', '24-hour verification turnaround', 'Direct bank payout']
  }
];

export const HowItWorks: React.FC = () => {
  return (
    <section id="how-it-works" className="py-12 bg-white border-b border-slate-200/80 relative overflow-hidden">
      
      {/* Decorative Background Pattern */}
      <div className="absolute inset-0 bg-[radial-gradient(#e2e8f0_1px,transparent_1px)] [background-size:24px_24px] opacity-40 pointer-events-none" />

      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 relative z-10">
        
        {/* Header */}
        <motion.div 
          initial={{ opacity: 0, y: 16 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true }}
          transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
          className="text-center max-w-2xl mx-auto mb-10"
        >
          <span className="text-xs font-extrabold text-emerald-700 uppercase tracking-wider bg-emerald-50 px-3.5 py-1.5 rounded-full border border-emerald-200 shadow-2xs">
            Simple 3-Step Journey
          </span>
          <h2 className="text-3xl sm:text-4xl font-extrabold text-slate-900 font-['Outfit',sans-serif] mt-3 tracking-tight">
            How Pathome Works
          </h2>
          <p className="text-slate-600 text-xs sm:text-sm mt-2.5 leading-relaxed">
            Eliminating broker markups and ghost listings with physical ground verification & direct cashback rewards.
          </p>
        </motion.div>

        {/* 3 Steps Grid */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-8 relative">
          
          {steps.map((step, index) => {
            const Icon = step.icon;
            return (
              <motion.div
                key={step.number}
                initial={{ opacity: 0, y: 24 }}
                whileInView={{ opacity: 1, y: 0 }}
                viewport={{ once: true }}
                transition={{ duration: 0.55, delay: index * 0.15, ease: [0.16, 1, 0.3, 1] }}
                whileHover={{ y: -4 }}
                className="bg-slate-50/80 rounded-3xl p-7 border border-slate-200/90 shadow-sm hover:shadow-xl hover:shadow-slate-200/60 transition-all duration-300 flex flex-col justify-between relative group"
              >
                <div>
                  {/* Step Number & Icon Header */}
                  <div className="flex items-center justify-between mb-6">
                    <span className="text-4xl font-black font-['Outfit',sans-serif] text-slate-300 group-hover:text-emerald-600 transition-colors">
                      {step.number}
                    </span>
                    <div className="w-12 h-12 rounded-2xl bg-white text-emerald-600 flex items-center justify-center border border-slate-200 shadow-sm group-hover:scale-110 transition-transform">
                      <Icon className="w-6 h-6 text-emerald-600" />
                    </div>
                  </div>

                  <span className="text-[10px] font-bold uppercase tracking-wider px-2.5 py-1 rounded-md bg-emerald-100/70 text-emerald-800 border border-emerald-200/60 font-mono mb-3 inline-block">
                    {step.badge}
                  </span>

                  <h3 className="text-xl font-bold text-slate-900 font-['Outfit',sans-serif] mb-2.5 leading-snug">
                    {step.title}
                  </h3>

                  <p className="text-xs text-slate-600 leading-relaxed mb-6">
                    {step.description}
                  </p>
                </div>

                {/* Bullets */}
                <div className="space-y-2 pt-4 border-t border-slate-200/70">
                  {step.bullets.map((bullet, idx) => (
                    <div key={idx} className="flex items-center gap-2 text-xs text-slate-700 font-medium">
                      <CheckCircle2 className="w-3.5 h-3.5 text-emerald-600 shrink-0" />
                      <span>{bullet}</span>
                    </div>
                  ))}
                </div>
              </motion.div>
            );
          })}

        </div>

        {/* CTA Banner bottom of how it works */}
        <motion.div
          initial={{ opacity: 0, y: 16 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true }}
          transition={{ duration: 0.5, delay: 0.3 }}
          className="mt-14 bg-gradient-to-r from-emerald-900 via-slate-900 to-slate-950 p-6 sm:p-8 rounded-3xl text-white border border-emerald-500/20 shadow-xl flex flex-col sm:flex-row items-center justify-between gap-6"
        >
          <div className="flex items-center gap-4">
            <div className="w-12 h-12 rounded-2xl bg-emerald-500/20 text-emerald-400 flex items-center justify-center shrink-0 border border-emerald-500/30">
              <ShieldCheck className="w-6 h-6" />
            </div>
            <div>
              <h4 className="text-lg font-bold font-['Outfit',sans-serif]">Ready to explore verified rental spaces?</h4>
              <p className="text-xs text-slate-300 mt-0.5">Start your 5 free visit passes with physical on-site escort today.</p>
            </div>
          </div>

          <a 
            href="#listings"
            className="bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs px-6 py-3.5 rounded-xl transition-all shadow-md shadow-emerald-600/30 flex items-center gap-2 whitespace-nowrap shrink-0"
          >
            <span>Browse Verified Listings</span>
            <ArrowRight className="w-4 h-4" />
          </a>
        </motion.div>

      </div>
    </section>
  );
};
