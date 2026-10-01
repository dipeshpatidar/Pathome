import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { motion, AnimatePresence, useReducedMotion } from 'framer-motion';
import { Property, UserProfile, UserRole } from '../types';
import { Navbar } from './Navbar';
import { HeroSection } from './HeroSection';
import { PropertyShowcase } from './PropertyShowcase';
import { ValueBanner } from './ValueBanner';
import { FutureExpansion } from './FutureExpansion';
import { HowItWorks } from './HowItWorks';
import { Footer } from './Footer';
import { AuthModal } from './AuthModal';
import { LeaseUploadModal } from './LeaseUploadModal';
import { TenantDashboard } from './TenantDashboard';
import { ArrowUp, ChevronDown, LoaderCircle, MapPin } from 'lucide-react';
import { PublicPropertyDetail } from './PublicPropertyDetail';
import { VisitRequestModal } from './VisitRequestModal';
import { LessorWorkspace } from './LessorWorkspace';
import { PathomeRouteShell } from './PathomeRouteShell';
import { lessorDraftService } from '../services/lessorDraftService';


import { MasterAdminDashboard } from './MasterAdminDashboard';
import { EmployeeCrmDashboard } from './EmployeeCrmDashboard';
import { CompactSearchContext } from './CompactSearchContext';
import { propertyService } from '../services/propertyService';
import { lessorCapabilityService } from '../services/lessorCapabilityService';
import { LessorCapabilityTracker, isCurrentLessorSession, readLessorSessionIdentity } from '../utils/lessorCapabilityState';
import { isLessorWorkspaceRoute, normalizeRoutePathname, resolveLessorExitPath } from '../utils/navigationPolicy';
import { getGuestResumableDraftCount, readResumableDraftCount } from '../utils/draftAccessPolicy';
import { useNotification } from '../context/NotificationContext';
import { clearPersistedUser, readPersistedUser } from '../utils/authSession';
import { discoverySearchKey, extractCityFromSearchQuery, parseRentalFurnishing, parseRentalPropertyType, parseRentFilter, resetFiltersForManualCityChange, resetFiltersForSearchClear, RentalSearchFilters } from '../utils/rentalSearch';

const VALID_ADMIN_TABS = new Set([
  'overview',
  'funnel',
  'crm',
  'employees',
  'payroll',
  'approval',
  'learning',
  'config',
  'media',
  'failed-uploads'
]);

const getInitialAdminTab = (): string => {
  try {
    const savedTab = localStorage.getItem('pathome_active_admin_tab');
    if (savedTab && VALID_ADMIN_TABS.has(savedTab)) {
      return savedTab;
    }
  } catch (err) {
    console.warn('Failed to load active admin tab from localStorage', err);
  }
  return 'overview';
};

