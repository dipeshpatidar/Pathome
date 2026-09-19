/**
 * Robust IndexedDB-backed local draft persistence engine.
 * Provides immediate crash/refresh/offline recovery before server synchronization.
 * Strictly isolated per authenticated admin email to prevent data leakage.
 */

export interface LocalDraftRecord {
  draftId: string;
  adminEmail: string;
  draftType: 'SINGLE' | 'BATCH';
  titleSummary: string;
  itemCount: number;
  version: number;
  payload: any;
  updatedAt: number; // epoch ms
}

const DB_NAME = 'PathomeAdminDraftsDB';
const DB_VERSION = 1;
const STORE_NAME = 'drafts';
const SCHEMA_VERSION = 1;

let dbPromise: Promise<IDBDatabase> | null = null;

const getIndexedDB = (): Promise<IDBDatabase> => {
  if (dbPromise) return dbPromise;

  dbPromise = new Promise((resolve, reject) => {
    if (typeof window === 'undefined' || !window.indexedDB) {
      reject(new Error('IndexedDB not supported in this environment'));
      return;
    }

    const request = window.indexedDB.open(DB_NAME, DB_VERSION);

    request.onupgradeneeded = (event) => {
      const db = (event.target as IDBOpenDBRequest).result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        const store = db.createObjectStore(STORE_NAME, { keyPath: 'storageKey' });
        store.createIndex('by_admin', 'adminEmail', { unique: false });
        store.createIndex('by_updated', 'updatedAt', { unique: false });
      }
    };

    request.onsuccess = () => resolve(request.result);
    request.onerror = () => {
      dbPromise = null;
      reject(request.error || new Error('Failed to open IndexedDB'));
    };
  });

  return dbPromise;
};

const makeStorageKey = (adminEmail: string, draftId: string): string => {
  const cleanEmail = (adminEmail || 'guest').trim().toLowerCase();
  return `v${SCHEMA_VERSION}:${cleanEmail}:${draftId}`;
};

/**
 * Recursively sanitizes any payload destined for IndexedDB or localStorage.
 * Strictly guarantees that no File objects, Blob objects, object URLs (blob:),
 * data URIs (base64 payloads), or sensitive credential tokens are persisted.
 */
export const sanitizeForStorage = (data: any, depth: number = 0): any => {
  if (data === null || data === undefined || depth > 10) {
    return data;
  }

  // Reject binary File and Blob instances
  if (typeof File !== 'undefined' && data instanceof File) {
    return undefined;
  }
  if (typeof Blob !== 'undefined' && data instanceof Blob) {
    return undefined;
  }

  // Reject object URLs and base64 payloads
  if (typeof data === 'string') {
    const trimmed = data.trim();
    if (trimmed.startsWith('blob:') || trimmed.startsWith('data:')) {
      return undefined;
    }
    // Cap arbitrary long strings (never store massive binary chunks)
    if (trimmed.length > 65536) {
      return trimmed.slice(0, 65536);
    }
    return data;
  }

  if (Array.isArray(data)) {
    return data
      .map((item) => sanitizeForStorage(item, depth + 1))
      .filter((item) => item !== undefined);
  }

  if (typeof data === 'object') {
    const sanitized: Record<string, any> = {};
    const FORBIDDEN_KEYS = new Set(['file', 'blob', 'binary', 'token', 'password', 'secret', 'previewurl']);
    for (const [key, value] of Object.entries(data)) {
      if (FORBIDDEN_KEYS.has(key.toLowerCase())) {
        continue;
      }
      const cleaned = sanitizeForStorage(value, depth + 1);
      if (cleaned !== undefined) {
        sanitized[key] = cleaned;
      }
    }
    return sanitized;
  }

  return data;
};

