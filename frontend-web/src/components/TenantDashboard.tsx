import React, { useState, useEffect } from 'react';
import {
  useSearchParams } from 'react-router-dom';
import {motion,
  AnimatePresence,
  useScroll,
  useSpring
} from 'framer-motion';
import { UserProfile,
  Property,
  BhkConfig } from '../types';
import { PropertyCardMedia } from './PropertyCardMedia';
import { VideoPlayerModal } from './VideoPlayerModal';
import {
  Sparkles,
  Gift,
  MapPin,
  Key,
  ShieldCheck,
  Phone,
  UserCheck,
  CheckCircle2,
  Clock,
  Navigation,
  UploadCloud,
  Heart,
  Check,
  Globe,
  ChevronDown,
  Search,
  Mic,
  SlidersHorizontal,
  Filter,
  RotateCcw
} from 'lucide-react';
import { propertyService } from '../services/propertyService';
import { useNotification } from '../context/NotificationContext';

interface TenantDashboardProps {
  user: UserProfile;
  properties: Property[];
  bhkConfigs?: BhkConfig[];
  onBookTour: (property: Property) => void;
  onOpenLeaseUpload: () => void;
}

interface CityOption {
  id: string;
  name: string;
  state: string;
  status: 'ACTIVE' | 'LAUNCHING_SOON';
  sectors: string[];
}