const mockPropertyList: Property[] = [
  {
    id: 1,
    title: "Luxury 3 BHK Gated Flat",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Indore",
    sector: "Vijay Nagar",
    bhk: "3BHK",
    monthlyRent: 22000,
    securityDeposit: 44000,
    totalAreaSqFt: 1650,
    images: ["/assets/hero_luxury.jpg", "/assets/interior_living.jpg"],
    verified: true,
    ownerPhone: "+91 98260 *****",
    latitude: 22.7533,
    longitude: 75.8937
  },
  {
    id: 2,
    title: "Independent House with Garden",
    listingType: "RENT",
    propertyType: "HOUSE",
    city: "Indore",
    sector: "Bhawarkua",
    bhk: "3BHK",
    monthlyRent: 18500,
    securityDeposit: 37000,
    totalAreaSqFt: 2100,
    images: ["/assets/interior_living.jpg", "/assets/hero_luxury.jpg"],
    verified: true,
    ownerPhone: "+91 94250 *****",
    latitude: 22.6926,
    longitude: 75.8676
  },
  {
    id: 3,
    title: "Phase 2 Prime Commercial Plot",
    listingType: "SALE",
    propertyType: "PLOT",
    city: "Indore",
    sector: "AB Road",
    monthlyRent: 0,
    askingPrice: 7500000,
    securityDeposit: 0,
    totalAreaSqFt: 3000,
    images: ["/assets/spatial_gis.jpg"],
    verified: true,
    ownerPhone: "+91 98930 *****",
    latitude: 22.7200,
    longitude: 75.8800
  },
  {
    id: 4,
    title: "Furnished 2 BHK High-Rise Apartment",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Indore",
    sector: "Nipania",
    bhk: "2BHK",
    monthlyRent: 16000,
    securityDeposit: 32000,
    totalAreaSqFt: 1250,
    images: ["/assets/panoramic_skyline.jpg", "/assets/hero_luxury.jpg"],
    verified: true,
    ownerPhone: "+91 98271 *****",
    latitude: 22.7650,
    longitude: 75.9050
  },
  {
    id: 5,
    title: "Modern 1 BHK Studio Flat",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Indore",
    sector: "LIG Circle",
    bhk: "1BHK",
    monthlyRent: 12000,
    securityDeposit: 24000,
    totalAreaSqFt: 750,
    images: ["/assets/interior_living.jpg"],
    verified: true,
    ownerPhone: "+91 98263 *****",
    latitude: 22.7388,
    longitude: 75.8820
  },
  {
    id: 6,
    title: "Executive Duplex Villa",
    listingType: "RENT",
    propertyType: "HOUSE",
    city: "Indore",
    sector: "Old Palasia",
    bhk: "4BHK",
    monthlyRent: 35000,
    securityDeposit: 70000,
    totalAreaSqFt: 2800,
    images: ["/assets/hero_luxury.jpg", "/assets/panoramic_skyline.jpg"],
    verified: true,
    ownerPhone: "+91 94253 *****",
    latitude: 22.7196,
    longitude: 75.8839
  },
  {
    id: 7,
    title: "Premium Student Co-Living Suite",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Indore",
    sector: "Bhawarkua",
    bhk: "1RK",
    monthlyRent: 8500,
    securityDeposit: 17000,
    totalAreaSqFt: 550,
    images: ["/assets/interior_living.jpg"],
    verified: true,
    ownerPhone: "+91 98934 *****",
    latitude: 22.6900,
    longitude: 75.8650
  },
  {
    id: 8,
    title: "High-Rise 3 BHK Penthouse",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Indore",
    sector: "Super Corridor",
    bhk: "3BHK",
    monthlyRent: 28000,
    securityDeposit: 56000,
    totalAreaSqFt: 1950,
    images: ["/assets/panoramic_skyline.jpg", "/assets/hero_luxury.jpg"],
    verified: true,
    ownerPhone: "+91 98265 *****",
    latitude: 22.7750,
    longitude: 75.8350
  },
  {
    id: 9,
    title: "Spacious 2 BHK Family Home",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Indore",
    sector: "Vijay Nagar",
    bhk: "2BHK",
    monthlyRent: 19000,
    securityDeposit: 38000,
    totalAreaSqFt: 1350,
    images: ["/assets/hero_luxury.jpg"],
    verified: true,
    ownerPhone: "+91 98268 *****",
    latitude: 22.7560,
    longitude: 75.8910
  },
  {
    id: 10,
    title: "Corner Residential Plot 2400 Sq Ft",
    listingType: "SALE",
    propertyType: "PLOT",
    sector: "Super Corridor",
    monthlyRent: 0,
    askingPrice: 4500000,
    securityDeposit: 0,
    totalAreaSqFt: 2400,
    images: ["/assets/spatial_gis.jpg"],
    verified: true,
    ownerPhone: "+91 94258 *****",
    latitude: 22.7720,
    longitude: 75.8320
  },
  {
    id: 11,
    title: "Gated Community 4 BHK Bungalow",
    listingType: "RENT",
    propertyType: "HOUSE",
    city: "Indore",
    sector: "South Tukoganj",
    bhk: "4BHK",
    monthlyRent: 42000,
    securityDeposit: 84000,
    totalAreaSqFt: 3400,
    images: ["/assets/hero_luxury.jpg", "/assets/interior_living.jpg"],
    verified: true,
    ownerPhone: "+91 98939 *****",
    latitude: 22.7120,
    longitude: 75.8750
  },
  {
    id: 12,
    title: "Compact 1 BHK Flat near Coaching Hub",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Indore",
    sector: "Bhawarkua",
    bhk: "1BHK",
    monthlyRent: 9500,
    securityDeposit: 19000,
    totalAreaSqFt: 620,
    images: ["/assets/interior_living.jpg"],
    verified: true,
    ownerPhone: "+91 98274 *****",
    latitude: 22.6950,
    longitude: 75.8690
  },
  {
    id: 13,
    title: "Modern 2 BHK Gated Residency",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Indore",
    sector: "AB Road",
    bhk: "2BHK",
    monthlyRent: 17500,
    securityDeposit: 35000,
    totalAreaSqFt: 1180,
    images: ["/assets/panoramic_skyline.jpg"],
    verified: true,
    ownerPhone: "+91 98262 *****",
    latitude: 22.7300,
    longitude: 75.8850
  },
  {
    id: 14,
    title: "Luxury 3 BHK Metro Apartment",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Bhopal",
    sector: "MP Nagar",
    bhk: "3BHK",
    monthlyRent: 20000,
    securityDeposit: 40000,
    totalAreaSqFt: 1500,
    images: ["/assets/hero_luxury.jpg"],
    verified: true,
    ownerPhone: "+91 98279 *****",
    latitude: 23.2332,
    longitude: 77.4343
  },
  {
    id: 15,
    title: "Premium 2 BHK Independent House",
    listingType: "RENT",
    propertyType: "HOUSE",
    city: "Bhopal",
    sector: "Arera Colony",
    bhk: "2BHK",
    monthlyRent: 24000,
    securityDeposit: 48000,
    totalAreaSqFt: 1800,
    images: ["/assets/interior_living.jpg"],
    verified: true,
    ownerPhone: "+91 94259 *****",
    latitude: 23.2100,
    longitude: 77.4400
  },
  {
    id: 16,
    title: "IT Park 2 BHK Smart Apartment",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Pune",
    sector: "Hinjewadi",
    bhk: "2BHK",
    monthlyRent: 26000,
    securityDeposit: 52000,
    totalAreaSqFt: 1100,
    images: ["/assets/panoramic_skyline.jpg"],
    verified: true,
    ownerPhone: "+91 98288 *****",
    latitude: 18.5912,
    longitude: 73.7389
  },
  {
    id: 17,
    title: "Gated 3 BHK Premium Society Flat",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Pune",
    sector: "Baner",
    bhk: "3BHK",
    monthlyRent: 32000,
    securityDeposit: 64000,
    totalAreaSqFt: 1600,
    images: ["/assets/hero_luxury.jpg"],
    verified: true,
    ownerPhone: "+91 98299 *****",
    latitude: 18.5590,
    longitude: 73.7868
  },
  {
    id: 18,
    title: "Tech Park 2 BHK Luxury Residence",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Bangalore",
    sector: "Indiranagar",
    bhk: "2BHK",
    monthlyRent: 38000,
    securityDeposit: 76000,
    totalAreaSqFt: 1300,
    images: ["/assets/hero_luxury.jpg", "/assets/interior_living.jpg"],
    verified: true,
    ownerPhone: "+91 98290 *****",
    latitude: 12.9784,
    longitude: 77.6408
  },
  {
    id: 19,
    title: "Phase 1 Residential Plot 1800 Sq Ft",
    listingType: "SALE",
    propertyType: "PLOT",
    sector: "Vijay Nagar",
    monthlyRent: 0,
    askingPrice: 5200000,
    securityDeposit: 0,
    totalAreaSqFt: 1800,
    images: ["/assets/spatial_gis.jpg"],
    verified: true,
    ownerPhone: "+91 98261 *****",
    latitude: 22.7580,
    longitude: 75.8940
  },
  {
    id: 20,
    title: "Fully Furnished 3 BHK Penthouse",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Indore",
    sector: "Old Palasia",
    bhk: "3BHK",
    monthlyRent: 38000,
    securityDeposit: 76000,
    totalAreaSqFt: 2200,
    images: ["/assets/panoramic_skyline.jpg", "/assets/hero_luxury.jpg"],
    verified: true,
    ownerPhone: "+91 94251 *****",
    latitude: 22.7210,
    longitude: 75.8850
  },
  {
    id: 21,
    title: "Independent 2 BHK Garden House",
    listingType: "RENT",
    propertyType: "HOUSE",
    city: "Indore",
    sector: "Nipania",
    bhk: "2BHK",
    monthlyRent: 21000,
    securityDeposit: 42000,
    totalAreaSqFt: 1750,
    images: ["/assets/interior_living.jpg"],
    verified: true,
    ownerPhone: "+91 98272 *****",
    latitude: 22.7680,
    longitude: 75.9080
  },
  {
    id: 22,
    title: "Cozy 1 BHK Flat for Young Professionals",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Indore",
    sector: "AB Road",
    bhk: "1BHK",
    monthlyRent: 11500,
    securityDeposit: 23000,
    totalAreaSqFt: 680,
    images: ["/assets/interior_living.jpg"],
    verified: true,
    ownerPhone: "+91 98264 *****",
    latitude: 22.7320,
    longitude: 75.8870
  },
  {
    id: 23,
    title: "Commercial Plot 3600 Sq Ft",
    listingType: "SALE",
    propertyType: "PLOT",
    sector: "Super Corridor",
    monthlyRent: 0,
    askingPrice: 8800000,
    securityDeposit: 0,
    totalAreaSqFt: 3600,
    images: ["/assets/spatial_gis.jpg"],
    verified: true,
    ownerPhone: "+91 94254 *****",
    latitude: 22.7780,
    longitude: 75.8380
  },
  {
    id: 24,
    title: "Luxury 4 BHK Villa with Private Garden",
    listingType: "RENT",
    propertyType: "HOUSE",
    city: "Indore",
    sector: "South Tukoganj",
    bhk: "4BHK",
    monthlyRent: 55000,
    securityDeposit: 110000,
    totalAreaSqFt: 4100,
    images: ["/assets/hero_luxury.jpg", "/assets/interior_living.jpg"],
    verified: true,
    ownerPhone: "+91 98935 *****",
    latitude: 22.7140,
    longitude: 75.8770
  },
  {
    id: 25,
    title: "Compact 1 BHK Studio for Students",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Indore",
    sector: "Bhawarkua",
    bhk: "1BHK",
    monthlyRent: 10000,
    securityDeposit: 20000,
    totalAreaSqFt: 600,
    images: ["/assets/interior_living.jpg"],
    verified: true,
    ownerPhone: "+91 98275 *****",
    latitude: 22.6970,
    longitude: 75.8710
  },
  {
    id: 26,
    title: "High-Rise 2 BHK Gated Flat",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Indore",
    sector: "LIG Circle",
    bhk: "2BHK",
    monthlyRent: 15500,
    securityDeposit: 31000,
    totalAreaSqFt: 1120,
    images: ["/assets/panoramic_skyline.jpg"],
    verified: true,
    ownerPhone: "+91 98266 *****",
    latitude: 22.7400,
    longitude: 75.8840
  },
  {
    id: 27,
    title: "Spacious 3 BHK Duplex Flat",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Indore",
    sector: "Vijay Nagar",
    bhk: "3BHK",
    monthlyRent: 25000,
    securityDeposit: 50000,
    totalAreaSqFt: 1780,
    images: ["/assets/hero_luxury.jpg"],
    verified: true,
    ownerPhone: "+91 98269 *****",
    latitude: 22.7590,
    longitude: 75.8950
  },
  {
    id: 28,
    title: "Corner Plot 1500 Sq Ft Prime Belt",
    listingType: "SALE",
    propertyType: "PLOT",
    sector: "AB Road",
    monthlyRent: 0,
    askingPrice: 3900000,
    securityDeposit: 0,
    totalAreaSqFt: 1500,
    images: ["/assets/spatial_gis.jpg"],
    verified: true,
    ownerPhone: "+91 94257 *****",
    latitude: 22.7350,
    longitude: 75.8890
  },
  {
    id: 29,
    title: "Modern 2 BHK Apartment",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Bhopal",
    sector: "Kolar Road",
    bhk: "2BHK",
    monthlyRent: 15000,
    securityDeposit: 30000,
    totalAreaSqFt: 1200,
    images: ["/assets/interior_living.jpg"],
    verified: true,
    ownerPhone: "+91 98278 *****",
    latitude: 23.1800,
    longitude: 77.4200
  },
  {
    id: 30,
    title: "Spacious 3 BHK Family House",
    listingType: "RENT",
    propertyType: "HOUSE",
    city: "Bhopal",
    sector: "Hoshangabad Road",
    bhk: "3BHK",
    monthlyRent: 22000,
    securityDeposit: 44000,
    totalAreaSqFt: 1900,
    images: ["/assets/hero_luxury.jpg"],
    verified: true,
    ownerPhone: "+91 94256 *****",
    latitude: 23.1900,
    longitude: 77.4500
  },
  {
    id: 31,
    title: "IT Hub 3 BHK Apartment",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Pune",
    sector: "Wakad",
    bhk: "3BHK",
    monthlyRent: 30000,
    securityDeposit: 60000,
    totalAreaSqFt: 1550,
    images: ["/assets/panoramic_skyline.jpg"],
    verified: true,
    ownerPhone: "+91 98287 *****",
    latitude: 18.5980,
    longitude: 73.7620
  },
  {
    id: 32,
    title: "Gated 2 BHK Society Flat",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Pune",
    sector: "Kharadi",
    bhk: "2BHK",
    monthlyRent: 27000,
    securityDeposit: 54000,
    totalAreaSqFt: 1150,
    images: ["/assets/hero_luxury.jpg"],
    verified: true,
    ownerPhone: "+91 98289 *****",
    latitude: 18.5510,
    longitude: 73.9450
  },
  {
    id: 33,
    title: "Premium 3 BHK Suite",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Pune",
    sector: "Viman Nagar",
    bhk: "3BHK",
    monthlyRent: 36000,
    securityDeposit: 72000,
    totalAreaSqFt: 1700,
    images: ["/assets/interior_living.jpg"],
    verified: true,
    ownerPhone: "+91 98291 *****",
    latitude: 18.5670,
    longitude: 73.9140
  },
  {
    id: 34,
    title: "Tech Corridor 2 BHK Flat",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Bangalore",
    sector: "Koramangala",
    bhk: "2BHK",
    monthlyRent: 35000,
    securityDeposit: 70000,
    totalAreaSqFt: 1250,
    images: ["/assets/panoramic_skyline.jpg"],
    verified: true,
    ownerPhone: "+91 98292 *****",
    latitude: 12.9350,
    longitude: 77.6240
  },
  {
    id: 35,
    title: "Luxury 3 BHK Penthouse",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Bangalore",
    sector: "HSR Layout",
    bhk: "3BHK",
    monthlyRent: 45000,
    securityDeposit: 90000,
    totalAreaSqFt: 2100,
    images: ["/assets/hero_luxury.jpg"],
    verified: true,
    ownerPhone: "+91 98293 *****",
    latitude: 12.9120,
    longitude: 77.6440
  },
  {
    id: 36,
    title: "Gated 2 BHK Flat",
    listingType: "RENT",
    propertyType: "FLAT",
    city: "Bangalore",
    sector: "Whitefield",
    bhk: "2BHK",
    monthlyRent: 30000,
    securityDeposit: 60000,
    totalAreaSqFt: 1200,
    images: ["/assets/interior_living.jpg"],
    verified: true,
    ownerPhone: "+91 98294 *****",
    latitude: 12.9690,
    longitude: 77.7500
  },
  {
    id: 37,
    title: "Phase 2 Corner Plot 2000 Sq Ft",
    listingType: "SALE",
    propertyType: "PLOT",
    sector: "Super Corridor",
    monthlyRent: 0,
    askingPrice: 4100000,
    securityDeposit: 0,
    totalAreaSqFt: 2000,
    images: ["/assets/spatial_gis.jpg"],
    verified: true,
    ownerPhone: "+91 94255 *****",
    latitude: 22.7760,
    longitude: 75.8360
  },
  {
    id: 38,
    title: "Executive 2 BHK Garden Villa",
    listingType: "RENT",
    propertyType: "HOUSE",
    sector: "Vijay Nagar",
    bhk: "2BHK",
    monthlyRent: 23500,
    securityDeposit: 47000,
    totalAreaSqFt: 1850,
    images: ["/assets/hero_luxury.jpg", "/assets/interior_living.jpg"],
    verified: true,
    ownerPhone: "+91 98267 *****",
    latitude: 22.7570,
    longitude: 75.8920
  }
];

