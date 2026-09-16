import React, { useState, useEffect } from 'react';
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
import { VideoPlayerModal } from './VideoPlayerModal';
import { CreditCard } from 'lucide-react';

import { WfhAdminDashboard } from './WfhAdminDashboard';
import { MasterAdminDashboard } from './MasterAdminDashboard';
import { EmployeeCrmDashboard } from './EmployeeCrmDashboard';
import { propertyService } from '../services/propertyService';
import { useNotification } from '../context/NotificationContext';

const getInitialSession = (): { role: UserRole; user: UserProfile | null } => {
  try {
    const savedRole = localStorage.getItem('pathome_role') as UserRole | null;
    const savedUserStr = localStorage.getItem('pathome_user');
    if (savedRole && savedUserStr) {
      const parsedUser = JSON.parse(savedUserStr);
      return { role: savedRole, user: parsedUser };
    }
  } catch (err) {
    console.error('Failed to load session from localStorage', err);
  }
  return { role: 'GUEST', user: null };
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
  const [properties, setProperties] = useState<Property[]>(mockPropertyList);
  const [showAuthModal, setShowAuthModal] = useState(false);
  const [showLeaseModal, setShowLeaseModal] = useState(false);
  const [showDepositModal, setShowDepositModal] = useState(false);
  const [activeAdminTab, setActiveAdminTab] = useState('overview');
  const [filterSector, setFilterSector] = useState<string>('');
  const [guestModalConfig, setGuestModalConfig] = useState<{ property: Property; initialMode?: 'VIDEO' | 'PHOTOS' } | null>(null);

  // Fetch live properties strictly from PostgreSQL backend DB on mount & realtime publish events
  const loadLiveProperties = async () => {
    try {
      const liveData = await propertyService.fetchProperties();
      if (liveData && liveData.length > 0) {
        setProperties(liveData);
      } else {
        setProperties(mockPropertyList);
      }
    } catch (err) {
      console.warn('Backend server offline or unreachable. Displaying fallback properties:', err);
      setProperties(mockPropertyList);
    }
  };

  useEffect(() => {
    loadLiveProperties();

    const handlePropertyPublished = () => {
      loadLiveProperties();
    };
    window.addEventListener('pathome_property_published', handlePropertyPublished);
    return () => {
      window.removeEventListener('pathome_property_published', handlePropertyPublished);
    };
  }, []);

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

  // 2. STRICT PROTECTED ROUTE GUARDS & PATH SYNCHRONIZATION
  useEffect(() => {
    const path = location.pathname.toLowerCase();
    const isProtectedRoute = path === '/tenant' || path === '/admin' || path === '/crm';

    // GUARD CHECK 1: If user is logged out (no user session in memory or localStorage)
    if (!user || role === 'GUEST') {
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

      if (role === 'TENANT' && path !== '/tenant') {
        navigate('/tenant', { replace: true });
      } else if (role === 'EMPLOYEE' && path !== '/crm') {
        navigate('/crm', { replace: true });
      } else if ((role === 'ADMIN' || role === 'SUPER_ADMIN' || role === 'SUB_ADMIN') && path !== '/admin' && path !== '/crm') {
        navigate('/admin', { replace: true });
      }
    }
  }, [location.pathname, user, role, navigate]);

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
    let targetPath = '/tenant';
    if (userProfile.role === 'EMPLOYEE') targetPath = '/crm';
    else if (userProfile.role === 'SUB_ADMIN' || userProfile.role === 'SUPER_ADMIN' || userProfile.role === 'ADMIN') targetPath = '/admin';
    navigate(targetPath);
  };

  const handleLogout = () => {
    localStorage.removeItem('pathome_role');
    localStorage.removeItem('pathome_user');
    localStorage.removeItem('pathome_auth_token');
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
      />

      {/* DEDICATED EMPLOYEE CRM DASHBOARD */}
      <AnimatePresence mode="wait">
        {role === 'EMPLOYEE' && (
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
        {(role === 'ADMIN' || role === 'SUPER_ADMIN' || role === 'SUB_ADMIN') && (
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
        {role === 'TENANT' && user ? (
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
        ) : role === 'GUEST' && (
          <motion.div
            key="guest-homepage"
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -16 }}
            transition={{ duration: 0.4, ease: [0.16, 1, 0.3, 1] }}
          >
            {/* SECTION 1: HERO SEARCH HUB */}
            <div id="hero">
              <HeroSection onSearch={(sector) => setFilterSector(sector)} />
            </div>

            {/* SECTION 2: LIVE TRUST & STATS BAR */}
            <motion.div
              initial={{ opacity: 0, y: 24 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true, margin: "-60px" }}
              transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
            >
              <TrustStatsBar />
            </motion.div>

            {/* SECTION 3: FEATURED PROPERTIES GRID */}
            <div id="listings">
              <PropertyShowcase
                properties={properties}
                onBookTour={handleBookTour}
                onOpenMediaModal={(p, mode) => setGuestModalConfig({ property: p, initialMode: mode })}
                selectedSectorFilter={filterSector}
              />
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
            <Footer />
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

      {/* GUEST LANDING PAGE MEDIA & PHOTO LIGHTBOX MODAL */}
      <VideoPlayerModal
        property={guestModalConfig?.property || null}
        isOpen={!!guestModalConfig}
        initialMode={guestModalConfig?.initialMode || 'PHOTOS'}
        onClose={() => setGuestModalConfig(null)}
        onBookTour={handleBookTour}
      />

    </div>
  );
};
