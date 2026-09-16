import React, { useState, useEffect, useRef, useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import {
  Sparkles,
  Mic,
  MicOff,
  UploadCloud,
  CheckCircle2,
  AlertCircle,
  X,
  Plus,
  Scissors,
  Image as ImageIcon,
  Building2,
  Phone,
  MapPin,
  IndianRupee,
  Layers,
  ArrowRight,
  Trash2
} from 'lucide-react';
import { propertyService } from '../services/propertyService';
import { getErrorDetails, getErrorMessage } from '../services/apiError';
import { compressImageToWebP } from '../utils/imageOptimizer';
import { useNotification } from '../context/NotificationContext';

interface StagedProperty {
  id: string;
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
  ownerName: string;
  ownerPhone: string;
  furnishingStatus: string;
  vastuFacing: string;
  amenities: string[];
  description: string;
  parserMissingFields: string[];
  conflicts: string[];
  mediaUrls: string[];
  localPhotos: File[];
  localPhotoPreviews: string[];
  isConfirmed: boolean;
  isValid: boolean;
  publishedId?: number;
  missingFields: string[];
}

interface BatchPropertyIngestionStudioProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: (publishedCount: number) => void;
  embedded?: boolean;
  initialDetails?: string;
  initialMediaFiles?: File[];
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

const isProvided = (value: string | null | undefined): boolean =>
  Boolean(value && value.trim() && !EMPTY_VALUE_PATTERN.test(value.trim()));

const toEditableValue = (value: string | null | undefined): string =>
  isProvided(value) ? value!.trim() : '';

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

  return {
    ...card,
    missingFields: missing,
    isValid: card.isConfirmed && missing.length === 0 && !card.publishedId
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
        className="w-full resize-y bg-transparent text-xs font-semibold text-slate-100 outline-none placeholder:text-slate-600 placeholder:italic"
      />
    ) : (
      <input
        type={type}
        value={value}
        placeholder={placeholder}
        onChange={(event) => onValueChange(event.target.value)}
        onBlur={onBlur}
        className="w-full bg-transparent text-xs font-semibold text-slate-100 outline-none placeholder:text-slate-600 placeholder:italic"
      />
    )}
  </motion.label>
  );
};

