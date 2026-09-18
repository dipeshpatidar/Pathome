import React, { useState } from 'react';
import {
  motion,
  AnimatePresence
} from 'framer-motion';
import { UserRole,
  UserProfile } from '../types';
import {
  Building2,
  Sparkles,
  Gift,
  User,
  LogOut,
  ChevronDown,
  Bell
} from 'lucide-react';
import { useNotification } from '../context/NotificationContext';

interface NavbarProps {
  user: UserProfile | null;
  role: UserRole;
  onOpenAuthModal: () => void;
  onOpenLeaseUpload: () => void;
  onLogout: () => void;
  activeAdminTab?: string;
  setActiveAdminTab?: (tab: string) => void;
}

export const Navbar: React.FC<NavbarProps> = ({
  user,
  role,
  onOpenAuthModal,
  onOpenLeaseUpload,
  onLogout,
  activeAdminTab = 'overview',
  setActiveAdminTab
}) => {
  const [profileDropdownOpen, setProfileDropdownOpen] = useState(false);
  const { unreadCount, isDrawerOpen, setIsDrawerOpen } = useNotification();

  const visitsUsed = user?.freeVisitsUsed || 0;
  const isPaywallActive = visitsUsed >= 5;

  const isAdminRole = role === 'ADMIN' || role === 'SUPER_ADMIN' || role === 'SUB_ADMIN';

  return (
    <motion.header
      initial={{ opacity: 0, y: -12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.45, ease: [0.16, 1, 0.3, 1] }}
      className={`sticky top-0 z-[100] w-full transition-all duration-300 ${
        isAdminRole
          ? 'bg-slate-950/95 backdrop-blur-2xl border-b border-emerald-500/30 shadow-2xl'
          : 'bg-white/95 backdrop-blur-2xl border-b border-slate-200/90 shadow-md'
      }`}
    >
      {/* GLOWING TOP ACCENT BEAM LINE WITH FLOWING GRADIENT */}
      <div className="h-[2.5px] w-full bg-gradient-to-r from-emerald-500 via-cyan-400 via-indigo-500 to-amber-400 animate-gradient-x shadow-[0_0_12px_rgba(16,185,129,0.5)]" />

      <div className="max-w-7xl mx-auto px-3 min-[360px]:px-4 sm:px-6 lg:px-8 h-[72px] flex items-center justify-between gap-2">
        
        {/* BRANDING LOGO */}
        <div className="flex min-w-0 items-center gap-2.5 sm:gap-8">
          <motion.div 
            whileHover={{ scale: 1.03 }}
            whileTap={{ scale: 0.98 }}
            className="flex min-w-0 items-center gap-2.5 cursor-pointer"
          >
            <motion.div
              whileHover={{ rotate: 8, scale: 1.08 }}
              transition={{ type: 'spring', stiffness: 400, damping: 18 }}
              className={`w-9 h-9 sm:w-10 sm:h-10 rounded-2xl flex items-center justify-center font-bold shrink-0 shadow-lg ${
                isAdminRole
                  ? 'bg-gradient-to-tr from-emerald-600 via-teal-500 to-emerald-400 text-white shadow-emerald-500/30'
                  : 'bg-emerald-600 text-white shadow-emerald-600/20'
              }`}
            >
              <Building2 className="w-5 h-5" />
            </motion.div>
            <div className="min-w-0">
              <a href="#" className={`font-['Outfit',sans-serif] text-base sm:text-xl font-black tracking-tight inline-flex items-center ${
                isAdminRole ? 'text-white' : 'text-slate-900'
              }`}>Path<span className="text-emerald-500">ome</span></a>
              <div className="flex items-center gap-1.5 mt-0.5">
                <span className={`hidden min-[360px]:flex text-[9px] sm:text-[10px] font-extrabold px-2 py-0.5 rounded-full border items-center gap-1.5 ${
                  isAdminRole 
                    ? 'text-emerald-300 bg-emerald-950/80 border-emerald-500/30'
                    : 'text-emerald-700 bg-emerald-50 border-emerald-200'
                }`}>
                  <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-ping"></span>
                  Your Dreams, Our Efforts
                </span>
                {isAdminRole && (
                  <span className="text-[10px] font-mono font-bold text-amber-400 bg-amber-500/10 px-2 py-0.5 rounded-full border border-amber-500/30 hidden sm:inline">
                    Console Mode
                  </span>
                )}
              </div>
            </div>
          </motion.div>

          {/* Guest Nav Links */}
          {role === 'GUEST' && (
            <nav className="hidden md:flex items-center gap-6">
              <a href="#listings" className="text-xs font-bold text-slate-700 hover:text-emerald-600 transition-colors">
                Verified Rentals
              </a>
              <a href="#how-it-works" className="text-xs font-bold text-slate-700 hover:text-emerald-600 transition-colors">
                How It Works
              </a>
              <a href="#why-us" className="text-xs font-bold text-slate-700 hover:text-emerald-600 transition-colors">
                Why Pathome
              </a>
            </nav>
          )}
        </div>

        {/* RIGHT ACTIONS & PROFILE MENU */}
        <div className="flex shrink-0 items-center gap-1.5 sm:gap-3">
          
          {role === 'GUEST' && (
            <motion.button
              whileHover={{ scale: 1.04, y: -1 }}
              whileTap={{ scale: 0.96 }}
              onClick={onOpenAuthModal}
              aria-label="Sign in or register"
              className="flex h-10 min-w-10 items-center justify-center gap-2 rounded-xl bg-emerald-600 px-3 text-xs font-extrabold text-white shadow-md shadow-emerald-600/20 transition-all hover:bg-emerald-700 min-[420px]:px-4"
            >
              <User className="w-3.5 h-3.5" />
              <span className="hidden min-[420px]:inline">Sign In / Register</span>
            </motion.button>
          )}

          {role === 'TENANT' && (
            <>
              <div className="flex h-10 items-center gap-1.5 rounded-full border border-slate-200 bg-slate-100 px-2 text-xs font-bold min-[480px]:px-3">
                <Sparkles className="w-3.5 h-3.5 text-emerald-600 animate-pulse" />
                <span className="hidden text-slate-500 font-medium min-[480px]:inline">Free Passes:</span>
                <span className="text-slate-900">{visitsUsed} / 5</span>
              </div>

              <motion.button
                whileHover={{ scale: 1.03 }}
                whileTap={{ scale: 0.96 }}
                onClick={onOpenLeaseUpload}
                className="hidden sm:flex bg-slate-900 text-white font-extrabold text-xs px-3.5 py-2 rounded-xl transition-all items-center gap-1.5 shadow-sm"
              >
                <Gift className="w-3.5 h-3.5 text-emerald-400" />
                <span>Claim ₹1,000 Cash-Back</span>
              </motion.button>
            </>
          )}

          {/* CENTRALIZED NOTIFICATION BELL BUTTON WITH PULSING UNREAD BADGE */}
          <motion.button
            whileHover={{ scale: 1.08 }}
            whileTap={{ scale: 0.94 }}
            onClick={() => setIsDrawerOpen(!isDrawerOpen)}
            className={`relative flex h-10 w-10 items-center justify-center rounded-2xl border transition-all cursor-pointer shadow-md sm:h-11 sm:w-11 ${
              isAdminRole
                ? 'bg-slate-900/90 text-emerald-400 border-slate-800 hover:border-emerald-500/50 hover:bg-slate-800'
                : 'bg-slate-900/90 text-emerald-400 border-slate-800 hover:bg-slate-800'
            }`}
            title="Open Portal Notification Center"
          >
            <Bell className="w-4 h-4 sm:w-4.5 sm:h-4.5" />
            {unreadCount > 0 && (
              <span className="absolute -top-1 -right-1 w-5 h-5 rounded-full bg-emerald-500 text-slate-950 font-mono font-black text-[10px] flex items-center justify-center border-2 border-slate-950 shadow-lg shadow-emerald-500/50 animate-bounce">
                {unreadCount > 9 ? '9+' : unreadCount}
              </span>
            )}
          </motion.button>

          {/* USER PROFILE & LOGOUT DROPDOWN HUB */}
          {role !== 'GUEST' && (
            <div 
              className="relative py-1"
              onMouseEnter={() => setProfileDropdownOpen(true)}
              onMouseLeave={() => setProfileDropdownOpen(false)}
            >
              <motion.button
                whileHover={{ scale: 1.04 }}
                whileTap={{ scale: 0.96 }}
                onClick={() => setProfileDropdownOpen(!profileDropdownOpen)}
                className={`flex items-center gap-2 p-1.5 rounded-2xl border transition-all shadow-md ${
                  isAdminRole
                    ? 'bg-slate-900 border-slate-800 text-white hover:border-emerald-500/40'
                    : 'bg-slate-900 border-slate-800 text-white'
                }`}
              >
                <div className={`w-7 h-7 rounded-xl flex items-center justify-center font-extrabold text-xs shadow-inner ${
                  role === 'SUPER_ADMIN' || role === 'ADMIN' ? 'bg-gradient-to-tr from-amber-500 to-amber-300 text-slate-950' :
                  role === 'SUB_ADMIN' ? 'bg-gradient-to-tr from-purple-600 to-purple-400 text-white' :
                  role === 'EMPLOYEE' ? 'bg-gradient-to-tr from-indigo-600 to-indigo-400 text-white' : 'bg-emerald-500 text-slate-950'
                }`}>
                  {user?.fullName ? user.fullName.charAt(0).toUpperCase() : role.charAt(0)}
                </div>
                <span className="text-xs font-bold max-w-[180px] sm:max-w-[220px] truncate hidden sm:inline text-slate-200">
                  {user?.fullName || role}
                </span>
                <ChevronDown className={`hidden h-3.5 w-3.5 text-slate-400 transition-transform duration-300 min-[360px]:block ${profileDropdownOpen ? 'rotate-180' : ''}`} />
              </motion.button>

              <AnimatePresence>
                {profileDropdownOpen && (
                  <motion.div
                    initial={{ opacity: 0, scale: 0.95, y: 8 }}
                    animate={{ opacity: 1, scale: 1, y: 0 }}
                    exit={{ opacity: 0, scale: 0.95, y: 8 }}
                    transition={{ type: 'spring', stiffness: 450, damping: 28 }}
                    className="absolute right-0 mt-1 w-60 bg-slate-950 text-slate-100 rounded-2xl shadow-2xl border border-slate-800 py-2 z-50 overflow-hidden"
                  >
                    <div className="px-4 py-3 border-b border-slate-800/80 bg-slate-900/60">
                      <p className="text-xs font-bold text-white flex items-center justify-between">
                        <span className="truncate max-w-[130px]">{user?.fullName || 'User'}</span>
                        <span className={`px-2 py-0.5 rounded-full text-[9px] font-black uppercase border ${
                          role === 'SUPER_ADMIN' || role === 'ADMIN' ? 'bg-amber-500/20 text-amber-300 border-amber-500/40' :
                          role === 'SUB_ADMIN' ? 'bg-purple-500/20 text-purple-300 border-purple-500/40' :
                          role === 'EMPLOYEE' ? 'bg-indigo-500/20 text-indigo-300 border-indigo-500/40' : 'bg-emerald-500/20 text-emerald-300 border-emerald-500/40'
                        }`}>
                          {role}
                        </span>
                      </p>
                      <p className="text-[11px] text-slate-400 truncate mt-0.5">{user?.email || 'admin@pathome.in'}</p>
                    </div>

                    <div className="p-1">
                      <button
                        onClick={() => { setProfileDropdownOpen(false); onLogout(); }}
                        className="w-full text-left px-3.5 py-2.5 rounded-xl text-xs font-bold text-rose-400 hover:bg-rose-500/10 hover:text-rose-300 flex items-center gap-2.5 transition-colors"
                      >
                        <LogOut className="w-4 h-4 text-rose-400" /> Log Out Session
                      </button>
                    </div>
                  </motion.div>
                )}
              </AnimatePresence>
            </div>
          )}

        </div>

      </div>
    </motion.header>
  );
};
