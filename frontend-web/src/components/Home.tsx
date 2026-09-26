import React, { useState, useEffect, useRef } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { Property, UserProfile, UserRole } from '../types';
import { Navbar } from './Navbar';
import { HeroSection } from './HeroSection';
import { PropertyShowcase } from './PropertyShowcase';
import { ValueBanner } from './ValueBanner';
import { FutureExpansion } from './FutureExpansion';
import { TrustStatsBar } from './TrustStatsBar';
import { HowItWorks } from './HowItWorks';
import { Footer } from './Footer';
import { AuthModal } from './AuthModal';
import { LeaseUploadModal } from './LeaseUploadModal';
import { TenantDashboard } from './TenantDashboard';
import { CreditCard, ArrowUp, ChevronDown, LoaderCircle, MapPin } from 'lucide-react';
import { PublicPropertyDetail } from './PublicPropertyDetail';
import { VisitRequestModal } from './VisitRequestModal';


import { MasterAdminDashboard } from './MasterAdminDashboard';
import { EmployeeCrmDashboard } from './EmployeeCrmDashboard';
import { propertyService } from '../services/propertyService';
import { useNotification } from '../context/NotificationContext';
import { discoverySearchKey, parseRentalFurnishing, parseRentalPropertyType, parseRentFilter, RentalSearchFilters } from '../utils/rentalSearch';

