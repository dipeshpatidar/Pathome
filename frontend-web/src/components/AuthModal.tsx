import React, { useEffect, useRef, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { UserProfile, UserRole } from '../types';
import { X, Mail, Lock, User, AlertCircle } from 'lucide-react';
import { API_ROOT_URL, GOOGLE_OAUTH_URL } from '../config/endpoints';
import {
  DUPLICATE_EMAIL_MESSAGE,
  getDuplicateEmailRecoveryState,
  isDuplicateEmailResponse
} from '../utils/authRecovery';

interface AuthModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: (user: UserProfile) => void;
  lessorContext?: 'submit' | 'save' | null;
}

const normalizeRole = (rawRole: string): UserRole => {
  if (!rawRole) return 'GUEST';
  const clean = rawRole.toUpperCase().replace('ROLE_', '');
  
  if (clean === 'EMPLOYEE' || clean === 'STAFF' || clean === 'GROUND_BOY') return 'EMPLOYEE';
  if (clean === 'SUB_ADMIN' || clean === 'MANAGER') return 'SUB_ADMIN';
  if (clean === 'SUPER_ADMIN' || clean === 'ADMIN') return 'SUPER_ADMIN';
  return 'TENANT';
};

export const AuthModal: React.FC<AuthModalProps> = ({ isOpen, onClose, onSuccess, lessorContext = null }) => {
  const [authMode, setAuthMode] = useState<'LOGIN' | 'REGISTER'>('LOGIN');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fullName, setFullName] = useState('');
  const [errorMessage, setErrorMessage] = useState('');
  const [duplicateEmailError, setDuplicateEmailError] = useState(false);
  const [busy, setBusy] = useState(false);
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const closeRef = useRef(onClose);
  closeRef.current = busy ? () => {} : onClose;

  useEffect(() => {
    if (!isOpen) return;
    const previous = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    emailRef.current?.focus({ preventScroll: true });
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); closeRef.current(); return; }
      if (event.key !== 'Tab') return;
      const focusable = dialogRef.current?.querySelectorAll<HTMLElement>('button:not([disabled]),input:not([disabled]),a[href]');
      if (!focusable?.length) return;
      const first = focusable[0], last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => { document.removeEventListener('keydown', onKeyDown); document.body.style.overflow = overflow; previous?.focus({ preventScroll: true }); };
  }, [isOpen]);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setErrorMessage('');
    setDuplicateEmailError(false);

    try {
      const endpoint = `${API_ROOT_URL}/auth/${authMode === 'LOGIN' ? 'login' : 'register'}`;
      const payload = authMode === 'LOGIN' 
        ? { email, password }
        : { email, password, fullName };

      const res = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });

      if (res.ok) {
        const data = await res.json();
        if (!data.token) {
          setErrorMessage('Sign-in succeeded but did not return a secure session. Please try again.');
          return;
        }
        localStorage.setItem('pathome_auth_token', data.token);
        onSuccess({
          id: data.userId || 1,
          email: data.email || email,
          fullName: data.fullName || fullName || 'User',
          role: normalizeRole(data.role),
          freeVisitsUsed: 0,
          walletBalance: 0,
          hasLessorProfile: Boolean(data.hasLessorProfile)
        });
      } else {
        const text = await res.text();
        let errorData: { error?: string; message?: string } | null = null;
        try {
          errorData = JSON.parse(text);
        } catch {
          // non-JSON response
        }

        const isDuplicateEmail = isDuplicateEmailResponse(errorData, text);

        if (authMode === 'REGISTER' && isDuplicateEmail) {
          setDuplicateEmailError(true);
          setErrorMessage(DUPLICATE_EMAIL_MESSAGE);
        } else if (authMode === 'REGISTER') {
          setDuplicateEmailError(false);
          setErrorMessage('Unable to register with those details. Please check them and try again.');
        } else {
          setDuplicateEmailError(false);
          setErrorMessage('Unable to sign in with those credentials. Please check them and try again.');
        }
      }
    } catch {
      setDuplicateEmailError(false);
      setErrorMessage('The authentication service is unavailable. Please try again when the server is running.');
    } finally { setBusy(false); }
  };

  return (
    <AnimatePresence>
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: 0.3 }}
        onClick={() => closeRef.current()}
        className="fixed inset-0 z-50 bg-slate-950/70 backdrop-blur-md flex items-start justify-center overflow-y-auto p-3 pb-[calc(0.75rem+env(safe-area-inset-bottom))] sm:items-center sm:p-4 perspective-1000"
      >
        <motion.div
          initial={{ opacity: 0, scale: 0.84, rotateX: 14, y: 30 }}
          animate={{ opacity: 1, scale: 1, rotateX: 0, y: 0 }}
          exit={{ opacity: 0, scale: 0.84, rotateX: -14, y: 30 }}
          transition={{ type: 'spring', stiffness: 450, damping: 24 }}
          onClick={(e) => e.stopPropagation()}
          ref={dialogRef}
          role="dialog"
          aria-modal="true"
          aria-label={lessorContext === 'submit' ? 'Sign in to submit property' : lessorContext === 'save' ? 'Sign in to save property' : 'Sign in or create account'}
          className="relative my-auto max-h-[calc(100dvh-1.5rem)] w-full max-w-md overflow-x-hidden overflow-y-auto rounded-3xl border border-slate-200/90 bg-white p-5 shadow-2xl transform-gpu sm:max-h-[calc(100dvh-2rem)] sm:p-8"
        >
          
          <button
            onClick={() => closeRef.current()}
            disabled={busy}
            aria-label="Close sign in"
            className="absolute top-3 right-3 flex min-h-11 min-w-11 items-center justify-center text-slate-400 hover:text-slate-700 rounded-full hover:bg-slate-100 transition-colors z-10"
          >
            <X className="w-4 h-4" />
          </button>

          <div className="text-center mb-6">
            <h3 className="text-2xl font-bold text-slate-900 font-['Outfit',sans-serif]">
              {lessorContext === 'submit' ? 'Almost done' : lessorContext === 'save' ? 'Save across devices' : authMode === 'LOGIN' ? 'Welcome Back' : 'Create Account'}
            </h3>
            <p className="text-xs text-slate-500 mt-1">
              {lessorContext === 'submit' ? 'Sign in to save your property and submit it for review.' : lessorContext === 'save' ? 'Sign in to keep this draft on your account. You can submit it later.' : 'Access rental homes with transparent pricing'}
            </p>
          </div>

          {errorMessage && (
            <motion.div
              initial={{ opacity: 0, y: -6 }}
              animate={{ opacity: 1, y: 0 }}
              role="alert"
              className="mb-4 rounded-xl border border-rose-200 bg-rose-50 p-3.5 text-xs text-rose-800"
            >
              <div className="flex items-center gap-2">
                <AlertCircle className="w-4 h-4 text-rose-500 shrink-0" />
                <span className="font-semibold">{errorMessage}</span>
              </div>
              {duplicateEmailError && (
                <div className="mt-2.5 flex items-center justify-between gap-2 border-t border-rose-200/80 pt-2.5">
                  <span className="text-[11px] font-medium text-rose-700">Sign in to continue.</span>
                  <button
                    type="button"
                    onClick={() => {
                      const recovery = getDuplicateEmailRecoveryState(email);
                      setAuthMode(recovery.authMode);
                      setEmail(recovery.email);
                      setPassword(recovery.password);
                      setFullName(recovery.fullName);
                      setErrorMessage(recovery.errorMessage);
                      setDuplicateEmailError(recovery.duplicateEmailError);
                      setTimeout(() => passwordRef.current?.focus(), 50);
                    }}
                    className="inline-flex min-h-11 items-center justify-center rounded-xl bg-emerald-700 px-4 py-2 text-xs font-bold text-white shadow-xs hover:bg-emerald-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer"
                  >
                    Sign in
                  </button>
                </div>
              )}
            </motion.div>
          )}

          {!lessorContext && import.meta.env.VITE_GOOGLE_LOGIN_ENABLED === 'true' && (
            <>
              <motion.a
                whileHover={{ scale: 1.02, y: -1 }}
                whileTap={{ scale: 0.97 }}
                transition={{ type: 'spring', stiffness: 450, damping: 25 }}
                href={GOOGLE_OAUTH_URL}
                className="mb-3 flex w-full items-center justify-center gap-3 rounded-xl border border-slate-200 bg-slate-50 px-4 py-2.5 text-xs font-semibold text-slate-800 shadow-xs transition-colors hover:bg-slate-100"
              >
                <svg className="h-4 w-4" viewBox="0 0 24 24" aria-hidden="true">
                  <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/>
                  <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
                  <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z"/>
                  <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z"/>
                </svg>
                Sign in with Google
              </motion.a>

              <div className="my-3 flex items-center gap-3">
                <div className="h-px flex-1 bg-slate-200"></div>
                <span className="text-[10px] font-bold uppercase tracking-widest text-slate-400">Or continue with email</span>
                <div className="h-px flex-1 bg-slate-200"></div>
              </div>
            </>
          )}

          {/* PATHWAY 2: FORM */}
          <form onSubmit={handleSubmit} className="space-y-3.5">
            <AnimatePresence mode="wait">
              {authMode === 'REGISTER' && (
                <motion.div
                  initial={{ opacity: 0, height: 0 }}
                  animate={{ opacity: 1, height: 'auto' }}
                  exit={{ opacity: 0, height: 0 }}
                  transition={{ duration: 0.25 }}
                  className="space-y-3.5 overflow-hidden"
                >
                  <div>
                    <label htmlFor="pathome-auth-name" className="text-sm font-bold text-slate-700 block mb-1">Full Name</label>
                    <div className="relative">
                      <User className="w-4 h-4 text-slate-400 absolute left-3.5 top-3" />
                      <input
                        id="pathome-auth-name"
                        type="text"
                        value={fullName}
                        onChange={(e) => setFullName(e.target.value)}
                        placeholder="Full name"
                        className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-10 pr-3.5 py-2.5 text-base font-medium text-slate-900 focus:outline-none focus:border-emerald-600 focus:ring-2 focus:ring-emerald-600/10 transition-all"
                        required
                      />
                    </div>
                  </div>

                  <p className="rounded-xl bg-slate-50 border border-slate-200 px-3.5 py-2.5 text-xs text-slate-600">
                    New registrations create tenant accounts. Staff access is provisioned by an administrator.
                  </p>
                </motion.div>
              )}
            </AnimatePresence>

            <div>
              <label htmlFor="pathome-auth-email" className="text-sm font-bold text-slate-700 block mb-1">Email Address</label>
              <div className="relative">
                <Mail className="w-4 h-4 text-slate-400 absolute left-3.5 top-3" />
                <input
                  id="pathome-auth-email"
                  ref={emailRef}
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="name@domain.com"
                  className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-10 pr-3.5 py-2.5 text-base font-medium text-slate-900 focus:outline-none focus:border-emerald-600 focus:ring-2 focus:ring-emerald-600/10 transition-all"
                  required
                />
              </div>
            </div>

            <div>
              <label htmlFor="pathome-auth-password" className="text-sm font-bold text-slate-700 block mb-1">Password</label>
              <div className="relative">
                <Lock className="w-4 h-4 text-slate-400 absolute left-3.5 top-3" />
                <input
                  id="pathome-auth-password"
                  ref={passwordRef}
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="••••••••"
                  className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-10 pr-3.5 py-2.5 text-base font-medium text-slate-900 focus:outline-none focus:border-emerald-600 focus:ring-2 focus:ring-emerald-600/10 transition-all"
                  required
                />
              </div>
            </div>

            <motion.button
              whileHover={{ scale: 1.02, y: -1 }}
              whileTap={{ scale: 0.97 }}
              transition={{ type: 'spring', stiffness: 450, damping: 25 }}
              type="submit"
              disabled={busy}
              className="w-full bg-emerald-600 hover:bg-emerald-700 text-white font-bold py-3 rounded-xl text-xs shadow-md shadow-emerald-600/20 transition-all mt-3"
            >
              {busy ? 'Signing in…' : authMode === 'LOGIN' ? 'Sign In' : 'Register Account'}
            </motion.button>
          </form>

          <div className="mt-5 text-center">
            <button
              type="button"
              disabled={busy}
              onClick={() => {
                setAuthMode(authMode === 'LOGIN' ? 'REGISTER' : 'LOGIN');
                setErrorMessage('');
                setDuplicateEmailError(false);
              }}
              className="min-h-11 inline-flex items-center justify-center text-xs font-semibold text-emerald-600 hover:underline transition-all disabled:cursor-not-allowed disabled:opacity-60 cursor-pointer"
            >
              {authMode === 'LOGIN' ? "Don't have an account? Register" : "Already registered? Sign In"}
            </button>
          </div>

        </motion.div>
      </motion.div>
    </AnimatePresence>
  );
};
