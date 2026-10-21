import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

const require = createRequire(new URL('../../../package.json', import.meta.url));
const ts = require('typescript');

const workspaceUrl = new URL('../../components/operations/OperationsWorkspace.tsx', import.meta.url);

const parseWorkspace = () => {
  const source = readFileSync(workspaceUrl, 'utf8');
  const sourceFile = ts.createSourceFile('OperationsWorkspace.tsx', source, ts.ScriptTarget.Latest, true,
    ts.ScriptKind.TSX);
  return { source, sourceFile };
};

const walk = (node, visit) => {
  if (visit(node) === false) return;
  ts.forEachChild(node, child => walk(child, visit));
};

const variableDeclaration = (sourceFile, name) => {
  let found = null;
  walk(sourceFile, node => {
    if (found) return false;
    if (!ts.isVariableDeclaration(node) || node.name.getText(sourceFile) !== name) return;
    found = node;
    return false;
  });
  return found;
};

const useCallbackArrow = (declaration, sourceFile) => {
  if (!declaration?.initializer || !ts.isCallExpression(declaration.initializer)) return null;
  const first = declaration.initializer.arguments[0];
  if (!first) return null;
  if (ts.isArrowFunction(first) || ts.isFunctionExpression(first)) return first;
  return null;
};

const findProperty = (node, propertyName, sourceFile) => {
  let found = null;
  walk(node, child => {
    if (found) return false;
    if (!ts.isPropertyAssignment(child)) return;
    const name = child.name.getText(sourceFile).replace(/^['"]|['"]$/g, '');
    if (name !== propertyName) return;
    found = child.initializer;
    return false;
  });
  return found;
};

const findRunGuardedCommandOptions = (arrow, sourceFile) => {
  let found = null;
  walk(arrow.body, node => {
    if (found) return false;
    if (!ts.isCallExpression(node)) return;
    if (node.expression.getText(sourceFile) !== 'runGuardedCommand') return;
    const options = node.arguments[0];
    if (options && ts.isObjectLiteralExpression(options)) found = options;
    return false;
  });
  return found;
};

const expressionText = (node, sourceFile) =>
  node ? ts.transpileModule(`return (${node.getText(sourceFile)});`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
  }).outputText : null;

/**
 * Extracts the actual scheduling callbacks from the production component with the TypeScript AST.
 *
 * No fixed character window and no test-only reimplementation is used: property names are resolved
 * from the real `runGuardedCommand` options object, so comments, added properties or an additive
 * generic signature cannot silently move the test onto different code.
 */
export const extractSchedulingCallbacks = () => {
  const { sourceFile } = parseWorkspace();
  const confirm = variableDeclaration(sourceFile, 'confirmSelection');
  const arrow = useCallbackArrow(confirm, sourceFile);
  if (!arrow) throw new Error('confirmSelection useCallback was not found');
  const options = findRunGuardedCommandOptions(arrow, sourceFile);
  if (!options) throw new Error('runGuardedCommand options were not found');
  const callback = name => {
    const node = findProperty(options, name, sourceFile);
    if (!node) throw new Error(`scheduling callback ${name} was not found`);
    return expressionText(node, sourceFile);
  };
  const drawerDeclaration = (() => {
    let found = null;
    walk(arrow.body, node => {
      if (found) return false;
      if (!ts.isVariableDeclaration(node) || node.name.getText(sourceFile) !== 'drawer') return;
      found = node;
      return false;
    });
    return found;
  })();
  const drawerText = drawerDeclaration?.getText(sourceFile) ?? '';
  const commandPosition = drawerText ? sourceFile.getLineAndCharacterOfPosition(drawerDeclaration.getStart()) : null;
  const confirmText = arrow.getText(sourceFile);
  return {
    sourceFile,
    confirmSource: confirmText,
    drawerCapturedBeforeCommand:
      drawerText.includes('scheduleDrawerRef.current')
      && commandPosition !== null
      && confirmText.indexOf('await runGuardedCommand') > confirmText.indexOf(drawerText),
    onFailureSource: callback('onFailure'),
    applyResultSource: callback('applyResult'),
    onMissingVersionSource: callback('onMissingVersion')
  };
};

/**
 * Extracts the real exact-root withdrawal callback. It is executed with injected state so a denial
 * can be checked against a remount of the same Session, another root and an account change.
 */
export const extractWithdrawDeniedRoot = () => {
  const { sourceFile } = parseWorkspace();
  const declaration = variableDeclaration(sourceFile, 'withdrawDeniedRoot');
  const arrow = useCallbackArrow(declaration, sourceFile);
  if (!arrow) throw new Error('withdrawDeniedRoot useCallback was not found');
  return expressionText(arrow, sourceFile);
};

/** Extracts the real shared failure reporter, including its synchronous withdrawal ordering. */
export const extractReportCommandFailure = () => {
  const { sourceFile } = parseWorkspace();
  const declaration = variableDeclaration(sourceFile, 'reportCommandFailure');
  const arrow = useCallbackArrow(declaration, sourceFile);
  if (!arrow) throw new Error('reportCommandFailure useCallback was not found');
  return expressionText(arrow, sourceFile);
};

/** Compiles one extracted callback into a callable with explicit production dependencies. */
export const compileCallback = (callbackSource, dependencyNames, dependencyValues) => {
  const factory = new Function(...dependencyNames, callbackSource);
  return factory(...dependencyValues);
};

export const compileExpression = (expressionSource, dependencyNames, dependencyValues) => {
  const factory = new Function(...dependencyNames, expressionSource);
  return factory(...dependencyValues);
};
