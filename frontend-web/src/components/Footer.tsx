import React from 'react';
import { Building2, MapPin, Phone, Mail, ShieldCheck, Heart } from 'lucide-react';

interface FooterProps {
  onSelectSector?: (city: string, sector: string) => void;
}

const popularSectors = [
  'Vijay Nagar',
  'Bhawarkua',
  'Nipania',
  'AB Road',
  'Super Corridor',
  'LIG Circle',
  'Old Palasia',
  'Mahalaxmi Nagar'
];

export const Footer: React.FC<FooterProps> = ({ onSelectSector }) => {
  return (
    <footer id="footer" className="bg-slate-950 text-slate-400 font-['Inter',sans-serif] border-t border-slate-800">
      
      {/* Main Footer Links Container */}
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 pt-16 pb-12">
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-5 gap-10">
          
          {/* Brand Info Column */}
          <div className="lg:col-span-2 space-y-4">
            <div className="flex items-center gap-2.5">
              <div className="w-9 h-9 rounded-xl bg-emerald-600 text-white flex items-center justify-center font-bold shadow-md shadow-emerald-600/20">
                <Building2 className="w-5 h-5" />
              </div>
              <span className="font-['Outfit',sans-serif] text-xl font-bold text-white tracking-tight">Path<span className="text-emerald-500">ome</span></span>
            </div>

            <p className="text-xs leading-relaxed text-slate-400 max-w-sm">
              Pathome — <span className="pathome-tagline text-white font-semibold">Your Dreams, Our Efforts.</span> Premier zero-brokerage rental & plot marketplace backed by PostGIS spatial geofencing and physical verification.
            </p>

            <div className="pt-2 flex flex-wrap items-center gap-2">
              <span className="inline-flex items-center gap-1.5 text-[11px] font-semibold text-emerald-400 bg-emerald-500/10 px-3 py-1 rounded-full border border-emerald-500/20">
                <ShieldCheck className="w-3.5 h-3.5 text-emerald-400" />
                PostGIS Spatial Geofence Active
              </span>
              <span className="inline-flex items-center gap-1.5 text-[11px] font-semibold text-amber-400 bg-amber-500/10 px-3 py-1 rounded-full border border-amber-500/20 font-mono">
                ₹1,000 Direct Cashback
              </span>
            </div>
          </div>

          {/* Quick Links Column */}
          <div className="space-y-3">
            <h4 className="text-xs font-bold uppercase tracking-wider text-slate-200 font-['Outfit',sans-serif]">
              Quick Navigation
            </h4>
            <ul className="space-y-2 text-xs">
              <li>
                <a href="#hero" className="hover:text-emerald-400 transition-colors">Home & Search</a>
              </li>
              <li>
                <a href="#listings" className="hover:text-emerald-400 transition-colors">Verified Rentals</a>
              </li>
              <li>
                <a href="#how-it-works" className="hover:text-emerald-400 transition-colors">How It Works</a>
              </li>
              <li>
                <a href="#why-us" className="hover:text-emerald-400 transition-colors">Why Pathome</a>
              </li>
              <li>
                <a href="#land-plots" className="hover:text-emerald-400 transition-colors">Phase 2 Plot Sales</a>
              </li>
            </ul>
          </div>

          {/* Popular Indore Sectors Column */}
          <div className="space-y-3">
            <h4 className="text-xs font-bold uppercase tracking-wider text-slate-200 font-['Outfit',sans-serif]">
              Popular Sectors (Indore)
            </h4>
            <ul className="space-y-2 text-xs">
              {popularSectors.map((sector) => (
                <li key={sector}>
                  <a
                    href={`/?city=Indore&sector=${encodeURIComponent(sector)}#listings`}
                    onClick={(e) => {
                      if (onSelectSector) {
                        e.preventDefault();
                        onSelectSector('Indore', sector);
                      }
                    }}
                    className="hover:text-emerald-400 transition-colors flex items-center gap-1.5 focus:outline-none focus:text-emerald-400"
                  >
                    <MapPin className="w-3 h-3 text-slate-500 shrink-0" />
                    <span>{sector}</span>
                  </a>
                </li>
              ))}
            </ul>
          </div>

          {/* Contact & Support HQ Column */}
          <div className="space-y-3">
            <h4 className="text-xs font-bold uppercase tracking-wider text-slate-200 font-['Outfit',sans-serif]">
              Indore HQ Contact
            </h4>
            <div className="space-y-2.5 text-xs">
              <div className="flex items-start gap-2 text-slate-400">
                <MapPin className="w-4 h-4 text-emerald-500 shrink-0 mt-0.5" />
                <span>AB Road, Vijay Nagar, Indore, Madhya Pradesh - 452010</span>
              </div>
              <div className="flex items-center gap-2 text-slate-400">
                <Phone className="w-4 h-4 text-emerald-500 shrink-0" />
                <span>+91 98260 00000 / 0731-4000000</span>
              </div>
              <div className="flex items-center gap-2 text-slate-400">
                <Mail className="w-4 h-4 text-emerald-500 shrink-0" />
                <span>support@pathome.com</span>
              </div>
            </div>

            <div className="pt-3">
              <span className="text-[10px] font-semibold text-slate-400 bg-slate-900 px-3 py-1.5 rounded-lg border border-slate-800 inline-block">
                On-Site Verification Team: Field Personnel Active
              </span>
            </div>
          </div>

        </div>
      </div>

      {/* Bottom Bar */}
      <div className="border-t border-slate-900 bg-slate-950/90 py-6">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 flex flex-col sm:flex-row items-center justify-between gap-4 text-xs text-slate-500">
          <p>© {new Date().getFullYear()} Pathome Technologies Pvt. Ltd. All rights reserved.</p>
          <div className="flex items-center gap-1 text-slate-400">
            <span>Engineered with</span>
            <Heart className="w-3.5 h-3.5 text-emerald-500 fill-emerald-500" />
            <span>for Indian Real Estate Transparency</span>
          </div>
        </div>
      </div>

    </footer>
  );
};
