import type { StaffCapabilities } from './operationsWorkspacePresentation.ts';
import {
  hasLeadDiagnosticsAccess,
  hasLeadWorkspaceAccess
} from './operationsLeadPresentation.ts';
import {
  hasOperationsWorkspaceAccess,
  hasRoutingWorkspaceAccess
} from './operationsWorkspacePresentation.ts';

export type OperationsWorkspaceDestinationKey =
  | 'home'
  | 'attention'
  | 'my-work'
  | 'claimable'
  | 'automatic-routing'
  | 'leads'
  | 'exceptions';

export interface OperationsWorkspaceDestination {
  key: OperationsWorkspaceDestinationKey;
  label: string;
  short: string;
  path: string;
  ownedWork: boolean;
}

/**
 * One capability-derived destination list for desktop and mobile. The order follows the approved v0
 * workspace; destinations whose authority is absent never render, even while a route directly names
 * the surface (the surface itself still fails closed server-side).
 */
export const operationsWorkspaceDestinations = (
  capabilities: StaffCapabilities | null | undefined
): OperationsWorkspaceDestination[] => {
  const destinations: OperationsWorkspaceDestination[] = [];
  if (hasOperationsWorkspaceAccess(capabilities)) {
    destinations.push(
      { key: 'home', label: 'Overview', short: 'Home', path: '/operations', ownedWork: true },
      { key: 'attention', label: 'Needs attention', short: 'Attention', path: '/operations/queues/attention', ownedWork: true },
      { key: 'my-work', label: 'My work', short: 'My work', path: '/operations/queues/my-work', ownedWork: true },
      { key: 'claimable', label: 'Available work', short: 'Available', path: '/operations/queues/claimable', ownedWork: true }
    );
  }
  if (hasLeadWorkspaceAccess(capabilities)) {
    destinations.push({
      key: 'leads',
      label: 'Leads',
      short: 'Leads',
      path: '/operations/leads',
      ownedWork: false
    });
  }
  if (hasRoutingWorkspaceAccess(capabilities)) {
    destinations.push({
      key: 'automatic-routing',
      label: 'City routing',
      short: 'Routing',
      path: '/operations/routing',
      ownedWork: false
    });
  }
  if (hasLeadDiagnosticsAccess(capabilities)) {
    destinations.push({
      key: 'exceptions',
      label: 'Import issues',
      short: 'Imports',
      path: '/operations/leads/source-exceptions',
      ownedWork: false
    });
  }
  return destinations;
};
