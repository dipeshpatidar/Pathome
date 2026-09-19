import React, { useState, useRef, useEffect, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { motion, AnimatePresence, Variants } from 'framer-motion';
import {
  BarChart3,
  Users,
  CheckSquare,
  ShieldCheck,
  CheckCircle2,
  XCircle,
  FileText,
  Zap,
  ChevronRight,
  ChevronLeft,
  ChevronDown,
  ChevronUp,
  SlidersHorizontal,
  Plus,
  ToggleLeft,
  ToggleRight,
  UploadCloud,
  Camera,
  Video,
  Sparkles,
  Menu,
  Database,
  Copy,
  Check,
  Tag,
  Layers,
  X,
  Star,
  Mic,
  MicOff,
  RefreshCw,
  AlertTriangle,
  AlertCircle
} from 'lucide-react';
import { propertyService } from '../services/propertyService';
import { failedUploadService } from '../services/failedUploadService';
import { getErrorDetails, getErrorMessage } from '../services/apiError';
import { useNotification } from '../context/NotificationContext';
import { RoomTag, Property, DEFAULT_SMART_TAG_SEQUENCE } from '../types';
import { describeMediaLimits, prepareMediaForUpload } from '../utils/imageOptimizer';

import { RevenueAreaChart } from './analytics/RevenueAreaChart';
import { FunnelStepGraph } from './analytics/FunnelStepGraph';
import { SectorPerformanceBarChart } from './analytics/SectorPerformanceBarChart';
import { BhkDemandGaugeGrid } from './analytics/BhkDemandGaugeGrid';
import { BatchPropertyIngestionStudio } from './BatchPropertyIngestionStudio';
import { ParserLearningReviewPanel } from './ParserLearningReviewPanel';
import { FailedUploadsPanel } from './FailedUploadsPanel';
import { DraftManagementBar } from './DraftManagementBar';
import { usePropertyDraft } from '../hooks/usePropertyDraft';
import { draftService, DraftMedia } from '../services/draftService';

interface MasterAdminDashboardProps {
  activeTab: string;
  setActiveAdminTab?: (tab: string) => void;
}

const mockGroundBoys = [
  { id: 1, name: "Rahul Verma", sector: "Vijay Nagar", basePay: 15000, dealsClosed: 6, visitsEscorted: 28, status: "PENDING_DISBURSAL" },
  { id: 2, name: "Vikram Singh", sector: "Bhawarkua", basePay: 15000, dealsClosed: 8, visitsEscorted: 34, status: "DISBURSED" },
  { id: 3, name: "Sandeep Joshi", sector: "Palasia", basePay: 15000, dealsClosed: 3, visitsEscorted: 15, status: "PENDING_DISBURSAL" },
];

const mockLeaseCashbacks = [
  { id: "CB-101", tenantName: "Aman Gupta", propertyTitle: "Luxury 3 BHK Flat (Vijay Nagar)", leaseDate: "10 Sep 2026", amount: 1000, status: "PENDING" },
  { id: "CB-102", tenantName: "Ritu Sharma", propertyTitle: "Independent House (Bhawarkua)", leaseDate: "12 Sep 2026", amount: 1000, status: "PENDING" },
];

const mockPlotApprovals = [
  { id: "PLT-55", title: "Commercial Plot AB Road Sector B", areaSqFt: 4200, ownerName: "Rajesh Agrawal", askingPrice: "₹1.25 Cr", status: "UNDER_REVIEW" },
  { id: "PLT-56", title: "Super Corridor Residential Plot #12", areaSqFt: 1800, ownerName: "Sunil Jain", askingPrice: "₹45 Lakhs", status: "UNDER_REVIEW" }
];

const initialBhkConfigs = [
  { id: '1RK', label: '1 RK Studio', enabled: true, demandScore: '88%', avgRent: '₹8,500' },
  { id: '1BHK', label: '1 BHK Apartment', enabled: true, demandScore: '92%', avgRent: '₹11,000' },
  { id: '2BHK', label: '2 BHK Family Flat', enabled: true, demandScore: '98%', avgRent: '₹17,500' },
  { id: '3BHK', label: '3 BHK Gated Flat', enabled: true, demandScore: '95%', avgRent: '₹24,000' },
  { id: '4BHK', label: '4 BHK+ Luxury Villa', enabled: true, demandScore: '85%', avgRent: '₹40,000' }
];

const PRESET_PROMPTS = [
  {
    id: '2bhk-family-flat',
    label: '🏠 2 BHK Family Flat',
    subtitle: 'Full details: Rent, Deposit, Brokerage & Vastu',
    badge: 'Most Popular',
    text: 'Premium 2 BHK flat of 525 sqft in Nanda Nagar, Indore. Monthly rent ₹30,000, brokerage ₹15,000, 1+1 security deposit. Owner Ramesh Sharma +91 98260 12345. East facing, fully furnished, ready to move, status live.'
  },
  {
    id: '3bhk-luxury-penthouse',
    label: '🏢 3 BHK Luxury Penthouse',
    subtitle: 'High-rise with Terrace, Pool & Furnishing',
    badge: 'High-Value',
    text: 'Luxury 3 BHK Penthouse of 1800 sqft in Vijay Nagar, Indore. Monthly rent ₹45,000, brokerage ₹22,500, security deposit ₹90,000. Owner Vikram Singh +91 94250 88990. North-East facing with terrace, balcony and pool. Fully furnished, ready to move, status live.'
  },
  {
    id: '4bhk-gated-villa',
    label: '🏡 4 BHK Gated Villa',
    subtitle: 'Independent Villa with Private Garden & Gym',
    badge: 'Premium',
    text: 'Spacious 4 BHK Independent Villa of 2500 sqft in Nipania, Indore. Monthly rent ₹60,000, brokerage ₹30,000, security deposit ₹120,000. Owner Rajesh Gupta +91 98930 11223. East facing with private garden and gym. Semi furnished, ready to move, status live.'
  },
  {
    id: 'express-2bhk-quick',
    label: '⚡ Express 2 BHK Quick',
    subtitle: 'Fast 3-line prompt for rapid property listing',
    badge: 'Fast Upload',
    text: '2 BHK flat in Saket Nagar, Indore for ₹22,000 monthly rent. Owner Ankit Joshi +91 97550 44556. East facing, semi furnished, status live.'
  }
];

const MULTIPLE_PROPERTY_ENTRY_PATTERN = /(?:\r?\n\s*\r?\n+|---|\bnext\s*(?:property|flat|house|listing|unit)\b|\b(?:and\s+)?(?:the\s+)?(?:second|third|fourth|another)\s+(?:property|flat|house|listing|unit)(?:\s+is)?\b|(?:^|\n)\s*(?:\d+[\).]|#\d+)\s+)/im;
const SPOKEN_PROPERTY_BOUNDARY_PATTERN = /\b(?:and\s+)?(?:list\s+)?(?:the\s+)?(?:one\s+)?(?:other|another|next|second|third|fourth)\s+(?:property|flat|house|listing|unit)(?:\s+is)?\b/gi;
const SPOKEN_RENT_PREFIX_PATTERN = /(?:rent\s+is\s+|kiraya\s+)/gi;
const INDIAN_OWNER_PHONE_PATTERN = /^(?:\+?91[\s-]?)?[6-9]\d{4}[\s-]?\d{5}$/;
const READY_AVAILABILITY_PATTERN = /ready\s*to\s*move|immediate/i;
const ISO_DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;
type AvailabilityStatusValue = 'READY_NOW' | 'AVAILABLE_FROM_DATE' | 'UNSPECIFIED';
const DISPLAY_MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const normalizeAvailabilityStatus = (value: any): AvailabilityStatusValue => {
  if (value?.availabilityStatus === 'READY_NOW' || value?.availabilityStatus === 'AVAILABLE_FROM_DATE') {
    return value.availabilityStatus;
  }
  if (value?.availableFrom) return 'AVAILABLE_FROM_DATE';
  if (READY_AVAILABILITY_PATTERN.test(value?.possessionDate || '')) return 'READY_NOW';
  return value?.possessionDate ? 'AVAILABLE_FROM_DATE' : 'UNSPECIFIED';
};

const formatAvailabilityDate = (isoDate: string): string => {
  const match = ISO_DATE_PATTERN.exec(isoDate);
  if (!match) return '';
  const monthIndex = Number(match[2]) - 1;
  if (monthIndex < 0 || monthIndex >= DISPLAY_MONTHS.length) return '';
  return `${Number(match[3])} ${DISPLAY_MONTHS[monthIndex]} ${match[1]}`;
};

const normalizeOwnerPhoneForPublishing = (value: string | null | undefined): string => {
  const trimmed = value?.trim() || '';
  let digits = trimmed.replace(/\D/g, '');
  if (digits.startsWith('91') && digits.length === 12) digits = digits.slice(2);
  return /^[6-9]\d{9}$/.test(digits)
    ? `+91 ${digits.slice(0, 5)} ${digits.slice(5)}`
    : trimmed;
};

const formatSingleVoiceTranscript = (transcript: string): string =>
  transcript
    .replace(SPOKEN_PROPERTY_BOUNDARY_PATTERN, '\n\nNext property\n')
    .replace(SPOKEN_RENT_PREFIX_PATTERN, 'Rent: ');

const containerVariants: Variants = {
  hidden: { opacity: 0, y: 24, scale: 0.97, filter: 'blur(8px)' },
  visible: {
    opacity: 1,
    y: 0,
    scale: 1,
    filter: 'blur(0px)',
    transition: {
      staggerChildren: 0.08,
      duration: 0.45,
      ease: [0.16, 1, 0.3, 1]
    }
  },
  exit: {
    opacity: 0,
    y: -18,
    scale: 0.98,
    filter: 'blur(8px)',
    transition: { duration: 0.25, ease: [0.16, 1, 0.3, 1] }
  }
};

const cardVariants: Variants = {
  hidden: { opacity: 0, y: 20, scale: 0.95 },
  visible: {
    opacity: 1,
    y: 0,
    scale: 1,
    transition: {
      type: "spring",
      stiffness: 350,
      damping: 26
    }
  }
};

const mockEmployeeRoster = [
  { id: "EMP-101", name: "Rahul Verma", role: "Ground Boy Escort", sector: "Vijay Nagar", status: "ONLINE", phone: "+91 98765 43210", lastActive: "2 mins ago", rating: "4.9/5", loginIp: "103.22.41.12 (Mobile App)" },
  { id: "EMP-102", name: "Vikram Singh", role: "Field Verification Lead", sector: "Bhawarkua", status: "ON_LEAVE", phone: "+91 98765 43211", lastActive: "Yesterday", rating: "4.8/5", loginIp: "103.22.41.15 (Mobile App)" },
  { id: "EMP-103", name: "Sandeep Joshi", role: "Customer Support Executive", sector: "Palasia HQ", status: "ONLINE", phone: "+91 98765 43212", lastActive: "Just now", rating: "4.7/5", loginIp: "103.22.41.18 (Web Console)" },
];

const mockLeaveRequests = [
  { id: "LV-301", empId: "EMP-102", empName: "Vikram Singh", leaveType: "Casual Leave", startDate: "14 Sep 2026", endDate: "16 Sep 2026", reason: "Family Function", status: "PENDING" },
  { id: "LV-302", empId: "EMP-101", empName: "Rahul Verma", leaveType: "Medical Leave", startDate: "20 Sep 2026", endDate: "21 Sep 2026", reason: "Health Checkup", status: "APPROVED" }
];

export const MasterAdminDashboard: React.FC<MasterAdminDashboardProps> = ({ activeTab: externalActiveTab, setActiveAdminTab: externalSetActiveAdminTab }) => {
  const { notifySuccess, notifyInfo, notifyWarning, notifyAiMagic, showErrorDialog } = useNotification();

  const [internalTab, setInternalTab] = useState<string>(() => {
    try {
      const saved = localStorage.getItem('pathome_active_admin_tab');
      if (saved) return saved;
    } catch (_) {}
    return 'funnel';
  });
  const activeTab = externalActiveTab || internalTab;

  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState<boolean>(false);
  const [showMobileListingTools, setShowMobileListingTools] = useState<boolean>(false);
  const [activeAttributeTab, setActiveAttributeTab] = useState<'all' | 'location' | 'pricing' | 'specs'>('all');

  const handleTabSelect = (tabId: string) => {
    setInternalTab(tabId);
    setIsMobileMenuOpen(false);
    try {
      localStorage.setItem('pathome_active_admin_tab', tabId);
    } catch (_) {}
    if (externalSetActiveAdminTab) {
      externalSetActiveAdminTab(tabId);
    }
  };

  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState<boolean>(false);
  const [groundBoys, setGroundBoys] = useState(mockGroundBoys);
  const [cashbacks, setCashbacks] = useState(mockLeaseCashbacks);
  const [plots, setPlots] = useState(mockPlotApprovals);
  const [employees, setEmployees] = useState(mockEmployeeRoster);
  const [leaves, setLeaves] = useState(mockLeaveRequests);

  const uploadConsoleRef = useRef<HTMLDivElement>(null);
  const [bhkConfigs, setBhkConfigs] = useState<any[]>(initialBhkConfigs);
  const [newBhkLabel, setNewBhkLabel] = useState('');
  const [failedUploadsCount, setFailedUploadsCount] = useState<number>(0);
  const [attachedMediaFiles, setAttachedMediaFiles] = useState<File[]>([]);
  const [attachedMediaTags, setAttachedMediaTags] = useState<Record<number, RoomTag>>({});
  const [failedMediaUploads, setFailedMediaUploads] = useState<Array<{ file: File; originalIndex: number }>>([]);
  const [isUploadingMedia, setIsUploadingMedia] = useState<boolean>(false);
  const [coverPhotoIndex, setCoverPhotoIndex] = useState<number>(0);
  const [mediaRestorationProgress, setMediaRestorationProgress] = useState<{ loaded: number; total: number } | null>(null);
  const [isDragOverMedia, setIsDragOverMedia] = useState<boolean>(false);
  const [isMediaUploadModalOpen, setIsMediaUploadModalOpen] = useState<boolean>(false);
  const [previewLightboxIndex, setPreviewLightboxIndex] = useState<number | null>(null);
  const [mediaViewMode, setMediaViewMode] = useState<'grid' | 'list'>('grid');

  const mediaObjectUrlMapRef = useRef<Map<File, string>>(new Map());

  const getMediaPreviewUrl = useCallback((file: File): string => {
    let url = mediaObjectUrlMapRef.current.get(file);
    if (!url) {
      url = URL.createObjectURL(file);
      mediaObjectUrlMapRef.current.set(file, url);
    }
    return url;
  }, []);

  const revokeMediaPreviewUrl = useCallback((file: File) => {
    const url = mediaObjectUrlMapRef.current.get(file);
    if (url) {
      try {
        URL.revokeObjectURL(url);
      } catch (_) {}
      mediaObjectUrlMapRef.current.delete(file);
    }
  }, []);

  const revokeAllMediaPreviewUrls = useCallback(() => {
    mediaObjectUrlMapRef.current.forEach((url) => {
      try {
        URL.revokeObjectURL(url);
      } catch (_) {}
    });
    mediaObjectUrlMapRef.current.clear();
  }, []);

  useEffect(() => {
    return () => {
      revokeAllMediaPreviewUrls();
    };
  }, [revokeAllMediaPreviewUrls]);

  const handleOpenMediaUpload = () => {
    setIsMediaUploadModalOpen(true);
    setTimeout(() => {
      uploadConsoleRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }, 50);
  };
  const [selectedPropertyId, setSelectedPropertyId] = useState<number>(1);
  const [isUploadingCloudinary, setIsUploadingCloudinary] = useState<boolean>(false);
  const [uploadStatusMsg, setUploadStatusMsg] = useState<string | null>(null);

  // Property Submission Success & History State
  const [isSubmittingListing, setIsSubmittingListing] = useState<boolean>(false);
  const [publishSuccessNotification, setPublishSuccessNotification] = useState<{
    title: string;
    label: string;
    sector: string;
    city: string;
    rentVal: string;
    vastuFacing: string;
    amenities: string[];
    savedToDatabase: boolean;
    mediaCount: number;
    timestamp: string;
  } | null>(null);

  // Extracted Property Parameters Inspection State (Null until natural language prompt is submitted)
  const [lastExtractedResult, setLastExtractedResult] = useState<any>(null);
  const [copiedJson, setCopiedJson] = useState<boolean>(false);
  const [isInlineEditOpen, setIsInlineEditOpen] = useState<boolean>(false);
  const [isSavingDb, setIsSavingDb] = useState<boolean>(false);
  const [dbSaveSuccessMsg, setDbSaveSuccessMsg] = useState<string | null>(null);
  const [editForm, setEditForm] = useState<any>(null);
  const [isAttributesCollapsed, setIsAttributesCollapsed] = useState<boolean>(true);

  // Real Property Upload Pipeline Progress State (Zero Fake Percentages)
  const [uploadPipeline, setUploadPipeline] = useState<{
    active: boolean;
    stage: 'idle' | 'validating' | 'saving_listing' | 'preparing_media' | 'uploading_media' | 'success' | 'partial_failure' | 'failure';
    stageLabel: string;
    totalMediaCount: number;
    currentMediaIndex: number;
    currentMediaName: string;
    currentFilePercent?: number;
    failedCount: number;
    errorMessage?: string;
    savedPropertyId?: number;
  }>({
    active: false,
    stage: 'idle',
    stageLabel: '',
    totalMediaCount: 0,
    currentMediaIndex: 0,
    currentMediaName: '',
    failedCount: 0
  });

  // Fetch unresolved upload count for nav badge on mount
  useEffect(() => {
    failedUploadService.fetchUnresolvedCount().then(setFailedUploadsCount);
  }, []);

  // Auto-dismiss upload notification after confirmed success
  useEffect(() => {
    if (uploadPipeline.active && uploadPipeline.stage === 'success') {
      const timer = setTimeout(() => {
        setUploadPipeline(prev => ({ ...prev, active: false, stage: 'idle' }));
      }, 4500);
      return () => clearTimeout(timer);
    }
  }, [uploadPipeline.active, uploadPipeline.stage]);

  const refreshFailedUploadsCount = () => {
    failedUploadService.fetchUnresolvedCount().then(setFailedUploadsCount);
  };

  // Admin Draft & Recovery System for Single Property
  const handleRestoreSingleDraft = useCallback(async (payload: any, media: DraftMedia[]) => {
    if (!payload) return;
    if (typeof payload.newBhkLabel === 'string') {
      setNewBhkLabel(payload.newBhkLabel);
    }
    if (payload.lastExtractedResult !== undefined) {
      setLastExtractedResult(payload.lastExtractedResult);
    }
    if (payload.editForm !== undefined) {
      setEditForm(payload.editForm);
    }
    if (payload.activeAttributeTab) {
      setActiveAttributeTab(payload.activeAttributeTab);
    }
    if (payload.attachedMediaTags) {
      setAttachedMediaTags(payload.attachedMediaTags);
    }
    if (typeof payload.coverPhotoIndex === 'number') {
      setCoverPhotoIndex(payload.coverPhotoIndex);
    }

    if (media && media.length > 0 && attachedMediaFiles.length === 0) {
      setMediaRestorationProgress({ loaded: 0, total: media.length });
      try {
        const restored = await draftService.restoreMediaFiles(media, (loaded, total) => {
          setMediaRestorationProgress({ loaded, total });
        });
        if (restored.files.length > 0) {
          setAttachedMediaFiles(restored.files);
          setAttachedMediaTags(prev => ({ ...prev, ...restored.tags }));
          if (restored.coverIndex >= 0) {
            setCoverPhotoIndex(restored.coverIndex);
          }
        }
        if (restored.files.length < media.length) {
          notifyWarning('Partial media restored', `${restored.files.length} of ${media.length} staged media files were restored.`);
        }
      } catch (err) {
        console.warn('Draft media restoration notice:', err);
      } finally {
        setMediaRestorationProgress(null);
      }
    }
  }, [attachedMediaFiles.length, notifyWarning]);

  const handleClearSingleDraftState = useCallback(() => {
    setNewBhkLabel('');
    if (singleMicBaseTextRef.current) singleMicBaseTextRef.current = '';
    setLastExtractedResult(null);
    setEditForm(null);
    setDbSaveSuccessMsg(null);
    revokeAllMediaPreviewUrls();
    setAttachedMediaFiles([]);
    setAttachedMediaTags({});
    setCoverPhotoIndex(0);
  }, [revokeAllMediaPreviewUrls]);

  const singleDraft = usePropertyDraft({
    draftType: 'SINGLE',
    onRestoreDraft: handleRestoreSingleDraft,
    onClearDraftState: handleClearSingleDraftState
  });

  // Automatically autosave on changes
  useEffect(() => {
    const hasMeaningfulWork = Boolean(
      newBhkLabel.trim() ||
      lastExtractedResult ||
      attachedMediaFiles.length > 0
    );

    if (!hasMeaningfulWork) return;

    const titleSummary =
      lastExtractedResult?.title ||
      (newBhkLabel.trim().length > 40
        ? `${newBhkLabel.trim().slice(0, 40)}…`
        : newBhkLabel.trim()) ||
      'Property draft';

    singleDraft.scheduleAutosave(
      {
        newBhkLabel,
        lastExtractedResult,
        editForm,
        activeAttributeTab,
        attachedMediaTags,
        coverPhotoIndex
      },
      titleSummary,
      1
    );
  }, [
    newBhkLabel,
    lastExtractedResult,
    editForm,
    activeAttributeTab,
    attachedMediaTags,
    coverPhotoIndex,
    attachedMediaFiles.length,
    singleDraft.scheduleAutosave
  ]);

  // Robust iOS-safe background scroll lock preserving viewport scroll position
  useEffect(() => {
    const isAnyOverlayActive = isMobileMenuOpen || isMediaUploadModalOpen || previewLightboxIndex !== null || isInlineEditOpen;
    if (!isAnyOverlayActive) return;

    const scrollY = window.scrollY;
    const originalPosition = document.body.style.position;
    const originalTop = document.body.style.top;
    const originalWidth = document.body.style.width;
    const originalOverflow = document.body.style.overflow;

    document.body.style.position = 'fixed';
    document.body.style.top = `-${scrollY}px`;
    document.body.style.width = '100%';
    document.body.style.overflow = 'hidden';

    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setIsMobileMenuOpen(false);
        setIsMediaUploadModalOpen(false);
        setPreviewLightboxIndex(null);
        setIsInlineEditOpen(false);
      }
    };
    window.addEventListener('keydown', handleEscape);

    return () => {
      window.removeEventListener('keydown', handleEscape);
      document.body.style.position = originalPosition;
      document.body.style.top = originalTop;
      document.body.style.width = originalWidth;
      document.body.style.overflow = originalOverflow;
      window.scrollTo(0, scrollY);
    };
  }, [isMobileMenuOpen, isMediaUploadModalOpen, previewLightboxIndex, isInlineEditOpen]);

  const handleOpenInlineEdit = () => {
    const activeData = lastExtractedResult || liveExtractedPreview;
    if (activeData) {
      const rawType = (activeData.type || 'FLAT').toUpperCase();
      const typeVal = rawType.includes('HOUSE') || rawType.includes('VILLA') ? 'HOUSE'
                    : rawType.includes('PLOT') ? 'PLOT'
                    : rawType.includes('PENTHOUSE') ? 'PENTHOUSE'
                    : rawType.includes('STUDIO') ? 'STUDIO'
                    : rawType.includes('AIRBNB') ? 'AIRBNB'
                    : 'FLAT';

      const rawFurnish = String(activeData.furnishingStatus || 'UNSPECIFIED').toUpperCase();
      const furnishingVal = rawFurnish.includes('SEMI') ? 'SEMI_FURNISHED'
                          : rawFurnish.includes('FULLY') || rawFurnish.includes('FURNISHED') ? 'FULLY_FURNISHED'
                          : rawFurnish.includes('UNFURNISHED') ? 'UNFURNISHED'
                          : 'UNSPECIFIED';

      const parsedRentAmount = activeData.rentAmount || (activeData.rentVal && activeData.rentVal !== 'Unspecified' ? parseInt(String(activeData.rentVal).replace(/[^0-9]/g, '')) : 0);
      const parsedBathrooms = typeof activeData.bathrooms === 'number'
        ? activeData.bathrooms
        : Number.parseInt(String(activeData.bathrooms || ''), 10);
      const availabilityStatus = normalizeAvailabilityStatus(activeData);

      const initialForm = {
        ...activeData,
        title: activeData.title || (activeData.bhk ? `${activeData.bhk} ${typeVal} in ${activeData.sector || 'Indore'}` : ''),
        bhk: activeData.bhk && activeData.bhk !== 'Unspecified' ? activeData.bhk : '',
        type: typeVal,
        bathrooms: Number.isFinite(parsedBathrooms) ? parsedBathrooms : '',
        rentAmount: parsedRentAmount || '',
        rentVal: activeData.rentVal && activeData.rentVal !== 'Unspecified' ? activeData.rentVal : (parsedRentAmount ? `₹${parsedRentAmount.toLocaleString('en-IN')}` : ''),
        brokerageVal: activeData.brokerageVal && activeData.brokerageVal !== 'Unmentioned' ? activeData.brokerageVal : '',
        depositVal: activeData.depositVal && activeData.depositVal !== 'Unspecified' ? activeData.depositVal : '',
        areaSqFt: activeData.areaSqFt && activeData.areaSqFt !== 'Unspecified' ? activeData.areaSqFt : '',
        vastuFacing: activeData.vastuFacing || 'Not Specified',
        furnishingStatus: furnishingVal,
        possessionDate: activeData.possessionDate && activeData.possessionDate !== 'Unspecified' ? activeData.possessionDate : '',
        availabilityStatus,
        availableFrom: availabilityStatus === 'AVAILABLE_FROM_DATE' ? (activeData.availableFrom || '') : '',
        status: activeData.status && activeData.status !== 'Unspecified' ? String(activeData.status).toUpperCase() : 'LIVE',
        sector: activeData.sector && activeData.sector !== 'Not Specified' ? activeData.sector : '',
        city: activeData.city && activeData.city !== 'Not Specified' ? activeData.city : 'Indore',
        ownerName: activeData.ownerName && activeData.ownerName !== 'Not Specified' ? activeData.ownerName : '',
        ownerPhone: activeData.ownerPhone && activeData.ownerPhone !== 'Not Specified' ? activeData.ownerPhone : ''
      };
      setEditForm(initialForm);
      setLastExtractedResult(initialForm);
    }
    setIsInlineEditOpen(true);
  };

  const handleSaveInlineEdits = (e: React.FormEvent) => {
    e.preventDefault();
    if (!editForm) return;
    const updated = {
      ...(lastExtractedResult || liveExtractedPreview || {}),
      ...editForm,
      bhk: editForm.bhk || 'Unspecified',
      type: editForm.type || 'FLAT',
      sector: editForm.sector || 'Not Specified',
      city: editForm.city || 'Indore',
      rentAmount: editForm.rentAmount ? Number(editForm.rentAmount) : 0,
      rentVal: editForm.rentVal || (editForm.rentAmount ? `₹${Number(editForm.rentAmount).toLocaleString('en-IN')}` : 'Unspecified'),
      brokerageVal: editForm.brokerageVal || 'Unmentioned',
      depositVal: editForm.depositVal || 'Unspecified',
      areaSqFt: editForm.areaSqFt || 'Unspecified',
      vastuFacing: editForm.vastuFacing || 'Not Specified',
      furnishingStatus: editForm.furnishingStatus || 'UNSPECIFIED',
      possessionDate: editForm.possessionDate || '',
      availabilityStatus: editForm.availabilityStatus || 'UNSPECIFIED',
      availableFrom: editForm.availableFrom || '',
      status: editForm.status || 'LIVE',
      ownerName: editForm.ownerName || 'Not Specified',
      ownerPhone: normalizeOwnerPhoneForPublishing(editForm.ownerPhone) || 'Not Specified',
      title: editForm.title || `${editForm.bhk || ''} ${editForm.type || 'Flat'} in ${editForm.sector || 'Indore'}`,
      label: `${editForm.bhk || 'Property'} ${editForm.type || ''} (${editForm.sector || ''}, ${editForm.city || 'Indore'})`,
      missingFields: [] // Clear missing attributes warning deck after manual admin verification
    };
    setLastExtractedResult(updated);
    setIsInlineEditOpen(false);
    notifySuccess('✅ Changes saved', 'Property details were updated successfully.');
  };

  const uploadPendingMedia = async (
    targetPropertyId: number,
    pendingItems: Array<{ file: File; originalIndex: number }>,
    metadata: { title?: string; sector?: string; rentVal?: string; vastuFacing?: string }
  ) => {
    if (!targetPropertyId || pendingItems.length === 0) return;
    setIsUploadingMedia(true);
    setUploadPipeline(prev => ({
      ...prev,
      active: true,
      stage: 'uploading_media',
      totalMediaCount: pendingItems.length,
      failedCount: 0,
      stageLabel: `Preparing ${pendingItems.length} media file(s) for listing #${targetPropertyId}…`
    }));

    const failedItems: Array<{ file: File; originalIndex: number }> = [];
    const failureMessages: string[] = [];

    for (let pendingIndex = 0; pendingIndex < pendingItems.length; pendingIndex += 1) {
      const item = pendingItems[pendingIndex];
      setUploadPipeline(prev => ({
        ...prev,
        currentMediaIndex: pendingIndex + 1,
        currentMediaName: item.file.name,
        currentFilePercent: 0,
        stage: 'uploading_media',
        stageLabel: `Uploading media item ${pendingIndex + 1} of ${pendingItems.length} (${item.file.name})…`
      }));

      try {
        await propertyService.uploadTaggedMedia(targetPropertyId, item.file, {
          roomTag: attachedMediaTags[item.originalIndex] || (item.originalIndex === 0 ? 'LIVING_ROOM' : 'BEDROOM'),
          mediaType: item.file.type.startsWith('video/') ? 'VIDEO_WALKTHROUGH' : 'IMAGE',
          caption: metadata.title || 'Property media',
          isPrimaryCover: item.originalIndex === coverPhotoIndex,
          sector: metadata.sector,
          priceTag: metadata.rentVal,
          vastuFacing: metadata.vastuFacing
        }, {
          onProgress: (progress) => {
            const itemLabel = `Media ${pendingIndex + 1} of ${pendingItems.length} (${item.file.name})`;
            const statusText = progress.stage === 'retrying'
              ? `Connection interrupted. Retrying ${itemLabel} (attempt ${progress.attempt}/${progress.maxAttempts})…`
              : progress.stage === 'preparing'
                ? `Preparing & compressing ${itemLabel}…`
                : progress.percent > 0
                  ? `Uploading ${itemLabel} • ${progress.percent}% byte progress`
                  : `Uploading ${itemLabel}…`;

            setUploadStatusMsg(statusText);
            setUploadPipeline(prev => ({
              ...prev,
              currentFilePercent: progress.percent,
              stageLabel: statusText
            }));
          }
        });
      } catch (mediaError) {
        failedItems.push(item);
        failureMessages.push(getErrorMessage(mediaError, `${item.file.name} could not be uploaded.`));
      }
    }

    setIsUploadingMedia(false);
    setFailedMediaUploads(failedItems);

    if (failedItems.length > 0) {
      setUploadStatusMsg(`${failedItems.length} of ${pendingItems.length} ${pendingItems.length === 1 ? 'file' : 'files'} could not be uploaded.`);
      setUploadPipeline(prev => ({
        ...prev,
        stage: 'partial_failure',
        failedCount: failedItems.length,
        stageLabel: `Listing #${targetPropertyId} published, but ${failedItems.length} of ${pendingItems.length} media items failed.`
      }));
      showErrorDialog({
        title: 'Property published, but some media needs attention',
        message: `${failedItems.length} of ${pendingItems.length} media files could not be uploaded for listing #${targetPropertyId}. You can retry only the failed media; successful uploads have been preserved.`,
        details: failureMessages.join(' • '),
        action: {
          label: 'Retry failed media',
          onClick: () => void uploadPendingMedia(targetPropertyId, failedItems, metadata)
        }
      });
      void singleDraft.onPublishSuccess();
      return;
    }

    setUploadStatusMsg('All property media uploaded successfully.');
    setUploadPipeline(prev => ({
      ...prev,
      stage: 'success',
      stageLabel: pendingItems.length > 0
        ? `Listing #${targetPropertyId} and all ${pendingItems.length} media items uploaded successfully!`
        : `Listing #${targetPropertyId} published successfully!`
    }));
    notifySuccess(
      '1 property uploaded',
      pendingItems.length > 0
        ? `Listing #${targetPropertyId} and all ${pendingItems.length} media items published successfully.`
        : `Listing #${targetPropertyId} published successfully.`,
      undefined,
      'PROPERTY'
    );

    void singleDraft.onPublishSuccess();
    // Reset single-property transient form state after confirmed success
    setNewBhkLabel('');
    if (singleMicBaseTextRef.current) {
      singleMicBaseTextRef.current = '';
    }
    revokeAllMediaPreviewUrls();
    setAttachedMediaFiles([]);
    setAttachedMediaTags({});
    setCoverPhotoIndex(0);
    setLastExtractedResult(null);
    setDbSaveSuccessMsg(null);
  };

  const handleRetryFailedMedia = async () => {
    const targetPropertyId = lastExtractedResult?.databaseId;
    if (!targetPropertyId || failedMediaUploads.length === 0) return;
    await uploadPendingMedia(targetPropertyId, failedMediaUploads, {
      title: lastExtractedResult.title,
      sector: lastExtractedResult.sector,
      rentVal: lastExtractedResult.rentVal,
      vastuFacing: lastExtractedResult.vastuFacing
    });
  };

  const handleSaveToDatabase = async () => {
    if (!lastExtractedResult) return;

    if (lastExtractedResult.savedToDatabase && lastExtractedResult.databaseId) {
      if (failedMediaUploads.length > 0) {
        await handleRetryFailedMedia();
      }
      return;
    }
    
    // Pre-flight validation for mandatory non-null database fields
    setUploadPipeline({
      active: true,
      stage: 'validating',
      stageLabel: 'Validating property details…',
      totalMediaCount: attachedMediaFiles.length,
      currentMediaIndex: 0,
      currentMediaName: '',
      failedCount: 0
    });

    const missingReq: string[] = [];
    if (!lastExtractedResult.bhk || lastExtractedResult.bhk === 'Not Specified' || lastExtractedResult.bhk === 'Unspecified') {
      missingReq.push('BHK Layout Count');
    }
    if (!lastExtractedResult.type || lastExtractedResult.type === 'Not Specified' || lastExtractedResult.type === 'Unspecified') {
      missingReq.push('Property Type');
    }
    const rentAmountNum = lastExtractedResult.rentAmount ? Number(lastExtractedResult.rentAmount) : (lastExtractedResult.rentVal ? Number(String(lastExtractedResult.rentVal).replace(/[^0-9]/g, '')) : 0);
    if (!rentAmountNum || rentAmountNum <= 0) {
      missingReq.push('Monthly Rent Amount');
    }
    if (!lastExtractedResult.sector || lastExtractedResult.sector === 'Not Specified' || lastExtractedResult.sector === 'Unspecified') {
      missingReq.push('Locality / Sector Name');
    }
    const ownerPhoneForPublishing = normalizeOwnerPhoneForPublishing(lastExtractedResult.ownerPhone);
    if (!ownerPhoneForPublishing || lastExtractedResult.ownerPhone === 'Not Specified') {
      missingReq.push('Owner Contact Phone Number');
    } else if (!INDIAN_OWNER_PHONE_PATTERN.test(ownerPhoneForPublishing)) {
      missingReq.push('Owner Contact Phone Number (use a valid 10-digit Indian mobile number)');
    }
    if (!lastExtractedResult.depositVal || lastExtractedResult.depositVal === 'Not Specified' || lastExtractedResult.depositVal === 'Unspecified') {
      missingReq.push('Security Deposit');
    }

    if (missingReq.length > 0) {
      setUploadPipeline(prev => ({ ...prev, active: false, stage: 'idle' }));
      showErrorDialog({
        title: 'Complete the property details',
        message: 'A few required details need your attention before this listing can be published.',
        details: missingReq.join(' • '),
        action: {
          label: 'Edit details',
          onClick: handleOpenInlineEdit
        }
      });
      return;
    }

    setIsSavingDb(true);
    setDbSaveSuccessMsg(null);
    setUploadPipeline(prev => ({
      ...prev,
      stage: 'saving_listing',
      stageLabel: 'Saving property listing to database…'
    }));

    try {
      const payload = {
        learningExampleId: lastExtractedResult.learningExampleId || '',
        promptIndex: lastExtractedResult.promptIndex || 1,
        title: lastExtractedResult.title || `${lastExtractedResult.bhk || ''} Property`,
        description: lastExtractedResult.description || lastExtractedResult.rawInput || lastExtractedResult.title,
        bhk: lastExtractedResult.bhk || '',
        type: lastExtractedResult.type || '',
        status: lastExtractedResult.status || 'LIVE',
        bathrooms: lastExtractedResult.bathrooms || '',
        rentAmount: rentAmountNum,
        rentVal: lastExtractedResult.rentVal || '',
        brokerageVal: lastExtractedResult.brokerageVal || '',
        brokerageDays: lastExtractedResult.brokerageDays || '',
        depositVal: lastExtractedResult.depositVal || '',
        areaSqFt: lastExtractedResult.areaSqFt || '',
        vastuFacing: lastExtractedResult.vastuFacing || 'Not Specified',
        furnishingStatus: lastExtractedResult.furnishingStatus || '',
        possessionDate: lastExtractedResult.possessionDate || '',
        availabilityStatus: lastExtractedResult.availabilityStatus || 'UNSPECIFIED',
        availableFrom: lastExtractedResult.availableFrom || '',
        address: lastExtractedResult.address || lastExtractedResult.sector || '',
        sector: lastExtractedResult.sector || '',
        city: lastExtractedResult.city || '',
        colony: lastExtractedResult.colony || '',
        state: lastExtractedResult.state || '',
        pincode: lastExtractedResult.pincode || '',
        landmark: lastExtractedResult.landmark || '',
        ownerName: lastExtractedResult.ownerName && lastExtractedResult.ownerName !== 'Not Specified' ? lastExtractedResult.ownerName : '',
        ownerPhone: ownerPhoneForPublishing,
        amenities: lastExtractedResult.amenities || [],
        rawPrompt: lastExtractedResult.rawInput || '',
        adminVerified: true,
        draftId: singleDraft.currentDraftId || undefined
      };

      const saved = await propertyService.createPropertyFromParsed(payload);
      const propertyId = saved.propertyId;
      setIsSavingDb(false);
      window.dispatchEvent(new Event('pathome_property_published'));
      setDbSaveSuccessMsg(`Property successfully published. Listing ID: #${propertyId}`);

      setLastExtractedResult((prev: any) => ({
        ...prev,
        ownerPhone: ownerPhoneForPublishing,
        savedToDatabase: true,
        adminVerified: true,
        databaseId: propertyId,
        extractedAt: `Just now (Listing #${saved.id})`
      }));

      if (attachedMediaFiles.length > 0) {
        setUploadPipeline(prev => ({
          ...prev,
          savedPropertyId: propertyId,
          stage: 'uploading_media',
          stageLabel: `Listing #${propertyId} created. Uploading ${attachedMediaFiles.length} media file(s)…`
        }));
        await uploadPendingMedia(
          propertyId,
          attachedMediaFiles.map((file, originalIndex) => ({ file, originalIndex })),
          {
            title: payload.title,
            sector: payload.sector,
            rentVal: payload.rentVal || `₹${rentAmountNum.toLocaleString('en-IN')} / month`,
            vastuFacing: payload.vastuFacing
          }
        );
      } else {
        setUploadPipeline(prev => ({
          ...prev,
          savedPropertyId: propertyId,
          stage: 'success',
          stageLabel: `Listing #${propertyId} published successfully!`
        }));
        notifySuccess(
          '1 property uploaded',
          `Listing #${propertyId} published successfully.`,
          undefined,
          'PROPERTY'
        );
        void singleDraft.onPublishSuccess();
        // Reset single-property transient form state after confirmed success
        setNewBhkLabel('');
        if (singleMicBaseTextRef.current) {
          singleMicBaseTextRef.current = '';
        }
        revokeAllMediaPreviewUrls();
        setAttachedMediaFiles([]);
        setAttachedMediaTags({});
        setCoverPhotoIndex(0);
        setLastExtractedResult(null);
        setDbSaveSuccessMsg(null);
      }
    } catch (err: unknown) {
      console.warn('Backend endpoint status notice:', err);
      const message = getErrorMessage(err, 'Please check the required property details and try again.');
      setUploadPipeline(prev => ({
        ...prev,
        stage: 'failure',
        errorMessage: message,
        stageLabel: `Unable to publish listing: ${message}`
      }));
      showErrorDialog({
        title: 'Unable to publish this listing',
        message,
        details: getErrorDetails(err)
      });
      setDbSaveSuccessMsg(`⚡ Action needed: ${message}`);
    } finally {
      setIsSavingDb(false);
    }
  };

  // Rich Media Metadata Tagging State (Zero hardcoded fallbacks)
  const [uploadMode, setUploadMode] = useState<'single' | 'multiple'>('single');
  const [selectedBatchDraftId, setSelectedBatchDraftId] = useState<string | null>(null);
  const [batchDetails, setBatchDetails] = useState<string>('');
  const [isSingleMicListening, setIsSingleMicListening] = useState<boolean>(false);
  const [isSingleDictationChoiceOpen, setIsSingleDictationChoiceOpen] = useState<boolean>(false);
  const singleRecognitionRef = useRef<any>(null);
  const singleMicBaseTextRef = useRef<string>('');
  const singleInputSourceRef = useRef<'TYPED' | 'DICTATED' | 'MIXED'>('TYPED');

  useEffect(() => {
    return () => {
      if (singleRecognitionRef.current) {
        try {
          singleRecognitionRef.current.abort();
        } catch (_) {}
      }
    };
  }, []);

  const startSingleDictation = (mode: 'replace' | 'append') => {
    const SpeechRecognition =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

    if (!SpeechRecognition) {
      notifyWarning(
        'Dictation not supported by this browser',
        'Your mobile browser does not support the Web Speech API. Tip: You can tap the microphone button on your phone keyboard to dictate directly into the text field.'
      );
      return;
    }

    if (singleRecognitionRef.current) {
      try {
        singleRecognitionRef.current.abort();
      } catch (_) {}
    }

    try {
      const isIOSOrSafari = /iPad|iPhone|iPod/.test(navigator.userAgent) ||
        (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1) ||
        /^((?!chrome|android).)*safari/i.test(navigator.userAgent);

      const recognition = new SpeechRecognition();
      recognition.continuous = !isIOSOrSafari;
      recognition.interimResults = true;
      recognition.lang = 'en-IN';

      const currentDraft = newBhkLabel.trim();
      singleInputSourceRef.current = mode === 'append' && currentDraft ? 'MIXED' : 'DICTATED';
      singleMicBaseTextRef.current = mode === 'append' ? currentDraft : '';
      if (mode === 'replace' && currentDraft) {
        setNewBhkLabel('');
      }
      setIsSingleDictationChoiceOpen(false);

      recognition.onresult = (event: any) => {
        let finalTranscript = '';
        let interimTranscript = '';

        for (let i = event.resultIndex; i < event.results.length; i++) {
          const transcript = event.results[i][0].transcript;
          if (event.results[i].isFinal) {
            finalTranscript += (finalTranscript ? ' ' : '') + transcript.trim();
          } else {
            interimTranscript += (interimTranscript ? ' ' : '') + transcript.trim();
          }
        }

        const formattedFinal = formatSingleVoiceTranscript(finalTranscript);
        if (formattedFinal) {
          const base = singleMicBaseTextRef.current || '';
          const separator = base && !formattedFinal.startsWith('\n') ? ' ' : '';
          singleMicBaseTextRef.current = `${base}${separator}${formattedFinal}`;
        }

        const confirmedText = singleMicBaseTextRef.current;
        const interimSeparator = confirmedText && interimTranscript ? ' ' : '';
        setNewBhkLabel(`${confirmedText}${interimSeparator}${interimTranscript}`);
      };

      recognition.onerror = (event: any) => {
        console.warn('Speech recognition notice:', event);
        setIsSingleMicListening(false);
        setIsSingleDictationChoiceOpen(false);
        const errType = event?.error;
        if (errType === 'no-speech') return;

        if (errType === 'not-allowed' || errType === 'service-not-allowed') {
          if (!window.isSecureContext && window.location.hostname !== 'localhost' && window.location.hostname !== '127.0.0.1') {
            notifyWarning(
              'Microphone requires HTTPS on mobile',
              'Mobile browsers restrict voice dictation to HTTPS connections over Wi-Fi/LAN. Tip: Tap the microphone button on your phone keyboard to dictate directly into the text field!'
            );
          } else {
            notifyWarning(
              'Microphone permission needed',
              'Please allow microphone access in your browser settings to dictate property details, or use the microphone button on your device keyboard.'
            );
          }
        } else if (errType === 'network') {
          notifyWarning(
            'Speech recognition network error',
            'Voice dictation could not connect to speech services. Please check your internet connection, or use the microphone button on your phone keyboard.'
          );
        } else if (errType) {
          notifyWarning(
            'Dictation paused',
            `Dictation stopped (${errType}). You can also use the microphone button on your phone keyboard.`
          );
        }
      };

      recognition.onend = () => {
        setIsSingleMicListening(false);
        setIsSingleDictationChoiceOpen(false);
      };

      singleRecognitionRef.current = recognition;
      recognition.start();
      setIsSingleMicListening(true);
    } catch (err: any) {
      console.warn('Mic start error', err);
      setIsSingleMicListening(false);
      if (!window.isSecureContext && window.location.hostname !== 'localhost' && window.location.hostname !== '127.0.0.1') {
        notifyWarning(
          'Microphone requires HTTPS on mobile',
          'Mobile browsers restrict speech recognition to HTTPS connections over Wi-Fi/LAN. Tip: Tap the microphone button on your phone keyboard to dictate directly!'
        );
      } else {
        notifyWarning(
          'Dictation unavailable',
          'Could not activate microphone. You can tap the microphone button on your phone keyboard to dictate directly.'
        );
      }
    }
  };

  const toggleSingleMic = () => {
    const SpeechRecognition =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

    if (!SpeechRecognition) {
      notifyWarning(
        'Dictation not supported by this browser',
        'Your mobile browser does not support the Web Speech API. You can use the microphone button on your phone keyboard to dictate directly into the text field.'
      );
      return;
    }

    if (isSingleMicListening) {
      try {
        singleRecognitionRef.current?.stop();
      } catch (_) {}
      setIsSingleMicListening(false);
      return;
    }

    if (newBhkLabel.trim()) {
      setIsSingleDictationChoiceOpen(true);
      return;
    }

    startSingleDictation('replace');
  };

  const handleSinglePromptChange = (details: string) => {
    if (isSingleMicListening) {
      singleRecognitionRef.current?.stop();
      setIsSingleMicListening(false);
    }
    singleInputSourceRef.current = !details.trim()
      ? 'TYPED'
      : singleInputSourceRef.current === 'DICTATED'
        ? 'MIXED'
        : singleInputSourceRef.current;
    singleMicBaseTextRef.current = details;
    setNewBhkLabel(details);
  };

  const [selectedRoomTag, setSelectedRoomTag] = useState<RoomTag>('LIVING_ROOM');
  const [mediaCaption, setMediaCaption] = useState<string>('');
  const [mediaPriceTag, setMediaPriceTag] = useState<string>('');
  const [mediaSector, setMediaSector] = useState<string>('');
  const [mediaVastu, setMediaVastu] = useState<string>('');
  const [isPrimaryCover, setIsPrimaryCover] = useState<boolean>(false);

  const handleFillMediaFromExtracted = () => {
    const result = lastExtractedResult;
    
    if (result && (result.sector || result.rentVal || result.title || result.bhk)) {
      if (result.sector) setMediaSector(result.sector);
      if (result.rentVal) setMediaPriceTag(result.rentVal.includes('/ month') ? result.rentVal : `${result.rentVal} / month`);
      if (result.vastuFacing) setMediaVastu(result.vastuFacing);
      if (result.title) setMediaCaption(result.title);
      notifySuccess('✅ Photo details prepared', `Location: ${result.sector || 'Not provided'} • Rent: ${result.rentVal || 'Not provided'}`, undefined, 'PROPERTY');
    } else {
      notifyInfo('ℹ️ Review property details first', 'Review the property details before preparing photo information.', undefined, 'PROPERTY');
    }
  };

  const handleCopyJson = () => {
    if (lastExtractedResult) {
      navigator.clipboard.writeText(JSON.stringify(lastExtractedResult, null, 2));
      setCopiedJson(true);
      notifySuccess('📋 Copied', 'Property details were copied to the clipboard.', undefined, 'PROPERTY');
      setTimeout(() => setCopiedJson(false), 2000);
    }
  };

  const handleCloudinaryPhotoUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    if (!e.target.files || e.target.files.length === 0) return;
    const files = Array.from(e.target.files);
    setIsUploadingCloudinary(true);
    setUploadStatusMsg(`Uploading ${files.length === 1 ? 'photo' : 'photos'}…`);

    const uploadPendingPhotos = async (pendingFiles: File[]) => {
      const failedFiles: File[] = [];
      const failureMessages: string[] = [];
      setIsUploadingCloudinary(true);

      for (let index = 0; index < pendingFiles.length; index += 1) {
        const file = pendingFiles[index];
        try {
          await propertyService.uploadTaggedMedia(selectedPropertyId, file, {
            roomTag: selectedRoomTag,
            mediaType: 'IMAGE',
            caption: mediaCaption || `${selectedRoomTag.replace('_', ' ')} View`,
            isPrimaryCover,
            sector: mediaSector,
            priceTag: mediaPriceTag,
            vastuFacing: mediaVastu
          }, {
            onProgress: (progress) => {
              const overallProgress = Math.round(((index + (progress.percent / 100)) / pendingFiles.length) * 100);
              setUploadStatusMsg(progress.stage === 'retrying'
                ? `Connection interrupted. Retrying photo… ${overallProgress}%`
                : `Uploading photos… ${overallProgress}%`);
            }
          });
        } catch (error) {
          failedFiles.push(file);
          failureMessages.push(getErrorMessage(error, `${file.name} could not be uploaded.`));
        }
      }

      setIsUploadingCloudinary(false);
      if (failedFiles.length > 0) {
        setUploadStatusMsg(`${failedFiles.length} ${failedFiles.length === 1 ? 'photo needs' : 'photos need'} another attempt.`);
        showErrorDialog({
          title: 'Some photos need another attempt',
          message: 'Successfully uploaded photos are safe. Retry only the remaining files.',
          details: failureMessages.join(' • '),
          action: {
            label: 'Retry failed photos',
            onClick: () => void uploadPendingPhotos(failedFiles)
          }
        });
        return;
      }

      setUploadStatusMsg(`✓ ${pendingFiles.length} ${pendingFiles.length === 1 ? 'photo' : 'photos'} uploaded.`);
      notifySuccess('Photos uploaded', `${pendingFiles.length} ${pendingFiles.length === 1 ? 'photo was' : 'photos were'} added to this listing.`, `Location: ${mediaSector} • Rent: ${mediaPriceTag} • Facing: ${mediaVastu}`, 'PROPERTY');
    };

    await uploadPendingPhotos(files);
  };

  const handleCloudinaryVideoUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    if (!e.target.files || e.target.files.length === 0) return;
    const file = e.target.files[0];
    setIsUploadingCloudinary(true);
    setUploadStatusMsg('Uploading walkthrough video…');

    const uploadVideo = async () => {
      setIsUploadingCloudinary(true);
      try {
        await propertyService.uploadTaggedMedia(selectedPropertyId, file, {
          roomTag: selectedRoomTag,
          mediaType: 'VIDEO_WALKTHROUGH',
          caption: mediaCaption || 'Property walkthrough video',
          isPrimaryCover: false,
          sector: mediaSector,
          priceTag: mediaPriceTag,
          vastuFacing: mediaVastu
        }, {
          onProgress: (progress) => setUploadStatusMsg(progress.stage === 'retrying'
            ? `Connection interrupted. Retrying video… ${progress.percent}%`
            : `Uploading walkthrough video… ${progress.percent}%`)
        });
        setUploadStatusMsg('✓ Walkthrough video uploaded.');
        notifySuccess('Walkthrough video uploaded', 'The walkthrough video was added to this listing.', undefined, 'PROPERTY');
      } catch (err: unknown) {
        setUploadStatusMsg('The walkthrough video needs another attempt.');
        showErrorDialog({
          title: 'Unable to upload the walkthrough video',
          message: getErrorMessage(err, 'The property is safe. Retry the video when the connection is stable.'),
          details: getErrorDetails(err),
          action: {
            label: 'Retry video',
            onClick: () => void uploadVideo()
          }
        });
      } finally {
        setIsUploadingCloudinary(false);
      }
    };

    await uploadVideo();
  };

  React.useEffect(() => {
    // Clean up any old stale local storage property/filter keys
    localStorage.removeItem('pathome_bhk_configs');
    localStorage.removeItem('pathome_custom_properties');
    localStorage.removeItem('pathome_tenant_filters');
  }, []);

  const handleToggleBhk = (id: string) => {
    setBhkConfigs(bhkConfigs.map((c: any) => c.id === id ? { ...c, enabled: !c.enabled } : c));
  };

  const liveExtractedPreview = lastExtractedResult;

  const handleAddCustomBhk = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newBhkLabel.trim()) {
      showErrorDialog({
        title: 'Add property details first',
        message: 'Enter property details or select an example before reviewing the listing.'
      });
      return;
    }

    if (MULTIPLE_PROPERTY_ENTRY_PATTERN.test(newBhkLabel)) {
      setBatchDetails(newBhkLabel);
      setUploadMode('multiple');
      return;
    }

    setIsSubmittingListing(true);
    setPublishSuccessNotification(null);

    try {
      // Parsing is read-only. The administrator must review the canonical backend
      // result before the separate publish action can create a listing.
      const parsed = await propertyService.parsePropertyPrompt(newBhkLabel, singleInputSourceRef.current);
      setLastExtractedResult({
        ...parsed,
        rawInput: newBhkLabel,
        adminVerified: false,
        extractedAt: new Date().toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
      });
      notifyInfo(
        'Review property details',
        parsed.requiresReview
          ? 'Some values are missing or conflict. Review and correct them before publishing.'
          : 'Review the extracted values, then select “Publish reviewed listing” to publish it.',
        undefined,
        'PROPERTY'
      );
    } catch (err: unknown) {
      console.error('Failed to parse property listing:', err);
      showErrorDialog({
        title: 'Unable to review property details',
        message: getErrorMessage(err, 'Please check the property details and try again.'),
        details: getErrorDetails(err)
      });
    } finally {
      setIsSubmittingListing(false);
    }
  };

  const prepareAndAttachMedia = async (files: FileList | File[]) => {
    const fileArray = Array.from(files).filter(
      f => f.type.startsWith('image/') || f.type.startsWith('video/')
    );
    if (fileArray.length === 0) return;

    setUploadStatusMsg(`Preparing ${fileArray.length} ${fileArray.length === 1 ? 'file' : 'files'}…`);
    const preparedFiles: File[] = [];
    const preparationErrors: string[] = [];

    for (const file of fileArray) {
      try {
        const prepared = await prepareMediaForUpload(file);
        preparedFiles.push(prepared);
      } catch (error) {
        preparationErrors.push(getErrorMessage(error, `${file.name} could not be prepared.`));
      }
    }

    if (preparedFiles.length > 0) {
      const startIdx = attachedMediaFiles.length;
      const newTags: Record<number, RoomTag> = { ...attachedMediaTags };
      preparedFiles.forEach((_, i) => {
        newTags[startIdx + i] = DEFAULT_SMART_TAG_SEQUENCE[(startIdx + i) % DEFAULT_SMART_TAG_SEQUENCE.length];
      });
      setAttachedMediaTags(newTags);
      setAttachedMediaFiles(prev => [...prev, ...preparedFiles]);
      setUploadStatusMsg(`${preparedFiles.length} ${preparedFiles.length === 1 ? 'file' : 'files'} prepared and ready.`);

      const draftId = singleDraft.ensureDraftId();
      preparedFiles.forEach((file, i) => {
        const idx = startIdx + i;
        const tag = newTags[idx] || 'LIVING_ROOM';
        draftService.stageMedia(draftId, file, {
          roomTag: tag,
          isCover: idx === coverPhotoIndex
        }).catch(err => console.warn('Draft media staging notice:', err));
      });
    }

    if (preparationErrors.length > 0) {
      showErrorDialog({
        title: preparedFiles.length > 0 ? 'Some media could not be added' : 'Media could not be added',
        message: preparedFiles.length > 0
          ? 'Supported files were prepared. Review the files that still need attention.'
          : 'Choose supported media within the displayed limits and try again.',
        details: preparationErrors.join(' • ')
      });
    }
  };

  const handleMediaSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files.length > 0) {
      void prepareAndAttachMedia(e.target.files);
    }
  };

  const handleMediaDrop = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setIsDragOverMedia(false);
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      void prepareAndAttachMedia(e.dataTransfer.files);
    }
  };

  const handleRemoveAttachedMedia = (index: number) => {
    const removedFile = attachedMediaFiles[index];
    if (removedFile) {
      revokeMediaPreviewUrl(removedFile);
    }
    setAttachedMediaFiles(prev => prev.filter((_, i) => i !== index));
    setFailedMediaUploads(prev => prev.filter(item => item.originalIndex !== index));
    const updatedTags: Record<number, RoomTag> = {};
    attachedMediaFiles.filter((_, i) => i !== index).forEach((_, newIdx) => {
      const origKey = newIdx >= index ? newIdx + 1 : newIdx;
      if (attachedMediaTags[origKey]) updatedTags[newIdx] = attachedMediaTags[origKey];
    });
    setAttachedMediaTags(updatedTags);
    if (coverPhotoIndex === index) {
      setCoverPhotoIndex(0);
    } else if (coverPhotoIndex > index) {
      setCoverPhotoIndex(coverPhotoIndex - 1);
    }
    if (previewLightboxIndex === index) setPreviewLightboxIndex(null);
    else if (previewLightboxIndex !== null && previewLightboxIndex > index) {
      setPreviewLightboxIndex(previewLightboxIndex - 1);
    }
  };

  const handleDisbursePayroll = (id: number) => {
    const gb = groundBoys.find(g => g.id === id);
    setGroundBoys(groundBoys.map(g => g.id === id ? { ...g, status: "DISBURSED" } : g));
    notifySuccess('💸 Payroll Disbursed', `Base salary ₹15,000 disbursed to ${gb?.name || 'Ground Escort Staff'}`, `Sector: ${gb?.sector} • ${gb?.dealsClosed} Deals Closed`, 'PAYROLL');
  };

  const handleApproveCashback = (id: string) => {
    const item = cashbacks.find(c => c.id === id);
    setCashbacks(cashbacks.map(c => c.id === id ? { ...c, status: "APPROVED" } : c));
    notifySuccess('💰 ₹1,000 Cashback Approved', `Tenant lease cashback released for ${item?.tenantName || 'Tenant'}`, `Direct Bank UPI Payout • ${item?.propertyTitle}`, 'APPROVAL');
  };

  const handleApprovePlot = (id: string) => {
    setPlots(plots.map(p => p.id === id ? { ...p, status: "APPROVED" } : p));
    notifySuccess('📌 Plot Approval Verified', `Commercial plot listing ${id} approved & published`, undefined, 'APPROVAL');
  };

  const handleApproveLeave = (id: string) => {
    setLeaves(leaves.map(l => l.id === id ? { ...l, status: "APPROVED" } : l));
    notifyInfo('📅 Staff Leave Approved', `Leave request ID ${id} approved`, 'Staff Roster updated in realtime', 'SYSTEM');
  };

  const handleRejectLeave = (id: string) => {
    setLeaves(leaves.map(l => l.id === id ? { ...l, status: "REJECTED" } : l));
    notifyWarning('📅 Staff Leave Rejected', `Leave request ID ${id} rejected`, 'Staff Roster updated', 'SYSTEM');
  };

  const adminNavItems = [
    { id: 'funnel', label: 'Funnel & Analytics', badge: '18%', icon: BarChart3, color: 'text-emerald-600' },
    { id: 'crm', label: 'Staff CRM & Telemetry', badge: `${employees.length} Staff`, icon: Users, color: 'text-indigo-600' },
    { id: 'approval', label: 'Approvals Queue', badge: `${cashbacks.filter(c => c.status === 'PENDING').length} New`, icon: CheckSquare, color: 'text-amber-600' },
    { id: 'learning', label: 'Learning review', badge: 'Private', icon: ShieldCheck, color: 'text-emerald-600' },
    { id: 'config', label: 'Listing settings', badge: 'Ready', icon: SlidersHorizontal, color: 'text-purple-600' },
    { id: 'media', label: 'Update Property Listing', badge: 'Console', icon: UploadCloud, color: 'text-teal-600' },
    { id: 'failed-uploads', label: 'Failed Uploads', badge: failedUploadsCount > 0 ? `${failedUploadsCount} Issue${failedUploadsCount > 1 ? 's' : ''}` : 'Clear', icon: AlertTriangle, color: 'text-rose-600' }
  ];

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900 flex flex-col lg:flex-row min-w-0">

      {/* COMPACT NAVIGATION BAR FOR PHONES AND TABLETS */}
      <div className="lg:hidden sticky top-[74px] z-40 bg-slate-900 text-white border-b border-slate-800 px-3 min-[360px]:px-4 py-3 flex items-center justify-between gap-3 shadow-lg">
        <div className="flex min-w-0 items-center gap-2.5">
          <div className="w-8 h-8 rounded-xl bg-emerald-500/20 text-emerald-400 flex items-center justify-center font-bold border border-emerald-500/30">
            <ShieldCheck className="w-4 h-4" />
          </div>
          <div className="min-w-0">
            <h2 className="truncate text-xs font-black font-['Outfit'] text-white leading-tight">Pathome Admin Portal</h2>
            <span className="text-[10px] text-emerald-400 font-mono font-bold block">Indore Region HQ</span>
          </div>
        </div>

        <button
          onClick={() => setIsMobileMenuOpen(!isMobileMenuOpen)}
          className="p-2 rounded-xl bg-slate-800 text-emerald-300 hover:bg-slate-700 active:scale-95 transition-all flex items-center gap-1.5 cursor-pointer border border-slate-700"
        >
          {isMobileMenuOpen ? <XCircle className="w-4 h-4 text-rose-400" /> : <Menu className="w-4 h-4 text-emerald-400" />}
          <span className="text-xs font-bold font-mono">{isMobileMenuOpen ? "Close" : "Menu"}</span>
        </button>
      </div>

      {/* MOBILE DRAWER NAVIGATION MENU OVERLAY */}
      <AnimatePresence>
        {isMobileMenuOpen && (
          <div className="lg:hidden fixed inset-0 top-[124px] z-50 flex flex-col">
            {/* Backdrop */}
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.2 }}
              onClick={() => setIsMobileMenuOpen(false)}
              className="fixed inset-0 bg-slate-950/80 backdrop-blur-sm z-10"
            />
            {/* Drawer Content */}
            <motion.div
              initial={{ opacity: 0, y: -10 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -10 }}
              transition={{ duration: 0.2 }}
              className="relative z-20 bg-slate-950 text-white border-b border-slate-800 px-4 py-4 space-y-2 shadow-2xl max-h-[calc(100dvh-130px)] overflow-y-auto"
            >
              <div className="flex items-center justify-between text-[10px] font-mono font-bold text-slate-400 uppercase tracking-wider mb-2">
                <span>Select Navigation Panel:</span>
                <button
                  type="button"
                  onClick={() => setIsMobileMenuOpen(false)}
                  className="text-slate-400 hover:text-white p-1"
                  aria-label="Close menu"
                >
                  <XCircle className="w-4 h-4" />
                </button>
              </div>
              <div className="grid grid-cols-1 gap-2">
                {adminNavItems.map((item, index) => {
                  const isActive = activeTab === item.id || (activeTab === 'overview' && item.id === 'funnel');
                  const Icon = item.icon;
                  return (
                    <motion.button
                      key={item.id}
                      initial={{ opacity: 0, x: -14 }}
                      animate={{ opacity: 1, x: 0 }}
                      transition={{ delay: index * 0.04, type: "spring", stiffness: 400, damping: 24 }}
                      whileTap={{ scale: 0.97 }}
                      onClick={() => handleTabSelect(item.id)}
                      className={`w-full min-h-[44px] px-4 py-3 rounded-xl text-xs font-bold flex items-center justify-between transition-all cursor-pointer ${
                        isActive
                          ? 'bg-gradient-to-r from-emerald-600 to-teal-600 text-white shadow-lg shadow-emerald-600/30 font-black'
                          : 'bg-slate-900 text-slate-300 hover:bg-slate-800 border border-slate-800/80'
                      }`}
                    >
                      <div className="flex items-center gap-3">
                        <Icon className={`w-4 h-4 ${isActive ? 'text-white' : 'text-emerald-400'}`} />
                        <span className="font-['Outfit']">{item.label}</span>
                      </div>
                      <span className={`px-2 py-0.5 rounded-full text-[9px] font-mono font-bold border ${
                        isActive ? 'bg-emerald-700 text-emerald-100 border-emerald-500/40' : 'bg-slate-950 text-slate-400 border-slate-800'
                      }`}>
                        {item.badge}
                      </span>
                    </motion.button>
                  );
                })}
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>

      {/* 1. COLLAPSIBLE LEFT SIDEBAR NAVIGATION (LARGE DESKTOPS) */}
      <motion.aside
        animate={{ width: isSidebarCollapsed ? 80 : 280 }}
        transition={{ type: "spring", stiffness: 350, damping: 32 }}
        className="bg-white border-r border-slate-200/90 shadow-sm shrink-0 sticky top-[74.5px] h-[calc(100vh-74.5px)] flex flex-col justify-between z-30 select-none hidden lg:flex relative"
      >
        {/* FLOATING SIDEBAR COLLAPSE CHEVRON TOGGLE PILL */}
        <button
          onClick={() => setIsSidebarCollapsed(!isSidebarCollapsed)}
          className="absolute -right-3.5 top-6 z-40 w-7 h-7 rounded-full bg-white border border-slate-200/90 shadow-md flex items-center justify-center text-slate-600 hover:text-emerald-700 hover:border-emerald-300 hover:scale-110 active:scale-95 transition-all group"
          title={isSidebarCollapsed ? "Expand Sidebar Navigation" : "Collapse Sidebar Navigation"}
        >
          <motion.div animate={{ rotate: isSidebarCollapsed ? 180 : 0 }} transition={{ duration: 0.3 }}>
            <ChevronLeft className="w-4 h-4" />
          </motion.div>
        </button>

        <div className="p-4 space-y-6 overflow-y-auto no-scrollbar">

          {/* SIDEBAR TITLE */}
          <div className="flex items-center gap-3 pb-3 border-b border-slate-100 min-h-[44px]">
            <div className="w-9 h-9 rounded-xl bg-emerald-50 text-emerald-700 flex items-center justify-center font-bold border border-emerald-200/80 shrink-0 shadow-xs">
              <ShieldCheck className="w-5 h-5" />
            </div>

            {!isSidebarCollapsed && (
              <motion.div
                initial={{ opacity: 0, x: -6 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: -6 }}
                transition={{ duration: 0.2 }}
                className="min-w-0"
              >
                <h2 className="text-sm font-black font-['Outfit'] text-slate-900 leading-none truncate">
                  Admin Portal
                </h2>
                <span className="text-[10px] text-slate-500 font-semibold truncate block mt-0.5">Indore Region HQ</span>
              </motion.div>
            )}
          </div>

          {/* NAV ITEMS LIST WITH MAGNETIC LIQUID SLIDING TRANSITION */}
          <nav className="space-y-1.5 relative">
            {adminNavItems.map((item) => {
              const isActive = activeTab === item.id || (activeTab === 'overview' && item.id === 'funnel');
              const Icon = item.icon;
              return (
                <motion.button
                  key={item.id}
                  whileHover={!isActive ? { x: 5 } : { scale: 1.01 }}
                  whileTap={{ scale: 0.98 }}
                  transition={{ type: "spring", stiffness: 450, damping: 25 }}
                  onClick={() => handleTabSelect(item.id)}
                  className={`w-full relative px-3 py-3 rounded-2xl text-xs font-bold flex items-center justify-between transition-colors duration-200 group cursor-pointer ${
                    isActive ? 'text-white' : 'text-slate-700 hover:text-slate-900'
                  }`}
                  title={isSidebarCollapsed ? item.label : undefined}
                >
                  {/* LIQUID ACTIVE PILL SLIDER */}
                  {isActive && (
                    <motion.div
                      layoutId="activeSidebarIndicator"
                      className="absolute inset-0 bg-gradient-to-r from-emerald-600 via-teal-600 to-emerald-500 rounded-2xl shadow-lg shadow-emerald-600/30 z-0"
                      transition={{ type: "spring", stiffness: 450, damping: 32 }}
                    />
                  )}

                  <div className="relative z-10 flex items-center gap-2.5 min-w-0">
                    <Icon className={`w-4.5 h-4.5 shrink-0 transition-transform duration-200 group-hover:scale-110 ${isActive ? 'text-white' : 'text-slate-500 group-hover:text-emerald-600'}`} />
                    {!isSidebarCollapsed && (
                      <span className="truncate font-['Outfit'] font-bold text-xs">
                        {item.label}
                      </span>
                    )}
                  </div>

                  {!isSidebarCollapsed && (
                    <span
                      className={`relative z-10 px-2 py-0.5 rounded-full text-[9px] font-extrabold font-mono border shrink-0 ml-1 transition-colors ${isActive
                          ? 'bg-emerald-700 text-emerald-100 border-emerald-500/40 shadow-xs'
                          : 'bg-slate-100 text-slate-600 border-slate-200'
                        }`}
                    >
                      {item.badge}
                    </span>
                  )}

                  {/* SLEEK FLOATING TOOLTIP WHEN COLLAPSED */}
                  {isSidebarCollapsed && (
                    <div className="absolute left-full ml-3.5 px-3 py-1.5 bg-slate-900 text-white font-['Outfit'] font-extrabold text-xs rounded-xl shadow-2xl z-50 whitespace-nowrap opacity-0 group-hover:opacity-100 group-hover:translate-x-0 translate-x-1 pointer-events-none transition-all duration-200 flex items-center gap-2 border border-slate-800">
                      <span>{item.label}</span>
                      <span className="px-1.5 py-0.5 rounded-full text-[9px] font-mono bg-emerald-500 text-slate-950 font-black">{item.badge}</span>
                    </div>
                  )}
                </motion.button>
              );
            })}
          </nav>
        </div>

        {/* SIDEBAR FOOTER CARD */}
        {!isSidebarCollapsed && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ duration: 0.2 }}
            className="p-4 border-t border-slate-100 bg-slate-50/50"
          >
            <div className="bg-emerald-50 border border-emerald-200/90 rounded-2xl p-3.5 space-y-1">
              <div className="flex items-center justify-between text-xs font-bold text-emerald-900 font-['Outfit']">
                <span>System Status</span>
                <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
              </div>
              <p className="text-[11px] text-emerald-700 font-medium leading-relaxed">
                Property management is ready.
              </p>
            </div>
          </motion.div>
        )}
      </motion.aside>

      {/* 2. MAIN ADMIN CONTENT CONTAINER */}
      <main className="flex-1 min-w-0 max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4 sm:py-6 space-y-6 w-full">

        {/* EXECUTIVE PORTAL HEADER */}
        <div className={`${activeTab === 'media' ? 'hidden lg:block' : 'block'} relative overflow-hidden rounded-3xl border border-slate-200/90 bg-white p-4 text-slate-900 shadow-sm sm:p-6 lg:p-8`}>
          <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4 relative z-10">
            <div>
              <div className="flex items-center gap-2 flex-wrap">
                <span className="text-xs font-extrabold text-emerald-800 bg-emerald-50 px-3 py-1 rounded-full border border-emerald-200 flex items-center gap-1.5 font-mono">
                  <ShieldCheck className="w-3.5 h-3.5 text-emerald-600" />
                  Executive Analytics Portal
                </span>
                <span className="text-xs text-slate-500 font-mono font-semibold">Indore Region HQ</span>
              </div>
              <h1 className="text-2xl sm:text-3xl font-black font-['Outfit'] mt-2 text-slate-900 tracking-tight">
                Operations & Property Analytics Hub
              </h1>
              <p className="text-xs text-slate-500 mt-1">
                Visual Area Graphs, Conversion Funnels, GPS Telemetry Radar & Automated Property Ingestion.
              </p>
            </div>

            <div className="flex items-center gap-3 shrink-0 flex-wrap">
              <div className="bg-slate-50 px-4 py-2.5 rounded-2xl border border-slate-200 text-right font-mono">
                <span className="text-[10px] text-slate-500 block uppercase font-bold">Monthly Revenue</span>
                <span className="text-base sm:text-lg font-black text-emerald-700">₹14.2 Lakhs</span>
              </div>
              <div className="bg-slate-50 px-4 py-2.5 rounded-2xl border border-slate-200 text-right font-mono">
                <span className="text-[10px] text-slate-500 block uppercase font-bold">Active Employees</span>
                <span className="text-base sm:text-lg font-black text-amber-700">{employees.length} Staff</span>
              </div>
            </div>
          </div>

          {/* AMBIENT AURORA MESH */}
          <div className="absolute -top-24 -right-24 w-80 h-80 bg-emerald-500/5 rounded-full blur-3xl pointer-events-none" />
          <div className="absolute -bottom-24 -left-24 w-80 h-80 bg-teal-500/5 rounded-full blur-3xl pointer-events-none" />

          {/* 4 STAT BADGES WITH INTERACTIVE SPRING HOVER & GLOW */}
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 mt-6 pt-6 border-t border-slate-100 relative z-10">
            <motion.div
              whileHover={{ y: -4, scale: 1.02, transition: { type: 'spring', stiffness: 450, damping: 18 } }}
              whileTap={{ scale: 0.98 }}
              className="bg-slate-50/80 hover:bg-white p-3.5 rounded-2xl border border-slate-200/80 hover:border-slate-400 hover:shadow-lg hover:shadow-slate-200/60 transition-all cursor-pointer group"
            >
              <div className="flex items-center justify-between">
                <span className="text-[10px] text-slate-500 uppercase font-bold tracking-wider block group-hover:text-slate-900 transition-colors">Meta Ads Leads</span>
                <span className="text-[9px] font-mono font-bold text-emerald-700 bg-emerald-50 px-1.5 py-0.5 rounded border border-emerald-200">+18%</span>
              </div>
              <span className="text-xl font-black text-slate-900 font-mono mt-1 block">482 Total</span>
            </motion.div>

            <motion.div
              whileHover={{ y: -4, scale: 1.02, transition: { type: 'spring', stiffness: 450, damping: 18 } }}
              whileTap={{ scale: 0.98 }}
              className="bg-slate-50/80 hover:bg-white p-3.5 rounded-2xl border border-slate-200/80 hover:border-emerald-400 hover:shadow-lg hover:shadow-emerald-500/10 transition-all cursor-pointer group"
            >
              <div className="flex items-center justify-between">
                <span className="text-[10px] text-slate-500 uppercase font-bold tracking-wider block group-hover:text-emerald-800 transition-colors">Escorted Tours</span>
                <span className="text-[9px] font-mono font-bold text-emerald-700 bg-emerald-50 px-1.5 py-0.5 rounded border border-emerald-200">Active</span>
              </div>
              <span className="text-xl font-black text-emerald-700 font-mono mt-1 block">184 Passes</span>
            </motion.div>

            <motion.div
              whileHover={{ y: -4, scale: 1.02, transition: { type: 'spring', stiffness: 450, damping: 18 } }}
              whileTap={{ scale: 0.98 }}
              className="bg-slate-50/80 hover:bg-white p-3.5 rounded-2xl border border-slate-200/80 hover:border-amber-400 hover:shadow-lg hover:shadow-amber-500/10 transition-all cursor-pointer group"
            >
              <div className="flex items-center justify-between">
                <span className="text-[10px] text-slate-500 uppercase font-bold tracking-wider block group-hover:text-amber-800 transition-colors">Staff Online</span>
                <span className="text-[9px] font-mono font-bold text-amber-700 bg-amber-50 px-1.5 py-0.5 rounded border border-amber-200">GPS Live</span>
              </div>
              <span className="text-xl font-black text-amber-700 font-mono mt-1 block">2 / 3 Staff</span>
            </motion.div>

            <motion.div
              whileHover={{ y: -4, scale: 1.02, transition: { type: 'spring', stiffness: 450, damping: 18 } }}
              whileTap={{ scale: 0.98 }}
              className="bg-slate-50/80 hover:bg-white p-3.5 rounded-2xl border border-slate-200/80 hover:border-indigo-400 hover:shadow-lg hover:shadow-indigo-500/10 transition-all cursor-pointer group"
            >
              <div className="flex items-center justify-between">
                <span className="text-[10px] text-slate-500 uppercase font-bold tracking-wider block group-hover:text-indigo-800 transition-colors">Pending Leaves</span>
                <span className="text-[9px] font-mono font-bold text-indigo-700 bg-indigo-50 px-1.5 py-0.5 rounded border border-indigo-200">Audit</span>
              </div>
              <span className="text-xl font-black text-indigo-700 font-mono mt-1 block">{leaves.filter(l => l.status === 'PENDING').length} Requests</span>
            </motion.div>
          </div>
        </div>

        {/* 3. DYNAMIC TAB VIEW DISPLAY */}
        <AnimatePresence mode="wait">

          {/* TAB 1: FUNNEL HUB & GOOGLE VISUAL ANALYTICS GRAPH */}
          {(activeTab === 'funnel' || activeTab === 'overview') && (
            <motion.div
              key="tab-funnel"
              variants={containerVariants}
              initial="hidden"
              animate="visible"
              exit="exit"
              className="space-y-6"
            >
              {/* GOOGLE ANALYTICS REVENUE & TOUR AREA GRAPH */}
              <motion.div variants={cardVariants}>
                <RevenueAreaChart />
              </motion.div>

              {/* STEPPED FUNNEL FLOW GRAPH */}
              <motion.div variants={cardVariants}>
                <FunnelStepGraph />
              </motion.div>

              {/* INDORE SECTOR PERFORMANCE COMPARISON BAR CHART */}
              <motion.div variants={cardVariants}>
                <SectorPerformanceBarChart />
              </motion.div>
            </motion.div>
          )}

          {/* TAB 2: STAFF CRM & TELEMETRY */}
          {(activeTab === 'crm' || activeTab === 'employees' || activeTab === 'payroll') && (
            <motion.div
              key="tab-crm"
              variants={containerVariants}
              initial="hidden"
              animate="visible"
              exit="exit"
              className="space-y-6"
            >
              {/* GPS Telemetry Console */}
              <motion.div variants={cardVariants} className="bg-white text-slate-900 rounded-3xl p-6 sm:p-7 border border-slate-200/90 shadow-sm">
                <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 pb-4 mb-6 border-b border-slate-100">
                  <div>
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] font-black text-emerald-800 bg-emerald-50 px-2.5 py-1 rounded-full border border-emerald-200 uppercase font-mono flex items-center gap-1.5">
                        <span className="w-2 h-2 rounded-full bg-emerald-500 animate-ping"></span>
                        Real-Time GPS Radar
                      </span>
                    </div>
                    <h3 className="text-xl font-black text-slate-900 font-['Outfit'] mt-1 flex items-center gap-2">
                      🌐 Live Field Escort GPS Telemetry & Tracking Console
                    </h3>
                  </div>

                  <button
                    onClick={() => notifyInfo('Location update complete', 'Escort locations have been refreshed for Indore.', undefined, 'SYSTEM')}
                    className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 text-white text-xs font-extrabold rounded-xl transition-all shadow-md shadow-emerald-600/20 flex items-center gap-1.5"
                  >
                    📡 Ping Live GPS Signals
                  </button>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div className="bg-slate-50 rounded-2xl p-5 border border-slate-200 hover:border-emerald-500/40 transition-all">
                    <div className="flex items-center justify-between mb-3">
                      <div className="flex items-center gap-2.5">
                        <div className="w-10 h-10 rounded-xl bg-emerald-100 border border-emerald-200 text-emerald-800 flex items-center justify-center font-bold text-sm">
                          RV
                        </div>
                        <div>
                          <h4 className="font-bold text-sm text-slate-900 flex items-center gap-1.5">
                            Rahul Verma <span className="text-[10px] text-emerald-800 bg-emerald-50 px-2 py-0.5 rounded border border-emerald-200 font-mono">Active Escort</span>
                          </h4>
                          <p className="text-[11px] text-slate-500 font-mono">ID: EMP-101 • Field Escort Lead</p>
                        </div>
                      </div>
                      <span className="px-2.5 py-1 rounded-full text-[10px] font-mono font-bold bg-emerald-50 text-emerald-800 border border-emerald-200 animate-pulse">
                        ● Live GPS Active
                      </span>
                    </div>

                    <div className="bg-white p-3.5 rounded-xl border border-slate-200/90 space-y-2 text-xs font-mono">
                      <div className="flex justify-between items-center text-slate-700">
                        <span className="text-slate-500 flex items-center gap-1"><Zap className="w-3.5 h-3.5 text-emerald-600" /> GPS Telemetry:</span>
                        <span className="text-emerald-700 font-bold">22.7533° N, 75.8937° E</span>
                      </div>
                      <div className="flex justify-between items-center text-slate-700">
                        <span className="text-slate-500 flex items-center gap-1"><FileText className="w-3.5 h-3.5 text-indigo-600" /> Sector Landmark:</span>
                        <span className="text-slate-900 font-bold">Vijay Nagar (C21 Mall Hub)</span>
                      </div>
                    </div>
                  </div>

                  <div className="bg-slate-50 rounded-2xl p-5 border border-slate-200 hover:border-indigo-500/40 transition-all">
                    <div className="flex items-center justify-between mb-3">
                      <div className="flex items-center gap-2.5">
                        <div className="w-10 h-10 rounded-xl bg-indigo-100 border border-indigo-200 text-indigo-800 flex items-center justify-center font-bold text-sm">
                          VS
                        </div>
                        <div>
                          <h4 className="font-bold text-sm text-slate-900 flex items-center gap-1.5">
                            Vikram Singh <span className="text-[10px] text-indigo-800 bg-indigo-50 px-2 py-0.5 rounded border border-indigo-200 font-mono">Verification Lead</span>
                          </h4>
                          <p className="text-[11px] text-slate-500 font-mono">ID: EMP-102 • Field Inspector</p>
                        </div>
                      </div>
                      <span className="px-2.5 py-1 rounded-full text-[10px] font-mono font-bold bg-cyan-50 text-cyan-800 border border-cyan-200">
                        ● Live GPS Active
                      </span>
                    </div>

                    <div className="bg-white p-3.5 rounded-xl border border-slate-200/90 space-y-2 text-xs font-mono">
                      <div className="flex justify-between items-center text-slate-700">
                        <span className="text-slate-500 flex items-center gap-1"><Zap className="w-3.5 h-3.5 text-cyan-600" /> GPS Telemetry:</span>
                        <span className="text-cyan-700 font-bold">22.6900° N, 75.8650° E</span>
                      </div>
                      <div className="flex justify-between items-center text-slate-700">
                        <span className="text-slate-500 flex items-center gap-1"><FileText className="w-3.5 h-3.5 text-indigo-600" /> Sector Landmark:</span>
                        <span className="text-slate-900 font-bold">Bhawarkua Coaching Hub</span>
                      </div>
                    </div>
                  </div>
                </div>
              </motion.div>

              {/* Staff Roster */}
              <motion.div variants={cardVariants} className="bg-white rounded-3xl p-6 sm:p-7 border border-slate-200/90 shadow-sm">
                <div className="flex items-center justify-between mb-6">
                  <div>
                    <h3 className="text-lg font-bold text-slate-900 font-['Outfit']">Staff Roster & Performance Audit</h3>
                    <span className="text-xs text-slate-500">Employee profiles, assigned sectors & rating metrics</span>
                  </div>
                  <span className="text-xs font-mono font-bold text-emerald-800 bg-emerald-50 px-3 py-1 rounded-full border border-emerald-200">
                    {employees.filter(e => e.status === 'ONLINE').length} Staff Online Now
                  </span>
                </div>

                <div className="space-y-3">
                  {employees.map((emp) => (
                    <div key={emp.id} className="bg-slate-50 p-4 rounded-2xl border border-slate-200 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                      <div className="space-y-1">
                        <div className="flex items-center gap-2">
                          <span className="font-mono text-xs font-bold text-amber-800 bg-amber-50 px-2 py-0.5 rounded border border-amber-200">{emp.id}</span>
                          <span className="font-extrabold text-sm text-slate-900">{emp.name}</span>
                          <span className="text-xs font-bold text-emerald-800 bg-emerald-50 px-2 py-0.5 rounded border border-emerald-200">{emp.role}</span>
                        </div>
                        <p className="text-xs text-slate-500 font-mono">
                          Sector: <span className="font-bold text-slate-800">{emp.sector}</span> • Phone: <span className="font-bold text-slate-800">{emp.phone}</span>
                        </p>
                      </div>

                      <span className={`px-3 py-1 rounded-xl text-xs font-extrabold font-mono border ${emp.status === 'ONLINE' ? 'bg-emerald-50 text-emerald-800 border-emerald-200' : 'bg-slate-200 text-slate-600 border-slate-300'
                        }`}>
                        {emp.status === 'ONLINE' ? '● Online' : '○ On Leave'}
                      </span>
                    </div>
                  ))}
                </div>
              </motion.div>
            </motion.div>
          )}

          {/* TAB 3: APPROVALS QUEUE */}
          {activeTab === 'approval' && (
            <motion.div
              key="tab-approval"
              variants={containerVariants}
              initial="hidden"
              animate="visible"
              exit="exit"
              className="space-y-6"
            >
              {/* Lease Cashback Approvals */}
              <motion.div variants={cardVariants} className="bg-white rounded-3xl p-6 sm:p-7 border border-slate-200/90 shadow-sm">
                <div className="flex items-center justify-between mb-4 pb-3 border-b border-slate-100">
                  <div>
                    <h3 className="text-lg font-bold text-slate-900 font-['Outfit']">Tenant Lease Cashback Approvals (₹1,000)</h3>
                    <span className="text-xs text-slate-500">Verify uploaded rent agreement PDFs to release ₹1,000 tenant cashback</span>
                  </div>
                  <span className="text-xs font-mono font-bold text-emerald-800 bg-emerald-50 px-3 py-1 rounded-full border border-emerald-200">
                    Direct Bank UPI Transfer
                  </span>
                </div>

                <div className="space-y-3">
                  {cashbacks.map((item) => (
                    <div key={item.id} className="bg-slate-50 p-4 rounded-2xl border border-slate-200 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                      <div className="space-y-1">
                        <div className="flex items-center gap-2">
                          <span className="font-mono text-xs font-bold text-slate-900">{item.id}</span>
                          <span className="text-xs font-bold text-slate-900">{item.tenantName}</span>
                        </div>
                        <p className="text-xs text-slate-600">{item.propertyTitle}</p>
                      </div>

                      {item.status === 'APPROVED' ? (
                        <span className="text-xs font-bold text-emerald-800 bg-emerald-50 px-3 py-1.5 rounded-xl border border-emerald-200 flex items-center gap-1">
                          <CheckCircle2 className="w-4 h-4 text-emerald-600" /> ₹1,000 Cashback Sent
                        </span>
                      ) : (
                        <motion.button
                          whileHover={{ scale: 1.05 }}
                          whileTap={{ scale: 0.95 }}
                          onClick={() => handleApproveCashback(item.id)}
                          className="bg-emerald-600 hover:bg-emerald-700 text-white font-extrabold text-xs px-4 py-2 rounded-xl shadow-md shadow-emerald-600/20"
                        >
                          Approve ₹1,000 Cashback
                        </motion.button>
                      )}
                    </div>
                  ))}
                </div>
              </motion.div>
            </motion.div>
          )}

          {activeTab === 'learning' && <ParserLearningReviewPanel />}

          {activeTab === 'failed-uploads' && (
            <FailedUploadsPanel onCountChange={refreshFailedUploadsCount} />
          )}

          {/* TAB 4: BHK ENGINE & CLOUDINARY MEDIA CDN */}
          {(activeTab === 'config' || activeTab === 'media') && (
            <motion.div
              key={`tab-${activeTab}`}
              variants={containerVariants}
              initial="hidden"
              animate="visible"
              exit="exit"
              className="space-y-6"
            >
              {/* PROPERTY UPLOAD WORKSPACE */}
              <motion.div ref={uploadConsoleRef} variants={cardVariants} className="relative overflow-hidden rounded-3xl border border-slate-800 bg-slate-900 p-3 text-white shadow-2xl min-[380px]:p-4 sm:p-7">
                {/* COOL ANIMATED AMBIENT AURORA GLOW ORBS */}
                <div className="absolute -top-28 -right-28 w-96 h-96 bg-emerald-500/10 rounded-full blur-3xl pointer-events-none animate-pulse" />
                <div className="absolute -bottom-28 -left-28 w-96 h-96 bg-cyan-500/10 rounded-full blur-3xl pointer-events-none animate-pulse" style={{ animationDelay: '1.5s' }} />

                {/* Header */}
                <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-5 mb-5 border-b border-slate-800 relative z-10">
                  <div className="space-y-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-[10px] font-black text-emerald-400 bg-emerald-950 px-3 py-1 rounded-full border border-emerald-800 uppercase font-mono tracking-wider flex items-center gap-1.5">
                        <Sparkles className="w-3.5 h-3.5 text-emerald-400" /> Property listings
                      </span>
                      <span className="text-[10px] font-black text-cyan-400 bg-cyan-950 px-3 py-1 rounded-full border border-cyan-800 uppercase font-mono tracking-wider">
                        Location details
                      </span>
                    </div>
                    <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                      <div>
                        <h2 className="text-xl sm:text-2xl font-black text-white font-['Outfit'] flex items-center gap-2">
                          <UploadCloud className="w-6 h-6 text-emerald-400" /> Add properties
                        </h2>
                        <p className="text-xs text-slate-400">
                          Create one listing or add several at once. Add details, photos, and video, then review before publishing.
                        </p>
                      </div>

                      {/* Action Controls: Compact Draft Controls */}
                      {uploadMode === 'single' && (
                        <div className="flex items-center shrink-0">
                          <DraftManagementBar
                            compact={true}
                            currentDraftId={singleDraft.currentDraftId}
                            draftType="SINGLE"
                            autosaveStatus={singleDraft.autosaveStatus}
                            lastSavedAt={singleDraft.lastSavedAt}
                            conflictMessage={singleDraft.conflictMessage}
                            drafts={singleDraft.draftsList}
                            isLoadingDrafts={singleDraft.isLoadingDrafts}
                            onSelectDraft={async (id) => {
                              const selected = singleDraft.draftsList.find((d) => d.draftId === id);
                              if (selected && selected.draftType === 'BATCH') {
                                setSelectedBatchDraftId(id);
                                setUploadMode('multiple');
                              } else {
                                setSelectedBatchDraftId(null);
                                setUploadMode('single');
                                await singleDraft.loadDraft(id);
                              }
                            }}
                            onStartNewDraft={() => void singleDraft.startNewDraft()}
                            onDiscardDraft={(id) => singleDraft.discardDraft(id)}
                            onResolveConflictKeepLocal={singleDraft.resolveConflictKeepLocal}
                            onResolveConflictReloadServer={singleDraft.resolveConflictReloadServer}
                            fetchingMediaProgress={mediaRestorationProgress}
                          />
                        </div>
                      )}
                    </div>
                  </div>
                </div>

                {/* ANIMATED PUBLICATION SUCCESS TOAST BANNER */}
                <AnimatePresence>
                  {publishSuccessNotification && (
                    <motion.div
                      initial={{ opacity: 0, y: -16, scale: 0.96 }}
                      animate={{ opacity: 1, y: 0, scale: 1 }}
                      exit={{ opacity: 0, y: -16, scale: 0.96 }}
                      transition={{ type: "spring", stiffness: 450, damping: 25 }}
                      className="mb-6 p-5 sm:p-6 rounded-2xl bg-gradient-to-r from-emerald-950 via-slate-950 to-teal-950 border-2 border-emerald-400 text-white shadow-2xl shadow-emerald-500/20 relative overflow-hidden z-20"
                    >
                      {/* Laser beam accent line */}
                      <div className="absolute inset-x-0 top-0 h-[2.5px] bg-gradient-to-r from-emerald-400 via-teal-300 to-amber-400 animate-scan-beam" />

                      <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 pb-4 border-b border-emerald-500/30">
                        <div className="flex items-center gap-3">
                          <div className="w-10 h-10 rounded-2xl bg-emerald-500/20 text-emerald-400 border border-emerald-400/50 flex items-center justify-center shrink-0 shadow-lg shadow-emerald-500/30">
                            <CheckCircle2 className="w-6 h-6 animate-bounce" />
                          </div>
                          <div>
                            <div className="flex items-center gap-2 flex-wrap">
                              <span className="text-[10px] font-mono font-black text-emerald-300 bg-emerald-900/80 px-2.5 py-0.5 rounded-full border border-emerald-500/40 uppercase">
                                ✓ Listing published
                              </span>
                              <span className="text-[10px] font-mono text-slate-400">
                                Published at {publishSuccessNotification.timestamp}
                              </span>
                            </div>
                            <h3 className="text-lg sm:text-xl font-black font-['Outfit'] text-white mt-1">
                              🎉 Property listing published
                            </h3>
                          </div>
                        </div>

                        <button
                          type="button"
                          onClick={() => setPublishSuccessNotification(null)}
                          className="px-3 py-1.5 bg-slate-900 hover:bg-slate-800 text-slate-300 hover:text-white text-xs font-bold font-mono rounded-xl border border-slate-700 transition-all cursor-pointer shrink-0"
                        >
                          ✕ Dismiss Notification
                        </button>
                      </div>

                      {/* PUBLISHED LISTING SUMMARY DETAIL GRID */}
                      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 mt-4 pt-1 text-xs font-mono">
                        <div className="bg-slate-900/90 p-3 rounded-xl border border-slate-800">
                          <span className="text-[9px] text-slate-400 block uppercase font-bold">🏢 Property Title</span>
                          <span className="text-xs font-black font-['Outfit'] text-emerald-300 truncate block mt-0.5" title={publishSuccessNotification.title}>
                            {publishSuccessNotification.title}
                          </span>
                        </div>

                        <div className="bg-slate-900/90 p-3 rounded-xl border border-slate-800">
                          <span className="text-[9px] text-slate-400 block uppercase font-bold">📍 Locality & City</span>
                          <span className="text-xs font-black font-['Outfit'] text-cyan-300 truncate block mt-0.5">
                            {publishSuccessNotification.sector}, {publishSuccessNotification.city}
                          </span>
                        </div>

                        <div className="bg-slate-900/90 p-3 rounded-xl border border-slate-800">
                          <span className="text-[9px] text-slate-400 block uppercase font-bold">💰 Monthly rent</span>
                          <span className="text-xs font-black font-['Outfit'] text-amber-300 truncate block mt-0.5">
                            {publishSuccessNotification.rentVal} • Saved
                          </span>
                        </div>

                        <div className="bg-slate-900/90 p-3 rounded-xl border border-slate-800">
                          <span className="text-[9px] text-slate-400 block uppercase font-bold">📸 Attached Media</span>
                          <span className="text-xs font-black font-['Outfit'] text-purple-300 truncate block mt-0.5">
                            {publishSuccessNotification.mediaCount > 0 ? `✓ ${publishSuccessNotification.mediaCount} File(s) Attached` : 'No Media Files Attached'}
                          </span>
                        </div>
                      </div>
                    </motion.div>
                  )}
                </AnimatePresence>

                {/* COMPACT VIEWPORT-ATTACHED UPLOAD PROGRESS NOTIFICATION (PORTAL TO DOCUMENT.BODY) */}
                {typeof document !== 'undefined' && createPortal(
                  <AnimatePresence>
                    {uploadPipeline.active && (
                      <motion.div
                        initial={{ opacity: 0, y: -16, scale: 0.96 }}
                        animate={{ opacity: 1, y: 0, scale: 1 }}
                        exit={{ opacity: 0, y: -16, scale: 0.96 }}
                        transition={{ type: "spring", stiffness: 450, damping: 28 }}
                        style={{
                          position: 'fixed',
                          top: 'max(5.25rem, calc(env(safe-area-inset-top, 0px) + 5rem))',
                          zIndex: 9995,
                          maxWidth: 'calc(100vw - 2rem)'
                        }}
                        className={`left-4 right-4 sm:right-auto sm:left-6 sm:w-[420px] ${
                          isSidebarCollapsed ? 'lg:left-[104px]' : 'lg:left-[304px]'
                        } p-3 sm:p-3.5 rounded-2xl border shadow-2xl backdrop-blur-2xl transition-all ${
                          uploadPipeline.stage === 'failure'
                            ? 'bg-rose-950/95 border-rose-500/60 text-rose-100 shadow-rose-950/60'
                            : uploadPipeline.stage === 'partial_failure'
                            ? 'bg-amber-950/95 border-amber-500/60 text-amber-100 shadow-amber-950/60'
                            : uploadPipeline.stage === 'success'
                            ? 'bg-emerald-950/95 border-emerald-500/60 text-emerald-100 shadow-emerald-950/60'
                            : 'bg-slate-950/95 border-cyan-500/50 text-cyan-100 shadow-cyan-950/50'
                        }`}
                        role="region"
                        aria-live="polite"
                        aria-label="Upload progress"
                      >
                        <div className="flex items-center justify-between gap-2.5">
                          <div className="flex items-center gap-2.5 min-w-0 flex-1">
                            <div className={`w-8 h-8 rounded-xl flex items-center justify-center shrink-0 border ${
                              uploadPipeline.stage === 'failure'
                                ? 'bg-rose-900/60 border-rose-400/40 text-rose-300'
                                : uploadPipeline.stage === 'partial_failure'
                                ? 'bg-amber-900/60 border-amber-400/40 text-amber-300'
                                : uploadPipeline.stage === 'success'
                                ? 'bg-emerald-900/60 border-emerald-400/40 text-emerald-300'
                                : 'bg-cyan-900/60 border-cyan-400/40 text-cyan-300'
                            }`}>
                              {uploadPipeline.stage === 'failure' ? (
                                <AlertCircle className="w-4 h-4" />
                              ) : uploadPipeline.stage === 'partial_failure' ? (
                                <AlertTriangle className="w-4 h-4" />
                              ) : uploadPipeline.stage === 'success' ? (
                                <CheckCircle2 className="w-4 h-4" />
                              ) : (
                                <RefreshCw className="w-4 h-4 animate-spin" />
                              )}
                            </div>

                            <div className="min-w-0 flex-1">
                              <div className="flex items-center gap-1.5 flex-wrap">
                                <span className="text-xs font-black font-['Outfit'] text-white truncate">
                                  {uploadPipeline.stage === 'validating' && 'Validating property details…'}
                                  {uploadPipeline.stage === 'saving_listing' && 'Creating property listing…'}
                                  {uploadPipeline.stage === 'preparing_media' && 'Preparing property media…'}
                                  {uploadPipeline.stage === 'uploading_media' && (
                                    <>
                                      Uploading media {uploadPipeline.currentMediaIndex} of {uploadPipeline.totalMediaCount}
                                      {uploadPipeline.currentFilePercent !== undefined && uploadPipeline.currentFilePercent > 0 ? ` • ${uploadPipeline.currentFilePercent}%` : ''}
                                    </>
                                  )}
                                  {uploadPipeline.stage === 'success' && 'Property listing published successfully!'}
                                  {uploadPipeline.stage === 'partial_failure' && 'Listing created with media upload notice'}
                                  {uploadPipeline.stage === 'failure' && 'Failed to publish property listing'}
                                </span>
                                {uploadPipeline.savedPropertyId && (
                                  <span className="px-1.5 py-0.2 rounded-full text-[9px] font-mono font-bold bg-slate-900/80 border border-white/20 text-white shrink-0">
                                    Listing #{uploadPipeline.savedPropertyId}
                                  </span>
                                )}
                              </div>
                              {uploadPipeline.stage === 'uploading_media' && uploadPipeline.currentMediaName ? (
                                <p className="text-[11px] text-slate-400 font-mono truncate mt-0.5">
                                  {uploadPipeline.currentMediaName}
                                </p>
                              ) : uploadPipeline.stageLabel || uploadPipeline.errorMessage ? (
                                <p className="text-[11px] text-slate-300 font-mono truncate mt-0.5">
                                  {uploadPipeline.stageLabel || uploadPipeline.errorMessage}
                                </p>
                              ) : null}
                            </div>
                          </div>

                          {/* Pipeline Actions */}
                          <div className="flex items-center gap-1.5 shrink-0">
                            {uploadPipeline.failedCount > 0 && (
                              <button
                                type="button"
                                onClick={() => void handleRetryFailedMedia()}
                                disabled={isUploadingMedia}
                                className="min-h-[36px] px-2.5 py-1 bg-rose-600 hover:bg-rose-500 text-white font-bold text-xs rounded-xl shadow cursor-pointer transition-all flex items-center justify-center gap-1"
                              >
                                <RefreshCw className={`w-3 h-3 ${isUploadingMedia ? 'animate-spin' : ''}`} />
                                <span>Retry ({uploadPipeline.failedCount})</span>
                              </button>
                            )}
                            {(uploadPipeline.stage === 'success' || uploadPipeline.stage === 'failure' || uploadPipeline.stage === 'partial_failure') && (
                              <button
                                type="button"
                                onClick={() => setUploadPipeline(prev => ({ ...prev, active: false }))}
                                className="min-h-[36px] px-2.5 py-1 bg-slate-900/80 hover:bg-slate-800 text-slate-300 hover:text-white font-bold text-xs rounded-xl border border-white/20 cursor-pointer transition-all"
                              >
                                Dismiss
                              </button>
                            )}
                          </div>
                        </div>

                        {/* Real Progress Bar for Active Uploads */}
                        {uploadPipeline.stage === 'uploading_media' && uploadPipeline.totalMediaCount > 0 && (
                          <div className="mt-2.5 pt-2 border-t border-cyan-500/20">
                            <div className="h-1.5 w-full bg-slate-900/80 rounded-full overflow-hidden border border-cyan-500/30">
                              <div
                                className="h-full bg-gradient-to-r from-cyan-400 to-emerald-400 rounded-full transition-all duration-300"
                                style={{
                                  width: `${Math.min(100, Math.max(5, Math.round(
                                    ((uploadPipeline.currentMediaIndex - 1 + ((uploadPipeline.currentFilePercent || 0) / 100)) / uploadPipeline.totalMediaCount) * 100
                                  )))}%`
                                }}
                              />
                            </div>
                          </div>
                        )}
                      </motion.div>
                    )}
                  </AnimatePresence>,
                  document.body
                )}

                {/* 4-STEP VISUAL WORKFLOW STEPPER */}
                <div className="relative z-10 mb-5 grid grid-cols-2 sm:grid-cols-4 gap-2 rounded-2xl border border-slate-800/90 bg-slate-950/90 p-2.5 sm:p-3 font-mono shadow-inner w-full">
                  <motion.div whileHover={{ scale: 1.02, y: -1 }} className={`flex min-w-0 flex-1 items-center gap-2 rounded-xl p-2 sm:p-2.5 transition-all ${
                    newBhkLabel ? 'bg-emerald-950/90 text-emerald-300 border border-emerald-500/40 shadow-sm' : 'bg-slate-900/80 text-slate-400 border border-slate-800/60'
                  }`}>
                    <div className="w-6 h-6 sm:w-7 sm:h-7 rounded-lg bg-emerald-500/20 text-emerald-400 flex items-center justify-center font-black text-xs shrink-0 border border-emerald-500/30">1</div>
                    <div className="text-[10px] sm:text-[11px] leading-tight min-w-0">
                      <div className="font-black uppercase text-[8px] sm:text-[9px] text-emerald-400 tracking-wider truncate">Step 1: Details</div>
                      <div className="truncate font-sans font-bold text-slate-200">Listing details</div>
                    </div>
                  </motion.div>

                  <motion.div whileHover={{ scale: 1.02, y: -1 }} className={`flex min-w-0 flex-1 items-center gap-2 rounded-xl p-2 sm:p-2.5 transition-all ${
                    liveExtractedPreview && !liveExtractedPreview.isGarbageInput ? 'bg-cyan-950/90 text-cyan-300 border border-cyan-500/40 shadow-sm' : 'bg-slate-900/80 text-slate-400 border border-slate-800/60'
                  }`}>
                    <div className="w-6 h-6 sm:w-7 sm:h-7 rounded-lg bg-cyan-500/20 text-cyan-400 flex items-center justify-center font-black text-xs shrink-0 border border-cyan-500/30">2</div>
                    <div className="text-[10px] sm:text-[11px] leading-tight min-w-0">
                      <div className="font-black uppercase text-[8px] sm:text-[9px] text-cyan-400 tracking-wider truncate">Step 2: Review</div>
                      <div className="truncate font-sans font-bold text-slate-200">Check fields</div>
                    </div>
                  </motion.div>

                  <motion.div whileHover={{ scale: 1.02, y: -1 }} className={`flex min-w-0 flex-1 items-center gap-2 rounded-xl p-2 sm:p-2.5 transition-all ${
                    attachedMediaFiles.length > 0 ? 'bg-indigo-950/90 text-indigo-300 border border-indigo-500/40 shadow-sm' : 'bg-slate-900/80 text-slate-400 border border-slate-800/60'
                  }`}>
                    <div className="w-6 h-6 sm:w-7 sm:h-7 rounded-lg bg-indigo-500/20 text-indigo-400 flex items-center justify-center font-black text-xs shrink-0 border border-indigo-500/30">3</div>
                    <div className="text-[10px] sm:text-[11px] leading-tight min-w-0">
                      <div className="font-black uppercase text-[8px] sm:text-[9px] text-indigo-400 tracking-wider truncate">Step 3: Media</div>
                      <div className="truncate font-sans font-bold text-slate-200">Photos & video</div>
                    </div>
                  </motion.div>

                  <motion.div whileHover={{ scale: 1.02, y: -1 }} className={`flex min-w-0 flex-1 items-center gap-2 rounded-xl p-2 sm:p-2.5 transition-all ${
                    lastExtractedResult?.savedToDatabase ? 'bg-purple-950/90 text-purple-300 border border-purple-500/40 shadow-sm' : 'bg-slate-900/80 text-slate-400 border border-slate-800/60'
                  }`}>
                    <div className="w-6 h-6 sm:w-7 sm:h-7 rounded-lg bg-purple-500/20 text-purple-400 flex items-center justify-center font-black text-xs shrink-0 border border-purple-500/30">4</div>
                    <div className="text-[10px] sm:text-[11px] leading-tight min-w-0">
                      <div className="font-black uppercase text-[8px] sm:text-[9px] text-purple-400 tracking-wider truncate">Step 4: Publish</div>
                      <div className="truncate font-sans font-bold text-slate-200">Live listing</div>
                    </div>
                  </motion.div>
                </div>

                {uploadMode === 'single' ? (
                <form onSubmit={handleAddCustomBhk} className="space-y-6 relative z-10 w-full">
                  {/* FULL-WIDTH AI STUDIO CONSOLE CONTAINER */}
                  <div className="w-full space-y-5">
                    
                    {/* 1-CLICK PRESET TOOLBAR */}
                    <div className="bg-slate-950/90 p-3.5 rounded-2xl border border-slate-800/90 shadow-md space-y-2.5 w-full">
                      <div className="flex items-center justify-between">
                        <label className="text-[11px] font-mono font-extrabold text-slate-300 flex items-center gap-1.5 uppercase tracking-wide">
                          <Zap className="w-3.5 h-3.5 text-amber-400 fill-current animate-pulse" />
                          Property examples
                        </label>
                        {newBhkLabel && (
                          <motion.button
                            whileHover={{ scale: 1.05 }}
                            whileTap={{ scale: 0.95 }}
                            type="button"
                            onClick={() => handleSinglePromptChange('')}
                            className="text-[10px] text-rose-400 hover:text-rose-300 font-bold font-mono transition-colors cursor-pointer flex items-center gap-1 bg-rose-950/50 px-2 py-0.5 rounded-lg border border-rose-800/50"
                          >
                            <span>↺ Clear selection</span>
                          </motion.button>
                        )}
                      </div>

                      {/* RESPONSIVE EXAMPLES CAROUSEL / GRID */}
                      <div className="no-scrollbar flex w-full snap-x snap-mandatory touch-pan-x gap-2.5 overflow-x-auto pb-1 xl:grid xl:grid-cols-4 xl:overflow-visible xl:pb-0">
                        {PRESET_PROMPTS.map((preset) => {
                          const isSelected = newBhkLabel === preset.text;
                          return (
                            <motion.button
                              key={preset.id}
                              type="button"
                              whileHover={{ scale: 1.02, y: -1 }}
                              whileTap={{ scale: 0.97 }}
                              transition={{ type: "spring", stiffness: 450, damping: 18 }}
                              onClick={() => {
                                singleInputSourceRef.current = 'TYPED';
                                handleSinglePromptChange(preset.text);
                              }}
                              className={`flex min-w-[250px] max-w-[300px] sm:min-w-[260px] flex-1 snap-start flex-col justify-between gap-1.5 rounded-xl border p-3 text-left transition-all cursor-pointer xl:min-w-0 xl:max-w-none ${
                                isSelected
                                  ? 'bg-emerald-950/90 border-emerald-400 text-emerald-200 shadow-lg shadow-emerald-950/80 ring-1 ring-emerald-500/50'
                                  : 'bg-slate-900/90 hover:bg-slate-800/90 text-slate-300 border-slate-800 hover:border-slate-700'
                              }`}
                            >
                              <div className="flex items-center justify-between w-full gap-2">
                                <span className="text-xs font-black font-['Outfit'] text-white leading-tight truncate">{preset.label}</span>
                                <span className="text-[9px] font-mono font-black text-amber-400 bg-amber-950/90 border border-amber-500/40 px-1.5 py-0.5 rounded-md shrink-0">
                                  {preset.badge}
                                </span>
                              </div>
                              <p className="text-[10px] text-slate-400 font-mono line-clamp-1 leading-tight">
                                {preset.subtitle}
                              </p>
                            </motion.button>
                          );
                        })}
                      </div>
                    </div>

                    {/* AI PROMPT COMPOSER STUDIO */}
                    <div className="space-y-2 w-full">
                      <div className="flex items-center justify-between gap-2 flex-wrap">
                        <div>
                          <label className="text-xs font-extrabold text-slate-300 flex items-center gap-1.5 font-mono uppercase tracking-wide">
                            <Sparkles className="w-3.5 h-3.5 text-emerald-400 animate-pulse" />
                            Property details
                          </label>
                          <p className="mt-1 text-[10px] text-slate-500">For several listings, use “Add another property” or paste numbered details.</p>
                        </div>
                        <div className="flex items-center gap-2">
                          <motion.button
                            whileHover={{ scale: 1.04 }}
                            whileTap={{ scale: 0.96 }}
                            type="button"
                            onClick={toggleSingleMic}
                            className={`px-3 py-1.5 rounded-xl text-xs font-bold flex items-center gap-1.5 transition-all cursor-pointer border ${
                              isSingleMicListening
                                ? 'bg-rose-500 text-white border-rose-400 shadow-lg shadow-rose-500/30 animate-pulse'
                                : 'bg-slate-900 hover:bg-slate-800 text-slate-300 border-slate-700 hover:border-emerald-500/50'
                            }`}
                          >
                            {isSingleMicListening ? <MicOff className="w-3.5 h-3.5" /> : <Mic className="w-3.5 h-3.5 text-emerald-400" />}
                            <span>{isSingleMicListening ? 'Listening…' : 'Dictate details'}</span>
                          </motion.button>
                          <span className="text-[10px] font-mono font-bold text-emerald-400 bg-emerald-950/80 px-2.5 py-1 rounded-full border border-emerald-800/80">
                            {newBhkLabel.length} characters
                          </span>
                        </div>
                      </div>

                      <AnimatePresence initial={false}>
                        {isSingleDictationChoiceOpen && (
                          <motion.div
                            initial={{ opacity: 0, y: -6 }}
                            animate={{ opacity: 1, y: 0 }}
                            exit={{ opacity: 0, y: -6 }}
                            transition={{ duration: 0.16 }}
                            role="dialog"
                            aria-label="Choose how to add dictation"
                            className="rounded-2xl border border-amber-400/25 bg-amber-400/[0.06] p-3 shadow-lg shadow-slate-950/20"
                          >
                            <div className="flex flex-wrap items-center justify-between gap-2">
                              <div>
                                <p className="text-xs font-bold text-slate-100">How should dictation be added?</p>
                                <p className="mt-0.5 text-[11px] text-slate-400">Your current draft stays unchanged until you choose.</p>
                              </div>
                              <button
                                type="button"
                                onClick={() => setIsSingleDictationChoiceOpen(false)}
                                aria-label="Cancel dictation"
                                className="rounded-lg p-1 text-slate-400 transition-colors hover:bg-slate-800 hover:text-white"
                              >
                                <X className="h-4 w-4" />
                              </button>
                            </div>
                            <div className="mt-3 flex flex-wrap gap-2">
                              <button
                                type="button"
                                onClick={() => startSingleDictation('replace')}
                                className="rounded-xl bg-amber-400 px-3 py-2 text-xs font-bold text-slate-950 transition-colors hover:bg-amber-300"
                              >
                                Start a new voice draft
                              </button>
                              <button
                                type="button"
                                onClick={() => startSingleDictation('append')}
                                className="rounded-xl border border-slate-700 bg-slate-800 px-3 py-2 text-xs font-bold text-slate-200 transition-colors hover:border-slate-600 hover:text-white"
                              >
                                Add to current draft
                              </button>
                            </div>
                          </motion.div>
                        )}
                      </AnimatePresence>

                      {/* GLOWING FULL-WIDTH PROMPT COMPOSER BOX */}
                      <div className="relative group w-full">
                        <div className="absolute -inset-0.5 bg-gradient-to-r from-emerald-500 via-teal-400 to-cyan-500 rounded-3xl blur-md opacity-25 group-hover:opacity-50 transition duration-500 pointer-events-none" />

                        <div className="relative bg-slate-950 rounded-2xl border border-slate-800 shadow-2xl overflow-hidden w-full">
                          <div className="h-1 w-full bg-gradient-to-r from-emerald-500 via-teal-400 via-cyan-400 to-amber-400 animate-scan-beam" />

                          <div className="p-3.5 sm:p-4 space-y-3">
                            <textarea
                              rows={4}
                              value={newBhkLabel}
                              onChange={(e) => handleSinglePromptChange(e.target.value)}
                              placeholder="Type or edit property details here. Include layout, location, rent, deposit, owner contact, furnishing, and availability."
                              className="w-full bg-transparent text-emerald-300 placeholder-slate-500 text-[16px] sm:text-sm font-mono border-0 focus:ring-0 outline-none leading-relaxed resize-none"
                            />

                            {/* RESPONSIVE BOTTOM ACTION TOOLBAR */}
                            <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between pt-3 border-t border-slate-800/80 gap-3">
                              <div className="grid grid-cols-1 min-[420px]:grid-cols-2 sm:flex sm:flex-wrap items-center gap-2 w-full sm:w-auto">
                                <motion.button
                                  whileHover={{ scale: 1.02, y: -1 }}
                                  whileTap={{ scale: 0.97 }}
                                  type="button"
                                  onClick={handleOpenMediaUpload}
                                  className={`min-h-[44px] px-4 py-2.5 rounded-xl text-xs font-mono font-extrabold transition-all cursor-pointer flex items-center justify-center gap-2 border w-full sm:w-auto ${
                                    attachedMediaFiles.length > 0
                                      ? 'bg-cyan-950 text-cyan-300 border-cyan-500/50 shadow-md shadow-cyan-950/50'
                                      : 'bg-slate-900 hover:bg-slate-800 text-cyan-300 border-slate-800 hover:border-cyan-500/40'
                                  }`}
                                >
                                  <Camera className="w-4 h-4 text-cyan-400 shrink-0" />
                                  <span className="truncate">{attachedMediaFiles.length > 0 ? `Media added (${attachedMediaFiles.length})` : 'Add photos or video'}</span>
                                </motion.button>

                                {mediaRestorationProgress && (
                                  <div className="min-h-[44px] px-3.5 py-2.5 rounded-xl bg-cyan-950/90 border border-cyan-500/50 text-cyan-300 text-xs font-mono font-bold flex items-center justify-center gap-2 shadow-md shadow-cyan-950/50 animate-pulse w-full sm:w-auto">
                                    <RefreshCw className="w-3.5 h-3.5 animate-spin text-cyan-400 shrink-0" />
                                    <span>Fetching media... {mediaRestorationProgress.loaded} of {mediaRestorationProgress.total}</span>
                                  </div>
                                )}

                                {newBhkLabel.trim() && (
                                  <motion.button
                                    whileHover={{ scale: 1.02, y: -1 }}
                                    whileTap={{ scale: 0.97 }}
                                    type="button"
                                    onClick={() => setNewBhkLabel((details) => {
                                      const nextDetails = `${details.trimEnd()}\n\nNext property\n`;
                                      singleMicBaseTextRef.current = nextDetails;
                                      return nextDetails;
                                    })}
                                    className="min-h-[44px] px-4 py-2.5 rounded-xl text-xs font-extrabold text-amber-200 bg-amber-950/50 hover:bg-amber-950 border border-amber-500/40 hover:border-amber-400/70 transition-all cursor-pointer flex items-center justify-center gap-2 w-full sm:w-auto"
                                  >
                                    <Layers className="w-4 h-4 text-amber-400 shrink-0" />
                                    <span className="truncate">Add another property</span>
                                  </motion.button>
                                )}
                              </div>

                              <motion.button
                                disabled={isSubmittingListing}
                                whileHover={!isSubmittingListing ? { scale: 1.02, y: -1, boxShadow: "0 0 30px rgba(16, 185, 129, 0.6)" } : {}}
                                whileTap={!isSubmittingListing ? { scale: 0.97 } : {}}
                                transition={{ type: "spring", stiffness: 450, damping: 18 }}
                                type="submit"
                                className={`w-full sm:w-auto min-h-[44px] px-7 py-3 bg-gradient-to-r from-emerald-600 via-teal-600 to-emerald-600 hover:from-emerald-500 hover:to-teal-500 text-white font-black text-xs sm:text-sm rounded-xl transition-all shadow-lg shadow-emerald-600/30 flex items-center justify-center gap-2 cursor-pointer ${
                                  isSubmittingListing ? 'opacity-80 cursor-wait' : ''
                                }`}
                              >
                                {isSubmittingListing ? (
                                  <>
                                    <Sparkles className="w-4 h-4 text-emerald-300 animate-spin shrink-0" />
                                    <span>Extracting property details...</span>
                                  </>
                                ) : (
                                  <>
                                    <Sparkles className="w-4 h-4 text-amber-300 animate-pulse shrink-0" />
                                    <span>{MULTIPLE_PROPERTY_ENTRY_PATTERN.test(newBhkLabel) ? 'Review multiple properties' : 'Review property details'}</span>
                                  </>
                                )}
                              </motion.button>
                            </div>
                          </div>
                        </div>
                      </div>

                      {/* ATTACHED MEDIA CHIPS PREVIEW BELOW COMPOSER */}
                      {attachedMediaFiles.length > 0 && (
                        <div className="bg-slate-950/80 p-3 rounded-2xl border border-slate-800 flex items-center gap-2 overflow-x-auto no-scrollbar w-full">
                          <span className="text-[10px] font-mono font-bold text-slate-400 uppercase tracking-wider shrink-0 flex items-center gap-1">
                            <Camera className="w-3 h-3 text-cyan-400" /> Attached:
                          </span>
                          {attachedMediaFiles.map((file, idx) => (
                            <div
                              key={idx}
                              className={`text-[10px] font-mono font-bold px-2.5 py-1 rounded-lg border flex items-center gap-1.5 shrink-0 shadow-xs transition-all ${
                                coverPhotoIndex === idx
                                  ? 'bg-amber-950/80 border-amber-500/60 text-amber-200'
                                  : 'bg-slate-900 border-slate-700 text-slate-200'
                              }`}
                            >
                              {file.type.startsWith('video/') ? (
                                <Video className="w-3 h-3 text-indigo-400 shrink-0" />
                              ) : (
                                <Camera className="w-3 h-3 text-cyan-400 shrink-0" />
                              )}
                              <span className="max-w-[130px] truncate">{file.name}</span>
                              <button
                                type="button"
                                onClick={() => {
                                  setCoverPhotoIndex(idx);
                                  notifySuccess('⭐ Cover Photo Selected', `Photo #${idx + 1} (${file.name}) set as primary listing cover`);
                                }}
                                title={coverPhotoIndex === idx ? "Primary Cover Photo" : "Click to set as primary cover"}
                                className={`px-1.5 py-0.5 rounded text-[9px] font-extrabold flex items-center gap-0.5 transition-all cursor-pointer ${
                                  coverPhotoIndex === idx
                                    ? 'bg-amber-400 text-slate-950 ring-1 ring-amber-300'
                                    : 'text-slate-400 hover:text-amber-300 bg-slate-800'
                                }`}
                              >
                                <Star className={`w-2.5 h-2.5 ${coverPhotoIndex === idx ? 'fill-current text-slate-950' : 'text-slate-400'}`} />
                                <span>{coverPhotoIndex === idx ? 'Cover' : 'Set Cover'}</span>
                              </button>
                              <button
                                type="button"
                                onClick={() => handleRemoveAttachedMedia(idx)}
                                className="text-rose-400 hover:text-rose-300 ml-0.5 font-black cursor-pointer"
                              >
                                ✕
                              </button>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>

                    {/* LIVE REAL-TIME IDENTIFIED PROPERTY ATTRIBUTES (100% FULL WIDTH) */}
                    {liveExtractedPreview && (
                      <div className={`p-5 rounded-2xl border space-y-4 shadow-xl transition-all relative overflow-hidden w-full ${
                        liveExtractedPreview.requiresReview
                          ? 'bg-amber-950/40 border-amber-500/40'
                          : 'bg-slate-950/95 border-emerald-500/40'
                      }`}>
                        {/* COOL CYBER SCAN BEAM ANIMATION ACROSS ATTRIBUTE CARD */}
                        <div className="absolute inset-x-0 top-0 h-[2px] bg-gradient-to-r from-transparent via-emerald-400 via-teal-300 to-transparent animate-scan-beam opacity-80 pointer-events-none" />

                        {/* INSPECTOR HEADER & QUICK TOOL BUTTONS */}
                        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-slate-800/80">
                          <div className="flex items-center gap-2 flex-wrap">
                            <span className={`text-xs sm:text-sm font-mono font-black uppercase tracking-wider flex items-center gap-1.5 ${
                              liveExtractedPreview.requiresReview ? 'text-amber-400' : 'text-emerald-400'
                            }`}>
                              <Zap className={`w-4 h-4 fill-current ${liveExtractedPreview.requiresReview ? 'text-amber-400' : 'text-emerald-400 animate-pulse'}`} />
                              {liveExtractedPreview.requiresReview
                                ? 'Review required before publishing'
                                : 'Extracted property attributes'}
                            </span>
                            <span className={`text-[9px] font-mono font-bold px-2.5 py-0.5 rounded-full border ${
                              liveExtractedPreview.requiresReview
                                ? 'text-amber-300 bg-amber-950/80 border-amber-700'
                                : 'text-emerald-300 bg-emerald-950 border-emerald-800'
                            }`}>
                              {liveExtractedPreview.requiresReview ? 'Review required' : 'Ready for review'}
                            </span>
                          </div>

                          {!liveExtractedPreview.isGarbageInput && (
                            <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-2 w-full sm:w-auto shrink-0 pt-2 sm:pt-0 border-t sm:border-t-0 border-slate-800">
                              {/* Primary Action Button - Full Width on Mobile with 44px min-height */}
                              <motion.button
                                whileHover={{ scale: 1.02 }}
                                whileTap={{ scale: 0.98 }}
                                type="button"
                                onClick={handleSaveToDatabase}
                                disabled={isSavingDb || isUploadingMedia || (liveExtractedPreview.savedToDatabase && failedMediaUploads.length === 0)}
                                aria-busy={isSavingDb || isUploadingMedia}
                                className={`min-h-[44px] px-4 py-2.5 ${
                                  liveExtractedPreview.savedToDatabase && failedMediaUploads.length > 0
                                    ? 'bg-rose-700 hover:bg-rose-600 border-rose-400/40 text-white'
                                    : 'bg-emerald-700 hover:bg-emerald-600 border-emerald-400/40 text-white'
                                } disabled:opacity-50 text-xs font-black rounded-xl transition-all border flex items-center justify-center gap-2 cursor-pointer shadow-md order-first sm:order-last w-full sm:w-auto`}
                              >
                                {liveExtractedPreview.savedToDatabase && failedMediaUploads.length > 0 ? (
                                  <>
                                    <RefreshCw className={`w-4 h-4 ${isUploadingMedia ? 'animate-spin' : ''}`} />
                                    <span>{isUploadingMedia ? 'Uploading media…' : `Retry failed media (${failedMediaUploads.length})`}</span>
                                  </>
                                ) : isSavingDb ? (
                                  <>
                                    <Database className="w-4 h-4 animate-pulse" />
                                    <span>Publishing listing…</span>
                                  </>
                                ) : isUploadingMedia ? (
                                  <>
                                    <RefreshCw className="w-4 h-4 animate-spin" />
                                    <span>Uploading media…</span>
                                  </>
                                ) : liveExtractedPreview.savedToDatabase ? (
                                  <>
                                    <CheckCircle2 className="w-4 h-4 text-emerald-400" />
                                    <span>Listing Published</span>
                                  </>
                                ) : (
                                  <>
                                    <Database className="w-4 h-4" />
                                    <span>Publish Reviewed Listing</span>
                                  </>
                                )}
                              </motion.button>

                              {/* Secondary Actions in Responsive Grid on Mobile */}
                              <div className="grid grid-cols-3 sm:flex items-center gap-2 w-full sm:w-auto">
                                <motion.button
                                  whileHover={{ scale: 1.03 }}
                                  whileTap={{ scale: 0.97 }}
                                  type="button"
                                  onClick={handleOpenInlineEdit}
                                  className="min-h-[40px] px-2.5 sm:px-3 py-1.5 bg-slate-900 hover:bg-slate-800 text-amber-300 text-[11px] font-extrabold rounded-xl transition-all border border-amber-500/40 flex items-center justify-center gap-1 cursor-pointer shadow-xs"
                                >
                                  <SlidersHorizontal className="w-3.5 h-3.5" />
                                  <span>Edit</span>
                                </motion.button>

                                <motion.button
                                  whileHover={{ scale: 1.03 }}
                                  whileTap={{ scale: 0.97 }}
                                  type="button"
                                  onClick={handleCopyJson}
                                  className="min-h-[40px] px-2.5 sm:px-3 py-1.5 bg-slate-900 hover:bg-slate-800 text-slate-200 text-[11px] font-bold rounded-xl transition-all border border-slate-700 flex items-center justify-center gap-1 cursor-pointer shadow-xs"
                                >
                                  {copiedJson ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5 text-slate-400" />}
                                  <span>{copiedJson ? 'Copied' : 'Copy'}</span>
                                </motion.button>

                                <motion.button
                                  whileHover={{ scale: 1.03 }}
                                  whileTap={{ scale: 0.97 }}
                                  type="button"
                                  onClick={() => {
                                    handleFillMediaFromExtracted();
                                    handleOpenMediaUpload();
                                  }}
                                  className="min-h-[40px] px-2.5 sm:px-3 py-1.5 bg-cyan-950/90 hover:bg-cyan-900 text-cyan-200 text-[11px] font-extrabold rounded-xl transition-all border border-cyan-500/40 flex items-center justify-center gap-1 cursor-pointer shadow-xs"
                                >
                                  <Tag className="w-3.5 h-3.5 text-cyan-400" />
                                  <span>Photos</span>
                                </motion.button>
                              </div>
                            </div>
                          )}
                        </div>

                        {/* COLLAPSED ATTRIBUTE OVERVIEW CARD OR FULL 18-CARD INSPECTION GRID */}
                        {isAttributesCollapsed ? (
                          <div className="bg-slate-900/90 rounded-2xl border border-slate-800 p-3.5 space-y-2.5">
                            <div className="flex flex-col min-[480px]:flex-row min-[480px]:items-center justify-between gap-2.5">
                              <div className="flex items-center gap-2 flex-wrap min-w-0">
                                <span className="text-xs sm:text-sm font-black text-white font-['Outfit']">
                                  {[liveExtractedPreview.bhk, liveExtractedPreview.propertyType, liveExtractedPreview.locality, liveExtractedPreview.city].filter(v => v && v !== 'Unspecified').join(' • ') || 'Property details extracted'}
                                </span>
                                {liveExtractedPreview.expectedRent && liveExtractedPreview.expectedRent !== 'Unspecified' && (
                                  <span className="text-xs font-mono font-bold text-amber-300 bg-amber-950/80 px-2 py-0.5 rounded-md border border-amber-800/60">
                                    {liveExtractedPreview.expectedRent}
                                  </span>
                                )}
                              </div>

                              <motion.button
                                whileHover={{ scale: 1.02 }}
                                whileTap={{ scale: 0.98 }}
                                type="button"
                                onClick={() => setIsAttributesCollapsed(false)}
                                className="min-h-[40px] px-3.5 py-1.5 rounded-xl text-xs font-bold text-emerald-400 bg-emerald-950/80 hover:bg-emerald-900 border border-emerald-500/40 hover:border-emerald-400 transition-all flex items-center justify-center gap-1.5 cursor-pointer shrink-0"
                              >
                                <span>View all 18 attributes</span>
                                <ChevronDown className="w-4 h-4" />
                              </motion.button>
                            </div>

                            {/* Quick mini-specs pill row */}
                            <div className="flex items-center gap-2 overflow-x-auto no-scrollbar pt-1 text-[11px] font-mono text-slate-400">
                              {liveExtractedPreview.bathrooms && liveExtractedPreview.bathrooms !== 'Unspecified' && (
                                <span className="bg-slate-950/80 px-2 py-0.5 rounded-md border border-slate-800 shrink-0">
                                  🚿 {liveExtractedPreview.bathrooms} Baths
                                </span>
                              )}
                              {liveExtractedPreview.furnishing && liveExtractedPreview.furnishing !== 'Unspecified' && (
                                <span className="bg-slate-950/80 px-2 py-0.5 rounded-md border border-slate-800 shrink-0">
                                  🛋️ {liveExtractedPreview.furnishing}
                                </span>
                              )}
                              {liveExtractedPreview.floor && liveExtractedPreview.floor !== 'Unspecified' && (
                                <span className="bg-slate-950/80 px-2 py-0.5 rounded-md border border-slate-800 shrink-0">
                                  🏢 Floor {liveExtractedPreview.floor}
                                </span>
                              )}
                              {liveExtractedPreview.vastuFacing && liveExtractedPreview.vastuFacing !== 'Unspecified' && (
                                <span className="bg-slate-950/80 px-2 py-0.5 rounded-md border border-slate-800 shrink-0">
                                  🧭 {liveExtractedPreview.vastuFacing} Facing
                                </span>
                              )}
                              {liveExtractedPreview.deposit && liveExtractedPreview.deposit !== 'Unspecified' && (
                                <span className="bg-slate-950/80 px-2 py-0.5 rounded-md border border-slate-800 shrink-0">
                                  🔒 Deposit: {liveExtractedPreview.deposit}
                                </span>
                              )}
                            </div>
                          </div>
                        ) : (
                          <div className="space-y-3">
                            <div className="flex items-center justify-between gap-2">
                              {/* CATEGORY SEGMENTED FILTER TABS WITH SLIDING LIQUID PILL */}
                              {!liveExtractedPreview.isGarbageInput && (
                                <div className="flex items-center gap-1.5 overflow-x-auto pb-1 no-scrollbar relative flex-1">
                                  {[
                                    { id: 'all', label: '🌐 All 18 Attributes', activeStyle: 'text-emerald-300 border-emerald-500/50 bg-emerald-950' },
                                    { id: 'location', label: '📍 Location & Type (6)', activeStyle: 'text-blue-300 border-blue-500/50 bg-blue-950' },
                                    { id: 'pricing', label: '💰 Rent & Financials (5)', activeStyle: 'text-amber-300 border-amber-500/50 bg-amber-950' },
                                    { id: 'specs', label: '🛋️ Specs & Amenities (7)', activeStyle: 'text-purple-300 border-purple-500/50 bg-purple-950' }
                                  ].map(tab => {
                                    const isTabActive = activeAttributeTab === tab.id;
                                    return (
                                      <button
                                        key={tab.id}
                                        type="button"
                                        onClick={() => setActiveAttributeTab(tab.id as any)}
                                        className={`relative px-3 py-1.5 rounded-xl text-xs font-extrabold font-mono transition-colors cursor-pointer whitespace-nowrap flex items-center gap-1.5 ${
                                          isTabActive
                                            ? tab.activeStyle + ' font-black shadow-xs border'
                                            : 'bg-slate-900/80 text-slate-400 border border-slate-800 hover:text-slate-200'
                                        }`}
                                      >
                                        {isTabActive && (
                                          <motion.div
                                            layoutId="activeCategoryTabPill"
                                            className="absolute inset-0 bg-white/5 rounded-xl pointer-events-none"
                                            transition={{ type: "spring", stiffness: 450, damping: 30 }}
                                          />
                                        )}
                                        <span className="relative z-10">{tab.label}</span>
                                      </button>
                                    );
                                  })}
                                </div>
                              )}

                              <button
                                type="button"
                                onClick={() => setIsAttributesCollapsed(true)}
                                className="min-h-[36px] px-2.5 py-1 rounded-xl text-xs font-bold text-slate-400 hover:text-white bg-slate-900/90 border border-slate-800 hover:border-slate-700 transition-all flex items-center gap-1 shrink-0 cursor-pointer"
                              >
                                <span>Collapse</span>
                                <ChevronUp className="w-4 h-4" />
                              </button>
                            </div>

                            {/* 18-CARD CATEGORIZED REAL-TIME PARAMETER INSPECTION GRID (RESPONSIVE AUTO-FIT) */}
                            <motion.div layout className="grid grid-cols-1 min-[440px]:grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6 gap-2.5 w-full">
                          {/* CATEGORY 1: LOCATION & TYPE */}
                          {(activeAttributeTab === 'all' || activeAttributeTab === 'location') && (
                            <>
                              {/* 1. BHK Layout */}
                              <motion.div
                                layout
                                initial={{ opacity: 0, scale: 0.9 }}
                                animate={{ opacity: 1, scale: 1 }}
                                whileHover={{ scale: 1.03, y: -1 }}
                                className="bg-slate-900/90 p-3 rounded-xl border border-slate-800 hover:border-emerald-500/50 transition-colors"
                              >
                                <span className="text-[9px] font-mono text-slate-400 block uppercase font-bold tracking-wider">🏠 BHK Layout</span>
                                <span className={`text-xs font-black font-['Outfit'] break-words leading-tight block mt-0.5 ${
                                  liveExtractedPreview.bhk === 'Unspecified' ? 'text-slate-500 italic' : 'text-white'
                                }`}>{liveExtractedPreview.bhk}</span>
                              </motion.div>

                              {/* 2. Property Type */}
                              <motion.div
                                layout
                                initial={{ opacity: 0, scale: 0.9 }}
                                animate={{ opacity: 1, scale: 1 }}
                                whileHover={{ scale: 1.03, y: -1 }}
                                className="bg-slate-900/90 p-3 rounded-xl border border-slate-800 hover:border-indigo-500/50 transition-colors"
                              >
                                <span className="text-[9px] font-mono text-slate-400 block uppercase font-bold tracking-wider">🏷️ Property Type</span>
                                <span className={`text-xs font-black font-['Outfit'] break-words leading-tight block mt-0.5 ${
                                  !liveExtractedPreview.type ? 'text-slate-500 italic' : 'text-indigo-300'
                                }`}>
                                  {liveExtractedPreview.type === 'FLAT' ? 'Flat / Apartment' :
                                   liveExtractedPreview.type === 'HOUSE' ? 'Independent House' :
                                   liveExtractedPreview.type === 'VILLA' ? 'Villa' :
                                   liveExtractedPreview.type === 'PLOT' ? 'Plot / Land' :
                                   liveExtractedPreview.type === 'PENTHOUSE' ? 'Penthouse' :
                                   liveExtractedPreview.type === 'STUDIO' ? 'Studio' :
                                   liveExtractedPreview.type === 'AIRBNB' ? 'Airbnb' :
                                   (liveExtractedPreview.type || 'Unspecified')}
                                </span>
                              </motion.div>

                              {/* 3. Locality / Sector */}
                              <motion.div
                                layout
                                initial={{ opacity: 0, scale: 0.9 }}
                                animate={{ opacity: 1, scale: 1 }}
                                whileHover={{ scale: 1.03, y: -1 }}
                                className="bg-slate-900/90 p-3 rounded-xl border border-slate-800 hover:border-emerald-500/50 transition-colors"
                              >
                                <span className="text-[9px] font-mono text-slate-400 block uppercase font-bold tracking-wider">📍 Locality</span>
                                <span className={`text-xs font-black font-['Outfit'] break-words leading-tight block mt-0.5 ${
                                  liveExtractedPreview.sector === 'Not Specified' ? 'text-slate-500 italic' : 'text-emerald-300'
                                }`} title={liveExtractedPreview.sector}>
                                  {liveExtractedPreview.sector}
                                </span>
                              </motion.div>

                              {/* 4. City & State */}
                              <motion.div
                                layout
                                initial={{ opacity: 0, scale: 0.9 }}
                                animate={{ opacity: 1, scale: 1 }}
                                whileHover={{ scale: 1.03, y: -1 }}
                                className="bg-slate-900/90 p-3 rounded-xl border border-slate-800 hover:border-blue-500/50 transition-colors"
                              >
                                <span className="text-[9px] font-mono text-slate-400 block uppercase font-bold tracking-wider">🏙️ City</span>
                                <span className="text-xs font-black font-['Outfit'] break-words leading-tight block mt-0.5 text-blue-300">
                                  {liveExtractedPreview.city || 'Indore'}{liveExtractedPreview.state ? `, ${liveExtractedPreview.state}` : ''}
                                </span>
                              </motion.div>

                              {/* 11. Landmark */}
                              <motion.div
                                layout
                                initial={{ opacity: 0, scale: 0.9 }}
                                animate={{ opacity: 1, scale: 1 }}
                                whileHover={{ scale: 1.03, y: -1 }}
                                className="bg-slate-900/90 p-3 rounded-xl border border-slate-800 hover:border-yellow-500/50 transition-colors"
                              >
                                <span className="text-[9px] font-mono text-slate-400 block uppercase font-bold tracking-wider">🏢 Landmark</span>
                                <span className={`text-xs font-black font-['Outfit'] break-words leading-tight block mt-0.5 ${
                                  !liveExtractedPreview.landmark ? 'text-slate-500 italic' : 'text-yellow-300'
                                }`} title={liveExtractedPreview.landmark || 'Unspecified'}>
                                  {liveExtractedPreview.landmark || 'Unspecified'}
                                </span>
                              </motion.div>

                              {/* 12. Pincode */}
                              <motion.div
                                layout
                                initial={{ opacity: 0, scale: 0.9 }}
                                animate={{ opacity: 1, scale: 1 }}
                                whileHover={{ scale: 1.03, y: -1 }}
                                className="bg-slate-900/90 p-3 rounded-xl border border-slate-800 hover:border-indigo-500/50 transition-colors"
                              >
                                <span className="text-[9px] font-mono text-slate-400 block uppercase font-bold tracking-wider">📌 Pincode</span>
                                <span className={`text-xs font-black font-['Outfit'] break-words leading-tight block mt-0.5 ${
                                  !liveExtractedPreview.pincode ? 'text-slate-500 italic' : 'text-indigo-400'
                                }`}>{liveExtractedPreview.pincode || 'Unspecified'}</span>
                              </motion.div>
                            </>
                          )}

                          {/* CATEGORY 2: RENT & FINANCIALS */}
                          {(activeAttributeTab === 'all' || activeAttributeTab === 'pricing') && (
                            <>
                              {/* 5. Monthly Rent */}
                              <motion.div
                                layout
                                initial={{ opacity: 0, scale: 0.9 }}
                                animate={{ opacity: 1, scale: 1 }}
                                whileHover={{ scale: 1.03, y: -1 }}
                                className="bg-slate-900/90 p-3 rounded-xl border border-slate-800 hover:border-amber-500/50 transition-colors"
                              >
                                <span className="text-[9px] font-mono text-slate-400 block uppercase font-bold tracking-wider">💰 Rent</span>
                                <span className={`text-xs font-black font-['Outfit'] break-words leading-tight block mt-0.5 ${
                                  liveExtractedPreview.rentVal === 'Unspecified' ? 'text-slate-500 italic' : 'text-amber-300'
                                }`}>{liveExtractedPreview.rentVal}</span>
                              </motion.div>

                              {/* 6. Brokerage Fee */}
                              <motion.div
                                layout
                                initial={{ opacity: 0, scale: 0.9 }}
                                animate={{ opacity: 1, scale: 1 }}
                                whileHover={{ scale: 1.03, y: -1 }}
                                className="bg-slate-900/90 p-3 rounded-xl border border-slate-800 hover:border-purple-500/50 transition-colors"
                              >
                                <span className="text-[9px] font-mono text-slate-400 block uppercase font-bold tracking-wider">💼 Brokerage</span>
                                <span className={`text-xs font-black font-['Outfit'] break-words leading-tight block mt-0.5 ${
                                  liveExtractedPreview.brokerageVal === 'Unmentioned' ? 'text-slate-500 italic' : 'text-purple-300'
                                }`}>{liveExtractedPreview.brokerageVal}</span>
                              </motion.div>

                              {/* 7. Security Deposit */}
                              <motion.div
                                layout
                                initial={{ opacity: 0, scale: 0.9 }}
                                animate={{ opacity: 1, scale: 1 }}
                                whileHover={{ scale: 1.03, y: -1 }}
                                className="bg-slate-900/90 p-3 rounded-xl border border-slate-800 hover:border-rose-500/50 transition-colors"
                              >
                                <span className="text-[9px] font-mono text-slate-400 block uppercase font-bold tracking-wider">🛡️ Deposit</span>
                                <span className={`text-xs font-black font-['Outfit'] break-words leading-tight block mt-0.5 ${
                                  !liveExtractedPreview.depositVal || liveExtractedPreview.depositVal === 'Unspecified' ? 'text-slate-500 italic' : 'text-rose-300'
                                }`}>{liveExtractedPreview.depositVal || 'Unspecified'}</span>
                              </motion.div>

                              {/* 10. Possession Date */}
                              <motion.div
                                layout
                                initial={{ opacity: 0, scale: 0.9 }}
                                animate={{ opacity: 1, scale: 1 }}
                                whileHover={{ scale: 1.03, y: -1 }}
                                className="bg-slate-900/90 p-3 rounded-xl border border-slate-800 hover:border-emerald-500/50 transition-colors"
                              >
                                <span className="text-[9px] font-mono text-slate-400 block uppercase font-bold tracking-wider">📅 Possession</span>
                                <span className={`text-xs font-black font-['Outfit'] break-words leading-tight block mt-0.5 ${
                                  !liveExtractedPreview.possessionDate ? 'text-slate-500 italic' : 'text-emerald-400'
                                }`}>{liveExtractedPreview.possessionDate || 'Unspecified'}</span>
                              </motion.div>

                              {/* 17. Listing Status */}
                              <motion.div
                                layout
                                initial={{ opacity: 0, scale: 0.9 }}
                                animate={{ opacity: 1, scale: 1 }}
                                whileHover={{ scale: 1.03, y: -1 }}
                                className="bg-slate-900/90 p-3 rounded-xl border border-slate-800 hover:border-lime-500/50 transition-colors"
                              >
                                <span className="text-[9px] font-mono text-slate-400 block uppercase font-bold tracking-wider">⚡ Status</span>
                                <span className={`text-xs font-black font-['Outfit'] break-words leading-tight block mt-0.5 ${
                                  !liveExtractedPreview.status ? 'text-slate-500 italic' : 'text-lime-300'
                                }`}>
                                  {liveExtractedPreview.status || 'LIVE'}
                                </span>
                              </motion.div>
                            </>
                          )}

                          {/* CATEGORY 3: SPECS & AMENITIES */}
                          {(activeAttributeTab === 'all' || activeAttributeTab === 'specs') && (
                            <>
                              {/* 8. Carpet Area */}
                              <motion.div
                                layout
                                initial={{ opacity: 0, scale: 0.9 }}
                                animate={{ opacity: 1, scale: 1 }}
                                whileHover={{ scale: 1.03, y: -1 }}
                                className="bg-slate-900/90 p-3 rounded-xl border border-slate-800 hover:border-orange-500/50 transition-colors"
                              >
                                <span className="text-[9px] font-mono text-slate-400 block uppercase font-bold tracking-wider">📐 Carpet Area</span>
                                <span className={`text-xs font-black font-['Outfit'] break-words leading-tight block mt-0.5 ${
                                  !liveExtractedPreview.areaSqFt || liveExtractedPreview.areaSqFt === 'Unspecified' ? 'text-slate-500 italic' : 'text-orange-300'
                                }`}>{liveExtractedPreview.areaSqFt || 'Unspecified'}</span>
                              </motion.div>

                              {/* 9. Bathrooms */}
                              <motion.div
                                layout
                                initial={{ opacity: 0, scale: 0.9 }}
                                animate={{ opacity: 1, scale: 1 }}
                                whileHover={{ scale: 1.03, y: -1 }}
                                className="bg-slate-900/90 p-3 rounded-xl border border-slate-800 hover:border-cyan-500/50 transition-colors"
                              >
                                <span className="text-[9px] font-mono text-slate-400 block uppercase font-bold tracking-wider">🛁 Bathrooms</span>
                                <span className={`text-xs font-black font-['Outfit'] break-words leading-tight block mt-0.5 ${
                                  !liveExtractedPreview.bathrooms ? 'text-slate-500 italic' : 'text-cyan-300'
                                }`}>{liveExtractedPreview.bathrooms || 'Unspecified'}</span>
                              </motion.div>

                              {/* 13. Owner Name */}
                              <motion.div
                                layout
                                initial={{ opacity: 0, scale: 0.9 }}
                                animate={{ opacity: 1, scale: 1 }}
                                whileHover={{ scale: 1.03, y: -1 }}
                                className="bg-slate-900/90 p-3 rounded-xl border border-slate-800 hover:border-cyan-500/50 transition-colors"
                              >
                                <span className="text-[9px] font-mono text-slate-400 block uppercase font-bold tracking-wider">👤 Owner Name</span>
                                <span className={`text-xs font-black font-['Outfit'] break-words leading-tight block mt-0.5 ${
                                  liveExtractedPreview.ownerName === 'Not Specified' ? 'text-slate-500 italic' : 'text-cyan-300'
                                }`} title={liveExtractedPreview.ownerName}>
                                  {liveExtractedPreview.ownerName}
                                </span>
                              </motion.div>

                              {/* 14. Owner Phone */}
                              <motion.div
                                layout
                                initial={{ opacity: 0, scale: 0.9 }}
                                animate={{ opacity: 1, scale: 1 }}
                                whileHover={{ scale: 1.03, y: -1 }}
                                className="bg-slate-900/90 p-3 rounded-xl border border-slate-800 hover:border-sky-500/50 transition-colors"
                              >
                                <span className="text-[9px] font-mono text-slate-400 block uppercase font-bold tracking-wider">📞 Owner Contact</span>
                                <span className={`text-xs font-black font-['Outfit'] break-words leading-tight block mt-0.5 ${
                                  liveExtractedPreview.ownerPhone === 'Not Specified' ? 'text-slate-500 italic' : 'text-sky-300'
                                }`} title={liveExtractedPreview.ownerPhone}>
                                  {liveExtractedPreview.ownerPhone}
                                </span>
                              </motion.div>

                              {/* 15. Vastu Facing */}
                              <motion.div
                                layout
                                initial={{ opacity: 0, scale: 0.9 }}
                                animate={{ opacity: 1, scale: 1 }}
                                whileHover={{ scale: 1.03, y: -1 }}
                                className="bg-slate-900/90 p-3 rounded-xl border border-slate-800 hover:border-teal-500/50 transition-colors"
                              >
                                <span className="text-[9px] font-mono text-slate-400 block uppercase font-bold tracking-wider">🧭 Vastu Facing</span>
                                <span className={`text-xs font-black font-['Outfit'] break-words leading-tight block mt-0.5 ${
                                  liveExtractedPreview.vastuFacing === 'Not Specified' ? 'text-slate-500 italic' : 'text-teal-300'
                                }`}>{liveExtractedPreview.vastuFacing}</span>
                              </motion.div>

                              {/* 16. Furnishing Status */}
                              <motion.div
                                layout
                                initial={{ opacity: 0, scale: 0.9 }}
                                animate={{ opacity: 1, scale: 1 }}
                                whileHover={{ scale: 1.03, y: -1 }}
                                className="bg-slate-900/90 p-3 rounded-xl border border-slate-800 hover:border-fuchsia-500/50 transition-colors"
                              >
                                <span className="text-[9px] font-mono text-slate-400 block uppercase font-bold tracking-wider">🛋️ Furnishing</span>
                                <span className={`text-xs font-black font-['Outfit'] break-words leading-tight block mt-0.5 ${
                                  liveExtractedPreview.furnishingStatus === 'UNSPECIFIED' ? 'text-slate-500 italic' : 'text-fuchsia-300'
                                }`}>
                                  {liveExtractedPreview.furnishingStatus === 'FULLY_FURNISHED' || liveExtractedPreview.furnishingStatus === 'Fully Furnished' ? 'Furnished' :
                                   liveExtractedPreview.furnishingStatus === 'SEMI_FURNISHED' || liveExtractedPreview.furnishingStatus === 'Semi Furnished' ? 'Semi-Furnished' :
                                   liveExtractedPreview.furnishingStatus === 'UNFURNISHED' || liveExtractedPreview.furnishingStatus === 'Unfurnished' ? 'Unfurnished' :
                                   'Unspecified'}
                                </span>
                              </motion.div>

                              {/* 18. Amenities */}
                              <motion.div
                                layout
                                initial={{ opacity: 0, scale: 0.9 }}
                                animate={{ opacity: 1, scale: 1 }}
                                whileHover={{ scale: 1.03, y: -1 }}
                                className="bg-slate-900/90 p-3 rounded-xl border border-slate-800 hover:border-violet-500/50 transition-colors"
                              >
                                <span className="text-[9px] font-mono text-slate-400 block uppercase font-bold tracking-wider">✨ Key Amenities</span>
                                <span className={`text-xs font-black font-['Outfit'] break-words leading-tight block mt-0.5 ${
                                  !liveExtractedPreview.amenities || liveExtractedPreview.amenities.length === 0 ? 'text-slate-500 italic' : 'text-violet-300'
                                }`} title={liveExtractedPreview.amenities?.join(', ') || 'Standard'}>
                                  {liveExtractedPreview.amenities && liveExtractedPreview.amenities.length > 0 ? liveExtractedPreview.amenities.join(', ') : 'Standard'}
                                </span>
                              </motion.div>
                            </>
                          )}
                        </motion.div>

                        {/* Bottom Collapse Button */}
                        <div className="flex justify-center pt-2">
                          <button
                            type="button"
                            onClick={() => setIsAttributesCollapsed(true)}
                            className="min-h-[40px] px-4 py-1.5 rounded-xl text-xs font-bold text-slate-400 hover:text-white bg-slate-900 hover:bg-slate-850 border border-slate-800 transition-all flex items-center gap-1.5 cursor-pointer shadow-xs"
                          >
                            <span>Collapse 18 attributes</span>
                            <ChevronUp className="w-4 h-4" />
                          </button>
                        </div>
                      </div>
                    )}

                        {/* Optional Missing Fields Indicator */}
                        {liveExtractedPreview.missingFields && liveExtractedPreview.missingFields.length > 0 && !liveExtractedPreview.isGarbageInput && (
                          <div className="text-[10px] font-mono text-slate-500 pt-2 flex items-center gap-1.5 border-t border-slate-800/60">
                            <span className="text-slate-400 font-bold">💡 Unmentioned Attributes:</span>
                            <span className="italic truncate">{liveExtractedPreview.missingFields.join(' • ')}</span>
                          </div>
                        )}
                        {liveExtractedPreview.conflicts && liveExtractedPreview.conflicts.length > 0 && (
                          <div className="text-[10px] font-mono text-amber-300 pt-2 flex items-center gap-1.5 border-t border-amber-800/60">
                            <span className="font-bold">Conflicting values:</span>
                            <span className="italic truncate">{liveExtractedPreview.conflicts.join(' • ')}</span>
                          </div>
                        )}
                        {dbSaveSuccessMsg && (
                          <div className="text-[10px] font-mono text-emerald-300 pt-2 border-t border-emerald-800/60">
                            {dbSaveSuccessMsg}
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                </form>
                ) : (
                  <BatchPropertyIngestionStudio
                    isOpen
                    embedded
                    initialDetails={batchDetails}
                    initialDraftId={selectedBatchDraftId}
                    initialMediaFiles={attachedMediaFiles}
                    onClose={() => {
                      setSelectedBatchDraftId(null);
                      setUploadMode('single');
                    }}
                    onSwitchToSingleDraft={async (singleId) => {
                      setSelectedBatchDraftId(null);
                      setUploadMode('single');
                      await singleDraft.loadDraft(singleId);
                    }}
                    onSuccess={(count) => {
                      setSelectedBatchDraftId(null);
                      notifySuccess(
                        `${count} ${count === 1 ? 'property' : 'properties'} uploaded`,
                        `${count} ${count === 1 ? 'property was' : 'properties were'} uploaded successfully.`,
                        undefined,
                        'PROPERTY'
                      );
                      // Clear parent upload state so next upload starts completely clean
                      setBatchDetails('');
                      setNewBhkLabel('');
                      if (singleMicBaseTextRef.current) {
                        singleMicBaseTextRef.current = '';
                      }
                      revokeAllMediaPreviewUrls();
                      setAttachedMediaFiles([]);
                      setAttachedMediaTags({});
                      setCoverPhotoIndex(0);
                      setUploadPipeline(prev => ({ ...prev, active: false, stage: 'idle' }));
                      setUploadMode('single');
                    }}
                  />
                )}

              </motion.div>

              <button
                type="button"
                onClick={() => setShowMobileListingTools((current) => !current)}
                aria-expanded={showMobileListingTools}
                className="flex min-h-12 w-full items-center justify-between gap-3 rounded-2xl border border-slate-200 bg-white px-4 py-3 text-left text-sm font-bold text-slate-800 shadow-sm transition-colors hover:border-emerald-300 lg:hidden"
              >
                <span>
                  <span className="block">Demand insights and listing options</span>
                  <span className="mt-0.5 block text-[11px] font-medium text-slate-500">
                    Open only when you need to manage search options.
                  </span>
                </span>
                <ChevronRight className={`h-5 w-5 shrink-0 text-emerald-600 transition-transform ${showMobileListingTools ? 'rotate-90' : ''}`} />
              </button>

              <div className={`${showMobileListingTools ? 'contents' : 'hidden'} lg:contents`}>
              {/* BHK DEMAND VISUAL SCORE GAUGES */}
              <motion.div variants={cardVariants}>
                <BhkDemandGaugeGrid />
              </motion.div>

              {/* BHK CONFIGURATION MANAGER HEADER */}
              <motion.div variants={cardVariants} className="bg-white rounded-3xl p-6 sm:p-7 border border-slate-200/90 shadow-sm">
                <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4 mb-6 border-b border-slate-100">
                  <div>
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] font-black text-emerald-800 bg-emerald-50 px-2.5 py-1 rounded-full border border-emerald-200 uppercase font-mono">
                        Listing options
                      </span>
                      <span className="text-xs text-slate-500 font-mono">Tenant search</span>
                    </div>
                    <h3 className="text-xl font-black text-slate-900 font-['Outfit'] mt-1">
                      Property layout options
                    </h3>
                  </div>
                  <div className="bg-slate-50 border border-slate-200 px-4 py-2 rounded-2xl text-emerald-800 font-mono text-xs font-bold">
                    {bhkConfigs.filter((c: any) => c.enabled).length} / {bhkConfigs.length} Active Options
                  </div>
                </div>

                {/* BHK CONFIGURATION GRID TABLE */}
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                  {bhkConfigs.map((config: any) => (
                    <div
                      key={config.id}
                      className={`p-4 rounded-2xl border transition-all ${config.enabled
                          ? 'bg-slate-900 text-white border-slate-800 shadow-md'
                          : 'bg-slate-100 text-slate-500 border-slate-200'
                        }`}
                    >
                      <div className="flex items-center justify-between mb-2">
                        <span className="text-[10px] font-mono font-bold uppercase text-slate-400">
                          ID: {config.id}
                        </span>

                        <button
                          type="button"
                          onClick={() => handleToggleBhk(config.id)}
                          className={`flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-extrabold transition-all ${config.enabled
                              ? 'bg-emerald-500 text-white shadow-sm'
                              : 'bg-slate-300 text-slate-700'
                            }`}
                        >
                          {config.enabled ? <ToggleRight className="w-4 h-4" /> : <ToggleLeft className="w-4 h-4" />}
                          {config.enabled ? 'Enabled' : 'Disabled'}
                        </button>
                      </div>

                      <h4 className="text-base font-extrabold font-['Outfit'] mb-1">
                        {config.label}
                      </h4>

                      <div className="flex items-center justify-between text-xs pt-2 mt-2 border-t border-slate-800/40 font-mono">
                        <span>Demand: <strong className={config.enabled ? 'text-emerald-300' : 'text-slate-600'}>{config.demandScore}</strong></span>
                        <span>Avg Rent: <strong className={config.enabled ? 'text-amber-300' : 'text-slate-600'}>{config.avgRent}</strong></span>
                      </div>
                    </div>
                  ))}
                </div>
              </motion.div>
              </div>
            </motion.div>
          )}

        </AnimatePresence>

        {/* FULLSCREEN ROOT-LEVEL MEDIA UPLOAD POPUP WITH TOTAL SCREEN BLUR */}
        <AnimatePresence>
          {isMediaUploadModalOpen && (
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.25 }}
              className="fixed inset-0 z-[999] bg-slate-950 sm:bg-slate-950/95 backdrop-blur-2xl flex flex-col sm:items-center sm:justify-center p-0 sm:p-4 overflow-hidden"
              onClick={() => setIsMediaUploadModalOpen(false)}
            >
              <motion.div
                initial={{ scale: 0.95, y: 20, opacity: 0 }}
                animate={{ scale: 1, y: 0, opacity: 1 }}
                exit={{ scale: 0.95, y: 20, opacity: 0 }}
                transition={{ type: "spring", stiffness: 450, damping: 28 }}
                onClick={(e) => e.stopPropagation()}
                className="bg-slate-900 border-0 sm:border-2 sm:border-cyan-500/40 text-white rounded-none sm:rounded-3xl max-w-2xl w-full h-[100dvh] sm:h-auto sm:max-h-[90dvh] flex flex-col shadow-2xl shadow-cyan-500/20 relative overflow-hidden"
              >
                {/* AMBIENT AURORA GLOW ORB */}
                <div className="absolute -top-24 -right-24 w-72 h-72 bg-cyan-500/15 rounded-full blur-3xl pointer-events-none animate-pulse" />
                <div className="absolute -bottom-24 -left-24 w-72 h-72 bg-emerald-500/15 rounded-full blur-3xl pointer-events-none animate-pulse" style={{ animationDelay: '1s' }} />

                {/* MODAL HEADER - PINNED TOP WITH SOLID OPACITY AND SAFE AREA */}
                <div className="shrink-0 flex items-center justify-between p-4 sm:p-6 pb-3 sm:pb-4 border-b border-slate-800 bg-slate-900 relative z-20 pt-[calc(env(safe-area-inset-top,0px)+0.75rem)] sm:pt-6">
                  <div className="flex items-center gap-3 min-w-0">
                    <div className="w-10 h-10 rounded-2xl bg-cyan-950 text-cyan-400 border border-cyan-700/60 flex items-center justify-center font-bold shadow-lg shadow-cyan-950/60 shrink-0">
                      <Camera className="w-5 h-5 animate-pulse" />
                    </div>
                    <div className="min-w-0">
                      <h3 className="text-lg sm:text-xl font-black text-white font-['Outfit'] flex items-center gap-2 truncate">
                        Property media
                      </h3>
                      <p className="text-[11px] text-slate-400 font-mono truncate">
                        Add photos and walkthrough videos to this listing.
                      </p>
                    </div>
                  </div>

                  <button
                    type="button"
                    onClick={() => setIsMediaUploadModalOpen(false)}
                    aria-label="Close dialog"
                    className="min-h-[44px] min-w-[44px] rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-400 hover:text-white flex items-center justify-center transition-colors cursor-pointer border border-slate-700 shrink-0"
                  >
                    <X className="w-5 h-5" />
                  </button>
                </div>

                {/* MODAL BODY - INDEPENDENTLY SCROLLABLE */}
                <div className="flex-1 min-h-0 overflow-y-auto p-4 sm:p-6 space-y-4 relative z-10 overscroll-contain">
                  {/* DRAG & DROP MEDIA ZONE */}
                  <div
                    onDragOver={(e) => { e.preventDefault(); setIsDragOverMedia(true); }}
                    onDragLeave={() => setIsDragOverMedia(false)}
                    onDrop={handleMediaDrop}
                    className={`p-5 sm:p-7 rounded-2xl border-2 border-dashed transition-all flex flex-col items-center justify-center text-center gap-3 sm:gap-4 relative z-10 ${
                      isDragOverMedia
                        ? 'bg-cyan-950/80 border-cyan-400 text-cyan-200 scale-[1.01] shadow-lg shadow-cyan-950/80'
                        : 'bg-slate-950/80 border-slate-800 hover:border-cyan-500/50 text-slate-400'
                    }`}
                  >
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 sm:w-11 sm:h-11 rounded-2xl bg-slate-900 border border-slate-800 flex items-center justify-center text-cyan-400 shadow-md">
                        <Camera className="w-5 h-5 sm:w-6 sm:h-6" />
                      </div>
                      <div className="w-10 h-10 sm:w-11 sm:h-11 rounded-2xl bg-slate-900 border border-slate-800 flex items-center justify-center text-indigo-400 shadow-md">
                        <Video className="w-5 h-5 sm:w-6 sm:h-6" />
                      </div>
                    </div>

                    <div>
                      <p className="text-xs sm:text-sm font-bold text-slate-200 font-['Outfit']">
                        Drag & drop photos or walkthrough videos here
                      </p>
                      <p className="text-[10px] text-slate-500 mt-1 font-mono">
                        {describeMediaLimits()}
                      </p>
                    </div>

                    <label className="cursor-pointer min-h-[44px] px-5 py-2.5 bg-slate-800 hover:bg-slate-700 text-cyan-300 text-xs font-extrabold font-mono rounded-xl border border-cyan-500/40 transition-all shadow-md flex items-center justify-center gap-2">
                      <Plus className="w-4 h-4 text-cyan-400" />
                      <span>Select / Browse Files</span>
                      <input
                        type="file"
                        multiple
                        accept="image/*,video/*"
                        onChange={handleMediaSelect}
                        className="hidden"
                      />
                    </label>
                  </div>

                  {/* ATTACHED FILE PREVIEW LIST & VISUAL GALLERY */}
                  {attachedMediaFiles.length > 0 && (
                    <div className="space-y-3 relative z-10">
                      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 text-[11px] font-mono font-bold text-slate-300 border-b border-slate-800 pb-2">
                        <div className="flex items-center gap-2">
                          <span>Attached media ({attachedMediaFiles.length})</span>
                          <span className="text-[10px] text-emerald-400 font-normal">
                            Ready to upload
                          </span>
                        </div>

                        <div className="flex items-center justify-between sm:justify-end gap-2">
                          <div className="flex items-center bg-slate-950 p-1 rounded-lg border border-slate-800">
                            <button
                              type="button"
                              onClick={() => setMediaViewMode('grid')}
                              className={`min-h-[28px] px-2.5 py-0.5 rounded text-[10px] font-bold transition-all ${
                                mediaViewMode === 'grid'
                                  ? 'bg-cyan-500 text-slate-950 shadow-sm'
                                  : 'text-slate-400 hover:text-slate-200'
                              }`}
                            >
                              📷 Gallery
                            </button>
                            <button
                              type="button"
                              onClick={() => setMediaViewMode('list')}
                              className={`min-h-[28px] px-2.5 py-0.5 rounded text-[10px] font-bold transition-all ${
                                mediaViewMode === 'list'
                                  ? 'bg-cyan-500 text-slate-950 shadow-sm'
                                  : 'text-slate-400 hover:text-slate-200'
                              }`}
                            >
                              📋 List
                            </button>
                          </div>
                          <button
                            type="button"
                            onClick={handleFillMediaFromExtracted}
                            className="min-h-[28px] text-cyan-400 hover:text-cyan-300 text-[10px] flex items-center gap-1 cursor-pointer font-bold bg-slate-900 border border-cyan-500/30 px-2.5 py-1 rounded-lg"
                          >
                            <Tag className="w-3 h-3" /> Auto-tag
                          </button>
                        </div>
                      </div>

                      {mediaViewMode === 'grid' ? (
                        <div className="grid grid-cols-1 min-[420px]:grid-cols-2 sm:grid-cols-3 gap-3 font-mono">
                          {attachedMediaFiles.map((file, idx) => {
                            const isImage = file.type.startsWith('image/');
                            const imgUrl = isImage ? getMediaPreviewUrl(file) : null;
                            const currentTag = attachedMediaTags[idx] || DEFAULT_SMART_TAG_SEQUENCE[idx % DEFAULT_SMART_TAG_SEQUENCE.length];

                            return (
                              <div
                                key={idx}
                                className="group relative bg-slate-950 rounded-xl border border-slate-800 hover:border-cyan-500/60 overflow-hidden flex flex-col justify-between transition-all shadow-md"
                              >
                                {/* Media Thumbnail Card */}
                                <div
                                  onClick={() => isImage && setPreviewLightboxIndex(idx)}
                                  className="relative h-32 w-full bg-slate-900 cursor-pointer overflow-hidden flex items-center justify-center"
                                >
                                  {/* Non-overlapping top action buttons */}
                                  <div className="absolute inset-x-0 top-0 p-1.5 flex items-center justify-between gap-1 z-10 bg-gradient-to-b from-slate-950/80 via-slate-950/40 to-transparent">
                                    <button
                                      type="button"
                                      onClick={(e) => {
                                        e.stopPropagation();
                                        setCoverPhotoIndex(idx);
                                        notifySuccess('⭐ Cover Photo Selected', `Photo #${idx + 1} (${file.name}) set as primary listing cover`);
                                      }}
                                      className={`px-2 py-1 rounded-lg text-[9px] font-extrabold flex items-center gap-1 shadow-md transition-all cursor-pointer ${
                                        coverPhotoIndex === idx
                                          ? 'bg-amber-400 text-slate-950 ring-2 ring-amber-300 shadow-amber-500/40 scale-105'
                                          : 'bg-slate-950/90 hover:bg-slate-900 text-slate-300 hover:text-amber-300 border border-white/20'
                                      }`}
                                    >
                                      <Star className={`w-3 h-3 ${coverPhotoIndex === idx ? 'fill-current text-slate-950' : 'text-slate-400'}`} />
                                      <span>{coverPhotoIndex === idx ? 'Cover Photo' : 'Set Cover'}</span>
                                    </button>

                                    <button
                                      type="button"
                                      aria-label={`Remove file ${file.name}`}
                                      onClick={(e) => {
                                        e.stopPropagation();
                                        handleRemoveAttachedMedia(idx);
                                      }}
                                      className="min-h-[30px] min-w-[30px] rounded-full bg-slate-950/90 hover:bg-rose-600 text-white flex items-center justify-center text-xs font-bold border border-white/20 transition-colors shadow-lg cursor-pointer"
                                    >
                                      ✕
                                    </button>
                                  </div>

                                  {isImage && imgUrl ? (
                                    <>
                                      <img
                                        src={imgUrl}
                                        alt={file.name}
                                        className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                                      />
                                      <div className="absolute inset-0 bg-slate-950/40 opacity-0 group-hover:opacity-100 flex items-center justify-center transition-opacity">
                                        <span className="bg-slate-900/90 text-cyan-300 text-[10px] font-bold px-2 py-1 rounded-lg border border-cyan-500/40 shadow-lg flex items-center gap-1">
                                          <Sparkles className="w-3 h-3 text-cyan-400" /> Inspect
                                        </span>
                                      </div>
                                    </>
                                  ) : (
                                    <div className="flex flex-col items-center justify-center gap-1 p-2 text-center">
                                      <Video className="w-8 h-8 text-indigo-400 animate-pulse" />
                                      <span className="text-[10px] font-bold text-slate-300 truncate max-w-full">
                                        {file.name}
                                      </span>
                                    </div>
                                  )}
                                </div>

                                {/* File Details & Tag Selector */}
                                <div className="p-2.5 bg-slate-900/90 border-t border-slate-800 flex flex-col gap-1.5">
                                  <div className="flex items-center justify-between gap-1 min-w-0">
                                    <span className="text-[10px] text-slate-300 truncate font-mono font-medium flex-1" title={file.name}>
                                      {file.name}
                                    </span>
                                    <span className="text-[9px] text-slate-400 font-mono shrink-0">
                                      {(file.size / 1024 / 1024).toFixed(1)}MB
                                    </span>
                                  </div>

                                  <select
                                    value={currentTag}
                                    onChange={(e) => setAttachedMediaTags({ ...attachedMediaTags, [idx]: e.target.value as RoomTag })}
                                    className="bg-slate-950 text-cyan-300 font-mono text-xs font-bold border border-slate-700 rounded-lg px-2 py-1.5 outline-none focus:border-cyan-500 cursor-pointer w-full"
                                  >
                                    <option value="GENERAL">🌐 None / General</option>
                                    <option value="LIVING_ROOM">🛋️ Living Room</option>
                                    <option value="MASTER_BEDROOM">🛏️ Master Bedroom</option>
                                    <option value="BEDROOM">🛏️ Guest Bedroom</option>
                                    <option value="KITCHEN">🍳 Kitchen</option>
                                    <option value="BATHROOM">🚿 Bathroom</option>
                                    <option value="BALCONY">🌅 Balcony & View</option>
                                    <option value="ELEVATION">🏢 Elevation & Exterior</option>
                                    <option value="AMENITIES">🏊 Amenities</option>
                                    <option value="FLOOR_PLAN">📐 Floor Plan</option>
                                  </select>
                                </div>
                              </div>
                            );
                          })}
                        </div>
                      ) : (
                        <div className="space-y-2.5">
                          {attachedMediaFiles.map((file, idx) => {
                            const isImage = file.type.startsWith('image/');
                            const imgUrl = isImage ? getMediaPreviewUrl(file) : null;
                            const currentTag = attachedMediaTags[idx] || DEFAULT_SMART_TAG_SEQUENCE[idx % DEFAULT_SMART_TAG_SEQUENCE.length];

                            return (
                              <div
                                key={idx}
                                className="bg-slate-950 p-3 rounded-2xl border border-slate-800 flex items-start gap-3.5 hover:border-slate-700 transition-colors relative group"
                              >
                                {/* Photo Thumbnail - Prominent & Crisp */}
                                {isImage && imgUrl ? (
                                  <div
                                    onClick={() => setPreviewLightboxIndex(idx)}
                                    className="relative w-16 h-16 sm:w-20 sm:h-20 rounded-xl overflow-hidden border border-slate-700 bg-slate-900 cursor-pointer shrink-0 group/thumb shadow-sm"
                                  >
                                    <img
                                      src={imgUrl}
                                      alt={file.name}
                                      className="w-full h-full object-cover group-hover/thumb:scale-105 transition-transform"
                                    />
                                    <div className="absolute inset-0 bg-slate-950/40 opacity-0 group-hover/thumb:opacity-100 flex items-center justify-center transition-opacity">
                                      <Sparkles className="w-4 h-4 text-cyan-300" />
                                    </div>
                                    {coverPhotoIndex === idx && (
                                      <div className="absolute top-1 left-1 bg-amber-400 text-slate-950 rounded-md p-0.5 shadow-sm">
                                        <Star className="w-3 h-3 fill-current text-slate-950" />
                                      </div>
                                    )}
                                  </div>
                                ) : (
                                  <div className="w-16 h-16 sm:w-20 sm:h-20 rounded-xl bg-indigo-950/60 border border-indigo-500/40 flex items-center justify-center shrink-0 shadow-sm">
                                    <Video className="w-6 h-6 text-indigo-400" />
                                  </div>
                                )}

                                {/* File Details & Compact Controls */}
                                <div className="flex-1 min-w-0 pr-7 flex flex-col justify-between self-stretch py-0.5">
                                  <div>
                                    <p className="font-bold text-slate-200 truncate text-xs sm:text-sm font-['Outfit']" title={file.name}>
                                      {file.name}
                                    </p>
                                    <p className="text-[10px] font-mono text-slate-500 mt-0.5">
                                      {(file.size / 1024 / 1024).toFixed(1)} MB
                                    </p>
                                  </div>

                                  {/* Compact inline chip controls */}
                                  <div className="flex items-center gap-2 flex-wrap mt-2">
                                    <button
                                      type="button"
                                      onClick={() => {
                                        setCoverPhotoIndex(idx);
                                        notifySuccess('⭐ Cover Photo Selected', `Photo #${idx + 1} (${file.name}) set as primary listing cover`);
                                      }}
                                      className={`min-h-[28px] px-2 py-0.5 rounded-lg text-[10px] font-mono font-bold flex items-center gap-1 transition-all cursor-pointer ${
                                        coverPhotoIndex === idx
                                          ? 'bg-amber-400 text-slate-950 ring-1 ring-amber-300 font-extrabold shadow-xs'
                                          : 'bg-slate-900 text-slate-400 hover:text-amber-300 border border-slate-800 hover:border-slate-700'
                                      }`}
                                    >
                                      <Star className={`w-3 h-3 ${coverPhotoIndex === idx ? 'fill-current text-slate-950' : 'text-slate-400'}`} />
                                      <span>{coverPhotoIndex === idx ? 'Cover photo' : 'Set cover'}</span>
                                    </button>

                                    <div className="relative inline-flex items-center">
                                      <select
                                        value={currentTag}
                                        onChange={(e) => setAttachedMediaTags({ ...attachedMediaTags, [idx]: e.target.value as RoomTag })}
                                        className="min-h-[28px] bg-slate-900 text-cyan-300 font-mono text-[10px] sm:text-[11px] font-bold border border-slate-800 rounded-lg pl-2 pr-5 outline-none focus:border-cyan-500 cursor-pointer appearance-none"
                                      >
                                        <option value="GENERAL">🌐 General</option>
                                        <option value="LIVING_ROOM">🛋️ Living</option>
                                        <option value="MASTER_BEDROOM">🛏️ Master</option>
                                        <option value="BEDROOM">🛏️ Bed</option>
                                        <option value="KITCHEN">🍳 Kitchen</option>
                                        <option value="BATHROOM">🚿 Bath</option>
                                        <option value="BALCONY">🌅 Balcony</option>
                                        <option value="ELEVATION">🏢 Exterior</option>
                                        <option value="AMENITIES">🏊 Amenities</option>
                                        <option value="FLOOR_PLAN">📐 Plan</option>
                                      </select>
                                      <ChevronDown className="w-3 h-3 text-slate-500 absolute right-1 pointer-events-none" />
                                    </div>
                                  </div>
                                </div>

                                {/* Delete / Remove Button - top-right corner */}
                                <button
                                  type="button"
                                  onClick={() => handleRemoveAttachedMedia(idx)}
                                  aria-label={`Remove file ${file.name}`}
                                  className="absolute top-2.5 right-2.5 w-8 h-8 rounded-lg text-slate-500 hover:text-rose-400 hover:bg-rose-950/40 flex items-center justify-center transition-colors cursor-pointer"
                                >
                                  <X className="w-4 h-4" />
                                </button>
                              </div>
                            );
                          })}
                        </div>
                      )}
                    </div>
                  )}

                  {uploadStatusMsg && (
                    <div
                      aria-live="polite"
                      className="rounded-xl border border-cyan-500/30 bg-cyan-950/35 px-3 py-2 text-[11px] font-semibold text-cyan-200 break-words"
                    >
                      {uploadStatusMsg}
                    </div>
                  )}
                </div>

                {/* MODAL FOOTER - PINNED BOTTOM WITH SAFE AREA */}
                <div className="shrink-0 p-4 sm:p-6 pt-3 sm:pt-4 border-t border-slate-800 bg-slate-900/95 backdrop-blur-md flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-3 relative z-20 safe-area-bottom">
                  <span className="text-[11px] font-mono text-slate-400 text-center sm:text-left">
                    {attachedMediaFiles.length} file(s) attached • Images are optimized before upload
                  </span>

                  <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-2">
                    {failedMediaUploads.length > 0 && lastExtractedResult?.databaseId && (
                      <button
                        type="button"
                        disabled={isUploadingMedia}
                        onClick={() => void handleRetryFailedMedia()}
                        className="min-h-[44px] px-4 py-2.5 bg-rose-700 hover:bg-rose-600 disabled:opacity-50 text-white font-bold text-xs rounded-xl shadow cursor-pointer transition-all flex items-center justify-center gap-1.5"
                      >
                        <RefreshCw className={`w-4 h-4 ${isUploadingMedia ? 'animate-spin' : ''}`} />
                        <span>Retry failed media ({failedMediaUploads.length})</span>
                      </button>
                    )}

                    <button
                      type="button"
                      onClick={() => setIsMediaUploadModalOpen(false)}
                      className="min-h-[44px] px-6 py-2.5 bg-gradient-to-r from-emerald-600 to-teal-600 hover:from-emerald-500 hover:to-teal-500 text-white font-black text-xs rounded-xl shadow-lg shadow-emerald-600/30 cursor-pointer transition-all flex items-center justify-center gap-2 w-full sm:w-auto active:scale-95"
                    >
                      <Check className="w-4 h-4" />
                      <span>Done / Save Attachments</span>
                    </button>
                  </div>
                </div>
              </motion.div>
            </motion.div>
          )}
        </AnimatePresence>

        {/* FULL-SCREEN INTERACTIVE LIGHTBOX INSPECTOR MODAL */}
        <AnimatePresence>
          {previewLightboxIndex !== null && attachedMediaFiles[previewLightboxIndex] && (
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              className="fixed inset-0 z-[1000] bg-slate-950/98 backdrop-blur-3xl flex flex-col justify-between p-4 sm:p-6"
            >
              {/* TOP HEADER BAR */}
              <div className="flex items-center justify-between border-b border-slate-800 pb-3">
                <div className="flex items-center gap-3">
                  <span className="px-3 py-1 bg-cyan-950 text-cyan-400 border border-cyan-500/40 rounded-xl text-xs font-mono font-bold">
                    Photo {previewLightboxIndex + 1} of {attachedMediaFiles.length}
                  </span>
                  <span className="text-white font-bold text-sm truncate max-w-md font-['Outfit']">
                    {attachedMediaFiles[previewLightboxIndex].name}
                  </span>
                  <span className="text-slate-400 text-xs font-mono">
                    ({(attachedMediaFiles[previewLightboxIndex].size / 1024 / 1024).toFixed(2)} MB)
                  </span>
                </div>

                <div className="flex items-center gap-3">
                  <button
                    type="button"
                    onClick={() => {
                      if (previewLightboxIndex !== null) {
                        setCoverPhotoIndex(previewLightboxIndex);
                        notifySuccess('⭐ Cover Photo Selected', `Photo #${previewLightboxIndex + 1} (${attachedMediaFiles[previewLightboxIndex].name}) set as primary listing cover`);
                      }
                    }}
                    className={`px-3 py-1.5 rounded-xl text-xs font-mono font-bold flex items-center gap-1.5 transition-all cursor-pointer ${
                      coverPhotoIndex === previewLightboxIndex
                        ? 'bg-amber-400 text-slate-950 ring-2 ring-amber-300 font-extrabold shadow-md'
                        : 'bg-slate-900 text-slate-300 hover:text-amber-300 border border-slate-700'
                    }`}
                  >
                    <Star className={`w-3.5 h-3.5 ${coverPhotoIndex === previewLightboxIndex ? 'fill-current text-slate-950' : 'text-slate-400'}`} />
                    <span>{coverPhotoIndex === previewLightboxIndex ? 'Primary Cover Photo' : 'Make Cover Photo'}</span>
                  </button>

                  <button
                    type="button"
                    onClick={() => setPreviewLightboxIndex(null)}
                    className="w-9 h-9 rounded-full bg-slate-800 hover:bg-rose-600 text-slate-300 hover:text-white flex items-center justify-center font-bold text-sm transition-colors cursor-pointer"
                  >
                    ✕
                  </button>
                </div>
              </div>

              {/* MAIN IMAGE DISPLAY AREA */}
              <div className="relative flex-1 flex items-center justify-center my-4 overflow-hidden">
                {attachedMediaFiles[previewLightboxIndex].type.startsWith('image/') ? (
                  <img
                    src={getMediaPreviewUrl(attachedMediaFiles[previewLightboxIndex])}
                    alt={attachedMediaFiles[previewLightboxIndex].name}
                    className="max-h-[65vh] max-w-full object-contain rounded-2xl border border-slate-800 shadow-2xl"
                  />
                ) : (
                  <div className="flex flex-col items-center gap-3 p-8 bg-slate-900 rounded-3xl border border-indigo-500/40">
                    <Video className="w-16 h-16 text-indigo-400 animate-pulse" />
                    <span className="text-white font-bold text-lg">MP4 Property Walkthrough Video</span>
                  </div>
                )}

                {/* PREVIOUS / NEXT LIGHTBOX BUTTONS */}
                {attachedMediaFiles.length > 1 && (
                  <>
                    <button
                      type="button"
                      onClick={() => setPreviewLightboxIndex((previewLightboxIndex - 1 + attachedMediaFiles.length) % attachedMediaFiles.length)}
                      className="absolute left-2 sm:left-6 top-1/2 -translate-y-1/2 w-12 h-12 rounded-full bg-slate-900/90 hover:bg-cyan-600 text-white flex items-center justify-center border border-white/20 shadow-2xl transition-all"
                    >
                      <ChevronLeft className="w-7 h-7" />
                    </button>

                    <button
                      type="button"
                      onClick={() => setPreviewLightboxIndex((previewLightboxIndex + 1) % attachedMediaFiles.length)}
                      className="absolute right-2 sm:right-6 top-1/2 -translate-y-1/2 w-12 h-12 rounded-full bg-slate-900/90 hover:bg-cyan-600 text-white flex items-center justify-center border border-white/20 shadow-2xl transition-all"
                    >
                      <ChevronRight className="w-7 h-7" />
                    </button>
                  </>
                )}
              </div>

              {/* BOTTOM 1-CLICK ROOM TAG SELECTION PILLS TOOLBAR */}
              <div className="bg-slate-900/90 border border-slate-800 p-4 rounded-2xl backdrop-blur-xl flex flex-col gap-3 shadow-2xl">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-mono font-bold text-slate-300 flex items-center gap-2">
                    <Tag className="w-4 h-4 text-emerald-400" />
                    Assign Room Tag to Photo #{previewLightboxIndex + 1}:
                  </span>
                  <span className="text-emerald-400 font-mono font-extrabold text-xs bg-emerald-950/80 px-3 py-1 rounded-xl border border-emerald-500/40">
                    Current: {attachedMediaTags[previewLightboxIndex] || DEFAULT_SMART_TAG_SEQUENCE[previewLightboxIndex % DEFAULT_SMART_TAG_SEQUENCE.length]}
                  </span>
                </div>

                <div className="flex flex-wrap gap-2 justify-center">
                  {[
                    { value: 'GENERAL', label: '🌐 None / General' },
                    { value: 'LIVING_ROOM', label: '🛋️ Living Room' },
                    { value: 'MASTER_BEDROOM', label: '🛏️ Master Bedroom' },
                    { value: 'BEDROOM', label: '🛏️ Guest Bedroom' },
                    { value: 'KITCHEN', label: '🍳 Kitchen' },
                    { value: 'BATHROOM', label: '🚿 Bathroom' },
                    { value: 'BALCONY', label: '🌅 Balcony & View' },
                    { value: 'ELEVATION', label: '🏢 Elevation & Exterior' },
                    { value: 'AMENITIES', label: '🏊 Amenities' },
                    { value: 'FLOOR_PLAN', label: '📐 Floor Plan' }
                  ].map((option) => {
                    const isSelected = (attachedMediaTags[previewLightboxIndex] || DEFAULT_SMART_TAG_SEQUENCE[previewLightboxIndex % DEFAULT_SMART_TAG_SEQUENCE.length]) === option.value;

                    return (
                      <button
                        key={option.value}
                        type="button"
                        onClick={() => setAttachedMediaTags({ ...attachedMediaTags, [previewLightboxIndex]: option.value as RoomTag })}
                        className={`px-3.5 py-2 rounded-xl text-xs font-bold font-mono transition-all cursor-pointer ${
                          isSelected
                            ? 'bg-gradient-to-r from-emerald-500 to-teal-600 text-white shadow-lg shadow-emerald-500/30 scale-105 border-2 border-emerald-300'
                            : 'bg-slate-950 text-slate-300 border border-slate-800 hover:border-slate-600 hover:text-white'
                        }`}
                      >
                        {option.label}
                      </button>
                    );
                  })}
                </div>
              </div>
            </motion.div>
          )}
        </AnimatePresence>

      </main>

      {/* TOP-LEVEL VIEWPORT-CENTERED INLINE QUICK EDIT MODAL DIALOG */}
      {isInlineEditOpen && editForm && typeof document !== 'undefined' && createPortal(
        <div
          className="fixed inset-0 z-[9999] grid items-start justify-items-center overflow-y-auto bg-slate-950/85 p-0 backdrop-blur-md sm:place-items-center sm:p-6"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setIsInlineEditOpen(false);
          }}
        >
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="property-edit-dialog-title"
            className="m-0 min-h-[100dvh] w-full max-w-3xl space-y-4 overflow-y-auto rounded-none border border-slate-800 bg-slate-900 p-4 text-white shadow-2xl sm:min-h-0 sm:max-h-[calc(100dvh-3rem)] sm:rounded-3xl sm:p-6"
          >
            <div className="flex items-center justify-between pb-3 border-b border-slate-800">
              <div>
                <h3 id="property-edit-dialog-title" className="text-lg font-bold font-['Outfit'] text-emerald-400 flex items-center gap-2">
                  ✏️ Edit property details
                </h3>
                <p className="text-xs text-slate-400">Review and update property details before publishing.</p>
              </div>
              <button
                type="button"
                onClick={() => setIsInlineEditOpen(false)}
                aria-label="Close property editor"
                className="text-slate-400 hover:text-white p-1 rounded-lg bg-slate-800 font-bold cursor-pointer"
              >
                ✕
              </button>
            </div>

            <form onSubmit={handleSaveInlineEdits} className="space-y-4">
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
                <div>
                  <label className="text-slate-400 block font-mono text-[10px] uppercase font-bold mb-1">Listing Title:</label>
                  <input
                    type="text"
                    value={editForm.title || ''}
                    onChange={(e) => setEditForm({ ...editForm, title: e.target.value })}
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-white font-bold focus:outline-none focus:border-emerald-500"
                  />
                </div>

                <div>
                  <label className="text-slate-400 block font-mono text-[10px] uppercase font-bold mb-1">Property Type:</label>
                  <select
                    value={editForm.type || 'FLAT'}
                    onChange={(e) => setEditForm({ ...editForm, type: e.target.value })}
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-white font-bold focus:outline-none focus:border-emerald-500"
                  >
                    <option value="FLAT">FLAT / APARTMENT</option>
                    <option value="HOUSE">HOUSE / VILLA</option>
                    <option value="PLOT">PLOT / LAND</option>
                    <option value="PENTHOUSE">PENTHOUSE</option>
                    <option value="STUDIO">STUDIO APARTMENT</option>
                    <option value="AIRBNB">AIRBNB / VACATION STAY</option>
                  </select>
                </div>

                <div>
                  <label className="text-slate-400 block font-mono text-[10px] uppercase font-bold mb-1">BHK Configuration:</label>
                  <input
                    type="text"
                    value={editForm.bhk || ''}
                    onChange={(e) => setEditForm({ ...editForm, bhk: e.target.value })}
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-white font-bold focus:outline-none focus:border-emerald-500"
                  />
                </div>

                <div>
                  <label className="text-slate-400 block font-mono text-[10px] uppercase font-bold mb-1">Bathrooms Count:</label>
                  <input
                    type="number"
                    value={editForm.bathrooms ?? ''}
                    onChange={(e) => setEditForm({ ...editForm, bathrooms: e.target.value ? Number(e.target.value) : '' })}
                    placeholder="e.g. 2"
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-white font-bold focus:outline-none focus:border-emerald-500"
                  />
                </div>

                <div>
                  <label className="text-slate-400 block font-mono text-[10px] uppercase font-bold mb-1">Monthly Rent Amount (₹):</label>
                  <input
                    type="number"
                    value={editForm.rentAmount ?? ''}
                    onChange={(e) => setEditForm({ ...editForm, rentAmount: e.target.value ? Number(e.target.value) : '', rentVal: e.target.value ? `₹${Number(e.target.value).toLocaleString('en-IN')}` : '' })}
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-amber-400 font-bold focus:outline-none focus:border-emerald-500"
                  />
                </div>

                <div>
                  <label className="text-slate-400 block font-mono text-[10px] uppercase font-bold mb-1">Brokerage Fee / Terms:</label>
                  <input
                    type="text"
                    value={editForm.brokerageVal || ''}
                    onChange={(e) => setEditForm({ ...editForm, brokerageVal: e.target.value })}
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-purple-300 font-bold focus:outline-none focus:border-emerald-500"
                  />
                </div>

                <div>
                  <label className="text-slate-400 block font-mono text-[10px] uppercase font-bold mb-1">Security Deposit Terms:</label>
                  <input
                    type="text"
                    value={editForm.depositVal || ''}
                    onChange={(e) => setEditForm({ ...editForm, depositVal: e.target.value })}
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-blue-300 font-bold focus:outline-none focus:border-emerald-500"
                  />
                </div>

                <div>
                  <label className="text-slate-400 block font-mono text-[10px] uppercase font-bold mb-1">Carpet Area (SqFt):</label>
                  <input
                    type="text"
                    value={editForm.areaSqFt || ''}
                    onChange={(e) => setEditForm({ ...editForm, areaSqFt: e.target.value })}
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-teal-300 font-bold focus:outline-none focus:border-emerald-500"
                  />
                </div>

                <div>
                  <label className="text-slate-400 block font-mono text-[10px] uppercase font-bold mb-1">Vastu Facing Direction:</label>
                  <select
                    value={editForm.vastuFacing || 'Not Specified'}
                    onChange={(e) => setEditForm({ ...editForm, vastuFacing: e.target.value })}
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-cyan-300 font-bold focus:outline-none focus:border-emerald-500"
                  >
                    <option value="Not Specified">Not Specified</option>
                    <option value="East Facing">East Facing</option>
                    <option value="North Facing">North Facing</option>
                    <option value="North-East Facing">North-East Facing</option>
                    <option value="West Facing">West Facing</option>
                    <option value="South Facing">South Facing</option>
                  </select>
                </div>

                <div>
                  <label className="text-slate-400 block font-mono text-[10px] uppercase font-bold mb-1">Furnishing Status:</label>
                  <select
                    value={editForm.furnishingStatus || 'UNSPECIFIED'}
                    onChange={(e) => setEditForm({ ...editForm, furnishingStatus: e.target.value })}
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-indigo-300 font-bold focus:outline-none focus:border-emerald-500"
                  >
                    <option value="UNSPECIFIED">UNSPECIFIED</option>
                    <option value="FULLY_FURNISHED">FULLY FURNISHED</option>
                    <option value="SEMI_FURNISHED">SEMI FURNISHED</option>
                    <option value="UNFURNISHED">UNFURNISHED</option>
                  </select>
                </div>

                <div>
                  <label className="text-slate-400 block font-mono text-[10px] uppercase font-bold mb-1">Availability</label>
                  <select
                    value={editForm.availabilityStatus || 'UNSPECIFIED'}
                    onChange={(event) => {
                      const availabilityStatus = event.target.value as AvailabilityStatusValue;
                      setEditForm({
                        ...editForm,
                        availabilityStatus,
                        availableFrom: '',
                        possessionDate: availabilityStatus === 'READY_NOW' ? 'Ready To Move' : '',
                        conflicts: (editForm.conflicts || []).filter(
                          (conflict: string) => !conflict.startsWith('Possession date')
                        )
                      });
                    }}
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-emerald-300 font-bold focus:outline-none focus:border-emerald-500"
                  >
                    <option value="UNSPECIFIED">Not provided</option>
                    <option value="READY_NOW">Ready to move now</option>
                    <option value="AVAILABLE_FROM_DATE">Available from a specific date</option>
                  </select>
                </div>

                {editForm.availabilityStatus === 'AVAILABLE_FROM_DATE' && (
                  <div>
                    <label className="text-slate-400 block font-mono text-[10px] uppercase font-bold mb-1">Possession date</label>
                    <input
                      type="date"
                      value={editForm.availableFrom || ''}
                      onChange={(event) => setEditForm({
                        ...editForm,
                        availableFrom: event.target.value,
                        possessionDate: formatAvailabilityDate(event.target.value),
                        conflicts: (editForm.conflicts || []).filter(
                          (conflict: string) => !conflict.startsWith('Possession date')
                        )
                      })}
                      className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-emerald-300 font-bold focus:outline-none focus:border-emerald-500 [color-scheme:dark]"
                    />
                  </div>
                )}

                <div>
                  <label className="text-slate-400 block font-mono text-[10px] uppercase font-bold mb-1">Listing Status:</label>
                  <select
                    value={editForm.status || 'LIVE'}
                    onChange={(e) => setEditForm({ ...editForm, status: e.target.value })}
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-emerald-300 font-bold focus:outline-none focus:border-emerald-500"
                  >
                    <option value="LIVE">LIVE</option>
                    <option value="DRAFT">DRAFT</option>
                    <option value="PAUSED">PAUSED</option>
                  </select>
                </div>

                <div>
                  <label className="text-slate-400 block font-mono text-[10px] uppercase font-bold mb-1">Locality / Sector:</label>
                  <input
                    type="text"
                    value={editForm.sector || ''}
                    onChange={(e) => setEditForm({ ...editForm, sector: e.target.value })}
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-emerald-300 font-bold focus:outline-none focus:border-emerald-500"
                  />
                </div>

                <div>
                  <label className="text-slate-400 block font-mono text-[10px] uppercase font-bold mb-1">Target City:</label>
                  <input
                    type="text"
                    value={editForm.city || ''}
                    onChange={(e) => setEditForm({ ...editForm, city: e.target.value })}
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-white font-bold focus:outline-none focus:border-emerald-500"
                  />
                </div>

                <div>
                  <label className="text-slate-400 block font-mono text-[10px] uppercase font-bold mb-1">Owner Name:</label>
                  <input
                    type="text"
                    value={editForm.ownerName || ''}
                    onChange={(e) => setEditForm({ ...editForm, ownerName: e.target.value })}
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-pink-300 font-bold focus:outline-none focus:border-emerald-500"
                  />
                </div>

                <div>
                  <label className="text-slate-400 block font-mono text-[10px] uppercase font-bold mb-1">Owner Phone Number:</label>
                  <input
                    type="text"
                    value={editForm.ownerPhone || ''}
                    onChange={(e) => setEditForm({ ...editForm, ownerPhone: e.target.value })}
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-pink-300 font-bold focus:outline-none focus:border-emerald-500"
                  />
                </div>
              </div>

              <div className="flex items-center justify-end gap-3 pt-3 border-t border-slate-800">
                <button
                  type="button"
                  onClick={() => setIsInlineEditOpen(false)}
                  className="px-4 py-2 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded-xl font-bold text-xs cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="px-5 py-2 bg-emerald-600 hover:bg-emerald-500 text-white rounded-xl font-black text-xs shadow-md shadow-emerald-600/30 flex items-center gap-1.5 cursor-pointer"
                >
                  <CheckCircle2 className="w-4 h-4" />
                  <span>Save changes</span>
                </button>
              </div>
            </form>
          </div>
        </div>,
        document.body
      )}

    </div>
  );
};
