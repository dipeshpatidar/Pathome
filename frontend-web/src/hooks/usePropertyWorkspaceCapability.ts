import { useCallback, useEffect, useRef, useState } from 'react';
import { staffAdministrationService } from '../services/staffAdministrationService.ts';
import {
  captureOperationsSession,
  isOperationsSessionActive,
  subscribeOperationsSession,
  type OperationsSession
} from '../utils/operationsSession.ts';

export type PropertyCapability =
  | 'PROPERTY_EDIT_PUBLISH'
  | 'PROPERTY_AUDIT_READ'
  | 'PROPERTY_LIFECYCLE';

export interface PropertyWorkspaceCapability {
  state: 'checking' | 'granted' | 'restricted' | 'unavailable';
  capabilities: Set<PropertyCapability>;
  cityIds: Set<number>;
  canEnter: boolean;
  canEdit: boolean;
  canAudit: boolean;
  canLifecycle: boolean;
  reload: () => void;
  verify: (session?: OperationsSession) => Promise<boolean>;
}

const propertyCapabilities = new Set<PropertyCapability>([
  'PROPERTY_EDIT_PUBLISH', 'PROPERTY_AUDIT_READ', 'PROPERTY_LIFECYCLE'
]);

export function usePropertyWorkspaceCapability(userId: number | null): PropertyWorkspaceCapability {
  const [state, setState] = useState<PropertyWorkspaceCapability['state']>(
    userId ? 'checking' : 'restricted'
  );
  const [capabilities, setCapabilities] = useState<Set<PropertyCapability>>(() => new Set());
  const [cityIds, setCityIds] = useState<Set<number>>(() => new Set());
  const revision = useRef(0);
  const pending = useRef<AbortController | null>(null);

  const verify = useCallback(async (supplied?: OperationsSession) => {
    const session = supplied ?? captureOperationsSession();
    if (!session || (userId !== null && session.userId !== userId)) {
      pending.current?.abort();
      pending.current = null;
      setCapabilities(new Set());
      setCityIds(new Set());
      setState('restricted');
      return false;
    }
    const ownRevision = ++revision.current;
    pending.current?.abort();
    const controller = new AbortController();
    pending.current = controller;
    try {
      const result = await staffAdministrationService.capabilities({ session, signal: controller.signal });
      if (ownRevision !== revision.current || !isOperationsSessionActive(session)) return false;
      const next = new Set<PropertyCapability>();
      const nextCities = new Set<number>();
      result.capabilities.forEach((entry) => {
        if (!propertyCapabilities.has(entry.capability as PropertyCapability)
            || entry.scopeType !== 'CITY' || entry.cityId === null) return;
        next.add(entry.capability as PropertyCapability);
        nextCities.add(entry.cityId);
      });
      setCapabilities(next);
      setCityIds(nextCities);
      setState(next.size > 0 ? 'granted' : 'restricted');
      return next.size > 0;
    } catch (cause) {
      if (controller.signal.aborted || ownRevision !== revision.current) return false;
      setCapabilities(new Set());
      setCityIds(new Set());
      const status = typeof cause === 'object' && cause !== null && 'status' in cause
        ? Number((cause as { status?: unknown }).status) : 0;
      setState(status === 401 || status === 403 ? 'restricted' : 'unavailable');
      return false;
    } finally {
      if (pending.current === controller) pending.current = null;
    }
  }, [userId]);

  useEffect(() => {
    if (!userId) {
      setState('restricted');
      setCapabilities(new Set());
      setCityIds(new Set());
      return undefined;
    }
    setState('checking');
    void verify();
    const unsubscribe = subscribeOperationsSession(() => {
      pending.current?.abort();
      setCapabilities(new Set());
      setCityIds(new Set());
      setState('checking');
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
  }, [userId, verify]);

  return {
    state,
    capabilities,
    cityIds,
    canEnter: state === 'granted',
    canEdit: capabilities.has('PROPERTY_EDIT_PUBLISH'),
    canAudit: capabilities.has('PROPERTY_AUDIT_READ'),
    canLifecycle: capabilities.has('PROPERTY_LIFECYCLE'),
    reload: () => { void verify(); },
    verify
  };
}

