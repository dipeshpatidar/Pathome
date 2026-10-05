import React, { useState, useEffect, useRef } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import {
  motion,
  AnimatePresence,
  useReducedMotion
} from 'framer-motion';
import { UserRole, UserProfile } from '../types';
import { resolveWorkspaceContext, resolveLogoDestination } from '../utils/navigationPolicy';
import {
  Building2,
  Home as HomeIcon,
  User,
  LogOut,
  ChevronDown,
  ChevronRight,
  Bell,
  Menu,
  X,
  ArrowRight
} from 'lucide-react';
import { useNotification } from '../context/NotificationContext';
import { shouldShowMyProperties, shouldShowListYourProperty } from '../utils/navigationPolicy';
import { DraftAccessButton, DraftAccessState } from './DraftAccessButton';

interface NavbarProps {
  user: UserProfile | null;
  role: UserRole;
  onOpenAuthModal: () => void;
  onLogout: () => void;
  onOpenPostProperty: () => void;
  postPropertyModalOpen: boolean;
  onClosePostProperty: () => void;
  activeAdminTab?: string;
  setActiveAdminTab?: (tab: string) => void;
  isLandingHero?: boolean;
  hasLessorCapability: boolean | null | 'error';
  draftCount: number;
  draftState: DraftAccessState;
  onOpenDrafts: () => void;
  onRetryDrafts: () => void;
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
      <div className="relative max-h-[calc(100dvh-2rem)] w-full max-w-lg overflow-y-auto overscroll-contain rounded-3xl border border-white/20 bg-slate-900 p-6 text-white shadow-2xl sm:p-8">
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
            <p className="mt-1 text-xs text-slate-300">Connect directly with prospective tenants without middleman commissions.</p>
          </div>
          <div className="rounded-2xl border border-white/10 bg-slate-950/50 p-3.5">
            <p className="text-xs font-bold text-white">Online Visit Requests</p>
            <p className="mt-1 text-xs text-slate-300">Receive tenant visit requests directly for your property.</p>
          </div>
          <div className="rounded-2xl border border-white/10 bg-slate-950/50 p-3.5">
            <p className="text-xs font-bold text-white">Digital Lease Agreements</p>
            <p className="mt-1 text-xs text-slate-300">Secure agreement execution and rent collection assistance.</p>
          </div>
        </div>

