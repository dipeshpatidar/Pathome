import React, { createContext, useContext, useState, useCallback } from 'react';

export type NotificationType = 'success' | 'info' | 'warning' | 'error' | 'ai_magic';
export type NotificationCategory = 'SYSTEM' | 'PROPERTY' | 'PAYROLL' | 'APPROVAL' | 'AI_ENGINE';

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
}

export interface NotificationHistoryItem extends ToastNotification {
  read: boolean;
}

interface NotificationContextType {
  toasts: ToastNotification[];
  history: NotificationHistoryItem[];
  unreadCount: number;
  isDrawerOpen: boolean;
  setIsDrawerOpen: (open: boolean) => void;
  showNotification: (notification: Omit<ToastNotification, 'id' | 'createdAt'>) => string;
  removeToast: (id: string) => void;
  clearHistory: () => void;
  markAllAsRead: () => void;
  markAsRead: (id: string) => void;
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

  // Sync role-restricted notifications from Spring Boot REST API
  const fetchBackendNotifications = useCallback(async () => {
    try {
      const activeRole = getActiveRole();
      const response = await fetch(`http://localhost:8080/api/v1/notifications?role=${activeRole}`);
      if (!response.ok) return;
      const data = await response.json();

      if (Array.isArray(data)) {
        const fetchedItems: NotificationHistoryItem[] = data.map((item: any) => ({
          id: `db-${item.id}`,
          type: item.type as NotificationType || 'info',
          category: item.category as NotificationCategory || 'SYSTEM',
          title: item.title,
          message: item.message,
          details: item.details,
          targetRole: item.targetRole,
          createdAt: item.createdAt ? new Date(item.createdAt) : new Date(),
          read: Boolean(item.isRead)
        }));

        setHistory(prev => {
          const existingIds = new Set(prev.map(p => p.id));
          const uniqueNew = fetchedItems.filter(f => !existingIds.has(f.id));
          return [...uniqueNew, ...prev];
        });
      }
    } catch (err) {
      console.warn("Backend notifications sync deferred:", err);
    }
  }, [getActiveRole]);

  React.useEffect(() => {
    fetchBackendNotifications();
    const interval = setInterval(fetchBackendNotifications, 15000); // Polling every 15s for live notifications
    return () => clearInterval(interval);
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

  const markAllAsRead = useCallback(() => {
    setHistory(prev => prev.map(item => ({ ...item, read: true })));
  }, []);

  const markAsRead = useCallback((id: string) => {
    setHistory(prev => prev.map(item => item.id === id ? { ...item, read: true } : item));
    if (id.startsWith('db-')) {
      const numericId = id.replace('db-', '');
      fetch(`http://localhost:8080/api/v1/notifications/${numericId}/read`, { method: 'PUT' }).catch(() => {});
    }
  }, []);

  // Filter history strictly by active user role for total portal isolation
  const activeRole = getActiveRole();
  const roleFilteredHistory = history.filter(item => {
    if (!item.targetRole || item.targetRole === 'ALL') return true;
    return item.targetRole.toUpperCase() === activeRole.toUpperCase();
  });

  const unreadCount = roleFilteredHistory.filter(item => !item.read).length;

  return (
    <NotificationContext.Provider value={{
      toasts,
      history: roleFilteredHistory,
      unreadCount,
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
