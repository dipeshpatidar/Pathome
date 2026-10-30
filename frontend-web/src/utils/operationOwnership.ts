export interface OperationOwner {
  operationId: number;
  sessionIdentity: string;
  routeIdentity: string;
  entityIdentity: string;
  generation: number;
}

export const captureOperationOwner = (
  operationId: number,
  sessionIdentity: string | null | undefined,
  routeIdentity: string,
  entityIdentity: string,
  generation: number
): OperationOwner => ({
  operationId,
  sessionIdentity: sessionIdentity ?? '',
  routeIdentity,
  entityIdentity,
  generation
});

export const operationOwnerIsCurrent = (
  captured: OperationOwner,
  current: OperationOwner
): boolean =>
  captured.operationId === current.operationId
  && captured.sessionIdentity === current.sessionIdentity
  && captured.routeIdentity === current.routeIdentity
  && captured.entityIdentity === current.entityIdentity
  && captured.generation === current.generation;

export class ExclusiveOperationGate {
  private active: OperationOwner | null = null;
  private nextOperationId = 0;

  begin(
    sessionIdentity: string | null | undefined,
    routeIdentity: string,
    entityIdentity: string,
    generation: number
  ): OperationOwner | null {
    if (this.active) return null;
    this.active = captureOperationOwner(++this.nextOperationId, sessionIdentity,
      routeIdentity, entityIdentity, generation);
    return this.active;
  }

  owns(captured: OperationOwner): boolean {
    return this.active !== null && operationOwnerIsCurrent(captured, this.active);
  }

  finish(captured: OperationOwner): boolean {
    if (!this.owns(captured)) return false;
    this.active = null;
    return true;
  }

  invalidate(): void {
    this.active = null;
  }

  get isActive(): boolean {
    return this.active !== null;
  }
}

export const isProtectedDenialStatus = (status: unknown): boolean => {
  const code = Number(status);
  return code === 401 || code === 403 || code === 404 || code === 410;
};

/** Only a denial from the still-current owner may withdraw that owner's protected UI state. */
export const isCurrentProtectedDenial = (status: unknown, currentOwner: boolean): boolean =>
  currentOwner && isProtectedDenialStatus(status);

/** A command may have committed even though its acknowledgement was not received. */
export const isUncertainCommandFailure = (status: unknown): boolean => {
  const code = Number(status);
  return !Number.isFinite(code) || code === 0 || code === 408 || code === 425
    || code === 429 || code >= 500;
};

export const statusOf = (cause: unknown): number =>
  typeof cause === 'object' && cause !== null && 'status' in cause
    ? Number((cause as { status?: unknown }).status) : 0;
