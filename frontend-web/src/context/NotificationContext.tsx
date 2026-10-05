import React, { createContext, useContext, useState, useCallback, useRef } from 'react';
import { API_ROOT_URL } from '../config/endpoints';
import { readNotificationIdentity, visibleNotificationHistory, isCurrentNotificationRequest } from '../utils/notificationSession';
import { notifySessionExpired } from '../services/apiError';

export type NotificationType = 'success' | 'info' | 'warning' | 'error' | 'ai_magic';
export type NotificationCategory = 'SYSTEM' | 'PROPERTY' | 'PAYROLL' | 'APPROVAL' | 'AI_ENGINE' | 'VISIT_SESSION';

export interface ToastAction {
  label: string;
  onClick: () => void;
}

export interface ErrorDialogOptions {
  title: string;
  message: string;
  details?: string;
  action?: ToastAction;
}

export interface ToastNotification {
  id: string;
  type: NotificationType;
  category: NotificationCategory;
  title: string;
  message: string;
  details?: string;
  duration?: number; // ms, default 4500
  createdAt: Date;
  action?: ToastAction;
  targetRole?: string;
  recipientUserId?: string;
  listingId?: number;
  revisionId?: string;
  actionType?: string;
  actionTarget?: string;
  eventKey?: string;
}

export interface NotificationHistoryItem extends ToastNotification {
  read: boolean;
}

interface NotificationContextType {
  toasts: ToastNotification[];
  history: NotificationHistoryItem[];
  unreadCount: number;
  isLoading: boolean;
  fetchError: string | null;
  markReadError: string | null;
  clearMarkReadError: () => void;
  refetchNotifications: () => Promise<void>;
  isDrawerOpen: boolean;
  setIsDrawerOpen: (open: boolean) => void;
  showNotification: (notification: Omit<ToastNotification, 'id' | 'createdAt'>) => string;
  removeToast: (id: string) => void;
  clearHistory: () => void;
  markAllAsRead: () => Promise<void>;
  markAsRead: (id: string) => Promise<void>;
  notifySuccess: (title: string, message: string, details?: string, category?: NotificationCategory) => string;
  notifyError: (title: string, message: string, details?: string, category?: NotificationCategory) => string;
  notifyInfo: (title: string, message: string, details?: string, category?: NotificationCategory) => string;
  notifyWarning: (title: string, message: string, details?: string, category?: NotificationCategory) => string;
  notifyAiMagic: (title: string, message: string, details?: string, category?: NotificationCategory) => string;
  errorDialog: ErrorDialogOptions | null;
  showErrorDialog: (error: ErrorDialogOptions) => void;
  dismissErrorDialog: () => void;
}

const NotificationContext = createContext<NotificationContextType | undefined>(undefined);

