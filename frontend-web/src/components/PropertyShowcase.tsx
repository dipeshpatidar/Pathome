import React, { useState } from 'react';
import {
  motion,
  AnimatePresence
} from 'framer-motion';
import { Property } from '../types';
import {
  MapPin,
  Key,
  Lock,
  ChevronLeft,
  ChevronRight,
  CheckCircle2,
  ShieldCheck,
  Sparkles,
  ArrowRight,
  ChevronDown,
  ChevronUp,
  Camera
} from 'lucide-react';
import { useNotification } from '../context/NotificationContext';

interface PropertyShowcaseProps {
  properties: Property[];
  onBookTour: (property: Property) => void;
  onOpenMediaModal?: (property: Property, initialMode?: 'VIDEO' | 'PHOTOS') => void;
  selectedSectorFilter?: string;
}

const SECTORS_TAB = [
  'ALL',
  'Vijay Nagar',
  'Bhawarkua',
  'Nipania',
  'AB Road',
  'Super Corridor',
  'LIG Circle'
];

const getOrdinal = (n: number): string => {
  const s = ['th', 'st', 'nd', 'rd'];
  const v = n % 100;
  return n + (s[(v - 20) % 10] || s[v] || s[0]);
};

const formatFloorDisplay = (floor: number | null | undefined, totalFloors: number | null | undefined): string | null => {
  if (floor === null || floor === undefined) {
    if (typeof totalFloors === 'number' && totalFloors > 0) {
      return `${totalFloors} Floors Building`;
    }
    return null;
  }
  const floorName = floor === 0 ? 'Ground Floor' : `${getOrdinal(floor)} Floor`;
  if (typeof totalFloors === 'number' && totalFloors > 0) {
    return `${floorName} of ${totalFloors}`;
  }
  return floorName;
};

const formatPreferredTenantDisplay = (pref: string | null | undefined): string | null => {
  if (!pref || !pref.trim()) return null;
  const labelMap: Record<string, string> = {
    FAMILY: 'Family',
    WORKING_PROFESSIONALS: 'Working Professionals',
    BACHELORS: 'Bachelors',
    STUDENTS: 'Students',
    ANY: 'No Preference'
  };
  const parts = pref.split(',').map((p) => p.trim().toUpperCase()).filter(Boolean);
  const formatted = parts.map((p) => labelMap[p] || p);
  if (formatted.length === 0) return null;
  return `Preferred: ${formatted.join(', ')}`;
};

