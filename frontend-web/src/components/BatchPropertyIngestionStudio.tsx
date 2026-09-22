import React, { useState, useEffect, useRef, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { motion,
  AnimatePresence
} from 'framer-motion';
import {
  Sparkles,
  Mic,
  MicOff,
  CheckCircle2,
  AlertCircle,
  X,
  Plus,
  Scissors,
  Building2,
  ChevronLeft,
  ChevronRight,
  ChevronDown,
  Trash2,
  RefreshCw,
  Star,
  Video,
  Clock
} from 'lucide-react';
import { propertyService, createStableUploadRequestId } from '../services/propertyService';
import { getErrorDetails, getErrorMessage } from '../services/apiError';
import { describeMediaLimits, prepareMediaForUpload } from '../utils/imageOptimizer';
import { useNotification } from '../context/NotificationContext';
import { RoomTag, ROOM_TAG_OPTIONS, DEFAULT_SMART_TAG_SEQUENCE } from '../types';
import { DraftManagementBar } from './DraftManagementBar';
import { usePropertyDraft, CompletedListingSummary } from '../hooks/usePropertyDraft';
import { draftService, DraftMedia, DraftDetail } from '../services/draftService';
import { countDetectedProperties } from '../utils/propertyPromptComposer';

export interface StagedMediaItem {
  id: string;
  file?: File;
  previewUrl: string;
  roomTag: RoomTag;
  isCover: boolean;
  originalFilename?: string;
  fileSizeBytes?: number;
  contentType?: string;
  stagingStatus?: 'staging' | 'staged' | 'failed';
}

export const isMediaVideo = (item: StagedMediaItem): boolean => {
  if (item.file?.type) return item.file.type.startsWith('video/');
  if (item.contentType) return item.contentType.startsWith('video/');
  if (item.originalFilename) return /\.(mp4|mov|webm|m4v|3gp)$/i.test(item.originalFilename);
  return false;
};

export const getMediaFilename = (item: StagedMediaItem): string => {
  return item.file?.name || item.originalFilename || 'Media file';
};

export const getMediaFileSize = (item: StagedMediaItem): number => {
  return item.file?.size ?? item.fileSizeBytes ?? 0;
};

interface StagedProperty {
  id: string;
  learningExampleId: string;
  promptIndex: number;
  rawPrompt: string;
  title: string;
  bhk: string;
  type: string;
  status: string;
  sector: string;
  city: string;
  colony: string;
  address: string;
  state: string;
  pincode: string;
  landmark: string;
  rentAmount: number;
  rentVal: string;
  depositVal: string;
  brokerageVal: string;
  brokerageDays: string;
  areaSqFt: string;
  bathrooms: string;
  possessionDate: string;
  availabilityStatus: AvailabilityStatusValue;
  availableFrom: string;
  ownerName: string;
  ownerPhone: string;
  furnishingStatus: string;
  vastuFacing: string;
  amenities: string[];
  description: string;
  parserMissingFields: string[];
  conflicts: string[];
  appliedAmendments: string[];
  floor?: number | null;
  totalFloors?: number | null;
  preferredTenants?: string[];
  mediaUrls: string[];
  stagedMedia: StagedMediaItem[];
  localPhotos: File[];
  localPhotoPreviews: string[];
  isConfirmed: boolean;
  isValid: boolean;
  publishedId?: number;
  missingFields: string[];
  mediaUploadStatus: 'idle' | 'preparing' | 'uploading' | 'retrying' | 'complete' | 'failed';
  mediaUploadProgress: number;
  mediaUploadMessage: string;
  failedMediaFiles: File[];
}

type AvailabilityStatusValue = 'READY_NOW' | 'AVAILABLE_FROM_DATE' | 'UNSPECIFIED';

interface BatchPropertyIngestionStudioProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: (publishedCount: number) => void;
  onPartialSuccess?: (publishedCount: number, remainingCount: number) => void;
  embedded?: boolean;
  initialDetails?: string;
  initialMediaFiles?: File[];
  initialDraftId?: string | null;
  onSwitchToSingleDraft?: (draftId: string) => void;
  onAlreadyPublished?: (detail: DraftDetail, count: number, listings?: CompletedListingSummary[]) => void;
}

const COLOR_PALETTES = [
  { border: 'border-blue-500/40', bg: 'bg-blue-500/10', text: 'text-blue-400', badge: 'bg-blue-500/20 text-blue-300' },
  { border: 'border-emerald-500/40', bg: 'bg-emerald-500/10', text: 'text-emerald-400', badge: 'bg-emerald-500/20 text-emerald-300' },
  { border: 'border-purple-500/40', bg: 'bg-purple-500/10', text: 'text-purple-400', badge: 'bg-purple-500/20 text-purple-300' },
  { border: 'border-amber-500/40', bg: 'bg-amber-500/10', text: 'text-amber-400', badge: 'bg-amber-500/20 text-amber-300' },
  { border: 'border-cyan-500/40', bg: 'bg-cyan-500/10', text: 'text-cyan-400', badge: 'bg-cyan-500/20 text-cyan-300' },
];
const INDIAN_MOBILE_PATTERN = /^(?:\+?91[\s-]?)?[6-9]\d{4}[\s-]?\d{5}$/;
const EMPTY_VALUE_PATTERN = /^(?:not specified|unspecified|unmentioned|unknown|n\/a)$/i;
const SPOKEN_PROPERTY_BOUNDARY_PATTERN = /\b(?:and\s+)?(?:list\s+)?(?:the\s+)?(?:one\s+)?(?:other|another|next|second|third|fourth)\s+(?:property|flat|house|listing|unit)(?:\s+is)?\b/gi;
const SPOKEN_RENT_PREFIX_PATTERN = /(?:rent\s+is\s+|kiraya\s+)/gi;
const READY_AVAILABILITY_PATTERN = /ready\s*to\s*move|immediate/i;
const ISO_DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;
const DISPLAY_MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const isProvided = (value: string | null | undefined): boolean =>
  Boolean(value && value.trim() && !EMPTY_VALUE_PATTERN.test(value.trim()));

const toEditableValue = (value: string | null | undefined): string =>
  isProvided(value) ? value!.trim() : '';

const normalizeAvailabilityStatus = (dto: any): AvailabilityStatusValue => {
  if (dto?.availabilityStatus === 'READY_NOW' || dto?.availabilityStatus === 'AVAILABLE_FROM_DATE') {
    return dto.availabilityStatus;
  }
  if (isProvided(dto?.availableFrom)) return 'AVAILABLE_FROM_DATE';
  if (READY_AVAILABILITY_PATTERN.test(dto?.possessionDate || '')) return 'READY_NOW';
  return isProvided(dto?.possessionDate) ? 'AVAILABLE_FROM_DATE' : 'UNSPECIFIED';
};

const formatAvailabilityDate = (isoDate: string): string => {
  const match = ISO_DATE_PATTERN.exec(isoDate);
  if (!match) return '';
  const monthIndex = Number(match[2]) - 1;
  if (monthIndex < 0 || monthIndex >= DISPLAY_MONTHS.length) return '';
  return `${Number(match[3])} ${DISPLAY_MONTHS[monthIndex]} ${match[1]}`;
};

const getPropertyTabLabel = (card: StagedProperty): string => {
  const layout = isProvided(card.bhk) ? card.bhk : 'Property';
  const context = isProvided(card.sector)
    ? card.sector
    : isProvided(card.type)
      ? card.type
      : 'Details needed';
  return `${layout} ${context}`;
};

const getAdditionalDetailCount = (card: StagedProperty): number => [
  card.colony,
  card.landmark,
  card.address,
  card.state,
  card.pincode,
  card.brokerageVal,
  card.brokerageDays,
  card.areaSqFt,
  card.bathrooms,
  card.furnishingStatus,
  card.vastuFacing,
  card.possessionDate,
  card.status,
  card.ownerName,
  card.amenities.length > 0 ? card.amenities.join(', ') : '',
  card.description
].filter((value) => isProvided(value)).length;

const normalizeOwnerPhone = (value: string | null | undefined): string => {
  const trimmed = value?.trim() || '';
  if (!trimmed || EMPTY_VALUE_PATTERN.test(trimmed)) return '';

  let digits = trimmed.replace(/\D/g, '');
  if (digits.startsWith('91') && digits.length === 12) digits = digits.slice(2);

  if (!/^[6-9]\d{9}$/.test(digits)) return trimmed;
  return `+91 ${digits.slice(0, 5)} ${digits.slice(5)}`;
};

const hasValidOwnerPhone = (value: string): boolean =>
  INDIAN_MOBILE_PATTERN.test(normalizeOwnerPhone(value));

