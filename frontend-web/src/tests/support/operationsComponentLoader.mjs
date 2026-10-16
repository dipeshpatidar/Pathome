import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { resolve as serviceResolve } from './operationsServiceLoader.mjs';
const require = createRequire(new URL('../../../package.json', import.meta.url));
export const ts = require('typescript');
export function resolve(specifier, context, next) {
  try { return serviceResolve(specifier, context, next); }
  catch (error) {
    if (specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier)) return next(specifier + '.tsx', context);
    throw error;
  }
}
export function load(url, context, next) {
  if (url.endsWith('.css')) return { format: 'module', source: 'export default {};', shortCircuit: true };
  if (/\.tsx?$/.test(url)) return { format: 'module', shortCircuit: true,
    source: ts.transpileModule(readFileSync(new URL(url), 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.ESNext, jsx: ts.JsxEmit.React }
    }).outputText };
  return next(url, context);
}
