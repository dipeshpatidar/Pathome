import { useCallback, useEffect, useRef, useState } from 'react';
import {
  propertyRemovalService,
  type RemovalQueueItem
} from '../services/propertyRemovalService.ts';
import type { UserRole } from '../types/index.ts';
import {
  captureOperationsSession,
  isOperationsSessionActive,
  subscribeOperationsSession
} from '../utils/operationsSession.ts';
import { statusOf } from '../utils/operationOwnership.ts';

export interface RemovalAdmissionQueue {
  items: RemovalQueueItem[];
  page: number;
  hasMore: boolean;
  sessionIdentity: string;
}

export interface PropertyRemovalAuthority {
  state: 'checking' | 'authorized' | 'restricted' | 'unavailable';
  canReview: boolean;
  admissionQueue: RemovalAdmissionQueue | null;
  reload: () => void;
}

/**
 * Admission for the Admin Removal tab comes from the actual protected queue endpoint. The local role
 * is only a navigation hint; the queue proves persisted role, active profile, global grant and record
 * scope in one request whose successful page is reused by the queue component.
 */
export function usePropertyRemovalAuthority(
  userId: number | null,
  roleHint: UserRole
): PropertyRemovalAuthority {
  const [state, setState] = useState<PropertyRemovalAuthority['state']>(
    userId && roleHint === 'ADMIN' ? 'checking' : 'restricted'
  );
  const [admissionQueue, setAdmissionQueue] = useState<RemovalAdmissionQueue | null>(null);
  const revision = useRef(0);
  const pending = useRef<AbortController | null>(null);

  const verify = useCallback(async () => {
    const ownRevision = ++revision.current;
    pending.current?.abort();
    pending.current = null;
    const session = captureOperationsSession();
    if (!userId || userId !== session?.userId || roleHint !== 'ADMIN') {
      setAdmissionQueue(null);
      setState('restricted');
      return;
    }
    setAdmissionQueue(null);
    setState('checking');
    const controller = new AbortController();
    pending.current = controller;
    try {
      const result = await propertyRemovalService.adminQueue(
        'PENDING', 0, null, controller.signal
      );
      if (controller.signal.aborted || ownRevision !== revision.current
          || !isOperationsSessionActive(session)) return;
      setAdmissionQueue({
        items: result.items,
        page: result.page,
        hasMore: result.hasMore,
        sessionIdentity: session.identity
      });
      setState('authorized');
    } catch (cause) {
      if (controller.signal.aborted || ownRevision !== revision.current) return;
      setAdmissionQueue(null);
      const status = statusOf(cause);
      setState(status === 401 || status === 403 || status === 404 ? 'restricted' : 'unavailable');
    } finally {
      if (pending.current === controller) pending.current = null;
    }
  }, [roleHint, userId]);

  useEffect(() => {
    void verify();
    const unsubscribe = subscribeOperationsSession(() => {
      pending.current?.abort();
      revision.current += 1;
      setAdmissionQueue(null);
      setState(userId && roleHint === 'ADMIN' ? 'checking' : 'restricted');
      void verify();
    });
    const refresh = () => {
      if (document.visibilityState !== 'hidden') void verify();
    };
    const interval = window.setInterval(refresh, 30_000);
    window.addEventListener('focus', refresh);
    document.addEventListener('visibilitychange', refresh);
    return () => {
      revision.current += 1;
      pending.current?.abort();
      unsubscribe();
      window.clearInterval(interval);
      window.removeEventListener('focus', refresh);
      document.removeEventListener('visibilitychange', refresh);
    };
  }, [roleHint, userId, verify]);

  return {
    state,
    canReview: state === 'authorized',
    admissionQueue,
    reload: () => { void verify(); }
  };
}
