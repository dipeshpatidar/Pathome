import { useState, useEffect, useRef, useCallback } from 'react';
import {
  draftService,
  DraftSummary,
  DraftDetail,
  DraftMedia,
  getCurrentAdminEmail
} from '../services/draftService';
import { API_ROOT_URL } from '../config/endpoints';
import { localDraftStorage, LocalDraftRecord } from '../services/localDraftStorage';
import { createApiRequestError, notifySessionExpired } from '../services/apiError';
import { AutosaveStatus } from '../components/DraftManagementBar';
import {
  UploaderSession,
  UploaderOperationLifetime,
  captureUploaderSession,
  isUploaderSessionActive,
  matchesUploaderStoredCredential,
  sameOperationsCredential,
  subscribeUploaderSession,
  assertUploaderSessionActive
} from '../utils/uploaderSession';

export interface CompletedListingSummary {
  cardId?: string;
  listingId?: number;
  title?: string;
}

export interface LoadDraftResult {
  status: 'RESTORED' | 'ALREADY_PUBLISHED' | 'NOT_FOUND' | 'ERROR';
  detail?: DraftDetail | null;
  completedCount?: number;
  completedListings?: CompletedListingSummary[];
}

export interface UploaderOperationGuard extends UploaderOperationLifetime {
  /** Unique object identity for this uploader operation lifetime. Never a reusable numeric flag. */
  readonly operationId: symbol;
  readonly session: UploaderSession;
  readonly draftType: 'SINGLE' | 'BATCH';
  readonly draftIdAtDispatch: string | null;
  readonly emailAtDispatch: string;
}

interface UsePropertyDraftOptions {
  draftType: 'SINGLE' | 'BATCH';
  onRestoreDraft: (
    payload: any,
    media: DraftMedia[],
    session: UploaderSession,
    operation: UploaderOperationGuard
  ) => Promise<void> | void;
  onClearDraftState: () => void;
  initialDraftId?: string | null;
  onDraftAlreadyPublished?: (
    detail: DraftDetail,
    completedCount: number,
    completedListings: CompletedListingSummary[]
  ) => void;
}

const AUTOSAVE_DEBOUNCE_MS = 800;

export const generateDraftId = (type: 'SINGLE' | 'BATCH'): string =>
  `draft-${type.toLowerCase()}-${Date.now().toString(36)}-${Math.random().toString(36).substring(2, 7)}`;

export interface BulkDiscardResult {
  succeeded: string[];
  failed: string[];
  notAttempted: string[];
  outcome: DiscardOutcome;
  operationId: symbol;
  isOwnerActive: boolean;
  canFinalize: boolean;
  isAuthError?: boolean;
}

export type DiscardOutcome =
  | 'succeeded'
  | 'permission-denied'
  | 'session-expired'
  | 'cancelled'
  | 'failed';

export interface DiscardDraftResult {
  outcome: DiscardOutcome;
  operationId: symbol;
  isOwnerActive: boolean;
  canFinalize: boolean;
}

