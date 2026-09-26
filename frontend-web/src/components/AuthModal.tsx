import React, { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { UserProfile, UserRole } from '../types';
import { X, Mail, Lock, User, AlertCircle } from 'lucide-react';
import { GOOGLE_OAUTH_URL } from '../config/endpoints';

interface AuthModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: (user: UserProfile) => void;
}

const normalizeRole = (rawRole: string): UserRole => {
  if (!rawRole) return 'GUEST';
  const clean = rawRole.toUpperCase().replace('ROLE_', '');
  
  if (clean === 'EMPLOYEE' || clean === 'STAFF' || clean === 'GROUND_BOY') return 'EMPLOYEE';
  if (clean === 'SUB_ADMIN' || clean === 'MANAGER') return 'SUB_ADMIN';
  if (clean === 'SUPER_ADMIN' || clean === 'ADMIN') return 'SUPER_ADMIN';
  return 'TENANT';
};

export const AuthModal: React.FC<AuthModalProps> = ({ isOpen, onClose, onSuccess }) => {
  const [authMode, setAuthMode] = useState<'LOGIN' | 'REGISTER'>('LOGIN');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fullName, setFullName] = useState('');
  const [errorMessage, setErrorMessage] = useState('');

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMessage('');

    try {
      const endpoint = authMode === 'LOGIN' ? '/api/v1/auth/login' : '/api/v1/auth/register';
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
          walletBalance: 0
        });
        onClose();
      } else {
        setErrorMessage('Unable to sign in with those credentials. Please check them and try again.');
      }
    } catch (err) {
      setErrorMessage('The authentication service is unavailable. Please try again when the server is running.');
    }
  };

  return (
    <AnimatePresence>
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: 0.3 }}
        onClick={onClose}
        className="fixed inset-0 z-50 bg-slate-950/70 backdrop-blur-md flex items-start justify-center overflow-y-auto p-3 sm:items-center sm:p-4 perspective-1000"
      >
        <motion.div
          initial={{ opacity: 0, scale: 0.84, rotateX: 14, y: 30 }}
          animate={{ opacity: 1, scale: 1, rotateX: 0, y: 0 }}
          exit={{ opacity: 0, scale: 0.84, rotateX: -14, y: 30 }}
          transition={{ type: 'spring', stiffness: 450, damping: 24 }}
          onClick={(e) => e.stopPropagation()}
          className="relative my-auto max-h-[calc(100dvh-1.5rem)] w-full max-w-md overflow-x-hidden overflow-y-auto rounded-3xl border border-slate-200/90 bg-white p-5 shadow-2xl transform-gpu sm:max-h-[calc(100dvh-2rem)] sm:p-8"
        >
          
          <button
            onClick={onClose}
            className="absolute top-5 right-5 text-slate-400 hover:text-slate-700 p-1.5 rounded-full hover:bg-slate-100 transition-colors z-10"
          >
            <X className="w-4 h-4" />
          </button>

          <div className="text-center mb-6">
            <h3 className="text-2xl font-bold text-slate-900 font-['Outfit',sans-serif]">
              {authMode === 'LOGIN' ? 'Welcome Back' : 'Create Account'}
            </h3>
            <p className="text-xs text-slate-500 mt-1">
              Access zero-brokerage rentals & verified homes
            </p>
          </div>

          {errorMessage && (
            <motion.div
              initial={{ opacity: 0, y: -6 }}
              animate={{ opacity: 1, y: 0 }}
              className="mb-4 bg-rose-50 border border-rose-200 text-rose-700 text-xs p-3 rounded-xl flex items-center gap-2"
            >
              <AlertCircle className="w-4 h-4 text-rose-500 shrink-0" />
              <span>{errorMessage}</span>
            </motion.div>
          )}

          {import.meta.env.VITE_GOOGLE_LOGIN_ENABLED === 'true' && (
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
                    <label className="text-[11px] font-bold text-slate-700 block mb-1">Full Name</label>
                    <div className="relative">
                      <User className="w-4 h-4 text-slate-400 absolute left-3.5 top-3" />
                      <input
                        type="text"
                        value={fullName}
                        onChange={(e) => setFullName(e.target.value)}
                        placeholder="Full name"
                        className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-10 pr-3.5 py-2.5 text-xs font-medium text-slate-900 focus:outline-none focus:border-emerald-600 focus:ring-2 focus:ring-emerald-600/10 transition-all"
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
              <label className="text-[11px] font-bold text-slate-700 block mb-1">Email Address</label>
              <div className="relative">
                <Mail className="w-4 h-4 text-slate-400 absolute left-3.5 top-3" />
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="name@domain.com"
                  className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-10 pr-3.5 py-2.5 text-xs font-medium text-slate-900 focus:outline-none focus:border-emerald-600 focus:ring-2 focus:ring-emerald-600/10 transition-all"
                  required
                />
              </div>
            </div>

            <div>
              <label className="text-[11px] font-bold text-slate-700 block mb-1">Password</label>
              <div className="relative">
                <Lock className="w-4 h-4 text-slate-400 absolute left-3.5 top-3" />
                <input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="••••••••"
                  className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-10 pr-3.5 py-2.5 text-xs font-medium text-slate-900 focus:outline-none focus:border-emerald-600 focus:ring-2 focus:ring-emerald-600/10 transition-all"
                  required
                />
              </div>
            </div>

            <motion.button
              whileHover={{ scale: 1.02, y: -1 }}
              whileTap={{ scale: 0.97 }}
              transition={{ type: 'spring', stiffness: 450, damping: 25 }}
              type="submit"
              className="w-full bg-emerald-600 hover:bg-emerald-700 text-white font-bold py-3 rounded-xl text-xs shadow-md shadow-emerald-600/20 transition-all mt-3"
            >
              {authMode === 'LOGIN' ? 'Sign In' : 'Register Account'}
            </motion.button>
          </form>

          <div className="mt-5 text-center">
            <button
              onClick={() => { setAuthMode(authMode === 'LOGIN' ? 'REGISTER' : 'LOGIN'); setErrorMessage(''); }}
              className="text-xs font-semibold text-emerald-600 hover:underline transition-all"
            >
              {authMode === 'LOGIN' ? "Don't have an account? Register" : "Already registered? Sign In"}
            </button>
          </div>

        </motion.div>
      </motion.div>
    </AnimatePresence>
  );
};
