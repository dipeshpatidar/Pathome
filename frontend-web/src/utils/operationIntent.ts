import { createOperationId, isOperationId } from './operationsOperationId.ts';

export interface OperationIntent {
  key: string;
  id: string;
}

/**
 * Keeps one UUID for one exact submitted intent. A changed target, version, action or reason gets a
 * new identity, while an uncertain network retry of the same intent safely reuses the original.
 */
export const operationIdForIntent = (
  current: OperationIntent | null,
  key: string,
  create: () => string = createOperationId
): OperationIntent => {
  if (current?.key === key && isOperationId(current.id)) return current;
  return { key, id: create() };
};

export const operationIntentKey = (parts: ReadonlyArray<string | number | null | undefined>): string =>
  JSON.stringify(parts.map(part => part == null ? '' : String(part).trim()));
