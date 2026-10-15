import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ArrowDown, ArrowUp, CircleAlert, CircleCheck, CircleDashed, CirclePlus, CircleSlash, Trash2 } from 'lucide-react';
import {
  OPERATIONS_AVAILABILITY_SOURCE_OPTIONS,
  OPERATIONS_AVAILABILITY_ZONES,
  OPERATIONS_DERIVED_ITEM_ORIGINS,
  OPERATIONS_REMOVAL_REASON_MAX,
  activeSessionItems,
  availabilityChoiceLabel,
  availabilityChoicesFor,
  derivedStopOriginCandidates,
  formatAvailabilityWindow,
  isSessionItemRemovable,
  operationsAvailabilityMeta,
  operationsRecordReference,
  operationsZoneOptions,
  removedSessionItems,
  resolveAvailabilityFieldInstant,
  serverInstantToZoneInput,
  sessionPermitsItemContact,
  sessionItemOriginLabel,
  sessionItemRemovalBlockedReason,
  setAvailabilityCommand,
  type AvailabilityWindowInput,
  type OperationsAvailabilityChoice,
  type OperationsAvailabilitySource,
  type OperationsDerivedItemOrigin,
  type OperationsLessorContact,
  type OperationsSessionDetail,
  type OperationsSessionItem
} from '../../utils/operationsWorkspacePresentation';
import { OperationsLessorContactPanel } from './OperationsLessorContactPanel';

/**
 * DRAFT itinerary management: exact order, soft removal with retained history, and the
 * lessor-stated availability record for each active stop.
 *
 * Every control here is only offered where the current backend state allows it — the server remains
 * the authority and any unexpected refusal is reconciled by the workspace rather than retried.
 */

export interface DiscoveryListingOption {
  listingId: number;
  title: string;
  sector: string | null;
  address: string | null;
  city: string | null;
}

export interface AddStopInput {
  listingId: number;
  originatingRequestId: number;
  origin: OperationsDerivedItemOrigin;
}

interface Props {
  session: OperationsSessionDetail;
  /** Itinerary editing exists only for an owned DRAFT session. */
  editable: boolean;
  blocked: boolean;
  busyCommand: string | null;
  onReorder: (itemIds: number[]) => void;
  onRemove: (itemId: number, reason: string | null) => void;
  onSaveAvailability: (itemId: number, choice: OperationsAvailabilityChoice, window: AvailabilityWindowInput) => void;
  onAddStop: (input: AddStopInput) => void;
  /** Reveals the canonical contact for one item; the signal cancels it when the context changes. */
  revealItemContact: (itemId: number, signal: AbortSignal) => Promise<OperationsLessorContact>;
  contactContextKey: string;
  /** Searches existing public property discovery. It is not an operational-eligibility check. */
  searchListings: (query: string, signal: AbortSignal) => Promise<DiscoveryListingOption[]>;
}

const AVAILABILITY_ICON = {
  pending: CircleDashed,
  'confirmed-incomplete': CircleAlert,
  'confirmed-window': CircleCheck,
  unavailable: CircleSlash
} as const;

export const OperationsAvailabilityBadge: React.FC<{ item: OperationsSessionItem }> = ({ item }) => {
  const meta = operationsAvailabilityMeta(item);
  const Icon = AVAILABILITY_ICON[meta.view];
  return (
    <span className={`oe-avail oe-avail--${meta.view}`} title={meta.hint}>
      <Icon size={13} aria-hidden="true" />
      {meta.label}
    </span>
  );
};

export const OperationsAvailabilitySummary: React.FC<{ item: OperationsSessionItem }> = ({ item }) => {
  const meta = operationsAvailabilityMeta(item);
  if (meta.view === 'confirmed-window') {
    const window = formatAvailabilityWindow(item);
    return <p className="oe-item-meta">Available {window}</p>;
  }
  return (
    <p className="oe-item-meta">
      {meta.hint}
      {meta.view === 'confirmed-incomplete' ? (
        <span className="oe-item-warn">Scheduling is blocked until a complete window is recorded.</span>
      ) : null}
    </p>
  );
};

