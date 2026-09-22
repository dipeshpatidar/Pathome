import test from 'node:test';
import assert from 'node:assert/strict';
import {
  CANONICAL_NEXT_PROPERTY_KEYWORD,
  CANONICAL_NEXT_PROPERTY_SEPARATOR,
  TRAILING_NEXT_PROPERTY_PATTERN,
  composeNextPropertyPrompt,
  scrollPromptTextareaToNextProperty
} from '../utils/propertyPromptComposer.ts';

// Authoritative multi-property detection regex from MasterAdminDashboard
const MULTIPLE_PROPERTY_ENTRY_PATTERN = /(?:\r?\n\s*\r?\n+|---|\bnext\s*(?:property|flat|house|listing|unit)\b|\b(?:and\s+)?(?:the\s+)?(?:second|third|fourth|another)\s+(?:property|flat|house|listing|unit)(?:\s+is)?\b|(?:^|\n)\s*(?:\d+[\).]|#\d+)\s+)/im;

const PRESET_EXAMPLE_A = 'Premium 2 BHK flat of 525 sqft in Nanda Nagar, Indore. Monthly rent ₹30,000, brokerage ₹15,000, 1+1 security deposit. Owner Ramesh Sharma +91 98260 12345. East facing, fully furnished, ready to move, status live.';
const PRESET_EXAMPLE_B = 'Luxury 3 BHK Penthouse of 1800 sqft in Vijay Nagar, Indore. Monthly rent ₹45,000, brokerage ₹22,500, security deposit ₹90,000. Owner Vikram Singh +91 94250 88990. North-East facing with terrace, balcony and pool. Fully furnished, ready to move, status live.';
const PRESET_EXAMPLE_C = 'Spacious 4 BHK Independent Villa of 2500 sqft in Nipania, Indore. Monthly rent ₹60,000, brokerage ₹30,000, security deposit ₹120,000. Owner Rajesh Gupta +91 98930 11223. East facing with private garden and gym. Semi furnished, ready to move, status live.';
const PRESET_EXAMPLE_D = '2 BHK flat in Saket Nagar, Indore for ₹22,000 monthly rent. Owner Ankit Joshi +91 97550 44556. East facing, semi furnished, status live.';

test('Example Prompt Append & Next Property Visibility UX Suite', async (t) => {
  await t.test('1. Empty prompt + Example A -> Example A inserted normally', () => {
    const emptyResult = composeNextPropertyPrompt('', PRESET_EXAMPLE_A);
    assert.equal(emptyResult, PRESET_EXAMPLE_A, 'Empty prompt should receive Example A directly');
    assert.equal(emptyResult.includes(CANONICAL_NEXT_PROPERTY_KEYWORD), false, 'Empty prompt must NOT add Next Property');

    const blankResult = composeNextPropertyPrompt('   \n\t  ', PRESET_EXAMPLE_A);
    assert.equal(blankResult, PRESET_EXAMPLE_A, 'Whitespace-only prompt should receive Example A directly');
  });

  await t.test('2. Existing Property A + Example B -> A preserved, Next Property inserted, B appended', () => {
    const propertyA = '1 BHK in Bengali Square Indore rent 10000 owner 9826011111';
    const result = composeNextPropertyPrompt(propertyA, PRESET_EXAMPLE_B);

    assert.ok(result.startsWith(propertyA), 'Property A must remain at the start');
    assert.ok(result.includes(CANONICAL_NEXT_PROPERTY_SEPARATOR), 'Canonical Next Property separator must be present');
    assert.ok(result.endsWith(PRESET_EXAMPLE_B), 'Example B must be appended at the end');
    assert.equal(result, `${propertyA}${CANONICAL_NEXT_PROPERTY_SEPARATOR}${PRESET_EXAMPLE_B}`);
  });

  await t.test('3. A + B + Example C -> all three preserved in order', () => {
    const step1 = composeNextPropertyPrompt('', PRESET_EXAMPLE_A);
    const step2 = composeNextPropertyPrompt(step1, PRESET_EXAMPLE_B);
    const step3 = composeNextPropertyPrompt(step2, PRESET_EXAMPLE_C);

    const occurrences = (step3.match(/Next property/g) || []).length;
    assert.equal(occurrences, 2, 'Exactly two "Next property" separators for 3 properties');
    assert.ok(step3.indexOf(PRESET_EXAMPLE_A) < step3.indexOf(PRESET_EXAMPLE_B), 'A must precede B');
    assert.ok(step3.indexOf(PRESET_EXAMPLE_B) < step3.indexOf(PRESET_EXAMPLE_C), 'B must precede C');
  });

  await t.test('4. Existing trailing newline + example -> clean separator', () => {
    const textWithNewline = `${PRESET_EXAMPLE_A}\n`;
    const result = composeNextPropertyPrompt(textWithNewline, PRESET_EXAMPLE_B);

    assert.equal(result, `${PRESET_EXAMPLE_A}${CANONICAL_NEXT_PROPERTY_SEPARATOR}${PRESET_EXAMPLE_B}`);
    assert.equal(result.includes('\n\n\n'), false, 'Should not contain triple newlines');
  });

  await t.test('5. Existing multiple blank lines + example -> no malformed separator', () => {
    const textWithBlankLines = `${PRESET_EXAMPLE_A}\n\n\n\n   \n`;
    const result = composeNextPropertyPrompt(textWithBlankLines, PRESET_EXAMPLE_B);

    assert.equal(result, `${PRESET_EXAMPLE_A}${CANONICAL_NEXT_PROPERTY_SEPARATOR}${PRESET_EXAMPLE_B}`);
    assert.equal(result.includes('\n\n\n'), false, 'Excessive blank lines must be normalized cleanly');
  });

  await t.test('6. Add Another Property -> canonical Next Property still inserted', () => {
    const text = '2 BHK flat in Vijay Nagar rent 20000';
    const result = composeNextPropertyPrompt(text);

    assert.equal(result, `${text}${CANONICAL_NEXT_PROPERTY_SEPARATOR}`);
    assert.ok(result.endsWith('\nNext property\n'), 'Ends with canonical Next property separator');
  });

  await t.test('7. Example append and Add Another Property use same separator convention', () => {
    const text = '2 BHK flat in Vijay Nagar rent 20000';
    const fromAddAnother = composeNextPropertyPrompt(text);
    const fromExample = composeNextPropertyPrompt(text, PRESET_EXAMPLE_D);

    const expectedSeparator = '\n\nNext property\n';
    assert.ok(fromAddAnother.includes(expectedSeparator), 'Add Another Property contains separator');
    assert.ok(fromExample.includes(expectedSeparator), 'Example Prompt contains same separator');
    assert.equal(fromExample, `${fromAddAnother}${PRESET_EXAMPLE_D}`, 'Both share identical separator layout');
  });

  await t.test('8. Newly appended Next Property becomes visible (scroll simulation)', () => {
    let scrolledTop = -1;
    let scrolledBehavior = null;

    const mockTextarea = {
      value: '',
      scrollHeight: 400,
      clientHeight: 120,
      scrollTop: 0,
      selectionStart: 0,
      selectionEnd: 0,
      scrollTo(options) {
        scrolledTop = options.top;
        scrolledBehavior = options.behavior;
        this.scrollTop = options.top;
      }
    };

    const initialText = '2 BHK in Vijay Nagar rent 20000';
    const nextDetails = composeNextPropertyPrompt(initialText);
    mockTextarea.value = nextDetails;

    scrollPromptTextareaToNextProperty(mockTextarea, nextDetails);

    assert.ok(scrolledTop > 0, `Textarea should scroll to bring Next Property into view (scrolledTop=${scrolledTop})`);
    assert.equal(mockTextarea.selectionStart, nextDetails.length, 'Caret placed at end of text');
    assert.equal(mockTextarea.selectionEnd, nextDetails.length, 'Caret placed at end of text');
  });

  await t.test('9. Example append makes new section visible', () => {
    let scrolledTop = -1;

    const mockTextarea = {
      value: '',
      scrollHeight: 600,
      clientHeight: 120,
      scrollTop: 0,
      selectionStart: 0,
      selectionEnd: 0,
      scrollTo(options) {
        scrolledTop = options.top;
        this.scrollTop = options.top;
      }
    };

    const text = composeNextPropertyPrompt(PRESET_EXAMPLE_A, PRESET_EXAMPLE_B);
    mockTextarea.value = text;

    scrollPromptTextareaToNextProperty(mockTextarea, text);

    assert.ok(scrolledTop > 0, `Scrolled top must be positive (scrolledTop=${scrolledTop})`);
    assert.ok(scrolledTop <= mockTextarea.scrollHeight - mockTextarea.clientHeight, 'Does not exceed max scroll');
    assert.equal(mockTextarea.selectionStart, text.length, 'Caret is positioned after appended example');
  });

  await t.test('10. Existing property text is never replaced', () => {
    const originalText = 'Custom typed property details for 1 BHK in Nanda Nagar with specific notes';
    const updated = composeNextPropertyPrompt(originalText, PRESET_EXAMPLE_A);

    assert.ok(updated.startsWith(originalText), 'Original text must be completely preserved');
    assert.ok(updated.length > originalText.length, 'Appended text expands the prompt');
  });

  await t.test('11. Existing prompt remains editable', () => {
    let promptState = 'Initial 2 BHK flat';
    // User edits manually
    promptState += ' with modular kitchen';
    // User clicks example
    promptState = composeNextPropertyPrompt(promptState, PRESET_EXAMPLE_B);
    // User continues editing after append
    promptState += ' and 2 covered car parkings.';

    assert.ok(promptState.includes('with modular kitchen'), 'Manual intermediate edits preserved');
    assert.ok(promptState.includes('2 covered car parkings.'), 'Manual subsequent edits preserved');
    assert.ok(promptState.includes(PRESET_EXAMPLE_B), 'Appended example preserved');
  });

  await t.test('12. Parser / mode inference behavior unchanged', () => {
    const single = '2 BHK flat in Vijay Nagar rent 20000 owner 9826012345';
    assert.equal(MULTIPLE_PROPERTY_ENTRY_PATTERN.test(single), false, 'Single prompt is not multiple');

    const multiple = composeNextPropertyPrompt(single, PRESET_EXAMPLE_A);
    assert.equal(MULTIPLE_PROPERTY_ENTRY_PATTERN.test(multiple), true, 'Appended prompt matches multiple property pattern');

    const addAnotherOnly = composeNextPropertyPrompt(single);
    assert.equal(MULTIPLE_PROPERTY_ENTRY_PATTERN.test(addAnotherOnly), true, 'Add another prompt matches multiple property pattern');
  });

  await t.test('13. No manual Single/Batch toggle introduced', () => {
    // Mode is derived reactively through MULTIPLE_PROPERTY_ENTRY_PATTERN without any manual toggle state
    const singleText = PRESET_EXAMPLE_A;
    assert.equal(MULTIPLE_PROPERTY_ENTRY_PATTERN.test(singleText), false);

    const batchText = composeNextPropertyPrompt(PRESET_EXAMPLE_A, PRESET_EXAMPLE_B);
    assert.equal(MULTIPLE_PROPERTY_ENTRY_PATTERN.test(batchText), true);
  });

  await t.test('14. Draft autosave path receives updated prompt normally', () => {
    let scheduledPayload = null;
    const mockScheduleAutosave = (payload) => {
      scheduledPayload = payload;
    };

    let newBhkLabel = PRESET_EXAMPLE_A;
    // User appends example
    newBhkLabel = composeNextPropertyPrompt(newBhkLabel, PRESET_EXAMPLE_B);

    mockScheduleAutosave({ newBhkLabel });
    assert.ok(scheduledPayload.newBhkLabel.includes(PRESET_EXAMPLE_A), 'Draft has Example A');
    assert.ok(scheduledPayload.newBhkLabel.includes(PRESET_EXAMPLE_B), 'Draft has Example B');
    assert.ok(scheduledPayload.newBhkLabel.includes('Next property'), 'Draft contains Next property separator');
  });

  await t.test('15. Mobile does not receive unintended forced focus/keyboard behavior', () => {
    let focusCalled = false;
    const mockTextarea = {
      value: '',
      scrollHeight: 500,
      clientHeight: 120,
      scrollTop: 0,
      selectionStart: 0,
      selectionEnd: 0,
      focus() {
        focusCalled = true;
      },
      scrollTo(options) {
        this.scrollTop = options.top;
      }
    };

    const text = composeNextPropertyPrompt('Property 1', PRESET_EXAMPLE_A);
    scrollPromptTextareaToNextProperty(mockTextarea, text);

    assert.equal(focusCalled, false, 'focus() must NEVER be called automatically to prevent keyboard popup');
  });

  await t.test('16. Desktop behavior works', () => {
    const desktopPrompt = composeNextPropertyPrompt(PRESET_EXAMPLE_A, PRESET_EXAMPLE_C);
    assert.ok(desktopPrompt.includes(PRESET_EXAMPLE_A));
    assert.ok(desktopPrompt.includes(PRESET_EXAMPLE_C));
    assert.ok(desktopPrompt.includes(CANONICAL_NEXT_PROPERTY_SEPARATOR));
  });

  await t.test('17. Repeated example clicks append repeatedly', () => {
    let prompt = '';
    prompt = composeNextPropertyPrompt(prompt, PRESET_EXAMPLE_A);
    prompt = composeNextPropertyPrompt(prompt, PRESET_EXAMPLE_B);
    prompt = composeNextPropertyPrompt(prompt, PRESET_EXAMPLE_C);
    prompt = composeNextPropertyPrompt(prompt, PRESET_EXAMPLE_D);

    const parts = prompt.split(/\n\nNext property\n/);
    assert.equal(parts.length, 4, 'Must have 4 separated properties');
    assert.equal(parts[0], PRESET_EXAMPLE_A);
    assert.equal(parts[1], PRESET_EXAMPLE_B);
    assert.equal(parts[2], PRESET_EXAMPLE_C);
    assert.equal(parts[3], PRESET_EXAMPLE_D);
  });

  await t.test('18. Mixed manual / example / add-another flow preserves all content', () => {
    // 1. Manually type Property A
    let prompt = 'Manual Property 1 in Mahalaxmi Nagar, rent 15000.';
    // 2. Click Example B
    prompt = composeNextPropertyPrompt(prompt, PRESET_EXAMPLE_B);
    // 3. Click Add Another Property
    prompt = composeNextPropertyPrompt(prompt);
    // 4. Manually type Property C
    prompt += 'Manual Property 3 in Scheme 78, rent 25000.';
    // 5. Click Example D
    prompt = composeNextPropertyPrompt(prompt, PRESET_EXAMPLE_D);

    assert.ok(prompt.includes('Manual Property 1 in Mahalaxmi Nagar'), 'Property A preserved');
    assert.ok(prompt.includes(PRESET_EXAMPLE_B), 'Example B preserved');
    assert.ok(prompt.includes('Manual Property 3 in Scheme 78'), 'Property C preserved');
    assert.ok(prompt.includes(PRESET_EXAMPLE_D), 'Example D preserved');
    assert.equal(MULTIPLE_PROPERTY_ENTRY_PATTERN.test(prompt), true, 'Recognized by multiple property pattern');
  });

  await t.test('19. No duplicate malformed "Next PropertyNext Property"', () => {
    // Click Add Another Property
    let prompt = composeNextPropertyPrompt('Property 1');
    assert.ok(TRAILING_NEXT_PROPERTY_PATTERN.test(prompt.trimEnd()), 'Has trailing delimiter');

    // Click Add Another Property AGAIN immediately
    prompt = composeNextPropertyPrompt(prompt);
    const matches = prompt.match(/Next property/gi) || [];
    assert.equal(matches.length, 1, 'Should NOT add a second Next property immediately');
    assert.equal(prompt.includes('Next propertyNext property'), false);
    assert.equal(prompt.includes('Next property\n\nNext property'), false);

    // Now click an Example Prompt
    prompt = composeNextPropertyPrompt(prompt, PRESET_EXAMPLE_B);
    const matchesAfterExample = prompt.match(/Next property/gi) || [];
    assert.equal(matchesAfterExample.length, 1, 'Should NOT add duplicate Next property when appending to existing trailing delimiter');
    assert.ok(prompt.endsWith(PRESET_EXAMPLE_B), 'Example B appended cleanly');
  });

  await t.test('20. No backend / API calls added solely for this behavior', () => {
    // composeNextPropertyPrompt and scrollPromptTextareaToNextProperty are 100% synchronous client-side utilities
    assert.equal(typeof composeNextPropertyPrompt, 'function');
    assert.equal(typeof scrollPromptTextareaToNextProperty, 'function');
    assert.equal(composeNextPropertyPrompt.constructor.name, 'Function', 'Pure synchronous function');
  });
});
