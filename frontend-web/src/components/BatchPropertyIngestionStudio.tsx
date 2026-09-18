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
  Trash2,
  RefreshCw
} from 'lucide-react';
import { propertyService } from '../services/propertyService';
import { getErrorDetails, getErrorMessage } from '../services/apiError';
import { describeMediaLimits, prepareMediaForUpload } from '../utils/imageOptimizer';
import { useNotification } from '../context/NotificationContext';

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
  mediaUrls: string[];
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
        className="w-full bg-transparent text-xs font-semibold text-slate-100 outline-none"
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
            className="w-full bg-transparent text-xs font-semibold text-slate-100 outline-none [color-scheme:dark]"
          />
        </div>
      )}
    </motion.div>
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

  const updateRawPrompts = useCallback((details: string) => {
    rawPromptsRef.current = details;
    setRawPrompts(details);

    if (details !== parsedPromptsRef.current) {
      setStagedCards([]);
      setActiveCardId(null);
      setMobileWorkspaceView('descriptions');
    }
  }, []);

  const handleTypedPromptChange = useCallback((details: string) => {
    inputSourceRef.current = !details.trim()
      ? 'TYPED'
      : inputSourceRef.current === 'DICTATED'
        ? 'MIXED'
        : inputSourceRef.current;
    updateRawPrompts(details);
  }, [updateRawPrompts]);

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
      inputSourceRef.current = mode === 'append' && currentDraft ? 'MIXED' : 'DICTATED';
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
          description: toEditableValue(dto.description),
          parserMissingFields: Array.isArray(dto.missingFields) ? dto.missingFields : [],
          conflicts: Array.isArray(dto.conflicts) ? dto.conflicts : [],
          appliedAmendments: Array.isArray(dto.appliedAmendments) ? dto.appliedAmendments : [],
          mediaUrls: dto.mediaUrls || [],
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
  }, [showErrorDialog]);

  useEffect(() => {
    if (!initialDetails.trim() || importedDetailsRef.current === initialDetails) return;

    importedDetailsRef.current = initialDetails;
    updateRawPrompts(initialDetails);
    inputSourceRef.current = 'TYPED';
    void parseBatchDetails(initialDetails);
  }, [initialDetails, parseBatchDetails, updateRawPrompts]);

  const handleParseBatch = () => parseBatchDetails(rawPrompts);

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

    setStagedCards((prev) =>
      prev.map((c) =>
        c.id === cardId
          ? {
              ...c,
              localPhotos: [...c.localPhotos, ...compressedFiles],
              localPhotoPreviews: [...c.localPhotoPreviews, ...previews],
              mediaUploadStatus: 'idle',
              mediaUploadProgress: 0,
              mediaUploadMessage: '',
              failedMediaFiles: []
            }
          : c
      )
    );

    if (preparationErrors.length > 0) {
      showErrorDialog({
        title: compressedFiles.length > 0 ? 'Some media could not be added' : 'Media could not be added',
        message: compressedFiles.length > 0
          ? 'Supported files were prepared. Review the files that still need attention.'
          : 'Choose supported media within the displayed limits and try again.',
        details: preparationErrors.join(' • ')
      });
    }
  }, [showErrorDialog]);

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
        URL.revokeObjectURL(newPreviews[photoIdx]);
        newFiles.splice(photoIdx, 1);
        newPreviews.splice(photoIdx, 1);
        return {
          ...c,
          localPhotos: newFiles,
          localPhotoPreviews: newPreviews,
          failedMediaFiles: c.failedMediaFiles.filter((file) => file !== c.localPhotos[photoIdx]),
          mediaUploadStatus: 'idle',
          mediaUploadProgress: 0,
          mediaUploadMessage: ''
        };
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

  const handleConfirmCard = (cardId: string, isConfirmed: boolean) => {
    setStagedCards((prev) => prev.map((card) => {
      if (card.id !== cardId) return card;
      return validateCard({ ...card, isConfirmed });
    }));
  };

  const uploadMediaFilesForCard = async (
    card: StagedProperty,
    propertyId: number,
    files: File[]
  ): Promise<{ failedFiles: File[]; errors: string[] }> => {
    const failedFiles: File[] = [];
    const errors: string[] = [];
    const totalFiles = files.length;

    setStagedCards((current) => current.map((item) => item.id === card.id
      ? {
          ...item,
          mediaUploadStatus: 'preparing',
          mediaUploadProgress: 0,
          mediaUploadMessage: `Preparing ${totalFiles} ${totalFiles === 1 ? 'file' : 'files'}…`,
          failedMediaFiles: []
        }
      : item));

    for (let fileIndex = 0; fileIndex < files.length; fileIndex += 1) {
      const file = files[fileIndex];
      try {
        await propertyService.uploadTaggedMedia(propertyId, file, {
          roomTag: 'GENERAL',
          mediaType: file.type.startsWith('video/') ? 'VIDEO_WALKTHROUGH' : 'IMAGE',
          caption: card.title,
          isPrimaryCover: fileIndex === 0,
          sector: card.sector,
          priceTag: card.rentVal,
          vastuFacing: card.vastuFacing
        }, {
          onProgress: (progress) => {
            const overallProgress = Math.round(
              ((fileIndex + (progress.percent / 100)) / totalFiles) * 100
            );
            const status = progress.stage === 'retrying'
              ? 'retrying'
              : progress.stage === 'preparing'
                ? 'preparing'
                : 'uploading';
            const message = progress.stage === 'retrying'
              ? `Connection interrupted. Retrying file ${fileIndex + 1} of ${totalFiles}…`
              : `Uploading file ${fileIndex + 1} of ${totalFiles}…`;
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
        errors.push(getErrorMessage(error, `${file.name} could not be uploaded.`));
      }
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

    setIsPublishing(true);
    try {
      const result = await uploadMediaFilesForCard(card, card.publishedId, card.failedMediaFiles);
      if (result.failedFiles.length > 0) {
        showErrorDialog({
          title: 'Some media still needs attention',
          message: 'The property remains published. Keep this screen open and retry when the connection is stable.',
          details: result.errors.join(' • ')
        });
      }
    } finally {
      setIsPublishing(false);
    }
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
        mediaUrls: c.mediaUrls,
        adminVerified: c.isConfirmed
      }));

      const res = await propertyService.createBatchProperties(payloadListings);

      const createdResults = Array.isArray(res.createdListings)
        ? res.createdListings.filter((item: any) =>
            Number.isInteger(item?.requestIndex)
            && item.requestIndex >= 0
            && item.requestIndex < validCards.length
            && Number.isFinite(Number(item?.id)))
        : [];
      const uniqueRequestIndexes = new Set(
        createdResults.map((item: any) => item.requestIndex)
      );
      if (createdResults.length !== Number(res.successCount || 0)
          || uniqueRequestIndexes.size !== createdResults.length) {
        throw new Error('The publish response could not be matched safely to the submitted properties.');
      }
      const publishedIdsByCardId = new Map<string, number>();

      // Upload media only after its listing exists, preserving the selected images and videos.
      const mediaUploadErrors: string[] = [];
      for (const created of createdResults) {
        const propId = Number(created.id);
        const card = validCards[created.requestIndex];
        publishedIdsByCardId.set(card.id, propId);
        if (card && card.localPhotos.length > 0) {
          const result = await uploadMediaFilesForCard(card, propId, card.localPhotos);
          if (result.errors.length > 0) {
            mediaUploadErrors.push(`Property ${card.promptIndex}: ${result.errors.join(', ')}`);
          }
        }
      }

      window.dispatchEvent(new Event('pathome_property_published'));
      const publishedCount = res.successCount || 0;
      setStagedCards((current) => current.map((card) => {
        const publishedId = publishedIdsByCardId.get(card.id);
        return publishedId === undefined
          ? card
          : { ...card, publishedId, isValid: false, isConfirmed: true };
      }));
      onSuccess(publishedCount);

      if (mediaUploadErrors.length > 0) {
        showErrorDialog({
          title: 'Property published, but some media needs attention',
          message: 'The listing details were published. Use Retry failed media below; the property will not be created again.',
          details: mediaUploadErrors.join(' • ')
        });
        return;
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

  const readyToPublishCount = stagedCards.filter((card) => card.isValid && !card.publishedId).length;
  const unpublishedCount = stagedCards.filter((card) => !card.publishedId).length;

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
          <div className="relative flex items-start justify-between gap-3 border-b border-slate-800 bg-slate-950/70 px-3 py-3 sm:px-6 sm:py-4">
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
              className="absolute right-3 top-3 flex h-11 w-11 shrink-0 items-center justify-center rounded-xl text-slate-400 hover:text-white hover:bg-slate-800/80 transition-colors sm:right-5"
            >
              <X className="w-5 h-5" />
            </button>
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
                  className="w-full h-full min-h-[220px] p-4 text-xs font-mono bg-slate-950/80 border border-slate-700/80 rounded-2xl text-slate-100 placeholder-slate-500 focus:outline-none focus:border-amber-500/80 focus:ring-2 focus:ring-amber-500/20 transition-all resize-none shadow-inner sm:min-h-[260px]"
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
                            <span className="px-1.5 py-0.2 rounded-md bg-slate-800 text-[10px] text-slate-300 font-mono">
                              {mediaCount} media
                            </span>
                          </button>
                        );
                      })}
                    </div>
                  </div>

                  {/* Staged Cards Grid */}
                  <div className="flex flex-col gap-3.5">
                    {stagedCards.map((card, idx) => {
                      if (activeCardId && card.id !== activeCardId) return null;

                      const palette = COLOR_PALETTES[idx % COLOR_PALETTES.length];
                      const totalMedia = card.localPhotos.length + card.mediaUrls.length;
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
                                className="min-w-0 flex-1 truncate border-b border-transparent bg-transparent text-xs font-bold text-white hover:border-slate-700 focus:border-amber-500 focus:outline-none min-[560px]:max-w-[240px]"
                              />
                            </div>

                            <div className="flex shrink-0 items-center gap-2 self-start min-[560px]:self-auto">
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
                                  <ReviewField label="Furnishing" value={card.furnishingStatus} onValueChange={(value) => handleUpdateField(card.id, 'furnishingStatus', value)} placeholder="Enter furnishing" />
                                  <ReviewField label="Facing" value={card.vastuFacing} onValueChange={(value) => handleUpdateField(card.id, 'vastuFacing', value)} placeholder="Enter facing" />
                                  <AvailabilityEditor
                                    status={card.availabilityStatus}
                                    availableFrom={card.availableFrom}
                                    onChange={(status, availableFrom) => handleAvailabilityChange(card.id, status, availableFrom)}
                                  />
                                  <ReviewField label="Listing status" value={card.status} onValueChange={(value) => handleUpdateField(card.id, 'status', value.toUpperCase())} placeholder="Enter listing status" />
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
                                <ReviewField label="Furnishing" value={card.furnishingStatus} onValueChange={(value) => handleUpdateField(card.id, 'furnishingStatus', value)} placeholder="Enter furnishing" />
                                <ReviewField label="Facing" value={card.vastuFacing} onValueChange={(value) => handleUpdateField(card.id, 'vastuFacing', value)} placeholder="Enter facing" />
                                <AvailabilityEditor
                                  status={card.availabilityStatus}
                                  availableFrom={card.availableFrom}
                                  onChange={(status, availableFrom) => handleAvailabilityChange(card.id, status, availableFrom)}
                                />
                                <ReviewField label="Listing status" value={card.status} onValueChange={(value) => handleUpdateField(card.id, 'status', value.toUpperCase())} placeholder="Enter listing status" />
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

                            {/* Thumbnail Strip */}
                            {totalMedia > 0 ? (
                              <div className="no-scrollbar flex touch-pan-x items-center gap-2 overflow-x-auto py-1">
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
                <span>
                  <strong className="text-white">{readyToPublishCount}</strong> of{' '}
                  <strong className="text-white">{unpublishedCount}</strong> properties ready to publish.
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
                className="flex min-h-11 w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-emerald-500 to-teal-600 px-5 py-2.5 text-center text-xs font-black text-slate-950 shadow-lg shadow-emerald-500/20 transition-all hover:brightness-110 disabled:opacity-50 min-[560px]:w-auto min-[560px]:px-6"
              >
                {isPublishing ? (
                  <span>Publishing listings…</span>
                ) : (
                  <>
                    <CheckCircle2 className="w-4 h-4" />
                  <span>Publish confirmed properties ({readyToPublishCount})</span>
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
                <span className="block text-sm font-black text-white">{readyToPublishCount}/{unpublishedCount}</span>
                <span className="block text-[9px] font-semibold uppercase tracking-wide text-slate-400">ready</span>
              </div>
              <motion.button
                whileTap={{ scale: 0.98 }}
                onClick={handlePublishAll}
                disabled={isPublishing || readyToPublishCount === 0}
                className="flex min-h-12 flex-1 items-center justify-center gap-2 rounded-2xl bg-gradient-to-r from-emerald-500 to-teal-500 px-4 text-sm font-black text-slate-950 shadow-lg shadow-emerald-500/20 transition-all disabled:cursor-not-allowed disabled:opacity-45"
              >
                <CheckCircle2 className="h-4 w-4" />
                {isPublishing ? 'Publishing properties…' : `Publish confirmed (${readyToPublishCount})`}
              </motion.button>
            </div>
          </motion.div>,
          document.body
        )}
      </div>
    </AnimatePresence>
  );
};