const AvailabilityEditor: React.FC<{
  session: OperationsSessionDetail;
  item: OperationsSessionItem;
  blocked: boolean;
  busyCommand: string | null;
  onSave: (choice: OperationsAvailabilityChoice, window: AvailabilityWindowInput) => void;
  onCancel: () => void;
}> = ({ session, item, blocked, busyCommand, onSave, onCancel }) => {
  const choices = availabilityChoicesFor(item);
  const [choice, setChoice] = useState<OperationsAvailabilityChoice>(choices[0] ?? 'window');
  /**
   * The window is edited as a wall clock in the property's own zone, so the server instant is
   * converted for display and the edited value is converted back to the intended instant. The device
   * time zone is never used.
   */
  const initialZoneId = item.availabilityZoneId ?? OPERATIONS_AVAILABILITY_ZONES[0];
  /** What the recorded instants displayed when this editor opened; the untouched baseline. */
  const initialStartField = serverInstantToZoneInput(item.availabilityStartAt, initialZoneId);
  const initialEndField = serverInstantToZoneInput(item.availabilityEndAt, initialZoneId);
  const zoneOptions = useMemo(() => operationsZoneOptions(item.availabilityZoneId), [item.availabilityZoneId]);
  const [window, setWindow] = useState<AvailabilityWindowInput>({
    availabilityStartAt: initialStartField,
    availabilityEndAt: initialEndField,
    availabilityZoneId: initialZoneId,
    availabilitySource: (item.availabilitySource as OperationsAvailabilitySource | null) ?? null
  });
  const [touched, setTouched] = useState(false);
  /** Set when an entered wall clock cannot be resolved in the selected zone (a gap or a repeated hour). */
  const [zoneConversionError, setZoneConversionError] = useState<string | null>(null);
  const formRef = useRef<HTMLFormElement | null>(null);

  useEffect(() => formRef.current?.querySelector<HTMLElement>('input, select')?.focus(), []);

  const selectedZoneId = window.availabilityZoneId ?? '';
  /**
   * Each field is resolved on its own. A field the OE did not touch (same text, same zone) returns the
   * recorded server instant verbatim, so seconds, fractional seconds and the identity of a repeated
   * DST hour survive an unchanged save; only a real edit is converted.
   */
  const startResolution = useMemo(() => resolveAvailabilityFieldInstant({
    value: window.availabilityStartAt,
    initialValue: initialStartField,
    zoneId: selectedZoneId,
    initialZoneId,
    initialInstant: item.availabilityStartAt
  }), [initialStartField, initialZoneId, item.availabilityStartAt, selectedZoneId, window.availabilityStartAt]);
  const endResolution = useMemo(() => resolveAvailabilityFieldInstant({
    value: window.availabilityEndAt,
    initialValue: initialEndField,
    zoneId: selectedZoneId,
    initialZoneId,
    initialInstant: item.availabilityEndAt
  }), [initialEndField, initialZoneId, item.availabilityEndAt, selectedZoneId, window.availabilityEndAt]);
  const windowInput: AvailabilityWindowInput = useMemo(() => {
    if (choice !== 'window') return window;
    return {
      availabilityStartAt: startResolution.value,
      availabilityEndAt: endResolution.value,
      availabilityZoneId: window.availabilityZoneId,
      availabilitySource: window.availabilitySource
    };
  }, [choice, endResolution.value, startResolution.value, window]);

  const validation = setAvailabilityCommand(session, item.itemId, choice, windowInput);
  const errors = validation.errors;
  /**
   * An entered wall clock that the selected zone cannot represent is reported as itself, not as a
   * missing field: the value is present, the zone simply has no single such local time.
   */
  const unrepresentableError = choice === 'window'
    ? (startResolution.error ?? endResolution.error)
    : null;
  const unrepresentable = unrepresentableError !== null;
  const unrepresentableMessage = (): string => {
    if (unrepresentableError === 'ambiguous') {
      return 'That local time happens twice in the selected time zone because the clocks go back. Choose a time that occurs once, or keep the recorded time as it is.';
    }
    if (unrepresentableError === 'gap') {
      return 'That local time doesn’t exist in the selected time zone. Choose a different time.';
    }
    return 'Enter the time the way it appears in the selected time zone.';
  };
  const showError = (key: keyof typeof errors): React.ReactNode =>
    touched && errors[key] ? (
      <p id={`oe-avail-${item.itemId}-${key}-err`} className="oe-reason-error" role="alert">{errors[key]}</p>
    ) : null;
  const describedBy = (key: keyof typeof errors): string | undefined =>
    touched && errors[key] ? `oe-avail-${item.itemId}-${key}-err` : undefined;

  const showWindowFields = choice === 'window';

  return (
    <form
      ref={formRef}
      className="oe-avail-editor"
      aria-label={`Record property availability for ${item.title ?? operationsRecordReference(item.itemId)}`}
      onSubmit={(event) => {
        event.preventDefault();
        setTouched(true);
        if (blocked || validation.command === null || unrepresentable) {
          setZoneConversionError(unrepresentable ? unrepresentableMessage() : null);
          return;
        }
        setZoneConversionError(null);
        onSave(choice, windowInput);
      }}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.stopPropagation();
          onCancel();
        }
      }}
    >
      {choices.length > 1 ? (
        <fieldset className="oe-seg-field">
          <legend className="oe-field-label">What did the lessor confirm?</legend>
          <div className="oe-seg-radios oe-seg-radios--stack">
            {choices.map((option) => (
              <label key={option} className="oe-seg-radio">
                <input
                  type="radio"
                  name={`oe-avail-choice-${item.itemId}`}
                  value={option}
                  checked={choice === option}
                  onChange={() => setChoice(option)}
                />
                <span>{availabilityChoiceLabel(option)}</span>
              </label>
            ))}
          </div>
        </fieldset>
      ) : (
        <p className="oe-inline-note">
          Update the recorded window. Once confirmed, a property can’t be set back to pending or marked
          unavailable here.
        </p>
      )}

      {showWindowFields ? (
        <>
          <div className="oe-avail-grid">
            <div className="oe-field-stack">
              <label className="oe-field-label" htmlFor={`oe-avail-${item.itemId}-start`}>Available from</label>
              <input
                id={`oe-avail-${item.itemId}-start`}
                className="oe-input"
                type="datetime-local"
                value={window.availabilityStartAt ?? ''}
                onChange={(event) => setWindow((current) => ({ ...current, availabilityStartAt: event.target.value }))}
                aria-invalid={Boolean(touched && errors.start)}
                aria-describedby={describedBy('start')}
              />
              {showError('start')}
            </div>
            <div className="oe-field-stack">
              <label className="oe-field-label" htmlFor={`oe-avail-${item.itemId}-end`}>Available until</label>
              <input
                id={`oe-avail-${item.itemId}-end`}
                className="oe-input"
                type="datetime-local"
                value={window.availabilityEndAt ?? ''}
                onChange={(event) => setWindow((current) => ({ ...current, availabilityEndAt: event.target.value }))}
                aria-invalid={Boolean(touched && errors.end)}
                aria-describedby={describedBy('end')}
              />
              {showError('end')}
            </div>
            <div className="oe-field-stack">
              <label className="oe-field-label" htmlFor={`oe-avail-${item.itemId}-zone`}>Time zone</label>
              <select
                id={`oe-avail-${item.itemId}-zone`}
                className="oe-input"
                value={window.availabilityZoneId ?? ''}
                onChange={(event) => setWindow((current) => ({ ...current, availabilityZoneId: event.target.value }))}
                aria-invalid={Boolean(touched && errors.zone)}
                aria-describedby={describedBy('zone')}
              >
                {zoneOptions.map((zone) => (
                  <option key={zone} value={zone}>{zone === 'Asia/Kolkata' ? 'IST (Asia/Kolkata)' : zone}</option>
                ))}
              </select>
              {showError('zone')}
            </div>
            <div className="oe-field-stack">
              <label className="oe-field-label" htmlFor={`oe-avail-${item.itemId}-source`}>Recorded source</label>
              <select
                id={`oe-avail-${item.itemId}-source`}
                className="oe-input"
                value={window.availabilitySource ?? ''}
                onChange={(event) =>
                  setWindow((current) => ({
                    ...current,
                    availabilitySource: (event.target.value || null) as OperationsAvailabilitySource | null
                  }))
                }
                aria-invalid={Boolean(touched && errors.source)}
                aria-describedby={describedBy('source')}
              >
                <option value="">Choose a source</option>
                {OPERATIONS_AVAILABILITY_SOURCE_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
              {showError('source')}
            </div>
          </div>
          {touched && unrepresentable ? (
            <p className="oe-reason-error" role="alert">
              {zoneConversionError ?? unrepresentableMessage()}
            </p>
          ) : null}
          <p className="oe-footnote">
            Start, end, time zone and source are saved together; the server rejects a partial window.
            “WhatsApp” records how the lessor told you, not a WhatsApp integration.
          </p>
        </>
      ) : choice === 'confirm-only' ? (
        <p className="oe-inline-note oe-inline-note--warn">
          Only the confirmation is saved, with no times and no source. Scheduling stays blocked until a
          complete window is recorded.
        </p>
      ) : (
        <p className="oe-inline-note oe-inline-note--warn">
          Only record unavailable when the lessor has confirmed it. An unanswered call isn’t
          confirmation. This is final and can’t be changed afterwards.
        </p>
      )}

      <div className="oe-avail-actions">
        <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" onClick={onCancel}>Cancel</button>
        <button
          type="submit"
          className={`oe-btn oe-btn--md ${choice === 'unavailable' ? 'oe-btn--danger' : 'oe-btn--primary'}`}
          disabled={blocked}
        >
          {busyCommand === 'availability' ? 'Saving…' : choice === 'unavailable' ? 'Record unavailable' : 'Save availability'}
        </button>
      </div>
    </form>
  );
};

