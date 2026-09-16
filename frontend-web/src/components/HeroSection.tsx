import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence, LayoutGroup, useMotionValue, useTransform, useSpring } from 'framer-motion';
import { 
  Search, MapPin, ShieldCheck, Check, Sparkles, Building2, Map, Home, 
  ChevronDown, Mic, Compass, Zap, Globe, Rocket, ArrowRight
} from 'lucide-react';
import { useNotification } from '../context/NotificationContext';

interface HeroSectionProps {
  onSearch: (sector: string) => void;
}

interface CityOption {
  id: string;
  name: string;
  state: string;
  status: 'ACTIVE' | 'LAUNCHING_SOON';
  passesToday?: number;
  sectors: string[];
}

const CITIES: CityOption[] = [
  {
    id: 'INDORE',
    name: 'Indore',
    state: 'Madhya Pradesh',
    status: 'ACTIVE',
    passesToday: 184,
    sectors: ['Vijay Nagar', 'Bhawarkua', 'Nipania', 'LIG Circle', 'AB Road', 'Old Palasia', 'Super Corridor', 'Mahalaxmi Nagar']
  },
  {
    id: 'BHOPAL',
    name: 'Bhopal',
    state: 'Madhya Pradesh',
    status: 'LAUNCHING_SOON',
    sectors: ['MP Nagar', 'Arera Colony', 'Kolar Road', 'Hoshangabad Road']
  },
  {
    id: 'PUNE',
    name: 'Pune',
    state: 'Maharashtra',
    status: 'LAUNCHING_SOON',
    sectors: ['Baner', 'Wakad', 'Hinjewadi', 'Kharadi', 'Viman Nagar']
  },
  {
    id: 'BANGALORE',
    name: 'Bangalore',
    state: 'Karnataka',
    status: 'LAUNCHING_SOON',
    sectors: ['Indiranagar', 'Koramangala', 'HSR Layout', 'Whitefield']
  }
];

const HERO_BANNERS = [
  {
    id: 'skyline',
    title: 'Indore Sunset Lake & High-Rise Skyline',
    subtitle: 'Direct owner rentals in Vijay Nagar & Bhawarkua',
    image: '/assets/panoramic_skyline.jpg',
    tag: 'Indore Prime Metro Zone'
  },
  {
    id: 'luxury',
    title: 'Luxury Gated Residencies & Duplexes',
    subtitle: 'Physical Escort Verified homes with zero brokerage',
    image: '/assets/hero_luxury.jpg',
    tag: 'Gated 3BHK Flats'
  },
  {
    id: 'spatial',
    title: 'PostGIS Spatial Engine & Land Registry',
    subtitle: '150m geofence radius on-site verification',
    image: '/assets/spatial_gis.jpg',
    tag: 'Phase 2 Plot Sales'
  }
];