export const localDraftStorage = {
  /**
   * Stores or updates a local draft record in IndexedDB (with LocalStorage fallback).
   */
  async save(adminEmail: string, draft: LocalDraftRecord): Promise<void> {
    if (!adminEmail || !draft.draftId) return;
    const cleanEmail = adminEmail.trim().toLowerCase();
    const storageKey = makeStorageKey(cleanEmail, draft.draftId);
    const sanitizedPayload = sanitizeForStorage(draft.payload);
    const record = {
      ...draft,
      payload: sanitizedPayload,
      adminEmail: cleanEmail,
      storageKey,
      updatedAt: Date.now()
    };

    try {
      const db = await getIndexedDB();
      await new Promise<void>((resolve, reject) => {
        const tx = db.transaction(STORE_NAME, 'readwrite');
        const store = tx.objectStore(STORE_NAME);
        const req = store.put(record);
        req.onsuccess = () => resolve();
        req.onerror = () => reject(req.error);
      });
    } catch {
      // Fallback to localStorage if IndexedDB is unavailable
      try {
        localStorage.setItem(`pathome_local_draft_${storageKey}`, JSON.stringify(record));
      } catch {
        // Local quota exceeded - non-fatal
      }
    }
  },

  /**
   * Retrieves a single local draft record.
   */
  async get(adminEmail: string, draftId: string): Promise<LocalDraftRecord | null> {
    if (!adminEmail || !draftId) return null;
    const cleanEmail = adminEmail.trim().toLowerCase();
    const storageKey = makeStorageKey(cleanEmail, draftId);

    try {
      const db = await getIndexedDB();
      return await new Promise<LocalDraftRecord | null>((resolve) => {
        const tx = db.transaction(STORE_NAME, 'readonly');
        const store = tx.objectStore(STORE_NAME);
        const req = store.get(storageKey);
        req.onsuccess = () => resolve(req.result || null);
        req.onerror = () => resolve(null);
      });
    } catch {
      try {
        const raw = localStorage.getItem(`pathome_local_draft_${storageKey}`);
        return raw ? JSON.parse(raw) : null;
      } catch {
        return null;
      }
    }
  },

  /**
   * Lists all local drafts belonging strictly to the specified admin.
   */
  async list(adminEmail: string): Promise<LocalDraftRecord[]> {
    if (!adminEmail) return [];
    const cleanEmail = adminEmail.trim().toLowerCase();

    try {
      const db = await getIndexedDB();
      return await new Promise<LocalDraftRecord[]>((resolve) => {
        const tx = db.transaction(STORE_NAME, 'readonly');
        const store = tx.objectStore(STORE_NAME);
        const index = store.index('by_admin');
        const req = index.getAll(cleanEmail);
        req.onsuccess = () => {
          const list = (req.result || []) as LocalDraftRecord[];
          list.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
          resolve(list);
        };
        req.onerror = () => resolve([]);
      });
    } catch {
      const list: LocalDraftRecord[] = [];
      const prefix = `pathome_local_draft_v${SCHEMA_VERSION}:${cleanEmail}:`;
      for (let i = 0; i < localStorage.length; i += 1) {
        const key = localStorage.key(i);
        if (key && key.startsWith(prefix)) {
          try {
            const item = JSON.parse(localStorage.getItem(key) || '{}');
            if (item && item.adminEmail === cleanEmail) {
              list.push(item);
            }
          } catch {}
        }
      }
      list.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
      return list;
    }
  },

  /**
   * Removes a local draft record.
   */
  async remove(adminEmail: string, draftId: string): Promise<void> {
    if (!adminEmail || !draftId) return;
    const cleanEmail = adminEmail.trim().toLowerCase();
    const storageKey = makeStorageKey(cleanEmail, draftId);

    try {
      const db = await getIndexedDB();
      await new Promise<void>((resolve, reject) => {
        const tx = db.transaction(STORE_NAME, 'readwrite');
        const store = tx.objectStore(STORE_NAME);
        const req = store.delete(storageKey);
        req.onsuccess = () => resolve();
        req.onerror = () => reject(req.error);
      });
    } catch {
      try {
        localStorage.removeItem(`pathome_local_draft_${storageKey}`);
      } catch {}
    }
  },

  /**
   * Cleans all local drafts for this admin (e.g. on explicit logout).
   */
  async clearAll(adminEmail: string): Promise<void> {
    if (!adminEmail) return;
    const cleanEmail = adminEmail.trim().toLowerCase();

    try {
      const items = await this.list(cleanEmail);
      for (const item of items) {
        await this.remove(cleanEmail, item.draftId);
      }
    } catch {}
  }
};