        <div className="mt-6 rounded-2xl border border-emerald-500/20 bg-emerald-950/40 p-4 text-xs text-emerald-200 leading-relaxed">
          Online self-listing is actively expanding across our supported cities. Today, our property onboarding team assists with listing onboarding directly.
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
  onLogout,
  onOpenPostProperty,
  postPropertyModalOpen,
  onClosePostProperty,
  activeAdminTab = 'overview',
  setActiveAdminTab,
  isLandingHero = false,
  hasLessorCapability,
  draftCount,
  draftState,
  onOpenDrafts,
  onRetryDrafts
}) => {
  const [profileDropdownOpen, setProfileDropdownOpen] = useState(false);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [isScrolled, setIsScrolled] = useState(() => (typeof window !== 'undefined' ? window.scrollY > 40 : false));
  const { unreadCount, isDrawerOpen, setIsDrawerOpen } = useNotification();
  const prefersReducedMotion = useReducedMotion();
  const navigate = useNavigate();
  const location = useLocation();
  const isTenantLanding = role === 'TENANT' && location.pathname.replace(/\/+$/, '') === '/tenant';
  const tenantBreadcrumb = location.hash === '#saved-homes-title' ? 'Saved homes'
    : location.hash === '#visit-history' || location.hash.startsWith('#visit-session-') ? 'Visit requests'
      : location.hash === '#account' ? 'Your account'
        : location.hash === '#notifications' ? 'Notifications' : 'Explore homes';
  const isLessorSurface = /^\/lessor(?:\/|$)/.test(location.pathname);
  const isDraftHub = isLessorSurface && new URLSearchParams(location.search).get('view') === 'drafts';
  const isPropertySurface = role === 'TENANT' && /^\/property(?:\/|$)/.test(location.pathname);
  const isConsumerHeader = isTenantLanding || isLessorSurface || isPropertySurface;

  // Keep one mounted header across identity changes and drop account overlays immediately.
  useEffect(() => {
    setProfileDropdownOpen(false);
    setMobileMenuOpen(false);
  }, [user?.id, role]);

  const isAdminRole = role === 'ADMIN' || role === 'SUPER_ADMIN' || role === 'SUB_ADMIN';
  const workspaceContext = resolveWorkspaceContext(location.pathname, role);
  const logoDestination = resolveLogoDestination(workspaceContext);

  const showMyProperties = shouldShowMyProperties(role, hasLessorCapability);
  const showListProperty = shouldShowListYourProperty(role, hasLessorCapability);

  // Track scroll position to transition from transparent Hero overlay to solid sticky header
  useEffect(() => {
    const handleScroll = () => {
      setIsScrolled((current) => {
        const next = window.scrollY > 40;
        return current === next ? current : next;
      });
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
      <header data-pathome-header="global" data-guest-lessor={isLessorSurface && role === 'GUEST'} data-tenant-header={isConsumerHeader} data-consumer-section={isLessorSurface ? 'lessor' : isPropertySurface ? 'property' : isTenantLanding ? 'tenant' : undefined}
        className={`sticky top-0 z-[100] w-full pt-[env(safe-area-inset-top)] transition-[background-color,border-color,box-shadow] duration-300 motion-reduce:transition-none ${
          isConsumerHeader
            ? 'border-b border-[#eeece7] bg-[#f8f7f4]/95 shadow-none backdrop-blur-xl'
            : isAdminRole
            ? 'bg-slate-950/95 backdrop-blur-2xl border-b border-emerald-500/30 shadow-2xl'
            : isHeroTop
            ? 'bg-slate-950/40 backdrop-blur-md border-b border-white/10 shadow-sm'
            : 'bg-white/95 backdrop-blur-xl border-b border-slate-200/90 shadow-xs'
        }`}
      >
        {/* The mobile toolbar stays quiet; the established accent remains on larger screens. */}
        <div
          className={`h-0 w-full transition-opacity duration-300 ${isConsumerHeader ? '' : 'sm:h-[2.5px]'} ${
            isHeroTop
              ? 'bg-gradient-to-r from-emerald-500/80 via-cyan-400/80 via-indigo-500/80 to-amber-400/80 shadow-[0_0_12px_rgba(16,185,129,0.3)]'
              : 'bg-gradient-to-r from-emerald-500 via-cyan-400 via-indigo-500 to-amber-400 shadow-[0_0_12px_rgba(16,185,129,0.5)]'
          }`}
        />

        <div className={`${isConsumerHeader ? 'tenant-v0-header-inner max-w-[1220px]' : 'max-w-7xl'} mx-auto flex items-center justify-between gap-1 px-2.5 min-[360px]:px-3 sm:gap-2 sm:px-6 lg:px-8 ${role === 'GUEST' ? 'h-[60px] sm:h-[68px] lg:h-[72px]' : isConsumerHeader ? 'h-[63px]' : 'h-[72px]'}`}>

          {(isTenantLanding || (isLessorSurface && role !== 'GUEST')) && <div className="tenant-v0-crumbs hidden items-center gap-2 lg:flex"><span>Home</span><ChevronRight size={14} aria-hidden="true" /><strong>{isLessorSurface ? isDraftHub ? 'Your drafts' : hasLessorCapability === true ? 'Your listings' : 'List your property' : tenantBreadcrumb}</strong></div>}

          {/* BRANDING LOGO */}
          <div data-tenant-header-brand={isTenantLanding || isLessorSurface || undefined} className={`flex min-w-0 items-center gap-2.5 sm:gap-6 lg:gap-8 ${isTenantLanding ? 'lg:hidden' : ''}`}>
            <motion.button
              type="button"
              whileHover={{ scale: 1.02 }}
              whileTap={{ scale: 0.98 }}
              onClick={() => isLessorSurface && role === 'GUEST' ? navigate('/') : isTenantLanding ? navigate('/tenant#tenant-home-search') : navigate(logoDestination.path)}
              aria-label={isLessorSurface && role === 'GUEST' ? 'Pathome home' : logoDestination.label}
              title={isLessorSurface && role === 'GUEST' ? 'Pathome home' : logoDestination.label}
              className="flex min-w-0 items-center gap-2.5 cursor-pointer text-left rounded-xl p-0.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 bg-transparent border-none"
            >
              <div
                className={`flex w-9 h-9 sm:w-10 sm:h-10 rounded-xl items-center justify-center font-bold shrink-0 shadow-sm transition-colors ${
                  isAdminRole
                    ? 'bg-gradient-to-tr from-emerald-600 via-teal-500 to-emerald-400 text-white shadow-emerald-500/30'
                    : isConsumerHeader ? 'bg-[#355c49] text-white' : 'bg-emerald-600 text-white shadow-emerald-600/20'
                }`}
              >
                {isConsumerHeader ? <HomeIcon className="w-5 h-5" /> : <Building2 className="w-5 h-5" />}
              </div>
              <div className="min-w-0">
                <span
                  className={`font-['Outfit',sans-serif] text-base sm:text-xl font-black tracking-tight inline-flex items-center transition-colors ${
                    isConsumerHeader ? 'text-[#344036]' : isAdminRole || isHeroTop ? 'text-white' : 'text-slate-900'
                  }`}
                >
                  {isConsumerHeader ? <>pathome<span className="text-[#6d886e]">.</span></> : <>Path<span className="text-emerald-400">ome</span></>}
                </span>
                <div className="flex items-center gap-1.5 mt-0.5">
                  <span
                    className={`pathome-tagline inline-flex whitespace-nowrap text-[9px] leading-3 min-[390px]:text-[10px] min-[390px]:leading-3.5 sm:text-[11px] sm:leading-4 font-bold px-1.5 sm:px-2 py-0.5 rounded-full border items-center transition-colors ${
                      isTenantLanding && isHeroTop
                        ? 'border-transparent bg-transparent px-0 text-white/80'
                        : isAdminRole
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
            </motion.button>

            {/* Desktop Nav Links (1024px+): Spacious, deliberate layout without cramped tablet squeeze */}
            {role === 'GUEST' && !isLessorSurface && (
              <nav className="hidden lg:flex items-center gap-6" aria-label="Main Navigation">
                <a
                  href="/#homes"
                  className={`text-xs font-bold transition-colors ${
                    isHeroTop ? 'text-white/80 hover:text-white' : 'text-slate-700 hover:text-emerald-600'
                  }`}
                >
                  Rental Homes
                </a>
                <a
                  href="/#how-it-works"
                  className={`text-xs font-bold transition-colors ${
                    isHeroTop ? 'text-white/80 hover:text-white' : 'text-slate-700 hover:text-emerald-600'
                  }`}
                >
                  How It Works
                </a>
                <a
                  href="/#why-us"
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
          <div className={`flex shrink-0 items-center gap-1 sm:gap-3 ${isTenantLanding || isLessorSurface ? 'lg:ml-auto' : ''}`}>

            {role === 'GUEST' && (
              <>
                {/* The owner action remains in desktop navigation; mobile finds it beside Hero search. */}
                <button
                  type="button"
                  onClick={onOpenPostProperty}
                  className={`${isDraftHub ? 'hidden' : 'hidden lg:inline-flex'} h-11 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-xl border px-3.5 text-xs font-bold leading-none transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400 focus-visible:ring-offset-2 ${
                    isHeroTop
                      ? 'border-white/20 bg-white/10 text-emerald-300 hover:bg-white/15'
                      : 'border-emerald-200/80 bg-emerald-50 text-emerald-800 hover:bg-emerald-100'
                  }`}
                >
                  <Building2 className="h-3.5 w-3.5 shrink-0 text-emerald-400" aria-hidden="true" />
                  <span>List your property</span>
                </button>

                {/* Sign In / Register CTA */}
                <motion.button
                  whileHover={prefersReducedMotion ? undefined : { scale: 1.03 }}
                  whileTap={prefersReducedMotion ? undefined : { scale: 0.985 }}
                  onClick={onOpenAuthModal}
                  aria-label="Sign in or register"
                  className={`flex h-11 min-w-11 items-center justify-center gap-2 rounded-full border border-transparent px-0 text-xs font-semibold transition-colors sm:rounded-xl sm:bg-emerald-600 sm:px-3 sm:text-white sm:hover:bg-emerald-700 min-[640px]:px-4 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400 focus-visible:ring-offset-2 lg:border-transparent ${
                    isHeroTop ? 'text-white/90 hover:bg-white/10' : 'text-slate-700 hover:bg-slate-900/5'
                  }`}
                >
                  <User className="w-3.5 h-3.5" />
                  <span className="hidden min-[640px]:inline">Sign in</span>
                </motion.button>

                {/* Tablet & Mobile Menu Toggle (<1024px) */}
                <button
                  type="button"
                  onClick={() => setMobileMenuOpen((prev) => !prev)}
                  aria-expanded={mobileMenuOpen}
                  aria-label={mobileMenuOpen ? 'Close navigation menu' : 'Open navigation menu'}
                  className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-full border border-transparent transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400 focus-visible:ring-offset-2 lg:hidden ${
                    isHeroTop
                      ? 'text-white hover:bg-white/10'
                      : 'text-slate-700 hover:bg-slate-900/5'
                  }`}
                >
                  {mobileMenuOpen ? <X className="w-4 h-4" /> : <Menu className="w-4 h-4" />}
                </button>
              </>
            )}

            {(role === 'GUEST' || role === 'TENANT' || hasLessorCapability === true) && (
              isTenantLanding
                ? <div className="hidden lg:block"><DraftAccessButton count={draftCount} state={draftState} onOpen={onOpenDrafts} onRetry={onRetryDrafts} dark={isHeroTop} /></div>
                : <DraftAccessButton count={draftCount} state={draftState} onOpen={onOpenDrafts} onRetry={onRetryDrafts} dark={isHeroTop} />
            )}

            {/* CENTRALIZED NOTIFICATION BELL — RENDERED ONLY FOR AUTHENTICATED USERS */}
            {user && role !== 'GUEST' && (
              <motion.button
                whileHover={{ scale: 1.05 }}
                whileTap={{ scale: 0.95 }}
                onClick={() => {
                  if (isConsumerHeader && role === 'TENANT') {
                    setIsDrawerOpen(false);
                    navigate('/tenant#notifications');
                  } else setIsDrawerOpen(!isDrawerOpen);
                }}
                className={`relative flex min-h-11 min-w-11 items-center justify-center rounded-2xl border transition-all cursor-pointer shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 ${
                  isConsumerHeader
                    ? 'border-0 bg-transparent text-[#61715d] shadow-none hover:bg-[#edf2ed]'
                    : isAdminRole
                    ? 'bg-slate-900/90 text-emerald-400 border-slate-800 hover:border-emerald-500/50 hover:bg-slate-800'
                    : 'bg-slate-900/90 text-emerald-400 border-slate-800 hover:bg-slate-800'
                }`}
                title={unreadCount > 0 ? `Notifications (${unreadCount} unread)` : 'Notifications'}
                aria-label={unreadCount > 0 ? `Open notifications, ${unreadCount} unread` : 'Open notifications'}
              >
                <Bell className="w-4 h-4 sm:w-4.5 sm:h-4.5" aria-hidden="true" />
                {unreadCount > 0 && (
                  <span
                    aria-hidden="true"
                    className="absolute -top-1 -right-1 inline-flex h-[18px] min-w-[18px] items-center justify-center rounded-full border border-white bg-[#355c49] px-1 font-sans text-[10px] font-semibold leading-none text-white shadow-sm"
                  >
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
                  aria-label={isConsumerHeader ? `Profile menu for ${user?.fullName || role}` : undefined}
                  aria-expanded={isConsumerHeader ? profileDropdownOpen : undefined}
                  className={`flex min-h-11 min-w-11 items-center justify-center gap-2 ${isConsumerHeader ? 'rounded-full focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-300' : 'rounded-2xl'} border p-1.5 transition-all shadow-md ${
                    isConsumerHeader
                      ? 'border-0 bg-transparent text-[#61715d] shadow-none hover:bg-[#edf2ed]'
                      : isAdminRole
                      ? 'bg-slate-900 border-slate-800 text-white hover:border-emerald-500/40'
                      : 'bg-slate-900 border-slate-800 text-white'
                  }`}
                >
                  <div className={`w-7 h-7 ${isConsumerHeader ? 'rounded-full' : 'rounded-xl'} flex items-center justify-center font-extrabold text-xs shadow-inner ${
                    isConsumerHeader ? 'bg-[#e8ded4] text-[#675145]' : role === 'SUPER_ADMIN' || role === 'ADMIN' ? 'bg-gradient-to-tr from-amber-500 to-amber-300 text-slate-950' :
                    role === 'SUB_ADMIN' ? 'bg-gradient-to-tr from-purple-600 to-purple-400 text-white' :
                    role === 'EMPLOYEE' ? 'bg-gradient-to-tr from-indigo-600 to-indigo-400 text-white' : 'bg-emerald-500 text-slate-950'
                  }`}>
                    {user?.fullName ? user.fullName.charAt(0).toUpperCase() : role.charAt(0)}
                  </div>
                  <span className={`text-xs font-bold max-w-[180px] sm:max-w-[220px] truncate hidden sm:inline ${isConsumerHeader ? 'text-[#39483d]' : 'text-slate-200'}`}>
                    {user?.fullName || role}
                  </span>
                  <ChevronDown className={`hidden h-3.5 w-3.5 text-slate-400 transition-transform duration-300 sm:block ${profileDropdownOpen ? 'rotate-180' : ''}`} />
                </motion.button>

                  {profileDropdownOpen && (
                    <motion.div
                      initial={{ opacity: 0, scale: 0.95, y: 8 }}
                      animate={{ opacity: 1, scale: 1, y: 0 }}
                      transition={{ type: 'spring', stiffness: 450, damping: 28 }}
                      className={`absolute right-0 mt-1 w-60 rounded-2xl border py-2 shadow-2xl z-50 overflow-hidden ${isConsumerHeader ? 'tenant-v0-profile-dropdown border-[#e9e7e1] bg-white text-[#252b25]' : 'border-slate-800 bg-slate-950 text-slate-100'}`}
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
                        {user?.email && <p className="text-[11px] text-slate-400 truncate mt-0.5">{user.email}</p>}
                      </div>

                      <div className="p-1">
                        {isConsumerHeader && role === 'TENANT' && <button type="button" onClick={() => { setProfileDropdownOpen(false); navigate('/tenant#account'); }}
                          className="flex min-h-11 w-full items-center gap-2.5 rounded-xl px-3.5 text-left text-xs font-bold text-emerald-300 hover:bg-emerald-500/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400">
                          <User className="h-4 w-4" />Your account
                        </button>}
                        {showMyProperties && (
                          <button
                            onClick={() => { setProfileDropdownOpen(false); navigate('/lessor'); }}
                            className="flex min-h-11 w-full items-center gap-2.5 rounded-xl px-3.5 text-left text-xs font-bold text-emerald-300 hover:bg-emerald-500/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400 cursor-pointer"
                          >
                            <Building2 className="h-4 w-4"/>My Properties
                          </button>
                        )}
                        {role === 'TENANT' && hasLessorCapability === null && (
                          <p role="status" className="px-3.5 py-2.5 text-xs text-slate-300">Checking property access…</p>
                        )}
                        {role === 'TENANT' && hasLessorCapability === 'error' && (
                          <div className="px-3.5 py-2.5 text-xs text-slate-300">
                            <p role="alert">Could not check property access.</p>
                            <button
                              type="button"
                              onClick={() => window.dispatchEvent(new Event('pathome_auth_changed'))}
                              className="mt-2 min-h-11 font-semibold text-emerald-300 underline underline-offset-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400"
                            >Try again</button>
                          </div>
                        )}
                        {showListProperty && role === 'TENANT' && (
                          <button
                            type="button"
                            onClick={() => { setProfileDropdownOpen(false); onOpenPostProperty(); }}
                            className="flex min-h-11 w-full items-center gap-2.5 rounded-xl px-3.5 text-left text-xs font-bold text-emerald-300 hover:bg-emerald-500/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400 cursor-pointer"
                          >
                            <Building2 className="h-4 w-4"/>List your property
                          </button>
                        )}
                        {draftState === 'error' && (
                          <div className="px-3.5 py-2.5 text-xs text-slate-300">
                            <p role="alert">Drafts could not be checked.</p>
                            <button type="button" onClick={onRetryDrafts} className="mt-2 min-h-11 font-semibold text-emerald-300 underline underline-offset-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400">Try again</button>
                          </div>
                        )}
                        <button
                          onClick={() => { setProfileDropdownOpen(false); onLogout(); }}
                          className="w-full text-left px-3.5 py-2.5 min-h-[44px] rounded-xl text-xs font-bold text-rose-400 hover:bg-rose-500/10 hover:text-rose-300 flex items-center gap-2.5 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-400"
                        >
                          <LogOut className="w-4 h-4 text-rose-400" /> Log Out Session
                        </button>
                      </div>
                    </motion.div>
                  )}
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
                  href="/#homes"
                  onClick={() => setMobileMenuOpen(false)}
                  className={`block px-3.5 py-2.5 rounded-xl text-sm font-semibold transition-colors ${
                    isHeroTop ? 'text-white/90 hover:bg-white/10 hover:text-white' : 'text-slate-700 hover:bg-slate-100 hover:text-emerald-600'
                  }`}
                >
                  Rental Homes
                </a>
                <a
                  href="/#how-it-works"
                  onClick={() => setMobileMenuOpen(false)}
                  className={`block px-3.5 py-2.5 rounded-xl text-sm font-semibold transition-colors ${
                    isHeroTop ? 'text-white/90 hover:bg-white/10 hover:text-white' : 'text-slate-700 hover:bg-slate-100 hover:text-emerald-600'
                  }`}
                >
                  How It Works
                </a>
                <a
                  href="/#why-us"
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
                      onOpenPostProperty();
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

      </header>

      {/* Post Your Property Informational Modal */}
      <PostPropertyModal
        isOpen={postPropertyModalOpen}
        onClose={onClosePostProperty}
      />
    </>
  );
};
