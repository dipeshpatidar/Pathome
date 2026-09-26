import React, { useState, useEffect, useRef } from 'react';
import {
  motion,
  AnimatePresence,
  useReducedMotion
} from 'framer-motion';
import { UserRole, UserProfile } from '../types';
import {
  Building2,
  Sparkles,
  Gift,
  User,
  LogOut,
  ChevronDown,
  Bell,
  Menu,
  X,
  ArrowRight
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
  isLandingHero?: boolean;
}

/**
 * Truthful, non-destructive Landlord listing informational modal.
 * Connects property owners directly to Pathome's onboarding desk
 * without fabricating unbuilt self-listing forms or routing to admin console.
 */
const PostPropertyModal: React.FC<{ isOpen: boolean; onClose: () => void }> = ({ isOpen, onClose }) => {
  const closeBtnRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!isOpen) return;
    closeBtnRef.current?.focus();
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handleKeyDown);
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      document.body.style.overflow = prevOverflow;
    };
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="post-property-title"
      className="fixed inset-0 z-[9999] flex items-center justify-center bg-slate-950/80 p-4 backdrop-blur-md"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="relative w-full max-w-lg rounded-3xl border border-white/20 bg-slate-900 p-6 text-white shadow-2xl sm:p-8">
        <button
          ref={closeBtnRef}
          type="button"
          onClick={onClose}
          aria-label="Close dialog"
          className="absolute right-4 top-4 flex h-10 w-10 items-center justify-center rounded-full bg-white/10 text-white/80 transition-colors hover:bg-white/20 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400"
        >
          <X className="h-5 w-5" />
        </button>

        <div className="flex items-center gap-3">
          <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-emerald-500/20 text-emerald-400 border border-emerald-500/30">
            <Building2 className="h-6 w-6" />
          </div>
          <div>
            <h2 id="post-property-title" className="font-['Outfit',sans-serif] text-xl font-black text-white sm:text-2xl">
              Post Your Property
            </h2>
            <p className="text-xs font-semibold text-emerald-400">Direct Landlord Listing Desk</p>
          </div>
        </div>

        <div className="mt-5 space-y-3">
          <div className="rounded-2xl border border-white/10 bg-slate-950/50 p-3.5">
            <p className="text-xs font-bold text-white">0% Brokerage for Direct Owners</p>
            <p className="mt-1 text-xs text-slate-300">Connect directly with verified tenants without middleman commissions.</p>
          </div>
          <div className="rounded-2xl border border-white/10 bg-slate-950/50 p-3.5">
            <p className="text-xs font-bold text-white">Field-Verified Escorted Visits</p>
            <p className="mt-1 text-xs text-slate-300">Our local operations team assists verified tenant visits on your schedule.</p>
          </div>
          <div className="rounded-2xl border border-white/10 bg-slate-950/50 p-3.5">
            <p className="text-xs font-bold text-white">Digital Lease Agreements</p>
            <p className="mt-1 text-xs text-slate-300">Secure agreement execution and rent collection assistance.</p>
          </div>
        </div>

        <div className="mt-6 rounded-2xl border border-emerald-500/20 bg-emerald-950/40 p-4 text-xs text-emerald-200 leading-relaxed">
          Online self-listing is actively expanding across our supported cities. Today, our property onboarding team lists and verifies homes directly.
        </div>

        <div className="mt-6 flex flex-col gap-3 sm:flex-row sm:items-center">
          <a
            href="mailto:listings@pathome.in?subject=Post%20Property%20Inquiry%20-%20Pathome"
            className="inline-flex min-h-11 flex-1 items-center justify-center gap-2 rounded-xl bg-emerald-600 px-5 text-sm font-bold text-white transition-colors hover:bg-emerald-500 active:scale-[0.98] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400"
          >
            <span>Contact Onboarding Team</span>
            <ArrowRight className="h-4 w-4" />
          </a>
          <button
            type="button"
            onClick={onClose}
            className="inline-flex min-h-11 items-center justify-center rounded-xl border border-white/20 bg-white/5 px-5 text-sm font-semibold text-white/80 transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/40"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};