export const PropertyShowcase: React.FC<PropertyShowcaseProps> = ({
  properties,
  onBookTour,
  onOpenMediaModal,
  selectedSectorFilter
}) => {
  const { notifySuccess } = useNotification();
  const [activeImageIndex, setActiveImageIndex] = useState<Record<number, number>>({});
  const [slideDirection, setSlideDirection] = useState<Record<number, 'left' | 'right'>>({});
  const [activeSectorTab, setActiveSectorTab] = useState<string>('ALL');
  const [visibleCount, setVisibleCount] = useState<number>(6);

  const handleNextImage = (propId: number, maxImages: number, e: React.MouseEvent) => {
    e.stopPropagation();
    setSlideDirection(prev => ({ ...prev, [propId]: 'right' }));
    setActiveImageIndex(prev => ({
      ...prev,
      [propId]: ((prev[propId] || 0) + 1) % maxImages
    }));
  };

  const handlePrevImage = (propId: number, maxImages: number, e: React.MouseEvent) => {
    e.stopPropagation();
    setSlideDirection(prev => ({ ...prev, [propId]: 'left' }));
    setActiveImageIndex(prev => ({
      ...prev,
      [propId]: ((prev[propId] || 0) - 1 + maxImages) % maxImages
    }));
  };

  const currentSector = selectedSectorFilter || (activeSectorTab !== 'ALL' ? activeSectorTab : '');
  
  const filteredProperties = properties.filter(prop => {
    if (!currentSector || currentSector === 'ALL') return true;
    return prop.sector.toLowerCase().includes(currentSector.toLowerCase());
  });

  const displayedProperties = filteredProperties.slice(0, visibleCount);
  const remainingCount = filteredProperties.length - visibleCount;

  const handleSelectTab = (sector: string) => {
    setActiveSectorTab(sector);
    setVisibleCount(6);
  };

  return (
    <section id="listings" className="py-14 bg-slate-50/80 border-b border-slate-200/80 relative overflow-hidden font-['Inter',sans-serif]">
      
      {/* Background Soft Glow & Laser Accent Grid */}
      <div className="absolute top-1/4 left-1/4 w-[32rem] h-[32rem] bg-emerald-500/5 blur-3xl rounded-full pointer-events-none" />
      <div className="absolute bottom-1/4 right-1/4 w-[32rem] h-[32rem] bg-indigo-500/5 blur-3xl rounded-full pointer-events-none" />

      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 relative z-10">
        
        {/* SECTION HEADER */}
        <motion.div
          initial={{ opacity: 0, y: 16 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true }}
          transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
          className="flex flex-col lg:flex-row lg:items-end justify-between mb-12 pb-6 border-b border-slate-200/80 gap-6"
        >
          <div>
            <div className="flex flex-wrap items-center gap-2.5">
              <span className="text-xs font-extrabold text-emerald-800 uppercase tracking-wider bg-emerald-50 px-3.5 py-1.5 rounded-full border border-emerald-200 flex items-center gap-1.5 shadow-2xs font-mono">
                <Sparkles className="w-3.5 h-3.5 text-emerald-600 animate-pulse" />
                100% On-Site Vetted Portfolio
              </span>
              <span className="bg-emerald-600 text-white font-mono font-bold text-xs px-3 py-1 rounded-full shadow-xs">
                {filteredProperties.length} Verified Spaces
              </span>
            </div>

            <h2 className="text-3xl sm:text-4xl font-extrabold text-slate-900 font-['Outfit',sans-serif] mt-2.5 tracking-tight">
              Verified Properties <span className="text-emerald-600">in Indore</span>
            </h2>
            <p className="text-slate-600 text-xs sm:text-sm mt-1 leading-relaxed">
              Alternating editorial showcase of direct owner rentals & Phase 2 land plots with physical Ground Boy escort.
            </p>
          </div>

          {/* Sector Filter Tabs */}
          <div className="flex items-center gap-1.5 overflow-x-auto pb-1 max-w-full no-scrollbar">
            {SECTORS_TAB.map((sector) => {
              const isActive = (activeSectorTab === sector && !selectedSectorFilter) || (selectedSectorFilter === sector);
              return (
                <motion.button
                  key={sector}
                  whileHover={{ scale: 1.05, y: -1 }}
                  whileTap={{ scale: 0.95 }}
                  onClick={() => handleSelectTab(sector)}
                  className={`px-4 py-2 rounded-xl text-xs font-bold whitespace-nowrap transition-all duration-200 ${
                    isActive
                      ? 'bg-emerald-600 text-white shadow-md shadow-emerald-600/30'
                      : 'bg-white text-slate-600 hover:text-slate-900 hover:bg-slate-100 border border-slate-200/90 shadow-2xs'
                  }`}
                >
                  {sector === 'ALL' ? 'All Sectors' : sector}
                </motion.button>
              );
            })}
          </div>
        </motion.div>

        {/* ZIG-ZAG ALTERNATING EDITORIAL PROPERTY SHOWCASE */}
        {displayedProperties.length > 0 ? (
          <>
            <div className="space-y-16 lg:space-y-20 relative">
              
              {/* Center Visual Timeline Connector Line for Desktop */}
              <div className="hidden lg:block absolute top-12 bottom-12 left-1/2 -translate-x-1/2 w-0.5 bg-gradient-to-b from-emerald-500/30 via-emerald-600/20 to-emerald-500/30 pointer-events-none z-0">
                <div className="absolute top-1/4 -left-1.5 w-3.5 h-3.5 rounded-full bg-emerald-500 animate-ping opacity-75" />
                <div className="absolute top-3/4 -left-1.5 w-3.5 h-3.5 rounded-full bg-emerald-500 animate-ping opacity-75" style={{ animationDelay: '1.5s' }} />
              </div>

              {displayedProperties.map((prop, index) => {
                const isEven = index % 2 === 0;
                const currentImgIdx = activeImageIndex[prop.id] || 0;
                const dir = slideDirection[prop.id] || 'right';

                return (
                  <motion.div
                    key={prop.id}
                    initial={{ opacity: 0, x: isEven ? -35 : 35, y: 20 }}
                    whileInView={{ opacity: 1, x: 0, y: 0 }}
                    viewport={{ once: true, margin: "-60px" }}
                    transition={{ duration: 0.55, ease: [0.16, 1, 0.3, 1] }}
                    className="relative z-10 group"
                  >
                    <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 lg:gap-0 items-center">
                      
                      {/* IMAGE CAROUSEL CONTAINER (7 COLUMNS) */}
                      <div className={`lg:col-span-7 ${isEven ? 'order-1' : 'order-1 lg:order-2'}`}>
                        <motion.div 
                          whileHover={{ scale: 1.01 }}
                          transition={{ type: 'spring', stiffness: 400, damping: 25 }}
                          onClick={() => onOpenMediaModal?.(prop, 'PHOTOS')}
                          className="relative h-64 sm:h-80 lg:h-[380px] rounded-3xl overflow-hidden shadow-xl shadow-slate-900/10 bg-slate-900 border border-slate-200/90 group-hover:shadow-2xl group-hover:shadow-slate-900/20 transition-all duration-500 cursor-pointer"
                        >
                          
                          <AnimatePresence mode="wait" initial={false}>
                            <motion.img
                              key={currentImgIdx}
                              initial={{ opacity: 0, scale: 1.08, x: dir === 'right' ? 50 : -50 }}
                              animate={{ opacity: 1, scale: 1, x: 0 }}
                              exit={{ opacity: 0, scale: 0.95, x: dir === 'right' ? -50 : 50 }}
                              transition={{ duration: 0.35, ease: [0.16, 1, 0.3, 1] }}
                              src={prop.images[currentImgIdx] || prop.images[0]}
                              alt={prop.title}
                              className="w-full h-full object-cover group-hover:scale-106 transition-transform duration-700 ease-out"
                            />
                          </AnimatePresence>

                          {/* Dark Gradient Overlay */}
                          <div className="absolute inset-0 bg-gradient-to-t from-slate-950/85 via-transparent to-slate-950/40 pointer-events-none" />

                          {/* Badges */}
                          <div className="absolute top-4 left-4 right-4 flex items-center justify-between z-10">
                            <div className="flex items-center gap-2 flex-wrap">
                              <motion.span 
                                whileHover={{ scale: 1.08 }}
                                className="bg-slate-950/90 text-emerald-300 text-[10px] font-extrabold px-3.5 py-1.5 rounded-full border border-emerald-500/40 flex items-center gap-1.5 backdrop-blur-md shadow-lg font-mono"
                              >
                                <ShieldCheck className="w-3.5 h-3.5 text-emerald-400" /> Escort Verified
                              </motion.span>
                              
                              {/* Active Image Room Tag Badge */}
                              {(() => {
                                const currentImgIdx = activeImageIndex[prop.id] || 0;
                                const tagKey = prop.taggedMedia?.[currentImgIdx]?.roomTag || ['LIVING_ROOM', 'MASTER_BEDROOM', 'KITCHEN', 'BATHROOM', 'BALCONY', 'BEDROOM', 'EXTERIOR'][currentImgIdx % 7];
                                const tagEmojis: Record<string, string> = {
                                  LIVING_ROOM: '🛋️ Living Room',
                                  MASTER_BEDROOM: '🛏️ Master Bedroom',
                                  BEDROOM: '🛏️ Guest Bedroom',
                                  KITCHEN: '🍳 Kitchen',
                                  BATHROOM: '🚿 Bathroom',
                                  BALCONY: '🌅 Balcony',
                                  EXTERIOR: '🏢 Exterior',
                                  AMENITIES: '🏊 Amenities',
                                  FLOOR_PLAN: '📐 Floor Plan'
                                };
                                const label = tagEmojis[tagKey];
                                if (!label || tagKey === 'GENERAL') return null;
                                return (
                                  <span className="bg-slate-950/90 text-amber-300 text-[10px] font-extrabold px-2.5 py-1 rounded-full border border-amber-500/40 backdrop-blur-md shadow-md font-mono">
                                    {label}
                                  </span>
                                );
                              })()}

                              <button
                                onClick={(e) => {
                                  e.stopPropagation();
                                  onOpenMediaModal?.(prop, 'PHOTOS');
                                }}
                                className="bg-emerald-600 hover:bg-emerald-500 text-white text-[10px] font-extrabold px-3 py-1.5 rounded-full border border-emerald-400/30 flex items-center gap-1 backdrop-blur-md shadow-md transition-colors font-mono"
                                title="Open HD Photos Lightbox Window"
                              >
                                <Camera className="w-3.5 h-3.5 text-white" />
                                <span>{prop.images.length} HD Photos</span>
                              </button>
                            </div>

                            <motion.button
                              whileHover={{ scale: 1.2, rotate: 15 }}
                              whileTap={{ scale: 0.85 }}
                              onClick={(e) => { e.stopPropagation(); notifySuccess('Saved to bookmarks', `${prop.title} is available in your saved properties.`); }}
                              className="w-9 h-9 rounded-full bg-slate-900/80 hover:bg-emerald-600 text-white flex items-center justify-center backdrop-blur-md border border-white/25 shadow-lg transition-all text-xs"
                            >
                              ★
                            </motion.button>
                          </div>

                          {/* Carousel Arrow Controls */}
                          {prop.images.length > 1 && (
                            <>
                              <motion.button
                                whileHover={{ scale: 1.2, x: -2 }}
                                whileTap={{ scale: 0.9 }}
                                onClick={(e) => handlePrevImage(prop.id, prop.images.length, e)}
                                className="absolute left-3.5 top-1/2 -translate-y-1/2 w-9 h-9 rounded-full bg-white/95 text-slate-900 flex items-center justify-center shadow-xl backdrop-blur-md transition-all opacity-0 group-hover:opacity-100 z-10 hover:bg-white"
                              >
                                <ChevronLeft className="w-5 h-5" />
                              </motion.button>
                              <motion.button
                                whileHover={{ scale: 1.2, x: 2 }}
                                whileTap={{ scale: 0.9 }}
                                onClick={(e) => handleNextImage(prop.id, prop.images.length, e)}
                                className="absolute right-3.5 top-1/2 -translate-y-1/2 w-9 h-9 rounded-full bg-white/95 text-slate-900 flex items-center justify-center shadow-xl backdrop-blur-md transition-all opacity-0 group-hover:opacity-100 z-10 hover:bg-white"
                              >
                                <ChevronRight className="w-5 h-5" />
                              </motion.button>
                            </>
                          )}

                          {/* Carousel Dots */}
                          {prop.images.length > 1 && (
                            <div className="absolute bottom-4 left-0 right-0 flex justify-center gap-1.5 z-10">
                              {prop.images.map((_, i) => (
                                <span 
                                  key={i} 
                                  className={`h-1.5 rounded-full transition-all duration-300 ${i === currentImgIdx ? 'w-6 bg-emerald-400' : 'w-1.5 bg-white/50'}`}
                                />
                              ))}
                            </div>
                          )}
                        </motion.div>
                      </div>

                      {/* SIGNATURE FLOATING CONTENT CARD CONTAINER (5 COLUMNS OVERLAPPING) */}
                      <div className={`lg:col-span-5 ${isEven ? 'order-2 lg:-ml-14' : 'order-2 lg:order-1 lg:-mr-14'} relative z-20`}>
                        
                        <motion.div 
                          whileHover={{ y: -4, scale: 1.01 }}
                          transition={{ type: 'spring', stiffness: 450, damping: 25 }}
                          className="bg-white rounded-3xl p-6 sm:p-7 shadow-2xl shadow-slate-900/12 border border-slate-200/90 relative backdrop-blur-md transition-all duration-300 hover:shadow-emerald-900/15"
                        >
                          {/* Crest Emblem */}
                          <motion.div 
                            whileHover={{ scale: 1.18, rotate: 12 }}
                            transition={{ type: 'spring', stiffness: 500, damping: 15 }}
                            className="w-11 h-11 rounded-2xl bg-slate-900 text-emerald-400 border-4 border-white shadow-xl flex items-center justify-center absolute -top-5 left-6 z-30 group-hover:bg-emerald-600 group-hover:text-white transition-colors duration-300"
                          >
                            <CheckCircle2 className="w-5 h-5 text-emerald-400 group-hover:text-white" />
                          </motion.div>

                          <div className="pt-1.5">
                            
                            {/* Micro-market & Area Row */}
                            <div className="flex items-center justify-between mb-2.5">
                              <span className="text-xs font-bold text-slate-700 flex items-center gap-1.5">
                                <MapPin className="w-4 h-4 text-emerald-600 shrink-0" />
                                {prop.sector}, Indore
                              </span>
                              <span className="text-xs font-mono font-bold text-slate-500 bg-slate-100 px-3 py-0.5 rounded-xl border border-slate-200/70 shadow-2xs">
                                {prop.totalAreaSqFt} sq ft
                              </span>
                            </div>

                            {/* Property Title */}
                            <h3 
                              onClick={() => onBookTour(prop)}
                              className="text-lg sm:text-xl font-extrabold text-slate-900 line-clamp-2 mb-3 font-['Outfit',sans-serif] group-hover:text-emerald-700 transition-colors leading-snug cursor-pointer"
                            >
                              {prop.title}
                            </h3>

                            {/* Floor & Preferred Tenant Specs Row */}
                            {(formatFloorDisplay(prop.floor, prop.totalFloors) || formatPreferredTenantDisplay(prop.preferredTenant)) && (
                              <div className="flex flex-wrap items-center gap-1.5 mb-3 text-[11px] font-medium text-slate-600">
                                {formatFloorDisplay(prop.floor, prop.totalFloors) && (
                                  <span className="inline-flex items-center gap-1 bg-slate-100 text-slate-700 px-2.5 py-0.5 rounded-xl border border-slate-200/80 font-mono text-[11px]">
                                    🏢 {formatFloorDisplay(prop.floor, prop.totalFloors)}
                                  </span>
                                )}
                                {formatPreferredTenantDisplay(prop.preferredTenant) && (
                                  <span className="inline-flex items-center gap-1 bg-emerald-50 text-emerald-800 px-2.5 py-0.5 rounded-xl border border-emerald-200/80 text-[11px]">
                                    👥 {formatPreferredTenantDisplay(prop.preferredTenant)}
                                  </span>
                                )}
                              </div>
                            )}

                            {/* Rent & Security Deposit Box */}
                            <div className="bg-slate-50 p-3.5 rounded-2xl border border-slate-200/80 mb-4 flex items-center justify-between shadow-inner">
                              <div>
                                <span className="text-[10px] text-slate-400 font-bold uppercase tracking-wider block mb-0.5">
                                  {prop.listingType === 'SALE' ? 'Asking Price' : 'Monthly Rent'}
                                </span>
                                <span className="text-xl sm:text-2xl font-black text-slate-900 font-mono tracking-tight">
                                  ₹{prop.monthlyRent ? prop.monthlyRent.toLocaleString('en-IN') : prop.askingPrice?.toLocaleString('en-IN')}
                                  {prop.monthlyRent > 0 && <span className="text-xs font-normal text-slate-500">/mo</span>}
                                </span>
                              </div>
                              {prop.securityDeposit ? (
                                <div className="text-right">
                                  <span className="text-[10px] text-slate-400 font-bold uppercase tracking-wider block mb-0.5">Security Deposit</span>
                                  <span className="text-xs font-extrabold text-emerald-700 bg-emerald-50 px-2.5 py-0.5 rounded-xl border border-emerald-200 font-mono inline-block shadow-2xs">
                                    ₹{prop.securityDeposit.toLocaleString('en-IN')}
                                  </span>
                                </div>
                              ) : (
                                <span className="text-[10px] font-extrabold text-emerald-700 bg-emerald-50 px-2.5 py-0.5 rounded-xl border border-emerald-200 uppercase font-mono">
                                  Phase 2 Deed
                                </span>
                              )}
                            </div>

                            {/* Owner Phone Mask & Escort Tour Action */}
                            <div className="space-y-2.5">
                              <div className="flex items-center justify-between text-xs text-slate-500 bg-slate-50 px-3.5 py-2 rounded-xl border border-slate-200/70">
                                <span className="flex items-center gap-1.5 font-medium">
                                  <Lock className="w-3.5 h-3.5 text-emerald-600" /> Direct Owner
                                </span>
                                <span className="font-mono text-slate-900 font-extrabold">{prop.ownerPhone}</span>
                              </div>

                              <motion.button
                                whileHover={{ scale: 1.02, y: -1 }}
                                whileTap={{ scale: 0.97 }}
                                transition={{ type: 'spring', stiffness: 500, damping: 22 }}
                                onClick={() => onBookTour(prop)}
                                className="w-full bg-emerald-600 hover:bg-emerald-700 text-white font-extrabold text-xs py-3.5 rounded-2xl shadow-lg shadow-emerald-600/25 hover:shadow-xl hover:shadow-emerald-600/35 transition-all flex items-center justify-center gap-2.5 uppercase tracking-wider shimmer-glow relative overflow-hidden"
                              >
                                <Key className="w-4 h-4 text-white shrink-0" />
                                <span className="text-white font-extrabold tracking-wider">Book Escorted Tour Pass</span>
                                <ArrowRight className="w-4 h-4 text-white shrink-0" />
                              </motion.button>
                            </div>

                          </div>

                        </motion.div>

                      </div>

                    </div>
                  </motion.div>
                );
              })}

            </div>

            {/* EXPANDABLE LOAD MORE BUTTON */}
            <div className="mt-12 text-center relative z-20">
              {remainingCount > 0 ? (
                <motion.button
                  whileHover={{ scale: 1.04, y: -2 }}
                  whileTap={{ scale: 0.96 }}
                  onClick={() => setVisibleCount(prev => prev + 6)}
                  className="bg-slate-900 hover:bg-slate-800 text-white font-extrabold text-xs px-8 py-4 rounded-2xl shadow-xl shadow-slate-900/20 border border-slate-800 inline-flex items-center gap-2.5 uppercase tracking-wider transition-all"
                >
                  <span>Explore More Verified Properties (+{remainingCount} Remaining)</span>
                  <ChevronDown className="w-4 h-4 text-emerald-400 animate-bounce" />
                </motion.button>
              ) : filteredProperties.length > 6 && (
                <motion.button
                  whileHover={{ scale: 1.04, y: -2 }}
                  whileTap={{ scale: 0.96 }}
                  onClick={() => setVisibleCount(6)}
                  className="bg-slate-100 hover:bg-slate-200 text-slate-800 font-extrabold text-xs px-8 py-3.5 rounded-2xl border border-slate-200 inline-flex items-center gap-2.5 uppercase tracking-wider transition-all"
                >
                  <span>Collapse View (Show Top 6)</span>
                  <ChevronUp className="w-4 h-4 text-slate-500" />
                </motion.button>
              )}
            </div>
          </>
        ) : (
          <div className="text-center py-16 bg-white rounded-3xl border border-slate-200 p-8 shadow-sm">
            <p className="text-slate-600 text-sm font-medium">No verified properties found in sector "{currentSector}".</p>
            <button 
              onClick={() => handleSelectTab('ALL')}
              className="mt-3 text-xs font-bold text-emerald-600 hover:underline"
            >
              Reset Sector Filter
            </button>
          </div>
        )}

      </div>

    </section>
  );
};