export function usePropertyDraft({
  draftType,
  onRestoreDraft,
  onClearDraftState,
  initialDraftId,
  onDraftAlreadyPublished
}: UsePropertyDraftOptions) {
  const [currentDraftId, setCurrentDraftId] = useState<string | null>(initialDraftId || null);
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
  const pendingSessionRef = useRef<UploaderSession | null>(null);

  const debounceTimerRef = useRef<any>(null);
  const currentDraftIdRef = useRef<string | null>(initialDraftId || null);
  const publishedPropertyIdRef = useRef<number | null>(publishedPropertyId);
  const draftVersionRef = useRef<number>(draftVersion);
  const isSavingRef = useRef<boolean>(false);
  const conflictRetryCountRef = useRef<number>(0);
  const initialRestoreDoneRef = useRef<boolean>(false);
  const isMountedRef = useRef<boolean>(true);
  const deniedLocalDraftKeysRef = useRef<Set<string>>(new Set());
  const isSuspendedRef = useRef<boolean>(false);
  const activeOperationEpochRef = useRef<symbol | null>(Symbol('uploader-operation'));
  const operationAbortControllerRef = useRef<AbortController | null>(null);
  if (!operationAbortControllerRef.current) {
    operationAbortControllerRef.current = new AbortController();
  }
  const latestProtectedSessionRef = useRef<UploaderSession | null>(null);
  const currentSessionExpirySessionRef = useRef<UploaderSession | null>(null);
  const terminalDeniedSessionRef = useRef<UploaderSession | null>(null);
  const terminalDenialMessageRef = useRef<string | null>(null);
  const onClearDraftStateRef = useRef(onClearDraftState);
  onClearDraftStateRef.current = onClearDraftState;
  const refreshDraftsListRef = useRef<
    ((capturedSession?: UploaderSession | null) => Promise<void>) | null
  >(null);

  const cancelPendingProtectedWork = useCallback(() => {
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }
    pendingPayloadRef.current = null;
    pendingSessionRef.current = null;
    isSavingRef.current = false;
    isSuspendedRef.current = true;
    setAutosaveStatus('idle');
    setConflictMessage(null);
    setDraftsList([]);
    setIsLoadingDrafts(false);
    onClearDraftStateRef.current();
  }, []);

  useEffect(() => {
    isMountedRef.current = true;
    if (!activeOperationEpochRef.current || !operationAbortControllerRef.current
        || operationAbortControllerRef.current.signal.aborted) {
      operationAbortControllerRef.current = new AbortController();
      activeOperationEpochRef.current = Symbol('uploader-operation');
    }
    const unsubscribe = subscribeUploaderSession((reason) => {
      if (reason === 'session-expired') {
        // The token-bound 401 notification invalidates the shared generation before the request
        // rejects. Preserve the exact retired session so its own caller can still identify a genuine
        // current expiry without treating any later inactive session as expired.
        currentSessionExpirySessionRef.current = latestProtectedSessionRef.current;
      }
      latestProtectedSessionRef.current = null;
      operationAbortControllerRef.current?.abort(reason);
      activeOperationEpochRef.current = null;
      terminalDeniedSessionRef.current = null;
      terminalDenialMessageRef.current = null;
      cancelPendingProtectedWork();
      if (reason === 'session-expired') return;
      const replacement = captureUploaderSession();
      if (replacement && isUploaderSessionActive(replacement)) {
        // The uploader component is keyed by User.id and survives same-User token rotation.
        // Retire the old generation, then make the replacement generation eligible for autosave.
        operationAbortControllerRef.current = new AbortController();
        activeOperationEpochRef.current = Symbol('uploader-operation');
        isSuspendedRef.current = false;
      }
    });
    return () => {
      isMountedRef.current = false;
      latestProtectedSessionRef.current = null;
      operationAbortControllerRef.current?.abort('unmounted');
      activeOperationEpochRef.current = null;
      unsubscribe();
    };
  }, [cancelPendingProtectedWork]);

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

  const deniedLocalDraftKey = useCallback((
    email: string,
    draftId: string,
    session: UploaderSession
  ) => `${session.identity}:${email}:${draftId}`, []);

  const isCurrentSessionDenial = useCallback((
    error: any,
    session: UploaderSession
  ): boolean => {
    if (error?.status !== 401 && error?.status !== 403) return false;
    return isUploaderSessionActive(session)
      || (error.status === 401 && matchesUploaderStoredCredential(session));
  }, []);

  const classifyDiscardFailure = useCallback((
    error: any,
    session: UploaderSession,
    operation: UploaderOperationGuard
  ): DiscardOutcome => {
    if (error?.status === 401) {
      const currentSessionOwnsExpiry = isUploaderSessionActive(session);
      const belongsToCurrentExpiry = currentSessionOwnsExpiry
        || sameOperationsCredential(currentSessionExpirySessionRef.current, session);
      if (!belongsToCurrentExpiry) return 'cancelled';
      // The discard request adapter prevents the shared HTTP helper from notifying for this operation.
      // Notify once here, before retirement, so concurrent sibling 401s cannot issue duplicate expiry.
      if (currentSessionOwnsExpiry) notifySessionExpired(session.token);
      return 'session-expired';
    }
    if (error?.status === 403) {
      return isCurrentSessionDenial(error, session) ? 'permission-denied' : 'cancelled';
    }
    return operation.isActive() ? 'failed' : 'cancelled';
  }, [isCurrentSessionDenial]);

  const captureProtectedOperation = useCallback((
    capturedSession?: UploaderSession | null
  ): UploaderOperationGuard | null => {
    const session = capturedSession || captureUploaderSession();
    const operationId = activeOperationEpochRef.current;
    if (
      !session
      || !operationId
      || !operationAbortControllerRef.current
      || operationAbortControllerRef.current.signal.aborted
      || isSuspendedRef.current
      || terminalDeniedSessionRef.current
      || !isUploaderSessionActive(session)
    ) {
      return null;
    }

    const operationController = operationAbortControllerRef.current;
    const operationSignal = operationController.signal;
    latestProtectedSessionRef.current = session;
    return Object.freeze({
      operationId,
      session,
      signal: operationSignal,
      draftType,
      draftIdAtDispatch: currentDraftIdRef.current,
      emailAtDispatch: getCurrentAdminEmail(),
      isActive: () => Boolean(
        isMountedRef.current
        && activeOperationEpochRef.current === operationId
        && operationAbortControllerRef.current === operationController
        && !operationSignal.aborted
        && !terminalDeniedSessionRef.current
        && isUploaderSessionActive(session)
      )
    });
  }, [draftType]);

  const isProtectedOperationActive = useCallback(
    (operation: UploaderOperationGuard | null | undefined): boolean =>
      Boolean(operation?.isActive()),
    []
  );

  const discardDraftRequest = useCallback(async (
    draftId: string,
    session: UploaderSession,
    signal: AbortSignal
  ): Promise<void> => {
    const response = await fetch(`${API_ROOT_URL}/admin/drafts/${encodeURIComponent(draftId)}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${session.token}` },
      signal
    });
    if (!response.ok && response.status !== 404) {
      // The discard operation owns its single 401 notification. Passing a deliberately retired token
      // to the shared error factory preserves its status/message contract without emitting from a
      // stale concurrent response, then classifyDiscardFailure notifies only the current generation.
      throw await createApiRequestError(
        response,
        'Unable to discard draft.',
        false,
        `retired-uploader-operation-${session.generation}`
      );
    }
    assertUploaderSessionActive(session);
  }, []);

  const persistLocalDraft = useCallback(async (
    operation: UploaderOperationGuard,
    record: LocalDraftRecord
  ): Promise<void> => {
    const denialKey = deniedLocalDraftKey(
      operation.emailAtDispatch,
      record.draftId,
      operation.session
    );
    if (!operation.isActive() || deniedLocalDraftKeysRef.current.has(denialKey)) return;
    await localDraftStorage.save(operation.emailAtDispatch, record, () =>
      operation.isActive()
      && !deniedLocalDraftKeysRef.current.has(denialKey)
    );
  }, [deniedLocalDraftKey]);

  const withdrawDeniedProtectedDraft = useCallback((
    error: any,
    session: UploaderSession,
    fallbackMessage: string
  ): boolean => {
    if (!isCurrentSessionDenial(error, session)) return false;

    const firstDenial = !sameOperationsCredential(terminalDeniedSessionRef.current, session);
    terminalDeniedSessionRef.current = terminalDeniedSessionRef.current || session;
    terminalDenialMessageRef.current = firstDenial
      ? error?.message || fallbackMessage
      : terminalDenialMessageRef.current || error?.message || fallbackMessage;
    // Retiring the local operation lifetime is synchronous and precedes every cleanup or state update.
    operationAbortControllerRef.current?.abort('permission-denied');
    activeOperationEpochRef.current = null;
    const draftId = currentDraftIdRef.current;
    const email = getCurrentAdminEmail();
    if (draftId) {
      deniedLocalDraftKeysRef.current.add(deniedLocalDraftKey(email, draftId, session));
    }
    cancelPendingProtectedWork();
    try {
      localStorage.removeItem(getActiveStorageKey());
    } catch {}
    if (draftId) {
      void localDraftStorage.removeIf(email, draftId, () =>
        Boolean(terminalDeniedSessionRef.current)
        && sameOperationsCredential(terminalDeniedSessionRef.current, session)
        && matchesUploaderStoredCredential(session)
      );
    }
    currentDraftIdRef.current = null;
    setCurrentDraftId(null);
    setPublishedPropertyId(null);
    publishedPropertyIdRef.current = null;
    setDraftVersion(1);
    draftVersionRef.current = 1;
    setLastSavedAt(null);
    setAutosaveStatus('denied');
    setConflictMessage(terminalDenialMessageRef.current);
    return true;
  }, [
    cancelPendingProtectedWork,
    deniedLocalDraftKey,
    getActiveStorageKey,
    isCurrentSessionDenial
  ]);

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
  }, [cancelPendingProtectedWork, draftType, getActiveStorageKey]);

  // Load drafts summary list
  const refreshDraftsList = useCallback(async (capturedSession?: UploaderSession | null) => {
    const operation = captureProtectedOperation(capturedSession);
    if (!operation) return;
    const session = operation.session;
    setIsLoadingDrafts(true);
    try {
      const serverList = await draftService.listDrafts(session);
      if (!operation.isActive()) return;
      setDraftsList(serverList);
    } catch (error: any) {
      if (withdrawDeniedProtectedDraft(
        error,
        session,
        'You no longer have permission to access saved drafts.'
      )) return;
      if (!operation.isActive()) return;
      // Only a genuine transport/server failure while the same session remains active may use recovery.
      const localList = await localDraftStorage.list(operation.emailAtDispatch);
      if (!operation.isActive()) return;
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
      if (operation.isActive()) setIsLoadingDrafts(false);
    }
  }, [captureProtectedOperation, withdrawDeniedProtectedDraft]);
  refreshDraftsListRef.current = refreshDraftsList;

  // Flush any pending un-saved changes immediately to local storage and server
  const flushPendingSave = useCallback(async () => {
    if (isSuspendedRef.current) return;
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }

    const pending = pendingPayloadRef.current;
    if (!pending) return;
    const session = pendingSessionRef.current || captureUploaderSession();
    if (!session || !isUploaderSessionActive(session)) {
      pendingPayloadRef.current = null;
      pendingSessionRef.current = null;
      return;
    }

    const operation = captureProtectedOperation(session);
    if (!operation) return;
    const email = operation.emailAtDispatch;
    const draftId = currentDraftIdRef.current || generateDraftId(draftType);

    if (!currentDraftIdRef.current) {
      currentDraftIdRef.current = draftId;
      setCurrentDraftId(draftId);
      try {
        localStorage.setItem(getActiveStorageKey(), draftId);
      } catch {}
    }

    // Save locally immediately
    await persistLocalDraft(operation, {
      draftId,
      adminEmail: email,
      draftType,
      titleSummary: pending.titleSummary,
      itemCount: pending.itemCount,
      version: draftVersionRef.current,
      payload: pending.payload,
      updatedAt: Date.now()
    });
    if (!operation.isActive()) return;

    // Save to server
    try {
      const res = await draftService.saveDraft({
        draftId,
        draftType,
        titleSummary: pending.titleSummary,
        itemCount: pending.itemCount,
        version: draftVersionRef.current,
        payload: JSON.stringify(pending.payload)
      }, session);
      if (!operation.isActive()) return;
      setDraftVersion(res.version);
      draftVersionRef.current = res.version;
      setLastSavedAt(new Date());
      setAutosaveStatus('saved');
      pendingPayloadRef.current = null;
      pendingSessionRef.current = null;
    } catch (err: any) {
      if (withdrawDeniedProtectedDraft(
        err,
        session,
        'You no longer have permission to save this draft.'
      )) return;
      if (!operation.isActive()) return;
      if (err?.status === 409) {
        try {
          const serverDraft = await draftService.getDraft(draftId, session);
          if (!operation.isActive()) return;
          if (serverDraft && typeof serverDraft.version === 'number') {
            setDraftVersion(serverDraft.version);
            draftVersionRef.current = serverDraft.version;
          }
        } catch (recoveryError: any) {
          if (withdrawDeniedProtectedDraft(
            recoveryError,
            session,
            'You no longer have permission to save this draft.'
          )) return;
          if (!operation.isActive()) return;
        }
        if (!operation.isActive()) return;
        setAutosaveStatus('conflict');
        setConflictMessage(err.message || 'Draft was modified elsewhere.');
      } else {
        setAutosaveStatus('offline');
      }
    }
  }, [
    draftType,
    captureProtectedOperation,
    getActiveStorageKey,
    persistLocalDraft,
    withdrawDeniedProtectedDraft
  ]);

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
        debounceTimerRef.current = null;
      }
      // Component cleanup can run after sign-out or an account switch. It must never obtain the next
      // account's credential and send the unmounted account's payload.
      pendingPayloadRef.current = null;
      pendingSessionRef.current = null;
      isSavingRef.current = false;
    };
  }, [flushPendingSave]);

  // Initial mount: load available drafts for dropdown without auto-populating editor.
  // A saved draft is restored ONLY if an explicit initialDraftId is provided.
  useEffect(() => {
    if (initialRestoreDoneRef.current) return;
    initialRestoreDoneRef.current = true;

    // Synchronously clean active pointer if this is a brand new session without explicit initial draft
    if (!initialDraftId) {
      try {
        localStorage.removeItem(getActiveStorageKey());
      } catch {}
    }

    const init = async () => {
      const session = captureUploaderSession();
      if (!session || !isUploaderSessionActive(session)) return;
      // 1. Fetch available drafts so the Drafts dropdown is populated with existing drafts
      await refreshDraftsList(session);

      if (!isMountedRef.current || !isUploaderSessionActive(session)) return;

      // 2. Restore ONLY if an explicit draft identity was provided (e.g. batch continue from parent)
      if (initialDraftId) {
        await loadDraft(initialDraftId, false, session);
      }
    };

    void init();
  }, [getActiveStorageKey, initialDraftId, refreshDraftsList]);

  // Schedule debounced autosave
  const scheduleAutosave = useCallback(
    (payload: any, titleSummary: string, itemCount: number = 1, capturedSession?: UploaderSession | null) => {
      if (isSuspendedRef.current) {
        return;
      }
      const session = capturedSession || captureUploaderSession();
      if (!session || !isUploaderSessionActive(session)) return;
      const operation = captureProtectedOperation(session);
      if (!operation) return;
      pendingPayloadRef.current = { payload, titleSummary, itemCount };
      pendingSessionRef.current = session;

      let draftId = currentDraftIdRef.current;
      if (!draftId) {
        draftId = generateDraftId(draftType);
        currentDraftIdRef.current = draftId;
        setCurrentDraftId(draftId);
        try {
          localStorage.setItem(getActiveStorageKey(), draftId);
        } catch {}
      }

      const email = operation.emailAtDispatch;

      // Immediately persist to IndexedDB/localStorage for crash protection
      void persistLocalDraft(operation, {
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
        if (!isMountedRef.current || isSuspendedRef.current) return;
        if (!operation.isActive()) {
          cancelPendingProtectedWork();
          return;
        }
        if (isSavingRef.current) {
          // If a save is currently in flight, reschedule so latest pending changes are not dropped
          debounceTimerRef.current = setTimeout(() => {
            if (isMountedRef.current && pendingPayloadRef.current) {
              scheduleAutosave(
                pendingPayloadRef.current.payload,
                pendingPayloadRef.current.titleSummary,
                pendingPayloadRef.current.itemCount,
                session
              );
            }
          }, 300);
          return;
        }
        isSavingRef.current = true;

        const payloadToSave = pendingPayloadRef.current;
        if (!payloadToSave) {
          isSavingRef.current = false;
          return;
        }

        try {
          const res = await draftService.saveDraft({
            draftId: draftId!,
            draftType,
            titleSummary: payloadToSave.titleSummary,
            itemCount: payloadToSave.itemCount,
            version: draftVersionRef.current,
            payload: JSON.stringify(payloadToSave.payload)
          }, session);
          if (!isMountedRef.current || !operation.isActive()) return;
          setDraftVersion(res.version);
          draftVersionRef.current = res.version;
          setLastSavedAt(new Date());
          setAutosaveStatus('saved');
          conflictRetryCountRef.current = 0;
          if (pendingPayloadRef.current === payloadToSave) {
            pendingPayloadRef.current = null;
            pendingSessionRef.current = null;
          }
          void refreshDraftsList(session);
        } catch (err: any) {
          if (!isMountedRef.current) return;
          if (withdrawDeniedProtectedDraft(
            err,
            session,
            'You no longer have permission to save this draft.'
          )) return;
          if (!operation.isActive()) return;
          if (err?.status === 409) {
            // Safe authoritative version resynchronization (bounded to 1 retry, preserving local unsaved state)
            let resynced = false;
            if (conflictRetryCountRef.current < 1) {
              conflictRetryCountRef.current += 1;
              try {
                const serverDraft = await draftService.getDraft(draftId!, session);
                if (!operation.isActive()) return;
                if (serverDraft && typeof serverDraft.version === 'number') {
                  setDraftVersion(serverDraft.version);
                  draftVersionRef.current = serverDraft.version;

                  // Retry saving latest local payload with authoritative version
                  const retryRes = await draftService.saveDraft({
                    draftId: draftId!,
                    draftType,
                    titleSummary: payloadToSave.titleSummary,
                    itemCount: payloadToSave.itemCount,
                    version: serverDraft.version,
                    payload: JSON.stringify(payloadToSave.payload)
                  }, session);
                  if (!isMountedRef.current || !operation.isActive()) return;
                  setDraftVersion(retryRes.version);
                  draftVersionRef.current = retryRes.version;
                  setLastSavedAt(new Date());
                  setAutosaveStatus('saved');
                  setConflictMessage(null);
                  conflictRetryCountRef.current = 0;
                  if (pendingPayloadRef.current === payloadToSave) {
                    pendingPayloadRef.current = null;
                    pendingSessionRef.current = null;
                  }
                  void refreshDraftsList(session);
                  resynced = true;
                }
              } catch (retryErr: any) {
                if (withdrawDeniedProtectedDraft(
                  retryErr,
                  session,
                  'You no longer have permission to save this draft.'
                )) return;
                if (!operation.isActive()) return;
                if (retryErr?.status !== 409) {
                  setAutosaveStatus('offline');
                  return;
                }
              }
            }

            if (!operation.isActive()) return;
            if (!resynced) {
              setAutosaveStatus('conflict');
              setConflictMessage(err.message || 'Draft was modified elsewhere.');
              conflictRetryCountRef.current = 0;
            }
          } else {
            if (!operation.isActive()) return;
            setAutosaveStatus('offline');
          }
        } finally {
          if (operation.isActive()) {
            isSavingRef.current = false;
          }
        }
      }, AUTOSAVE_DEBOUNCE_MS);
    },
    [
      cancelPendingProtectedWork,
      captureProtectedOperation,
      draftType,
      getActiveStorageKey,
      persistLocalDraft,
      refreshDraftsList,
      withdrawDeniedProtectedDraft
    ]
  );

  // Switch to another draft
  const loadDraft = useCallback(
    async (
      targetDraftId: string,
      shouldClearState: boolean = true,
      capturedSession?: UploaderSession | null
    ): Promise<LoadDraftResult> => {
      const session = capturedSession || captureUploaderSession();
      if (!session || !isUploaderSessionActive(session)) return { status: 'ERROR' };
      const operation = captureProtectedOperation(session);
      if (!operation) return { status: 'ERROR' };
      // 1. Flush current draft if pending
      await flushPendingSave();
      if (!operation.isActive()) return { status: 'ERROR' };

      // 2. Clear current editor state first if requested to prevent merging fields
      if (shouldClearState) {
        onClearDraftState();
      }

      const email = operation.emailAtDispatch;
      // 3. Set active draft identity
      currentDraftIdRef.current = targetDraftId;
      setCurrentDraftId(targetDraftId);
      try {
        localStorage.setItem(getActiveStorageKey(), targetDraftId);
      } catch {}

      try {
        const detail = await draftService.getDraft(targetDraftId, session);
        if (!isMountedRef.current || !operation.isActive()) return { status: 'ERROR' };
        if (detail) {
          // PUBLISHED tombstone protection: never wipe the active editor with empty tombstone payload
          if (detail.status === 'PUBLISHED' || (!detail.payload || detail.payload === '{}')) {
            try {
              localStorage.removeItem(getActiveStorageKey());
            } catch {}
            if (!operation.isActive()) return { status: 'ERROR' };
            await localDraftStorage.remove(email, targetDraftId, () => operation.isActive());
            if (!operation.isActive()) return { status: 'ERROR' };
            currentDraftIdRef.current = null;
            setCurrentDraftId(null);
            await refreshDraftsList(session);
            if (!operation.isActive()) return { status: 'ERROR' };

            let completedListings: CompletedListingSummary[] = [];
            if (detail.payload && detail.payload !== '{}') {
              try {
                const parsed = JSON.parse(detail.payload);
                if (Array.isArray(parsed.completedListings)) {
                  completedListings = parsed.completedListings;
                }
              } catch {}
            }
            if (completedListings.length === 0 && detail.publishedPropertyId) {
              completedListings = [{ listingId: detail.publishedPropertyId, title: detail.titleSummary }];
            }

            onDraftAlreadyPublished?.(detail, completedListings.length, completedListings);
            return {
              status: 'ALREADY_PUBLISHED',
              detail,
              completedCount: completedListings.length,
              completedListings
            };
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
          await onRestoreDraft(parsed, detail.media || [], session, operation);
          if (isMountedRef.current && operation.isActive()) {
            await refreshDraftsList(session);
          }
          if (!operation.isActive()) return { status: 'ERROR' };
          return { status: 'RESTORED', detail };
        }
      } catch (error: any) {
        if (withdrawDeniedProtectedDraft(
          error,
          session,
          'You no longer have permission to open this draft.'
        )) return { status: 'ERROR' };
        if (!operation.isActive()) {
          return { status: 'ERROR' };
        }
        const local = await localDraftStorage.get(email, targetDraftId);
        if (!isMountedRef.current || !operation.isActive()) return { status: 'ERROR' };
        if (local && local.payload && local.payload !== '{}') {
          currentDraftIdRef.current = local.draftId;
          setCurrentDraftId(local.draftId);
          setDraftVersion(local.version);
          draftVersionRef.current = local.version;
          setPublishedPropertyId(null);
          publishedPropertyIdRef.current = null;
          setLastSavedAt(new Date(local.updatedAt));
          setAutosaveStatus('offline');
          await onRestoreDraft(local.payload, [], session, operation);
          if (isMountedRef.current && operation.isActive()) {
            await refreshDraftsList(session);
          }
          if (!operation.isActive()) return { status: 'ERROR' };
          return { status: 'RESTORED' };
        }
      }
      return { status: 'NOT_FOUND' };
    },
    [captureProtectedOperation, flushPendingSave, getActiveStorageKey, onClearDraftState,
      withdrawDeniedProtectedDraft,
      onDraftAlreadyPublished, onRestoreDraft, refreshDraftsList]
  );

  // Start a new blank draft
  const startNewDraft = useCallback(async () => {
    const session = captureUploaderSession();
    if (!session || !isUploaderSessionActive(session)) return;
    const operation = captureProtectedOperation(session);
    if (!operation) return;
    // 1. Flush/save current meaningful draft first if needed
    await flushPendingSave();
    if (!operation.isActive()) return;

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
    await refreshDraftsList(session);
  }, [
    captureProtectedOperation,
    flushPendingSave,
    getActiveStorageKey,
    onClearDraftState,
    refreshDraftsList
  ]);

  // Discard a draft explicitly
  const discardDraft = useCallback(
    async (draftIdToDiscard: string): Promise<DiscardDraftResult> => {
      const unownedResult = (outcome: DiscardOutcome): DiscardDraftResult => ({
        outcome,
        operationId: Symbol('uploader-discard'),
        isOwnerActive: false,
        canFinalize: false
      });
      const session = captureUploaderSession();
      if (!session || !isUploaderSessionActive(session)) return unownedResult('cancelled');
      const operation = captureProtectedOperation(session);
      if (!operation) return unownedResult('cancelled');
      const resultFor = (outcome: DiscardOutcome): DiscardDraftResult => ({
        outcome,
        operationId: operation.operationId,
        isOwnerActive: operation.isActive(),
        // Current 401 completion is owned by the token-bound global expiry path. The rejected request
        // may settle after that path retires this operation, so its own result must remain inert.
        canFinalize: outcome === 'permission-denied'
          ? isUploaderSessionActive(session)
          : operation.isActive()
      });
      const email = operation.emailAtDispatch;
      try {
        await discardDraftRequest(draftIdToDiscard, session, operation.signal);
      } catch (error: any) {
        const outcome = classifyDiscardFailure(error, session, operation);
        if (outcome === 'permission-denied' || outcome === 'session-expired') {
          withdrawDeniedProtectedDraft(
            error,
            session,
            'You no longer have permission to discard this draft.'
          );
        }
        if (outcome === 'failed') {
          console.warn(`Unable to discard draft ${draftIdToDiscard}:`, error);
        }
        return resultFor(outcome);
      }
      if (!operation.isActive()) return resultFor('cancelled');
      await localDraftStorage.remove(email, draftIdToDiscard, () => operation.isActive());
      if (!operation.isActive()) return resultFor('cancelled');

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

      await refreshDraftsList(session);
      return resultFor(operation.isActive() ? 'succeeded' : 'cancelled');
    },
    [captureProtectedOperation, classifyDiscardFailure, discardDraftRequest, getActiveStorageKey,
      onClearDraftState, refreshDraftsList,
      withdrawDeniedProtectedDraft]
  );

  const discardMultipleDrafts = useCallback(
    async (draftIdsToDiscard: string[]): Promise<BulkDiscardResult> => {
      const unownedResult = (outcome: DiscardOutcome): BulkDiscardResult => ({
        succeeded: [],
        failed: [],
        notAttempted: [...draftIdsToDiscard],
        outcome,
        operationId: Symbol('uploader-bulk-discard'),
        isOwnerActive: false,
        canFinalize: false,
        isAuthError: false
      });
      const session = captureUploaderSession();
      if (!session || !isUploaderSessionActive(session)) {
        return unownedResult('cancelled');
      }
      const operation = captureProtectedOperation(session);
      if (!operation) {
        return unownedResult('cancelled');
      }
      const email = operation.emailAtDispatch;
      const outcomes = draftIdsToDiscard.map(() => 'not-attempted' as
        'succeeded' | 'failed' | 'not-attempted');
      let activeDraftWasDiscarded = false;
      let terminalOutcome: DiscardOutcome | null = null;

      const resultFor = (outcome: DiscardOutcome): BulkDiscardResult => {
        const succeeded = draftIdsToDiscard.filter((_, index) => outcomes[index] === 'succeeded');
        const failed = draftIdsToDiscard.filter((_, index) => outcomes[index] === 'failed');
        const notAttempted = draftIdsToDiscard.filter((_, index) => outcomes[index] === 'not-attempted');
        return {
          succeeded,
          failed,
          notAttempted,
          outcome,
          operationId: operation.operationId,
          isOwnerActive: operation.isActive(),
          // Session expiry, including a legitimate current 401, is finalized only by the existing
          // token-bound notification and Home reset. Stale async completion never receives authority.
          canFinalize: outcome === 'permission-denied'
            ? isUploaderSessionActive(session)
            : operation.isActive(),
          isAuthError: outcome === 'session-expired'
        };
      };

      // Execute with bounded concurrency = 3
      const BATCH_CONCURRENCY = 3;
      for (let i = 0; i < draftIdsToDiscard.length; i += BATCH_CONCURRENCY) {
        const chunk = draftIdsToDiscard.slice(i, i + BATCH_CONCURRENCY);
        await Promise.all(
          chunk.map(async (draftId, offset) => {
            const outcomeIndex = i + offset;
            if (!operation.isActive()) return;
            try {
              await discardDraftRequest(draftId, session, operation.signal);
              outcomes[outcomeIndex] = 'succeeded';
              if (!operation.isActive()) return;
              await localDraftStorage.remove(email, draftId, () => operation.isActive());
              if (!operation.isActive()) return;
              if (currentDraftIdRef.current === draftId) {
                activeDraftWasDiscarded = true;
              }
            } catch (err: any) {
              outcomes[outcomeIndex] = 'failed';
              const outcome = classifyDiscardFailure(err, session, operation);
              if (outcome === 'permission-denied' || outcome === 'session-expired') {
                terminalOutcome = terminalOutcome || outcome;
                // Retirement happens synchronously inside the failing worker, before sibling settlement.
                withdrawDeniedProtectedDraft(
                  err,
                  session,
                  'You no longer have permission to discard saved drafts.'
                );
              } else if (outcome === 'failed') {
                console.warn(`Failed to discard draft ${draftId}:`, err);
              }
            }
          })
        );

        if (!operation.isActive()) break;
      }

      if (terminalOutcome) return resultFor(terminalOutcome);
      if (!operation.isActive()) {
        return resultFor('cancelled');
      }

      // Only reset active draft identity and editor if one of the discarded drafts was active
      if (activeDraftWasDiscarded) {
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

      await refreshDraftsList(session);
      return resultFor(operation.isActive() ? 'succeeded' : 'cancelled');
    },
    [captureProtectedOperation, classifyDiscardFailure, discardDraftRequest, getActiveStorageKey,
      onClearDraftState, refreshDraftsList, withdrawDeniedProtectedDraft]
  );

  // Handle successful publication
  const onPublishSuccess = useCallback(async (
    listingId?: number,
    capturedSession?: UploaderSession | null
  ) => {
    const session = capturedSession || captureUploaderSession();
    if (!session || !isUploaderSessionActive(session)) return;
    const operation = captureProtectedOperation(session);
    if (!operation) return;
    const draftId = currentDraftIdRef.current;
    const finalListingId = listingId || publishedPropertyIdRef.current || undefined;
    if (!draftId) return;

    const email = operation.emailAtDispatch;
    try {
      await draftService.markPublished(draftId, finalListingId, session);
    } catch (error: any) {
      if (withdrawDeniedProtectedDraft(
        error,
        session,
        'You no longer have permission to finalize this draft.'
      )) return;
      if (!operation.isActive()) return;
    }
    if (!operation.isActive()) return;
    await localDraftStorage.remove(email, draftId, () => operation.isActive());
    if (!operation.isActive()) return;

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
    await refreshDraftsList(session);
  }, [
    captureProtectedOperation,
    getActiveStorageKey,
    refreshDraftsList,
    withdrawDeniedProtectedDraft
  ]);

  // Cancel pending debounced autosave
  const cancelAutosave = useCallback(() => {
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }
    pendingPayloadRef.current = null;
    setAutosaveStatus('idle');
  }, []);

  // Suspend autosave during publishing operations and cancel any pending debounced timers
  const suspendAutosave = useCallback(() => {
    isSuspendedRef.current = true;
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }
    pendingPayloadRef.current = null;
    setAutosaveStatus('idle');
  }, []);

  // Resume autosave after publishing operations or recoverable exit
  const resumeAutosave = useCallback(() => {
    const session = captureUploaderSession();
    if (
      session
      && activeOperationEpochRef.current
      && !terminalDeniedSessionRef.current
      && isUploaderSessionActive(session)
    ) {
      isSuspendedRef.current = false;
    }
  }, []);

  // Handle batch publication reconciliation
  const onBatchPublished = useCallback(
    async (
      publishedCardIds: string[],
      completedListings?: Array<{ cardId: string; listingId: number; title: string }>,
      capturedSession?: UploaderSession | null
    ): Promise<DraftDetail | null> => {
      const session = capturedSession || captureUploaderSession();
      if (!session || !isUploaderSessionActive(session)) return null;
      const operation = captureProtectedOperation(session);
      if (!operation) return null;
      const draftId = currentDraftIdRef.current;
      if (!draftId) return null;

      let remaining: DraftDetail | null;
      try {
        remaining = await draftService.reconcileBatch(
          draftId, publishedCardIds, completedListings, session);
      } catch (error: any) {
        if (withdrawDeniedProtectedDraft(
          error,
          session,
          'You no longer have permission to finalize these properties.'
        )) return null;
        if (!operation.isActive()) return null;
        throw error;
      }
      if (!operation.isActive()) return null;
      if (!remaining) {
        // Fully published
        await onPublishSuccess(undefined, session);
      } else {
        // Partial success: update remaining version and refresh
        setDraftVersion(remaining.version);
        draftVersionRef.current = remaining.version;
        setLastSavedAt(new Date());
        await refreshDraftsList(session);
      }
      if (!operation.isActive()) return null;
      return remaining;
    },
    [captureProtectedOperation, onPublishSuccess, refreshDraftsList, withdrawDeniedProtectedDraft]
  );

  // Overwrite conflict with local edits
  const resolveConflictKeepLocal = useCallback(async () => {
    const session = captureUploaderSession();
    if (!session || !isUploaderSessionActive(session)) return;
    const operation = captureProtectedOperation(session);
    if (!operation) return;
    const pending = pendingPayloadRef.current;
    const draftId = currentDraftIdRef.current;
    if (!pending || !draftId) return;

    try {
      // Force fetch server to get latest version counter
      const latest = await draftService.getDraft(draftId, session);
      if (!operation.isActive()) return;
      const nextVersion = (latest?.version || 1) + 1;
      const res = await draftService.saveDraft({
        draftId,
        draftType,
        titleSummary: pending.titleSummary,
        itemCount: pending.itemCount,
        version: nextVersion,
        payload: JSON.stringify(pending.payload)
      }, session);
      if (!operation.isActive()) return;
      setDraftVersion(res.version);
      draftVersionRef.current = res.version;
      setLastSavedAt(new Date());
      setAutosaveStatus('saved');
      setConflictMessage(null);
    } catch (error: any) {
      if (withdrawDeniedProtectedDraft(
        error,
        session,
        'You no longer have permission to save this draft.'
      )) return;
      if (!operation.isActive()) return;
      setAutosaveStatus('offline');
    }
  }, [captureProtectedOperation, draftType, withdrawDeniedProtectedDraft]);

  // Overwrite local with server version
  const resolveConflictReloadServer = useCallback(async () => {
    const operation = captureProtectedOperation();
    if (!operation) return;
    const draftId = currentDraftIdRef.current;
    if (!draftId) return;
    setConflictMessage(null);
    await loadDraft(draftId);
  }, [captureProtectedOperation, loadDraft]);

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
    cancelAutosave,
    suspendAutosave,
    resumeAutosave,
    flushPendingSave,
    loadDraft,
    startNewDraft,
    discardDraft,
    discardMultipleDrafts,
    onPublishSuccess,
    onBatchPublished,
    resolveConflictKeepLocal,
    resolveConflictReloadServer,
    refreshDraftsList,
    captureProtectedOperation,
    isProtectedOperationActive,
    withdrawCurrentSessionDenial: withdrawDeniedProtectedDraft
  };
}
