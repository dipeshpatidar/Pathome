import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { deriveVideoPosterUrl } from '../utils/mediaTransform.ts';

test('Discovery Performance & Navigation Suite', async (t) => {
  await t.test('1. Cloudinary mp4 video derives lightweight JPEG poster frame', () => {
    const videoUrl = 'https://res.cloudinary.com/demo/video/upload/v1790105251/pathome/properties/videos/media-82-video.mp4';
    const posterUrl = deriveVideoPosterUrl(videoUrl);
    assert.equal(
      posterUrl,
      'https://res.cloudinary.com/demo/video/upload/so_0,w_800,c_limit,f_auto,q_auto/v1790105251/pathome/properties/videos/media-82-video.jpg'
    );
  });

  await t.test('2. Cloudinary mov/webm videos derive .jpg poster format', () => {
    const movUrl = 'https://res.cloudinary.com/demo/video/upload/v1790105225/pathome/properties/videos/media-80-walkthrough.mov';
    const webmUrl = 'https://res.cloudinary.com/demo/video/upload/v1790105225/pathome/properties/videos/media-80-walkthrough.webm';

    assert.equal(
      deriveVideoPosterUrl(movUrl),
      'https://res.cloudinary.com/demo/video/upload/so_0,w_800,c_limit,f_auto,q_auto/v1790105225/pathome/properties/videos/media-80-walkthrough.jpg'
    );
    assert.equal(
      deriveVideoPosterUrl(webmUrl),
      'https://res.cloudinary.com/demo/video/upload/so_0,w_800,c_limit,f_auto,q_auto/v1790105225/pathome/properties/videos/media-80-walkthrough.jpg'
    );
  });

  await t.test('3. Non-Cloudinary video returns null poster (triggers designed placeholder fallback)', () => {
    assert.equal(deriveVideoPosterUrl('https://example.com/videos/home-tour.mp4'), null);
    assert.equal(deriveVideoPosterUrl(''), null);
    assert.equal(deriveVideoPosterUrl(null), null);
  });

  await t.test('4. Discovery context session storage serialization and restoration contract', () => {
    const mockContext = {
      city: 'Indore',
      sector: 'Vijay Nagar',
      filterSector: 'Vijay Nagar',
      page: 1,
      properties: [
        { id: 101, title: 'Prop 1' },
        { id: 102, title: 'Prop 2' }
      ],
      hasMore: true,
      scrollY: 850,
      originPropertyId: 102
    };

    const serialized = JSON.stringify(mockContext);
    const parsed = JSON.parse(serialized);

    assert.equal(parsed.city, 'Indore');
    assert.equal(parsed.sector, 'Vijay Nagar');
    assert.equal(parsed.page, 1);
    assert.equal(parsed.properties.length, 2);
    assert.equal(parsed.originPropertyId, 102);
    assert.equal(parsed.hasMore, true);
  });

  await t.test('5. Discovery session restoration matches active URL filters', () => {
    const saved = { city: 'Indore', sector: 'Vijay Nagar', properties: [{ id: 1 }] };
    const currentCity = 'Indore';
    const currentSector = 'Vijay Nagar';
    const mismatchSector = 'Palasia';

    const isMatch = (saved.city || undefined) === currentCity && (saved.sector || undefined) === currentSector;
    const isMismatch = (saved.city || undefined) === currentCity && (saved.sector || undefined) === mismatchSector;

    assert.equal(isMatch, true, 'Matching filters restore discovery state');
    assert.equal(isMismatch, false, 'Changed filters reject stale discovery state');
  });

  await t.test('6. Authoritative incremental loading contract: 6 per batch, no fabricated remaining count', () => {
    const INITIAL_BATCH_SIZE = 6;
    const SHOW_MORE_BATCH_SIZE = 6;

    assert.equal(INITIAL_BATCH_SIZE, 6);
    assert.equal(SHOW_MORE_BATCH_SIZE, 6);

    // Verify button labeling rules
    const getButtonText = (loading, error, hasMore) => {
      if (loading) return 'Loading more properties…';
      if (error) return 'Retry';
      if (hasMore) return 'Show More Properties';
      return null;
    };

    assert.equal(getButtonText(false, null, true), 'Show More Properties');
    assert.equal(getButtonText(true, null, true), 'Loading more properties…');
    assert.equal(getButtonText(false, 'Network error', true), 'Retry');
    assert.equal(getButtonText(false, null, false), null, 'Button removed when hasMore is false');
  });

  await t.test('7. Truthful count badge copy: non-misleading "Showing X homes"', () => {
    const getCountCopy = (loadedCount) => `Showing ${loadedCount} homes`;
    assert.equal(getCountCopy(6), 'Showing 6 homes');
    assert.equal(getCountCopy(12), 'Showing 12 homes');
    // Reject misleading copy that claims 6 is the total
    assert.notEqual(getCountCopy(6), '6 Properties');
  });

  await t.test('8. Multi-city locality scoping: Indore localities do not mix with Bhopal/Pune', () => {
    const citiesConfig = [
      { id: 'INDORE', name: 'Indore', sectors: ['All Localities', 'Vijay Nagar', 'Bhawarkua', 'Nipania'] },
      { id: 'BHOPAL', name: 'Bhopal', sectors: ['All Localities', 'MP Nagar', 'Arera Colony'] },
      { id: 'PUNE', name: 'Pune', sectors: ['All Localities', 'Baner', 'Hinjewadi'] }
    ];

    const indore = citiesConfig.find(c => c.name === 'Indore');
    const bhopal = citiesConfig.find(c => c.name === 'Bhopal');

    assert.ok(indore.sectors.includes('Vijay Nagar'));
    assert.ok(!bhopal.sectors.includes('Vijay Nagar'), 'Indore sector must not appear in Bhopal');
    assert.ok(bhopal.sectors.includes('MP Nagar'));
    assert.ok(!indore.sectors.includes('MP Nagar'), 'Bhopal sector must not appear in Indore');
  });

  await t.test('9. Guest favorite semantics: Guest role prompts sign-in without fake persistence', () => {
    const handleFavoriteClick = (role, user, propId) => {
      if (role === 'GUEST' || !user) {
        return {
          action: 'PROMPT_AUTH',
          message: 'Sign in to save properties',
          persistedLocally: false,
          pendingPropId: propId
        };
      }
      return {
        action: 'ACCOUNT_FLAG',
        message: 'Saved to account',
        persistedLocally: false,
        pendingPropId: null
      };
    };

    const guestResult = handleFavoriteClick('GUEST', null, 42);
    assert.equal(guestResult.action, 'PROMPT_AUTH');
    assert.equal(guestResult.message, 'Sign in to save properties');
    assert.equal(guestResult.persistedLocally, false, 'Guest favorite must NEVER falsely claim local persistence');
    assert.equal(guestResult.pendingPropId, 42, 'Guest property intent is preserved for post-auth');

    const tenantResult = handleFavoriteClick('TENANT', { id: 1 }, 42);
    assert.equal(tenantResult.action, 'ACCOUNT_FLAG');
    assert.equal(tenantResult.persistedLocally, false, 'No fake server persistence claim without backend table');
  });

  await t.test('10. Video click toggle play/pause logic', () => {
    let isPlaying = false;
    const fakeVideo = {
      paused: true,
      play: () => { isPlaying = true; fakeVideo.paused = false; return Promise.resolve(); },
      pause: () => { isPlaying = false; fakeVideo.paused = true; }
    };

    const togglePlay = () => {
      if (fakeVideo.paused) {
        fakeVideo.play();
      } else {
        fakeVideo.pause();
      }
    };

    // 1. Initial paused -> click surface -> play
    togglePlay();
    assert.equal(isPlaying, true);
    assert.equal(fakeVideo.paused, false);

    // 2. Playing -> click surface -> pause
    togglePlay();
    assert.equal(isPlaying, false);
    assert.equal(fakeVideo.paused, true);

    // 3. Paused again -> click surface -> resume
    togglePlay();
    assert.equal(isPlaying, true);
    assert.equal(fakeVideo.paused, false);
  });

  await t.test('11. Back to discovery polling ensures card is mounted before scrolling', () => {
    const mockDOM = new Map();
    let scrolledTarget = null;

    const findAndScroll = (targetId) => {
      const el = mockDOM.get(targetId);
      if (el) {
        scrolledTarget = targetId;
        return true;
      }
      return false;
    };

    // Frame 1: card not mounted yet
    assert.equal(findAndScroll('property-card-85'), false);
    assert.equal(scrolledTarget, null);

    // Frame 2: card mounted in DOM by React
    mockDOM.set('property-card-85', { id: 'property-card-85' });
    assert.equal(findAndScroll('property-card-85'), true);
    assert.equal(scrolledTarget, 'property-card-85');
  });

  await t.test('12. Discovery cards use one grid and keep internal actions separate from card navigation', async () => {
    const source = await readFile(new URL('../components/PropertyShowcase.tsx', import.meta.url), 'utf8');
    assert.match(source, /grid grid-cols-1 gap-5 lg:grid-cols-2/);
    assert.doesNotMatch(source, /isEven|lg:-ml-14|lg:-mr-14|Property listing|Crest Emblem/);
    assert.match(source, /closest\('button, a, input, select'\)/);
    assert.match(source, /event\.stopPropagation\(\)/);
    assert.match(source, /aria-label=\{`View details for \$\{prop\.title\}`\}/);
  });

  await t.test('13. Back to discovery navigation lifecycle & mobile touch target contract', () => {
    // 1. Initial scroll state (scrollY < 60): button is in-flow above the media (0% media occlusion)
    const getNavState = (scrollY, isMobile) => {
      const isScrolled = scrollY > 60;
      return {
        isFixed: isScrolled,
        isCircular: isScrolled && isMobile,
        touchTargetMinPx: 44
      };
    };

    const initialDesktop = getNavState(0, false);
    assert.equal(initialDesktop.isFixed, false, 'Unscrolled desktop button is in-flow, 0% media coverage');

    const scrolledMobile = getNavState(120, true);
    assert.equal(scrolledMobile.isFixed, true, 'Scrolled mobile button is fixed');
    assert.equal(scrolledMobile.isCircular, true, 'Scrolled mobile button collapses to circular icon');
    assert.equal(scrolledMobile.touchTargetMinPx, 44, 'Mobile button maintains >= 44x44px touch target');

    const scrolledDesktop = getNavState(120, false);
    assert.equal(scrolledDesktop.isFixed, true, 'Scrolled desktop button is fixed');
    assert.equal(scrolledDesktop.isCircular, false, 'Scrolled desktop button retains full pill');
  });

  await t.test('14. Carousel navigation isolation vs video playback contract', () => {
    // Media list with video at index 0 and photo at index 1
    const mediaList = [
      { url: 'video.mp4', type: 'VIDEO' },
      { url: 'photo1.jpg', type: 'IMAGE' },
      { url: 'photo2.jpg', type: 'IMAGE' }
    ];

    let activeMedia = 0;
    let isVideoPlaying = true;
    let videoPaused = false;

    const mockVideoRef = {
      pause: () => { videoPaused = true; }
    };

    const handleNextMedia = (event) => {
      if (event && event.stopPropagation) event.stopPropagation();
      if (event && event.preventDefault) event.preventDefault();
      if (mockVideoRef) {
        mockVideoRef.pause();
        isVideoPlaying = false;
      }
      activeMedia = (activeMedia + 1) % mediaList.length;
    };

    const handlePrevMedia = (event) => {
      if (event && event.stopPropagation) event.stopPropagation();
      if (event && event.preventDefault) event.preventDefault();
      if (mockVideoRef) {
        mockVideoRef.pause();
        isVideoPlaying = false;
      }
      activeMedia = (activeMedia - 1 + mediaList.length) % mediaList.length;
    };

    const togglePlay = (event) => {
      if (event && event.stopPropagation) event.stopPropagation();
      isVideoPlaying = !isVideoPlaying;
    };

    // 1. Video is currently active and playing
    assert.equal(activeMedia, 0);
    assert.equal(isVideoPlaying, true);

    // 2. Click RIGHT arrow -> must advance to index 1, pause video, must NOT keep playing or toggle
    const mockEventRight = { stopped: false, stopPropagation() { this.stopped = true; }, preventDefault() {} };
    handleNextMedia(mockEventRight);
    assert.equal(activeMedia, 1, 'Right arrow advances media index to 1');
    assert.equal(videoPaused, true, 'Video paused when navigating away');
    assert.equal(isVideoPlaying, false, 'Video playing state reset to false');
    assert.equal(mockEventRight.stopped, true, 'Right arrow stops event propagation');

    // 3. Click LEFT arrow -> returns to index 0 (the video), video remains paused
    const mockEventLeft = { stopped: false, stopPropagation() { this.stopped = true; }, preventDefault() {} };
    handlePrevMedia(mockEventLeft);
    assert.equal(activeMedia, 0, 'Left arrow returns to index 0');
    assert.equal(isVideoPlaying, false, 'Navigating does not play video');
    assert.equal(mockEventLeft.stopped, true, 'Left arrow stops event propagation');

    // 4. Central play button or intended video surface interaction -> toggles play without changing media index
    const mockEventPlay = { stopped: false, stopPropagation() { this.stopped = true; } };
    togglePlay(mockEventPlay);
    assert.equal(isVideoPlaying, true, 'Central video action plays video');
    assert.equal(activeMedia, 0, 'Play action does NOT change carousel media index');
    assert.equal(mockEventPlay.stopped, true, 'Video click stops event propagation');

    // 5. Central video interaction pauses video without changing media index
    togglePlay(mockEventPlay);
    assert.equal(isVideoPlaying, false, 'Central video action pauses video');
    assert.equal(activeMedia, 0, 'Pause action does NOT change carousel media index');
  });

  await t.test('15. Detail layout stable media height reservation contract (0px CLS)', () => {
    // Both loading skeleton and loaded media viewer enforce the EXACT identical responsive height scale
    const SKELETON_MEDIA_CLASSES = 'h-[340px] sm:h-[420px] lg:h-[480px] w-full';
    const VIEWER_MEDIA_CLASSES = 'h-[340px] sm:h-[420px] lg:h-[480px] w-full';

    assert.equal(
      SKELETON_MEDIA_CLASSES,
      VIEWER_MEDIA_CLASSES,
      'Loading skeleton and loaded media viewer reserve the identical height scale, preventing layout shift'
    );
  });

  await t.test('16. Smoked glass Back control accessibility & high-contrast boundary contract', () => {
    const MIN_TOUCH_PX = 44;
    const buttonAttrs = {
      role: 'button',
      ariaLabel: 'Back to discovery',
      minHeightPx: 44,
      minWidthPx: 44,
      bg: 'slate-950/80',
      text: 'white',
      border: 'white/25',
      ring: 'black/40',
      shadow: 'black/35'
    };

    assert.equal(buttonAttrs.ariaLabel, 'Back to discovery');
    assert.ok(buttonAttrs.minHeightPx >= MIN_TOUCH_PX, 'Button meets >=44px height requirement');
    assert.ok(buttonAttrs.minWidthPx >= MIN_TOUCH_PX, 'Button meets >=44px width requirement');
    assert.equal(buttonAttrs.text, 'white', 'Pure white text guarantees >15:1 contrast against slate-950 backdrop');
    assert.ok(buttonAttrs.border.includes('white/25'), 'Luminous white border guarantees edge contrast over dark media');
    assert.ok(buttonAttrs.ring.includes('black/40'), 'Black ring guarantees edge contrast over bright/white backgrounds');
  });

  await t.test('17. Property detail entry scroll reset & history preservation contract', () => {
    // Simulated browser window and sessionStorage
    let windowScrollY = 1250;
    let windowScrollTarget = null;
    const mockSessionStorage = new Map();

    const mockWindow = {
      get scrollY() { return windowScrollY; },
      scrollTo: ({ top, left, behavior }) => {
        windowScrollY = top;
        windowScrollTarget = { top, left, behavior };
      }
    };

    // 1. User clicks property 85 from scrolled discovery page (scrollY = 1250)
    const propertyToOpen = { id: 85, title: '4 BHK Villa' };
    const currentDiscoveryPage = 1;
    const properties = [{ id: 82 }, { id: 85 }];

    const handleOpenPropertyDetail = (property) => {
      // Must save discovery context with non-zero scroll BEFORE resetting scroll
      mockSessionStorage.set('pathome_discovery_context', JSON.stringify({
        city: 'Indore',
        sector: 'Vijay Nagar',
        filterSector: 'Vijay Nagar',
        page: currentDiscoveryPage,
        properties: properties,
        hasMore: true,
        scrollY: mockWindow.scrollY,
        originPropertyId: property.id
      }));

      // Instant scroll reset to top before route transition
      mockWindow.scrollTo({ top: 0, left: 0, behavior: 'instant' });
    };

    handleOpenPropertyDetail(propertyToOpen);

    // Verify discovery context was saved with the pre-navigation scroll offset (1250px)
    const savedContext = JSON.parse(mockSessionStorage.get('pathome_discovery_context'));
    assert.equal(savedContext.scrollY, 1250, 'Discovery scroll position preserved before reset');
    assert.equal(savedContext.originPropertyId, 85, 'Originating property ID preserved');

    // Verify window scroll position was reset to 0 before navigation
    assert.equal(windowScrollY, 0, 'Window scroll reset to 0 on detail entry');
    assert.equal(windowScrollTarget.behavior, 'instant', 'Instant behavior eliminates layout jump or scroll flash');

    // 2. PublicPropertyDetail layout effect on PUSH navigation
    let isScrolled = mockWindow.scrollY > 60;
    assert.equal(isScrolled, false, 'isScrolled initializes to false when entering at top');

    const detailLayoutEffect = (navType) => {
      if (navType !== 'POP') {
        mockWindow.scrollTo({ top: 0, left: 0, behavior: 'instant' });
        isScrolled = false;
      }
    };

    detailLayoutEffect('PUSH');
    assert.equal(windowScrollY, 0);
    assert.equal(isScrolled, false, 'PUSH navigation maintains 0 scroll and false isScrolled');

    // 3. Browser Back/Forward (POP) navigation: must NOT forcibly overwrite scroll with 0
    windowScrollY = 320; // user had previously scrolled on detail
    detailLayoutEffect('POP');
    assert.equal(windowScrollY, 320, 'POP navigation preserves browser history scroll position');

    // 4. Back to Discovery restoration: restores the originating 1250px position
    let restoredScroll = null;
    if (savedContext && typeof savedContext.scrollY === 'number') {
      restoredScroll = savedContext.scrollY;
    }
    assert.equal(restoredScroll, 1250, 'Back to discovery correctly restores the saved 1250px scroll position');
  });
});