export const BatchPropertyIngestionStudio: React.FC<BatchPropertyIngestionStudioProps> = ({
  isOpen,
  onClose,
  onSuccess,
  embedded = false,
  initialDetails = '',
  initialMediaFiles = []
}) => {
  const { showErrorDialog } = useNotification();
  const [rawPrompts, setRawPrompts] = useState<string>('');
  const [stagedCards, setStagedCards] = useState<StagedProperty[]>([]);
  const [isParsing, setIsParsing] = useState<boolean>(false);
  const [isPublishing, setIsPublishing] = useState<boolean>(false);
  const [isListening, setIsListening] = useState<boolean>(false);
  const [speechSupported, setSpeechSupported] = useState<boolean>(true);
  const [isDictationChoiceOpen, setIsDictationChoiceOpen] = useState<boolean>(false);
  const [detectedCount, setDetectedCount] = useState<number>(0);
  const [activeCardId, setActiveCardId] = useState<string | null>(null);

  const recognitionRef = useRef<any>(null);
  const batchSpeechBaseTextRef = useRef<string>('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const importedDetailsRef = useRef<string>('');
  const initialMediaAddedRef = useRef(false);
  const rawPromptsRef = useRef<string>('');
  const parsedPromptsRef = useRef<string>('');
  const parseRequestIdRef = useRef(0);

  const updateRawPrompts = useCallback((details: string) => {
    rawPromptsRef.current = details;
    setRawPrompts(details);

    if (details !== parsedPromptsRef.current) {
      setStagedCards([]);
      setActiveCardId(null);
    }
  }, []);

  // Live Delimiter Property Counting
  useEffect(() => {
    if (!rawPrompts.trim()) {
      setDetectedCount(0);
      return;
    }
    const chunks = rawPrompts
      .split(/(?:\r?\n\s*\r?\n+|---|(?:\b(?:next\s*property|next\s*flat)\b)|^(?:\d+[\)\.]|#\d+)\s+)/gmi)
      .filter((c) => c && c.trim().length >= 8);
    setDetectedCount(Math.max(1, chunks.length));
  }, [rawPrompts]);

  // Initialize Web Speech Recognition
  useEffect(() => {
    const SpeechRecognition =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

    if (!SpeechRecognition) {
      setSpeechSupported(false);
      return;
    }

    const recognition = new SpeechRecognition();
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.lang = 'en-IN'; // Optimized for Indian English & Hinglish terms

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
    };

    recognition.onend = () => {
      setIsListening(false);
      setIsDictationChoiceOpen(false);
    };

    recognitionRef.current = recognition;
  }, [updateRawPrompts]);

  const startDictation = (mode: 'replace' | 'append') => {
    if (!recognitionRef.current) return;

    try {
      const currentDraft = rawPromptsRef.current.trim();
      batchSpeechBaseTextRef.current = mode === 'append' ? currentDraft : '';
      if (mode === 'replace' && currentDraft) {
        updateRawPrompts('');
      }
      setIsDictationChoiceOpen(false);
      recognitionRef.current.start();
      setIsListening(true);
    } catch (err) {
      console.error('Failed to start voice listener', err);
    }
  };

  const toggleSpeech = () => {
    if (!recognitionRef.current) return;
    if (isListening) {
      recognitionRef.current.stop();
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
    const requestId = ++parseRequestIdRef.current;
    setIsParsing(true);
    initialMediaAddedRef.current = false;

    try {
      const dtos = await propertyService.parseBatchPrompts(details);
      const mapped: StagedProperty[] = dtos.map((dto: any, idx: number) => {
        const card: StagedProperty = {
          id: `staged-${idx}-${Date.now()}`,
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
          ownerName: toEditableValue(dto.ownerName),
          ownerPhone: normalizeOwnerPhone(dto.ownerPhone),
          furnishingStatus: toEditableValue(dto.furnishingStatus),
          vastuFacing: toEditableValue(dto.vastuFacing),
          amenities: Array.isArray(dto.amenities) ? dto.amenities.filter(isProvided) : [],
          description: toEditableValue(dto.description),
          parserMissingFields: Array.isArray(dto.missingFields) ? dto.missingFields : [],
          conflicts: Array.isArray(dto.conflicts) ? dto.conflicts : [],
          mediaUrls: dto.mediaUrls || [],
          localPhotos: [],
          localPhotoPreviews: [],
          isConfirmed: false,
          isValid: false,
          missingFields: []
        };
        return validateCard(card);
      });

      if (requestId !== parseRequestIdRef.current || rawPromptsRef.current !== details) return;

      parsedPromptsRef.current = details;
      setStagedCards(mapped);
      if (mapped.length > 0) {
        setActiveCardId(mapped[0].id);
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
  }, [showErrorDialog]);

  useEffect(() => {
    if (!initialDetails.trim() || importedDetailsRef.current === initialDetails) return;

    importedDetailsRef.current = initialDetails;
    updateRawPrompts(initialDetails);
    void parseBatchDetails(initialDetails);
  }, [initialDetails, parseBatchDetails, updateRawPrompts]);

  const handleParseBatch = () => parseBatchDetails(rawPrompts);

  const attachMediaToCard = useCallback(async (cardId: string, files: FileList | File[]) => {
    const fileArray = Array.from(files).filter(
      (file) => file.type.startsWith('image/') || file.type.startsWith('video/')
    );
    const compressedFiles: File[] = [];
    const previews: string[] = [];

    for (const f of fileArray) {
      const optimized = f.type.startsWith('image/')
        ? await compressImageToWebP(f, 1920, 1080, 0.82)
        : f;
      compressedFiles.push(optimized);
      previews.push(URL.createObjectURL(optimized));
    }

    setStagedCards((prev) =>
      prev.map((c) =>
        c.id === cardId
          ? {
              ...c,
              localPhotos: [...c.localPhotos, ...compressedFiles],
              localPhotoPreviews: [...c.localPhotoPreviews, ...previews]
            }
          : c
      )
    );
  }, []);

  useEffect(() => {
    if (initialMediaAddedRef.current || initialMediaFiles.length === 0 || stagedCards.length === 0) return;

    initialMediaAddedRef.current = true;
    void attachMediaToCard(stagedCards[0].id, initialMediaFiles);
  }, [attachMediaToCard, initialMediaFiles, stagedCards]);

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

  // Remove photo from card
  const handleRemovePhoto = (cardId: string, photoIdx: number) => {
    setStagedCards((prev) =>
      prev.map((c) => {
        if (c.id !== cardId) return c;
        const newFiles = [...c.localPhotos];
        const newPreviews = [...c.localPhotoPreviews];
        newFiles.splice(photoIdx, 1);
        newPreviews.splice(photoIdx, 1);
        return { ...c, localPhotos: newFiles, localPhotoPreviews: newPreviews };
      })
    );
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

  const handleConfirmCard = (cardId: string, isConfirmed: boolean) => {
    setStagedCards((prev) => prev.map((card) => {
      if (card.id !== cardId) return card;
      return validateCard({ ...card, isConfirmed });
    }));
  };

  // Batch Publish All Staged Properties
  const handlePublishAll = async () => {
    const validCards = stagedCards.filter((c) => c.isValid && !c.publishedId);
    if (validCards.length === 0) {
      showErrorDialog({
        title: 'Review the property details',
        message: 'Complete the required details and confirm at least one listing before publishing.'
      });
      return;
    }

    setIsPublishing(true);

    try {
      const payloadListings = validCards.map((c) => ({
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
        ownerName: c.ownerName,
        ownerPhone: c.ownerPhone,
        furnishingStatus: c.furnishingStatus,
        vastuFacing: c.vastuFacing,
        amenities: c.amenities,
        mediaUrls: c.mediaUrls,
        adminVerified: c.isConfirmed
      }));

      const res = await propertyService.createBatchProperties(payloadListings);

      // Upload media only after its listing exists, preserving the selected images and videos.
      const mediaUploadErrors: string[] = [];
      if (res.createdIds && Array.isArray(res.createdIds)) {
        for (let i = 0; i < res.createdIds.length; i++) {
          const propId = res.createdIds[i];
          const card = validCards[i];
          if (card && card.localPhotos.length > 0) {
            try {
              const imageFiles = card.localPhotos.filter((file) => file.type.startsWith('image/'));
              const videoFiles = card.localPhotos.filter((file) => file.type.startsWith('video/'));
              if (imageFiles.length > 0) {
                await propertyService.uploadPhotosToCloudinary(propId, imageFiles);
              }
              for (const videoFile of videoFiles) {
                await propertyService.uploadVideoToCloudinary(propId, videoFile);
              }
            } catch (mediaErr) {
              console.warn(`Media upload failed for property ${propId}:`, mediaErr);
              mediaUploadErrors.push(`Property ${i + 1}: ${getErrorMessage(mediaErr, 'Media could not be uploaded.')}`);
            }
          }
        }
      }

      window.dispatchEvent(new Event('pathome_property_published'));
      const publishedCount = res.successCount || 0;
      setStagedCards((current) => {
        let publishedIndex = 0;
        return current.map((card) => {
          if (!card.isValid || card.publishedId || publishedIndex >= publishedCount) return card;
          const publishedId = res.createdIds?.[publishedIndex++];
          return { ...card, publishedId, isValid: false, isConfirmed: true };
        });
      });
      onSuccess(publishedCount);

      if (mediaUploadErrors.length > 0) {
        showErrorDialog({
          title: 'Property published, but some media needs attention',
          message: 'The listing details were published. Please add the affected media again from the property management page.',
          details: mediaUploadErrors.join(' • ')
        });
      }

      if (res.failedCount > 0) {
        showErrorDialog({
          title: 'Some properties still need attention',
          message: `${publishedCount} ${publishedCount === 1 ? 'property was' : 'properties were'} published. Review the remaining entries and try again.`,
          details: Array.isArray(res.failedListings)
            ? res.failedListings.map((item: any) => item.error).filter(Boolean).join(' • ')
            : undefined
        });
        return;
      }

      onClose();
    } catch (err: unknown) {
      console.error('Batch publish failed:', err);
      showErrorDialog({
        title: 'Unable to publish the selected properties',
        message: getErrorMessage(err, 'Please try again after reviewing the property details.'),
        details: getErrorDetails(err)
      });
    } finally {
      setIsPublishing(false);
    }
  };

  if (!isOpen) return null;

  return (
    <AnimatePresence>
      <div className={embedded ? 'relative w-full' : 'fixed inset-0 z-50 flex items-center justify-center p-3 sm:p-6 overflow-y-auto'}>
        {!embedded && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={onClose}
            className="fixed inset-0 bg-slate-950/80 backdrop-blur-md transition-all"
          />
        )}

        {/* 3D Spring Tilt Pop-in Modal Window */}
        <motion.div
          initial={embedded ? { opacity: 0, y: 12 } : { opacity: 0, scale: 0.85, rotateX: 14 }}
          animate={{ opacity: 1, scale: 1, rotateX: 0 }}
          exit={embedded ? { opacity: 0, y: 12 } : { opacity: 0, scale: 0.85, rotateX: 14 }}
          transition={{ type: 'spring', stiffness: 480, damping: 25 }}
          className={`relative w-full flex flex-col bg-slate-900 border border-slate-700/80 rounded-3xl shadow-2xl overflow-hidden text-slate-100 ${
            embedded ? 'max-h-none' : 'max-w-7xl max-h-[92vh] z-10'
          }`}
        >
          {/* Header Bar */}
          <div className="flex items-center justify-between px-6 py-4 border-b border-slate-800 bg-slate-950/70">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-2xl bg-gradient-to-tr from-amber-500 to-orange-400 flex items-center justify-center text-slate-950 shadow-lg shadow-orange-500/20">
                <Sparkles className="w-5 h-5 font-black" />
              </div>
              <div>
                <h2 className="text-lg font-black tracking-tight text-white flex items-center gap-2">
                  Review property details
                  <span className="text-[10px] font-bold uppercase tracking-wider px-2.5 py-0.5 rounded-full bg-amber-500/20 text-amber-300 border border-amber-500/30">
                    Admin review
                  </span>
                </h2>
                <p className="text-xs text-slate-400">
                  Add one or several properties, then confirm the complete details for each before publishing.
                </p>
              </div>
            </div>

            <button
              onClick={onClose}
              aria-label="Back to property details"
              title="Back to property details"
              className="p-2 rounded-xl text-slate-400 hover:text-white hover:bg-slate-800/80 transition-colors"
            >
              <X className="w-5 h-5" />
            </button>
          </div>

          {/* Main Content Split Screen */}
          <div className="flex-1 overflow-y-auto p-6 grid grid-cols-1 lg:grid-cols-12 gap-6">
            {/* Left Panel: Voice & Text Prompt Input (5 Cols) */}
            <div className="lg:col-span-5 flex flex-col gap-4">
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
                    className={`inline-flex h-9 shrink-0 items-center gap-2 rounded-xl px-3 text-xs font-bold transition-all ${
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
                  onChange={(e) => updateRawPrompts(e.target.value)}
                  placeholder={`Type, paste, or dictate property details here.\n\nTo add another property, write “next property” on a new line.\n\nExample:\n2 BHK in Vijay Nagar, rent 18,000, owner 98260 12345\n\nnext property\n3 BHK in Palasia, rent 35,000, owner 98260 54321`}
                  className="w-full h-full min-h-[260px] p-4 text-xs font-mono bg-slate-950/80 border border-slate-700/80 rounded-2xl text-slate-100 placeholder-slate-500 focus:outline-none focus:border-amber-500/80 focus:ring-2 focus:ring-amber-500/20 transition-all resize-none shadow-inner"
                />
              </div>

              <div className="flex justify-end pt-1">
                <motion.button
                  whileHover={{ scale: 1.03 }}
                  whileTap={{ scale: 0.97 }}
                  onClick={handleParseBatch}
                  disabled={isParsing || !rawPrompts.trim()}
                  className="px-5 py-2.5 rounded-xl font-black text-xs bg-gradient-to-r from-amber-500 via-orange-500 to-amber-600 text-slate-950 shadow-lg shadow-orange-500/20 hover:brightness-110 transition-all disabled:opacity-50 flex items-center gap-2"
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
            <div className="lg:col-span-7 flex flex-col gap-4">
              {stagedCards.length === 0 ? (
                <div className="h-full min-h-[360px] flex flex-col items-center justify-center border border-dashed border-slate-800 rounded-2xl p-8 text-center bg-slate-950/40">
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
                  <div className="p-3.5 bg-slate-950/90 border border-slate-800 rounded-2xl shadow-md">
                    <div className="flex items-center justify-between text-xs font-bold text-slate-300 mb-2">
                      <span className="flex items-center gap-1.5">
                        <Scissors className="w-3.5 h-3.5 text-amber-400" />
                        Properties to review
                      </span>
                      <span className="text-[11px] text-slate-400">
                        {stagedCards.length} {stagedCards.length === 1 ? 'property' : 'properties'} awaiting review
                      </span>
                    </div>

                    <div className="flex items-center gap-1.5 overflow-x-auto pb-1">
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
                            <span>{card.bhk || 'Property'} {card.sector || 'details'}</span>
                            <span className="px-1.5 py-0.2 rounded-md bg-slate-800 text-[10px] text-slate-300 font-mono">
                              {mediaCount} media
                            </span>
                          </button>
                        );
                      })}
                    </div>
                  </div>

                  {/* Staged Cards Grid */}
                  <div className="flex flex-col gap-3.5 max-h-[500px] overflow-y-auto pr-1">
                    {stagedCards.map((card, idx) => {
                      if (activeCardId && card.id !== activeCardId) return null;

                      const palette = COLOR_PALETTES[idx % COLOR_PALETTES.length];
                      const totalMedia = card.localPhotos.length + card.mediaUrls.length;

                      return (
                        <div
                          key={card.id}
                          tabIndex={0}
                          onPaste={(e) => handleCardPaste(e, card.id)}
                          onClick={() => setActiveCardId(card.id)}
                          className={`p-4 rounded-2xl border transition-all bg-slate-950/70 shadow-lg ${
                            activeCardId === card.id ? `${palette.border} ring-1 ${palette.border}` : 'border-slate-800/80 hover:border-slate-700'
                          }`}
                        >
                          {/* Card Header */}
                          <div className="flex items-center justify-between pb-3 border-b border-slate-800/80 mb-3">
                            <div className="flex items-center gap-2.5">
                              <span className={`px-2 py-0.5 rounded-lg text-xs font-black uppercase font-mono ${palette.badge}`}>
                                #{idx + 1}
                              </span>
                              <input
                                type="text"
                                value={card.title}
                                onChange={(e) => handleUpdateField(card.id, 'title', e.target.value)}
                                className="text-xs font-bold text-white bg-transparent border-b border-transparent hover:border-slate-700 focus:border-amber-500 focus:outline-none max-w-[240px] truncate"
                              />
                            </div>

                            <div className="flex items-center gap-2">
                              {card.publishedId ? (
                                <span className="flex items-center gap-1 text-[11px] font-bold text-sky-300 bg-sky-500/10 px-2 py-0.5 rounded-full border border-sky-500/30">
                                  <CheckCircle2 className="w-3 h-3" /> Published
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

                          {card.conflicts.length > 0 && (
                            <div className="mb-3 rounded-xl border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-[11px] text-amber-100">
                              <div className="flex items-center gap-1.5 font-bold text-amber-300">
                                <AlertCircle className="h-3.5 w-3.5" /> Conflicting details need a decision
                              </div>
                              <p className="mt-1 text-amber-100/80">{card.conflicts.join(' • ')}</p>
                            </div>
                          )}

                          <label className={`mb-3 flex items-center gap-2 rounded-xl border border-slate-800 bg-slate-900/60 px-3 py-2 text-[11px] text-slate-300 ${card.publishedId ? 'cursor-default opacity-70' : 'cursor-pointer'}`}>
                            <input
                              type="checkbox"
                              checked={card.isConfirmed}
                              disabled={Boolean(card.publishedId)}
                              onChange={(e) => handleConfirmCard(card.id, e.target.checked)}
                              className="h-3.5 w-3.5 accent-emerald-500"
                            />
                            I reviewed these details and confirm this property is ready to publish.
                          </label>

                          <details className="mb-3 rounded-xl border border-slate-800 bg-slate-900/40 px-3 py-2 text-[11px] text-slate-400">
                            <summary className="cursor-pointer font-semibold text-slate-300">View source text for this property</summary>
                            <p className="mt-2 whitespace-pre-wrap font-mono text-[10px] leading-relaxed text-slate-400">{card.rawPrompt}</p>
                          </details>

                          <div className="space-y-3 text-xs">
                            <section>
                              <h4 className="mb-2 text-[10px] font-bold uppercase tracking-wider text-slate-500">Essential publishing details</h4>
                              <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-4">
                                <ReviewField label="Layout" value={card.bhk} required onValueChange={(value) => handleUpdateField(card.id, 'bhk', value)} placeholder="Enter layout" />
                                <ReviewField label="Property type" value={card.type} required onValueChange={(value) => handleUpdateField(card.id, 'type', value)} placeholder="Enter property type" />
                                <ReviewField label="Monthly rent (₹)" value={card.rentAmount || ''} required type="number" onValueChange={(value) => handleUpdateField(card.id, 'rentAmount', Number(value) || 0)} placeholder="Enter monthly rent" />
                                <ReviewField label="Security deposit" value={card.depositVal} required onValueChange={(value) => handleUpdateField(card.id, 'depositVal', value)} placeholder="Enter security deposit" />
                              </div>
                            </section>

                            <section>
                              <h4 className="mb-2 text-[10px] font-bold uppercase tracking-wider text-slate-500">Location</h4>
                              <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-4">
                                <ReviewField label="Locality" value={card.sector} required onValueChange={(value) => handleUpdateField(card.id, 'sector', value)} placeholder="Enter locality" />
                                <ReviewField label="City" value={card.city} required onValueChange={(value) => handleUpdateField(card.id, 'city', value)} placeholder="Enter city" />
                                <ReviewField label="Society / colony" value={card.colony} onValueChange={(value) => handleUpdateField(card.id, 'colony', value)} placeholder="Enter society or colony" />
                                <ReviewField label="Landmark" value={card.landmark} onValueChange={(value) => handleUpdateField(card.id, 'landmark', value)} placeholder="Enter landmark" />
                                <ReviewField label="Address" value={card.address} className="col-span-2" onValueChange={(value) => handleUpdateField(card.id, 'address', value)} placeholder="Enter address" />
                                <ReviewField label="State" value={card.state} onValueChange={(value) => handleUpdateField(card.id, 'state', value)} placeholder="Enter state" />
                                <ReviewField label="Postal code" value={card.pincode} onValueChange={(value) => handleUpdateField(card.id, 'pincode', value)} placeholder="Enter postal code" />
                              </div>
                            </section>

                            <section>
                              <h4 className="mb-2 text-[10px] font-bold uppercase tracking-wider text-slate-500">Property and commercial details</h4>
                              <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-4">
                                <ReviewField label="Brokerage" value={card.brokerageVal} onValueChange={(value) => handleUpdateField(card.id, 'brokerageVal', value)} placeholder="Enter brokerage" />
                                <ReviewField label="Brokerage terms" value={card.brokerageDays} onValueChange={(value) => handleUpdateField(card.id, 'brokerageDays', value)} placeholder="Enter brokerage terms" />
                                <ReviewField label="Area" value={card.areaSqFt} onValueChange={(value) => handleUpdateField(card.id, 'areaSqFt', value)} placeholder="Enter area" />
                                <ReviewField label="Bathrooms" value={card.bathrooms} onValueChange={(value) => handleUpdateField(card.id, 'bathrooms', value)} placeholder="Enter bathroom count" />
                                <ReviewField label="Furnishing" value={card.furnishingStatus} onValueChange={(value) => handleUpdateField(card.id, 'furnishingStatus', value)} placeholder="Enter furnishing" />
                                <ReviewField label="Facing" value={card.vastuFacing} onValueChange={(value) => handleUpdateField(card.id, 'vastuFacing', value)} placeholder="Enter facing" />
                                <ReviewField label="Available from" value={card.possessionDate} onValueChange={(value) => handleUpdateField(card.id, 'possessionDate', value)} placeholder="Enter availability" />
                                <ReviewField label="Listing status" value={card.status} onValueChange={(value) => handleUpdateField(card.id, 'status', value.toUpperCase())} placeholder="Enter listing status" />
                              </div>
                            </section>

                            <section>
                              <h4 className="mb-2 text-[10px] font-bold uppercase tracking-wider text-slate-500">Owner and listing notes</h4>
                              <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-4">
                                <ReviewField label="Owner name" value={card.ownerName} onValueChange={(value) => handleUpdateField(card.id, 'ownerName', value)} placeholder="Enter owner name" />
                                <ReviewField label="Owner phone" value={card.ownerPhone} required onValueChange={(value) => handleUpdateField(card.id, 'ownerPhone', value)} onBlur={() => handleUpdateField(card.id, 'ownerPhone', normalizeOwnerPhone(card.ownerPhone))} placeholder="Enter mobile number" />
                                <ReviewField label="Amenities" value={card.amenities.join(', ')} className="col-span-2" onValueChange={(value) => handleUpdateField(card.id, 'amenities', value.split(',').map((amenity) => amenity.trim()).filter(isProvided))} placeholder="Enter amenities" />
                                <ReviewField label="Description" value={card.description} multiline className="col-span-2 sm:col-span-4" onValueChange={(value) => handleUpdateField(card.id, 'description', value)} placeholder="Enter useful listing details" />
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
                                <Plus className="w-3 h-3" /> Add media
                                <input
                                  type="file"
                                  multiple
                                  accept="image/*,video/*"
                                  onChange={(e) => e.target.files && attachMediaToCard(card.id, e.target.files)}
                                  className="hidden"
                                />
                              </label>
                            </div>

                            {/* Thumbnail Strip */}
                            {totalMedia > 0 ? (
                              <div className="flex items-center gap-2 overflow-x-auto py-1">
                                {card.localPhotoPreviews.map((src, pIdx) => (
                                  <div key={pIdx} className="relative group w-14 h-14 rounded-xl overflow-hidden border border-slate-700 flex-shrink-0">
                                    {card.localPhotos[pIdx]?.type.startsWith('video/') ? (
                                      <video src={src} className="h-full w-full object-cover" muted />
                                    ) : (
                                      <img src={src} alt="Property media" className="w-full h-full object-cover" />
                                    )}
                                    <button
                                      type="button"
                                      onClick={() => handleRemovePhoto(card.id, pIdx)}
                                      className="absolute inset-0 bg-slate-950/70 text-rose-400 opacity-0 group-hover:opacity-100 flex items-center justify-center transition-opacity"
                                    >
                                      <Trash2 className="w-3.5 h-3.5" />
                                    </button>
                                  </div>
                                ))}

                                {card.mediaUrls.map((url, uIdx) => (
                                  <div key={`url-${uIdx}`} className="relative w-14 h-14 rounded-xl overflow-hidden border border-indigo-500/40 flex-shrink-0">
                                    {isVideoUrl(url) ? (
                                      <video src={url} className="h-full w-full object-cover" muted />
                                    ) : (
                                      <img src={url} alt="Property media" className="w-full h-full object-cover" />
                                    )}
                                    <span className="absolute bottom-0 inset-x-0 bg-indigo-950/90 text-[8px] font-bold text-center text-indigo-300 truncate px-1">
                                      URL
                                    </span>
                                  </div>
                                ))}
                              </div>
                            ) : (
                              <div
                                onDragOver={(e) => e.preventDefault()}
                                onDrop={(e) => {
                                  e.preventDefault();
                                  if (e.dataTransfer.files) attachMediaToCard(card.id, e.dataTransfer.files);
                                }}
                                className="border border-dashed border-slate-800 rounded-xl py-3 px-4 text-center hover:border-slate-700 transition-colors cursor-pointer bg-slate-900/40"
                              >
                                <p className="text-[11px] text-slate-400">
                                  Drag photos or videos here, or paste images from your clipboard.
                                </p>
                              </div>
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* Footer Bar */}
          <div className="flex items-center justify-between px-6 py-4 border-t border-slate-800 bg-slate-950/80">
            <div className="text-xs text-slate-400">
              {stagedCards.length > 0 && (
                <span>
                  <strong className="text-white">{stagedCards.filter((c) => c.isValid && !c.publishedId).length}</strong> of{' '}
                  <strong className="text-white">{stagedCards.filter((c) => !c.publishedId).length}</strong> properties ready to publish.
                </span>
              )}
            </div>

            <div className="flex items-center gap-3">
              <button
                type="button"
                onClick={onClose}
                className="px-4 py-2 rounded-xl text-xs font-bold text-slate-400 hover:text-white transition-colors"
              >
                Back to property details
              </button>

              <motion.button
                whileHover={{ scale: 1.03 }}
                whileTap={{ scale: 0.97 }}
                onClick={handlePublishAll}
                disabled={isPublishing || stagedCards.filter((c) => c.isValid).length === 0}
                className="px-6 py-2.5 rounded-xl font-black text-xs bg-gradient-to-r from-emerald-500 to-teal-600 text-slate-950 shadow-lg shadow-emerald-500/20 hover:brightness-110 transition-all disabled:opacity-50 flex items-center gap-2"
              >
                {isPublishing ? (
                  <span>Publishing listings…</span>
                ) : (
                  <>
                    <CheckCircle2 className="w-4 h-4" />
                  <span>Publish confirmed properties ({stagedCards.filter((c) => c.isValid && !c.publishedId).length})</span>
                  </>
                )}
              </motion.button>
            </div>
          </div>
        </motion.div>
      </div>
    </AnimatePresence>
  );
};
