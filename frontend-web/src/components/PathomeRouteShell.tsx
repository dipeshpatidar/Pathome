import React from 'react';
import { resolveAppHeaderOwner } from '../utils/navigationPolicy';

/** Mounts exactly one route surface. Outgoing page content cannot linger beside the editor. */
export function PathomeRouteShell({ pathname, normal, focused }: {
  pathname: string;
  normal: React.ReactNode;
  focused: React.ReactNode;
}) {
  return resolveAppHeaderOwner(pathname) === 'LESSOR_ONBOARDING' && focused
    ? <>{focused}</>
    : <>{normal}</>;
}
