import React from 'react';
import { createRoot } from 'react-dom/client';
import { DraftManagementBar } from '../../components/DraftManagementBar';
import type { BulkDiscardResult } from '../../hooks/usePropertyDraft';

export const mountDiscardOwnershipHarness = (container: HTMLElement): void => {
  const draft = {
    draftId: 'stale-bulk-owner',
    draftType: 'SINGLE' as const,
    status: 'DRAFT',
    titleSummary: 'Replacement-owned draft',
    itemCount: 1,
    version: 1,
    mediaCount: 0,
    createdAt: '2026-10-21T00:00:00Z',
    updatedAt: '2026-10-21T00:05:00Z'
  };
  createRoot(container).render(React.createElement(DraftManagementBar, {
    currentDraftId: null,
    draftType: 'SINGLE',
    autosaveStatus: 'idle',
    lastSavedAt: null,
    drafts: [draft],
    onSelectDraft: () => {},
    onStartNewDraft: () => {},
    onDiscardDraft: async () => {},
    onDiscardDrafts: () => new Promise<BulkDiscardResult>(resolve => {
      (window as any).__resolveF4BulkResult = resolve;
    })
  }));
};
