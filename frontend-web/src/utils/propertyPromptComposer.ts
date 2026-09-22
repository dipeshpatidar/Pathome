/**
 * Canonical utilities for composing and scrolling property prompts in the Property Upload studio.
 * Ensures consistent separator conventions between Example Prompts and "Add another property".
 */

export const CANONICAL_NEXT_PROPERTY_KEYWORD = 'Next property';
export const CANONICAL_NEXT_PROPERTY_SEPARATOR = '\n\nNext property\n';

/**
 * Pattern detecting whether prompt text ends with a "Next property" delimiter line.
 * Matches case-insensitively on property/flat/house/listing/unit.
 */
export const TRAILING_NEXT_PROPERTY_PATTERN = /(?:\r?\n|^)\s*next\s*(?:property|flat|house|listing|unit)\s*$/i;

/**
 * Composes the updated prompt when adding or appending another property.
 *
 * Case A (Prompt empty/blank):
 *   Returns the new property text cleanly without adding "Next property".
 *
 * Case B (Prompt already contains property content):
 *   Appends the new property using the canonical "\n\nNext property\n" separator.
 *   Safely strips excess trailing newlines and prevents duplicate delimiters.
 *
 * When newPropertyText is omitted (e.g. clicking "Add another property"):
 *   Appends the canonical separator so the user can begin typing the next listing.
 *   If the prompt already ends with "Next property", avoids duplicating it.
 *
 * @param existingText Current prompt text in the editor
 * @param newPropertyText Optional property description (e.g. from an Example Prompt)
 * @returns Canonical updated prompt text
 */
export function composeNextPropertyPrompt(existingText: string, newPropertyText?: string): string {
  const cleanExisting = (existingText || '').trimEnd();
  const cleanNew = (newPropertyText || '').trim();

  // CASE A: Prompt is empty or effectively blank
  if (!cleanExisting) {
    return cleanNew;
  }

  const alreadyHasTrailingDelimiter = TRAILING_NEXT_PROPERTY_PATTERN.test(cleanExisting);

  // CASE B1: Appending an example property to existing content
  if (cleanNew) {
    if (alreadyHasTrailingDelimiter) {
      return `${cleanExisting}\n${cleanNew}`;
    }
    return `${cleanExisting}${CANONICAL_NEXT_PROPERTY_SEPARATOR}${cleanNew}`;
  }

  // CASE B2: Clicking "Add another property" without immediate text
  if (alreadyHasTrailingDelimiter) {
    return `${cleanExisting}\n`;
  }
  return `${cleanExisting}${CANONICAL_NEXT_PROPERTY_SEPARATOR}`;
}

/**
 * Scrolls the prompt textarea so that the newly appended "Next property" section
 * and the beginning of the newly added property details are clearly visible.
 *
 * Does NOT force keyboard focus on mobile (preventing unexpected keyboard popups).
 * Positions caret at the end of the newly appended content.
 *
 * @param textarea The HTMLTextAreaElement reference
 * @param newText The updated full prompt text
 */
export function scrollPromptTextareaToNextProperty(
  textarea: HTMLTextAreaElement | null,
  newText: string
): void {
  if (!textarea) return;

  const performScroll = () => {
    if (!textarea) return;

    // 1. Position caret after newly appended text without forcing focus / mobile keyboard
    const newLen = newText.length;
    try {
      textarea.selectionStart = newLen;
      textarea.selectionEnd = newLen;
    } catch (_) {}

    // 2. Check overflow
    const { scrollHeight, clientHeight } = textarea;
    if (scrollHeight > 0 && scrollHeight <= clientHeight) {
      return;
    }

    // 3. Locate the newly appended "Next property" section
    const lastNextIndex = newText.toLowerCase().lastIndexOf('next property');
    let targetScrollTop = Math.max(0, scrollHeight - clientHeight);

    if (lastNextIndex > 0 && newText.length > 0 && scrollHeight > 0) {
      // Calculate target position using character offset ratio
      // Monospace font ensures linear relationship between text length and vertical position
      const textRatio = lastNextIndex / newText.length;
      const approxTop = textRatio * scrollHeight;
      // Allow 24px (1 line) breathing room above "Next property" so the transition is obvious
      const targetWithBreathingRoom = Math.max(0, approxTop - 24);
      targetScrollTop = Math.min(targetWithBreathingRoom, Math.max(0, scrollHeight - clientHeight));
    } else if (scrollHeight === 0) {
      // Fallback for non-rendered / virtual test environments
      const lines = newText.split('\n').length;
      targetScrollTop = Math.max(0, (lines - 4) * 24);
    }

    // 4. Smooth scrolling respecting user motion preference
    const prefersReducedMotion =
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    const scrollBehavior: ScrollBehavior = prefersReducedMotion ? 'auto' : 'smooth';

    if (typeof textarea.scrollTo === 'function') {
      try {
        textarea.scrollTo({ top: targetScrollTop, behavior: scrollBehavior });
      } catch (_) {
        textarea.scrollTop = targetScrollTop;
      }
    }
    textarea.scrollTop = targetScrollTop;
  };

  // Run immediately for instant synchronization
  performScroll();

  // In browser environments, schedule a follow-up in next frame to confirm layout after React re-renders
  if (typeof requestAnimationFrame === 'function') {
    requestAnimationFrame(performScroll);
  }
}

