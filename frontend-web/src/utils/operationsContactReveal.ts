/**
 * Generation guard for one protected lessor-contact reveal.
 *
 * The contact panel is keyed by a contact context (account identity, root, version and authority), and
 * a context change clears what is on screen. Clearing alone is not enough: a reveal that was already
 * in flight can still resolve *after* the context changed — the same record at a new version keeps the
 * component mounted, so its late completion would otherwise repopulate protected contact information.
 *
 * The rule here is deliberately tiny and pure: every reveal takes a ticket from the current generation,
 * and a ticket is only usable while its generation is still the live one and its context still matches.
 * A context change, a newer reveal in the same context, or an explicit reset invalidates every older
 * ticket, so an outdated answer can never be displayed.
 */

export interface ContactRevealTicket {
  generation: number;
  contextKey: string;
}

export interface ContactRevealGate {
  generation: number;
  /** Context the live generation belongs to, or null before any reveal/reset. */
  contextKey: string | null;
}

export const createContactRevealGate = (): ContactRevealGate => ({ generation: 0, contextKey: null });

/**
 * Invalidates every outstanding ticket and binds the gate to `contextKey`. Used when the contact
 * context changes (identity, root, version or authority), so pending reveals become unusable.
 */
export const resetContactRevealGate = (
  gate: ContactRevealGate,
  contextKey: string | null
): ContactRevealGate => ({ generation: gate.generation + 1, contextKey });

/**
 * Starts one reveal. A reveal issued for a different context first resets the gate, and any reveal
 * supersedes the previously issued ticket for that same context.
 */
export const beginContactReveal = (
  gate: ContactRevealGate,
  contextKey: string
): { gate: ContactRevealGate; ticket: ContactRevealTicket } => {
  const base = gate.contextKey === contextKey ? gate : resetContactRevealGate(gate, contextKey);
  const generation = base.generation + 1;
  return { gate: { generation, contextKey }, ticket: { generation, contextKey } };
};

/** True only while this ticket is still the live reveal for the live context. */
export const contactRevealIsCurrent = (
  gate: ContactRevealGate,
  ticket: ContactRevealTicket
): boolean => gate.generation === ticket.generation && gate.contextKey === ticket.contextKey;
