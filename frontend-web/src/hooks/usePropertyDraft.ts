import { useState, useEffect, useRef, useCallback } from 'react';
import {
  draftService,
  DraftSummary,
  DraftDetail,
  DraftMedia,
  getCurrentAdminEmail
} from '../services/draftService';
import { localDraftStorage, LocalDraftRecord } from '../services/localDraftStorage';
import { AutosaveStatus } from '../components/DraftManagementBar';

interface UsePropertyDraftOptions {
  draftType: 'SINGLE' | 'BATCH';
  onRestoreDraft: (payload: any, media: DraftMedia[]) => Promise<void> | void;
  onClearDraftState: () => void;
  initialDraftId?: string | null;
}

const AUTOSAVE_DEBOUNCE_MS = 800;

export const generateDraftId = (type: 'SINGLE' | 'BATCH'): string =>
  `draft-${type.toLowerCase()}-${Date.now().toString(36)}-${Math.random().toString(36).substring(2, 7)}`;

export function usePropertyDraft({
  draftType,
  onRestoreDraft,
  onClearDraftState,
  initialDraftId
}: UsePropertyDraftOptions) {
  const [currentDraftId, setCurrentDraftId] = useState<string | null>(null);
  const [publishedPropertyId, setPublishedPropertyId] = useState<number | null>(null);
  const [draftVersion, setDraftVersion] = useState<number>(1);
  const [autosaveStatus, setAutosaveStatus] = useState<AutosaveStatus>('idle');
  const [lastSavedAt, setLastSavedAt] = useState<Date | null>(null);
  const [conflictMessage, setConflictMessage] = useState<string | null>(null);
  const [draftsList, setDraftsList] = useState<DraftSummary[]>([]);
  const [isLoadingDrafts, setIsLoadingDrafts] = useState<boolean>(false);

  const pendingPayloadRef = useRef<{
    payload: any;
    titleSummary: string;
    itemCount: number;
  } | null>(null);

  const debounceTimerRef = useRef<any>(null);
  const currentDraftIdRef = useRef<string | null>(currentDraftId);
  const publishedPropertyIdRef = useRef<number | null>(publishedPropertyId);
  const draftVersionRef = useRef<number>(draftVersion);
  const isSavingRef = useRef<boolean>(false);
  const initialRestoreDoneRef = useRef<boolean>(false);

  useEffect(() => {
    currentDraftIdRef.current = currentDraftId;
  }, [currentDraftId]);

  useEffect(() => {
    publishedPropertyIdRef.current = publishedPropertyId;
  }, [publishedPropertyId]);

  useEffect(() => {
    draftVersionRef.current = draftVersion;
  }, [draftVersion]);

  const getActiveStorageKey = useCallback(() => {
    const email = getCurrentAdminEmail();
    return `pathome_active_draft_${draftType.toLowerCase()}_${email}`;
  }, [draftType]);

  // Synchronously ensure a valid draft identity exists before any operation
  const ensureDraftId = useCallback((): string => {
    if (currentDraftIdRef.current) {
      return currentDraftIdRef.current;
    }
    const newId = generateDraftId(draftType);
    currentDraftIdRef.current = newId;
    setCurrentDraftId(newId);
    try {
      localStorage.setItem(getActiveStorageKey(), newId);
    } catch {}
    return newId;
  }, [draftType, getActiveStorageKey]);

  // Load drafts summary list
  const refreshDraftsList = useCallback(async () => {
    const email = getCurrentAdminEmail();
    setIsLoadingDrafts(true);
    try {
      const serverList = await draftService.listDrafts();
      setDraftsList(serverList);
    } catch {
      // Offline fallback: load from local storage
      const localList = await localDraftStorage.list(email);
      const mapped: DraftSummary[] = localList.map((l) => ({
        draftId: l.draftId,
        draftType: l.draftType,
        status: 'DRAFT',
        titleSummary: l.titleSummary,
        itemCount: l.itemCount,
        version: l.version,
        mediaCount: 0,
        createdAt: new Date(l.updatedAt).toISOString(),
        updatedAt: new Date(l.updatedAt).toISOString()
      }));
      setDraftsList(mapped);
    } finally {
      setIsLoadingDrafts(false);
    }
  }, []);

  // Flush any pending un-saved changes immediately to local storage and server
  const flushPendingSave = useCallback(async () => {
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }

    const pending = pendingPayloadRef.current;
    if (!pending) return;

    const email = getCurrentAdminEmail();
    const draftId = currentDraftIdRef.current || generateDraftId(draftType);

    if (!currentDraftIdRef.current) {
      currentDraftIdRef.current = draftId;
      setCurrentDraftId(draftId);
      try {
        localStorage.setItem(getActiveStorageKey(), draftId);
      } catch {}
    }

    // Save locally immediately
    await localDraftStorage.save(email, {
      draftId,
      adminEmail: email,
      draftType,
      titleSummary: pending.titleSummary,
      itemCount: pending.itemCount,
      version: draftVersionRef.current,
      payload: pending.payload,
      updatedAt: Date.now()
    });

    // Save to server
    try {
      const res = await draftService.saveDraft({
        draftId,
        draftType,
        titleSummary: pending.titleSummary,
        itemCount: pending.itemCount,
        version: draftVersionRef.current,
        payload: JSON.stringify(pending.payload)
      });
      setDraftVersion(res.version);
      draftVersionRef.current = res.version;
      setLastSavedAt(new Date());
      setAutosaveStatus('saved');
      pendingPayloadRef.current = null;
    } catch (err: any) {
      if (err?.status === 409) {
        setAutosaveStatus('conflict');
        setConflictMessage(err.message || 'Draft was modified elsewhere.');
      } else {
        setAutosaveStatus('offline');
      }
    }
  }, [draftType, getActiveStorageKey]);

  // Flush on page unload
  useEffect(() => {
    const handleBeforeUnload = () => {
      void flushPendingSave();
    };
    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => {
      window.removeEventListener('beforeunload', handleBeforeUnload);
      if (debounceTimerRef.current) {
        clearTimeout(debounceTimerRef.current);
      }
      void flushPendingSave();
    };
  }, [flushPendingSave]);

  // Initial mount: load available drafts for dropdown without auto-populating editor.
  // A saved draft is restored ONLY if an explicit initialDraftId is provided.
  useEffect(() => {
    if (initialRestoreDoneRef.current) return;
    initialRestoreDoneRef.current = true;

    const init = async () => {
      // 1. Fetch available drafts so the Drafts dropdown is populated with existing drafts
      await refreshDraftsList();

      // 2. Clear active pointer so fresh property entry allocates an independent draft ID
      try {
        localStorage.removeItem(getActiveStorageKey());
      } catch {}

      // 3. Ensure editor starts in clean, detached state
      currentDraftIdRef.current = null;
      setCurrentDraftId(null);
      setDraftVersion(1);
      draftVersionRef.current = 1;
      setAutosaveStatus('idle');
      setLastSavedAt(null);
      setConflictMessage(null);
      pendingPayloadRef.current = null;

      // 4. Restore ONLY if an explicit draft identity was provided (e.g. batch continue from parent)
      if (initialDraftId) {
        await loadDraft(initialDraftId);
      }
    };

    void init();
  }, [getActiveStorageKey, initialDraftId, refreshDraftsList]);

  // Schedule debounced autosave
  const scheduleAutosave = useCallback(
    (payload: any, titleSummary: string, itemCount: number = 1) => {
      pendingPayloadRef.current = { payload, titleSummary, itemCount };

      let draftId = currentDraftIdRef.current;
      if (!draftId) {
        draftId = generateDraftId(draftType);
        currentDraftIdRef.current = draftId;
        setCurrentDraftId(draftId);
        try {
          localStorage.setItem(getActiveStorageKey(), draftId);
        } catch {}
      }

      const email = getCurrentAdminEmail();

      // Immediately persist to IndexedDB/localStorage for crash protection
      void localDraftStorage.save(email, {
        draftId,
        adminEmail: email,
        draftType,
        titleSummary,
        itemCount,
        version: draftVersionRef.current,
        payload,
        updatedAt: Date.now()
      });

      setAutosaveStatus('saving');

      if (debounceTimerRef.current) {
        clearTimeout(debounceTimerRef.current);
      }

      debounceTimerRef.current = setTimeout(async () => {
        if (isSavingRef.current) return;
        isSavingRef.current = true;

        try {
          const res = await draftService.saveDraft({
            draftId: draftId!,
            draftType,
            titleSummary,
            itemCount,
            version: draftVersionRef.current,
            payload: JSON.stringify(payload)
          });
          setDraftVersion(res.version);
          draftVersionRef.current = res.version;
          setLastSavedAt(new Date());
          setAutosaveStatus('saved');
          pendingPayloadRef.current = null;
          void refreshDraftsList();
        } catch (err: any) {
          if (err?.status === 409) {
            setAutosaveStatus('conflict');
            setConflictMessage(err.message || 'Draft updated elsewhere.');
          } else {
            setAutosaveStatus('offline');
          }
        } finally {
          isSavingRef.current = false;
        }
      }, AUTOSAVE_DEBOUNCE_MS);
    },
    [draftType, getActiveStorageKey, refreshDraftsList]
  );

  // Switch to another draft
  const loadDraft = useCallback(
    async (targetDraftId: string) => {
      // 1. Flush current draft if pending
      await flushPendingSave();

      // 2. Clear current editor state first to prevent merging fields
      onClearDraftState();

      const email = getCurrentAdminEmail();
      // 3. Set active draft identity
      currentDraftIdRef.current = targetDraftId;
      setCurrentDraftId(targetDraftId);
      try {
        localStorage.setItem(getActiveStorageKey(), targetDraftId);
      } catch {}

      try {
        const detail = await draftService.getDraft(targetDraftId);
        if (detail) {
          // PUBLISHED tombstone protection: never wipe the active editor with empty tombstone payload
          if (detail.status === 'PUBLISHED' || (!detail.payload || detail.payload === '{}')) {
            try {
              localStorage.removeItem(getActiveStorageKey());
            } catch {}
            await localDraftStorage.remove(email, targetDraftId);
            await refreshDraftsList();
            return;
          }

          currentDraftIdRef.current = detail.draftId;
          setCurrentDraftId(detail.draftId);
          setDraftVersion(detail.version);
          draftVersionRef.current = detail.version;
          const propId = detail.publishedPropertyId || null;
          setPublishedPropertyId(propId);
          publishedPropertyIdRef.current = propId;
          setLastSavedAt(new Date(detail.updatedAt));
          setAutosaveStatus('saved');
          const parsed = JSON.parse(detail.payload);
          await onRestoreDraft(parsed, detail.media || []);
          await refreshDraftsList();
          return;
        }
      } catch {
        const local = await localDraftStorage.get(email, targetDraftId);
        if (local && local.payload && local.payload !== '{}') {
          currentDraftIdRef.current = local.draftId;
          setCurrentDraftId(local.draftId);
          setDraftVersion(local.version);
          draftVersionRef.current = local.version;
          setPublishedPropertyId(null);
          publishedPropertyIdRef.current = null;
          setLastSavedAt(new Date(local.updatedAt));
          setAutosaveStatus('offline');
          await onRestoreDraft(local.payload, []);
          await refreshDraftsList();
        }
      }
    },
    [flushPendingSave, getActiveStorageKey, onClearDraftState, onRestoreDraft, refreshDraftsList]
  );

  // Start a new blank draft
  const startNewDraft = useCallback(async () => {
    // 1. Flush/save current meaningful draft first if needed
    await flushPendingSave();

    // 2. Detach and reset to clean empty state (do not pre-create draft in DB)
    currentDraftIdRef.current = null;
    setCurrentDraftId(null);
    setPublishedPropertyId(null);
    publishedPropertyIdRef.current = null;
    setDraftVersion(1);
    draftVersionRef.current = 1;
    setAutosaveStatus('idle');
    setLastSavedAt(null);
    setConflictMessage(null);
    pendingPayloadRef.current = null;
    try {
      localStorage.removeItem(getActiveStorageKey());
    } catch {}

    // 3. Clear editor state for the new property
    onClearDraftState();

    // 4. Refresh drafts list so user sees existing drafts
    await refreshDraftsList();
  }, [flushPendingSave, getActiveStorageKey, onClearDraftState, refreshDraftsList]);

  // Discard a draft explicitly
  const discardDraft = useCallback(
    async (draftIdToDiscard: string) => {
      const email = getCurrentAdminEmail();
      try {
        await draftService.discardDraft(draftIdToDiscard);
      } catch {}
      await localDraftStorage.remove(email, draftIdToDiscard);

      // Only reset active draft identity and editor if the discarded draft was active
      if (currentDraftIdRef.current === draftIdToDiscard) {
        currentDraftIdRef.current = null;
        setCurrentDraftId(null);
        setPublishedPropertyId(null);
        publishedPropertyIdRef.current = null;
        setDraftVersion(1);
        draftVersionRef.current = 1;
        setAutosaveStatus('idle');
        setLastSavedAt(null);
        setConflictMessage(null);
        pendingPayloadRef.current = null;
        try {
          localStorage.removeItem(getActiveStorageKey());
        } catch {}
        onClearDraftState();
      }

      await refreshDraftsList();
    },
    [getActiveStorageKey, onClearDraftState, refreshDraftsList]
  );

  // Handle successful publication
  const onPublishSuccess = useCallback(async (listingId?: number) => {
    const draftId = currentDraftIdRef.current;
    const finalListingId = listingId || publishedPropertyIdRef.current || undefined;
    if (!draftId) return;

    const email = getCurrentAdminEmail();
    try {
      await draftService.markPublished(draftId, finalListingId);
    } catch {}
    await localDraftStorage.remove(email, draftId);

    currentDraftIdRef.current = null;
    setCurrentDraftId(null);
    setPublishedPropertyId(null);
    publishedPropertyIdRef.current = null;
    setDraftVersion(1);
    draftVersionRef.current = 1;
    setAutosaveStatus('idle');
    setLastSavedAt(null);
    pendingPayloadRef.current = null;
    try {
      localStorage.removeItem(getActiveStorageKey());
    } catch {}
    await refreshDraftsList();
  }, [getActiveStorageKey, refreshDraftsList]);

  // Handle batch publication reconciliation
  const onBatchPublished = useCallback(
    async (publishedCardIds: string[]) => {
      const draftId = currentDraftIdRef.current;
      if (!draftId) return;

      try {
        const remaining = await draftService.reconcileBatch(draftId, publishedCardIds);
        if (!remaining) {
          // Fully published
          await onPublishSuccess();
        } else {
          // Partial success: update remaining version and refresh
          setDraftVersion(remaining.version);
          draftVersionRef.current = remaining.version;
          setLastSavedAt(new Date());
          await refreshDraftsList();
        }
      } catch {
        // Non-fatal
      }
    },
    [onPublishSuccess, refreshDraftsList]
  );

  // Overwrite conflict with local edits
  const resolveConflictKeepLocal = useCallback(async () => {
    const pending = pendingPayloadRef.current;
    const draftId = currentDraftIdRef.current;
    if (!pending || !draftId) return;

    try {
      // Force fetch server to get latest version counter
      const latest = await draftService.getDraft(draftId);
      const nextVersion = (latest?.version || 1) + 1;
      const res = await draftService.saveDraft({
        draftId,
        draftType,
        titleSummary: pending.titleSummary,
        itemCount: pending.itemCount,
        version: nextVersion,
        payload: JSON.stringify(pending.payload)
      });
      setDraftVersion(res.version);
      draftVersionRef.current = res.version;
      setLastSavedAt(new Date());
      setAutosaveStatus('saved');
      setConflictMessage(null);
    } catch {
      setAutosaveStatus('offline');
    }
  }, [draftType]);

  // Overwrite local with server version
  const resolveConflictReloadServer = useCallback(async () => {
    const draftId = currentDraftIdRef.current;
    if (!draftId) return;
    setConflictMessage(null);
    await loadDraft(draftId);
  }, [loadDraft]);

  return {
    currentDraftId,
    publishedPropertyId,
    draftVersion,
    autosaveStatus,
    lastSavedAt,
    conflictMessage,
    draftsList,
    isLoadingDrafts,
    ensureDraftId,
    scheduleAutosave,
    flushPendingSave,
    loadDraft,
    startNewDraft,
    discardDraft,
    onPublishSuccess,
    onBatchPublished,
    resolveConflictKeepLocal,
    resolveConflictReloadServer,
    refreshDraftsList
  };
}