export const HeroSection: React.FC<HeroSectionProps> = ({ onSearch }) => {
  const { notifySuccess } = useNotification();
  const [selectedCityId, setSelectedCityId] = useState<string>('INDORE');
  const [activeTab, setActiveTab] = useState<'RENT' | 'PLOT' | 'COMMERCIAL' | 'PG'>('RENT');
  const [selectedSectors, setSelectedSectors] = useState<string[]>([]);
  const [propertyType, setPropertyType] = useState<string>('ALL');
  const [maxBudget, setMaxBudget] = useState<number>(25000);
  const [verifiedOnly, setVerifiedOnly] = useState<boolean>(false);
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [activeBannerIdx, setActiveBannerIdx] = useState<number>(0);
  const [showCityPicker, setShowCityPicker] = useState<boolean>(false);

  const selectedCity = CITIES.find(c => c.id === selectedCityId) || CITIES[0];

  // 3D Mouse Parallax Card Tilt Setup
  const mouseX = useMotionValue(0);
  const mouseY = useMotionValue(0);

  const rotateXRaw = useTransform(mouseY, [-200, 200], [6, -6]);
  const rotateYRaw = useTransform(mouseX, [-400, 400], [-8, 8]);

  const rotateX = useSpring(rotateXRaw, { stiffness: 300, damping: 25 });
  const rotateY = useSpring(rotateYRaw, { stiffness: 300, damping: 25 });

  const handleMouseMove = (e: React.MouseEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const centerX = rect.left + rect.width / 2;
    const centerY = rect.top + rect.height / 2;
    mouseX.set(e.clientX - centerX);
    mouseY.set(e.clientY - centerY);
  };

  const handleMouseLeave = () => {
    mouseX.set(0);
    mouseY.set(0);
  };

  // Switch sectors when city changes
  const handleSelectCity = (city: CityOption) => {
    setSelectedCityId(city.id);
    setSelectedSectors([city.sectors[0] || 'Main Sector']);
    setShowCityPicker(false);
    if (city.status === 'LAUNCHING_SOON') {
      notifySuccess('Early access requested', `We will let you know when Pathome becomes available in ${city.name}.`);
    }
  };

  // Auto-slide banner every 7 seconds
  useEffect(() => {
    const timer = setInterval(() => {
      setActiveBannerIdx((prev) => (prev + 1) % HERO_BANNERS.length);
    }, 7000);
    return () => clearInterval(timer);
  }, []);

  const currentBanner = HERO_BANNERS[activeBannerIdx];

  const toggleSector = (sector: string) => {
    if (selectedSectors.includes(sector)) {
      if (selectedSectors.length > 1) {
        setSelectedSectors(selectedSectors.filter(s => s !== sector));
      }
    } else {
      setSelectedSectors([...selectedSectors, sector]);
    }
  };

  return (
    <div className="relative bg-slate-950 font-['Inter',sans-serif] overflow-hidden">
      
      {/* ========================================================================= */}
      {/* 1. CINEMATIC FULL-BLEED PANORAMIC BACKDROP HERO BANNER WITH CRAZY TRANSITIONS */}
      {/* ========================================================================= */}
      <section className="relative min-h-[500px] sm:min-h-[560px] w-full flex flex-col justify-between pt-10 pb-28 px-4 sm:px-6 lg:px-8 overflow-hidden">
        
        {/* CRAZY BACKGROUND IMAGE TRANSITIONS (Zoom-Pan + Blur-Zoom + Ken Burns Motion) */}
        <AnimatePresence mode="wait">
          <motion.div
            key={currentBanner.id}
            initial={{ opacity: 0, scale: 1.18, filter: 'blur(12px) brightness(0.6)', rotate: 1.5 }}
            animate={{ 
              opacity: 1, 
              scale: 1, 
              filter: 'blur(0px) brightness(1)', 
              rotate: 0,
              transition: { duration: 1.1, ease: [0.16, 1, 0.3, 1] } 
            }}
            exit={{ 
              opacity: 0, 
              scale: 0.94, 
              filter: 'blur(10px) brightness(0.4)', 
              rotate: -1.5,
              transition: { duration: 0.75, ease: [0.16, 1, 0.3, 1] } 
            }}
            className="absolute inset-0 z-0 pointer-events-none"
          >
            {/* KEN BURNS CONTINUOUS SLOW PAN MOTION */}
            <motion.img
              src={currentBanner.image}
              alt={currentBanner.title}
              animate={{ 
                scale: [1, 1.08, 1],
                x: [0, -15, 0],
                y: [0, -10, 0]
              }}
              transition={{ duration: 16, repeat: Infinity, ease: 'easeInOut' }}
              className="w-full h-full object-cover object-center"
            />
            
            {/* Cinematic Gradient Vignette Overlay */}
            <div className="absolute inset-0 bg-gradient-to-t from-slate-950 via-slate-950/50 to-slate-950/70" />
            <div className="absolute inset-0 bg-[radial-gradient(#0f172a_1px,transparent_1px)] [background-size:28px_28px] opacity-35" />
          </motion.div>
        </AnimatePresence>

        {/* Ambient Glow Light Orbs */}
        <motion.div 
          animate={{ scale: [1, 1.25, 1], opacity: [0.3, 0.55, 0.3], x: [0, 30, 0] }}
          transition={{ duration: 8, repeat: Infinity, ease: "easeInOut" }}
          className="absolute top-10 left-1/4 w-96 h-96 bg-emerald-500/25 blur-[130px] rounded-full pointer-events-none z-10"
        />

        {/* TOP HERO HEADLINE & MULTI-CITY SELECTOR BAR */}
        <div className="max-w-7xl mx-auto w-full relative z-20 space-y-4 text-center sm:text-left">
          
          {/* MULTI-CITY SELECTION DRIVER */}
          <div className="flex flex-wrap items-center justify-center sm:justify-start gap-3">
            
            {/* DYNAMIC CITY SELECTOR DROPDOWN BUTTON (OPENS ON HOVER FOR FRICTIONLESS UX) */}
            <div 
              className="relative py-1"
              onMouseEnter={() => setShowCityPicker(true)}
              onMouseLeave={() => setShowCityPicker(false)}
            >
              <motion.button
                whileHover={{ scale: 1.04 }}
                whileTap={{ scale: 0.96 }}
                onClick={() => setShowCityPicker(!showCityPicker)}
                className="bg-slate-900/90 hover:bg-slate-800 text-white font-extrabold text-xs px-4 py-2 rounded-full border border-emerald-500/50 flex items-center gap-2 shadow-xl backdrop-blur-md font-mono"
              >
                <Globe className="w-4 h-4 text-emerald-400 animate-spin" style={{ animationDuration: '12s' }} />
                <span>City: <span className="text-emerald-400 uppercase tracking-wider">{selectedCity.name}</span></span>
                <ChevronDown className={`w-3.5 h-3.5 text-slate-400 transition-transform duration-300 ${showCityPicker ? 'rotate-180' : ''}`} />
              </motion.button>

              {/* CITY PICKER DROPDOWN MENU */}
              <AnimatePresence>
                {showCityPicker && (
                  <motion.div
                    initial={{ opacity: 0, scale: 0.95, y: 8 }}
                    animate={{ opacity: 1, scale: 1, y: 0 }}
                    exit={{ opacity: 0, scale: 0.95, y: 8 }}
                    transition={{ type: 'spring', stiffness: 450, damping: 28 }}
                    className="absolute left-0 mt-1 w-64 bg-slate-900 border border-slate-700/90 rounded-2xl p-2 shadow-2xl z-50 text-left text-white"
                  >
                    <div className="px-3 py-1.5 border-b border-slate-800 text-[10px] font-black uppercase text-slate-400 tracking-wider">
                      Select Target Metro
                    </div>
                    {CITIES.map((city) => (
                      <button
                        key={city.id}
                        onClick={() => handleSelectCity(city)}
                        className={`w-full text-left px-3 py-2 rounded-xl text-xs font-bold flex items-center justify-between transition-colors ${
                          city.id === selectedCityId ? 'bg-emerald-600 text-white' : 'hover:bg-slate-800 text-slate-300'
                        }`}
                      >
                        <div className="flex items-center gap-2">
                          <MapPin className="w-3.5 h-3.5" />
                          <div>
                            <p className="font-bold">{city.name}</p>
                            <p className="text-[10px] text-slate-400">{city.state}</p>
                          </div>
                        </div>
                        {city.status === 'ACTIVE' ? (
                          <span className="text-[9px] bg-emerald-950 text-emerald-300 px-2 py-0.5 rounded border border-emerald-700 font-mono">
                            Active HQ
                          </span>
                        ) : (
                          <span className="text-[9px] bg-amber-950 text-amber-300 px-2 py-0.5 rounded border border-amber-700 font-mono flex items-center gap-1">
                            <Rocket className="w-2.5 h-2.5" /> Soon
                          </span>
                        )}
                      </button>
                    ))}
                  </motion.div>
                )}
              </AnimatePresence>
            </div>

            {/* Live Escort Beacon Badge */}
            <motion.div
              initial={{ opacity: 0, y: -10 }}
              animate={{ opacity: 1, y: 0 }}
              className="inline-flex items-center gap-2 bg-emerald-950/85 backdrop-blur-md border border-emerald-500/40 text-emerald-300 text-xs font-extrabold px-4 py-1.5 rounded-full shadow-lg font-mono cursor-default"
            >
              <span className="flex h-2 w-2 relative">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
                <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-500"></span>
              </span>
              <span>⚡ 184 Escorts Active in {selectedCity.name}</span>
            </motion.div>

            {/* Zero Brokerage Guarantee Badge */}
            <motion.div
              initial={{ opacity: 0, y: -10 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.1 }}
              className="inline-flex items-center gap-1.5 bg-amber-950/85 backdrop-blur-md border border-amber-500/40 text-amber-300 text-xs font-extrabold px-3.5 py-1.5 rounded-full shadow-lg font-mono cursor-default"
            >
              <Sparkles className="w-3.5 h-3.5 text-amber-400 animate-spin" style={{ animationDuration: '8s' }} />
              <span>₹0 Brokerage Pan-India Guarantee</span>
            </motion.div>

          </div>

          {/* Main Headline (Multi-City Scalable) */}
          <motion.h1
            initial={{ opacity: 0, y: 18 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5, delay: 0.12, ease: [0.16, 1, 0.3, 1] }}
            className="text-3xl sm:text-5xl lg:text-6xl font-black font-['Outfit',sans-serif] text-white tracking-tight leading-[1.1] max-w-4xl"
          >
            Find Your Next Home in <span className="text-emerald-400">{selectedCity.name}</span> & Metros with <span className="bg-gradient-to-r from-emerald-400 via-teal-300 to-emerald-400 bg-clip-text text-transparent animate-pulse">Zero Brokerage</span>
          </motion.h1>

          <motion.p
            initial={{ opacity: 0, y: 14 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5, delay: 0.18, ease: [0.16, 1, 0.3, 1] }}
            className="text-slate-300 text-sm sm:text-base max-w-2xl font-normal leading-relaxed"
          >
            <span className="text-emerald-400 font-bold">Pathome</span> — <span className="text-white font-semibold">Your Dreams, Our Efforts</span>. First 5 guided property tours are 100% free with on-site escorts. Active in {selectedCity.name} and expanding nationwide across top tech hubs.
          </motion.p>

        </div>

        {/* BANNER CAROUSEL THUMBNAIL INDICATORS */}
        <div className="max-w-7xl mx-auto w-full relative z-20 flex justify-center sm:justify-end items-center gap-2.5 mt-4">
          {HERO_BANNERS.map((b, idx) => (
            <motion.button
              key={b.id}
              whileHover={{ scale: 1.25 }}
              whileTap={{ scale: 0.9 }}
              onClick={() => setActiveBannerIdx(idx)}
              className={`h-2.5 rounded-full transition-all duration-300 ${
                idx === activeBannerIdx ? 'w-9 bg-emerald-400 shadow-lg shadow-emerald-400/60 ring-2 ring-emerald-400/40' : 'w-2.5 bg-white/40 hover:bg-white/70'
              }`}
              title={b.title}
            />
          ))}
        </div>

      </section>


      {/* ========================================================================= */}
      {/* 2. THE INDORE DISCOVERY DECK (3D PARALLAX TILT FLOATING GLASS CONSOLE) */}
      {/* ========================================================================= */}
      <section className="-mt-20 sm:-mt-24 relative z-30 max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 pb-16 perspective-1000">
        
        <motion.div
          style={{ rotateX, rotateY }}
          onMouseMove={handleMouseMove}
          onMouseLeave={handleMouseLeave}
          initial={{ opacity: 0, y: 36, scale: 0.96 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={{ type: 'spring', stiffness: 300, damping: 24, delay: 0.22 }}
          className="bg-white/95 backdrop-blur-2xl rounded-3xl p-5 sm:p-7 shadow-2xl shadow-slate-950/50 border border-white/80 space-y-5 relative text-slate-900 transform-gpu hover:shadow-emerald-950/30 transition-shadow duration-500"
        >
          
          {/* TAB CATEGORY ROW */}
          <div className="flex items-center justify-between border-b border-slate-200/80 pb-4 overflow-x-auto no-scrollbar gap-2">
            
            <div className="flex items-center gap-1 sm:gap-2">
              {[
                { id: 'RENT', label: 'Rentals (Zero Brokerage)', icon: Home, badge: 'Popular' },
                { id: 'PLOT', label: 'Buy Plots & Land', icon: Map, badge: 'Phase 2' },
                { id: 'COMMERCIAL', label: 'Commercial Offices', icon: Building2 },
                { id: 'PG', label: 'Student PGs & Hostels', icon: ShieldCheck }
              ].map((tab) => {
                const isActive = activeTab === tab.id;
                const Icon = tab.icon;
                return (
                  <motion.button
                    key={tab.id}
                    whileHover={{ scale: 1.04, y: -1 }}
                    whileTap={{ scale: 0.96 }}
                    transition={{ type: 'spring', stiffness: 500, damping: 25 }}
                    onClick={() => setActiveTab(tab.id as any)}
                    className={`relative px-4 py-2.5 rounded-2xl text-xs font-extrabold flex items-center gap-2 transition-colors duration-200 shrink-0 z-10 ${
                      isActive ? 'text-slate-900' : 'text-slate-500 hover:text-slate-800'
                    }`}
                  >
                    {isActive && (
                      <motion.div
                        layoutId="heroCategoryTabIndicator"
                        className="absolute inset-0 bg-slate-100 rounded-2xl border border-slate-200/90 -z-10 shadow-xs"
                        transition={{ type: 'spring', stiffness: 500, damping: 32 }}
                      />
                    )}
                    <Icon className={`w-4 h-4 ${isActive ? 'text-emerald-600' : 'text-slate-400'}`} />
                    <span>{tab.label}</span>
                    {tab.badge && (
                      <span className={`text-[9px] font-black uppercase px-2 py-0.5 rounded-full font-mono ${
                        tab.id === 'RENT' ? 'bg-emerald-100 text-emerald-800 border border-emerald-200' : 'bg-amber-100 text-amber-800 border border-amber-200'
                      }`}>
                        {tab.badge}
                      </span>
                    )}
                  </motion.button>
                );
              })}
            </div>

            <div className="hidden lg:flex items-center gap-2 text-xs text-slate-500 font-semibold shrink-0">
              <span className="flex h-2 w-2 rounded-full bg-emerald-500 animate-pulse"></span>
              <span>{selectedCity.name} Hub Online</span>
            </div>

          </div>

          {/* DYNAMIC ANIMATE PRESENCE PER TAB MODE */}
          <AnimatePresence mode="wait">
            <motion.div
              key={activeTab}
              initial={{ opacity: 0, y: 12, rotateX: 6 }}
              animate={{ opacity: 1, y: 0, rotateX: 0 }}
              exit={{ opacity: 0, y: -12, rotateX: -6 }}
              transition={{ duration: 0.35, ease: [0.16, 1, 0.3, 1] }}
              className="space-y-4"
            >
              
              {/* SEARCH INPUT WITH VOICE / AI SMART PROMPT & TRAVELING SPARK BORDER ANIMATION */}
              <div className="spark-search-container group shadow-2xl">
                {/* Rotating Luminous Border Spark (Loops every 1.8 seconds) */}
                <div className="spark-border-beam" />

                <div className="spark-search-inner flex items-center p-2 sm:p-2.5">
                  <div className="flex items-center gap-2 px-3 text-slate-400 border-r border-slate-200/90 shrink-0 relative z-10">
                    <div className="relative flex items-center justify-center p-1.5 rounded-xl bg-emerald-50 text-emerald-600 border border-emerald-200/80 shadow-xs">
                      <Search className="w-4 h-4 text-emerald-600" />
                      <Sparkles className="w-3.5 h-3.5 text-cyan-500 absolute -top-2 -right-2 animate-spin pointer-events-none drop-shadow-xs" style={{ animationDuration: '3s' }} />
                    </div>
                    <span className="text-xs font-black text-slate-900 hidden sm:inline ml-1 font-['Outfit']">Search {selectedCity.name}</span>
                  </div>

                  <input
                    type="text"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    placeholder={`Type sector, colony, or landmark in ${selectedCity.name} (e.g. '${selectedCity.sectors[0]} Scheme 54')...`}
                    className="w-full bg-transparent px-4 py-2.5 text-xs font-extrabold text-slate-900 focus:outline-none placeholder:text-slate-400 relative z-10"
                  />

                  <div className="flex items-center gap-2 px-2 shrink-0 relative z-10">
                    <motion.button
                      whileHover={{ scale: 1.08 }}
                      whileTap={{ scale: 0.94 }}
                      onClick={() => setSearchQuery(`3BHK Flat ${selectedCity.sectors[0]}`)}
                      className="hidden md:flex items-center gap-1.5 bg-gradient-to-r from-emerald-500/10 via-teal-500/15 to-cyan-500/10 text-emerald-800 border border-emerald-300 px-3 py-1.5 rounded-xl text-[11px] font-extrabold font-mono hover:bg-emerald-100 transition-colors shadow-2xs"
                      title="Auto-fill AI Search Prompt"
                    >
                      <Sparkles className="w-3 h-3 text-emerald-600 animate-pulse" />
                      <span>Smart Prompt</span>
                    </motion.button>

                    <motion.button
                      whileHover={{ scale: 1.1, rotate: 8 }}
                      whileTap={{ scale: 0.9 }}
                      onClick={() => setSearchQuery(`3BHK Flat ${selectedCity.sectors[0]}`)}
                      className="p-2.5 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white shadow-md shadow-emerald-600/30 transition-colors"
                      title="Voice Search AI Prompt"
                    >
                      <Mic className="w-4 h-4 text-white animate-pulse" />
                    </motion.button>
                  </div>
                </div>
              </div>

              {/* SECTOR MULTI-SELECT PILLS ROW (DYNAMICALLY POPULATED BY CITY) */}
              <div>
                <div className="flex items-center justify-between mb-2.5">
                  <span className="text-[11px] font-black text-slate-800 uppercase tracking-wider flex items-center gap-1.5 font-mono">
                    <MapPin className="w-3.5 h-3.5 text-emerald-600 animate-bounce" /> {selectedCity.name} Micro-Markets:
                  </span>
                  <span className="text-[10px] text-emerald-700 font-bold bg-emerald-50 px-2.5 py-0.5 rounded-full border border-emerald-200">
                    Multi-Select Active ({selectedSectors.length})
                  </span>
                </div>

                <LayoutGroup>
                  <div className="flex flex-wrap gap-2">
                    {selectedCity.sectors.map((sector) => {
                      const isSelected = selectedSectors.includes(sector);
                      return (
                        <motion.button
                          key={sector}
                          layout
                          whileHover={{ scale: 1.08, y: -2 }}
                          whileTap={{ scale: 0.92 }}
                          transition={{ type: 'spring', stiffness: 600, damping: 20 }}
                          type="button"
                          onClick={() => toggleSector(sector)}
                          className={`text-xs font-extrabold px-3.5 py-1.5 rounded-xl border flex items-center gap-1.5 transition-all duration-200 ${
                            isSelected
                              ? 'bg-slate-900 text-white border-slate-900 shadow-lg shadow-slate-900/20 ring-2 ring-emerald-500/50'
                              : 'bg-slate-50 text-slate-700 border-slate-200 hover:bg-slate-100 hover:border-slate-300'
                          }`}
                        >
                          <span>{sector}</span>
                          {isSelected && (
                            <motion.span
                              initial={{ scale: 0, rotate: -45 }}
                              animate={{ scale: 1, rotate: 0 }}
                              transition={{ type: 'spring', stiffness: 700, damping: 18 }}
                            >
                              <Check className="w-3.5 h-3.5 text-emerald-400 font-black" />
                            </motion.span>
                          )}
                        </motion.button>
                      );
                    })}
                  </div>
                </LayoutGroup>
              </div>

              {/* CONTROLS GRID */}
              <div className="grid grid-cols-1 md:grid-cols-4 gap-4 pt-4 border-t border-slate-100 items-end">
                
                {/* 1. Property Type Dropdown */}
                <div>
                  <label className="text-[10px] font-black text-slate-700 block mb-1.5 uppercase tracking-wider font-mono">
                    Property Configuration
                  </label>
                  <select
                    value={propertyType}
                    onChange={(e) => setPropertyType(e.target.value)}
                    className="w-full bg-slate-50 border border-slate-200 rounded-xl px-3.5 py-2.5 text-xs font-extrabold text-slate-900 focus:outline-none focus:border-emerald-600 cursor-pointer shadow-xs"
                  >
                    <option value="ALL">All Gated & Independent</option>
                    <option value="2BHK">2 BHK Flat / House</option>
                    <option value="3BHK">3 BHK Luxury Flat</option>
                    <option value="PLOT">Phase 2 Plot / Land</option>
                  </select>
                </div>

                {/* 2. Budget Range Slider */}
                <div>
                  <div className="flex justify-between items-center mb-1.5">
                    <label className="text-[10px] font-black text-slate-700 uppercase tracking-wider font-mono">
                      Max Budget
                    </label>
                    <span className="text-xs font-extrabold text-emerald-700 font-mono bg-emerald-50 px-2 py-0.5 rounded border border-emerald-200">
                      ₹{maxBudget.toLocaleString('en-IN')}/mo
                    </span>
                  </div>
                  <input
                    type="range"
                    min="5000"
                    max="50000"
                    step="1000"
                    value={maxBudget}
                    onChange={(e) => setMaxBudget(Number(e.target.value))}
                    className="w-full accent-emerald-600 cursor-pointer h-2 bg-slate-200 rounded-lg appearance-none"
                  />
                </div>

                {/* 3. 100% Escort Verified Toggle Switch */}
                <div>
                  <label className="text-[10px] font-black text-slate-700 block mb-1.5 uppercase tracking-wider font-mono">
                    Verification Filter
                  </label>
                  <motion.button
                    whileHover={{ scale: 1.04 }}
                    whileTap={{ scale: 0.94 }}
                    transition={{ type: 'spring', stiffness: 500, damping: 20 }}
                    type="button"
                    onClick={() => setVerifiedOnly(!verifiedOnly)}
                    className={`w-full py-2.5 px-3.5 rounded-xl border text-xs font-extrabold flex items-center justify-between transition-all ${
                      verifiedOnly
                        ? 'bg-emerald-50 border-emerald-300 text-emerald-900 shadow-md shadow-emerald-500/10'
                        : 'bg-slate-50 border-slate-200 text-slate-700 hover:bg-slate-100'
                    }`}
                  >
                    <span className="flex items-center gap-1.5">
                      <ShieldCheck className={`w-3.5 h-3.5 ${verifiedOnly ? 'text-emerald-600' : 'text-slate-400'}`} />
                      100% Escort Verified
                    </span>
                    <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full ${verifiedOnly ? 'bg-emerald-200 text-emerald-900' : 'bg-slate-200 text-slate-600'}`}>
                      {verifiedOnly ? 'ON' : 'OFF'}
                    </span>
                  </motion.button>
                </div>

                {/* 4. HIGH-CONTRAST SEARCH CTA */}
                <div>
                  <motion.button
                    whileHover={{ 
                      scale: 1.05, 
                      y: -3, 
                      boxShadow: '0 20px 30px -10px rgba(5, 150, 105, 0.4)' 
                    }}
                    whileTap={{ scale: 0.92 }}
                    transition={{ type: 'spring', stiffness: 500, damping: 20 }}
                    onClick={() => onSearch(selectedSectors[0] || 'Main Sector')}
                    className="w-full bg-emerald-600 hover:bg-emerald-700 text-white font-extrabold text-xs py-3.5 px-5 rounded-2xl shadow-xl shadow-emerald-600/30 flex items-center justify-center gap-2 uppercase tracking-wider shimmer-glow relative overflow-hidden z-10"
                  >
                    <Search className="w-4 h-4 text-white shrink-0 relative z-10" />
                    <span className="text-white font-extrabold tracking-wider relative z-10 drop-shadow-sm">Find Homes Now</span>
                  </motion.button>
                </div>

              </div>

            </motion.div>
          </AnimatePresence>

        </motion.div>

      </section>

    </div>
  );
};