const AddStop: React.FC<{
  session: OperationsSessionDetail;
  blocked: boolean;
  busyCommand: string | null;
  searchListings: (query: string, signal: AbortSignal) => Promise<DiscoveryListingOption[]>;
  onAdd: (input: AddStopInput) => void;
  onCancel: () => void;
}> = ({ session, blocked, busyCommand, searchListings, onAdd, onCancel }) => {
  /**
   * The originating Requests come from the session's authoritative linked-Request membership, so a
   * Request whose direct stop was removed is still offered — removing an item never unlinks it, and
   * the backend still accepts a linked Request as the origin of a replacement property.
   */
  const origins = useMemo(() => derivedStopOriginCandidates(session), [session]);
  const [listing, setListing] = useState<DiscoveryListingOption | null>(null);
  const [originatingRequestId, setOriginatingRequestId] = useState<number | null>(origins[0]?.requestId ?? null);
  const [origin, setOrigin] = useState<OperationsDerivedItemOrigin>(OPERATIONS_DERIVED_ITEM_ORIGINS[0].value);
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<DiscoveryListingOption[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState(false);
  const [touched, setTouched] = useState(false);
  const controller = useRef<AbortController | null>(null);
  const debounce = useRef<number | undefined>(undefined);
  const headingRef = useRef<HTMLHeadingElement | null>(null);

  useEffect(() => {
    headingRef.current?.focus();
    return () => {
      window.clearTimeout(debounce.current);
      controller.current?.abort();
    };
  }, []);

  const runSearch = useCallback(
    (value: string) => {
      window.clearTimeout(debounce.current);
      controller.current?.abort();
      const trimmed = value.trim();
      if (trimmed.length === 0) {
        setResults(null);
        setSearching(false);
        setSearchError(false);
        return;
      }
      const next = new AbortController();
      controller.current = next;
      setSearching(true);
      setSearchError(false);
      debounce.current = window.setTimeout(() => {
        void (async () => {
          try {
            const found = await searchListings(trimmed, next.signal);
            if (next.signal.aborted) return;
            setResults(found);
          } catch {
            if (next.signal.aborted) return;
            setSearchError(true);
          } finally {
            if (!next.signal.aborted) setSearching(false);
          }
        })();
      }, 350);
    },
    [searchListings]
  );

  const missingOrigin = origins.length === 0;
  const invalid = touched && (listing === null || originatingRequestId === null);

  return (
    <div className="oe-add">
      <h4 ref={headingRef} tabIndex={-1} className="oe-coord-title">Add a derived property</h4>
      <p className="oe-step-hint">
        Another property the tenant or lessor raised for this visit. The server checks that it is active
        and belongs to this session’s City; public discovery alone doesn’t guarantee that.
      </p>

      {missingOrigin ? (
        <p className="oe-inline-note oe-inline-note--warn" role="alert">
          A derived property must name one of this session’s linked requests as its origin, and this
          session has no linked request on record yet.
        </p>
      ) : (
        <>
          <div className="oe-field-stack">
            <label className="oe-field-label" htmlFor="oe-origin-request">Originating request</label>
            <select
              id="oe-origin-request"
              className="oe-input"
              value={originatingRequestId ?? ''}
              onChange={(event) => setOriginatingRequestId(Number(event.target.value))}
            >
              {origins.map((entry) => (
                <option key={entry.requestId} value={entry.requestId}>
                  {operationsRecordReference(entry.requestId)}
                </option>
              ))}
            </select>
          </div>

          <fieldset className="oe-seg-field">
            <legend className="oe-field-label">Why this property is on the visit</legend>
            <div className="oe-seg-radios oe-seg-radios--stack">
              {OPERATIONS_DERIVED_ITEM_ORIGINS.map((option) => (
                <label key={option.value} className="oe-seg-radio">
                  <input
                    type="radio"
                    name="oe-derived-origin"
                    value={option.value}
                    checked={origin === option.value}
                    onChange={() => setOrigin(option.value)}
                  />
                  <span>{option.label}</span>
                </label>
              ))}
            </div>
          </fieldset>

          <div className="oe-field-stack">
            <label className="oe-field-label" htmlFor="oe-add-listing">Find a property</label>
            <input
              id="oe-add-listing"
              className="oe-input"
              type="text"
              autoComplete="off"
              placeholder="Search published rental properties by title or locality"
              value={query}
              onChange={(event) => {
                setQuery(event.target.value);
                runSearch(event.target.value);
              }}
            />
          </div>
          {searching ? <p className="oe-ppanel-note" role="status">Searching published properties…</p> : null}
          {searchError ? (
            <p className="oe-reason-error" role="alert">
              The property search didn’t complete. Nothing was changed — try again.
            </p>
          ) : null}
          {results !== null && !searching && !searchError ? (
            results.length === 0 ? (
              <p className="oe-inline-note" role="status">No published property matched that search.</p>
            ) : (
              <fieldset className="oe-choice-list">
                <legend className="oe-field-label">Matching properties</legend>
                {results.map((option) => (
                  <label key={option.listingId} className="oe-choice">
                    <input
                      type="radio"
                      name="oe-add-listing-choice"
                      checked={listing?.listingId === option.listingId}
                      onChange={() => setListing(option)}
                    />
                    <span>
                      <span className="oe-choice-title">{option.title}</span>
                      <span className="oe-choice-sub">
                        <span className="oe-mono">{operationsRecordReference(option.listingId)}</span>
                        {' · '}
                        {option.sector ?? 'Locality not provided'}
                      </span>
                    </span>
                  </label>
                ))}
              </fieldset>
            )
          ) : null}
        </>
      )}

      {invalid ? (
        <p className="oe-reason-error" role="alert">
          Choose a published property and an originating request.
        </p>
      ) : null}
      <p className="oe-footnote">New stops always start with availability pending.</p>
      <div className="oe-avail-actions">
        <button type="button" className="oe-btn oe-btn--secondary oe-btn--md" onClick={onCancel}>Cancel</button>
        <button
          type="button"
          className="oe-btn oe-btn--primary oe-btn--md"
          disabled={blocked || missingOrigin}
          onClick={() => {
            setTouched(true);
            if (listing === null || originatingRequestId === null) return;
            onAdd({ listingId: listing.listingId, originatingRequestId, origin });
          }}
        >
          {busyCommand === 'add-item' ? 'Adding…' : 'Add stop'}
        </button>
      </div>
    </div>
  );
};

export const OperationsDraftItinerary: React.FC<Props> = ({
  session,
  editable,
  blocked,
  busyCommand,
  onReorder,
  onRemove,
  onSaveAvailability,
  onAddStop,
  revealItemContact,
  contactContextKey,
  searchListings,
}) => {
  const active = useMemo(() => activeSessionItems(session.items), [session.items]);
  const removed = useMemo(() => removedSessionItems(session.items), [session.items]);
  const [order, setOrder] = useState<number[]>(() => active.map((item) => item.itemId));
  const [editingItemId, setEditingItemId] = useState<number | null>(null);
  const [confirmRemoveId, setConfirmRemoveId] = useState<number | null>(null);
  const [removalReason, setRemovalReason] = useState('');
  const [adding, setAdding] = useState(false);
  const itemRefs = useRef<Record<number, HTMLButtonElement | null>>({});
  const headingRef = useRef<HTMLHeadingElement | null>(null);
  /**
   * Whether the *session root* permits a live contact reveal for its active items. It mirrors the
   * backend's revealable statuses, so a SCHEDULED/STARTED/INTERRUPTED session keeps the approved
   * read-only contact read while terminal work never offers it.
   */
  const contactPermitted = sessionPermitsItemContact(session.status);

  // The authoritative item set changed (added, removed, refreshed, version moved): rebuild the order.
  useEffect(() => {
    setOrder(active.map((item) => item.itemId));
    setEditingItemId(null);
    setConfirmRemoveId(null);
    setRemovalReason('');
  }, [active]);

  const orderedItems = order
    .map((itemId) => active.find((item) => item.itemId === itemId))
    .filter((item): item is OperationsSessionItem => Boolean(item));
  const orderDirty = orderedItems.length === active.length
    && orderedItems.some((item, index) => item.itemId !== active[index].itemId);

  const move = (index: number, delta: -1 | 1) => {
    const target = index + delta;
    if (target < 0 || target >= orderedItems.length) return;
    const next = [...orderedItems];
    const [moved] = next.splice(index, 1);
    next.splice(target, 0, moved);
    setOrder(next.map((item) => item.itemId));
    // Keep focus on the same control of the moved stop so repeated presses keep working.
    window.setTimeout(() => {
      itemRefs.current[moved.itemId]?.focus();
    }, 0);
  };

  if (active.length === 0 && removed.length === 0) {
    return (
      <div className="oe-itin">
        <p className="oe-quiet">No active stops on this session.</p>
        {editable ? (
          <button
            id="oe-add-stop"
            type="button"
            className="oe-btn oe-btn--secondary oe-btn--md"
            onClick={() => setAdding(true)}
            disabled={blocked}
          >
            <CirclePlus size={16} aria-hidden="true" />
            Add a stop
          </button>
        ) : null}
        {adding ? (
          <AddStop
            session={session}
            blocked={blocked}
            busyCommand={busyCommand}
            searchListings={searchListings}
            onAdd={(input) => { setAdding(false); onAddStop(input); }}
            onCancel={() => setAdding(false)}
          />
        ) : null}
      </div>
    );
  }

  return (
    <div className="oe-itin">
      <h3 id="oe-itin-title" ref={headingRef} tabIndex={-1} className="oe-h3">
        {editable ? 'Manage the draft itinerary' : 'Itinerary'}
      </h3>
      <ol className="oe-items">
        {orderedItems.map((item, index) => {
          const removalBlocked = sessionItemRemovalBlockedReason(item);
          const availabilityView = operationsAvailabilityMeta(item).view;
          /**
           * The canonical contact is a permitted read for an active item of a backend-revealable
           * session status — not an editing power — so it is offered in read-only Session views too.
           * It stays unavailable on removed and terminal work.
           */
          const canRevealContact = contactPermitted && item.removedAt === null;
          const canOpenAvailabilityEditor = editable && availabilityView !== 'unavailable';
          const showItemTools = canRevealContact || canOpenAvailabilityEditor;
          return (
            <li key={item.itemId} className="oe-item oe-item--rich">
              <span className="oe-item-order oe-num" aria-hidden="true">{index + 1}</span>
              <div className="oe-item-body">
                <div className="oe-item-top">
                  <p className="oe-item-title">
                    <span className="oe-sr">Stop {index + 1}: </span>
                    {item.title ?? 'Listing title not recorded'}
                  </p>
                  {/* Recorded availability is authoritative detail data, so it is stated in every
                      permitted view rather than only while the itinerary happens to be editable. */}
                  <OperationsAvailabilityBadge item={item} />
                </div>
                <p className="oe-item-meta">
                  {item.sector ?? 'Locality not provided'}
                  {' · '}
                  <span className="oe-mono">
                    {item.listingId === null
                      ? 'Listing ID not recorded'
                      : operationsRecordReference(item.listingId)}
                  </span>
                </p>
                {item.address ? <p className="oe-item-meta">{item.address}</p> : null}
                <p className="oe-item-meta oe-item-prov">
                  {sessionItemOriginLabel(item.origin)}
                  {item.sourceRequestId !== null ? (
                    <> · from <span className="oe-mono">{operationsRecordReference(item.sourceRequestId)}</span></>
                  ) : item.derivedFromRequestId !== null ? (
                    <> · from <span className="oe-mono">{operationsRecordReference(item.derivedFromRequestId)}</span></>
                  ) : null}
                </p>

                <OperationsAvailabilitySummary item={item} />

                {editable && editingItemId === item.itemId ? (
                  <AvailabilityEditor
                    session={session}
                    item={item}
                    blocked={blocked}
                    busyCommand={busyCommand}
                    onSave={(choice, window) => {
                      setEditingItemId(null);
                      onSaveAvailability(item.itemId, choice, window);
                    }}
                    onCancel={() => {
                      setEditingItemId(null);
                      window.setTimeout(() => document.getElementById(`oe-avail-btn-${item.itemId}`)?.focus(), 0);
                    }}
                  />
                ) : null}

                {showItemTools ? (
                  <div className="oe-item-tools">
                    {canOpenAvailabilityEditor && editingItemId !== item.itemId ? (
                      <button
                        id={`oe-avail-btn-${item.itemId}`}
                        type="button"
                        className="oe-btn oe-btn--secondary oe-btn--sm"
                        onClick={() => setEditingItemId(item.itemId)}
                        disabled={blocked}
                      >
                        {availabilityView === 'confirmed-window'
                          ? 'Update window'
                          : 'Record availability'}
                      </button>
                    ) : null}
                    {canRevealContact ? (
                      <OperationsLessorContactPanel
                        contextKey={`${contactContextKey}|item:${item.itemId}`}
                        subject={item.title ?? operationsRecordReference(item.itemId)}
                        reveal={(signal) => revealItemContact(item.itemId, signal)}
                        compact
                      />
                    ) : null}
                  </div>
                ) : null}

                {editable ? (
                  <>
                    <div className="oe-item-order-tools" role="group" aria-label={`Arrange ${item.title ?? operationsRecordReference(item.itemId)}`}>
                      <button
                        ref={(node) => { itemRefs.current[item.itemId] = node; }}
                        type="button"
                        className="oe-icon-btn oe-icon-btn--line"
                        disabled={index === 0 || blocked}
                        aria-label={`Move ${item.title ?? operationsRecordReference(item.itemId)} earlier`}
                        onClick={() => move(index, -1)}
                      >
                        <ArrowUp size={16} aria-hidden="true" />
                      </button>
                      <button
                        type="button"
                        className="oe-icon-btn oe-icon-btn--line"
                        disabled={index === orderedItems.length - 1 || blocked}
                        aria-label={`Move ${item.title ?? operationsRecordReference(item.itemId)} later`}
                        onClick={() => move(index, 1)}
                      >
                        <ArrowDown size={16} aria-hidden="true" />
                      </button>
                      {confirmRemoveId === item.itemId ? (
                        <span className="oe-remove-confirm">
                          <label className="oe-field-label" htmlFor={`oe-remove-reason-${item.itemId}`}>
                            Reason (optional)
                          </label>
                          <input
                            id={`oe-remove-reason-${item.itemId}`}
                            className="oe-input"
                            type="text"
                            maxLength={OPERATIONS_REMOVAL_REASON_MAX}
                            value={removalReason}
                            onChange={(event) => setRemovalReason(event.target.value)}
                          />
                          <button
                            type="button"
                            className="oe-btn oe-btn--danger oe-btn--sm"
                            disabled={blocked}
                            onClick={() => {
                              const reason = removalReason.trim();
                              setConfirmRemoveId(null);
                              setRemovalReason('');
                              onRemove(item.itemId, reason.length > 0 ? reason : null);
                            }}
                          >
                            {busyCommand === 'remove-item' ? 'Removing…' : 'Remove stop'}
                          </button>
                          <button
                            type="button"
                            className="oe-btn oe-btn--secondary oe-btn--sm"
                            onClick={() => {
                              setConfirmRemoveId(null);
                              setRemovalReason('');
                            }}
                          >
                            Keep
                          </button>
                        </span>
                      ) : isSessionItemRemovable(item) ? (
                        <button
                          type="button"
                          className="oe-btn oe-btn--ghost oe-btn--sm"
                          disabled={blocked}
                          aria-label={`Remove ${item.title ?? operationsRecordReference(item.itemId)}`}
                          onClick={() => setConfirmRemoveId(item.itemId)}
                        >
                          <Trash2 size={14} aria-hidden="true" />
                          Remove
                        </button>
                      ) : (
                        <span className="oe-item-meta">{removalBlocked}</span>
                      )}
                    </div>
                  </>
                ) : null}
              </div>
            </li>
          );
        })}
      </ol>

      {editable && orderDirty ? (
        <div className="oe-order-actions">
          <p className="oe-inline-note" role="status">
            The order below isn’t saved yet. Every active stop is saved together as one exact order.
          </p>
          <div className="oe-avail-actions">
            <button
              type="button"
              className="oe-btn oe-btn--secondary oe-btn--md"
              onClick={() => setOrder(active.map((item) => item.itemId))}
              disabled={blocked}
            >
              Reset order
            </button>
            <button
              type="button"
              className="oe-btn oe-btn--primary oe-btn--md"
              disabled={blocked}
              onClick={() => onReorder(orderedItems.map((item) => item.itemId))}
            >
              {busyCommand === 'reorder' ? 'Saving order…' : 'Save order'}
            </button>
          </div>
        </div>
      ) : null}

      {editable ? (
        adding ? (
          <AddStop
            session={session}
            blocked={blocked}
            busyCommand={busyCommand}
            searchListings={searchListings}
            onAdd={(input) => { setAdding(false); onAddStop(input); }}
            onCancel={() => setAdding(false)}
          />
        ) : (
          <button
            id="oe-add-stop"
            type="button"
            className="oe-btn oe-btn--secondary oe-btn--md"
            onClick={() => setAdding(true)}
            disabled={blocked}
          >
            <CirclePlus size={16} aria-hidden="true" />
            Add a stop
          </button>
        )
      ) : null}

      {removed.length > 0 ? (
        <div className="oe-removed">
          <h3 className="oe-h3">Removed stops ({removed.length})</h3>
          <ul className="oe-removed-list">
            {removed.map((item) => (
              <li key={item.itemId}>
                <span className="oe-removed-title">{item.title ?? operationsRecordReference(item.itemId)}</span>
                <span className="oe-item-meta">
                  <span className="oe-mono">
                    {item.listingId === null ? 'Listing ID not recorded' : operationsRecordReference(item.listingId)}
                  </span>
                  {item.removalReason ? ` · ${item.removalReason}` : ''}
                </span>
              </li>
            ))}
          </ul>
          <p className="oe-footnote">Removed stops stay in the session history and aren’t visited.</p>
        </div>
      ) : null}
    </div>
  );
};