const TENANT_CITIES: CityOption[] = [
  {
    id: 'INDORE',
    name: 'Indore',
    state: 'Madhya Pradesh',
    status: 'ACTIVE',
    sectors: ['Vijay Nagar', 'Bhawarkua', 'Nipania', 'LIG Circle', 'AB Road', 'Old Palasia', 'Super Corridor']
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

const containerVariants = {
  hidden: { opacity: 0 },
  visible: {
    opacity: 1,
    transition: { staggerChildren: 0.08, delayChildren: 0.04 }
  }
};

const itemVariants = {
  hidden: { opacity: 0, y: 16, scale: 0.98 },
  visible: { 
    opacity: 1, 
    y: 0, 
    scale: 1,
    transition: { duration: 0.4, ease: [0.16, 1, 0.3, 1] as const } 
  }
};

export const TenantDashboard: React.FC<TenantDashboardProps> = ({
  user,
  properties,
  bhkConfigs,
  onBookTour,
  onOpenLeaseUpload
}) => {
  const { notifySuccess } = useNotification();
  const passesRemaining = Math.max(0, 5 - user.freeVisitsUsed);
  const isPaywallActive = user.freeVisitsUsed >= 5;
  const [modalConfig, setModalConfig] = useState<{ property: Property; initialMode?: 'VIDEO' | 'PHOTOS' } | null>(null);
  
  const [searchParams, setSearchParams] = useSearchParams();

  // Helper to retrieve filter state from URL search params
  const getInitialFilter = <T,>(key: string, defaultValue: T): T => {
    const paramVal = searchParams.get(key);
    if (paramVal !== null) {
      if (typeof defaultValue === 'number') return Number(paramVal) as unknown as T;
      if (typeof defaultValue === 'boolean') return (paramVal === 'true') as unknown as T;
      if (Array.isArray(defaultValue)) return paramVal.split(',') as unknown as T;
      return paramVal as unknown as T;
    }
    return defaultValue;
  };

  const [selectedCityId, setSelectedCityId] = useState<string>(() => getInitialFilter('city', 'INDORE'));
  const [selectedSectors, setSelectedSectors] = useState<string[]>(() => getInitialFilter('sectors', []));
  const [showCityPicker, setShowCityPicker] = useState<boolean>(false);
  const [activeCategory, setActiveCategory] = useState<string>(() => getInitialFilter('cat', 'ALL'));
  const [dragOver, setDragOver] = useState(false);
  const [uploadedFile, setUploadedFile] = useState<string | null>(null);
  const [savedPropertyIds, setSavedPropertyIds] = useState<number[]>([1]);
  const [copiedOtp, setCopiedOtp] = useState<boolean>(false);
  const [liveBackendProperties, setLiveBackendProperties] = useState<Property[]>([]);

  // TENANT SEARCH CONTROLS & BHK SELECTION WITH PERSISTENCE
  const [searchQuery, setSearchQuery] = useState<string>(() => getInitialFilter('q', ''));
  const [selectedBhk, setSelectedBhk] = useState<string>(() => getInitialFilter('bhk', 'ALL'));
  const [maxBudget, setMaxBudget] = useState<number>(() => getInitialFilter('budget', 50000));
  const [verifiedOnly, setVerifiedOnly] = useState<boolean>(() => getInitialFilter('verified', true));
  const [showSavedOnly, setShowSavedOnly] = useState<boolean>(() => getInitialFilter('saved', false));
  const [showMobileLiveDetails, setShowMobileLiveDetails] = useState<boolean>(false);

  // FETCH REAL-TIME PROPERTIES FROM SPRING BOOT BACKEND REST API
  useEffect(() => {
    let isMounted = true;
    propertyService.fetchProperties(selectedSectors[0], selectedCityId)
      .then(fetched => {
        if (isMounted && fetched.length > 0) {
          setLiveBackendProperties(fetched);
        }
      })
      .catch(err => console.warn("Live API fetch fallback active:", err));
    return () => { isMounted = false; };
  }, [selectedCityId, selectedSectors]);

  // HIGH-TECH SCROLL PROGRESS TRACKER
  const { scrollYProgress } = useScroll();
  const scaleX = useSpring(scrollYProgress, {
    stiffness: 120,
    damping: 30,
    restDelta: 0.001
  });

  // AUTO-SYNC STATE TO URL SEARCH PARAMS
  useEffect(() => {
    const params = new URLSearchParams();
    if (selectedCityId !== 'INDORE') params.set('city', selectedCityId);
    if (selectedSectors.length > 0) params.set('sectors', selectedSectors.join(','));
    if (selectedBhk !== 'ALL') params.set('bhk', selectedBhk);
    if (searchQuery.trim()) params.set('q', searchQuery);
    if (maxBudget < 50000) params.set('budget', String(maxBudget));
    if (!verifiedOnly) params.set('verified', 'false');
    if (activeCategory !== 'ALL') params.set('cat', activeCategory);
    if (showSavedOnly) params.set('saved', 'true');

    setSearchParams(params, { replace: true });
  }, [selectedCityId, selectedSectors, searchQuery, selectedBhk, maxBudget, verifiedOnly, activeCategory, showSavedOnly, setSearchParams]);

  // CLEAR ALL FILTERS STATUS & HANDLER (CLEARS ALL CONSTRAINTS - NOTHING SELECTED)
  const isAnyFilterActive = 
    selectedCityId !== 'INDORE' ||
    selectedSectors.length > 0 ||
    selectedBhk !== 'ALL' ||
    searchQuery.trim() !== '' ||
    maxBudget < 50000 ||
    !verifiedOnly ||
    activeCategory !== 'ALL' ||
    showSavedOnly;

  const handleClearAllFilters = () => {
    setSelectedCityId('INDORE');
    setSelectedSectors([]); // NOTHING SELECTED! SHOW ALL SECTORS!
    setSelectedBhk('ALL');
    setSearchQuery('');
    setMaxBudget(50000); // MAX BUDGET RANGE!
    setVerifiedOnly(true); // DEFAULT ON!
    setActiveCategory('ALL');
    setShowSavedOnly(false);
    setSearchParams({}, { replace: true });
  };

  const selectedCity = TENANT_CITIES.find(c => c.id === selectedCityId) || TENANT_CITIES[0];

  const handleCopyOtp = () => {
    navigator.clipboard.writeText('849201');
    setCopiedOtp(true);
    setTimeout(() => setCopiedOtp(false), 2500);
  };

  const handleSelectCity = (city: CityOption) => {
    setSelectedCityId(city.id);
    setSelectedSectors([city.sectors[0] || 'Main Sector']);
    setShowCityPicker(false);
    if (city.status === 'LAUNCHING_SOON') {
      notifySuccess('Early access requested', `We will let you know when Pathome becomes available in ${city.name}.`);
    }
  };

  const toggleSector = (sector: string) => {
    if (selectedSectors.includes(sector)) {
      setSelectedSectors(selectedSectors.filter(s => s !== sector));
    } else {
      setSelectedSectors([...selectedSectors, sector]);
    }
  };

  const toggleSaveProperty = (id: number, e: React.MouseEvent) => {
    e.stopPropagation();
    if (savedPropertyIds.includes(id)) {
      setSavedPropertyIds(savedPropertyIds.filter(i => i !== id));
    } else {
      setSavedPropertyIds([...savedPropertyIds, id]);
    }
  };

  const handleDropLease = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    setUploadedFile("Indore_Rent_Agreement_VijayNagar.pdf");
    notifySuccess('Agreement uploaded', 'Your cashback claim has been sent for review.');
  };

  // REAL-TIME METRO & SECTOR & BHK FILTERED PROPERTIES
  const filteredProperties = properties.filter(prop => {
    // 1. Saved favorites filter
    if (showSavedOnly && !savedPropertyIds.includes(prop.id)) return false;

    // 2. Escort Verification filter
    if (verifiedOnly && !prop.verified) return false;

    // 3. Rent budget filter (only applied if listing is RENT and monthlyRent > 0)
    if (maxBudget && prop.listingType === 'RENT' && prop.monthlyRent > 0 && prop.monthlyRent > maxBudget) return false;

    // 4. Property Type Category filter (FLAT, HOUSE, PLOT)
    if (activeCategory !== 'ALL' && prop.propertyType !== activeCategory) return false;

    // 5. BHK CONFIGURATION FILTER (1RK, 1BHK, 2BHK, 3BHK, 4BHK)
    if (selectedBhk !== 'ALL') {
      const targetClean = selectedBhk.replace(/[\s\+]/g, '').toUpperCase();
      const targetDigit = targetClean.match(/\d+/)?.[0];
      const isRk = targetClean.includes('RK');

      let matches = false;

      if (prop.bhk) {
        const propBhkClean = prop.bhk.replace(/[\s\+]/g, '').toUpperCase();
        if (propBhkClean === targetClean) {
          matches = true;
        } else if (targetDigit && propBhkClean.includes(targetDigit)) {
          matches = true;
        }
      }

      if (!matches && prop.title) {
        const titleClean = prop.title.replace(/\s+/g, '').toUpperCase();
        if (titleClean.includes(targetClean)) {
          matches = true;
        } else if (isRk && (titleClean.includes('1RK') || titleClean.includes('STUDIO'))) {
          matches = true;
        } else if (targetDigit && (titleClean.includes(`${targetDigit}BHK`) || titleClean.includes(`${targetDigit}BEDROOM`))) {
          matches = true;
        }
      }

      if (!matches) return false;
    }

    // 6. TARGET METRO CITY FILTERING
    if (selectedCityId === 'INDORE') {
      const propCityClean = (prop.city || '').trim().toUpperCase();
      const isIndoreCity = propCityClean === '' || propCityClean === 'INDORE' || propCityClean === 'VIJAY';
      const isIndoreSector = selectedCity.sectors.some(
        s => s.toLowerCase() === prop.sector.toLowerCase() || prop.sector.toLowerCase().includes(s.toLowerCase())
      );
      if (!isIndoreCity && !isIndoreSector) return false;
    } else {
      if (prop.city && prop.city.toUpperCase() !== selectedCity.name.toUpperCase()) return false;
    }

    // 7. MICRO-MARKET SECTOR FILTERING (if specific sectors are toggled)
    if (selectedSectors.length > 0 && selectedCityId === 'INDORE') {
      const matchSelectedSector = selectedSectors.some(
        sec => prop.sector.toLowerCase().includes(sec.toLowerCase())
      );
      if (!matchSelectedSector) return false;
    }

    // 8. Text Search Query matching
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase();
      const matchTitle = prop.title.toLowerCase().includes(q);
      const matchSector = prop.sector.toLowerCase().includes(q);
      if (!matchTitle && !matchSector) return false;
    }

    return true;
  });

  return (
    <>
      {/* HIGH-TECH GLOWING TOP SCROLL PROGRESS BAR */}
      <motion.div 
        className="fixed top-0 left-0 right-0 h-1.5 bg-gradient-to-r from-emerald-500 via-teal-400 to-emerald-400 z-50 origin-left shadow-[0_0_16px_rgba(16,185,129,0.9)] pointer-events-none"
        style={{ scaleX }}
      />

      <motion.div
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        exit={{ opacity: 0, y: -16 }}
        transition={{ duration: 0.45, ease: [0.16, 1, 0.3, 1] }}
        className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6 font-['Inter',sans-serif]"
      >
      
      {/* ========================================================================= */}
      {/* 1. TOP AIRY GREETING & VIP PASS METRICS BANNER */}
      {/* ========================================================================= */}
      <motion.div 
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4 }}
        className="bg-white rounded-3xl p-4 sm:p-8 border border-slate-200/90 shadow-xl shadow-slate-900/5 relative overflow-hidden"
      >
        <div className="absolute top-0 right-0 w-96 h-96 bg-gradient-to-br from-emerald-500/10 via-teal-500/5 to-transparent blur-3xl rounded-full pointer-events-none" />

        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4 sm:gap-6 relative z-10">
          <div className="space-y-1.5">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-[10px] sm:text-[11px] font-extrabold text-emerald-800 uppercase tracking-wider bg-emerald-50 px-2.5 sm:px-3 py-1 rounded-full border border-emerald-200 font-mono flex items-center gap-1.5">
                <ShieldCheck className="w-3.5 h-3.5 text-emerald-600 animate-spin" style={{ animationDuration: '8s' }} />
                Verified Tenant VIP Pass
              </span>
              <span className="text-[11px] sm:text-xs text-slate-400 font-mono font-medium">{selectedCity.name} Region HQ</span>
            </div>

            <h1 className="text-xl xs:text-2xl sm:text-3xl lg:text-4xl font-black font-['Outfit',sans-serif] text-slate-900 tracking-tight">
              Welcome back, <span className="bg-gradient-to-r from-emerald-600 via-teal-600 to-emerald-600 bg-clip-text text-transparent">{user.fullName || 'Tenant Resident'}</span> 👋
            </h1>

            <p className="text-xs sm:text-sm text-slate-600 max-w-2xl leading-relaxed">
              Explore verified zero-brokerage homes in {selectedCity.name}, manage active Ground Boy escort tours, and claim your ₹1,000 lease agreement cashback.
            </p>
          </div>

          <div className="flex items-center gap-3 shrink-0">
            <motion.button
              whileHover={{ scale: 1.04, y: -2 }}
              whileTap={{ scale: 0.95 }}
              onClick={onOpenLeaseUpload}
              className="w-full sm:w-auto bg-emerald-600 hover:bg-emerald-700 text-white font-extrabold text-xs px-5 py-3.5 rounded-2xl shadow-lg shadow-emerald-600/25 flex items-center justify-center gap-2 uppercase tracking-wider shimmer-glow"
            >
              <Gift className="w-4 h-4 text-white" />
              <span className="text-white font-extrabold">Claim ₹1,000 Cash-Back</span>
            </motion.button>
          </div>
        </div>

        {/* 4 PRISTINE METRIC CHIPS - DESKTOP ONLY (CONSOLIDATED INTO SPACE-SAVING DROPDOWN ON MOBILE) */}
        <div className="hidden lg:grid lg:grid-cols-4 gap-3.5 mt-6 pt-6 border-t border-slate-100 relative z-10">
          <div className="bg-slate-50/90 p-4 rounded-2xl border border-slate-200/80 hover:border-emerald-300 transition-colors">
            <div className="flex items-center justify-between mb-1">
              <span className="text-[10px] font-black text-slate-400 uppercase tracking-wider font-mono">
                Tour Passes Left
              </span>
              <Sparkles className="w-3.5 h-3.5 text-emerald-600 shrink-0" />
            </div>
            <div className="flex items-baseline justify-between gap-1">
              <span className="text-2xl font-black text-emerald-700 font-mono">
                {passesRemaining} <span className="text-xs text-slate-400 font-normal">/ 5</span>
              </span>
              <span className="text-[9px] font-bold text-emerald-800 bg-emerald-100 px-2 py-0.5 rounded-full font-mono shrink-0">
                100% Free
              </span>
            </div>
          </div>

          <div className="bg-slate-50/90 p-4 rounded-2xl border border-slate-200/80 hover:border-emerald-300 transition-colors">
            <div className="flex items-center justify-between mb-1">
              <span className="text-[10px] font-black text-slate-400 uppercase tracking-wider font-mono">
                Active Escort Tour
              </span>
              <Clock className="w-3.5 h-3.5 text-emerald-600 shrink-0" />
            </div>
            <div className="flex items-baseline justify-between gap-1">
              <span className="text-xs font-bold text-slate-900 font-['Outfit'] truncate">
                {selectedSectors[0] || 'Selected Sector'} 3BHK
              </span>
              <span className="text-[9px] font-bold text-emerald-800 bg-emerald-100 px-2 py-0.5 rounded font-mono shrink-0">
                Today 4 PM
              </span>
            </div>
          </div>

          <div className="bg-slate-50/90 p-4 rounded-2xl border border-slate-200/80 hover:border-emerald-300 transition-colors">
            <div className="flex items-center justify-between mb-1">
              <span className="text-[10px] font-black text-slate-400 uppercase tracking-wider font-mono">
                Ground Boy Escort
              </span>
              <UserCheck className="w-3.5 h-3.5 text-emerald-600 shrink-0" />
            </div>
            <div className="flex items-baseline justify-between gap-1">
              <span className="text-xs font-bold text-slate-900 truncate">
                Rahul Verma
              </span>
              <span className="text-[9px] font-mono font-bold text-emerald-700 bg-emerald-100 px-2 py-0.5 rounded shrink-0">
                Active
              </span>
            </div>
          </div>

          <div className="bg-slate-50/90 p-4 rounded-2xl border border-slate-200/80 hover:border-amber-300 transition-colors">
            <div className="flex items-center justify-between mb-1">
              <span className="text-[10px] font-black text-slate-400 uppercase tracking-wider font-mono">
                Lease Reward
              </span>
              <Gift className="w-3.5 h-3.5 text-amber-600 shrink-0" />
            </div>
            <div className="flex items-baseline justify-between gap-1">
              <span className="text-lg font-black text-amber-900 font-mono">
                ₹1,000 UPI
              </span>
              <span className="text-[9px] font-bold text-amber-800 bg-amber-100 px-2 py-0.5 rounded font-mono shrink-0">
                Ready Upload
              </span>
            </div>
          </div>
        </div>
      </motion.div>

      {/* ========================================================================= */}
      {/* 2. MASTER 2-COLUMN DASHBOARD ARCHITECTURE */}
      {/* ========================================================================= */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        
        {/* ----------------------------------------------------------------------- */}
        {/* MAIN COLUMN (8 COLS): MAIN SEARCH CONSOLE & HOMES GRID WORKSPACE */}
        {/* FIRST ON MOBILE (order-1 lg:order-2) FOR IMMEDIATE ACCESSIBILITY */}
        {/* ----------------------------------------------------------------------- */}
        <div className="lg:col-span-8 space-y-6 order-1 lg:order-2">
          
          {/* MOBILE-FIRST ULTRA-COMPACT LIVE STATUS & METRICS DROPDOWN HUB */}
          <div className="block lg:hidden bg-gradient-to-br from-slate-950 via-slate-900 to-emerald-950 text-white rounded-3xl p-3.5 shadow-2xl border border-emerald-500/30 space-y-3">
            
            {/* COLLAPSED STATUS DOCK BAR */}
            <div 
              onClick={() => setShowMobileLiveDetails(!showMobileLiveDetails)}
              className="flex items-center justify-between gap-2 cursor-pointer select-none"
            >
              
              {/* LEFT LIVE BADGE & ESCORT EN ROUTE STATUS */}
              <div className="flex items-center gap-2.5 truncate">
                <span className="relative flex h-3 w-3 shrink-0">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
                  <span className="relative inline-flex rounded-full h-3 w-3 bg-emerald-500"></span>
                </span>
                <div className="truncate">
                  <div className="flex items-center gap-1.5 text-xs font-black text-emerald-400 font-['Outfit'] truncate">
                    <span>Rahul Verma En Route</span>
                    <span className="text-[10px] text-emerald-300/80 font-mono font-normal bg-emerald-950/80 px-1.5 py-0.5 rounded border border-emerald-800/60">(~4 Mins)</span>
                  </div>
                  <div className="text-[10px] text-slate-400 font-mono truncate flex items-center gap-1 mt-0.5">
                    <MapPin className="w-3 h-3 text-emerald-500 shrink-0" />
                    <span>{selectedSectors[0] || 'Selected Sector'} 3BHK • Today 4 PM</span>
                  </div>
                </div>
              </div>

              {/* RIGHT QUICK ACTIONS & DROPDOWN TOGGLE */}
              <div className="flex items-center gap-1.5 shrink-0">
                <button
                  type="button"
                  onClick={(e) => { e.stopPropagation(); handleCopyOtp(); }}
                  className="bg-emerald-950 text-emerald-300 border border-emerald-700/80 px-2.5 py-1 rounded-xl text-[11px] font-mono font-black flex items-center gap-1 hover:bg-emerald-900 transition-colors shadow-xs"
                  title="Tap code to copy OTP"
                >
                  <Key className="w-3 h-3 text-emerald-400" />
                  <span>{copiedOtp ? 'Copied ✓' : '849201'}</span>
                </button>

                <a
                  href="tel:+919876543210"
                  onClick={(e) => e.stopPropagation()}
                  className="bg-emerald-600 hover:bg-emerald-500 text-white p-1.5 rounded-xl flex items-center justify-center shadow-xs transition-transform active:scale-95"
                  title="Call Escort Rahul"
                >
                  <Phone className="w-3.5 h-3.5 text-white" />
                </a>

                <div 
                  className="bg-slate-800/90 text-slate-300 px-2 py-1 rounded-xl text-[10px] font-mono font-bold border border-slate-700 flex items-center gap-1"
                >
                  <ChevronDown className={`w-3.5 h-3.5 transition-transform duration-300 ${showMobileLiveDetails ? 'rotate-180 text-emerald-400' : ''}`} />
                </div>
              </div>

            </div>

            {/* EXPANDABLE ACCORDION DROPDOWN ON MOBILE */}
            <AnimatePresence>
              {showMobileLiveDetails && (
                <motion.div
                  initial={{ opacity: 0, height: 0 }}
                  animate={{ opacity: 1, height: 'auto' }}
                  exit={{ opacity: 0, height: 0 }}
                  transition={{ duration: 0.3, ease: 'easeInOut' }}
                  className="overflow-hidden pt-3 border-t border-slate-800/90 space-y-3"
                >
                  {/* SEAMLESS DARK GLASS LIVE ESCORT TRACKER CARD */}
                  <div className="bg-slate-900/90 border border-slate-800 p-3.5 rounded-2xl space-y-3">
                    <div className="flex items-center justify-between text-[10px] font-black font-mono uppercase text-slate-400 pb-2 border-b border-slate-800">
                      <span className="flex items-center gap-1.5 text-emerald-400">
                        <ShieldCheck className="w-3.5 h-3.5 text-emerald-400" /> On-Site Escort Pass #1
                      </span>
                      <span className="text-emerald-300 bg-emerald-950/90 border border-emerald-800/80 px-2 py-0.5 rounded font-mono font-bold">
                        Scheduled Today 4:00 PM
                      </span>
                    </div>

                    {/* CLEAR & DISTINCT 3-STEP TIMELINE WITH HIGHLIGHTED ACTIVE STAGE */}
                    <div className="space-y-2 text-xs">
                      
                      {/* STEP 1: COMPLETED (MUTED & CHECKED) */}
                      <div className="flex justify-between items-center bg-slate-950/60 p-2.5 rounded-xl border border-slate-800/80 opacity-90">
                        <span className="font-bold text-slate-300 flex items-center gap-2">
                          <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />
                          <span>Escort Assigned</span>
                        </span>
                        <span className="text-[10px] font-extrabold text-slate-300 bg-slate-800 px-2 py-0.5 rounded border border-slate-700 font-mono">
                          Rahul Verma (+91 98765 43210)
                        </span>
                      </div>

                      {/* STEP 2: ACTIVE NOW (HIGHLIGHTED HIGH-CONTRAST EMERALD CARD) */}
                      <div className="flex justify-between items-center bg-gradient-to-r from-emerald-950/90 via-teal-950/90 to-emerald-950/90 p-2.5 rounded-xl border-2 border-emerald-400 shadow-md shadow-emerald-950">
                        <span className="font-black text-white flex items-center gap-2">
                          <Clock className="w-4 h-4 text-emerald-300 animate-spin shrink-0" style={{ animationDuration: '6s' }} />
                          <span className="bg-gradient-to-r from-emerald-300 to-teal-200 bg-clip-text text-transparent">En Route to Site</span>
                        </span>
                        <span className="text-[10px] font-black text-slate-950 bg-emerald-400 px-2.5 py-0.5 rounded-full font-mono shadow-xs animate-pulse">
                          ~4 Mins Away
                        </span>
                      </div>

                      {/* STEP 3: PENDING ACTION (ACTIONABLE CYAN OTP CODE) */}
                      <div className="flex justify-between items-center bg-slate-950/60 p-2.5 rounded-xl border border-slate-800/80">
                        <span className="font-bold text-slate-200 flex items-center gap-2">
                          <Key className="w-4 h-4 text-cyan-400 shrink-0" />
                          <span>Geofenced Security OTP</span>
                        </span>
                        <button 
                          onClick={handleCopyOtp} 
                          className="font-mono font-black text-cyan-300 bg-cyan-950 hover:bg-cyan-900 border border-cyan-700 px-2.5 py-1 rounded text-[11px] flex items-center gap-1 transition-colors"
                          title="Tap code to copy"
                        >
                          <span>{copiedOtp ? 'Copied ✓' : '849201 (Tap to Copy)'}</span>
                        </button>
                      </div>
                    </div>

                    {/* ACTION BUTTONS: HIGH CONTRAST PRIMARY VS SECONDARY */}
                    <div className="grid grid-cols-2 gap-2 pt-1">
                      <a
                        href="tel:+919876543210"
                        className="bg-emerald-500 hover:bg-emerald-400 text-slate-950 font-black text-[11px] py-2.5 px-3 rounded-xl flex items-center justify-center gap-1.5 shadow-md shadow-emerald-500/20 transition-all active:scale-95"
                      >
                        <Phone className="w-3.5 h-3.5 text-slate-950" /> Call Escort Rahul
                      </a>
                      <a
                        href={`https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(`${selectedSectors[0] || 'Selected Sector'} Indore Escort Meeting Point`)}`}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-600 font-extrabold text-[11px] py-2.5 px-3 rounded-xl flex items-center justify-center gap-1.5 transition-colors"
                      >
                        <Navigation className="w-3.5 h-3.5 text-emerald-400" /> Escort Meeting Point
                      </a>
                    </div>
                  </div>

                  {/* 4 NON-REDUNDANT ACCOUNT METRICS WITH COLOR-CODED ACCENTS */}
                  <div className="grid grid-cols-2 gap-2 text-left">
                    {/* EMERALD ACCENT FOR TOUR PASSES */}
                    <div className="bg-emerald-950/30 p-2.5 rounded-2xl border border-emerald-500/30">
                      <div className="flex items-center justify-between mb-1">
                        <span className="text-[9px] font-mono text-emerald-400 uppercase font-bold">Tour Passes Balance</span>
                        <Sparkles className="w-3 h-3 text-emerald-400" />
                      </div>
                      <span className="text-xs font-black text-emerald-300 font-mono">{passesRemaining} / 5 Free Visits</span>
                    </div>

                    {/* AMBER/GOLD ACCENT FOR CASHBACK */}
                    <div className="bg-amber-950/30 p-2.5 rounded-2xl border border-amber-500/40">
                      <div className="flex items-center justify-between mb-1">
                        <span className="text-[9px] font-mono text-amber-400 uppercase font-bold">Lease Cashback</span>
                        <Gift className="w-3 h-3 text-amber-400" />
                      </div>
                      <span className="text-xs font-black text-amber-300 font-mono">₹1,000 UPI Reward</span>
                    </div>

                    {/* INDIGO/BLUE ACCENT FOR ZERO BROKERAGE */}
                    <div className="bg-indigo-950/30 p-2.5 rounded-2xl border border-indigo-500/30">
                      <div className="flex items-center justify-between mb-1">
                        <span className="text-[9px] font-mono text-indigo-400 uppercase font-bold">Zero Brokerage</span>
                        <ShieldCheck className="w-3 h-3 text-indigo-400" />
                      </div>
                      <span className="text-xs font-bold text-indigo-200 font-mono">100% Direct Deals</span>
                    </div>

                    {/* CYAN ACCENT FOR GEOFENCED OTP */}
                    <div className="bg-cyan-950/30 p-2.5 rounded-2xl border border-cyan-500/30">
                      <div className="flex items-center justify-between mb-1">
                        <span className="text-[9px] font-mono text-cyan-400 uppercase font-bold">Geofenced Code</span>
                        <Key className="w-3 h-3 text-cyan-400" />
                      </div>
                      <span className="text-xs font-bold text-cyan-300 font-mono">849201 Active</span>
                    </div>
                  </div>

                  <button
                    type="button"
                    onClick={() => setShowMobileLiveDetails(false)}
                    className="w-full py-1 text-center text-[11px] font-mono text-slate-400 hover:text-white flex items-center justify-center gap-1 transition-colors"
                  >
                    <span>Collapse Status & Details</span>
                    <ChevronDown className="w-3 h-3 rotate-180" />
                  </button>

                </motion.div>
              )}
            </AnimatePresence>
          </div>

          {/* CARD 1: CONSOLIDATED LOCATION SEARCH & FILTER CONSOLE DECK */}
          <div className="bg-white rounded-3xl p-4 sm:p-6 border border-slate-200/90 shadow-xl shadow-slate-900/5 space-y-4 sm:space-y-5">
            
            {/* CONSOLE TOP HEADER WITH CITY SELECTOR & CONDITIONAL CLEAR ALL FILTERS BUTTON */}
            <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 pb-3 border-b border-slate-100">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-xs font-black uppercase tracking-wider text-slate-900 font-['Outfit'] flex items-center gap-1.5">
                  <Filter className="w-4 h-4 text-emerald-600" /> Search & Filter Hub
                </span>
                
                {/* CITY METRO PICKER DROPDOWN */}
                <div 
                  className="relative"
                  onMouseEnter={() => setShowCityPicker(true)}
                  onMouseLeave={() => setShowCityPicker(false)}
                >
                  <button
                    type="button"
                    onClick={() => setShowCityPicker(!showCityPicker)}
                    className="bg-slate-900 hover:bg-slate-800 text-white font-extrabold text-[11px] px-3 py-1 rounded-xl flex items-center gap-1.5 shadow-sm transition-all"
                  >
                    <MapPin className="w-3 h-3 text-emerald-400" />
                    <span>City: <span className="text-emerald-400 uppercase tracking-wider font-mono">{selectedCity.name}</span></span>
                    <ChevronDown className={`w-3 h-3 text-slate-400 transition-transform duration-300 ${showCityPicker ? 'rotate-180' : ''}`} />
                  </button>

                  <AnimatePresence>
                    {showCityPicker && (
                      <motion.div
                        initial={{ opacity: 0, scale: 0.95, y: 6 }}
                        animate={{ opacity: 1, scale: 1, y: 0 }}
                        exit={{ opacity: 0, scale: 0.95, y: 6 }}
                        transition={{ type: 'spring', stiffness: 450, damping: 28 }}
                        className="absolute left-0 mt-1 w-48 bg-slate-900 border border-slate-700/90 rounded-2xl p-1.5 shadow-2xl z-50 text-left text-white"
                      >
                        {TENANT_CITIES.map((city) => (
                          <button
                            key={city.id}
                            onClick={() => handleSelectCity(city)}
                            className={`w-full text-left px-3 py-1.5 rounded-xl text-xs font-bold flex items-center justify-between transition-colors ${
                              city.id === selectedCityId ? 'bg-emerald-600 text-white' : 'hover:bg-slate-800 text-slate-300'
                            }`}
                          >
                            <div className="flex items-center gap-1.5">
                              <MapPin className="w-3 h-3 text-emerald-400" />
                              <span>{city.name}</span>
                            </div>
                            {city.status === 'ACTIVE' ? (
                              <span className="text-[8px] bg-emerald-950 text-emerald-300 px-1.5 py-0.5 rounded font-mono">Active</span>
                            ) : (
                              <span className="text-[8px] bg-amber-950 text-amber-300 px-1.5 py-0.5 rounded font-mono">Soon</span>
                            )}
                          </button>
                        ))}
                      </motion.div>
                    )}
                  </AnimatePresence>
                </div>
              </div>

              {/* CLEAR ALL FILTERS BUTTON (ONLY APPEARS WHEN AT LEAST 1 FILTER IS ACTIVE!) */}
              <AnimatePresence>
                {isAnyFilterActive && (
                  <motion.button
                    initial={{ opacity: 0, scale: 0.9 }}
                    animate={{ opacity: 1, scale: 1 }}
                    exit={{ opacity: 0, scale: 0.9 }}
                    whileHover={{ scale: 1.04 }}
                    whileTap={{ scale: 0.96 }}
                    type="button"
                    onClick={handleClearAllFilters}
                    className="w-full sm:w-auto text-xs font-extrabold px-3.5 py-1.5 rounded-xl transition-all flex items-center justify-center gap-1.5 border shadow-xs bg-rose-50 hover:bg-rose-100 text-rose-700 border-rose-300 shadow-rose-100 shrink-0"
                  >
                    <RotateCcw className="w-3.5 h-3.5 text-rose-600 animate-spin-once" />
                    <span>Clear All Filters</span>
                    <span className="w-2 h-2 rounded-full bg-rose-500" />
                  </motion.button>
                )}
              </AnimatePresence>
            </div>

            {/* SEARCH INPUT BAR WITH MIC AI & TRAVELING SPARK BORDER ANIMATION */}
            <div className="spark-search-container group shadow-2xl">
              {/* Rotating Luminous Border Spark (Loops every 1.8 seconds) */}
              <div className="spark-border-beam" />

              <div className="spark-search-inner flex items-center p-2">
                <div className="flex items-center gap-2 px-3 text-slate-400 border-r border-slate-200/90 shrink-0 relative z-10">
                  <div className="relative flex items-center justify-center p-1.5 rounded-xl bg-emerald-50 text-emerald-600 border border-emerald-200/80 shadow-xs">
                    <Search className="w-4 h-4 text-emerald-600" />
                    <Sparkles className="w-3.5 h-3.5 text-cyan-500 absolute -top-2 -right-2 animate-spin pointer-events-none drop-shadow-xs" style={{ animationDuration: '3s' }} />
                  </div>
                  <span className="text-xs font-black text-slate-900 hidden sm:inline ml-1 font-['Outfit']">Search Location</span>
                </div>

                <input
                  type="text"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  placeholder={`Search location, sector or colony in ${selectedCity.name}...`}
                  className="w-full bg-transparent px-3 py-2 text-xs font-extrabold text-slate-900 focus:outline-none placeholder:text-slate-400 relative z-10"
                />

                <div className="flex items-center gap-2 px-1 shrink-0 relative z-10">
                  <motion.button
                    whileHover={{ scale: 1.08 }}
                    whileTap={{ scale: 0.94 }}
                    onClick={() => setSearchQuery('Vijay Nagar 3BHK')}
                    className="hidden sm:flex items-center gap-1.5 bg-gradient-to-r from-emerald-500/10 via-teal-500/15 to-cyan-500/10 text-emerald-800 border border-emerald-300 px-3 py-1.5 rounded-xl text-[11px] font-extrabold font-mono hover:bg-emerald-100 transition-colors shadow-2xs"
                    title="Auto-fill AI Search Prompt"
                  >
                    <Sparkles className="w-3 h-3 text-emerald-600 animate-pulse" />
                    <span>Smart Prompt</span>
                  </motion.button>

                  <motion.button
                    whileHover={{ scale: 1.1, rotate: 8 }}
                    whileTap={{ scale: 0.9 }}
                    onClick={() => setSearchQuery('Vijay Nagar 3BHK')}
                    className="p-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white shadow-md shadow-emerald-600/30 transition-colors"
                    title="Voice Search AI Prompt"
                  >
                    <Mic className="w-3.5 h-3.5 text-white animate-pulse" />
                  </motion.button>
                </div>
              </div>
            </div>

            {/* MICRO-MARKET SECTORS FILTER BADGES (CONSOLIDATED HERE AT THE TOP!) */}
            <div className="space-y-2 pt-1">
              <div className="flex items-center justify-between">
                <span className="text-[10px] font-black text-slate-500 uppercase tracking-wider block font-mono flex items-center gap-1">
                  <Globe className="w-3 h-3 text-emerald-600" /> Micro-Market Sectors ({selectedCity.name}):
                </span>
                {selectedSectors.length > 0 && (
                  <button
                    type="button"
                    onClick={() => setSelectedSectors([])}
                    className="text-[10px] font-extrabold text-emerald-600 hover:text-emerald-700 font-mono underline"
                  >
                    Deselect All
                  </button>
                )}
              </div>

              <div className="flex overflow-x-auto no-scrollbar sm:flex-wrap items-center gap-1.5 pb-1 sm:pb-0 touch-pan-x">
                <button
                  type="button"
                  onClick={() => setSelectedSectors([])}
                  className={`text-[11px] font-extrabold px-3 py-1.5 rounded-xl border flex items-center gap-1 transition-all shrink-0 ${
                    selectedSectors.length === 0
                      ? 'bg-slate-900 text-white border-slate-800 shadow-xs'
                      : 'bg-slate-50 text-slate-700 border-slate-200 hover:bg-slate-100'
                  }`}
                >
                  <span>All</span>
                </button>

                {selectedCity.sectors.map((sector) => {
                  const isSelected = selectedSectors.includes(sector);
                  return (
                    <button
                      key={sector}
                      type="button"
                      onClick={() => toggleSector(sector)}
                      className={`text-[11px] font-extrabold px-3 py-1.5 rounded-xl border flex items-center gap-1 transition-all shrink-0 ${
                        isSelected
                          ? 'bg-emerald-600 text-white border-emerald-500 shadow-xs'
                          : 'bg-slate-50 text-slate-700 border-slate-200 hover:bg-slate-100'
                      }`}
                    >
                      <span>{sector}</span>
                      {isSelected && <Check className="w-3 h-3 text-white" />}
                    </button>
                  );
                })}
              </div>
            </div>

            {/* CONTROLS ROW: BUDGET SLIDER + VERIFICATION SWITCH */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 sm:gap-4 pt-2 border-t border-slate-100">
              {/* BUDGET SLIDER */}
              <div>
                <div className="flex justify-between items-center mb-1.5">
                  <label className="text-[10px] font-black text-slate-700 uppercase tracking-wider font-mono">
                    Max Monthly Budget
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

              {/* VERIFICATION FILTER SWITCH */}
              <div>
                <label className="text-[10px] font-black text-slate-700 block mb-1.5 uppercase tracking-wider font-mono">
                  Escort Inspection Filter
                </label>
                <button
                  type="button"
                  onClick={() => setVerifiedOnly(!verifiedOnly)}
                  className={`w-full py-2 px-3.5 rounded-xl border text-xs font-extrabold flex items-center justify-between transition-all ${
                    verifiedOnly
                      ? 'bg-emerald-50 border-emerald-300 text-emerald-900 shadow-xs'
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
                </button>
              </div>
            </div>

            {/* BHK CONFIGURATION SELECTOR BAR (SWIPABLE ON MOBILE) */}
            <div className="pt-3 border-t border-slate-100 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-[10px] font-black text-slate-700 uppercase tracking-wider font-mono flex items-center gap-1.5">
                  <SlidersHorizontal className="w-3.5 h-3.5 text-emerald-600" />
                  Flat Configuration (BHK Selector)
                </span>
                {selectedBhk !== 'ALL' && (
                  <button 
                    type="button"
                    onClick={() => setSelectedBhk('ALL')}
                    className="text-[10px] font-extrabold text-emerald-600 hover:text-emerald-700 font-mono underline"
                  >
                    Reset BHK
                  </button>
                )}
              </div>

              <div className="flex overflow-x-auto no-scrollbar sm:flex-wrap items-center gap-1.5 pb-1 sm:pb-0 touch-pan-x">
                <button
                  type="button"
                  onClick={() => setSelectedBhk('ALL')}
                  className={`px-3 py-1.5 rounded-xl text-xs font-extrabold transition-all shrink-0 ${
                    selectedBhk === 'ALL'
                      ? 'bg-slate-900 text-white shadow-xs'
                      : 'bg-slate-50 text-slate-700 hover:bg-slate-100 border border-slate-200'
                  }`}
                >
                  <span>All</span>
                </button>

                {(bhkConfigs || [
                  { id: '1RK', label: '1 RK', enabled: true },
                  { id: '1BHK', label: '1 BHK', enabled: true },
                  { id: '2BHK', label: '2 BHK', enabled: true },
                  { id: '3BHK', label: '3 BHK', enabled: true },
                  { id: '4BHK', label: '4 BHK+', enabled: true }
                ]).filter(c => c.enabled).map((config) => {
                  const isSelected = selectedBhk.toUpperCase() === config.id.toUpperCase();
                  return (
                    <button
                      key={config.id}
                      type="button"
                      onClick={() => setSelectedBhk(config.id)}
                      className={`px-3 py-1.5 rounded-xl text-xs font-extrabold flex items-center gap-1 transition-all shrink-0 ${
                        isSelected
                          ? 'bg-emerald-600 text-white shadow-md shadow-emerald-600/20'
                          : 'bg-slate-50 text-slate-700 hover:bg-slate-100 border border-slate-200'
                      }`}
                    >
                      <span>{config.label}</span>
                      {isSelected && <Check className="w-3 h-3 text-white" />}
                    </button>
                  );
                })}
              </div>
            </div>

          </div>

          {/* CARD 2: CATEGORY TABS BAR */}
          <div className="flex items-center justify-between bg-white rounded-2xl p-2 border border-slate-200/90 shadow-sm overflow-x-auto no-scrollbar gap-2">
            <div className="flex items-center gap-1.5 shrink-0">
              {[
                { id: 'ALL', label: 'All Available Homes' },
                { id: 'FLAT', label: 'Gated Flats' },
                { id: 'HOUSE', label: 'Independent Houses' },
                { id: 'PLOT', label: 'Plots / Land' }
              ].map(cat => {
                const isActive = activeCategory === cat.id && !showSavedOnly;
                return (
                  <button
                    key={cat.id}
                    onClick={() => { setActiveCategory(cat.id); setShowSavedOnly(false); }}
                    className={`relative px-3 sm:px-3.5 py-2 rounded-xl text-xs font-extrabold transition-colors shrink-0 ${
                      isActive ? 'text-slate-900' : 'text-slate-500 hover:text-slate-800'
                    }`}
                  >
                    {isActive && (
                      <motion.div
                        layoutId="categoryTabIndicator"
                        className="absolute inset-0 bg-slate-100 rounded-xl border border-slate-200 -z-10"
                        transition={{ type: 'spring', stiffness: 500, damping: 35 }}
                      />
                    )}
                    {cat.label}
                  </button>
                );
              })}
            </div>

            <button
              onClick={() => setShowSavedOnly(!showSavedOnly)}
              className={`px-3 sm:px-3.5 py-2 rounded-xl text-xs font-extrabold flex items-center gap-1.5 transition-all shrink-0 ${
                showSavedOnly ? 'bg-rose-50 text-rose-700 border border-rose-200' : 'text-slate-600 hover:bg-slate-50'
              }`}
            >
              <Heart className={`w-3.5 h-3.5 ${showSavedOnly ? 'fill-rose-500 text-rose-500' : 'text-slate-400'}`} />
              <span>Saved ({savedPropertyIds.length})</span>
            </button>
          </div>

          {/* CARD 3: FILTERED HOMES GRID */}
          <div className="space-y-4">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-1 sm:gap-0">
              <h2 className="text-base sm:text-xl font-black text-slate-900 font-['Outfit']">
                Available Homes in {selectedCity.name} ({filteredProperties.length})
              </h2>
              <span className="text-xs font-bold text-emerald-800 bg-emerald-50 px-3 py-1 rounded-full border border-emerald-200 font-mono w-fit">
                Direct Landlord Deals
              </span>
            </div>

            {filteredProperties.length === 0 ? (
              <div className="bg-white rounded-3xl p-6 sm:p-10 text-center border border-slate-200/90 shadow-xl space-y-3">
                <div className="w-12 h-12 sm:w-14 sm:h-14 bg-slate-100 text-slate-400 rounded-2xl flex items-center justify-center mx-auto">
                  <Filter className="w-6 h-6" />
                </div>
                <h3 className="text-base font-extrabold text-slate-900">No properties match your filters</h3>
                <p className="text-xs text-slate-500 max-w-sm mx-auto">Try deselecting sectors or resetting your search parameters.</p>
                <button
                  onClick={handleClearAllFilters}
                  className="bg-emerald-600 hover:bg-emerald-700 text-white font-extrabold text-xs px-5 py-2.5 rounded-xl shadow-md shadow-emerald-600/20 inline-flex items-center gap-2"
                >
                  <RotateCcw className="w-4 h-4 text-white" />
                  <span>Clear All Filters & Show All Homes</span>
                </button>
              </div>
            ) : (

              <motion.div 
                variants={containerVariants}
                initial="hidden"
                animate="visible"
                className="grid grid-cols-1 sm:grid-cols-2 gap-4 sm:gap-5"
              >
                {filteredProperties.map((prop, idx) => {
                  const isSaved = savedPropertyIds.includes(prop.id);
                  const isEven = idx % 2 === 0;
                  return (
                    <motion.div
                      key={prop.id}
                      initial={{ 
                        opacity: 0, 
                        y: 54, 
                        scale: 0.88, 
                        rotateY: isEven ? -14 : 14, 
                        rotateX: -10,
                        x: isEven ? -18 : 18
                      }}
                      whileInView={{ 
                        opacity: 1, 
                        y: 0, 
                        scale: 1, 
                        rotateY: 0, 
                        rotateX: 0,
                        x: 0
                      }}
                      viewport={{ once: true, margin: "-40px" }}
                      whileHover={{ 
                        y: -10, 
                        scale: 1.03, 
                        rotateY: isEven ? 2 : -2,
                        rotateX: 4
                      }}
                      transition={{ 
                        type: 'spring', 
                        stiffness: 260, 
                        damping: 20,
                        delay: (idx % 2) * 0.08
                      }}
                      className="relative rounded-3xl cursor-pointer group mb-3 sm:mb-4"
                      style={{ perspective: 1000 }}
                    >
                      {/* TOP MEDIA BACKDROP WITH AUTO-PLAY VIDEO ON HOVER */}
                      <PropertyCardMedia
                        property={prop}
                        onOpenVideoModal={(p, mode) => setModalConfig({ property: p, initialMode: mode })}
                        heightClass="h-44 xs:h-48 sm:h-56"
                      />

                      {/* ELEVATED OVERLAPPING FLOATING CARD WITH 3D LIFT */}
                      <motion.div 
                        initial={{ opacity: 0, y: 16, scale: 0.96 }}
                        whileInView={{ opacity: 1, y: 0, scale: 1 }}
                        viewport={{ once: true }}
                        transition={{ delay: 0.1, duration: 0.4 }}
                        className="bg-white rounded-3xl p-3.5 sm:p-5 shadow-xl border border-slate-200/90 relative -mt-8 sm:-mt-14 mx-2 sm:mx-4 z-20 hover:shadow-2xl transition-all duration-300"
                      >
                        <div className="flex items-center justify-between text-xs text-slate-500 mb-1.5">
                          <span className="flex items-center gap-1 font-semibold text-slate-700 truncate max-w-[65%]">
                            <MapPin className="w-3.5 h-3.5 text-emerald-600 shrink-0" />
                            <span className="truncate">{prop.sector}, {selectedCity.name}</span>
                          </span>
                          <span className="font-mono font-bold text-slate-400 shrink-0 text-[11px] sm:text-xs">{prop.totalAreaSqFt} sq ft</span>
                        </div>

                        <h3 className="font-extrabold text-slate-900 text-xs xs:text-sm sm:text-base mb-3 line-clamp-1 font-['Outfit']">{prop.title}</h3>

                        <div className="flex items-center justify-between pt-3 border-t border-slate-100 gap-2">
                          <div>
                            <span className="text-[9px] text-slate-400 font-black uppercase block font-mono">
                              {prop.listingType === 'SALE' ? 'Asking Price' : 'Monthly Rent'}
                            </span>
                            <span className="text-sm xs:text-base sm:text-lg font-black text-slate-900 font-mono">
                              {prop.listingType === 'SALE' ? (
                                `₹${((prop.askingPrice || 0) / 100000).toFixed(1)} L`
                              ) : (
                                <>₹{prop.monthlyRent?.toLocaleString('en-IN')}<span className="text-[10px] sm:text-xs font-normal text-slate-500">/mo</span></>
                              )}
                            </span>
                          </div>

                          <motion.button
                            whileHover={{ scale: 1.06, y: -2 }}
                            whileTap={{ scale: 0.94 }}
                            transition={{ type: 'spring', stiffness: 500, damping: 20 }}
                            onClick={() => onBookTour(prop)}
                            className="bg-emerald-600 hover:bg-emerald-700 text-white font-extrabold text-[11px] sm:text-xs px-3 sm:px-4 py-2 sm:py-2.5 rounded-xl shadow-md shadow-emerald-600/30 shimmer-glow relative z-10 shrink-0 whitespace-nowrap"
                          >
                            <span className="text-white font-extrabold">Book Tour Pass</span>
                          </motion.button>
                        </div>
                      </motion.div>

                    </motion.div>
                  );
                })}
              </motion.div>
            )}
          </div>

        </div>

        {/* ----------------------------------------------------------------------- */}
        {/* SIDEBAR COLUMN (4 COLS): ACTIVE ESCORT MILESTONE & CASHBACK */}
        {/* SECOND ON MOBILE (order-2 lg:order-1) */}
        {/* ----------------------------------------------------------------------- */}
        <div className="lg:col-span-4 grid grid-cols-1 md:grid-cols-2 lg:grid-cols-1 gap-5 space-y-0 lg:space-y-6 items-start lg:sticky lg:top-24 order-2 lg:order-1">
          
          {/* CARD 1: ACTIVE ESCORT 3-STEP MILESTONE TIMELINE PASS (DESKTOP SIDEBAR) */}
          <div className="hidden lg:block md:col-span-1 lg:col-span-1 bg-white rounded-3xl p-4 sm:p-6 border border-slate-200/90 shadow-xl shadow-slate-900/5 space-y-4 sm:space-y-5">
            <div className="flex items-center justify-between pb-3 border-b border-slate-100">
              <div className="flex items-center gap-2.5">
                <div className="w-8 h-8 rounded-xl bg-emerald-50 text-emerald-600 flex items-center justify-center font-bold border border-emerald-200 shrink-0">
                  <Key className="w-4 h-4 text-emerald-600" />
                </div>
                <div>
                  <h3 className="text-xs sm:text-sm font-black text-slate-900 font-['Outfit']">
                    On-Site Escort Pass #1
                  </h3>
                  <p className="text-[10px] sm:text-[11px] text-slate-500 font-mono">Scheduled Today 4:00 PM</p>
                </div>
              </div>
              <span className="text-[9px] sm:text-[10px] font-extrabold text-emerald-800 bg-emerald-50 px-2 sm:px-2.5 py-1 rounded-full border border-emerald-200 font-mono shrink-0">
                Live Status
              </span>
            </div>

            {/* 3-STEP TIMELINE LIST */}
            <div className="space-y-2.5 sm:space-y-3">
              <div className="bg-slate-50 p-3 sm:p-3.5 rounded-2xl border border-emerald-200/80 space-y-0.5">
                <div className="flex items-center justify-between">
                  <span className="text-[9px] sm:text-[10px] font-black uppercase text-emerald-700 tracking-wider font-mono flex items-center gap-1">
                    <CheckCircle2 className="w-3.5 h-3.5 text-emerald-600 shrink-0" /> Escort Assigned
                  </span>
                  <span className="text-[9px] font-bold text-emerald-700 bg-emerald-100 px-2 py-0.5 rounded font-mono">Done</span>
                </div>
                <p className="text-xs font-extrabold text-slate-900 truncate">Rahul Verma (+91 98765 43210)</p>
              </div>

              <div className="bg-emerald-50/80 p-3 sm:p-3.5 rounded-2xl border border-emerald-300 space-y-0.5">
                <div className="flex items-center justify-between">
                  <span className="text-[9px] sm:text-[10px] font-black uppercase text-emerald-800 tracking-wider font-mono flex items-center gap-1">
                    <Clock className="w-3.5 h-3.5 text-emerald-600 animate-spin shrink-0" style={{ animationDuration: '6s' }} /> En Route to Site
                  </span>
                  <span className="text-[9px] font-bold text-emerald-800 bg-emerald-200 px-2 py-0.5 rounded font-mono">~4 Mins</span>
                </div>
                <p className="text-xs font-extrabold text-slate-900">Arriving at Entrance</p>
              </div>

              <div className="bg-slate-50 p-3 sm:p-3.5 rounded-2xl border border-emerald-200/80 space-y-1">
                <div className="flex items-center justify-between">
                  <span className="text-[9px] sm:text-[10px] font-black uppercase text-slate-700 tracking-wider font-mono flex items-center gap-1">
                    <Key className="w-3.5 h-3.5 text-emerald-600 shrink-0" /> Geofenced Security OTP
                  </span>
                  <button
                    onClick={handleCopyOtp}
                    className="text-[10px] font-black font-mono text-emerald-800 bg-emerald-100 hover:bg-emerald-200 px-2.5 py-0.5 rounded-lg border border-emerald-300 transition-colors flex items-center gap-1"
                  >
                    <span>{copiedOtp ? 'Copied ✓' : '849201'}</span>
                  </button>
                </div>
                <p className="text-[10px] sm:text-[11px] text-slate-500">Tap code to copy & show to Rahul upon arrival</p>
              </div>
            </div>

            {/* ACTION BUTTONS */}
            <div className="grid grid-cols-2 gap-2 pt-1">
              <motion.a
                whileHover={{ scale: 1.02 }}
                whileTap={{ scale: 0.98 }}
                href="tel:+919876543210"
                className="bg-slate-900 hover:bg-slate-800 text-white font-extrabold text-[11px] py-2.5 px-3 rounded-xl flex items-center justify-center gap-1.5 shadow-sm"
              >
                <Phone className="w-3.5 h-3.5 text-emerald-400" /> Call Rahul
              </motion.a>

              <motion.a
                whileHover={{ scale: 1.02 }}
                whileTap={{ scale: 0.98 }}
                href={`https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(`${selectedSectors[0] || 'Selected Sector'} Indore Escort Meeting Point`)}`}
                target="_blank"
                rel="noopener noreferrer"
                className="bg-emerald-50 hover:bg-emerald-100 text-emerald-800 border border-emerald-200 font-extrabold text-[11px] py-2.5 px-3 rounded-xl flex items-center justify-center gap-1.5"
              >
                <Navigation className="w-3.5 h-3.5 text-emerald-600" /> Escort Meeting Point
              </motion.a>
            </div>
          </div>

          {/* CARD 2: LEASE AGREEMENT CASHBACK REWARD CARD */}
          <div className="md:col-span-1 lg:col-span-1 bg-white rounded-3xl p-4 sm:p-6 border border-slate-200/90 shadow-xl shadow-slate-900/5 space-y-4">
            <div className="flex items-center justify-between">
              <h4 className="text-sm font-black text-slate-900 font-['Outfit'] flex items-center gap-2">
                <Gift className="w-4 h-4 text-amber-600" /> ₹1,000 Rent Cashback
              </h4>
              <span className="text-[10px] font-bold text-amber-800 bg-amber-50 px-2 py-0.5 rounded font-mono">
                UPI Reward
              </span>
            </div>

            <div
              onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
              onDragLeave={() => setDragOver(false)}
              onDrop={handleDropLease}
              className={`border-2 border-dashed rounded-2xl p-4 sm:p-5 text-center transition-all ${
                dragOver ? 'border-emerald-500 bg-emerald-50/50' : 'border-slate-300 bg-slate-50/60 hover:bg-slate-50'
              }`}
            >
              <UploadCloud className="w-6 h-6 text-emerald-600 mx-auto mb-2" />
              <p className="text-xs font-extrabold text-slate-900 mb-1">Upload Rent Agreement PDF</p>
              <p className="text-[10px] text-slate-500 mb-3">Claim ₹1,000 lease bonus</p>

              {uploadedFile ? (
                <span className="text-[11px] font-mono text-emerald-700 font-bold bg-emerald-50 px-2.5 py-1 rounded-lg border border-emerald-200">
                  ✓ {uploadedFile}
                </span>
              ) : (
                <button
                  onClick={onOpenLeaseUpload}
                  className="bg-emerald-600 hover:bg-emerald-700 text-white font-extrabold text-xs px-4 py-2 rounded-xl shadow-xs"
                >
                  Browse PDF File
                </button>
              )}
            </div>
          </div>

        </div>

      </div>

      {/* ========================================================================= */}
      {/* 3. PERSISTENT FLOATING QUICK-ACTION CONTROL DOCK (ALWAYS AT THUMB HEIGHT) */}
      {/* ========================================================================= */}
      <AnimatePresence>
        <motion.div
          initial={{ y: 80, opacity: 0 }}
          animate={{ y: 0, opacity: 1 }}
          exit={{ y: 80, opacity: 0 }}
          transition={{ type: 'spring', stiffness: 450, damping: 28 }}
          className="fixed bottom-3 sm:bottom-5 left-1/2 -translate-x-1/2 z-50 bg-slate-950/92 text-white rounded-full p-1.5 sm:p-2 pl-3 sm:pl-4 pr-2.5 sm:pr-3 shadow-2xl backdrop-blur-xl border border-slate-800/90 flex items-center justify-between gap-1.5 sm:gap-3 max-w-xl w-[94%] sm:w-auto"
        >
          {/* OTP QUICK COPY BADGE */}
          <div className="flex items-center gap-1.5 sm:gap-2">
            <span className="text-[10px] text-slate-400 font-extrabold uppercase tracking-wider hidden sm:inline font-mono">
              Site Pass:
            </span>
            <button
              onClick={handleCopyOtp}
              className="bg-emerald-950 text-emerald-300 border border-emerald-700/80 px-2.5 sm:px-3 py-1 sm:py-1.5 rounded-full text-[11px] sm:text-xs font-black tracking-wider flex items-center gap-1 sm:gap-1.5 hover:bg-emerald-900 transition-colors font-mono"
              title="Click to copy OTP"
            >
              {copiedOtp ? <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" /> : <Key className="w-3.5 h-3.5 text-emerald-400" />}
              <span>{copiedOtp ? 'Copied ✓' : 'OTP: 849201'}</span>
            </button>
          </div>

          <div className="h-4 w-px bg-slate-800 shrink-0" />

          {/* CALL ESCORT CTA */}
          <a
            href="tel:+919876543210"
            className="bg-emerald-600 hover:bg-emerald-500 text-white font-extrabold text-[11px] sm:text-xs px-3 sm:px-4 py-1 sm:py-1.5 rounded-full flex items-center gap-1 sm:gap-1.5 shadow-md shadow-emerald-600/30 transition-all shrink-0"
          >
            <Phone className="w-3.5 h-3.5 text-white" />
            <span className="whitespace-nowrap font-extrabold">Call Escort</span>
          </a>

          {/* CLAIM CASHBACK CTA */}
          <button
            onClick={onOpenLeaseUpload}
            className="bg-slate-900 hover:bg-slate-800 text-amber-300 border border-amber-500/30 font-extrabold text-[11px] sm:text-xs px-2.5 sm:px-3.5 py-1 sm:py-1.5 rounded-full flex items-center gap-1 sm:gap-1.5 transition-all shrink-0"
          >
            <Gift className="w-3.5 h-3.5 text-amber-400" />
            <span className="whitespace-nowrap hidden xs:inline font-extrabold">₹1k Cashback</span>
          </button>
        </motion.div>
      </AnimatePresence>

      </motion.div>

      {/* VIDEO PLAYER MODAL ON CLICK */}
      <VideoPlayerModal
        property={modalConfig?.property || null}
        isOpen={!!modalConfig}
        initialMode={modalConfig?.initialMode || 'VIDEO'}
        onClose={() => setModalConfig(null)}
        onBookTour={onBookTour}
      />
    </>
  );
};