const isVideoUrl = (url: string): boolean => /\.(?:mp4|webm|mov)(?:[?#]|$)/i.test(url);

const getUnresolvedConflictField = (field: keyof StagedProperty): string | null => {
  const conflictFields: Partial<Record<keyof StagedProperty, string>> = {
    rentAmount: 'Monthly Rent',
    brokerageVal: 'Brokerage',
    depositVal: 'Security Deposit',
    furnishingStatus: 'Furnishing',
    vastuFacing: 'Facing Direction'
  };
  return conflictFields[field] ?? null;
};

const validateCard = (card: StagedProperty): StagedProperty => {
  const missing: string[] = [];
  if (!hasValidOwnerPhone(card.ownerPhone)) missing.push('Owner phone number');
  if (!isProvided(card.sector)) missing.push('Locality');
  if (!isProvided(card.city)) missing.push('City');
  if (!card.rentAmount || card.rentAmount <= 0) missing.push('Monthly rent');
  if (!isProvided(card.bhk)) missing.push('Layout');
  if (!isProvided(card.type)) missing.push('Property type');
  if (!isProvided(card.depositVal) || !/\d/.test(card.depositVal)) missing.push('Security deposit');
  if (card.conflicts.length > 0) missing.push('Resolve conflicting details');
  if (typeof card.floor === 'number' && card.floor < 0) missing.push('Floor must be 0 (Ground) or greater');
  if (typeof card.totalFloors === 'number' && card.totalFloors < 1) missing.push('Total floors must be at least 1');
  const isLandedType = ['PLOT', 'LAND', 'HOUSE', 'VILLA'].includes(String(card.type || '').toUpperCase());
  if (!isLandedType && typeof card.floor === 'number' && typeof card.totalFloors === 'number' && card.floor > card.totalFloors) {
    missing.push('Floor cannot exceed total floors');
  }

  return {
    ...card,
    missingFields: missing,
    isValid: (card.isConfirmed || Boolean(card.publishedId)) && missing.length === 0
  };
};

interface ReviewFieldProps {
  label: string;
  value: string | number;
  onValueChange: (value: string) => void;
  onBlur?: () => void;
  placeholder?: string;
  type?: 'text' | 'number';
  required?: boolean;
  multiline?: boolean;
  className?: string;
}

const ReviewField: React.FC<ReviewFieldProps> = ({
  label,
  value,
  onValueChange,
  onBlur,
  placeholder,
  type = 'text',
  required = false,
  multiline = false,
  className = ''
}) => {
  const hasValue = String(value ?? '').trim().length > 0;
  const fieldState = hasValue
    ? {
      accent: 'bg-emerald-400',
      card: 'border-slate-700/80 bg-slate-900/90 hover:border-emerald-500/40',
      label: 'Value included'
    }
    : required
      ? {
        accent: 'bg-rose-400',
        card: 'border-rose-500/40 bg-rose-500/[0.04] hover:border-rose-400/60',
        label: 'Required information is missing'
      }
      : {
        accent: 'bg-amber-400',
        card: 'border-dashed border-slate-700/90 bg-slate-950/35 hover:border-amber-400/45',
        label: 'Optional information is not provided'
      };

  return (
  <motion.label
    initial={{ opacity: 0, y: 5 }}
    animate={{ opacity: 1, y: 0 }}
    whileHover={{ y: -2 }}
    transition={{ type: 'spring', stiffness: 420, damping: 30 }}
    className={`relative block overflow-hidden rounded-2xl border p-3 transition-colors duration-200 focus-within:border-cyan-400/70 focus-within:ring-2 focus-within:ring-cyan-400/10 ${fieldState.card} ${className}`}
  >
    <span
      aria-label={fieldState.label}
      title={fieldState.label}
      className={`absolute inset-x-3 top-0 h-px ${fieldState.accent}`}
    />
    <span className="mb-2 block text-[11px] font-semibold tracking-[0.04em] text-slate-400">
      {label}{required && <span className="ml-1 text-rose-400">*</span>}
    </span>
    {multiline ? (
      <textarea
        value={value}
        placeholder={placeholder}
        onChange={(event) => onValueChange(event.target.value)}
        onBlur={onBlur}
        rows={3}
        className="w-full resize-y bg-transparent text-[16px] sm:text-xs font-semibold text-slate-100 outline-none placeholder:text-slate-600 placeholder:italic"
      />
    ) : (
      <input
        type={type}
        value={value}
        placeholder={placeholder}
        onChange={(event) => onValueChange(event.target.value)}
        onBlur={onBlur}
        className="w-full bg-transparent text-[16px] sm:text-xs font-semibold text-slate-100 outline-none placeholder:text-slate-600 placeholder:italic"
      />
    )}
  </motion.label>
  );
};

interface AvailabilityEditorProps {
  status: AvailabilityStatusValue;
  availableFrom: string;
  onChange: (status: AvailabilityStatusValue, availableFrom: string) => void;
  className?: string;
}

const AvailabilityEditor: React.FC<AvailabilityEditorProps> = ({
  status,
  availableFrom,
  onChange,
  className = ''
}) => {
  const hasValue = status === 'READY_NOW'
    || (status === 'AVAILABLE_FROM_DATE' && Boolean(availableFrom));

  return (
    <motion.div
      initial={{ opacity: 0, y: 5 }}
      animate={{ opacity: 1, y: 0 }}
      whileHover={{ y: -2 }}
      transition={{ type: 'spring', stiffness: 420, damping: 30 }}
      className={`relative overflow-hidden rounded-2xl border p-3 transition-colors duration-200 focus-within:border-cyan-400/70 focus-within:ring-2 focus-within:ring-cyan-400/10 ${
        hasValue
          ? 'border-slate-700/80 bg-slate-900/90 hover:border-emerald-500/40'
          : 'border-dashed border-slate-700/90 bg-slate-950/35 hover:border-amber-400/45'
      } ${className}`}
    >
      <span
        aria-label={hasValue ? 'Availability included' : 'Availability is not provided'}
        title={hasValue ? 'Availability included' : 'Availability is not provided'}
        className={`absolute inset-x-3 top-0 h-px ${hasValue ? 'bg-emerald-400' : 'bg-amber-400'}`}
      />
      <label className="mb-2 block text-[11px] font-semibold tracking-[0.04em] text-slate-400">
        Availability
      </label>
      <select
        value={status}
        onChange={(event) => onChange(event.target.value as AvailabilityStatusValue, '')}
        className="w-full bg-transparent text-[16px] sm:text-xs font-semibold text-slate-100 outline-none"
      >
        <option value="UNSPECIFIED">Not provided</option>
        <option value="READY_NOW">Ready to move now</option>
        <option value="AVAILABLE_FROM_DATE">Available from a specific date</option>
      </select>
      {status === 'AVAILABLE_FROM_DATE' && (
        <div className="mt-3 border-t border-slate-800 pt-3">
          <label className="mb-1.5 block text-[10px] font-semibold text-slate-500">Possession date</label>
          <input
            type="date"
            value={availableFrom}
            onChange={(event) => onChange(status, event.target.value)}
            className="w-full bg-transparent text-[16px] sm:text-xs font-semibold text-slate-100 outline-none [color-scheme:dark]"
          />
        </div>
      )}
    </motion.div>
  );
};

const PREFERRED_TENANT_OPTIONS: { value: string; label: string }[] = [
  { value: 'FAMILY', label: 'Family' },
  { value: 'WORKING_PROFESSIONALS', label: 'Working Professionals' },
  { value: 'BACHELORS', label: 'Bachelors' },
  { value: 'STUDENTS', label: 'Students' },
  { value: 'ANY', label: 'Any / No Preference' }
];

interface PreferredTenantEditorProps {
  selected: string[];
  onChange: (updated: string[]) => void;
  className?: string;
}

const PreferredTenantEditor: React.FC<PreferredTenantEditorProps> = ({
  selected = [],
  onChange,
  className = ''
}) => {
  const toggleOption = (val: string) => {
    let next: string[];
    if (val === 'ANY') {
      if (selected.includes('ANY')) {
        next = [];
      } else {
        next = ['ANY'];
      }
    } else {
      const withoutAny = selected.filter((s) => s !== 'ANY');
      if (withoutAny.includes(val)) {
        next = withoutAny.filter((s) => s !== val);
      } else {
        next = [...withoutAny, val];
      }
    }
    onChange(next);
  };

  const hasSelection = Array.isArray(selected) && selected.length > 0;
  const accent = hasSelection ? 'bg-emerald-400' : 'bg-amber-400';
  const cardBorder = hasSelection
    ? 'border-slate-700/80 bg-slate-900/90 hover:border-emerald-500/40'
    : 'border-dashed border-slate-700/90 bg-slate-950/35 hover:border-amber-400/45';

  return (
    <motion.div
      initial={{ opacity: 0, y: 5 }}
      animate={{ opacity: 1, y: 0 }}
      whileHover={{ y: -2 }}
      transition={{ type: 'spring', stiffness: 420, damping: 30 }}
      className={`relative overflow-hidden rounded-2xl border p-3 transition-colors duration-200 ${cardBorder} ${className}`}
    >
      <span
        aria-label={hasSelection ? 'Preferred tenant specified' : 'No tenant preference specified'}
        title={hasSelection ? 'Preferred tenant specified' : 'No tenant preference specified'}
        className={`absolute inset-x-3 top-0 h-px ${accent}`}
      />
      <label className="mb-2 block text-[11px] font-semibold tracking-[0.04em] text-slate-400">
        Preferred tenant
      </label>
      <div className="flex flex-wrap gap-1.5 pt-0.5">
        {PREFERRED_TENANT_OPTIONS.map((opt) => {
          const isSelected = Array.isArray(selected) && selected.includes(opt.value);
          return (
            <button
              key={opt.value}
              type="button"
              onClick={() => toggleOption(opt.value)}
              className={`rounded-lg px-2.5 py-1 text-xs font-semibold transition-all ${
                isSelected
                  ? 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/40 shadow-sm'
                  : 'bg-slate-800/60 text-slate-400 border border-slate-700/50 hover:bg-slate-800 hover:text-slate-200'
              }`}
            >
              {opt.label}
            </button>
          );
        })}
      </div>
    </motion.div>
  );
};

export const BatchPropertyIngestionStudio: React.FC<BatchPropertyIngestionStudioProps> = ({
  isOpen,
  onClose,
  onSuccess,
  embedded = false,
  initialDetails = '',
  initialMediaFiles = [],
  initialDraftId = null,
  onSwitchToSingleDraft,
  onPartialSuccess,
  onAlreadyPublished
}) => {
  const { showErrorDialog, notifyWarning } = useNotification();
  const [rawPrompts, setRawPrompts] = useState<string>('');
  const [stagedCards, setStagedCards] = useState<StagedProperty[]>([]);
  const [completedListings, setCompletedListings] = useState<Array<{ cardId: string; listingId: number; title: string }>>([]);
  const [isParsing, setIsParsing] = useState<boolean>(false);
  const [isPublishing, setIsPublishing] = useState<boolean>(false);
  const [publishingCardId, setPublishingCardId] = useState<string | null>(null);
  const [isListening, setIsListening] = useState<boolean>(false);
  const [speechSupported, setSpeechSupported] = useState<boolean>(true);
  const [isDictationChoiceOpen, setIsDictationChoiceOpen] = useState<boolean>(false);
  const [detectedCount, setDetectedCount] = useState<number>(0);
  const [activeCardId, setActiveCardId] = useState<string | null>(null);
  const [mobileWorkspaceView, setMobileWorkspaceView] = useState<'descriptions' | 'review'>('descriptions');

  const recognitionRef = useRef<any>(null);
  const batchSpeechBaseTextRef = useRef<string>('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const importedDetailsRef = useRef<string>('');
  const initialMediaAddedRef = useRef(false);
  const rawPromptsRef = useRef<string>('');
  const parsedPromptsRef = useRef<string>('');
  const parseRequestIdRef = useRef(0);
  const inputSourceRef = useRef<'TYPED' | 'DICTATED' | 'MIXED'>('TYPED');
  const stagedCardsRef = useRef<StagedProperty[]>(stagedCards);

  useEffect(() => {
    stagedCardsRef.current = stagedCards;
  }, [stagedCards]);

  const revokeCardMediaUrls = useCallback((card: StagedProperty) => {
    (card.stagedMedia || []).forEach((item) => {
      try {
        URL.revokeObjectURL(item.previewUrl);
      } catch (_) {}
    });
    (card.localPhotoPreviews || []).forEach((url) => {
      try {
        URL.revokeObjectURL(url);
      } catch (_) {}
    });
  }, []);

  useEffect(() => {
    return () => {
      stagedCardsRef.current.forEach(revokeCardMediaUrls);
    };
  }, [revokeCardMediaUrls]);

  const pendingSingleMediaRef = useRef<DraftMedia[]>([]);

  // Admin Draft & Recovery System for Batch Upload
  const handleRestoreBatchDraft = useCallback(async (payload: any, media?: DraftMedia[]) => {
    if (!payload) return;
    if (typeof payload.rawPrompts === 'string') {
      rawPromptsRef.current = payload.rawPrompts;
      parsedPromptsRef.current = payload.rawPrompts;
      setRawPrompts(payload.rawPrompts);
    } else if (typeof payload.newBhkLabel === 'string' && !rawPromptsRef.current.trim()) {
      const transitionText = payload.newBhkLabel;
      rawPromptsRef.current = transitionText;
      parsedPromptsRef.current = transitionText;
      setRawPrompts(transitionText);
      void parseBatchDetails(transitionText);
    }
    if (typeof payload.detectedCount === 'number') {
      setDetectedCount(payload.detectedCount);
    }
    if (payload.activeCardId) {
      setActiveCardId(payload.activeCardId);
    }
    if (payload.mobileWorkspaceView) {
      setMobileWorkspaceView(payload.mobileWorkspaceView);
    } else if (Array.isArray(payload.stagedCards) && payload.stagedCards.length > 0) {
      setMobileWorkspaceView('review');
    }
    if (Array.isArray(payload.completedListings)) {
      setCompletedListings(payload.completedListings);
    }
    if (Array.isArray(media)) {
      pendingSingleMediaRef.current = media.filter((m) => !m.cardId);
    }
    if (Array.isArray(payload.stagedCards) && payload.stagedCards.length > 0) {
      const serverMedia = Array.isArray(media) ? media : [];
      const restoredCards: StagedProperty[] = payload.stagedCards.map((card: StagedProperty, idx: number) => {
        const cardMedia = serverMedia.filter((m) => m.cardId === card.id || (!m.cardId && idx === 0));
        const existingStaged = Array.isArray(card.stagedMedia) ? card.stagedMedia : [];

        // Build composite media items from serverMedia and saved card.stagedMedia
        const mergedMediaMap = new Map<string, StagedMediaItem>();

        // 1. Add saved staged media metadata from payload
        existingStaged.forEach((item) => {
          mergedMediaMap.set(item.id, {
            ...item,
            originalFilename: item.originalFilename || item.file?.name || '',
            fileSizeBytes: item.fileSizeBytes || item.file?.size || 0,
            previewUrl: item.previewUrl || ''
          });
        });

        // 2. Overlay server media records with payload-authoritative metadata precedence
        cardMedia.forEach((m) => {
          const prev = mergedMediaMap.get(m.mediaId);
          const isVideo = isMediaVideo({
            id: m.mediaId,
            file: prev?.file,
            previewUrl: '',
            roomTag: (prev?.roomTag || m.roomTag || 'LIVING_ROOM') as RoomTag,
            isCover: false,
            originalFilename: m.originalFilename || prev?.originalFilename || '',
            contentType: m.contentType || prev?.contentType || ''
          });
          const candidateCover = typeof prev?.isCover === 'boolean'
            ? prev.isCover
            : typeof m.isCover === 'boolean'
              ? m.isCover
              : false;

          mergedMediaMap.set(m.mediaId, {
            id: m.mediaId,
            file: prev?.file,
            previewUrl: m.previewUrl || prev?.previewUrl || '',
            roomTag: prev?.roomTag || m.roomTag || 'LIVING_ROOM',
            isCover: !isVideo && candidateCover,
            originalFilename: m.originalFilename || prev?.originalFilename || '',
            fileSizeBytes: m.fileSizeBytes || prev?.fileSizeBytes || 0,
            contentType: m.contentType || prev?.contentType || 'image/jpeg',
            stagingStatus: 'staged'
          });
        });

        const stagedMedia = Array.from(mergedMediaMap.values());
        return {
          ...card,
          floor: typeof card.floor === 'number' ? card.floor : null,
          totalFloors: typeof card.totalFloors === 'number' ? card.totalFloors : null,
          preferredTenants: Array.isArray(card.preferredTenants) ? card.preferredTenants : [],
          stagedMedia,
          localPhotos: stagedMedia.map((m) => m.file).filter(Boolean) as File[],
          localPhotoPreviews: stagedMedia.map((m) => m.previewUrl).filter(Boolean)
        };
      });

      setStagedCards(restoredCards);
      if (!payload.activeCardId && restoredCards[0]) {
        setActiveCardId(restoredCards[0].id);
      }

      // Background binary File download from B2 staging to reconstruct File objects
      if (serverMedia.length > 0) {
        draftService.restoreMediaFiles(serverMedia).then((restored) => {
          if (restored.byMediaId && restored.byMediaId.size > 0) {
            setStagedCards((current) =>
              current.map((c) => {
                let changed = false;
                const updatedMedia = (c.stagedMedia || []).map((item) => {
                  const matchingFile = restored.byMediaId.get(item.id);
                  if (matchingFile && !item.file) {
                    changed = true;
                    return { ...item, file: matchingFile, previewUrl: URL.createObjectURL(matchingFile) };
                  }
                  return item;
                });
                if (!changed) return c;
                return {
                  ...c,
                  stagedMedia: updatedMedia,
                  localPhotos: updatedMedia.map((m) => m.file).filter(Boolean) as File[],
                  localPhotoPreviews: updatedMedia.map((m) => m.previewUrl).filter(Boolean)
                };
              })
            );
          }
        }).catch((err) => {
          console.warn('Background batch media restoration notice:', err);
        });
      }
    }
  }, []);

  const handleClearBatchDraftState = useCallback(() => {
    stagedCardsRef.current.forEach(revokeCardMediaUrls);
    setStagedCards([]);
    setCompletedListings([]);
    setRawPrompts('');
    rawPromptsRef.current = '';
    parsedPromptsRef.current = '';
    setDetectedCount(0);
    setActiveCardId(null);
    setMobileWorkspaceView('descriptions');
  }, [revokeCardMediaUrls]);

  const handleBatchDraftAlreadyPublished = useCallback((
    detail: DraftDetail,
    completedCount: number,
    completedListings: CompletedListingSummary[]
  ) => {
    if (onAlreadyPublished) {
      onAlreadyPublished(detail, completedCount, completedListings);
    }
    onClose();
  }, [onAlreadyPublished, onClose]);

  const batchDraft = usePropertyDraft({
    draftType: 'BATCH',
    initialDraftId,
    onRestoreDraft: handleRestoreBatchDraft,
    onClearDraftState: handleClearBatchDraftState,
    onDraftAlreadyPublished: handleBatchDraftAlreadyPublished
  });

  useEffect(() => {
    if (isPublishing) return;
    const hasMeaningfulWork = Boolean(rawPrompts.trim() || stagedCards.length > 0 || completedListings.length > 0);
    if (!hasMeaningfulWork) return;

    const remainingCount = stagedCards.length;
    let summary = '';
    if (completedListings.length > 0) {
      summary = remainingCount === 1 ? 'Batch — 1 property remaining' : `Batch — ${remainingCount} properties remaining`;
    } else if (stagedCards.length > 0) {
      summary = `Batch (${stagedCards.length} ${stagedCards.length === 1 ? 'property' : 'properties'})`;
    } else {
      summary = `Batch draft (${detectedCount || 1} ${detectedCount === 1 ? 'listing' : 'listings'})`;
    }

    batchDraft.scheduleAutosave(
      {
        rawPrompts,
        detectedCount,
        activeCardId,
        mobileWorkspaceView,
        completedListings,
        stagedCards: stagedCards.map((card) => ({
          ...card,
          localPhotos: [],
          localPhotoPreviews: [],
          stagedMedia: (card.stagedMedia || [])
            // Only persist media with confirmed durable B2 staging.
            // 'staging' items (upload in flight or silently failed) must not become
            // ghost payload entries that cannot be resolved after a hard refresh.
            // When B2 staging succeeds, setStagedCards flips status to 'staged' and
            // the stagedCards dependency re-fires this autosave effect, guaranteeing
            // the item is captured in the next autosave cycle.
            .filter((m) => m.stagingStatus === 'staged')
            .map((m) => ({
              id: m.id,
              roomTag: m.roomTag,
              isCover: m.isCover,
              originalFilename: getMediaFilename(m),
              fileSizeBytes: getMediaFileSize(m),
              contentType: m.contentType || m.file?.type || 'image/jpeg'
            }))
        }))
      },
      summary,
      stagedCards.length || detectedCount || 1
    );
  }, [rawPrompts, stagedCards, completedListings, detectedCount, activeCardId, mobileWorkspaceView, batchDraft.scheduleAutosave, isPublishing]);

  const updateRawPrompts = useCallback((details: string) => {
    rawPromptsRef.current = details;
    setRawPrompts(details);

    if (details !== parsedPromptsRef.current) {
      stagedCardsRef.current.forEach(revokeCardMediaUrls);
      setStagedCards([]);
      setActiveCardId(null);
      setMobileWorkspaceView('descriptions');
    }
  }, [revokeCardMediaUrls]);

  const handleTypedPromptChange = useCallback((details: string) => {
    inputSourceRef.current = !details.trim()
      ? 'TYPED'
      : inputSourceRef.current === 'DICTATED'
        ? 'MIXED'
        : inputSourceRef.current;
    updateRawPrompts(details);
  }, [updateRawPrompts]);

  // iOS-safe background body scroll lock when opened as standalone modal
  useEffect(() => {
    if (!isOpen || embedded) return;
    const originalOverflow = document.body.style.overflow;
    const originalPosition = document.body.style.position;
    const originalTop = document.body.style.top;
    const originalWidth = document.body.style.width;
    const scrollY = window.scrollY;

    document.body.style.overflow = 'hidden';
    document.body.style.position = 'fixed';
    document.body.style.top = `-${scrollY}px`;
    document.body.style.width = '100%';

    return () => {
      document.body.style.overflow = originalOverflow;
      document.body.style.position = originalPosition;
      document.body.style.top = originalTop;
      document.body.style.width = originalWidth;
      window.scrollTo(0, scrollY);
    };
  }, [isOpen, embedded]);

  // Live Delimiter Property Counting (two-tier detection matching backend PropertyParserService)
  useEffect(() => {
    setDetectedCount(countDetectedProperties(rawPrompts));
  }, [rawPrompts]);

  // Initialize Web Speech Recognition
  useEffect(() => {
    return () => {
      if (recognitionRef.current) {
        try {
          recognitionRef.current.abort();
        } catch (_) {}
      }
    };
  }, []);

  const startDictation = (mode: 'replace' | 'append') => {
    const SpeechRecognition =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

    if (!SpeechRecognition) {
      notifyWarning(
        'Dictation not supported by this browser',
        'Your mobile browser does not support the Web Speech API. Tip: You can tap the microphone button on your phone keyboard to dictate directly into the text field.'
      );
      return;
    }

    if (recognitionRef.current) {
      try {
        recognitionRef.current.abort();
      } catch (_) {}
    }

    try {
      const isIOSOrSafari = /iPad|iPhone|iPod/.test(navigator.userAgent) ||
        (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1) ||
        /^((?!chrome|android).)*safari/i.test(navigator.userAgent);

      const recognition = new SpeechRecognition();
      recognition.continuous = !isIOSOrSafari;
      recognition.interimResults = true;
      recognition.lang = 'en-IN'; // Optimized for Indian English & Hinglish terms

      const currentDraft = rawPromptsRef.current.trim();
      inputSourceRef.current = mode === 'append' && currentDraft ? 'MIXED' : 'DICTATED';
      batchSpeechBaseTextRef.current = mode === 'append' ? currentDraft : '';
      if (mode === 'replace' && currentDraft) {
        updateRawPrompts('');
      }
      setIsDictationChoiceOpen(false);

      recognition.onresult = (event: any) => {
        let finalTranscript = '';
        let interimTranscript = '';

        for (let i = event.resultIndex; i < event.results.length; i++) {
          const text = event.results[i][0].transcript;
          if (event.results[i].isFinal) {
            finalTranscript += (finalTranscript ? ' ' : '') + text.trim();
          } else {
            interimTranscript += (interimTranscript ? ' ' : '') + text.trim();
          }
        }

        // Spoken listing boundaries become explicit separators without adding listing details.
        const formattedFinal = finalTranscript
          .replace(SPOKEN_PROPERTY_BOUNDARY_PATTERN, '\n\nNext property\n')
          .replace(SPOKEN_RENT_PREFIX_PATTERN, 'Rent: ');

        if (formattedFinal) {
          const base = batchSpeechBaseTextRef.current || '';
          const separator = base && !formattedFinal.startsWith('\n') ? ' ' : '';
          batchSpeechBaseTextRef.current = `${base}${separator}${formattedFinal}`;
        }

        const confirmedText = batchSpeechBaseTextRef.current;
        const interimSeparator = confirmedText && interimTranscript ? ' ' : '';
        updateRawPrompts(`${confirmedText}${interimSeparator}${interimTranscript}`);
      };

      recognition.onerror = (err: any) => {
        console.warn('Speech recognition warning:', err);
        setIsListening(false);
        setIsDictationChoiceOpen(false);
        const errType = err?.error;
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
        setIsListening(false);
        setIsDictationChoiceOpen(false);
      };

      recognitionRef.current = recognition;
      recognition.start();
      setIsListening(true);
    } catch (err: any) {
      console.warn('Mic start error', err);
      setIsListening(false);
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

  const toggleSpeech = () => {
    const SpeechRecognition =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

    if (!SpeechRecognition) {
      notifyWarning(
        'Dictation not supported by this browser',
        'Your mobile browser does not support the Web Speech API. You can use the microphone button on your phone keyboard to dictate directly into the text field.'
      );
      return;
    }

    if (isListening) {
      try {
        recognitionRef.current?.stop();
      } catch (_) {}
      setIsListening(false);
      return;
    }

    if (rawPromptsRef.current.trim()) {
      setIsDictationChoiceOpen(true);
      return;
    }

    startDictation('replace');
  };

  const parseBatchDetails = useCallback(async (details: string) => {
    if (!details.trim()) return;

    // Publication recovery guard: Stable card identity invariant.
    // If the draft has completed listings or any staged card with publishedId,
    // authoritative listing correlation exists in PostgreSQL. Never re-parse over active publication state.
    if (completedListings.length > 0 || stagedCards.some((c) => Boolean(c.publishedId))) {
      console.warn('Batch parse prevented: Draft is in publication recovery state with active listing correlations.');
      setMobileWorkspaceView('review');
      return;
    }

    const requestId = ++parseRequestIdRef.current;
    setIsParsing(true);
    initialMediaAddedRef.current = false;

    try {
      const dtos = await propertyService.parseBatchPrompts(details, inputSourceRef.current);
      const mapped: StagedProperty[] = dtos.map((dto: any, idx: number) => {
        const card: StagedProperty = {
          id: `staged-${idx}-${Date.now()}`,
          learningExampleId: dto.learningExampleId || '',
          promptIndex: dto.promptIndex || idx + 1,
          rawPrompt: dto.rawPrompt || details,
          title: dto.title || 'Untitled property',
          bhk: toEditableValue(dto.bhk),
          type: toEditableValue(dto.type),
          status: toEditableValue(dto.status),
          sector: toEditableValue(dto.sector),
          city: toEditableValue(dto.city),
          colony: toEditableValue(dto.colony),
          address: toEditableValue(dto.address),
          state: toEditableValue(dto.state),
          pincode: toEditableValue(dto.pincode),
          landmark: toEditableValue(dto.landmark),
          rentAmount: dto.rentAmount || 0,
          rentVal: toEditableValue(dto.rentVal),
          depositVal: toEditableValue(dto.depositVal),
          brokerageVal: toEditableValue(dto.brokerageVal),
          brokerageDays: toEditableValue(dto.brokerageDays),
          areaSqFt: toEditableValue(dto.areaSqFt),
          bathrooms: toEditableValue(dto.bathrooms),
          possessionDate: toEditableValue(dto.possessionDate),
          availabilityStatus: normalizeAvailabilityStatus(dto),
          availableFrom: toEditableValue(dto.availableFrom),
          ownerName: toEditableValue(dto.ownerName),
          ownerPhone: normalizeOwnerPhone(dto.ownerPhone),
          furnishingStatus: toEditableValue(dto.furnishingStatus),
          vastuFacing: toEditableValue(dto.vastuFacing),
          amenities: Array.isArray(dto.amenities) ? dto.amenities.filter(isProvided) : [],
          floor: typeof dto.floor === 'number' ? dto.floor : null,
          totalFloors: typeof dto.totalFloors === 'number' ? dto.totalFloors : null,
          preferredTenants: Array.isArray(dto.preferredTenants) ? dto.preferredTenants : [],
          description: toEditableValue(dto.description),
          parserMissingFields: Array.isArray(dto.missingFields) ? dto.missingFields : [],
          conflicts: Array.isArray(dto.conflicts) ? dto.conflicts : [],
          appliedAmendments: Array.isArray(dto.appliedAmendments) ? dto.appliedAmendments : [],
          mediaUrls: dto.mediaUrls || [],
          stagedMedia: [],
          localPhotos: [],
          localPhotoPreviews: [],
          isConfirmed: false,
          isValid: false,
          missingFields: [],
          mediaUploadStatus: 'idle',
          mediaUploadProgress: 0,
          mediaUploadMessage: '',
          failedMediaFiles: []
        };
        return validateCard(card);
      });

      if (requestId !== parseRequestIdRef.current || rawPromptsRef.current !== details) return;

      parsedPromptsRef.current = details;
      if (pendingSingleMediaRef.current.length > 0 && mapped.length > 0) {
        const firstCardId = mapped[0].id;
        const unassignedServerMedia = pendingSingleMediaRef.current;
        pendingSingleMediaRef.current = [];
        const activeDraftId = initialDraftId || batchDraft.ensureDraftId();
        void draftService.reassignCardMedia(activeDraftId, firstCardId);

        mapped[0].stagedMedia = unassignedServerMedia.map((m) => ({
          id: m.mediaId,
          previewUrl: m.previewUrl || '',
          roomTag: m.roomTag || 'LIVING_ROOM',
          isCover: Boolean(m.isCover),
          originalFilename: m.originalFilename || '',
          fileSizeBytes: m.fileSizeBytes || 0,
          contentType: m.contentType || 'image/jpeg',
          stagingStatus: 'staged'
        }));
        mapped[0].localPhotoPreviews = mapped[0].stagedMedia.map((m) => m.previewUrl).filter(Boolean);
      }
      setStagedCards(mapped);
      if (mapped.length > 0) {
        setActiveCardId(mapped[0].id);
        setMobileWorkspaceView('review');
      }
    } catch (err: unknown) {
      console.error('Failed to parse batch prompts:', err);
      showErrorDialog({
        title: 'Unable to review these properties',
        message: getErrorMessage(err, 'Please check the property details and try again.'),
        details: getErrorDetails(err)
      });
    } finally {
      if (requestId === parseRequestIdRef.current) {
        setIsParsing(false);
      }
    }
  }, [showErrorDialog, completedListings, stagedCards]);

  useEffect(() => {
    if (!initialDetails.trim() || importedDetailsRef.current === initialDetails) return;
    if (completedListings.length > 0 || stagedCards.some((c) => Boolean(c.publishedId))) return;

    importedDetailsRef.current = initialDetails;
    updateRawPrompts(initialDetails);
    inputSourceRef.current = 'TYPED';
    void parseBatchDetails(initialDetails);
  }, [initialDetails, parseBatchDetails, updateRawPrompts, completedListings, stagedCards]);

  const handleParseBatch = () => {
    // If draft has completed listings or partially published cards, merely switch to review workspace
    if (stagedCards.length > 0 && (completedListings.length > 0 || stagedCards.some((c) => Boolean(c.publishedId)))) {
      setMobileWorkspaceView('review');
      return;
    }
    // If staged cards already exist and raw prompts have not changed since last parse,
    // just switch to review workspace without re-parsing
    if (stagedCards.length > 0 && parsedPromptsRef.current === rawPrompts.trim()) {
      setMobileWorkspaceView('review');
      return;
    }
    void parseBatchDetails(rawPrompts);
  };

  const attachMediaToCard = useCallback(async (cardId: string, files: FileList | File[]) => {
    const fileArray = Array.from(files).filter(
      (file) => file.type.startsWith('image/') || file.type.startsWith('video/')
    );
    const compressedFiles: File[] = [];
    const previews: string[] = [];

    const preparationErrors: string[] = [];
    for (const file of fileArray) {
      try {
        const preparedFile = await prepareMediaForUpload(file);
        compressedFiles.push(preparedFile);
        previews.push(URL.createObjectURL(preparedFile));
      } catch (error) {
        preparationErrors.push(getErrorMessage(error, `${file.name} could not be prepared.`));
      }
    }

    const draftId = batchDraft.ensureDraftId();
    const targetCard = stagedCardsRef.current.find((c) => c.id === cardId);
    const currentMedia: StagedMediaItem[] = targetCard?.stagedMedia || [];
    const hasExistingCover = currentMedia.some((m) => m.isCover);
    const newItems: StagedMediaItem[] = [];

    for (let i = 0; i < compressedFiles.length; i++) {
      const file = compressedFiles[i];
      const isVideo = file.type.startsWith('video/');
      const smartTag = DEFAULT_SMART_TAG_SEQUENCE[(currentMedia.length + i) % DEFAULT_SMART_TAG_SEQUENCE.length];
      const willBeCover = !hasExistingCover && !isVideo && !newItems.some((item) => item.isCover);
      const mediaId = (file as any)?.draftMediaId || `dm-${Math.random().toString(36).substring(2, 10)}${Date.now().toString(36)}_${i}`;
      (file as any).draftMediaId = mediaId;

      newItems.push({
        id: mediaId,
        file,
        previewUrl: previews[i],
        roomTag: smartTag,
        isCover: willBeCover,
        originalFilename: file.name,
        fileSizeBytes: file.size,
        contentType: file.type,
        stagingStatus: 'staging'
      });
    }

    // 1. Pure state updater: synchronously register staging items
    setStagedCards((prev) =>
      prev.map((c) => {
        if (c.id !== cardId) return c;
        const existingMedia = c.stagedMedia || [];
        const combinedMedia = [...existingMedia, ...newItems];

        return {
          ...c,
          stagedMedia: combinedMedia,
          localPhotos: combinedMedia.map((m) => m.file).filter(Boolean) as File[],
          localPhotoPreviews: combinedMedia.map((m) => m.previewUrl),
          mediaUploadStatus: 'idle',
          mediaUploadProgress: 0,
          mediaUploadMessage: '',
          failedMediaFiles: []
        };
      })
    );

    // 2. Network side effects executed strictly OUTSIDE the React state updater (exactly once per item)
    for (const item of newItems) {
      const { id: mediaId, file, roomTag, isCover } = item;
      if (!file) continue;
      draftService.stageMedia(draftId, file, {
        cardId,
        roomTag,
        isCover,
        mediaId
      }).then((staged) => {
        if (staged?.mediaId) {
          (file as any).draftMediaId = staged.mediaId;
          setStagedCards((current) =>
            current.map((card) => {
              if (card.id !== cardId) return card;
              return {
                ...card,
                stagedMedia: (card.stagedMedia || []).map((m) =>
                  m.id === mediaId ? { ...m, stagingStatus: 'staged' } : m
                )
              };
            })
          );
        }
      }).catch((err) => {
        console.warn(`Draft media staging notice for card [${cardId}]:`, err);
        setStagedCards((current) =>
          current.map((card) => {
            if (card.id !== cardId) return card;
            return {
              ...card,
              stagedMedia: (card.stagedMedia || []).map((m) =>
                m.id === mediaId ? { ...m, stagingStatus: 'failed' } : m
              )
            };
          })
        );
      });
    }

    if (preparationErrors.length > 0) {
      showErrorDialog({
        title: compressedFiles.length > 0 ? 'Some media could not be added' : 'Media could not be added',
        message: compressedFiles.length > 0
          ? 'Supported files were prepared. Review the files that still need attention.'
          : 'Choose supported media within the displayed limits and try again.',
        details: preparationErrors.join(' • ')
      });
    }
  }, [batchDraft, showErrorDialog]);

  // Set cover photo for a specific staged card (only images can be cover, strictly per-card isolation)
  const handleSetCoverPhoto = (cardId: string, mediaId: string) => {
    setStagedCards((prev) =>
      prev.map((card) => {
        if (card.id !== cardId) return card;
        const updatedMedia = (card.stagedMedia || []).map((m) => {
          if (isMediaVideo(m)) return { ...m, isCover: false };
          return { ...m, isCover: m.id === mediaId };
        });
        return {
          ...card,
          stagedMedia: updatedMedia
        };
      })
    );
  };

  // Set room tag for a specific media item on a specific staged card
  const handleSetMediaRoomTag = (cardId: string, mediaId: string, roomTag: RoomTag) => {
    setStagedCards((prev) =>
      prev.map((card) => {
        if (card.id !== cardId) return card;
        const updatedMedia = (card.stagedMedia || []).map((m) =>
          m.id === mediaId ? { ...m, roomTag } : m
        );
        return {
          ...card,
          stagedMedia: updatedMedia
        };
      })
    );
  };

  // Remove media item from a card with URL revocation and automatic cover fallback
  const handleRemoveStagedMedia = (cardId: string, mediaId: string) => {
    const draftId = batchDraft.currentDraftId;
    if (draftId && mediaId) {
      draftService.deleteMedia(draftId, mediaId).catch((err) => {
        console.warn('Non-fatal media deletion notice from staging:', err);
      });
    }

    setStagedCards((prev) =>
      prev.map((c) => {
        if (c.id !== cardId) return c;
        const mediaList = c.stagedMedia || [];
        const target = mediaList.find((m) => m.id === mediaId);
        if (target?.previewUrl) {
          try {
            URL.revokeObjectURL(target.previewUrl);
          } catch (_) {}
        }
        const remaining = mediaList.filter((m) => m.id !== mediaId);
        let updated = remaining;
        // If removed item was cover, assign cover to first remaining eligible image
        if (target?.isCover && remaining.length > 0) {
          const firstImgIdx = remaining.findIndex((m) => !isMediaVideo(m));
          if (firstImgIdx !== -1) {
            updated = remaining.map((m, idx) => ({
              ...m,
              isCover: idx === firstImgIdx
            }));
          }
        }
        return {
          ...c,
          stagedMedia: updated,
          localPhotos: updated.map((m) => m.file).filter(Boolean) as File[],
          localPhotoPreviews: updated.map((m) => m.previewUrl),
          failedMediaFiles: c.failedMediaFiles.filter((file) => file !== target?.file),
          mediaUploadStatus: 'idle',
          mediaUploadProgress: 0,
          mediaUploadMessage: ''
        };
      })
    );
  };

  useEffect(() => {
    if (initialMediaAddedRef.current || initialMediaFiles.length === 0 || stagedCards.length === 0) return;

    initialMediaAddedRef.current = true;
    if (initialDraftId) {
      const firstCardId = stagedCards[0].id;
      void draftService.reassignCardMedia(initialDraftId, firstCardId);

      setStagedCards((current) =>
        current.map((c) => {
          if (c.id !== firstCardId) return c;
          const currentMedia: StagedMediaItem[] = c.stagedMedia || [];
          const existingIds = new Set(currentMedia.map((m) => m.id));
          const newItems: StagedMediaItem[] = [];

          for (let i = 0; i < initialMediaFiles.length; i++) {
            const file = initialMediaFiles[i];
            const isVideo = file.type.startsWith('video/');
            const smartTag = DEFAULT_SMART_TAG_SEQUENCE[(currentMedia.length + i) % DEFAULT_SMART_TAG_SEQUENCE.length];
            const willBeCover = !currentMedia.some((m) => m.isCover) && !isVideo && !newItems.some((m) => m.isCover);
            const mediaId = (file as any)?.draftMediaId || `dm-${Math.random().toString(36).substring(2, 10)}${Date.now().toString(36)}_${i}`;
            (file as any).draftMediaId = mediaId;

            if (!existingIds.has(mediaId)) {
              newItems.push({
                id: mediaId,
                file,
                previewUrl: URL.createObjectURL(file),
                roomTag: smartTag,
                isCover: willBeCover,
                originalFilename: file.name,
                fileSizeBytes: file.size,
                contentType: file.type,
                stagingStatus: 'staged'
              });
            }
          }

          const combined = [...currentMedia, ...newItems];
          return {
            ...c,
            stagedMedia: combined,
            localPhotos: combined.map((m) => m.file).filter(Boolean) as File[],
            localPhotoPreviews: combined.map((m) => m.previewUrl)
          };
        })
      );
      return;
    }

    void attachMediaToCard(stagedCards[0].id, initialMediaFiles);
  }, [attachMediaToCard, initialDraftId, initialMediaFiles, stagedCards]);

  // Direct Clipboard Pasting (Cmd+V / Ctrl+V) onto Active Card
  const handleCardPaste = useCallback(
    async (e: React.ClipboardEvent, cardId: string) => {
      const items = e.clipboardData.items;
      const imageFiles: File[] = [];

      for (let i = 0; i < items.length; i++) {
        if (items[i].type.indexOf('image') !== -1) {
          const file = items[i].getAsFile();
          if (file) imageFiles.push(file);
        }
      }

      if (imageFiles.length > 0) {
        e.preventDefault();
        await attachMediaToCard(cardId, imageFiles);
      }
    },
    [attachMediaToCard]
  );

  // Remove photo by index (delegates to stable handleRemoveStagedMedia)
  const handleRemovePhoto = (cardId: string, photoIdx: number) => {
    const card = stagedCards.find((c) => c.id === cardId);
    if (card && card.stagedMedia && card.stagedMedia[photoIdx]) {
      handleRemoveStagedMedia(cardId, card.stagedMedia[photoIdx].id);
    }
  };

  // Card Field Inline Quick-Edit
  const handleUpdateField = (cardId: string, field: keyof StagedProperty, value: any) => {
    setStagedCards((prev) =>
      prev.map((c) => {
        if (c.id !== cardId) return c;
        const conflictField = getUnresolvedConflictField(field);
        const updated = {
          ...c,
          [field]: value,
          conflicts: conflictField
            ? c.conflicts.filter((conflict) => !conflict.startsWith(conflictField))
            : c.conflicts
        } as StagedProperty;
        return validateCard(updated);
      })
    );
  };

  const handleAvailabilityChange = (
    cardId: string,
    availabilityStatus: AvailabilityStatusValue,
    availableFrom: string
  ) => {
    setStagedCards((previousCards) => previousCards.map((card) => {
      if (card.id !== cardId) return card;

      const normalizedDate = availabilityStatus === 'AVAILABLE_FROM_DATE' ? availableFrom : '';
      const possessionDate = availabilityStatus === 'READY_NOW'
        ? 'Ready To Move'
        : formatAvailabilityDate(normalizedDate);
      const updated = {
        ...card,
        availabilityStatus,
        availableFrom: normalizedDate,
        possessionDate,
        conflicts: card.conflicts.filter((conflict) => !conflict.startsWith('Possession date'))
      };
      return validateCard(updated);
    }));
  };

  const handleFloorChange = (cardId: string, val: string) => {
    const trimmed = val.trim().toLowerCase();
    if (trimmed === '') {
      handleUpdateField(cardId, 'floor', null);
    } else if (trimmed === 'ground' || trimmed === 'g' || trimmed === '0') {
      handleUpdateField(cardId, 'floor', 0);
    } else {
      const num = parseInt(trimmed, 10);
      handleUpdateField(cardId, 'floor', Number.isNaN(num) ? null : num);
    }
  };

  const handleTotalFloorsChange = (cardId: string, val: string) => {
    const trimmed = val.trim();
    if (trimmed === '') {
      handleUpdateField(cardId, 'totalFloors', null);
    } else {
      const num = parseInt(trimmed, 10);
      handleUpdateField(cardId, 'totalFloors', Number.isNaN(num) ? null : num);
    }
  };

  const handleConfirmCard = (cardId: string, isConfirmed: boolean) => {
    setStagedCards((prev) => prev.map((card) => {
      if (card.id !== cardId) return card;
      return validateCard({ ...card, isConfirmed });
    }));
  };

  const uploadMediaFilesForCard = async (
    card: StagedProperty,
    propertyId: number,
    mediaItemsToUpload?: StagedMediaItem[]
  ): Promise<{ failedFiles: File[]; errors: string[] }> => {
    const rawItems = mediaItemsToUpload || card.stagedMedia || [];
    const totalFiles = rawItems.length;
    if (totalFiles === 0) return { failedFiles: [], errors: [] };

    // Deduplicate against existing assets on the property
    let existingReqIds = new Set<string>();
    try {
      const existing = await propertyService.fetchTaggedMedia(propertyId);
      if (Array.isArray(existing)) {
        existing.forEach(a => { if (a.uploadRequestId) existingReqIds.add(a.uploadRequestId); });
      }
    } catch {}

    const items: StagedMediaItem[] = [];
    let completedCount = 0;
    rawItems.forEach(item => {
      const draftMediaId = item.id || (item.file as any)?.draftMediaId;
      const reqId = createStableUploadRequestId(propertyId, item.file, draftMediaId);
      if (existingReqIds.has(reqId)) {
        completedCount += 1;
      } else {
        items.push(item);
      }
    });

    if (items.length === 0) {
      setStagedCards((current) => current.map((item) => item.id === card.id
        ? {
            ...item,
            mediaUploadStatus: 'complete',
            mediaUploadProgress: 100,
            mediaUploadMessage: `${totalFiles} ${totalFiles === 1 ? 'file was' : 'files were'} verified successfully.`,
            failedMediaFiles: []
          }
        : item));
      return { failedFiles: [], errors: [] };
    }

    const failedFiles: File[] = [];
    const errors: string[] = [];

    setStagedCards((current) => current.map((item) => item.id === card.id
      ? {
          ...item,
          mediaUploadStatus: 'preparing',
          mediaUploadProgress: Math.round((completedCount / totalFiles) * 100),
          mediaUploadMessage: `Preparing ${items.length} ${items.length === 1 ? 'file' : 'files'}…`,
          failedMediaFiles: []
        }
      : item));

    const CONCURRENCY_LIMIT = 3;
    let nextIndex = 0;

    const abortController = new AbortController();
    const handleOffline = () => {
      abortController.abort();
    };
    window.addEventListener('offline', handleOffline);

    const worker = async () => {
      while (nextIndex < items.length) {
        if (abortController.signal.aborted || (typeof navigator !== 'undefined' && !navigator.onLine)) {
          const unstarted = items[nextIndex];
          if (unstarted) {
            if (unstarted.file) failedFiles.push(unstarted.file);
            const fname = getMediaFilename(unstarted);
            errors.push(`${fname} was interrupted because the network disconnected.`);
          }
          nextIndex += 1;
          continue;
        }

        const fileIndex = nextIndex;
        nextIndex += 1;
        const mediaItem = items[fileIndex];
        let file = mediaItem.file;

        if (!file && mediaItem.id) {
          const draftId = batchDraft.currentDraftId;
          if (draftId) {
            try {
              const streamUrl = draftService.getMediaStreamUrl(draftId, mediaItem.id);
              const res = await fetch(streamUrl, {
                headers: draftService.getAuthHeaders()
              });
              if (res.ok) {
                const blob = await res.blob();
                file = new File([blob], getMediaFilename(mediaItem), {
                  type: mediaItem.contentType || (isMediaVideo(mediaItem) ? 'video/mp4' : 'image/jpeg')
                });
                (file as any).draftMediaId = mediaItem.id;
                mediaItem.file = file;
              }
            } catch (err) {
              console.warn('On-demand stream restore notice:', err);
            }
          }
        }

        if (!file) {
          const fname = getMediaFilename(mediaItem);
          failedFiles.push(new File([], fname));
          errors.push(`Could not read media file for ${fname}`);
          continue;
        }

        try {
          const draftMediaId = mediaItem.id || (file as any)?.draftMediaId;
          const fname = getMediaFilename(mediaItem);
          await propertyService.uploadTaggedMedia(propertyId, file, {
            roomTag: mediaItem.roomTag,
            mediaType: isMediaVideo(mediaItem) ? 'VIDEO_WALKTHROUGH' : 'IMAGE',
            caption: `${card.title} - ${mediaItem.roomTag.replace('_', ' ')}`,
            isPrimaryCover: mediaItem.isCover,
            sector: card.sector,
            priceTag: card.rentVal,
            vastuFacing: card.vastuFacing,
            draftMediaId
          }, {
            signal: abortController.signal,
            onProgress: (progress) => {
              const overallProgress = Math.round(
                ((completedCount + (progress.percent / 100)) / totalFiles) * 100
              );
              const status = progress.stage === 'retrying'
                ? 'retrying'
                : progress.stage === 'preparing'
                  ? 'preparing'
                  : 'uploading';
              const message = progress.stage === 'retrying'
                ? `Connection interrupted. Retrying file (${fname})…`
                : `Uploading file (${completedCount + 1} of ${totalFiles})…`;
              setStagedCards((current) => current.map((item) => item.id === card.id
                ? {
                    ...item,
                    mediaUploadStatus: status,
                    mediaUploadProgress: overallProgress,
                    mediaUploadMessage: message
                  }
                : item));
            }
          });
        } catch (error) {
          failedFiles.push(file);
          errors.push(getErrorMessage(error, `${getMediaFilename(mediaItem)} could not be uploaded.`));
        } finally {
          completedCount += 1;
          const overallProgress = Math.round((completedCount / totalFiles) * 100);
          setStagedCards((current) => current.map((item) => item.id === card.id
            ? {
                ...item,
                mediaUploadProgress: overallProgress,
                mediaUploadMessage: `Uploaded ${completedCount} of ${totalFiles} file(s)…`
              }
            : item));
        }
      }
    };

    try {
      const workerCount = Math.min(CONCURRENCY_LIMIT, items.length);
      const workers: Promise<void>[] = [];
      for (let w = 0; w < workerCount; w += 1) {
        workers.push(worker());
      }
      await Promise.all(workers);
    } finally {
      window.removeEventListener('offline', handleOffline);
    }

    setStagedCards((current) => current.map((item) => item.id === card.id
      ? {
          ...item,
          mediaUploadStatus: failedFiles.length > 0 ? 'failed' : 'complete',
          mediaUploadProgress: failedFiles.length > 0
            ? Math.round(((totalFiles - failedFiles.length) / totalFiles) * 100)
            : 100,
          mediaUploadMessage: failedFiles.length > 0
            ? `${failedFiles.length} ${failedFiles.length === 1 ? 'file needs' : 'files need'} another attempt.`
            : `${totalFiles} ${totalFiles === 1 ? 'file was' : 'files were'} uploaded successfully.`,
          failedMediaFiles: failedFiles
        }
      : item));

    return { failedFiles, errors };
  };

  const handleRetryCardMedia = async (cardId: string) => {
    const card = stagedCards.find((item) => item.id === cardId);
    if (!card?.publishedId || card.failedMediaFiles.length === 0) return;

    const itemsToRetry = (card.stagedMedia || []).filter((m) =>
      Boolean(m.file && card.failedMediaFiles.includes(m.file))
    );
    if (itemsToRetry.length === 0) return;

    setIsPublishing(true);
    batchDraft.suspendAutosave();
    try {
      const result = await uploadMediaFilesForCard(card, card.publishedId, itemsToRetry);
      if (result.failedFiles.length > 0) {
        showErrorDialog({
          title: 'Some media still needs attention',
          message: 'The property remains published. Keep this screen open and retry when the connection is stable.',
          details: result.errors.join(' • ')
        });
      }
    } finally {
      setIsPublishing(false);
      batchDraft.resumeAutosave();
    }
  };

  // Batch Publish All Staged Properties
  const handlePublishAll = async () => {
    const validCards = stagedCards.filter((c) => c.isValid);
    if (validCards.length === 0) {
      showErrorDialog({
        title: 'Review the property details',
        message: 'Complete the required details and confirm at least one listing before publishing.'
      });
      return;
    }

    setIsPublishing(true);
    batchDraft.suspendAutosave();

    try {
      const draftId = batchDraft.ensureDraftId();

      const cardsNeedingListing = validCards.filter((c) => !c.publishedId);
      const publishedIdsByCardId = new Map<string, number>();

      for (const card of validCards) {
        if (card.publishedId) {
          publishedIdsByCardId.set(card.id, Number(card.publishedId));
        }
      }

      let res: any = null;
      if (cardsNeedingListing.length > 0) {
        const payloadListings = cardsNeedingListing.map((c) => ({
          draftId,
          cardId: c.id,
          learningExampleId: c.learningExampleId,
          promptIndex: c.promptIndex,
          rawPrompt: c.rawPrompt,
          title: c.title,
          description: c.description,
          bhk: c.bhk,
          type: c.type,
          status: c.status,
          sector: c.sector,
          city: c.city,
          colony: c.colony,
          address: c.address,
          state: c.state,
          pincode: c.pincode,
          landmark: c.landmark,
          rentAmount: c.rentAmount,
          rentVal: c.rentVal,
          depositVal: c.depositVal,
          brokerageVal: c.brokerageVal,
          brokerageDays: c.brokerageDays,
          areaSqFt: c.areaSqFt,
          bathrooms: c.bathrooms,
          possessionDate: c.possessionDate,
          availabilityStatus: c.availabilityStatus,
          availableFrom: c.availableFrom,
          ownerName: c.ownerName,
          ownerPhone: c.ownerPhone,
          furnishingStatus: c.furnishingStatus,
          vastuFacing: c.vastuFacing,
          amenities: c.amenities,
          floor: typeof c.floor === 'number' ? c.floor : null,
          totalFloors: typeof c.totalFloors === 'number' ? c.totalFloors : null,
          preferredTenants: Array.isArray(c.preferredTenants) ? c.preferredTenants : [],
          mediaUrls: c.mediaUrls,
          adminVerified: c.isConfirmed
        }));

        res = await propertyService.createBatchProperties(payloadListings, draftId);

        const createdResults = Array.isArray(res.createdListings)
          ? res.createdListings.filter((item: any) =>
              Number.isInteger(item?.requestIndex)
              && item.requestIndex >= 0
              && item.requestIndex < cardsNeedingListing.length
              && Number.isFinite(Number(item?.id)))
          : [];
        const uniqueRequestIndexes = new Set(
          createdResults.map((item: any) => item.requestIndex)
        );
        if (createdResults.length !== Number(res.successCount || 0)
            || uniqueRequestIndexes.size !== createdResults.length) {
          throw new Error('The publish response could not be matched safely to the submitted properties.');
        }

        for (const created of createdResults) {
          const card = cardsNeedingListing[created.requestIndex];
          if (card) {
            publishedIdsByCardId.set(card.id, Number(created.id));
          }
        }
      }

      // Upload media only after its listing exists, preserving the selected images and videos.
      const mediaUploadErrors: string[] = [];
      for (let i = 0; i < validCards.length; i++) {
        const card = validCards[i];
        const propId = publishedIdsByCardId.get(card.id);
        if (!propId) continue;

        // Per-property publishing state isolation: mark this card specifically as publishing
        setPublishingCardId(card.id);
        setActiveCardId(card.id);
        setStagedCards((current) =>
          current.map((c) =>
            c.id === card.id ? { ...c, publishedId: propId, isConfirmed: true } : c
          )
        );

        if (card && card.stagedMedia && card.stagedMedia.length > 0) {
          const result = await uploadMediaFilesForCard(card, propId, card.stagedMedia);
          if (result.errors.length > 0) {
            mediaUploadErrors.push(`Property ${card.promptIndex}: ${result.errors.join(', ')}`);
          }
        }

        // Once this property finishes, auto-advance active view to next property immediately without blocking sequential upload
        if (i + 1 < validCards.length) {
          const nextCard = validCards[i + 1];
          if (nextCard) {
            setActiveCardId(nextCard.id);
          }
        }
      }
      setPublishingCardId(null);

      // Distinguish cards whose listing and media succeeded completely from those needing attention
      const successfulCardIds: string[] = [];
      const newlyCompleted: Array<{ cardId: string; listingId: number; title: string }> = [];

      for (const [cardId, listingId] of publishedIdsByCardId.entries()) {
        const card = validCards.find((c) => c.id === cardId);
        const cardHadMediaErrors = mediaUploadErrors.some((err) => err.startsWith(`Property ${card?.promptIndex}:`));
        if (!cardHadMediaErrors) {
          successfulCardIds.push(cardId);
          newlyCompleted.push({
            cardId,
            listingId,
            title: card?.title || `Property #${listingId}`
          });
        }
      }

      // If no cards were completely successful (e.g. all encountered media errors or network interrupted)
      if (successfulCardIds.length === 0) {
        if (mediaUploadErrors.length > 0) {
          showErrorDialog({
            title: 'Publishing interrupted',
            message: 'Some media could not be uploaded because the connection was interrupted. The listing was created and your progress is saved. Click Resume when connection is restored.',
            details: mediaUploadErrors.join(' • ')
          });
        }
        return;
      }

      // Reconcile with server to get authoritative remaining state.
      // Cancel any pending debounced autosave first — state mutations from the publish loop
      // (setStagedCards, setActiveCardId) schedule an autosave that would otherwise race the
      // reconcile-batch backend transaction and cause a StaleObjectStateException.
      batchDraft.cancelAutosave();

      let remainingDraft: any = null;
      let reconcileError: 'network' | 'server' | null = null;
      try {
        remainingDraft = await batchDraft.onBatchPublished(successfulCardIds, newlyCompleted);
      } catch (err: unknown) {
        // Distinguish real network loss (TypeError: Failed to fetch / NetworkError / offline) from
        // server-side errors (HTTP 500, 409 StaleObjectStateException, etc.).
        const isNetworkLoss =
          (typeof navigator !== 'undefined' && !navigator.onLine) ||
          (err instanceof TypeError && /fetch|network|load/i.test((err as TypeError).message)) ||
          (err as any)?.status === 0;
        reconcileError = isNetworkLoss ? 'network' : 'server';
      }

      if (reconcileError === 'network') {
        // Network was interrupted before reconciliation could be confirmed by the server
        // DO NOT show green success notification!
        showErrorDialog({
          title: 'Publishing interrupted',
          message: 'The network connection was interrupted before publication could be verified. Use Resume to reconcile your draft.'
        });
        return;
      }

      if (reconcileError === 'server') {
        // Backend acknowledged the publish but failed to update the draft status.
        // The listings were created successfully — the draft just needs reconciliation.
        showErrorDialog({
          title: 'Properties published',
          message: 'Your properties were published successfully. There was an issue updating the draft record. Use Resume to restore your draft to the correct state.'
        });
        return;
      }

      // Update completed listings in state
      const updatedCompleted = [...completedListings];
      newlyCompleted.forEach((nc) => {
        if (!updatedCompleted.some((e) => e.cardId === nc.cardId || e.listingId === nc.listingId)) {
          updatedCompleted.push(nc);
        }
      });
      setCompletedListings(updatedCompleted);

      // Revoke object URLs ONLY for the completely successful cards
      stagedCards.forEach((card) => {
        if (successfulCardIds.includes(card.id)) {
          (card.stagedMedia || []).forEach((item) => {
            try { URL.revokeObjectURL(item.previewUrl); } catch (_) {}
          });
        }
      });

      // Update stagedCards removing only the confirmed published cards
      const remainingCardsAfterPublish = stagedCards.filter((c) => !successfulCardIds.includes(c.id));
      setStagedCards(remainingCardsAfterPublish);
      if (remainingCardsAfterPublish.length > 0) {
        setActiveCardId(remainingCardsAfterPublish[0].id);
      }

      window.dispatchEvent(new Event('pathome_property_published'));

      const publishedThisRun = successfulCardIds.length;

      // Authoritative completion check from server
      const isServerBatchComplete = remainingDraft === null || (remainingDraft && remainingDraft.itemCount === 0);

      const failedCreateCount = res ? Number(res.failedCount || 0) : 0;
      if (isServerBatchComplete && mediaUploadErrors.length === 0 && failedCreateCount === 0) {
        // FULL BATCH SUCCESS (confirmed by server tombstone)
        await batchDraft.onPublishSuccess();
        const totalPublishedCount = updatedCompleted.length;
        onSuccess(totalPublishedCount);

        setStagedCards([]);
        setCompletedListings([]);
        updateRawPrompts('');
        setDetectedCount(0);
        rawPromptsRef.current = '';
        parsedPromptsRef.current = '';
        setMobileWorkspaceView('descriptions');
        onClose();
        return;
      }

      // PARTIAL SUCCESS (confirmed by server remaining count)
      const serverRemainingCount = remainingDraft != null ? remainingDraft.itemCount : remainingCardsAfterPublish.length;
      if (publishedThisRun > 0 && onPartialSuccess) {
        onPartialSuccess(publishedThisRun, serverRemainingCount);
      }

      if (mediaUploadErrors.length > 0) {
        showErrorDialog({
          title: 'Property published, but some media needs attention',
          message: 'The listing details were published. Use Retry failed media below; the property will not be created again.',
          details: mediaUploadErrors.join(' • ')
        });
      }

      if (res.failedCount > 0) {
        showErrorDialog({
          title: 'Some properties still need attention',
          message: `${publishedThisRun} ${publishedThisRun === 1 ? 'property was' : 'properties were'} published. Review the remaining entries and try again.`,
          details: Array.isArray(res.failedListings)
            ? res.failedListings.map((item: any) => item.error).filter(Boolean).join(' • ')
            : undefined
        });
      }
    } catch (err: unknown) {
      console.error('Batch publish failed:', err);
      const isNetworkLoss =
        (typeof navigator !== 'undefined' && !navigator.onLine) ||
        (err instanceof TypeError && /fetch|network|load/i.test((err as TypeError).message)) ||
        (err as any)?.status === 0;

      showErrorDialog({
        title: isNetworkLoss ? 'Publishing interrupted' : 'Unable to publish the selected properties',
        message: isNetworkLoss
          ? 'The network connection was interrupted. Your progress is saved. Click Resume when connection is restored.'
          : getErrorMessage(err, 'Please try again after reviewing the property details.'),
        details: getErrorDetails(err)
      });
    } finally {
      setPublishingCardId(null);
      setIsPublishing(false);
      batchDraft.resumeAutosave();
    }
  };

  if (!isOpen) return null;

  const totalOriginalCount = completedListings.length + stagedCards.length;
  const readyToPublishCount = stagedCards.filter((card) => card.isValid).length;
  const totalCardsCount = stagedCards.length;
  const unconfirmedCount = stagedCards.filter((card) => !card.isValid).length;

  const getPublishCtaText = () => {
    if (completedListings.length > 0) {
      if (readyToPublishCount === 0) {
        return totalCardsCount === 1 ? 'Confirm remaining property' : 'Confirm remaining properties';
      }
      if (readyToPublishCount === totalCardsCount) {
        return totalCardsCount === 1 ? 'Publish remaining property' : `Publish all ${totalCardsCount} remaining properties`;
      }
      return `Publish ${readyToPublishCount} of ${totalCardsCount} remaining properties`;
    }

    if (readyToPublishCount === 0) {
      return 'Confirm properties to publish';
    }
    if (totalCardsCount === 1) {
      return 'Publish 1 property';
    }
    if (readyToPublishCount === totalCardsCount) {
      return `Publish all ${totalCardsCount} properties`;
    }
    return `Publish ${readyToPublishCount} of ${totalCardsCount} confirmed properties`;
  };

  const getMobileCtaText = () => {
    if (completedListings.length > 0) {
      if (readyToPublishCount === 0) {
        return totalCardsCount === 1 ? 'Confirm property' : 'Confirm properties';
      }
      if (readyToPublishCount === totalCardsCount) {
        return totalCardsCount === 1 ? 'Publish remaining' : `Publish all ${totalCardsCount} remaining`;
      }
      return `Publish ${readyToPublishCount} of ${totalCardsCount} remaining`;
    }

    if (readyToPublishCount === 0) {
      return 'Confirm properties';
    }
    if (totalCardsCount === 1) {
      return 'Publish 1 property';
    }
    if (readyToPublishCount === totalCardsCount) {
      return `Publish all ${totalCardsCount} properties`;
    }
    return `Publish ${readyToPublishCount} of ${totalCardsCount} confirmed`;
  };

  return (
    <AnimatePresence>
      <div className={embedded ? 'relative w-full' : 'fixed inset-0 z-50 flex items-center justify-center p-0 sm:p-6 overflow-y-auto'}>
        {!embedded && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={onClose}
            className="fixed inset-0 bg-slate-950 sm:bg-slate-950/90 backdrop-blur-md transition-all"
          />
        )}

        {/* 3D Spring Tilt Pop-in Modal Window */}
        <motion.div
          initial={embedded ? { opacity: 0, y: 12 } : { opacity: 0, scale: 0.85, rotateX: 14 }}
          animate={{ opacity: 1, scale: 1, rotateX: 0 }}
          exit={embedded ? { opacity: 0, y: 12 } : { opacity: 0, scale: 0.85, rotateX: 14 }}
          transition={{ type: 'spring', stiffness: 480, damping: 25 }}
          className={`relative w-full flex flex-col bg-slate-900 border-0 sm:border border-slate-700/80 rounded-none sm:rounded-3xl shadow-2xl overflow-hidden text-slate-100 ${
            embedded ? 'max-h-none' : 'max-w-7xl h-[100dvh] sm:h-auto sm:max-h-[92vh] z-10'
          }`}
        >
          {/* Header Bar */}
          <div className={`relative flex items-start justify-between gap-3 border-b border-slate-800 bg-slate-950 px-3 sm:px-6 ${
            embedded ? 'py-3 sm:py-4' : 'pt-[calc(env(safe-area-inset-top,0px)+0.75rem)] pb-3 sm:py-4'
          }`}>
            <div className="flex min-w-0 items-start gap-3 pr-12">
              <div className="hidden w-10 h-10 shrink-0 rounded-2xl bg-gradient-to-tr from-amber-500 to-orange-400 items-center justify-center text-slate-950 shadow-lg shadow-orange-500/20 min-[400px]:flex">
                <Sparkles className="w-5 h-5 font-black" />
              </div>
              <div className="min-w-0">
                <h2 className="flex flex-wrap items-center gap-2 text-base font-black tracking-tight text-white sm:text-lg">
                  Review property details
                  <span className="text-[10px] font-bold uppercase tracking-wider px-2.5 py-0.5 rounded-full bg-amber-500/20 text-amber-300 border border-amber-500/30">
                    Admin review
                  </span>
                </h2>
                <p className="hidden text-xs text-slate-400 min-[420px]:block">
                  Add one or several properties, then confirm the complete details for each before publishing.
                </p>
              </div>
            </div>

            <button
              onClick={onClose}
              aria-label="Back to property details"
              title="Back to property details"
              className={`absolute right-3 flex h-11 w-11 shrink-0 items-center justify-center rounded-xl text-slate-400 hover:text-white hover:bg-slate-800/80 transition-colors sm:right-5 ${
                embedded ? 'top-3' : 'top-[calc(env(safe-area-inset-top,0px)+0.75rem)] sm:top-4'
              }`}
            >
              <X className="w-5 h-5" />
            </button>
          </div>

          {/* Batch Draft Management & Autosave Status Bar */}
          <div className="px-3 sm:px-6 pt-3">
            <DraftManagementBar
              compact={true}
              currentDraftId={batchDraft.currentDraftId}
              draftType="BATCH"
              autosaveStatus={batchDraft.autosaveStatus}
              lastSavedAt={batchDraft.lastSavedAt}
              conflictMessage={batchDraft.conflictMessage}
              drafts={batchDraft.draftsList}
              isLoadingDrafts={batchDraft.isLoadingDrafts}
              onSelectDraft={async (id) => {
                const selected = batchDraft.draftsList.find((d) => d.draftId === id);
                if (selected && selected.draftType === 'SINGLE') {
                  if (onSwitchToSingleDraft) {
                    onSwitchToSingleDraft(id);
                  } else {
                    onClose();
                  }
                } else {
                  await batchDraft.loadDraft(id);
                }
              }}
              onStartNewDraft={() => void batchDraft.startNewDraft()}
              onDiscardDraft={(id) => batchDraft.discardDraft(id)}
              onDiscardDrafts={(ids) => batchDraft.discardMultipleDrafts(ids)}
              onResolveConflictKeepLocal={batchDraft.resolveConflictKeepLocal}
              onResolveConflictReloadServer={batchDraft.resolveConflictReloadServer}
            />
          </div>

          {/* Main Content Split Screen */}
          <div className={`grid flex-1 grid-cols-1 gap-3 px-2 py-3 sm:gap-6 sm:p-6 lg:grid-cols-12 ${
            embedded
              ? mobileWorkspaceView === 'review' && stagedCards.length > 0
                ? 'overflow-visible pb-28 lg:pb-6'
                : 'overflow-visible'
              : 'overflow-y-auto'
          }`}>
            {stagedCards.length > 0 && (
              <div className="grid grid-cols-2 gap-1 rounded-2xl border border-slate-800 bg-slate-950/80 p-1 lg:hidden">
                <button
                  type="button"
                  onClick={() => setMobileWorkspaceView('descriptions')}
                  aria-pressed={mobileWorkspaceView === 'descriptions'}
                  className={`min-h-11 rounded-xl px-3 text-xs font-bold transition-colors ${
                    mobileWorkspaceView === 'descriptions'
                      ? 'bg-slate-800 text-white shadow-sm'
                      : 'text-slate-400 hover:text-slate-200'
                  }`}
                >
                  Edit descriptions
                </button>
                <button
                  type="button"
                  onClick={() => setMobileWorkspaceView('review')}
                  aria-pressed={mobileWorkspaceView === 'review'}
                  className={`min-h-11 rounded-xl px-3 text-xs font-bold transition-colors ${
                    mobileWorkspaceView === 'review'
                      ? 'bg-amber-400 text-slate-950 shadow-sm'
                      : 'text-slate-400 hover:text-slate-200'
                  }`}
                >
                  Review {stagedCards.length}
                </button>
              </div>
            )}

            {/* Left Panel: Voice & Text Prompt Input (5 Cols) */}
            <div className={`${mobileWorkspaceView === 'review' && stagedCards.length > 0 ? 'hidden lg:flex' : 'flex'} lg:col-span-5 flex-col gap-4`}>
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="flex min-w-0 items-center gap-2.5">
                  <span className="truncate text-sm font-bold text-slate-200">
                    Property descriptions
                  </span>
                  <span className="inline-flex h-7 shrink-0 items-center rounded-full border border-indigo-500/30 bg-indigo-500/10 px-2.5 text-[11px] font-bold text-indigo-300">
                    {detectedCount} {detectedCount === 1 ? 'property' : 'properties'}
                  </span>
                </div>

                {speechSupported && (
                  <motion.button
                    whileHover={{ y: -1, scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                    onClick={toggleSpeech}
                    type="button"
                    className={`inline-flex h-11 shrink-0 items-center gap-2 rounded-xl px-3 text-xs font-bold transition-all ${
                      isListening
                        ? 'bg-rose-500 text-white shadow-md shadow-rose-500/30'
                        : 'border border-slate-700 bg-slate-800/80 text-slate-200 hover:border-amber-400/40 hover:bg-slate-800 hover:text-white'
                    }`}
                  >
                    {isListening ? <MicOff className="h-3.5 w-3.5" /> : <Mic className="h-3.5 w-3.5 text-amber-400" />}
                    {isListening ? 'Stop dictation' : 'Dictate details'}
                  </motion.button>
                )}
              </div>

              <AnimatePresence initial={false}>
                {isDictationChoiceOpen && (
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
                        onClick={() => setIsDictationChoiceOpen(false)}
                        aria-label="Cancel dictation"
                        className="rounded-lg p-1 text-slate-400 transition-colors hover:bg-slate-800 hover:text-white"
                      >
                        <X className="h-4 w-4" />
                      </button>
                    </div>
                    <div className="mt-3 flex flex-wrap gap-2">
                      <button
                        type="button"
                        onClick={() => startDictation('replace')}
                        className="rounded-xl bg-amber-400 px-3 py-2 text-xs font-bold text-slate-950 transition-colors hover:bg-amber-300"
                      >
                        Start a new voice draft
                      </button>
                      <button
                        type="button"
                        onClick={() => startDictation('append')}
                        className="rounded-xl border border-slate-700 bg-slate-800 px-3 py-2 text-xs font-bold text-slate-200 transition-colors hover:border-slate-600 hover:text-white"
                      >
                        Add to current draft
                      </button>
                    </div>
                  </motion.div>
                )}
              </AnimatePresence>

              <div className="relative flex-1 min-h-[220px]">
                <textarea
                  ref={textareaRef}
                  value={rawPrompts}
                  onChange={(e) => handleTypedPromptChange(e.target.value)}
                  placeholder={`Type, paste, or dictate property details here.\n\nTo add another property, write “next property” on a new line.\n\nExample:\n2 BHK in Vijay Nagar, rent 18,000, owner 98260 12345\n\nnext property\n3 BHK in Palasia, rent 35,000, owner 98260 54321`}
                  className="w-full h-full min-h-[220px] p-4 text-[16px] sm:text-xs font-mono bg-slate-950/80 border border-slate-700/80 rounded-2xl text-slate-100 placeholder-slate-500 focus:outline-none focus:border-amber-500/80 focus:ring-2 focus:ring-amber-500/20 transition-all resize-none shadow-inner sm:min-h-[260px]"
                />
              </div>

              <div className="flex justify-stretch pt-1 sm:justify-end">
                <motion.button
                  whileHover={{ scale: 1.03 }}
                  whileTap={{ scale: 0.97 }}
                  onClick={handleParseBatch}
                  disabled={isParsing || !rawPrompts.trim()}
                  className="flex min-h-11 w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-amber-500 via-orange-500 to-amber-600 px-5 py-2.5 text-xs font-black text-slate-950 shadow-lg shadow-orange-500/20 transition-all hover:brightness-110 disabled:opacity-50 sm:w-auto"
                >
                  {isParsing ? (
                    <span>Extracting property details…</span>
                  ) : (
                    <>
                      <Sparkles className="w-4 h-4" />
                      <span>Review property details</span>
                    </>
                  )}
                </motion.button>
              </div>
            </div>

            {/* Right Panel: Staging Cards & Filmstrip (7 Cols) */}
            <div className={`${mobileWorkspaceView === 'descriptions' ? 'hidden lg:flex' : 'flex'} lg:col-span-7 flex-col gap-4`}>
              {stagedCards.length === 0 ? (
                <div className="h-full min-h-[300px] sm:min-h-[360px] flex flex-col items-center justify-center border border-dashed border-slate-800 rounded-2xl p-4 sm:p-8 text-center bg-slate-950/40">
                  <div className="w-14 h-14 rounded-3xl bg-slate-800/80 flex items-center justify-center text-slate-400 mb-3">
                    <Building2 className="w-7 h-7" />
                  </div>
                  <h3 className="text-sm font-bold text-slate-200">
                    {rawPrompts.trim() ? 'Review updated details' : 'No listings to review yet'}
                  </h3>
                  <p className="text-xs text-slate-400 max-w-sm mt-1">
                    {rawPrompts.trim()
                      ? <>The descriptions changed. Select <span className="text-amber-400 font-bold">“Review property details”</span> to create a fresh, separate review for each property.</>
                      : <>Add property details or use <span className="text-amber-400 font-bold">Dictate details</span>, then select <span className="text-amber-400 font-bold">“Review property details”</span>.</>}
                  </p>
                </div>
              ) : (
                <div className="flex flex-col gap-4">
                  {/* Magnetic Filmstrip Overview Ribbon */}
                  <div className="rounded-2xl border border-slate-800 bg-slate-950/90 p-3 shadow-md">
                    <div className="flex flex-col gap-1 text-xs font-bold text-slate-300 mb-2 min-[480px]:flex-row min-[480px]:items-center min-[480px]:justify-between">
                      <span className="flex items-center gap-1.5">
                        <Scissors className="w-3.5 h-3.5 text-amber-400" />
                        Properties to review
                      </span>
                      <span className="text-[11px] text-slate-400">
                        {stagedCards.length} {stagedCards.length === 1 ? 'property' : 'properties'} awaiting review
                      </span>
                    </div>

                    <div className="no-scrollbar flex touch-pan-x items-center gap-1.5 overflow-x-auto pb-1">
                      {stagedCards.map((card, idx) => {
                        const palette = COLOR_PALETTES[idx % COLOR_PALETTES.length];
                        const mediaCount = card.localPhotos.length + card.mediaUrls.length;
                        return (
                          <button
                            key={card.id}
                            onClick={() => setActiveCardId(card.id)}
                            className={`px-3 py-1.5 rounded-xl text-xs font-bold border transition-all flex items-center gap-2 whitespace-nowrap ${
                              activeCardId === card.id
                                ? `${palette.bg} ${palette.border} ${palette.text} shadow-md`
                                : 'bg-slate-900/60 border-slate-800 text-slate-400 hover:text-slate-200'
                            }`}
                          >
                            <span className="font-mono">#{idx + 1}</span>
                            <span>{getPropertyTabLabel(card)}</span>
                            {card.publishedId ? (
                              <span className="px-1.5 py-0.5 rounded-md bg-amber-950/80 text-[10px] text-amber-300 font-mono flex items-center gap-1 border border-amber-600/40">
                                <Clock className="w-2.5 h-2.5" /> Media Pending
                              </span>
                            ) : publishingCardId === card.id ? (
                              <span className="px-1.5 py-0.5 rounded-md bg-cyan-950/80 text-[10px] text-cyan-300 font-mono flex items-center gap-1 border border-cyan-600/40">
                                <RefreshCw className="w-2.5 h-2.5 animate-spin" /> Publishing
                              </span>
                            ) : (
                              <span className="px-1.5 py-0.2 rounded-md bg-slate-800 text-[10px] text-slate-300 font-mono">
                                {mediaCount} media
                              </span>
                            )}
                          </button>
                        );
                      })}
                    </div>
                  </div>

                  {/* Read-Only Completed Summary Banner for Partial Batch */}
                  {completedListings.length > 0 && (
                    <div className="mb-3 rounded-2xl border border-emerald-500/40 bg-gradient-to-r from-emerald-950/40 via-slate-900/60 to-slate-900/40 p-3.5 sm:p-4 text-slate-100 shadow-sm">
                      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-1.5 pb-2.5 border-b border-emerald-500/20">
                        <div className="flex items-center gap-2">
                          <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />
                          <span className="text-xs sm:text-sm font-bold text-emerald-300">
                            ✓ {completedListings.length} of {completedListings.length + stagedCards.length} properties published
                          </span>
                        </div>
                        <span className="text-[11px] font-mono text-emerald-400/90 font-semibold">
                          {stagedCards.length} {stagedCards.length === 1 ? 'property remaining' : 'properties remaining'}
                        </span>
                      </div>
                      <div className="flex flex-wrap gap-2 pt-2.5">
                        {completedListings.map((item) => (
                          <span
                            key={item.cardId || item.listingId}
                            className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-xl bg-emerald-900/30 border border-emerald-500/30 text-[11px] font-medium text-emerald-200"
                          >
                            <CheckCircle2 className="w-3 h-3 text-emerald-400" />
                            <span className="font-semibold">{item.title}</span>
                            <span className="font-mono text-emerald-400/80">Listing #{item.listingId}</span>
                          </span>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* Staged Cards Grid */}
                  <div className="flex flex-col gap-3.5">
                    {stagedCards.map((card, idx) => {
                      if (activeCardId && card.id !== activeCardId) return null;

                      const palette = COLOR_PALETTES[idx % COLOR_PALETTES.length];
                      const totalMedia = (card.stagedMedia && card.stagedMedia.length > 0)
                        ? card.stagedMedia.length
                        : (card.localPhotos.length + card.mediaUrls.length);
                      const requiredSnapshot = [
                        { label: 'Layout', value: card.bhk, ready: isProvided(card.bhk) },
                        { label: 'Property type', value: card.type, ready: isProvided(card.type) },
                        { label: 'Monthly rent', value: card.rentAmount > 0 ? `₹${card.rentAmount.toLocaleString('en-IN')}` : '', ready: card.rentAmount > 0 },
                        { label: 'Security deposit', value: card.depositVal, ready: isProvided(card.depositVal) && /\d/.test(card.depositVal) },
                        { label: 'Locality', value: card.sector, ready: isProvided(card.sector) },
                        { label: 'City', value: card.city, ready: isProvided(card.city) },
                        { label: 'Owner phone', value: card.ownerPhone, ready: hasValidOwnerPhone(card.ownerPhone) }
                      ];
                      const completedRequiredCount = requiredSnapshot.filter((item) => item.ready).length;

                      return (
                        <div
                          key={card.id}
                          tabIndex={0}
                          onPaste={(e) => handleCardPaste(e, card.id)}
                          onClick={() => setActiveCardId(card.id)}
                          className={`rounded-2xl border bg-slate-950/70 p-2.5 shadow-lg transition-all sm:p-4 ${
                            activeCardId === card.id ? `${palette.border} ring-1 ${palette.border}` : 'border-slate-800/80 hover:border-slate-700'
                          }`}
                        >
                          {/* Card Header */}
                          <div className="flex flex-col gap-2 pb-3 border-b border-slate-800/80 mb-3 min-[560px]:flex-row min-[560px]:items-center min-[560px]:justify-between">
                            <div className="flex min-w-0 w-full items-center gap-2.5">
                              <span className={`px-2 py-0.5 rounded-lg text-xs font-black uppercase font-mono ${palette.badge}`}>
                                #{idx + 1}
                              </span>
                              <input
                                type="text"
                                value={card.title}
                                onChange={(e) => handleUpdateField(card.id, 'title', e.target.value)}
                                className="min-w-0 flex-1 truncate border-b border-transparent bg-transparent text-[16px] sm:text-xs font-bold text-white hover:border-slate-700 focus:border-amber-500 focus:outline-none min-[560px]:max-w-[240px]"
                              />
                            </div>

                            <div className="flex shrink-0 items-center gap-2 self-start min-[560px]:self-auto">
                              {card.publishedId ? (
                                <span className="flex items-center gap-1 text-[11px] font-bold text-amber-300 bg-amber-500/10 px-2 py-0.5 rounded-full border border-amber-500/30">
                                  <Clock className="w-3 h-3" /> Media Pending
                                </span>
                              ) : publishingCardId === card.id ? (
                                <span className="flex items-center gap-1 text-[11px] font-bold text-cyan-300 bg-cyan-500/10 px-2 py-0.5 rounded-full border border-cyan-500/30">
                                  <RefreshCw className="w-3 h-3 animate-spin" /> Publishing…
                                </span>
                              ) : card.isValid ? (
                                <span className="flex items-center gap-1 text-[11px] font-bold text-emerald-400 bg-emerald-500/10 px-2 py-0.5 rounded-full border border-emerald-500/30">
                                  <CheckCircle2 className="w-3 h-3" /> Ready to publish
                                </span>
                              ) : (
                                <span className="flex items-center gap-1 text-[11px] font-bold text-amber-400 bg-amber-500/10 px-2 py-0.5 rounded-full border border-amber-500/30" title={card.missingFields.join(', ')}>
                                  <AlertCircle className="w-3 h-3" /> {card.missingFields[0] || 'Awaiting confirmation'}
                                </span>
                              )}
                            </div>
                          </div>

                          {card.appliedAmendments.length > 0 && (
                            <div className="mb-3 rounded-xl border border-emerald-500/30 bg-emerald-500/10 px-3 py-2 text-[11px] text-emerald-100">
                              <div className="flex items-center gap-1.5 font-bold text-emerald-300">
                                <CheckCircle2 className="h-3.5 w-3.5" /> Later dictation added to this property
                              </div>
                              <ul className="mt-1 space-y-1 text-emerald-100/80">
                                {card.appliedAmendments.map((amendment, amendmentIndex) => (
                                  <li key={`${card.id}-amendment-${amendmentIndex}`}>“{amendment}”</li>
                                ))}
                              </ul>
                            </div>
                          )}

                          {card.conflicts.length > 0 && (
                            <div className="mb-3 rounded-xl border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-[11px] text-amber-100">
                              <div className="flex items-center gap-1.5 font-bold text-amber-300">
                                <AlertCircle className="h-3.5 w-3.5" /> Conflicting details need a decision
                              </div>
                              <p className="mt-1 text-amber-100/80">{card.conflicts.join(' • ')}</p>
                            </div>
                          )}

                          <details className="mb-3 rounded-xl border border-slate-800 bg-slate-900/40 px-3 py-2 text-[11px] text-slate-400">
                            <summary className="cursor-pointer font-semibold text-slate-300">View source text for this property</summary>
                            <p className="mt-2 whitespace-pre-wrap font-mono text-[10px] leading-relaxed text-slate-400">{card.rawPrompt}</p>
                          </details>

                          <div className="space-y-3 text-xs">
                            <section className="rounded-2xl border border-slate-800 bg-slate-900/55 p-3 lg:hidden">
                              <div className="mb-2 flex items-center justify-between gap-3">
                                <div>
                                  <h4 className="text-xs font-bold text-slate-100">Property snapshot</h4>
                                  <p className="mt-0.5 hidden text-[10px] text-slate-500 min-[480px]:block">Confirm the essentials, then edit only what needs attention.</p>
                                </div>
                                <span className={`shrink-0 rounded-full border px-2 py-1 text-[10px] font-bold ${
                                  completedRequiredCount === requiredSnapshot.length
                                    ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-300'
                                    : 'border-rose-500/30 bg-rose-500/10 text-rose-300'
                                }`}>
                                  {completedRequiredCount}/{requiredSnapshot.length} complete
                                </span>
                              </div>
                              <dl className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)] gap-x-4 gap-y-2">
                                {requiredSnapshot.map((item) => (
                                  <div key={item.label} className={`min-w-0 ${item.label === 'Owner phone' ? 'col-span-2' : ''}`}>
                                    <dt className="text-[9px] font-bold uppercase tracking-wide text-slate-500">{item.label}</dt>
                                    <dd className={`mt-0.5 truncate text-[10px] font-semibold ${item.ready ? 'text-slate-100' : 'text-rose-300'}`}>
                                      {item.ready ? item.value : 'Needs attention'}
                                    </dd>
                                  </div>
                                ))}
                              </dl>
                            </section>

                            <details className={`group rounded-2xl border lg:hidden ${
                              completedRequiredCount === requiredSnapshot.length && card.conflicts.length === 0
                                ? 'border-slate-800 bg-slate-900/45'
                                : 'border-rose-500/35 bg-rose-500/[0.04]'
                            }`}>
                              <summary className="flex min-h-11 cursor-pointer list-none items-center justify-between gap-3 px-3 py-2 text-xs font-bold text-slate-200 marker:content-none">
                                <span>{completedRequiredCount === requiredSnapshot.length ? 'Review or edit publishing fields' : 'Fix required publishing fields'}</span>
                                <span className="flex shrink-0 items-center gap-1.5 text-[10px] font-semibold text-slate-400">
                                  {completedRequiredCount}/{requiredSnapshot.length}
                                  <ChevronRight className="h-4 w-4 transition-transform group-open:rotate-90" />
                                </span>
                              </summary>
                              <div className="grid grid-cols-1 gap-2.5 border-t border-slate-800 px-3 pb-3 pt-3 min-[480px]:grid-cols-2">
                                <ReviewField label="Layout" value={card.bhk} required onValueChange={(value) => handleUpdateField(card.id, 'bhk', value)} placeholder="Enter layout" />
                                <ReviewField label="Property type" value={card.type} required onValueChange={(value) => handleUpdateField(card.id, 'type', value)} placeholder="Enter property type" />
                                <ReviewField label="Monthly rent (₹)" value={card.rentAmount || ''} required type="number" onValueChange={(value) => handleUpdateField(card.id, 'rentAmount', Number(value) || 0)} placeholder="Enter monthly rent" />
                                <ReviewField label="Security deposit" value={card.depositVal} required onValueChange={(value) => handleUpdateField(card.id, 'depositVal', value)} placeholder="Enter security deposit" />
                                <ReviewField label="Locality" value={card.sector} required onValueChange={(value) => handleUpdateField(card.id, 'sector', value)} placeholder="Enter locality" />
                                <ReviewField label="City" value={card.city} required onValueChange={(value) => handleUpdateField(card.id, 'city', value)} placeholder="Enter city" />
                                <ReviewField label="Owner phone" value={card.ownerPhone} required className="min-[480px]:col-span-2" onValueChange={(value) => handleUpdateField(card.id, 'ownerPhone', value)} onBlur={() => handleUpdateField(card.id, 'ownerPhone', normalizeOwnerPhone(card.ownerPhone))} placeholder="Enter mobile number" />
                              </div>
                            </details>

                            <section className="hidden lg:block">
                              <h4 className="mb-2 text-[10px] font-bold uppercase tracking-wider text-slate-500">Essential publishing details</h4>
                              <div className="grid grid-cols-1 gap-2.5 min-[480px]:grid-cols-2 sm:grid-cols-4">
                                <ReviewField label="Layout" value={card.bhk} required onValueChange={(value) => handleUpdateField(card.id, 'bhk', value)} placeholder="Enter layout" />
                                <ReviewField label="Property type" value={card.type} required onValueChange={(value) => handleUpdateField(card.id, 'type', value)} placeholder="Enter property type" />
                                <ReviewField label="Monthly rent (₹)" value={card.rentAmount || ''} required type="number" onValueChange={(value) => handleUpdateField(card.id, 'rentAmount', Number(value) || 0)} placeholder="Enter monthly rent" />
                                <ReviewField label="Security deposit" value={card.depositVal} required onValueChange={(value) => handleUpdateField(card.id, 'depositVal', value)} placeholder="Enter security deposit" />
                              </div>
                            </section>

                            <details className="group rounded-2xl border border-slate-800 bg-slate-900/45 lg:hidden">
                              <summary className="flex min-h-11 cursor-pointer list-none items-center justify-between gap-3 px-3 py-2 text-xs font-bold text-slate-200 marker:content-none">
                                <span>Additional property details</span>
                                <span className="flex shrink-0 items-center gap-1.5 text-[10px] font-semibold text-slate-400">
                                  {getAdditionalDetailCount(card)} captured
                                  <ChevronRight className="h-4 w-4 transition-transform group-open:rotate-90" />
                                </span>
                              </summary>
                              <div className="space-y-3 border-t border-slate-800 px-3 pb-3 pt-3">
                                <div className="grid grid-cols-1 gap-2.5 min-[480px]:grid-cols-2">
                                  <ReviewField label="Society / colony" value={card.colony} onValueChange={(value) => handleUpdateField(card.id, 'colony', value)} placeholder="Enter society or colony" />
                                  <ReviewField label="Landmark" value={card.landmark} onValueChange={(value) => handleUpdateField(card.id, 'landmark', value)} placeholder="Enter landmark" />
                                  <ReviewField label="Address" value={card.address} onValueChange={(value) => handleUpdateField(card.id, 'address', value)} placeholder="Enter address" />
                                  <ReviewField label="State" value={card.state} onValueChange={(value) => handleUpdateField(card.id, 'state', value)} placeholder="Enter state" />
                                  <ReviewField label="Postal code" value={card.pincode} onValueChange={(value) => handleUpdateField(card.id, 'pincode', value)} placeholder="Enter postal code" />
                                  <ReviewField label="Brokerage" value={card.brokerageVal} onValueChange={(value) => handleUpdateField(card.id, 'brokerageVal', value)} placeholder="Enter brokerage" />
                                  <ReviewField label="Brokerage terms" value={card.brokerageDays} onValueChange={(value) => handleUpdateField(card.id, 'brokerageDays', value)} placeholder="Enter brokerage terms" />
                                  <ReviewField label="Area" value={card.areaSqFt} onValueChange={(value) => handleUpdateField(card.id, 'areaSqFt', value)} placeholder="Enter area" />
                                  <ReviewField label="Bathrooms" value={card.bathrooms} onValueChange={(value) => handleUpdateField(card.id, 'bathrooms', value)} placeholder="Enter bathroom count" />
                                  <ReviewField label="Floor" value={card.floor !== null && card.floor !== undefined ? String(card.floor) : ''} onValueChange={(value) => handleFloorChange(card.id, value)} placeholder="e.g. 0 (Ground), 3" />
                                  <ReviewField label="Total floors" value={card.totalFloors !== null && card.totalFloors !== undefined ? String(card.totalFloors) : ''} type="number" onValueChange={(value) => handleTotalFloorsChange(card.id, value)} placeholder="e.g. 7" />
                                  <ReviewField label="Furnishing" value={card.furnishingStatus} onValueChange={(value) => handleUpdateField(card.id, 'furnishingStatus', value)} placeholder="Enter furnishing" />
                                  <ReviewField label="Facing" value={card.vastuFacing} onValueChange={(value) => handleUpdateField(card.id, 'vastuFacing', value)} placeholder="Enter facing" />
                                  <AvailabilityEditor
                                    status={card.availabilityStatus}
                                    availableFrom={card.availableFrom}
                                    onChange={(status, availableFrom) => handleAvailabilityChange(card.id, status, availableFrom)}
                                  />
                                  <ReviewField label="Listing status" value={card.status} onValueChange={(value) => handleUpdateField(card.id, 'status', value.toUpperCase())} placeholder="Enter listing status" />
                                  <PreferredTenantEditor
                                    selected={card.preferredTenants || []}
                                    onChange={(updated) => handleUpdateField(card.id, 'preferredTenants', updated)}
                                    className="min-[480px]:col-span-2"
                                  />
                                  <ReviewField label="Owner name" value={card.ownerName} onValueChange={(value) => handleUpdateField(card.id, 'ownerName', value)} placeholder="Enter owner name" />
                                  <ReviewField label="Amenities" value={card.amenities.join(', ')} onValueChange={(value) => handleUpdateField(card.id, 'amenities', value.split(',').map((amenity) => amenity.trim()).filter(isProvided))} placeholder="Enter amenities" />
                                  <ReviewField label="Description" value={card.description} multiline className="min-[480px]:col-span-2" onValueChange={(value) => handleUpdateField(card.id, 'description', value)} placeholder="Enter useful listing details" />
                                </div>
                              </div>
                            </details>

                            <section className="hidden lg:block">
                              <h4 className="mb-2 text-[10px] font-bold uppercase tracking-wider text-slate-500">Location</h4>
                              <div className="grid grid-cols-1 gap-2.5 min-[480px]:grid-cols-2 sm:grid-cols-4">
                                <ReviewField label="Locality" value={card.sector} required onValueChange={(value) => handleUpdateField(card.id, 'sector', value)} placeholder="Enter locality" />
                                <ReviewField label="City" value={card.city} required onValueChange={(value) => handleUpdateField(card.id, 'city', value)} placeholder="Enter city" />
                                <ReviewField label="Society / colony" value={card.colony} onValueChange={(value) => handleUpdateField(card.id, 'colony', value)} placeholder="Enter society or colony" />
                                <ReviewField label="Landmark" value={card.landmark} onValueChange={(value) => handleUpdateField(card.id, 'landmark', value)} placeholder="Enter landmark" />
                                <ReviewField label="Address" value={card.address} className="min-[480px]:col-span-2" onValueChange={(value) => handleUpdateField(card.id, 'address', value)} placeholder="Enter address" />
                                <ReviewField label="State" value={card.state} onValueChange={(value) => handleUpdateField(card.id, 'state', value)} placeholder="Enter state" />
                                <ReviewField label="Postal code" value={card.pincode} onValueChange={(value) => handleUpdateField(card.id, 'pincode', value)} placeholder="Enter postal code" />
                              </div>
                            </section>

                            <section className="hidden lg:block">
                              <h4 className="mb-2 text-[10px] font-bold uppercase tracking-wider text-slate-500">Property and commercial details</h4>
                              <div className="grid grid-cols-1 gap-2.5 min-[480px]:grid-cols-2 sm:grid-cols-4">
                                <ReviewField label="Brokerage" value={card.brokerageVal} onValueChange={(value) => handleUpdateField(card.id, 'brokerageVal', value)} placeholder="Enter brokerage" />
                                <ReviewField label="Brokerage terms" value={card.brokerageDays} onValueChange={(value) => handleUpdateField(card.id, 'brokerageDays', value)} placeholder="Enter brokerage terms" />
                                <ReviewField label="Area" value={card.areaSqFt} onValueChange={(value) => handleUpdateField(card.id, 'areaSqFt', value)} placeholder="Enter area" />
                                <ReviewField label="Bathrooms" value={card.bathrooms} onValueChange={(value) => handleUpdateField(card.id, 'bathrooms', value)} placeholder="Enter bathroom count" />
                                <ReviewField label="Floor" value={card.floor !== null && card.floor !== undefined ? String(card.floor) : ''} onValueChange={(value) => handleFloorChange(card.id, value)} placeholder="e.g. 0 (Ground), 3" />
                                <ReviewField label="Total floors" value={card.totalFloors !== null && card.totalFloors !== undefined ? String(card.totalFloors) : ''} type="number" onValueChange={(value) => handleTotalFloorsChange(card.id, value)} placeholder="e.g. 7" />
                                <ReviewField label="Furnishing" value={card.furnishingStatus} onValueChange={(value) => handleUpdateField(card.id, 'furnishingStatus', value)} placeholder="Enter furnishing" />
                                <ReviewField label="Facing" value={card.vastuFacing} onValueChange={(value) => handleUpdateField(card.id, 'vastuFacing', value)} placeholder="Enter facing" />
                                <AvailabilityEditor
                                  status={card.availabilityStatus}
                                  availableFrom={card.availableFrom}
                                  onChange={(status, availableFrom) => handleAvailabilityChange(card.id, status, availableFrom)}
                                />
                                <ReviewField label="Listing status" value={card.status} onValueChange={(value) => handleUpdateField(card.id, 'status', value.toUpperCase())} placeholder="Enter listing status" />
                                <PreferredTenantEditor
                                  selected={card.preferredTenants || []}
                                  onChange={(updated) => handleUpdateField(card.id, 'preferredTenants', updated)}
                                  className="min-[480px]:col-span-2 sm:col-span-4"
                                />
                              </div>
                            </section>

                            <section className="hidden lg:block">
                              <h4 className="mb-2 text-[10px] font-bold uppercase tracking-wider text-slate-500">Owner and listing notes</h4>
                              <div className="grid grid-cols-1 gap-2.5 min-[480px]:grid-cols-2 sm:grid-cols-4">
                                <ReviewField label="Owner name" value={card.ownerName} onValueChange={(value) => handleUpdateField(card.id, 'ownerName', value)} placeholder="Enter owner name" />
                                <ReviewField label="Owner phone" value={card.ownerPhone} required onValueChange={(value) => handleUpdateField(card.id, 'ownerPhone', value)} onBlur={() => handleUpdateField(card.id, 'ownerPhone', normalizeOwnerPhone(card.ownerPhone))} placeholder="Enter mobile number" />
                                <ReviewField label="Amenities" value={card.amenities.join(', ')} className="min-[480px]:col-span-2" onValueChange={(value) => handleUpdateField(card.id, 'amenities', value.split(',').map((amenity) => amenity.trim()).filter(isProvided))} placeholder="Enter amenities" />
                                <ReviewField label="Description" value={card.description} multiline className="min-[480px]:col-span-2 sm:col-span-4" onValueChange={(value) => handleUpdateField(card.id, 'description', value)} placeholder="Enter useful listing details" />
                              </div>
                              {card.parserMissingFields.length > 0 && (
                                <p className="mt-2 text-[10px] text-slate-500">Not found in the original description: {card.parserMissingFields.join(', ')}. These fields are available above if you want to add them.</p>
                              )}
                            </section>
                          </div>

                          {/* Photos Dropzone & Clipboard Paste Receiver */}
                          <div className="mt-2.5">
                            <div className="flex items-center justify-between text-[11px] text-slate-400 mb-1.5">
                              <span>Property media ({totalMedia})</span>
                              <label className="cursor-pointer text-amber-400 hover:text-amber-300 font-bold flex items-center gap-1">
                                <Plus className="w-3 h-3" /> Add photos or video
                                <input
                                  type="file"
                                  multiple
                                  accept="image/*,video/*"
                                  onChange={(e) => e.target.files && attachMediaToCard(card.id, e.target.files)}
                                  className="hidden"
                                />
                              </label>
                            </div>
                            <p className="mb-2 text-[10px] leading-relaxed text-slate-500">
                              {describeMediaLimits()}
                            </p>

                            {/* Staged Media Cards List */}
                            {totalMedia > 0 ? (
                              <div className="space-y-2.5 py-1">
                                {(card.stagedMedia || []).map((item) => {
                                  const isVideo = isMediaVideo(item);
                                  const filename = getMediaFilename(item);
                                  const fileSize = getMediaFileSize(item);
                                  return (
                                    <div
                                      key={item.id}
                                      className="bg-slate-950 p-2.5 sm:p-3 rounded-2xl border border-slate-800 flex items-start gap-3 hover:border-slate-700 transition-colors relative group"
                                    >
                                      {/* Photo/Video Thumbnail */}
                                      {!isVideo ? (
                                        <div className="relative w-16 h-16 sm:w-20 sm:h-20 rounded-xl overflow-hidden border border-slate-700 bg-slate-900 shrink-0 shadow-sm">
                                          <img
                                            src={item.previewUrl}
                                            alt={filename}
                                            className="w-full h-full object-cover"
                                          />
                                          {item.isCover && (
                                            <div
                                              title="Primary listing cover"
                                              className="absolute top-1 left-1 bg-amber-400 text-slate-950 rounded-md p-0.5 shadow-sm"
                                            >
                                              <Star className="w-3 h-3 fill-current text-slate-950" />
                                            </div>
                                          )}
                                        </div>
                                      ) : (
                                        <div className="relative w-16 h-16 sm:w-20 sm:h-20 rounded-xl bg-indigo-950/60 border border-indigo-500/40 flex items-center justify-center shrink-0 shadow-sm">
                                          <Video className="w-6 h-6 text-indigo-400" />
                                        </div>
                                      )}

                                      {/* File Details & Compact Controls */}
                                      <div className="flex-1 min-w-0 pr-7 flex flex-col justify-between self-stretch py-0.5">
                                        <div>
                                          <p className="font-bold text-slate-200 truncate text-xs sm:text-sm font-['Outfit']" title={filename}>
                                            {filename}
                                          </p>
                                          <p className="text-[10px] font-mono text-slate-500 mt-0.5">
                                            {(fileSize / 1024 / 1024).toFixed(1)} MB
                                          </p>
                                        </div>

                                        {/* Compact inline chip controls */}
                                        <div className="flex items-center gap-2 flex-wrap mt-2">
                                          {!isVideo ? (
                                            <button
                                              type="button"
                                              onClick={(e) => {
                                                e.stopPropagation();
                                                handleSetCoverPhoto(card.id, item.id);
                                              }}
                                              title={item.isCover ? 'Primary Cover Photo' : 'Click to set as primary cover'}
                                              className={`min-h-[28px] px-2 py-0.5 rounded-lg text-[10px] font-mono font-bold flex items-center gap-1 transition-all cursor-pointer ${
                                                item.isCover
                                                  ? 'bg-amber-400 text-slate-950 ring-1 ring-amber-300 font-extrabold shadow-xs'
                                                  : 'bg-slate-900 text-slate-400 hover:text-amber-300 border border-slate-800 hover:border-slate-700'
                                              }`}
                                            >
                                              <Star className={`w-3 h-3 ${item.isCover ? 'fill-current text-slate-950' : 'text-slate-400'}`} />
                                              <span>{item.isCover ? 'Cover photo' : 'Set cover'}</span>
                                            </button>
                                          ) : (
                                            <span className="min-h-[28px] px-2 py-0.5 rounded-lg text-[10px] font-mono text-slate-500 bg-slate-900/60 border border-slate-800/80 flex items-center gap-1">
                                              Video
                                            </span>
                                          )}

                                          <div className="relative inline-flex items-center">
                                            <select
                                              value={item.roomTag}
                                              onChange={(e) => {
                                                e.stopPropagation();
                                                handleSetMediaRoomTag(card.id, item.id, e.target.value as RoomTag);
                                              }}
                                              aria-label={`Room category for ${filename}`}
                                              className="min-h-[28px] bg-slate-900 text-cyan-300 font-mono text-[10px] sm:text-[11px] font-bold border border-slate-800 rounded-lg pl-2 pr-5 outline-none focus:border-cyan-500 cursor-pointer appearance-none"
                                            >
                                              {ROOM_TAG_OPTIONS.map((opt) => (
                                                <option key={opt.value} value={opt.value}>
                                                  {opt.label}
                                                </option>
                                              ))}
                                            </select>
                                            <ChevronDown className="w-3 h-3 text-slate-500 absolute right-1 pointer-events-none" />
                                          </div>
                                        </div>
                                      </div>

                                      {/* Delete / Remove Button - top-right corner */}
                                      <button
                                        type="button"
                                        onClick={(e) => {
                                          e.stopPropagation();
                                          handleRemoveStagedMedia(card.id, item.id);
                                        }}
                                        aria-label={`Remove file ${filename}`}
                                        className="absolute top-2.5 right-2.5 w-8 h-8 rounded-lg text-slate-500 hover:text-rose-400 hover:bg-rose-950/40 flex items-center justify-center transition-colors cursor-pointer"
                                      >
                                        <X className="w-4 h-4" />
                                      </button>
                                    </div>
                                  );
                                })}

                                {card.mediaUrls.map((url, uIdx) => (
                                  <div
                                    key={`url-${uIdx}`}
                                    className="bg-slate-950 p-2.5 sm:p-3 rounded-2xl border border-indigo-500/30 flex items-center gap-3 relative group"
                                  >
                                    <div className="relative w-16 h-16 sm:w-20 sm:h-20 rounded-xl overflow-hidden border border-indigo-500/40 bg-slate-900 shrink-0">
                                      {isVideoUrl(url) ? (
                                        <video src={url} className="h-full w-full object-cover" muted />
                                      ) : (
                                        <img src={url} alt="Property media" className="w-full h-full object-cover" />
                                      )}
                                    </div>
                                    <div className="flex-1 min-w-0 pr-7">
                                      <span className="inline-block px-1.5 py-0.5 rounded text-[9px] font-bold bg-indigo-950/90 text-indigo-300 border border-indigo-500/30 mb-1">
                                        Extracted Media URL
                                      </span>
                                      <p className="text-[11px] font-mono text-slate-400 truncate" title={url}>
                                        {url}
                                      </p>
                                    </div>
                                  </div>
                                ))}

                                {totalMedia > 0 && stagedCards.length > 1 && (
                                  <div className="pt-2 flex items-center justify-between gap-2">
                                    <div>
                                      {idx > 0 && (
                                        <button
                                          type="button"
                                          onClick={(e) => {
                                            e.stopPropagation();
                                            setActiveCardId(stagedCards[idx - 1].id);
                                          }}
                                          className="inline-flex min-h-[36px] items-center gap-1.5 rounded-xl border border-slate-800 bg-slate-900/90 hover:bg-slate-850 px-3 py-1.5 text-xs font-bold text-slate-300 hover:border-amber-400/50 hover:text-white transition-all cursor-pointer shadow-xs"
                                        >
                                          <ChevronLeft className="w-3.5 h-3.5 text-amber-400" />
                                          <span>Previous property ({stagedCards[idx - 1].bhk || stagedCards[idx - 1].title || `#${idx}`})</span>
                                        </button>
                                      )}
                                    </div>
                                    <div>
                                      {idx < stagedCards.length - 1 && (
                                        <button
                                          type="button"
                                          onClick={(e) => {
                                            e.stopPropagation();
                                            setActiveCardId(stagedCards[idx + 1].id);
                                          }}
                                          className="inline-flex min-h-[36px] items-center gap-1.5 rounded-xl border border-slate-800 bg-slate-900/90 hover:bg-slate-850 px-3 py-1.5 text-xs font-bold text-slate-300 hover:border-amber-400/50 hover:text-white transition-all cursor-pointer shadow-xs"
                                        >
                                          <span>Next property ({stagedCards[idx + 1].bhk || stagedCards[idx + 1].title || `#${idx + 2}`})</span>
                                          <ChevronRight className="w-3.5 h-3.5 text-amber-400" />
                                        </button>
                                      )}
                                    </div>
                                  </div>
                                )}
                              </div>
                            ) : (
                              <div
                                onDragOver={(e) => e.preventDefault()}
                                onDrop={(e) => {
                                  e.preventDefault();
                                  if (e.dataTransfer.files) attachMediaToCard(card.id, e.dataTransfer.files);
                                }}
                                className="hidden rounded-xl border border-dashed border-slate-800 bg-slate-900/40 px-4 py-3 text-center transition-colors hover:border-slate-700 lg:block"
                              >
                                <p className="text-[11px] text-slate-400">
                                  Drag photos or videos here, or paste images from your clipboard.
                                </p>
                              </div>
                            )}

                            {card.mediaUploadStatus !== 'idle' && (
                              <div className={`mt-2 rounded-xl border px-3 py-2.5 ${
                                card.mediaUploadStatus === 'failed'
                                  ? 'border-rose-500/40 bg-rose-500/10'
                                  : card.mediaUploadStatus === 'complete'
                                    ? 'border-emerald-500/35 bg-emerald-500/10'
                                    : 'border-cyan-500/35 bg-cyan-500/10'
                              }`}>
                                <div className="flex items-center justify-between gap-3 text-[10px] font-semibold">
                                  <span className={card.mediaUploadStatus === 'failed' ? 'text-rose-300' : 'text-slate-300'}>
                                    {card.mediaUploadMessage}
                                  </span>
                                  <span className="shrink-0 text-slate-400">{card.mediaUploadProgress}%</span>
                                </div>
                                <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-slate-800">
                                  <motion.div
                                    initial={{ width: 0 }}
                                    animate={{ width: `${card.mediaUploadProgress}%` }}
                                    transition={{ type: 'spring', stiffness: 450, damping: 24 }}
                                    className={`h-full rounded-full ${
                                      card.mediaUploadStatus === 'failed'
                                        ? 'bg-rose-400'
                                        : card.mediaUploadStatus === 'complete'
                                          ? 'bg-emerald-400'
                                          : 'bg-cyan-400'
                                    }`}
                                  />
                                </div>
                                {card.mediaUploadStatus === 'failed' && card.publishedId && (
                                  <button
                                    type="button"
                                    disabled={isPublishing}
                                    onClick={() => void handleRetryCardMedia(card.id)}
                                    className="mt-2 flex min-h-10 w-full items-center justify-center gap-2 rounded-lg border border-rose-400/40 bg-rose-500/15 px-3 text-[11px] font-bold text-rose-200 transition-colors hover:bg-rose-500/25 disabled:opacity-50"
                                  >
                                    <RefreshCw className="h-3.5 w-3.5" />
                                    Retry failed media
                                  </button>
                                )}
                              </div>
                            )}
                          </div>

                          <label className={`mt-3 flex min-h-11 items-center gap-2 rounded-xl border border-slate-800 bg-slate-900/60 px-3 py-2 text-[11px] text-slate-300 ${card.publishedId ? 'cursor-default opacity-70' : 'cursor-pointer'}`}>
                            <input
                              type="checkbox"
                              checked={card.isConfirmed}
                              disabled={Boolean(card.publishedId)}
                              onChange={(e) => handleConfirmCard(card.id, e.target.checked)}
                              className="h-4 w-4 shrink-0 accent-emerald-500"
                            />
                            I reviewed these details and confirm this property is ready to publish.
                          </label>

                          {stagedCards.length > 1 && (
                            <div className="mt-3 grid grid-cols-[1fr_auto_1fr] items-center gap-2 border-t border-slate-800/80 pt-3 lg:hidden">
                              <button
                                type="button"
                                disabled={idx === 0}
                                onClick={(event) => {
                                  event.stopPropagation();
                                  setActiveCardId(stagedCards[idx - 1]?.id || card.id);
                                }}
                                className="flex min-h-11 items-center justify-center gap-1 rounded-xl border border-slate-800 bg-slate-900/70 px-2 text-[11px] font-bold text-slate-300 disabled:opacity-35"
                              >
                                <ChevronLeft className="h-4 w-4" /> Previous
                              </button>
                              <span className="text-[10px] font-bold text-slate-500">{idx + 1} of {stagedCards.length}</span>
                              <button
                                type="button"
                                disabled={idx === stagedCards.length - 1}
                                onClick={(event) => {
                                  event.stopPropagation();
                                  setActiveCardId(stagedCards[idx + 1]?.id || card.id);
                                }}
                                className="flex min-h-11 items-center justify-center gap-1 rounded-xl border border-slate-800 bg-slate-900/70 px-2 text-[11px] font-bold text-slate-300 disabled:opacity-35"
                              >
                                Next <ChevronRight className="h-4 w-4" />
                              </button>
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* Footer Bar */}
          <div className={`${embedded ? 'hidden lg:flex' : 'flex'} z-20 flex-col gap-3 border-t border-slate-800 bg-slate-950/95 px-4 py-3 shadow-[0_-12px_30px_rgba(2,6,23,0.55)] backdrop-blur sm:flex-row sm:items-center sm:justify-between sm:px-6 sm:py-4 lg:shadow-none`}>
            <div className="text-xs text-slate-400">
              {stagedCards.length > 0 && (
                <span className="block truncate text-xs text-slate-400">
                  {completedListings.length > 0 ? (
                    <>
                      <strong className="text-emerald-300">{completedListings.length}</strong> of{' '}
                      <strong className="text-white">{totalOriginalCount}</strong> published •{' '}
                      {unconfirmedCount > 0 ? (
                        <>
                          <strong className="text-amber-300">{unconfirmedCount}</strong>{' '}
                          {unconfirmedCount === 1 ? 'property still needs' : 'properties still need'} confirmation.
                        </>
                      ) : (
                        <>
                          <strong className="text-emerald-300">{totalCardsCount}</strong>{' '}
                          {totalCardsCount === 1 ? 'property' : 'properties'} ready to publish.
                        </>
                      )}
                    </>
                  ) : unconfirmedCount > 0 ? (
                    <>
                      <strong className="text-white">{unconfirmedCount}</strong>{' '}
                      {unconfirmedCount === 1 ? 'property still needs' : 'properties still need'} confirmation.
                    </>
                  ) : (
                    <>
                      <strong className="text-white">{totalCardsCount}</strong> of{' '}
                      <strong className="text-white">{totalCardsCount}</strong> properties confirmed and ready to publish.
                    </>
                  )}
                </span>
              )}
            </div>

            <div className="flex w-full flex-col-reverse gap-2 min-[560px]:w-auto min-[560px]:flex-row min-[560px]:items-center min-[560px]:gap-3">
              <button
                type="button"
                onClick={onClose}
                className="hidden min-h-11 w-full items-center justify-center rounded-xl px-4 py-2 text-xs font-bold text-slate-400 transition-colors hover:text-white min-[560px]:flex min-[560px]:w-auto"
              >
                Back to property details
              </button>

              <motion.button
                whileHover={{ scale: 1.03 }}
                whileTap={{ scale: 0.97 }}
                onClick={handlePublishAll}
                disabled={isPublishing || readyToPublishCount === 0}
                aria-busy={isPublishing}
                className="flex min-h-11 w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-emerald-500 to-teal-600 px-5 py-2.5 text-center text-xs font-black text-slate-950 shadow-lg shadow-emerald-500/20 transition-all hover:brightness-110 disabled:opacity-50 min-[560px]:w-auto min-[560px]:px-6"
              >
                {isPublishing ? (
                  <span>Publishing listings…</span>
                ) : (
                  <>
                    <CheckCircle2 className="w-4 h-4" />
                    <span>{getPublishCtaText()}</span>
                  </>
                )}
              </motion.button>
            </div>
          </div>
        </motion.div>

        {embedded && stagedCards.length > 0 && mobileWorkspaceView === 'review' && typeof document !== 'undefined' && createPortal(
          <motion.div
            initial={{ opacity: 0, y: 18 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 18 }}
            transition={{ type: 'spring', stiffness: 420, damping: 32 }}
            className="fixed inset-x-0 bottom-0 z-[70] border-t border-slate-700/80 bg-slate-950/95 px-3 pt-3 shadow-[0_-14px_34px_rgba(2,6,23,0.7)] backdrop-blur-xl lg:hidden"
            style={{ paddingBottom: 'max(0.75rem, env(safe-area-inset-bottom))' }}
          >
            <div className="mx-auto flex max-w-3xl items-center gap-3">
              <div className="min-w-[58px] text-center" aria-live="polite">
                {completedListings.length > 0 ? (
                  <>
                    <span className="block text-sm font-black text-emerald-300">{completedListings.length}/{totalOriginalCount}</span>
                    <span className="block text-[9px] font-semibold uppercase tracking-wide text-slate-400">published</span>
                  </>
                ) : (
                  <>
                    <span className="block text-sm font-black text-white">{readyToPublishCount}/{totalCardsCount}</span>
                    <span className="block text-[9px] font-semibold uppercase tracking-wide text-slate-400">confirmed</span>
                  </>
                )}
              </div>
              <motion.button
                whileTap={{ scale: 0.98 }}
                onClick={handlePublishAll}
                disabled={isPublishing || readyToPublishCount === 0}
                aria-busy={isPublishing}
                className="flex min-h-12 flex-1 items-center justify-center gap-2 rounded-2xl bg-gradient-to-r from-emerald-500 to-teal-500 px-4 text-sm font-black text-slate-950 shadow-lg shadow-emerald-500/20 transition-all disabled:cursor-not-allowed disabled:opacity-45"
              >
                <CheckCircle2 className="h-4 w-4" />
                {isPublishing ? 'Publishing properties…' : getMobileCtaText()}
              </motion.button>
            </div>
          </motion.div>,
          document.body
        )}
      </div>
    </AnimatePresence>
  );
};
