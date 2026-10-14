/**
 * Node loader hook used by `operationsWorkspaceSession.test.mjs`.
 *
 * Two narrow adaptations let the unmodified production OE service run under `node --test`:
 *  1. `config/endpoints` reads `import.meta.env` (Vite-only) and is redirected to a stub.
 *  2. The bundleless TypeScript sources use extensionless relative imports (`./apiError`), which
 *     Node's ESM resolver does not guess, so `.ts` / `/index.ts` are tried before giving up.
 * No other resolution behaviour changes, and no production file is modified for testability.
 */
const ENDPOINTS_STUB = new URL('./endpointsStub.mjs', import.meta.url).href;

/** Synchronous by design so it can be installed with `module.registerHooks()`. */
export function resolve(specifier, context, nextResolve) {
  if (specifier === '../config/endpoints' || specifier.endsWith('/config/endpoints')) {
    return { url: ENDPOINTS_STUB, shortCircuit: true, format: 'module' };
  }
  if (specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier)) {
    for (const candidate of [`${specifier}.ts`, `${specifier}/index.ts`]) {
      try {
        return nextResolve(candidate, context);
      } catch {
        // Try the next candidate, then fall back to Node's own resolution.
      }
    }
  }
  return nextResolve(specifier, context);
}
