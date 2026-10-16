import { isOperationsSessionActive, type OperationsSession } from './operationsSession';

export interface SchedulingIntentBody {
  expectedSessionVersion: number;
  groundExecutiveUserId: number;
  scheduledAt: string;
  zoneId: string;
  overrideReason: string | null;
}
export interface SchedulingIntent { commandId: string; body: SchedulingIntentBody }
const PREFIX = 'pathome_oe_schedule_v1:';
let bound = false;
const key = (session: OperationsSession, sessionId: number) => `${PREFIX}${session.userId}:${sessionId}`;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export const clearSchedulingIntents = (): void => {
  try {
    for (let i = sessionStorage.length - 1; i >= 0; i--) {
      const entry = sessionStorage.key(i);
      if (entry?.startsWith(PREFIX)) sessionStorage.removeItem(entry);
    }
  } catch { /* No protected result is retained in memory as a fallback. */ }
};
const bind = () => {
  if (bound) return;
  bound = true;
  window.addEventListener('pathome_auth_changed', clearSchedulingIntents);
  window.addEventListener('pathome_session_expired', clearSchedulingIntents);
  window.addEventListener('storage', (event) => {
    if (event.key === null || ['pathome_auth_token', 'pathome_user', 'pathome_role'].includes(event.key)) clearSchedulingIntents();
  });
};

/** Refresh-safe intent only: no credential, contact, recommendation list or authoritative outcome. */
export const readSchedulingIntent = (session: OperationsSession, sessionId: number): SchedulingIntent | null => {
  bind();
  if (!isOperationsSessionActive(session)) return null;
  const raw = sessionStorage.getItem(key(session, sessionId));
  if (!raw) return null;
  const value = JSON.parse(raw) as SchedulingIntent;
  if (!uuid.test(value.commandId) || !Number.isSafeInteger(value.body?.expectedSessionVersion)
      || !Number.isSafeInteger(value.body?.groundExecutiveUserId) || typeof value.body?.scheduledAt !== 'string'
      || typeof value.body?.zoneId !== 'string') throw Error('The pending booking record could not be read. Check the authoritative session before scheduling.');
  return value;
};
export const preserveSchedulingIntent = (
  session: OperationsSession, sessionId: number, body: SchedulingIntentBody
): SchedulingIntent => {
  if (!isOperationsSessionActive(session)) throw Error('The signed-in session changed.');
  const existing = readSchedulingIntent(session, sessionId);
  if (existing) {
    if (JSON.stringify(existing.body) !== JSON.stringify(body)) throw Error('A previous booking is still unconfirmed. Check the session before changing the selection.');
    return existing;
  }
  let count = 0;
  for (let i = 0; i < sessionStorage.length; i++) if (sessionStorage.key(i)?.startsWith(PREFIX)) count++;
  if (count >= 20) throw Error('Check your pending bookings before starting another one.');
  const intent = { commandId: crypto.randomUUID(), body: { ...body } };
  sessionStorage.setItem(key(session, sessionId), JSON.stringify(intent));
  return intent;
};
export const forgetSchedulingIntent = (session: OperationsSession, sessionId: number, commandId: string): void => {
  if (!isOperationsSessionActive(session)) return;
  if (readSchedulingIntent(session, sessionId)?.commandId === commandId) sessionStorage.removeItem(key(session, sessionId));
};
