import type { DraftSection, DraftSectionValue, LessorDraft } from './lessorDraftService.ts';
import { readLessorSessionIdentity } from '../utils/lessorCapabilityState.ts';

export type LessorSaveStatus = 'saved' | 'saving' | 'error' | 'conflict';
type Pending = Partial<Record<DraftSection, DraftSectionValue>>;
const ORDER: DraftSection[] = ['basics', 'pricing', 'location', 'details'];
const DEBOUNCE_MS = 800;

/** Per-draft sequential save queue. A failed request remains queued and survives refresh. */
export class LessorAutosave {
  private pending: Pending = {};
  private timer: ReturnType<typeof setTimeout> | null = null;
  private inFlight: Promise<boolean> | null = null;
  private blocked = false;
  private discarding = false;
  private status: LessorSaveStatus = 'saved';
  private version: number;
  private persistedBaseVersion: number;
  private readonly storageKey: string;
  private readonly userId: number;
  private readonly draftId: string;
  private readonly onStatus: (status: LessorSaveStatus) => void;
  private readonly onSaved: (draft: LessorDraft) => void;
  private readonly save: (draftId: string, section: DraftSection, version: number,
                          value: DraftSectionValue) => Promise<LessorDraft>;
  private readonly persistLocally: boolean;
  private readonly sessionKey: string | null;

  constructor(userId: number, draftId: string,
              version: number, onStatus: (status: LessorSaveStatus) => void,
              onSaved: (draft: LessorDraft) => void,
              save: (draftId: string, section: DraftSection, version: number,
                     value: DraftSectionValue) => Promise<LessorDraft>, persistLocally = true) {
    this.userId = userId;
    this.draftId = draftId;
    this.onStatus = onStatus;
    this.onSaved = onSaved;
    this.save = save;
    this.persistLocally = persistLocally;
    this.sessionKey = persistLocally ? (readLessorSessionIdentity()?.key ?? null) : null;
    this.version = version;
    this.persistedBaseVersion = version;
    this.storageKey = `pathome_lessor_unsynced_${userId}_${draftId}`;
    try {
      if (!persistLocally) return;
      const saved = JSON.parse(localStorage.getItem(this.storageKey) || 'null');
      if (saved && saved.pending && typeof saved.pending === 'object') {
        this.pending = saved.pending;
        if (saved.version === version) {
          if (Object.keys(this.pending).length) this.setStatus('error');
        } else {
          this.persistedBaseVersion = saved.version;
          this.blocked = true;
          this.setStatus('conflict');
        }
      }
    } catch { /* A corrupt local copy cannot overwrite the server. */ }
  }

  getPending(): Pending { return { ...this.pending }; }
  getStatus(): LessorSaveStatus { return this.status; }

  change(section: DraftSection, value: DraftSectionValue): void {
    if (this.discarding) return;
    this.pending[section] = value;
    this.persist();
    if (this.blocked) return;
    this.setStatus('saving');
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => { void this.flush(); }, DEBOUNCE_MS);
  }

  async flush(): Promise<boolean> {
    if (this.timer) { clearTimeout(this.timer); this.timer = null; }
    while (true) {
      if (this.blocked || this.discarding) return false;
      if (this.inFlight) {
        if (!await this.inFlight) return false;
        continue;
      }
      if (!Object.keys(this.pending).length) return true;
      const request = this.drain();
      this.inFlight = request.finally(() => { this.inFlight = null; });
      if (!await this.inFlight) return false;
    }
  }

  private async drain(): Promise<boolean> {
    this.setStatus('saving');
    for (const section of ORDER) {
      const value = this.pending[section];
      if (!value) continue;
      try {
        const draft = await this.save(this.draftId, section, this.version, value);
        if (this.blocked || this.discarding) return false;
        this.version = draft.version;
        this.persistedBaseVersion = this.version;
        if (this.pending[section] === value) delete this.pending[section];
        this.persist();
        this.onSaved(draft);
      } catch (error) {
        if (this.blocked || this.discarding) return false;
        if (error && typeof error === 'object' && 'status' in error && error.status === 409) {
          this.blocked = true;
          this.setStatus('conflict');
        } else {
          this.setStatus('error');
        }
        return false;
      }
    }
    if (!Object.keys(this.pending).length) this.setStatus('saved');
    return true;
  }

  private persist(): void {
    if (!this.persistLocally) return;
    if (!this.sessionKey || readLessorSessionIdentity()?.key !== this.sessionKey) return;
    try {
      if (Object.keys(this.pending).length) {
        localStorage.setItem(this.storageKey, JSON.stringify({ version: this.blocked ? this.persistedBaseVersion : this.version, pending: this.pending }));
      } else {
        localStorage.removeItem(this.storageKey);
      }
    } catch { this.setStatus('error'); }
  }

  private setStatus(next: LessorSaveStatus): void {
    if (this.status === next) return;
    this.status = next;
    this.onStatus(next);
  }

  abandon(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.blocked = true;
    this.discarding = true;
    this.pending = {};
    if (this.persistLocally) {
      try {
        localStorage.removeItem(this.storageKey);
      } catch {}
    }
  }

  beginDiscard(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.discarding = true;
  }

  resumeAfterDiscardFailure(): void {
    this.discarding = false;
    if (Object.keys(this.pending).length) this.setStatus('error');
  }

  dispose(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.persist();
  }
}
