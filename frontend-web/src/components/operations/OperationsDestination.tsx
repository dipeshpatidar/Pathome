import React, { useCallback, useRef } from 'react';
import { useLocation } from 'react-router-dom';
import type { OperationsAuthority } from '../../hooks/useOperationsCapability';
import { OperationsWorkspace } from './OperationsWorkspace';
import { OperationsLeadsWorkspace } from './OperationsLeadsWorkspace';
import { OperationsRoutingWorkspace } from './OperationsRoutingWorkspace';

interface OperationsDestinationProps {
  /**
   * The single application-wide capability state machine, owned by the app shell. The destination
   * never creates its own: authority is verified once and shared with the navigation entry and the
   * embedded console tabs, so there is no duplicated polling and no competing authority state.
   */
  authority: OperationsAuthority;
}

/**
 * The dedicated `/operations` destination.
 *
 * This route admits an account on a staff capability alone - an active staff profile holding an
 * effective `OPS_COORDINATE` Team grant - so an Executive reaches the workspace through ordinary
 * navigation regardless of the legacy role on their token. No role label is consulted here, and the
 * route is never reached by an automatic redirect: the destination is only ever entered because the
 * user chose it or opened the link directly.
 *
 * Every authority state is handled by the workspace itself and fails closed:
 * `checking` shows progress with no protected fetch, `restricted` withdraws all data and actions,
 * `unavailable` hides protected work and offers Retry, and a session that ends mid-view is reported
 * rather than masked. Because the workspace re-verifies on entry, on focus, on visibility
 * restoration and roughly every 30 seconds, a revoked or expired grant withdraws the destination
 * without a reload, and a browser refresh always re-verifies before any work is shown.
 */
export const OperationsDestination: React.FC<OperationsDestinationProps> = ({ authority }) => {
  const location = useLocation();
  const leadRoute = location.pathname === '/operations/leads'
    || location.pathname.startsWith('/operations/leads/');
  const routingRoute = location.pathname === '/operations/routing';
  const leadNavigationGuard = useRef<((leave: () => void) => void) | null>(null);
  const setLeadNavigationGuard = useCallback(
    (guard: ((leave: () => void) => void) | null) => {
      leadNavigationGuard.current = guard;
    },
    []
  );
  return (
    <div className="min-h-screen w-full bg-[#f8f7f4]">
      <main
        aria-label="Operations"
        className="w-full min-w-0"
      >
        {routingRoute
          ? <OperationsRoutingWorkspace authority={authority} />
          : (
            <OperationsWorkspace
              authority={authority}
              leadNavigationGuard={leadNavigationGuard}
              leadContent={leadRoute
                ? (
                  <OperationsLeadsWorkspace
                    authority={authority}
                    embedded
                    onShellNavigationGuardChange={setLeadNavigationGuard}
                  />
                )
                : undefined}
            />
          )}
      </main>
    </div>
  );
};