export const Navbar: React.FC<NavbarProps> = ({
  user,
  role,
  onOpenAuthModal,
  onOpenLeaseUpload,
  onLogout,
  activeAdminTab = 'overview',
  setActiveAdminTab,
  isLandingHero = false
}) => {
  const [profileDropdownOpen, setProfileDropdownOpen] = useState(false);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [showPostPropertyModal, setShowPostPropertyModal] = useState(false);
  const [isScrolled, setIsScrolled] = useState(() => (typeof window !== 'undefined' ? window.scrollY > 40 : false));
  const { unreadCount, isDrawerOpen, setIsDrawerOpen } = useNotification();
  const prefersReducedMotion = useReducedMotion();

  const visitsUsed = user?.freeVisitsUsed || 0;
  const isAdminRole = role === 'ADMIN' || role === 'SUPER_ADMIN' || role === 'SUB_ADMIN';

  // Track scroll position to transition from transparent Hero overlay to solid sticky header
  useEffect(() => {
    const handleScroll = () => {
      setIsScrolled(window.scrollY > 40);
    };
    handleScroll();
    window.addEventListener('scroll', handleScroll, { passive: true });
    return () => window.removeEventListener('scroll', handleScroll);
  }, []);

  // Close mobile drawer on desktop resize
  useEffect(() => {
    const handleResize = () => {
      if (window.innerWidth >= 1024) setMobileMenuOpen(false);
    };
    window.addEventListener('resize', handleResize, { passive: true });
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  // Determine active visual theme
  const isHeroTop = isLandingHero && !isScrolled;

  return (
    <>
      <motion.header
        initial={prefersReducedMotion ? false : { opacity: 0, y: -12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: prefersReducedMotion ? 0 : 0.45, ease: [0.16, 1, 0.3, 1] }}
        className={`sticky top-0 z-[100] w-full transition-colors transition-shadow duration-300 ${
          isAdminRole
            ? 'bg-slate-950/95 backdrop-blur-2xl border-b border-emerald-500/30 shadow-2xl'
            : isHeroTop
            ? 'bg-slate-950/40 backdrop-blur-md border-b border-white/10 shadow-sm'
            : 'bg-white/95 backdrop-blur-xl border-b border-slate-200/90 shadow-xs'
        }`}
      >
        {/* GLOWING TOP ACCENT BEAM LINE WITH FLOWING GRADIENT */}
        <div
          className={`h-[2.5px] w-full transition-opacity duration-300 ${
            isHeroTop
              ? 'bg-gradient-to-r from-emerald-500/80 via-cyan-400/80 via-indigo-500/80 to-amber-400/80 shadow-[0_0_12px_rgba(16,185,129,0.3)]'
              : 'bg-gradient-to-r from-emerald-500 via-cyan-400 via-indigo-500 to-amber-400 shadow-[0_0_12px_rgba(16,185,129,0.5)]'
          }`}
        />

        <div className="max-w-7xl mx-auto px-3 min-[360px]:px-4 sm:px-6 lg:px-8 h-[72px] flex items-center justify-between gap-2">

          {/* BRANDING LOGO */}
          <div className="flex min-w-0 items-center gap-2.5 sm:gap-6 lg:gap-8">
            <motion.div
              whileHover={{ scale: 1.02 }}
              whileTap={{ scale: 0.98 }}
              className="flex min-w-0 items-center gap-2.5 cursor-pointer"
            >
              <div
                className={`w-9 h-9 sm:w-10 sm:h-10 rounded-2xl flex items-center justify-center font-bold shrink-0 shadow-lg transition-colors ${
                  isAdminRole
                    ? 'bg-gradient-to-tr from-emerald-600 via-teal-500 to-emerald-400 text-white shadow-emerald-500/30'
                    : 'bg-emerald-600 text-white shadow-emerald-600/20'
                }`}
              >
                <Building2 className="w-5 h-5" />
              </div>
              <div className="min-w-0">
                <a
                  href="#"
                  className={`font-['Outfit',sans-serif] text-base sm:text-xl font-black tracking-tight inline-flex items-center transition-colors ${
                    isAdminRole || isHeroTop ? 'text-white' : 'text-slate-900'
                  }`}
                >
                  Path<span className="text-emerald-400">ome</span>
                </a>
                <div className="flex items-center gap-1.5 mt-0.5">
                  <span
                    className={`pathome-tagline inline-flex whitespace-nowrap text-[10px] leading-4 sm:text-[11px] font-bold px-1.5 sm:px-2 py-0.5 rounded-full border items-center transition-colors ${
                      isAdminRole
                        ? 'text-emerald-300 bg-emerald-950/80 border-emerald-500/30'
                        : isHeroTop
                        ? 'text-emerald-300 bg-emerald-950/70 border-emerald-500/40'
                        : 'text-emerald-700 bg-emerald-50 border-emerald-200'
                    }`}
                  >
                    Your Dreams, Our Efforts.
                  </span>
                  {isAdminRole && (
                    <span className="text-[10px] font-mono font-bold text-amber-400 bg-amber-500/10 px-2 py-0.5 rounded-full border border-amber-500/30 hidden sm:inline">
                      Console Mode
                    </span>
                  )}
                </div>
              </div>
            </motion.div>

            {/* Desktop Nav Links (1024px+): Spacious, deliberate layout without cramped tablet squeeze */}
            {role === 'GUEST' && (
              <nav className="hidden lg:flex items-center gap-6" aria-label="Main Navigation">
                <a
                  href="#listings"
                  className={`text-xs font-bold transition-colors ${
                    isHeroTop ? 'text-white/80 hover:text-white' : 'text-slate-700 hover:text-emerald-600'
                  }`}
                >
                  Verified Rentals
                </a>
                <a
                  href="#how-it-works"
                  className={`text-xs font-bold transition-colors ${
                    isHeroTop ? 'text-white/80 hover:text-white' : 'text-slate-700 hover:text-emerald-600'
                  }`}
                >
                  How It Works
                </a>
                <a
                  href="#why-us"
                  className={`text-xs font-bold transition-colors ${
                    isHeroTop ? 'text-white/80 hover:text-white' : 'text-slate-700 hover:text-emerald-600'
                  }`}
                >
                  Why Pathome
                </a>
              </nav>
            )}
          </div>

          {/* RIGHT ACTIONS & PROFILE MENU */}
          <div className="flex shrink-0 items-center gap-2 sm:gap-3">

            {role === 'GUEST' && (
              <>
                {/* Post Your Property: Desktop (1024px+) & Tablet (768px+) Entry Point */}
                <button
                  type="button"
                  onClick={() => setShowPostPropertyModal(true)}
                  className={`hidden sm:inline-flex items-center gap-1.5 px-3 sm:px-3.5 py-2 rounded-xl text-xs font-bold transition-all active:scale-[0.98] ${
                    isHeroTop
                      ? 'text-emerald-300 hover:text-emerald-200 bg-white/10 hover:bg-white/15 border border-white/20 shadow-sm backdrop-blur-sm'
                      : 'text-emerald-800 hover:text-emerald-900 bg-emerald-50 hover:bg-emerald-100 border border-emerald-200/80 shadow-xs'
                  }`}
                >
                  <Building2 className="w-3.5 h-3.5 text-emerald-400 shrink-0" />
                  <span className="hidden min-[920px]:inline">Post Your Property</span>
                  <span className="min-[920px]:hidden">Post Property</span>
                </button>

                {/* Sign In / Register CTA */}
                <motion.button
                  whileHover={{ scale: 1.03 }}
                  whileTap={{ scale: 0.97 }}
                  onClick={onOpenAuthModal}
                  aria-label="Sign in or register"
                  className="flex h-10 min-w-10 items-center justify-center gap-2 rounded-xl bg-emerald-600 px-3 text-xs font-extrabold text-white shadow-md shadow-emerald-600/20 transition-all hover:bg-emerald-500 min-[420px]:px-4"
                >
                  <User className="w-3.5 h-3.5" />
                  <span className="hidden min-[420px]:inline">Sign In / Register</span>
                </motion.button>

                {/* Tablet & Mobile Menu Toggle (<1024px) */}
                <button
                  type="button"
                  onClick={() => setMobileMenuOpen((prev) => !prev)}
                  aria-expanded={mobileMenuOpen}
                  aria-label={mobileMenuOpen ? 'Close navigation menu' : 'Open navigation menu'}
                  className={`flex h-10 w-10 items-center justify-center rounded-xl border transition-colors lg:hidden ${
                    isHeroTop
                      ? 'border-white/20 bg-white/10 text-white hover:bg-white/15'
                      : 'border-slate-200 bg-slate-100 text-slate-700 hover:bg-slate-200'
                  }`}
                >
                  {mobileMenuOpen ? <X className="w-4 h-4" /> : <Menu className="w-4 h-4" />}
                </button>
              </>
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

            {/* CENTRALIZED NOTIFICATION BELL — RENDERED ONLY FOR AUTHENTICATED USERS */}
            {role !== 'GUEST' && (
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
                aria-label="Open Portal Notification Center"
              >
                <Bell className="w-4 h-4 sm:w-4.5 sm:h-4.5" />
                {unreadCount > 0 && (
                  <span className="absolute -top-1 -right-1 w-5 h-5 rounded-full bg-emerald-500 text-slate-950 font-mono font-black text-[10px] flex items-center justify-center border-2 border-slate-950 shadow-lg shadow-emerald-500/50 animate-bounce">
                    {unreadCount > 9 ? '9+' : unreadCount}
                  </span>
                )}
              </motion.button>
            )}

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

        {/* RESPONSIVE MOBILE & TABLET DRAWER (<1024px) */}
        <AnimatePresence>
          {mobileMenuOpen && role === 'GUEST' && (
            <motion.div
              initial={{ opacity: 0, height: 0 }}
              animate={{ opacity: 1, height: 'auto' }}
              exit={{ opacity: 0, height: 0 }}
              transition={{ duration: prefersReducedMotion ? 0 : 0.2 }}
              className={`border-b lg:hidden overflow-hidden ${
                isHeroTop
                  ? 'bg-slate-950/95 border-white/10 text-white backdrop-blur-2xl'
                  : 'bg-white/98 border-slate-200 text-slate-900 backdrop-blur-2xl shadow-xl'
              }`}
            >
              <div className="max-w-7xl mx-auto px-4 py-4 space-y-2">
                <a
                  href="#listings"
                  onClick={() => setMobileMenuOpen(false)}
                  className={`block px-3.5 py-2.5 rounded-xl text-sm font-semibold transition-colors ${
                    isHeroTop ? 'text-white/90 hover:bg-white/10 hover:text-white' : 'text-slate-700 hover:bg-slate-100 hover:text-emerald-600'
                  }`}
                >
                  Verified Rentals
                </a>
                <a
                  href="#how-it-works"
                  onClick={() => setMobileMenuOpen(false)}
                  className={`block px-3.5 py-2.5 rounded-xl text-sm font-semibold transition-colors ${
                    isHeroTop ? 'text-white/90 hover:bg-white/10 hover:text-white' : 'text-slate-700 hover:bg-slate-100 hover:text-emerald-600'
                  }`}
                >
                  How It Works
                </a>
                <a
                  href="#why-us"
                  onClick={() => setMobileMenuOpen(false)}
                  className={`block px-3.5 py-2.5 rounded-xl text-sm font-semibold transition-colors ${
                    isHeroTop ? 'text-white/90 hover:bg-white/10 hover:text-white' : 'text-slate-700 hover:bg-slate-100 hover:text-emerald-600'
                  }`}
                >
                  Why Pathome
                </a>
                <div className="pt-2 border-t border-white/10 dark:border-slate-200/50">
                  <button
                    type="button"
                    onClick={() => {
                      setMobileMenuOpen(false);
                      setShowPostPropertyModal(true);
                    }}
                    className={`w-full text-left flex items-center justify-between px-3.5 py-2.5 rounded-xl text-sm font-bold transition-colors ${
                      isHeroTop
                        ? 'bg-emerald-950/60 text-emerald-300 border border-emerald-500/30 hover:bg-emerald-900/60'
                        : 'bg-emerald-50 text-emerald-800 border border-emerald-200 hover:bg-emerald-100'
                    }`}
                  >
                    <span className="flex items-center gap-2">
                      <Building2 className="w-4 h-4 text-emerald-400" />
                      Post Your Property
                    </span>
                    <span className="text-[10px] uppercase font-mono font-bold tracking-wider px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-300">
                      Landlords
                    </span>
                  </button>
                </div>
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </motion.header>

      {/* Post Your Property Informational Modal */}
      <PostPropertyModal
        isOpen={showPostPropertyModal}
        onClose={() => setShowPostPropertyModal(false)}
      />
    </>
  );
};