/**
 * Authoritative explicit multi-property delimiters matching backend PropertyParserService semantics:
 * 1. Markdown separator lines (---, ===, ***)
 * 2. Property headers at line start (e.g. "Property 1:", "Flat #2:")
 * 3. Numbered items at line start (e.g. "1)", "2.", "[1]", "#1")
 * 4. Explicit transition phrases ("Next property", "Next flat", "agli property", "dusra flat")
 * 5. Ordinal property phrases ("second property", "the third flat is", "another house")
 */
export const EXPLICIT_PROPERTY_DELIMITER_REGEX =
  /(?:^[\t ]*[-=_*~]{3,}[\t ]*$)|(?:^[\t ]*(?:property|flat|listing|house|unit)\s*#?\d+[:.-]?\s*)|(?:^[\t ]*(?:\[?\d+[\]).:-]|#\d+)\s+)|(?:\b(?:next\s*(?:property|flat|house|listing|unit|one)|agli\s*property|dusra\s*flat)\b)|(?:\b(?:and\s+)?(?:the\s+)?(?:second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|thirteenth|fourteenth|fifteenth|sixteenth|seventeenth|eighteenth|nineteenth|twentieth|another)\s+(?:property|flat|house|listing|unit)(?:\s+is)?\b)/;

export const EXPLICIT_PROPERTY_DELIMITER_SPLIT_PATTERN =
  new RegExp(EXPLICIT_PROPERTY_DELIMITER_REGEX.source, 'gmi');

export const FALLBACK_DOUBLE_NEWLINE_SPLIT_PATTERN = /(?:\r?\n\s*\r?\n+)/g;

export const CONTINUATION_PARAGRAPH_START_PATTERN =
  /^(?:monthly\s*rent|rent|security\s*deposit|deposit|built[\s-]?up|carpet|area|the\s+(?:flat|house|property|unit|apartment|villa)|it\s+(?:has|is)|located|floor|on\s+the|covered|parking|car\s*parking|property\s+is|available|possession|preferred|tenants?|amenities|facilities|owner|contact|call|phone|brokerage|note|landmarks?)\b/i;

/**
 * Two-tier property detection matching backend PropertyParserService semantics:
 * 1. If explicit multi-property delimiters exist in prompt, split ONLY on explicit boundaries.
 *    Internal blank lines within a property description NEVER increment the property count.
 * 2. If NO explicit multi-property delimiters exist, fallback to blank-line segmentation,
 *    recognizing distinct properties while avoiding false count inflation on continuation paragraphs.
 */
export function countDetectedProperties(rawPrompt: string): number {
  if (!rawPrompt || !rawPrompt.trim()) {
    return 0;
  }

  const trimmed = rawPrompt.trim();
  const hasExplicit = new RegExp(EXPLICIT_PROPERTY_DELIMITER_REGEX.source, 'mi').test(trimmed);

  if (hasExplicit) {
    const chunks = trimmed
      .split(EXPLICIT_PROPERTY_DELIMITER_SPLIT_PATTERN)
      .map((c) => c?.trim())
      .filter((c) => c && c.length >= 8);
    return Math.max(1, chunks.length);
  }

  const paragraphs = trimmed
    .split(FALLBACK_DOUBLE_NEWLINE_SPLIT_PATTERN)
    .map((p) => p?.trim())
    .filter((p) => p && p.length >= 8);

  if (paragraphs.length <= 1) {
    return 1;
  }

  let count = 1;
  for (let i = 1; i < paragraphs.length; i++) {
    const p = paragraphs[i];
    if (!CONTINUATION_PARAGRAPH_START_PATTERN.test(p)) {
      count++;
    }
  }

  return count;
}