export const NotificationProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [toasts, setToasts] = useState<ToastNotification[]>([]);
  const [history, setHistory] = useState<NotificationHistoryItem[]>([]);
  const [isDrawerOpen, setIsDrawerOpen] = useState<boolean>(false);
  const [errorDialog, setErrorDialog] = useState<ErrorDialogOptions | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [markReadError, setMarkReadError] = useState<string | null>(null);
  const [historyIdentity, setHistoryIdentity] = useState<string | null>(null);
  const [toastsIdentity, setToastsIdentity] = useState<string | null>(null);
  const sessionIdentityRef = useRef<string | null>(readNotificationIdentity());
  const requestGenerationRef = useRef(0);

  // Helper to fetch current active role from localStorage session
  const getActiveRole = useCallback((): string => {
    try {
      const role = localStorage.getItem('pathome_role');
      if (!role || role === 'GUEST') return 'ALL';
      return role.toUpperCase();
    } catch {
      return 'ALL';
    }
  }, []);

  // Sync notifications from Spring Boot REST API
  const fetchBackendNotifications = useCallback(async () => {
    const identity = readNotificationIdentity();
    if (!identity || identity !== sessionIdentityRef.current) return;
    const generation = ++requestGenerationRef.current;
    const isCurrent = () => isCurrentNotificationRequest(identity, generation,
      sessionIdentityRef.current, requestGenerationRef.current) && identity === readNotificationIdentity();
    try {
      setIsLoading(true);
      setFetchError(null);
      const token = localStorage.getItem('pathome_auth_token');
      const activeRole = getActiveRole();
      const headers: Record<string, string> = {};
      if (token) {
        headers['Authorization'] = `Bearer ${token}`;
      }

      const response = await fetch(`${API_ROOT_URL}/notifications?role=${activeRole}`, { headers });
      if (!response.ok) {
        if (response.status === 401) notifySessionExpired(token);
        throw new Error('Failed to fetch notifications');
      }
      const data = await response.json();

      if (isCurrent() && Array.isArray(data)) {
        const fetchedItems: NotificationHistoryItem[] = data.map((item: any) => ({
          id: `db-${item.id}`,
          type: (item.type as NotificationType) || 'info',
          category: (item.category as NotificationCategory) || 'PROPERTY',
          title: item.title,
          message: item.message,
          details: item.details,
          targetRole: item.targetRole,
          createdAt: item.createdAt ? new Date(item.createdAt) : new Date(),
          read: Boolean(item.isRead),
          listingId: item.listingId,
          revisionId: item.revisionId,
          actionType: item.actionType,
          actionTarget: item.actionTarget,
          eventKey: item.eventKey
        }));

        setHistory(fetchedItems);
        setHistoryIdentity(identity);
      }
    } catch (err) {
      if (isCurrent()) {
        console.warn("Backend notifications sync failed:", err);
        setFetchError("We couldn't load your notifications.");
      }
    } finally {
      if (isCurrent()) setIsLoading(false);
    }
  }, [getActiveRole]);

  React.useEffect(() => {
    const syncSession = () => {
      const identity = readNotificationIdentity();
      if (identity !== sessionIdentityRef.current) {
        sessionIdentityRef.current = identity;
        requestGenerationRef.current++;
        setHistory([]);
        setHistoryIdentity(null);
        setToasts([]);
        setToastsIdentity(null);
        setFetchError(null);
        setMarkReadError(null);
        setIsLoading(false);
        setIsDrawerOpen(false);
        setErrorDialog(null);
      }
      if (identity) void fetchBackendNotifications();
    };
    syncSession();
    window.addEventListener('pathome_auth_changed', syncSession);
    window.addEventListener('storage', syncSession);
    const interval = setInterval(syncSession, 15000);
    return () => {
      requestGenerationRef.current++;
      clearInterval(interval);
      window.removeEventListener('pathome_auth_changed', syncSession);
      window.removeEventListener('storage', syncSession);
    };
  }, [fetchBackendNotifications]);

  const removeToast = useCallback((id: string) => {
    setToasts(prev => prev.filter(t => t.id !== id));
  }, []);

  const showNotification = useCallback((
    notification: Omit<ToastNotification, 'id' | 'createdAt'>
  ): string => {
    const activeRole = getActiveRole();
    // ROLE ISOLATION: If notification is designated for a specific role (e.g. ADMIN), do NOT display to other roles
    if (notification.targetRole && notification.targetRole !== 'ALL' && notification.targetRole !== activeRole) {
      return '';
    }

    const id = `notif-${Date.now()}-${Math.random().toString(36).substring(2, 7)}`;
    const newToast: ToastNotification = {
      ...notification,
      id,
      createdAt: new Date(),
      category: notification.category || 'SYSTEM',
      duration: notification.duration ?? 4500,
      targetRole: notification.targetRole || activeRole
    };

    setToasts(prev => [newToast, ...prev.slice(0, 4)]); // Keep max 5 active floating toasts
    setHistory(prev => [{ ...newToast, read: false }, ...prev]);
    setHistoryIdentity(readNotificationIdentity() ?? 'guest');
    setToastsIdentity(readNotificationIdentity() ?? 'guest');

    return id;
  }, [getActiveRole]);

  const notifySuccess = useCallback((title: string, message: string, details?: string, category: NotificationCategory = 'SYSTEM') => {
    return showNotification({ type: 'success', title, message, details, category });
  }, [showNotification]);

  const notifyError = useCallback((title: string, message: string, details?: string, category: NotificationCategory = 'SYSTEM') => {
    return showNotification({ type: 'error', title, message, details, category });
  }, [showNotification]);

  const notifyInfo = useCallback((title: string, message: string, details?: string, category: NotificationCategory = 'SYSTEM') => {
    return showNotification({ type: 'info', title, message, details, category });
  }, [showNotification]);

  const notifyWarning = useCallback((title: string, message: string, details?: string, category: NotificationCategory = 'SYSTEM') => {
    return showNotification({ type: 'warning', title, message, details, category });
  }, [showNotification]);

  const notifyAiMagic = useCallback((title: string, message: string, details?: string, category: NotificationCategory = 'AI_ENGINE') => {
    return showNotification({ type: 'ai_magic', title, message, details, category });
  }, [showNotification]);

  const showErrorDialog = useCallback((error: ErrorDialogOptions) => {
    setErrorDialog(error);
  }, []);

  const dismissErrorDialog = useCallback(() => {
    setErrorDialog(null);
  }, []);

  const clearHistory = useCallback(() => {
    setHistory([]);
  }, []);

  const clearMarkReadError = useCallback(() => {
    setMarkReadError(null);
  }, []);

  const markAllAsRead = useCallback(async () => {
    const identity = readNotificationIdentity();
    if (!identity || identity !== sessionIdentityRef.current) return;
    let previousState: NotificationHistoryItem[] = [];
    setHistory(prev => {
      previousState = prev;
      return prev.map(item => ({ ...item, read: true }));
    });
    setMarkReadError(null);

    try {
      const token = localStorage.getItem('pathome_auth_token');
      const headers: Record<string, string> = {};
      if (token) {
        headers['Authorization'] = `Bearer ${token}`;
      }
      const res = await fetch(`${API_ROOT_URL}/notifications/read-all`, {
        method: 'PUT',
        headers
      });
      if (!res.ok) {
        throw new Error('Failed to mark all as read');
      }
    } catch {
      if (identity === sessionIdentityRef.current && identity === readNotificationIdentity()) {
        setHistory(previousState);
        setMarkReadError("We couldn't update notifications. Try again.");
      }
    }
  }, []);

  const markAsRead = useCallback(async (id: string) => {
    const identity = readNotificationIdentity();
    if (!identity || identity !== sessionIdentityRef.current) return;
    let previousState: NotificationHistoryItem[] = [];
    setHistory(prev => {
      previousState = prev;
      return prev.map(item => item.id === id ? { ...item, read: true } : item);
    });
    setMarkReadError(null);

    if (id.startsWith('db-')) {
      const numericId = id.replace('db-', '');
      try {
        const token = localStorage.getItem('pathome_auth_token');
        const headers: Record<string, string> = {};
        if (token) {
          headers['Authorization'] = `Bearer ${token}`;
        }
        const res = await fetch(`${API_ROOT_URL}/notifications/${numericId}/read`, {
          method: 'PUT',
          headers
        });
        if (!res.ok) {
          throw new Error('Failed to update notification');
        }
      } catch {
        if (identity === sessionIdentityRef.current && identity === readNotificationIdentity()) {
          setHistory(previousState);
          setMarkReadError("We couldn't update this notification. Try again.");
        }
      }
    }
  }, []);

  const visibleHistory = visibleNotificationHistory(history, historyIdentity, readNotificationIdentity());
  const visibleToasts = visibleNotificationHistory(toasts, toastsIdentity, readNotificationIdentity());
  const unreadCount = visibleHistory.filter(item => !item.read).length;

  return (
    <NotificationContext.Provider value={{
      toasts: visibleToasts,
      history: visibleHistory,
      unreadCount,
      isLoading,
      fetchError,
      markReadError,
      clearMarkReadError,
      refetchNotifications: fetchBackendNotifications,
      isDrawerOpen,
      setIsDrawerOpen,
      showNotification,
      removeToast,
      clearHistory,
      markAllAsRead,
      markAsRead,
      notifySuccess,
      notifyError,
      notifyInfo,
      notifyWarning,
      notifyAiMagic,
      errorDialog,
      showErrorDialog,
      dismissErrorDialog
    }}>
      {children}
    </NotificationContext.Provider>
  );
};

export const useNotification = (): NotificationContextType => {
  const context = useContext(NotificationContext);
  if (!context) {
    throw new Error('useNotification must be used within a NotificationProvider');
  }
  return context;
};