export const Home: React.FC = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const { notifySuccess } = useNotification();
  const reduceMotion = useReducedMotion();

  const [user, setUser] = useState<UserProfile | null>(readPersistedUser);
  const role: UserRole = user?.role ?? 'GUEST';
  const [hasLessorCapability, setHasLessorCapability] = useState<boolean | null | 'error'>(null);
  const [draftSnapshot, setDraftSnapshot] = useState<{ key: string; count: number; state: 'loading' | 'ready' | 'error' }>({
    key: '', count: 0, state: 'loading'
  });
  const capabilityTrackerRef = useRef<LessorCapabilityTracker | null>(null);
  if (!capabilityTrackerRef.current) {
    capabilityTrackerRef.current = new LessorCapabilityTracker(setHasLessorCapability);
  }
  const capabilityTracker = capabilityTrackerRef.current;
  const logoutInProgressRef = useRef(false);
  const clearUserSession = useCallback(() => {
    // Remove the token first so every in-flight identity check immediately becomes stale.
    clearPersistedUser();
    discoveryAbortRef.current?.abort();
    loadMoreAbortRef.current?.abort();
    discoveryRequestRef.current += 1;
    setProperties([]);
    setDiscoveryState('LOADING');
    setHasMoreProperties(false);
    setLoadingMore(false);
    setLoadMoreError(null);
    setUser(null);
    capabilityTracker.clear();
    setDraftSnapshot({ key: '', count: 0, state: 'loading' });
    setPendingVisitProperty(null);
    setShowPostPropertyModal(false);
    setLessorAuthContext(null);
    window.dispatchEvent(new Event('pathome_auth_changed'));
  }, [capabilityTracker]);
  const [properties, setProperties] = useState<Property[]>([]);
  const [discoveryState, setDiscoveryState] = useState<'LOADING' | 'READY' | 'ERROR'>('LOADING');
  const [loadedDiscoveryKey, setLoadedDiscoveryKey] = useState<string | null>(null);
  const [discoveryError, setDiscoveryError] = useState<string | null>(null);
  const [hasMoreProperties, setHasMoreProperties] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);
  const currentDiscoveryPage = useRef(0);
  const [showAuthModal, setShowAuthModal] = useState(false);
  const [lessorAuthContext, setLessorAuthContext] = useState<'submit' | 'save' | null>(null);
  const [activeAdminTab, setActiveAdminTab] = useState<string>(getInitialAdminTab);

  useEffect(() => {
    if (activeAdminTab && VALID_ADMIN_TABS.has(activeAdminTab)) {
      try {
        localStorage.setItem('pathome_active_admin_tab', activeAdminTab);
      } catch (_) {}
    }
  }, [activeAdminTab]);
  const filterSector = new URLSearchParams(location.search).get('sector') || '';
  const currentSearchParams = new URLSearchParams(location.search);
  const rawUrlCity = currentSearchParams.get('city');
  const urlQueryText = currentSearchParams.get('q') || '';
  const inferredCityFromQuery = urlQueryText ? extractCityFromSearchQuery(urlQueryText) : undefined;
  const activeDiscoveryCity = rawUrlCity
    || inferredCityFromQuery
    || (currentSearchParams.has('q') || currentSearchParams.get('rentalOnly') === 'true' ? '' : 'Indore');
  const activeSearchFilters: RentalSearchFilters = {
    city: activeDiscoveryCity,
    sector: filterSector || undefined,
    q: currentSearchParams.get('q') || undefined,
    bhk: currentSearchParams.get('bhk') || undefined,
    propertyType: parseRentalPropertyType(currentSearchParams.get('propertyType')),
    furnishing: parseRentalFurnishing(currentSearchParams.get('furnishing')),
    minRent: parseRentFilter(currentSearchParams.get('minRent')),
    maxRent: parseRentFilter(currentSearchParams.get('maxRent')),
    rentalOnly: currentSearchParams.get('rentalOnly') === 'true'
  };
  const activeDiscoveryKey = discoverySearchKey(activeSearchFilters);
  const [refineSearchRequest, setRefineSearchRequest] = useState(0);
  const [showPostPropertyModal, setShowPostPropertyModal] = useState(false);
  const openPostProperty = useCallback(() => {
    const origin = resolveLessorExitPath(`${location.pathname}${location.search}`, {
      guest: role === 'GUEST',
      tenant: role === 'TENANT',
      activeLessor: hasLessorCapability === true
    });
    navigate('/lessor/new', { state: { lessorOrigin: origin } });
  }, [navigate, location.pathname, location.search, role, hasLessorCapability]);
  const openDrafts = useCallback(() => navigate('/lessor?view=drafts'), [navigate]);
  const retryDraftCheck = useCallback(() => {
    window.dispatchEvent(new Event('pathome_lessor_drafts_changed'));
  }, []);
  const requestLessorAuth = useCallback((draftId: string, submit: boolean) => {
    sessionStorage.setItem(submit ? 'pathome_guest_submit_draft' : 'pathome_guest_save_draft', draftId);
    setLessorAuthContext(submit ? 'submit' : 'save');
    setShowAuthModal(true);
  }, []);
  const closePostProperty = useCallback(() => setShowPostPropertyModal(false), []);
  const [pendingVisitProperty, setPendingVisitProperty] = useState<Property | null>(null);
  const discoveryRequestRef = useRef(0);
  const focusResultsAfterSearch = useRef(false);
  const discoveryAbortRef = useRef<AbortController | null>(null);
  const loadMoreAbortRef = useRef<AbortController | null>(null);
  const normalizedPathname = normalizeRoutePathname(location.pathname);
  const isPropertyRoute = normalizedPathname.startsWith('/property/');
  const isLessorRoute = isLessorWorkspaceRoute(normalizedPathname);
  const isPublicPropertyRoute = /^\/property\/\d+$/.test(normalizedPathname);
  const publicPropertyId = isPublicPropertyRoute ? Number(normalizedPathname.split('/').pop()) : null;
  const draftSessionKey = user ? (readLessorSessionIdentity()?.key ?? 'missing-session') : 'guest';
  const currentDraftSnapshot = draftSnapshot.key === draftSessionKey ? draftSnapshot : null;
  const currentDraftCount = currentDraftSnapshot?.count ?? 0;
  const currentDraftState = currentDraftSnapshot?.state ?? 'loading';

  const [showBackToTop, setShowBackToTop] = useState(false);
  const prevIsPropertyRoute = useRef(isPropertyRoute);

  useEffect(() => {
    const handleScroll = () => {
      setShowBackToTop(window.scrollY > 450);
    };
    window.addEventListener('scroll', handleScroll, { passive: true });
    return () => window.removeEventListener('scroll', handleScroll);
  }, []);

  const [showCompactSearch, setShowCompactSearch] = useState(false);

  useEffect(() => {
    if (isPropertyRoute || role !== 'GUEST') {
      setShowCompactSearch(false);
      return undefined;
    }

    const heroSearchEl = document.getElementById('hero-search-surface');
    if (!heroSearchEl) return undefined;

    const syncCompactSearch = () => {
      const focusedElement = document.activeElement;
      if (focusedElement && heroSearchEl.contains(focusedElement)) {
        setShowCompactSearch(false);
      } else if (focusedElement && document.getElementById('compact-discovery-context')?.contains(focusedElement)) {
        setShowCompactSearch(true);
      } else {
        setShowCompactSearch(heroSearchEl.getBoundingClientRect().bottom < 75);
      }
    };
    let focusFrame = 0;
    const handleSearchFocus = (event: FocusEvent) => {
      const target = event.target as Node;
      if (!heroSearchEl.contains(target) && !document.getElementById('compact-discovery-context')?.contains(target)) return;
      window.cancelAnimationFrame(focusFrame);
      focusFrame = window.requestAnimationFrame(syncCompactSearch);
    };
    const observer = new IntersectionObserver(
      syncCompactSearch,
      {
        rootMargin: '-75px 0px 0px 0px',
        threshold: 0
      }
    );

    observer.observe(heroSearchEl);
    document.addEventListener('focusin', handleSearchFocus);
    document.addEventListener('focusout', handleSearchFocus);
    return () => {
      observer.disconnect();
      document.removeEventListener('focusin', handleSearchFocus);
      document.removeEventListener('focusout', handleSearchFocus);
      window.cancelAnimationFrame(focusFrame);
    };
  }, [isPropertyRoute, role, location.pathname]);

  // Fetch live properties — resets to page 0 and discards previous results on every call
  const loadLiveProperties = async (filters: RentalSearchFilters) => {
    discoveryAbortRef.current?.abort();
    loadMoreAbortRef.current?.abort();
    const requestId = ++discoveryRequestRef.current;
    const abortController = new AbortController();
    discoveryAbortRef.current = abortController;
    currentDiscoveryPage.current = 0;
    setDiscoveryState('LOADING');
    setDiscoveryError(null);
    setHasMoreProperties(false);
    setLoadMoreError(null);
    try {
      const result = await propertyService.fetchDiscoveryPage(0, filters.sector, filters.city, abortController.signal, filters);
      if (requestId !== discoveryRequestRef.current) return; // stale — discard
      setProperties(result.properties);
      setLoadedDiscoveryKey(discoverySearchKey(filters));
      setHasMoreProperties(result.hasMore);
      currentDiscoveryPage.current = 0;
      setDiscoveryState('READY');
    } catch (err: any) {
      if (abortController.signal.aborted || requestId !== discoveryRequestRef.current) return;
      console.warn('Public property discovery unavailable:', err);
      setProperties([]);
      setLoadedDiscoveryKey(discoverySearchKey(filters));
      setHasMoreProperties(false);
      setDiscoveryState('ERROR');
      setDiscoveryError(err?.message || 'Unable to load properties. Please try again.');
    }
  };

  // Load next page and append — preserves existing properties on failure
  const loadMoreProperties = async (filters: RentalSearchFilters) => {
    if (loadingMore) return; // prevent duplicate concurrent requests
    loadMoreAbortRef.current?.abort();
    const requestId = discoveryRequestRef.current; // must match the active filter session
    const abortController = new AbortController();
    loadMoreAbortRef.current = abortController;
    setLoadingMore(true);
    setLoadMoreError(null);
    const nextPage = currentDiscoveryPage.current + 1;
    try {
      const result = await propertyService.fetchDiscoveryPage(nextPage, filters.sector, filters.city, abortController.signal, filters);
      if (abortController.signal.aborted || requestId !== discoveryRequestRef.current) return; // stale
      setProperties(prev => [...prev, ...result.properties]);
      setHasMoreProperties(result.hasMore);
      currentDiscoveryPage.current = nextPage;
    } catch (err: any) {
      if (abortController.signal.aborted || requestId !== discoveryRequestRef.current) return;
      // Failure: existing properties preserved, retry button shown
      setLoadMoreError(err?.message || 'Unable to load more properties. Please try again.');
    } finally {
      if (loadMoreAbortRef.current === abortController) setLoadingMore(false);
    }
  };

  const handleOpenPropertyDetail = (property: Property) => {
    try {
      const query = new URLSearchParams(location.search);
      sessionStorage.setItem('pathome_discovery_context', JSON.stringify({
        city: query.get('city') || activeDiscoveryCity || 'Indore',
        sector: query.get('sector') || '',
        searchKey: activeDiscoveryKey,
        filterSector: filterSector || '',
        page: currentDiscoveryPage.current,
        properties: properties,
        hasMore: hasMoreProperties,
        scrollY: window.scrollY,
        originPropertyId: property.id
      }));
    } catch (_) {}
    window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
    navigate(`/property/${property.id}`);
  };

  const restoreDiscoveryState = (saved: any): boolean => {
    if (!saved || !Array.isArray(saved.properties) || saved.properties.length === 0) {
      return false;
    }
    setProperties(saved.properties);
    setLoadedDiscoveryKey(saved.searchKey || discoverySearchKey({ city: saved.city || 'Indore', sector: saved.sector || undefined, rentalOnly: false }));
    setHasMoreProperties(Boolean(saved.hasMore));
    currentDiscoveryPage.current = saved.page ?? 0;
    setDiscoveryState('READY');
    setDiscoveryError(null);

    // Polling retry: ensure the target DOM card is rendered before scrolling
    let attempts = 0;
    const maxAttempts = 20; // 20 * 30ms = 600ms
    const interval = setInterval(() => {
      attempts += 1;
      const targetEl = saved.originPropertyId
        ? document.getElementById(`property-card-${saved.originPropertyId}`)
        : null;

      if (targetEl) {
        clearInterval(interval);
        try { sessionStorage.removeItem('pathome_discovery_context'); } catch (_) {}
        targetEl.scrollIntoView({ behavior: 'instant', block: 'center' });
        return;
      }

      if (attempts >= maxAttempts) {
        clearInterval(interval);
        try { sessionStorage.removeItem('pathome_discovery_context'); } catch (_) {}
        if (typeof saved.scrollY === 'number') {
          window.scrollTo({ top: saved.scrollY, behavior: 'instant' });
        }
      }
    }, 30);

    return true;
  };

  // Back-to-discovery restoration effect: monitors route transition from property detail back to discovery
  useEffect(() => {
    const wasPropertyRoute = prevIsPropertyRoute.current;
    prevIsPropertyRoute.current = isPropertyRoute;

    if (wasPropertyRoute && !isPropertyRoute) {
      try {
        const savedRaw = sessionStorage.getItem('pathome_discovery_context');
        if (savedRaw) {
          const saved = JSON.parse(savedRaw);
          const savedKey = saved.searchKey || discoverySearchKey({ city: saved.city || 'Indore', sector: saved.sector || undefined, rentalOnly: false });
          if (savedKey === activeDiscoveryKey) {
            const restored = restoreDiscoveryState(saved);
            if (restored) return;
          }
        }
      } catch (err) {
        console.warn('Failed to restore discovery context on back navigation:', err);
      }
    }
  }, [isPropertyRoute, location.search]);

  useEffect(() => {
    if (isPropertyRoute || isLessorRoute) return;

    // Restore preserved discovery context when returning from property detail
    try {
      const savedRaw = sessionStorage.getItem('pathome_discovery_context');
      if (savedRaw) {
        const saved = JSON.parse(savedRaw);
        const savedKey = saved.searchKey || discoverySearchKey({ city: saved.city || 'Indore', sector: saved.sector || undefined, rentalOnly: false });
        if (savedKey === activeDiscoveryKey) {
          const restored = restoreDiscoveryState(saved);
          if (restored) return;
        }
      }
    } catch (err) {
      console.warn('Failed to restore discovery context:', err);
    }

    loadLiveProperties(activeSearchFilters);

    const handlePropertyPublished = () => {
      loadLiveProperties(activeSearchFilters);
    };
    window.addEventListener('pathome_property_published', handlePropertyPublished);
    return () => {
      window.removeEventListener('pathome_property_published', handlePropertyPublished);
      discoveryAbortRef.current?.abort();
    };
  }, [location.search, isPropertyRoute, isLessorRoute, user?.id]);

  // 1. MULTI-TAB & MULTI-WINDOW CROSS-TAB SESSION SYNCHRONIZATION
  useEffect(() => {
    const handleCrossTabSync = (e: StorageEvent) => {
      if (e.key === 'pathome_user' || e.key === 'pathome_auth_token' || e.key === null) {
        const storedToken = localStorage.getItem('pathome_auth_token');
        // A new token is written before its matching profile. Wait for the user write.
        if (e.key === 'pathome_auth_token' && storedToken) return;
        const storedUser = readPersistedUser();

        if (!storedToken || !storedUser) {
          // LOGOUT IN ANOTHER WINDOW/TAB DETECTED!
          clearUserSession();
          navigate('/', { replace: true });
        } else {
          // LOGIN/ROLE CHANGE IN ANOTHER WINDOW/TAB DETECTED!
          if (storedUser.id !== user?.id) {
            discoveryAbortRef.current?.abort();
            loadMoreAbortRef.current?.abort();
            discoveryRequestRef.current += 1;
            setProperties([]);
            setDiscoveryState('LOADING');
            setHasMoreProperties(false);
            setLoadingMore(false);
            setLoadMoreError(null);
          }
          setPendingVisitProperty(null);
          setUser(storedUser);
          capabilityTracker.clear();
        }
      }
    };

    window.addEventListener('storage', handleCrossTabSync);
    return () => window.removeEventListener('storage', handleCrossTabSync);
  }, [navigate, capabilityTracker, clearUserSession, user?.id]);

  // 1b. SESSION EXPIRATION LISTENER (Triggered by 401 / expired token)
  useEffect(() => {
    const handleSessionExpired = (event: Event) => {
      const requestToken = (event as CustomEvent<{ token?: string }>).detail?.token;
      if (requestToken && requestToken !== localStorage.getItem('pathome_auth_token')) return;
      if (location.pathname.startsWith('/lessor')) {
        try { sessionStorage.setItem('pathome_pending_after_auth', location.pathname); } catch (_) {}
      }
      clearUserSession();
      if (!location.pathname.startsWith('/lessor')) navigate('/', { replace: true });
      setShowAuthModal(true);
    };

    window.addEventListener('pathome_session_expired', handleSessionExpired);
    return () => window.removeEventListener('pathome_session_expired', handleSessionExpired);
  }, [navigate, location.pathname, clearUserSession]);

  // 1c. AUTHORITATIVE LESSOR CAPABILITY SYNCHRONIZATION
  useEffect(() => {
    const syncCapability = () => {
      const identity = readLessorSessionIdentity();
      const eligibleRole = ['TENANT', 'LANDLORD', 'ROLE_LANDLORD'].includes(role);
      if (!user || !eligibleRole || !identity || identity.userId !== user.id) {
        capabilityTracker.clear();
        return;
      }
      void capabilityTracker.refresh(identity, () => lessorCapabilityService.get());
    };

    syncCapability();
    window.addEventListener('pathome_auth_changed', syncCapability);
    window.addEventListener('storage', syncCapability);
    return () => {
      capabilityTracker.cancel();
      window.removeEventListener('pathome_auth_changed', syncCapability);
      window.removeEventListener('storage', syncCapability);
    };
  }, [user?.id, role, capabilityTracker]);

  useEffect(() => {
    let live = true;
    let revision = 0;
    const refreshDraftCount = () => {
      const currentRevision = ++revision;
      setDraftSnapshot({ key: draftSessionKey, count: 0, state: 'loading' });
      void (async () => {
        try {
          if (role === 'GUEST') {
            const localDraftId = localStorage.getItem('pathome_guest_draft_id');
            if (!localDraftId) {
              if (live && currentRevision === revision) setDraftSnapshot({ key: draftSessionKey, count: 0, state: 'ready' });
              return;
            }
            const draft = await lessorDraftService.resumeGuest();
            const count = getGuestResumableDraftCount(localDraftId, draft);
            if (live && currentRevision === revision) setDraftSnapshot({ key: draftSessionKey, count, state: 'ready' });
            return;
          }

          if (!user || !['TENANT', 'LANDLORD', 'ROLE_LANDLORD'].includes(role)) {
            if (live && currentRevision === revision) setDraftSnapshot({ key: draftSessionKey, count: 0, state: 'ready' });
            return;
          }
          const identity = readLessorSessionIdentity();
          if (!identity || identity.userId !== user.id) throw new Error('Session identity changed');
          const page = await lessorDraftService.list(0);
          const count = readResumableDraftCount(page.totalCount);
          if (count === null) throw new Error('Draft count unavailable');
          if (live && currentRevision === revision && isCurrentLessorSession(identity)) {
            setDraftSnapshot({ key: draftSessionKey, count, state: 'ready' });
          }
        } catch {
          if (live && currentRevision === revision &&
              (role === 'GUEST' || readLessorSessionIdentity()?.key === draftSessionKey)) {
            setDraftSnapshot({ key: draftSessionKey, count: 0, state: 'error' });
          }
        }
      })();
    };

    refreshDraftCount();
    window.addEventListener('pathome_lessor_drafts_changed', refreshDraftCount);
    window.addEventListener('pathome_auth_changed', refreshDraftCount);
    return () => {
      live = false;
      window.removeEventListener('pathome_lessor_drafts_changed', refreshDraftCount);
      window.removeEventListener('pathome_auth_changed', refreshDraftCount);
    };
  }, [draftSessionKey, role, user?.id]);

  // 2. STRICT PROTECTED ROUTE GUARDS & PATH SYNCHRONIZATION
  useEffect(() => {
    const path = location.pathname.toLowerCase();
    const isProtectedRoute = path === '/tenant' || path === '/admin' || path === '/crm';
    if (!isProtectedRoute) logoutInProgressRef.current = false;
    const hasToken = typeof window !== 'undefined' && !!localStorage.getItem('pathome_auth_token');

    // GUARD CHECK 1: If user is logged out or lacks auth token on protected route
    if (!user || role === 'GUEST' || (isProtectedRoute && !hasToken)) {
      if (isProtectedRoute) {
        if (logoutInProgressRef.current) return;
        if (path.startsWith('/lessor')) {
          try { sessionStorage.setItem('pathome_pending_after_auth', location.pathname); } catch (_) {}
        }
        // BLOCK ACCESS! Redirect to landing page & prompt login modal
        clearUserSession();
        navigate('/', { replace: true });
        setShowAuthModal(true);
      }
      return;
    }

    // GUARD CHECK 2: Logged-in user role routing enforcement
    if (user) {
      localStorage.setItem('pathome_role', role);
      localStorage.setItem('pathome_user', JSON.stringify(user));

      if (isPropertyRoute) return;
      if (role === 'TENANT' && path !== '/tenant' && !path.startsWith('/lessor')) {
        navigate('/tenant', { replace: true });
      } else if (role === 'EMPLOYEE' && path !== '/crm') {
        navigate('/crm', { replace: true });
      } else if ((role === 'ADMIN' || role === 'SUPER_ADMIN' || role === 'SUB_ADMIN') && path !== '/admin' && path !== '/crm') {
        navigate('/admin', { replace: true });
      }
    }
  }, [location.pathname, user, role, navigate, isPropertyRoute, clearUserSession]);

  const handleDiscoverySearch = (city?: string, sector?: string, search?: Pick<RentalSearchFilters, 'q' | 'bhk' | 'propertyType' | 'furnishing' | 'minRent' | 'maxRent' | 'rentalOnly'>) => {
    focusResultsAfterSearch.current = true;
    const query = new URLSearchParams();
    const effectiveCity = city || undefined;
    if (effectiveCity) query.set('city', effectiveCity);
    if (sector && sector !== 'ALL' && sector !== 'All Localities') {
      query.set('sector', sector);
    }
    if (search?.q) query.set('q', search.q);
    if (search?.bhk) query.set('bhk', search.bhk);
    if (search?.propertyType) query.set('propertyType', search.propertyType);
    if (search?.furnishing) query.set('furnishing', search.furnishing);
    if (search?.minRent) query.set('minRent', String(search.minRent));
    if (search?.maxRent) query.set('maxRent', String(search.maxRent));
    if (search?.rentalOnly) query.set('rentalOnly', 'true');
    const nextFilters: RentalSearchFilters = {
      city: effectiveCity,
      sector: query.get('sector') || undefined,
      q: search?.q,
      bhk: search?.bhk,
      propertyType: search?.propertyType,
      furnishing: search?.furnishing,
      minRent: search?.minRent,
      maxRent: search?.maxRent,
      rentalOnly: Boolean(search?.rentalOnly)
    };
    const sameCriteria = discoverySearchKey(nextFilters) === activeDiscoveryKey;
    if (sameCriteria && discoveryState === 'READY') {
      focusResultsAfterSearch.current = false;
      window.requestAnimationFrame(() => document.getElementById('discovery-results-heading')?.focus({ preventScroll: true }));
    } else if (sameCriteria && discoveryState === 'ERROR') {
      loadLiveProperties(nextFilters);
    }
    navigate(`/?${query.toString()}#listings`);
    const listingsElement = document.getElementById('listings');
    if (listingsElement) {
      listingsElement.scrollIntoView({
        behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth',
        block: 'start'
      });
    }
  };

  useEffect(() => {
    if (!focusResultsAfterSearch.current || loadedDiscoveryKey !== activeDiscoveryKey || discoveryState === 'LOADING') return;
    focusResultsAfterSearch.current = false;
    window.requestAnimationFrame(() => {
      document.getElementById(discoveryState === 'ERROR' ? 'discovery-results-error' : 'discovery-results-heading')?.focus({ preventScroll: true });
    });
  }, [activeDiscoveryKey, loadedDiscoveryKey, discoveryState]);

  const handleSaveFavorite = (property: Property) => {
    if (role === 'GUEST' || !user) {
      try {
        sessionStorage.setItem('pathome_pending_favorite_property_id', String(property.id));
      } catch (_) {}
      setShowAuthModal(true);
      notifySuccess('Sign in to save properties', 'Create or log into your Pathome account to save your favorite homes.');
      return;
    }
    try {
      const existing = JSON.parse(sessionStorage.getItem('pathome_session_saved_properties') || '[]');
      if (!existing.includes(property.id)) {
        sessionStorage.setItem('pathome_session_saved_properties', JSON.stringify([...existing, property.id]));
      }
    } catch (_) {}
    notifySuccess('Saved for this session', `"${property.title}" is saved for your current session.`);
  };

  const handleRequestVisit = (property: Property) => {
    setPendingVisitProperty(property);
    if (role !== 'TENANT' || !user) {
      setShowAuthModal(true);
    }
  };

  const handleLoginSuccess = (userProfile: UserProfile) => {
    localStorage.setItem('pathome_role', userProfile.role);
    localStorage.setItem('pathome_user', JSON.stringify(userProfile));
    setUser(userProfile);
    capabilityTracker.clear();
    window.dispatchEvent(new Event('pathome_auth_changed'));
    setShowAuthModal(false);
    setLessorAuthContext(null);
    if (userProfile.role === 'TENANT' && location.pathname.startsWith('/lessor/') &&
        (sessionStorage.getItem('pathome_guest_submit_draft') || sessionStorage.getItem('pathome_guest_save_draft'))) return;
    const pendingLessor = sessionStorage.getItem('pathome_pending_after_auth');
    if (pendingLessor?.startsWith('/lessor') && userProfile.role === 'TENANT') {
      sessionStorage.removeItem('pathome_pending_after_auth');
      navigate(pendingLessor);
      return;
    }
    if (pendingVisitProperty && userProfile.role === 'TENANT') {
      navigate(`/property/${pendingVisitProperty.id}`);
      return;
    }
    let targetPath = '/tenant';
    if (userProfile.role === 'EMPLOYEE') targetPath = '/crm';
    else if (userProfile.role === 'SUB_ADMIN' || userProfile.role === 'SUPER_ADMIN' || userProfile.role === 'ADMIN') targetPath = '/admin';
    navigate(targetPath);
  };

  const handleLogout = () => {
    logoutInProgressRef.current = true;
    clearUserSession();
    setShowAuthModal(false);
    sessionStorage.removeItem('pathome_pending_after_auth');
    navigate('/');
  };

  const lessorWorkspace = isLessorRoute && (role === 'GUEST' || role === 'TENANT' || hasLessorCapability === true) ? (
    <LessorWorkspace
      key={draftSessionKey}
      user={role === 'GUEST' ? null : user}
      hasLessorCapability={hasLessorCapability}
      draftCount={currentDraftCount}
      draftState={currentDraftState}
      onRetryDrafts={retryDraftCheck}
      onRequestAuth={requestLessorAuth}
    />
  ) : null;

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900 flex flex-col font-['Inter',sans-serif]">
      <PathomeRouteShell pathname={normalizedPathname} focused={lessorWorkspace} normal={<>
        <Navbar
          user={user}
          role={role}
          hasLessorCapability={hasLessorCapability}
          draftCount={currentDraftCount}
          draftState={currentDraftState}
          onOpenDrafts={openDrafts}
          onRetryDrafts={retryDraftCheck}
          onOpenAuthModal={() => setShowAuthModal(true)}
          onLogout={handleLogout}
          onOpenPostProperty={openPostProperty}
          postPropertyModalOpen={showPostPropertyModal}
          onClosePostProperty={closePostProperty}
          activeAdminTab={activeAdminTab}
          setActiveAdminTab={setActiveAdminTab}
          isLandingHero={!isPropertyRoute && !isLessorRoute && role === 'GUEST'}
        />

      {isPropertyRoute && (
        <PublicPropertyDetail propertyId={publicPropertyId} onRequestVisit={handleRequestVisit} />
      )}

      {lessorWorkspace}

      {!isLessorRoute && <>
      {/* DEDICATED EMPLOYEE CRM DASHBOARD */}
        {!isPropertyRoute && !isLessorRoute && role === 'EMPLOYEE' && (
          <motion.div 
            key="employee-crm-view"
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -16 }}
            transition={{ duration: 0.4, ease: [0.16, 1, 0.3, 1] }}
          >
            <EmployeeCrmDashboard user={user} />
          </motion.div>
        )}

      {/* MASTER ADMIN CONSOLE & SUB-ADMIN OVERLAY */}
        {!isPropertyRoute && !isLessorRoute && (role === 'ADMIN' || role === 'SUPER_ADMIN' || role === 'SUB_ADMIN') && (
          <motion.div 
            key="master-admin-view"
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -16 }}
            transition={{ duration: 0.4, ease: [0.16, 1, 0.3, 1] }}
          >
            <MasterAdminDashboard activeTab={activeAdminTab} setActiveAdminTab={setActiveAdminTab} />
          </motion.div>
        )}

      {/* LOGGED IN TENANT DASHBOARD VIEW vs GUEST HOMEPAGE VIEW */}
        {!isPropertyRoute && !isLessorRoute && role === 'TENANT' && user ? (
          <motion.div
            key={`tenant-dashboard-${draftSessionKey}`}
            initial={false}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, transition: { duration: 0 } }}
            transition={{ duration: reduceMotion ? 0 : 0.2 }}
          >
            <TenantDashboard
              user={user}
              properties={properties}
              discoveryState={discoveryState}
              discoveryCity={activeDiscoveryCity}
              discoveryQuery={activeSearchFilters.q || ''}
              searchFilters={activeSearchFilters}
              hasMoreProperties={hasMoreProperties}
              loadingMoreProperties={loadingMore}
              loadMorePropertiesError={loadMoreError}
              onSearchHomes={filters => {
                const params = new URLSearchParams();
                if (filters.city) params.set('city', filters.city);
                if (filters.sector) params.set('sector', filters.sector);
                if (filters.q) params.set('q', filters.q);
                if (filters.bhk) params.set('bhk', filters.bhk);
                if (filters.propertyType) params.set('propertyType', filters.propertyType);
                if (filters.furnishing) params.set('furnishing', filters.furnishing);
                if (filters.minRent) params.set('minRent', String(filters.minRent));
                if (filters.maxRent) params.set('maxRent', String(filters.maxRent));
                if (filters.rentalOnly) params.set('rentalOnly', 'true');
                navigate(`/tenant?${params.toString()}`);
              }}
              onRetryDiscovery={() => loadLiveProperties(activeSearchFilters)}
              onLoadMoreProperties={() => loadMoreProperties(activeSearchFilters)}
              onRequestVisit={handleRequestVisit}
              onViewProperty={handleOpenPropertyDetail}
            />
          </motion.div>
        ) : !isPropertyRoute && !isLessorRoute && role === 'GUEST' && (
          <motion.div
            key="guest-homepage"
            initial={reduceMotion ? false : { opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            exit={reduceMotion ? undefined : { opacity: 0, y: -16 }}
            transition={{ duration: reduceMotion ? 0 : 0.4, ease: [0.16, 1, 0.3, 1] }}
          >
            {/* SECTION 1: HERO SEARCH HUB */}
            <div id="hero" className="relative z-30 focus-within:z-50 -mt-[calc(61px+env(safe-area-inset-top))] sm:-mt-[calc(70.5px+env(safe-area-inset-top))] lg:-mt-[calc(74.5px+env(safe-area-inset-top))]">
              <HeroSection
                onSearch={handleDiscoverySearch}
                onOpenPostProperty={openPostProperty}
                selectedCity={activeDiscoveryCity}
                selectedSector={filterSector}
                selectedQuery={activeSearchFilters.q}
                selectedBhk={activeSearchFilters.bhk}
                selectedPropertyType={activeSearchFilters.propertyType}
                selectedFurnishing={activeSearchFilters.furnishing}
                selectedMinRent={activeSearchFilters.minRent}
                selectedMaxRent={activeSearchFilters.maxRent}
                refineRequest={refineSearchRequest}
              />
            </div>


            {/* Sticky Compact Search / Location Context (Task 2) */}
            <AnimatePresence>
              {showCompactSearch && (
                <motion.div
                  key="compact-search-wrapper"
                  initial={reduceMotion ? false : { opacity: 0, y: -10 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={reduceMotion ? undefined : { opacity: 0, y: -10 }}
                  transition={{ duration: reduceMotion ? 0 : 0.2, ease: [0.16, 1, 0.3, 1] }}
                  className="fixed top-[calc(69px+env(safe-area-inset-top))] sm:top-[calc(80px+env(safe-area-inset-top))] lg:top-[calc(88px+env(safe-area-inset-top))] inset-x-0 z-[90] px-3 sm:px-6 pointer-events-none"
                >
                  <div className="pointer-events-auto">
                    <CompactSearchContext
                      city={activeDiscoveryCity}
                      filters={activeSearchFilters}
                      onSearch={handleDiscoverySearch}
                      onManualCityChange={(newCity) => {
                        const clean = resetFiltersForManualCityChange(newCity);
                        handleDiscoverySearch(clean.city, undefined, clean);
                      }}
                      onClearAll={() => {
                        const clean = resetFiltersForSearchClear(activeDiscoveryCity);
                        handleDiscoverySearch(clean.city, undefined, clean);
                      }}
                    />
                  </div>
                </motion.div>
              )}
            </AnimatePresence>

            {/* SECTION 3: FEATURED PROPERTIES GRID */}
            <div id="listings" className="scroll-mt-36">
              <PropertyShowcase
                properties={properties}
                isLoading={discoveryState === 'LOADING' || loadedDiscoveryKey !== activeDiscoveryKey}
                error={discoveryState === 'ERROR' ? discoveryError : null}
                onRetry={() => {
                  loadLiveProperties(activeSearchFilters);
                }}
                onClearLocality={() => handleDiscoverySearch(activeDiscoveryCity, undefined)}
                onViewDetails={handleOpenPropertyDetail}
                onRefineSearch={() => setRefineSearchRequest((request) => request + 1)}
                onOpenMediaModal={handleOpenPropertyDetail}
                selectedSectorFilter={filterSector}
                selectedCityFilter={activeDiscoveryCity}
                onSaveFavorite={handleSaveFavorite}
              />
              {/* Single Authoritative Show More Properties CTA */}
              {discoveryState === 'READY' && loadedDiscoveryKey === activeDiscoveryKey && properties.length > 0 && (hasMoreProperties || loadMoreError) && (
                <div className="mx-auto flex max-w-7xl flex-col items-center gap-3 px-4 pb-8 pt-2">
                  {loadMoreError && (
                    <p role="alert" className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{loadMoreError}</p>
                  )}
                  {(hasMoreProperties || loadMoreError) && (
                    <button
                      type="button"
                      disabled={loadingMore}
                      onClick={() => {
                        loadMoreProperties(activeSearchFilters);
                      }}
                      className="group relative inline-flex min-h-11 items-center justify-center gap-2 rounded-2xl border border-emerald-600/35 bg-white px-8 py-3 text-sm font-extrabold text-emerald-700 shadow-xs transition-all duration-200 hover:-translate-y-0.5 hover:border-emerald-600/70 hover:bg-emerald-50/90 hover:shadow-md active:translate-y-0 active:scale-[0.98] active:shadow-2xs focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:translate-y-0 disabled:hover:shadow-xs"
                      aria-label={loadingMore ? "Loading more properties" : loadMoreError ? "Retry loading properties" : "Show More Properties"}
                    >
                      {loadingMore ? (
                        <>
                          <LoaderCircle className="h-4 w-4 animate-spin text-emerald-600" />
                          <span>Loading more properties…</span>
                        </>
                      ) : loadMoreError ? (
                        <span>Retry</span>
                      ) : (
                        <>
                          <span>Show More Properties</span>
                          <ChevronDown className="h-4 w-4 text-emerald-600 transition-transform duration-200 group-hover:translate-y-0.5" />
                        </>
                      )}
                    </button>
                  )}
                </div>
              )}
              {discoveryState === 'READY' && loadedDiscoveryKey === activeDiscoveryKey && !hasMoreProperties && properties.length > 0 && !loadMoreError && (
                <div className="mx-auto max-w-7xl px-4 py-6 text-center">
                  <p className="text-xs font-semibold text-slate-400">You've seen all available properties.</p>
                </div>
              )}
            </div>

            {/* SECTION 4: 3-STEP HOW IT WORKS */}
            <div>
              <HowItWorks />
            </div>

            {/* SECTION 5: STRATEGIC USP ACCORDION BAR */}
            <div id="why-us">
              <ValueBanner />
            </div>

            {/* SECTION 6: PLOTS & LAND EXPANSION BANNER */}
            <div id="land-plots">
              <FutureExpansion />
            </div>

            {/* SECTION 7: LANDING PAGE FOOTER */}
            <Footer onSelectSector={handleDiscoverySearch} />

            {/* FLOATING BACK TO TOP CONTROL */}
            <AnimatePresence>
              {showBackToTop && (
                <motion.button
                  initial={reduceMotion ? false : { opacity: 0, scale: 0.8, y: 12 }}
                  animate={{ opacity: 1, scale: 1, y: 0 }}
                  exit={reduceMotion ? undefined : { opacity: 0, scale: 0.8, y: 12 }}
                  transition={{ duration: reduceMotion ? 0 : 0.25, ease: [0.16, 1, 0.3, 1] }}
                  type="button"
                  aria-label="Back to top of listings"
                  title="Back to top"
                  onClick={() => {
                    const listingsEl = document.getElementById('listings');
                    if (listingsEl) {
                      listingsEl.scrollIntoView({ behavior: reduceMotion ? 'instant' : 'smooth', block: 'start' });
                    } else {
                      window.scrollTo({ top: 0, behavior: reduceMotion ? 'instant' : 'smooth' });
                    }
                  }}
                  className="fixed bottom-6 right-4 z-30 hidden h-11 w-11 items-center justify-center rounded-full border border-slate-200/90 bg-white/95 text-slate-700 shadow-xl backdrop-blur-md transition-all hover:border-emerald-500/50 hover:bg-emerald-50 hover:text-emerald-700 active:scale-95 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 md:bottom-8 md:right-4 md:flex lg:bottom-8 lg:right-4 xl:right-6 min-[1380px]:right-[max(1.5rem,calc((100vw-80rem)/2-3.75rem))]"
                >
                  <ArrowUp className="h-5 w-5" />
                </motion.button>
              )}
            </AnimatePresence>
          </motion.div>
        )}

      </>}

      </>} />

      {/* AUTH MODAL */}
      <AuthModal
        isOpen={showAuthModal}
        onClose={() => { setShowAuthModal(false); setLessorAuthContext(null); sessionStorage.removeItem('pathome_guest_submit_draft'); sessionStorage.removeItem('pathome_guest_save_draft'); }}
        onSuccess={handleLoginSuccess}
        lessorContext={lessorAuthContext}
      />

      {/* LEASE UPLOAD MODAL */}
      <LeaseUploadModal
        isOpen={false}
        onClose={() => undefined}
      />

      <VisitRequestModal
        property={pendingVisitProperty}
        isOpen={role === 'TENANT' && !!pendingVisitProperty}
        onClose={() => setPendingVisitProperty(null)}
      />

    </div>
  );
};
