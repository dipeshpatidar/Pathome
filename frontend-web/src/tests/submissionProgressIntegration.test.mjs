import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const workspace = read('../components/LessorWorkspace.tsx');
const component = read('../components/pathome-submission-progress.tsx');
const styles = read('../components/pathome-submission-progress.module.css');
const api = read('../services/lessorSubmissionProgressService.ts');
const progress = read('../../../backend/src/main/java/com/indore/pathome/spaces/service/LandlordSubmissionProgressService.java');
const promotion = read('../../../backend/src/main/java/com/indore/pathome/spaces/service/LandlordMediaPromotionService.java');
const submissionController = read('../../../backend/src/main/java/com/indore/pathome/spaces/controller/LandlordSubmissionController.java');

test('approved v0 overlay receives only backend progress and removes the plain processing card', () => {
  assert.match(workspace, /import PathomeSubmissionProgress from '\.\/pathome-submission-progress'/);
  assert.match(workspace, /percent=\{visibleSubmissionProgress\.percent\}/);
  assert.match(workspace, /visibleSubmissionProgress\.status === 'PROCESSING_MEDIA'/);
  assert.match(workspace, /visibleSubmissionProgress\.status === 'SAVING_PROPERTY'/);
  assert.doesNotMatch(workspace, /setInterval\(|percent\s*\+=|Math\.min\([^\n]*percent/);
  assert.doesNotMatch(component, /submissionProgressExamples|percent: 12|percent: 68/);
  assert.doesNotMatch(workspace, /Submitting your property…/);
});

test('polling uses an authenticated owner-scoped read, bounded delay, and unmount cleanup', () => {
  assert.match(api, /\/lessor\/properties\/drafts\/\$\{encodeURIComponent\(draftId\)\}\/submission-progress/);
  assert.match(api, /Authorization: `Bearer \$\{token\}`/);
  assert.match(workspace, /lessorSubmissionProgressService\.get\(tracked\.progress\.submissionId\)/);
  assert.match(workspace, /window\.setTimeout\(\(\) => void poll\(\), 1800\)/);
  assert.match(workspace, /return \(\) => \{[\s\S]*?cancelled = true;[\s\S]*?window\.clearTimeout\(timeoutId\)/);
  assert.match(workspace, /tracked\.progress\.completed \|\| tracked\.progress\.failed/);
});

test('media detail and success/failure actions depend on backend values and existing routes', () => {
  assert.match(workspace, /visibleSubmissionProgress\.totalMedia > 0[\s\S]*?processedMedia\} of \$\{visibleSubmissionProgress\.totalMedia\} media files processed/);
  assert.match(workspace, /visibleSubmissionProgress\.failed && draftId[\s\S]*?finishSubmission\(draftId\)/);
  assert.match(workspace, /visibleListingId !== undefined && visibleListingId > 0[\s\S]*?\/lessor\/listings\/\$\{visibleListingId\}/);
  assert.match(workspace, /navigate\('\/lessor', \{ replace: true, state: null \}\)/);
});

test('backend percentage follows completed-media events and only commit success reaches one hundred', () => {
  assert.match(progress, /return \(int\) \(90L \* Math\.min\(processed, total\) \/ total\)/);
  assert.match(progress, /SAVING_PROPERTY, current\.percent\(\)/);
  assert.match(progress, /COMPLETED, 100/);
  assert.match(promotion, /store\.complete\(email, draftId, row\.getMediaId\(\), result\.orElseThrow\(\)\);\s*progress\.mediaProcessed\(owner, draftId, row\.getMediaId\(\)\)/);
  assert.match(submissionController, /LandlordSubmission result = submissions\.submit\(auth\.getName\(\), draftId\);\s*progress\.complete\(ownerId, draftId\)/);
  assert.match(submissionController, /@GetMapping\("\/submission-progress"\)[\s\S]*?progress\.get\(auth\.getName\(\), draftId\)/);
});

test('the copied v0 responsive styles remain intact at compact mobile and desktop breakpoints', () => {
  assert.match(styles, /\.card \{[\s\S]*?width: min\(100%, 520px\)/);
  assert.match(styles, /@media \(max-width: 560px\)/);
  assert.match(styles, /\.primaryButton, \.secondaryButton \{ width: 100%; \}/);
  assert.match(styles, /@media \(prefers-reduced-motion: reduce\)/);
});
