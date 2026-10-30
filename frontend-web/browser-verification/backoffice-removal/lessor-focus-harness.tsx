import React from 'react';
import { createRoot } from 'react-dom/client';
import { LessorListingView } from '../../src/components/LessorListingView';

const root = createRoot(document.getElementById('root')!);

const renderListing = (listingId: number): void => {
  root.render(
    <main>
      <LessorListingView
        listingId={listingId}
        onBack={() => undefined}
        onOpenDraft={() => undefined}
      />
    </main>
  );
};

(window as any).selectFocusListing = renderListing;
(window as any).unmountFocusHarness = () => root.unmount();
renderListing(42);