const getInitialSession = (): { role: UserRole; user: UserProfile | null } => {
  try {
    const savedToken = localStorage.getItem('pathome_auth_token');
    const savedRole = localStorage.getItem('pathome_role') as UserRole | null;
    const savedUserStr = localStorage.getItem('pathome_user');
    if (savedToken && savedRole && savedUserStr) {
      const parsedUser = JSON.parse(savedUserStr);
      return { role: savedRole, user: parsedUser };
    }
  } catch (err) {
    console.error('Failed to load session from localStorage', err);
  }
  return { role: 'GUEST', user: null };
};

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

  const initialSession = getInitialSession();
  const [role, setRole] = useState<UserRole>(initialSession.role);
  const [user, setUser] = useState<UserProfile | null>(initialSession.user);
  const [properties, setProperties] = useState<Property[]>([]);
  const [discoveryState, setDiscoveryState] = useState<'LOADING' | 'READY' | 'ERROR'>('LOADING');
  const [loadedDiscoveryKey, setLoadedDiscoveryKey] = useState<string | null>(null);
  const [discoveryError, setDiscoveryError] = useState<string | null>(null);
  const [hasMoreProperties, setHasMoreProperties] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);
  const currentDiscoveryPage = useRef(0);
  const [showAuthModal, setShowAuthModal] = useState(false);
  const [showLeaseModal, setShowLeaseModal] = useState(false);
  const [showDepositModal, setShowDepositModal] = useState(false);
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
  const activeDiscoveryCity = currentSearchParams.get('city')
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
  const [pendingVisitProperty, setPendingVisitProperty] = useState<Property | null>(null);
  const discoveryRequestRef = useRef(0);
  const focusResultsAfterSearch = useRef(false);
  const discoveryAbortRef = useRef<AbortController | null>(null);
  const loadMoreAbortRef = useRef<AbortController | null>(null);
  const isPropertyRoute = location.pathname.startsWith('/property/');
  const isPublicPropertyRoute = /^\/property\/\d+$/.test(location.pathname);
  const publicPropertyId = isPublicPropertyRoute ? Number(location.pathname.split('/').pop()) : null;

  const [showBackToTop, setShowBackToTop] = useState(false);
  const prevIsPropertyRoute = useRef(isPropertyRoute);

  useEffect(() => {
    const handleScroll = () => {
      setShowBackToTop(window.scrollY > 450);
    };
    window.addEventListener('scroll', handleScroll, { passive: true });
    return () => window.removeEventListener('scroll', handleScroll);
  }, []);

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
      setLoadingMore(false);
    }
  };

  const handleOpenPropertyDetail = (property: Property) => {
    try {
      const query = new URLSearchParams(location.search);
      sessionStorage.setItem('pathome_discovery_context', JSON.stringify({
        city: query.get('city') || 'Indore',
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
    if (isPropertyRoute) return;

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
  }, [location.search, isPropertyRoute]);

  // 1. MULTI-TAB & MULTI-WINDOW CROSS-TAB SESSION SYNCHRONIZATION
  useEffect(() => {
    const handleCrossTabSync = (e: StorageEvent) => {
      if (e.key === 'pathome_user' || e.key === 'pathome_role' || e.key === null) {
        const storedUser = localStorage.getItem('pathome_user');
        const storedRole = localStorage.getItem('pathome_role');

        if (!storedUser || !storedRole) {
          // LOGOUT IN ANOTHER WINDOW/TAB DETECTED!
          setUser(null);
          setRole('GUEST');
          navigate('/', { replace: true });
          setShowAuthModal(true);
        } else {
          // LOGIN/ROLE CHANGE IN ANOTHER WINDOW/TAB DETECTED!
          try {
            const parsedUser = JSON.parse(storedUser);
            setUser(parsedUser);
            setRole(storedRole as UserRole);
          } catch (err) {
            console.error('Cross-tab session sync error', err);
          }
        }
      }
    };

    window.addEventListener('storage', handleCrossTabSync);
    return () => window.removeEventListener('storage', handleCrossTabSync);
  }, [navigate]);

  // 1b. SESSION EXPIRATION LISTENER (Triggered by 401 / expired token)
  useEffect(() => {
    const handleSessionExpired = () => {
      setRole('GUEST');
      setUser(null);
      localStorage.removeItem('pathome_role');
      localStorage.removeItem('pathome_user');
      localStorage.removeItem('pathome_auth_token');
      navigate('/', { replace: true });
      setShowAuthModal(true);
    };

    window.addEventListener('pathome_session_expired', handleSessionExpired);
    return () => window.removeEventListener('pathome_session_expired', handleSessionExpired);
  }, [navigate]);

  // 2. STRICT PROTECTED ROUTE GUARDS & PATH SYNCHRONIZATION
  useEffect(() => {
    const path = location.pathname.toLowerCase();
    const isProtectedRoute = path === '/tenant' || path === '/admin' || path === '/crm';
    const hasToken = typeof window !== 'undefined' && !!localStorage.getItem('pathome_auth_token');

    // GUARD CHECK 1: If user is logged out or lacks auth token on protected route
    if (!user || role === 'GUEST' || (isProtectedRoute && !hasToken)) {
      if (isProtectedRoute) {
        // BLOCK ACCESS! Redirect to landing page & prompt login modal
        setRole('GUEST');
        setUser(null);
        localStorage.removeItem('pathome_role');
        localStorage.removeItem('pathome_user');
        localStorage.removeItem('pathome_auth_token');
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
      if (role === 'TENANT' && path !== '/tenant') {
        navigate('/tenant', { replace: true });
      } else if (role === 'EMPLOYEE' && path !== '/crm') {
        navigate('/crm', { replace: true });
      } else if ((role === 'ADMIN' || role === 'SUPER_ADMIN' || role === 'SUB_ADMIN') && path !== '/admin' && path !== '/crm') {
        navigate('/admin', { replace: true });
      }
    }
  }, [location.pathname, user, role, navigate, isPropertyRoute]);

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

  const handleBookTour = (property: Property) => {
    if (role === 'GUEST') {
      setShowAuthModal(true);
      return;
    }

    const currentVisits = user?.freeVisitsUsed || 0;
    if (currentVisits >= 5) {
      setShowDepositModal(true);
    } else {
      const updatedVisits = currentVisits + 1;
      setUser(prev => prev ? { ...prev, freeVisitsUsed: updatedVisits } : null);
      notifySuccess('Property tour requested', `Your visit pass ${updatedVisits} of 5 is active for ${property.title}.`, 'An on-site escort will be assigned before your visit.');
    }
  };

  const handleLoginSuccess = (userProfile: UserProfile) => {
    setUser(userProfile);
    setRole(userProfile.role);
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
    localStorage.removeItem('pathome_role');
    localStorage.removeItem('pathome_user');
    localStorage.removeItem('pathome_auth_token');
    localStorage.removeItem('pathome_active_admin_tab');
    setUser(null);
    setRole('GUEST');
    navigate('/');
  };

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900 flex flex-col font-['Inter',sans-serif]">
      
      {/* 1. Dynamic Role-Based Sticky Navbar Component */}
      <Navbar
        user={user}
        role={role}
        onOpenAuthModal={() => setShowAuthModal(true)}
        onOpenLeaseUpload={() => setShowLeaseModal(true)}
        onLogout={handleLogout}
        activeAdminTab={activeAdminTab}
        setActiveAdminTab={setActiveAdminTab}
        isLandingHero={!isPropertyRoute && role === 'GUEST'}
      />

      {isPropertyRoute && (
        <PublicPropertyDetail propertyId={publicPropertyId} onRequestVisit={handleRequestVisit} />
      )}

      {/* DEDICATED EMPLOYEE CRM DASHBOARD */}
      <AnimatePresence mode="wait">
        {!isPropertyRoute && role === 'EMPLOYEE' && (
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
      </AnimatePresence>

      {/* MASTER ADMIN CONSOLE & SUB-ADMIN OVERLAY */}
      <AnimatePresence mode="wait">
        {!isPropertyRoute && (role === 'ADMIN' || role === 'SUPER_ADMIN' || role === 'SUB_ADMIN') && (
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
      </AnimatePresence>

      {/* LOGGED IN TENANT DASHBOARD VIEW vs GUEST HOMEPAGE VIEW */}
      <AnimatePresence mode="wait">
        {!isPropertyRoute && role === 'TENANT' && user ? (
          <motion.div
            key="tenant-dashboard"
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -16 }}
            transition={{ duration: 0.4, ease: [0.16, 1, 0.3, 1] }}
          >
            <TenantDashboard
              user={user}
              properties={properties}
              onBookTour={handleBookTour}
              onOpenLeaseUpload={() => setShowLeaseModal(true)}
            />
          </motion.div>
        ) : !isPropertyRoute && role === 'GUEST' && (
          <motion.div
            key="guest-homepage"
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -16 }}
            transition={{ duration: 0.4, ease: [0.16, 1, 0.3, 1] }}
          >
            {/* SECTION 1: HERO SEARCH HUB */}
            <div id="hero" className="relative z-30 -mt-[74.5px]">
              <HeroSection
                onSearch={handleDiscoverySearch}
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

            {/* SECTION 2: LIVE TRUST & STATS BAR */}
            <motion.div
              className="relative z-10"
              initial={{ opacity: 0, y: 24 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true, margin: "-60px" }}
              transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
            >
              <TrustStatsBar />
            </motion.div>

            {/* SECTION 3: FEATURED PROPERTIES GRID */}
            <div id="listings" className="scroll-mt-20">
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
            <motion.div
              initial={{ opacity: 0, y: 24 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true, margin: "-60px" }}
              transition={{ duration: 0.55, ease: [0.16, 1, 0.3, 1] }}
            >
              <HowItWorks />
            </motion.div>

            {/* SECTION 5: STRATEGIC USP ACCORDION BAR */}
            <motion.div
              id="why-us"
              initial={{ opacity: 0, y: 24 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true, margin: "-60px" }}
              transition={{ duration: 0.55, ease: [0.16, 1, 0.3, 1] }}
            >
              <ValueBanner />
            </motion.div>

            {/* SECTION 6: PLOTS & LAND EXPANSION BANNER */}
            <motion.div
              id="land-plots"
              initial={{ opacity: 0, y: 24 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true, margin: "-60px" }}
              transition={{ duration: 0.55, ease: [0.16, 1, 0.3, 1] }}
            >
              <FutureExpansion />
            </motion.div>

            {/* SECTION 7: LANDING PAGE FOOTER */}
            <Footer onSelectSector={handleDiscoverySearch} />

            {/* FLOATING BACK TO TOP CONTROL */}
            <AnimatePresence>
              {showBackToTop && (
                <motion.button
                  initial={{ opacity: 0, scale: 0.8, y: 12 }}
                  animate={{ opacity: 1, scale: 1, y: 0 }}
                  exit={{ opacity: 0, scale: 0.8, y: 12 }}
                  transition={{ duration: 0.25, ease: [0.16, 1, 0.3, 1] }}
                  type="button"
                  aria-label="Back to top of listings"
                  title="Back to top"
                  onClick={() => {
                    const listingsEl = document.getElementById('listings');
                    if (listingsEl) {
                      listingsEl.scrollIntoView({ behavior: 'smooth', block: 'start' });
                    } else {
                      window.scrollTo({ top: 0, behavior: 'smooth' });
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
      </AnimatePresence>

      {/* AUTH MODAL */}
      <AuthModal
        isOpen={showAuthModal}
        onClose={() => setShowAuthModal(false)}
        onSuccess={handleLoginSuccess}
      />

      {/* LEASE CASHBACK UPLOAD MODAL */}
      <LeaseUploadModal
        isOpen={showLeaseModal}
        onClose={() => setShowLeaseModal(false)}
      />

      {/* 6th VISIT COMMITMENT PAYWALL MODAL */}
      <AnimatePresence>
        {showDepositModal && (
          <motion.div 
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.25 }}
            onClick={() => setShowDepositModal(false)}
            className="fixed inset-0 z-50 bg-slate-950/65 backdrop-blur-md flex items-center justify-center p-4"
          >
            <motion.div
              initial={{ opacity: 0, scale: 0.92, y: 16 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.92, y: 16 }}
              transition={{ type: 'spring', stiffness: 420, damping: 28 }}
              onClick={(e) => e.stopPropagation()}
              className="bg-white max-w-md w-full rounded-3xl p-6 sm:p-8 shadow-2xl border border-slate-200/90 relative text-center"
            >
              <button
                onClick={() => setShowDepositModal(false)}
                className="absolute top-5 right-5 text-slate-400 hover:text-slate-700 p-1.5 rounded-full hover:bg-slate-100 transition-colors"
              >
                ✕
              </button>
              <div className="w-12 h-12 bg-amber-50 text-amber-700 rounded-2xl flex items-center justify-center mx-auto mb-3 border border-amber-200 shadow-xs">
                <CreditCard className="w-6 h-6 text-amber-600" />
              </div>
              <h3 className="text-2xl font-bold text-slate-900 font-['Outfit'] mb-1">
                6th Visit Commitment Lock
              </h3>
              <p className="text-xs text-slate-600 mb-5 leading-relaxed">
                You have completed 5 free property passes. The 6th visit requires a refundable ₹100 commitment deposit via UPI.
              </p>
              <motion.button
                whileHover={{ scale: 1.02, y: -1 }}
                whileTap={{ scale: 0.97 }}
                transition={{ type: 'spring', stiffness: 450, damping: 25 }}
                onClick={() => {
                  notifySuccess('Visit pass activated', 'Your additional visit pass is now active.');
                  setShowDepositModal(false);
                }}
                className="w-full bg-emerald-600 hover:bg-emerald-700 text-white font-bold py-3.5 rounded-xl text-xs shadow-md shadow-emerald-600/20 transition-all"
              >
                Pay ₹100 via UPI & Unlock Tour Pass
              </motion.button>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>

      <VisitRequestModal
        property={pendingVisitProperty}
        isOpen={role === 'TENANT' && !!pendingVisitProperty}
        onClose={() => setPendingVisitProperty(null)}
      />

    </div>
  );
};
