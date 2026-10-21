/**
 * Hook harness for the real `OperationsDraftItinerary` parent and its real `AvailabilityEditor`.
 *
 * The itinerary is controlled by a workspace-owned `availability` object, exactly as production
 * passes it down. This harness supplies that object in memory, so tests can drive the OE's actual
 * change/save/cancel handlers across a re-render or an authoritative refresh. It is a synchronous
 * hook harness over extracted component bodies, not a mounted browser.
 */
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const require = createRequire(new URL('../../../package.json', import.meta.url));
const ts = require('typescript');
const React = require('react');

const ITINERARY = fileURLToPath(
  new URL('../../components/operations/OperationsDraftItinerary.tsx', import.meta.url)
);

const compile = () => {
  const source = ts.createSourceFile(
    ITINERARY,
    readFileSync(ITINERARY, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TSX
  );
  const found = {};
  const visit = (node) => {
    if (ts.isVariableDeclaration(node)) {
      const name = node.name.getText(source);
      if (name === 'AvailabilityEditor' || name === 'OperationsDraftItinerary') {
        found[name] = node.initializer.getText(source);
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
  return ts.transpileModule(
    `globalThis.__p2parts = {
      AvailabilityEditor: ${found.AvailabilityEditor},
      OperationsDraftItinerary: ${found.OperationsDraftItinerary}
    };`,
    { compilerOptions: { target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React } }
  ).outputText;
};

const COMPILED = compile();

export const IDENTITY = '41|EMPLOYEE|deadbeef';

/**
 * Creates one mounted itinerary. `onSave` may return a promise; by default a successful save is
 * assumed. `legacyCloseBeforeSave` and `ignoreSavingStatus` exist only for isolated negative controls.
 */
export const createItineraryHarness = (presentation, options) => {
  const cells = [];
  const pendingEffects = [];
  let nextCell = 0;
  let props = null;
  let draft = null;
  let editorGeneration = 0;
  let deferredSave = null;
  const saveCalls = [];
  const discards = [];
  const editorCells = [];
  const editorEffects = [];
  let editorNextCell = 0;

  const state = {
    get draft() {
      return draft;
    },
    saveCalls,
    discards
  };
  const shallow = (left, right) =>
    left.length === right.length && left.every((value, index) => Object.is(value, right[index]));
  const useState = (initial) => {
    const cell = nextCell++;
    if (!(cell in cells)) cells[cell] = typeof initial === 'function' ? initial() : initial;
    return [
      cells[cell],
      (next) => {
        cells[cell] = typeof next === 'function' ? next(cells[cell]) : next;
      }
    ];
  };
  const useEffect = (effect, deps) => {
    const cell = `effect:${nextCell++}`;
    if (!(cell in cells)) {
      cells[cell] = deps;
      pendingEffects.push(effect);
      return;
    }
    if (deps !== undefined && shallow(cells[cell] ?? [], deps)) return;
    cells[cell] = deps;
    pendingEffects.push(effect);
  };
  const useMemo = (factory, deps) => {
    const cell = `memo:${nextCell++}`;
    if (!(cell in cells) || deps === undefined || !shallow(cells[cell].deps ?? [], deps)) {
      cells[cell] = { deps, value: factory() };
    }
    return cells[cell].value;
  };
  const useRef = (initial) => {
    const cell = `ref:${nextCell++}`;
    if (!(cell in cells)) cells[cell] = { current: initial ?? null };
    return cells[cell];
  };
  const editorUseState = (initial) => {
    const cell = editorNextCell++;
    if (!(cell in editorCells)) {
      editorCells[cell] = typeof initial === 'function' ? initial() : initial;
    }
    return [
      editorCells[cell],
      (next) => {
        editorCells[cell] = typeof next === 'function' ? next(editorCells[cell]) : next;
      }
    ];
  };
  const editorUseEffect = (effect, deps) => {
    const cell = `effect:${editorNextCell++}`;
    if (!(cell in editorCells)) {
      editorCells[cell] = deps;
      editorEffects.push(effect);
      return;
    }
    if (deps !== undefined && shallow(editorCells[cell] ?? [], deps)) return;
    editorCells[cell] = deps;
    editorEffects.push(effect);
  };
  const editorUseMemo = (factory, deps) => {
    const cell = `memo:${editorNextCell++}`;
    if (!(cell in editorCells) || deps === undefined || !shallow(editorCells[cell].deps ?? [], deps)) {
      editorCells[cell] = { deps, value: factory() };
    }
    return editorCells[cell].value;
  };
  const editorUseRef = (initial) => {
    const cell = `ref:${editorNextCell++}`;
    if (!(cell in editorCells)) editorCells[cell] = { current: initial ?? null };
    return editorCells[cell];
  };

  const context = {
    ...presentation,
    React,
    useMemo,
    useEffect,
    useRef,
    useState,
    window: { setTimeout: (fn) => fn() },
    document: { getElementById: () => null },
    ArrowDown: () => null,
    ArrowUp: () => null,
    CircleAlert: () => null,
    CircleCheck: () => null,
    CircleDashed: () => null,
    CirclePlus: () => null,
    CircleSlash: () => null,
    Trash2: () => null,
    OperationsAvailabilityBadge: () => null,
    OperationsAvailabilitySummary: () => null,
    OperationsPropertyThumb: () => null,
    AddStop: () => null,
    OperationsLessorContactPanel: () => null
  };
  vm.runInNewContext(COMPILED, context);
  context.AvailabilityEditor = context.__p2parts.AvailabilityEditor;

  const availability = () => ({
    draft,
    begin(itemId) {
      const item = props.session.items.find(
        (candidate) => candidate.itemId === itemId && candidate.removedAt === null
      );
      if (!item) throw new Error(`item ${itemId} is not active`);
      editorGeneration += 1;
      draft = presentation.createAvailabilityDraft({
        sessionId: props.session.sessionId,
        sessionVersion: props.session.version,
        item,
        identity: IDENTITY,
        authorizationGeneration: 1,
        editorGeneration
      });
    },
    change(generation, values) {
      if (!draft || draft.editorGeneration !== generation) return;
      draft = { ...draft, ...values };
      if (draft.status === 'editing' || draft.status === 'refused') {
        draft = { ...draft, status: 'editing', failureMessage: null };
      }
    },
    discard(generation) {
      if (!draft || draft.editorGeneration !== generation) return;
      discards.push(generation);
      if (options.legacyCancelKeepsDraft) return;
      draft = null;
    },
    save(generation, window) {
      if (!draft || draft.editorGeneration !== generation) {
        return Promise.resolve({ status: 'superseded' });
      }
      const submitted = draft;
      saveCalls.push({ generation, window, draft: submitted });
      if (options.legacyCloseBeforeSave) draft = null;
      if (!options.ignoreSavingStatus && draft) draft = { ...draft, status: 'saving' };
      const outcome = options.onSave
        ? options.onSave({ generation, window, draft: submitted, host: state })
        : Promise.resolve({ status: 'saved' });
      return outcome.then((result) => {
        if (!draft || draft.editorGeneration !== generation) return result;
        if (result.status === 'saved') draft = null;
        else {
          draft = {
            ...draft,
            status: result.status === 'unconfirmed' ? 'uncertain' : 'refused',
            failureMessage: result.message ?? 'The save did not complete.'
          };
        }
        return result;
      });
    }
  });

  const render = () => {
    nextCell = 0;
    const element = context.__p2parts.OperationsDraftItinerary({ ...props, availability: availability() });
    while (pendingEffects.length > 0) {
      const effect = pendingEffects.shift();
      effect();
      nextCell = 0;
    }
    return element;
  };
  const all = (root) => {
    const found = [];
    const walk = (node) => {
      if (!node || typeof node !== 'object') return;
      found.push(node);
      React.Children.toArray(node.props?.children).forEach(walk);
    };
    walk(root);
    return found;
  };
  const editorElement = () => {
    const element = all(render()).find((node) => node.type === context.AvailabilityEditor);
    return element ?? null;
  };
  /**
   * Renders the editor element the parent produced. React does not execute a child element in the
   * parent's element tree, so the harness runs the real editor body with its own hook cells.
   */
  const renderEditor = () => {
    const element = editorElement();
    if (!element) return null;
    editorNextCell = 0;
    const parentHooks = {
      useState: context.useState,
      useEffect: context.useEffect,
      useMemo: context.useMemo,
      useRef: context.useRef
    };
    context.useState = editorUseState;
    context.useEffect = editorUseEffect;
    context.useMemo = editorUseMemo;
    context.useRef = editorUseRef;
    try {
      const output = context.AvailabilityEditor(element.props);
      while (editorEffects.length > 0) editorEffects.shift()();
      return output;
    } finally {
      context.useState = parentHooks.useState;
      context.useEffect = parentHooks.useEffect;
      context.useMemo = parentHooks.useMemo;
      context.useRef = parentHooks.useRef;
    }
  };
  const control = (id) => all(render()).find((node) => node.props?.id === id) ?? null;
  const inside = (root, predicate) => all(root).find(predicate) ?? null;

  props = {
    session: options.session,
    editable: true,
    blocked: false,
    busyCommand: null,
    onReorder: () => {},
    onRemove: () => {},
    onAddStop: () => {},
    revealItemContact: async () => ({}),
    contactContextKey: 'ctx',
    searchListings: async () => []
  };
  render();

  return {
    internal: state,
    saveCalls,
    discards,
    render,
    setSession(session) {
      // Mirrors `openDetail`: a different Session drops the one bounded draft.
      const activeItem = draft
        ? session.items.find((item) => item.itemId === draft.itemId && item.removedAt === null)
        : null;
      if (draft && (draft.sessionId !== session.sessionId
        || session.status !== 'DRAFT'
        || !activeItem
        || activeItem.confirmationStatus === 'UNAVAILABLE')) {
        draft = null;
      }
      props = { ...props, session };
    },
    refreshSession(session) {
      if (draft && draft.sessionId !== session.sessionId) throw new Error('refresh changed the session');
      if (options.legacyResetOnRefresh) draft = null;
      props = { ...props, session };
    },
    setBlocked(blocked) {
      props = { ...props, blocked };
    },
    openEditor(itemId) {
      const button = control(`oe-avail-btn-${itemId}`);
      if (!button) throw new Error(`Record availability control missing for ${itemId}`);
      button.props.onClick();
      render();
    },
    hasEditor() {
      return editorElement() !== null;
    },
    type(field, value) {
      const output = renderEditor();
      if (!output) throw new Error('no editor is open');
      const input = inside(output, (node) => node.props?.id === `oe-avail-${props.session.items
        .find((item) => item.itemId === draft.itemId)?.itemId}-${field}`);
      if (!input) throw new Error(`the ${field} field is not rendered`);
      input.props.onChange({ target: { value } });
    },
    zone(zoneId) {
      const output = renderEditor();
      if (!output) throw new Error('no editor is open');
      const itemId = draft.itemId;
      const select = inside(output, (node) => node.props?.id === `oe-avail-${itemId}-zone`);
      select.props.onChange({ target: { value: zoneId } });
    },
    submit() {
      const output = renderEditor();
      if (!output) throw new Error('no editor is open');
      const form = inside(output, (node) => node.props?.className === 'oe-avail-editor');
      form.props.onSubmit({ preventDefault() {} });
    },
    cancel() {
      const output = renderEditor();
      if (!output) throw new Error('no editor is open');
      const form = inside(output, (node) => node.props?.className === 'oe-avail-editor');
      form.props.onKeyDown({ key: 'Escape', stopPropagation() {} });
    },
    state() {
      const output = renderEditor();
      if (!output) return { open: false, draft };
      const itemId = draft.itemId;
      const value = (suffix) => inside(output, (node) =>
        node.props?.id === `oe-avail-${itemId}-${suffix}`)?.props.value ?? null;
      const failure = inside(output, (node) => node.props?.['data-availability-failure'] !== undefined);
      const notes = all(output)
        .filter((node) => node.props?.role === 'status')
        .map((node) => node.props.children);
      const zoneError = all(output)
        .filter((node) => node.props?.role === 'alert'
          && node.props?.id === undefined
          && node.props?.['data-availability-failure'] === undefined
          && typeof node.props.children === 'string')
        .map((node) => node.props.children)[0] ?? null;
      const submit = inside(output, (node) =>
        node.type === 'button' && node.props?.type === 'submit');
      return {
        open: true,
        start: value('start'),
        end: value('end'),
        zone: value('zone'),
        source: value('source'),
        failureMessage: failure ? failure.props.children : null,
        zoneError,
        statusNotes: notes,
        submitDisabled: submit ? submit.props.disabled : null,
        draft
      };
    }
  };
};
