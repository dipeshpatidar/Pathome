/** Compatibility entry point. Genuine login/browser evidence lives in the Phase 2A runner.
 * Synthetic shell denials, role/storage seeding and fabricated cache headers have been removed.
 * Requires the bound disposable run and phase2a-browser-fixtures.json from API fixture setup.
 */
import { runBrowser } from '../../../tools/oe-integration-env/phase2a/browser.mjs';
await runBrowser();
