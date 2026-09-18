import React, { useRef, useState, useEffect } from 'react';
import {
  motion,
  AnimatePresence
} from 'framer-motion';
import { Property } from '../types';
import {
  Play,
  Pause,
  Volume2,
  VolumeX,
  Maximize,
  X,
  MapPin,
  ShieldCheck,
  Key,
  Lock,
  Phone,
  RotateCcw,
  RotateCw,
  Camera,
  Video as VideoIcon,
  ChevronLeft,
  ChevronRight
} from 'lucide-react';

interface VideoPlayerModalProps {
  property: Property | null;
  isOpen: boolean;
  initialMode?: 'VIDEO' | 'PHOTOS';
  onClose: () => void;
  onBookTour: (property: Property) => void;
}

const LOCAL_VIDEOS = [
  "/assets/videos/property_walkthrough_1.mp4",
  "/assets/videos/property_walkthrough_2.mp4",
  "/assets/videos/property_walkthrough_3.mp4",
  "/assets/videos/property_walkthrough_4.mp4"
];

const formatTime = (timeInSeconds: number): string => {
  if (isNaN(timeInSeconds) || timeInSeconds < 0) return "0:00";
  const mins = Math.floor(timeInSeconds / 60);
  const secs = Math.floor(timeInSeconds % 60);
  return `${mins}:${secs < 10 ? '0' : ''}${secs}`;
};

export const VideoPlayerModal: React.FC<VideoPlayerModalProps> = ({
  property,
  isOpen,
  initialMode = 'VIDEO',
  onClose,
  onBookTour
}) => {
  const videoRef = useRef<HTMLVideoElement>(null);
  const seekTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const [activeMediaMode, setActiveMediaMode] = useState<'VIDEO' | 'PHOTOS'>(initialMode);
  const [activePhotoIdx, setActivePhotoIdx] = useState<number>(0);
  const [isPhotoLightboxOpen, setIsPhotoLightboxOpen] = useState<boolean>(false);
  const [isPlaying, setIsPlaying] = useState<boolean>(true);
  const [isMuted, setIsMuted] = useState<boolean>(true);
  const [progress, setProgress] = useState<number>(0);
  const [currentTime, setCurrentTime] = useState<number>(0);
  const [duration, setDuration] = useState<number>(0);
  const [videoError, setVideoError] = useState<boolean>(false);
  const [seekFeedback, setSeekFeedback] = useState<string | null>(null);

  const videoUrl = property ? (property.videoUrl || LOCAL_VIDEOS[(property.id || 0) % LOCAL_VIDEOS.length]) : '';
  const propertyImages = property?.images || [];

  useEffect(() => {
    if (isOpen) {
      setActiveMediaMode(initialMode);
      setActivePhotoIdx(0);
      setIsPhotoLightboxOpen(false);
      setVideoError(false);
      if (videoRef.current) {
        videoRef.current.currentTime = 0;
        setCurrentTime(0);
        const playPromise = videoRef.current.play();
        if (playPromise !== undefined) {
          playPromise
            .then(() => setIsPlaying(true))
            .catch((err) => {
              console.warn("Modal auto-play prevented:", err);
              setIsPlaying(false);
            });
        }
      }
    }
  }, [isOpen, property]);

  const seekBy = (seconds: number) => {
    if (activeMediaMode === 'PHOTOS') {
      if (seconds > 0) {
        setActivePhotoIdx((prev) => (prev + 1) % propertyImages.length);
      } else {
        setActivePhotoIdx((prev) => (prev - 1 + propertyImages.length) % propertyImages.length);
      }
      return;
    }

    if (videoRef.current) {
      const current = videoRef.current.currentTime;
      const total = videoRef.current.duration || duration || 0;
      const newTime = Math.min(Math.max(0, current + seconds), total);
      videoRef.current.currentTime = newTime;
      setCurrentTime(newTime);
      if (total > 0) {
        setProgress((newTime / total) * 100);
      }
      setSeekFeedback(seconds > 0 ? `+${seconds}s ▶▶` : `◀◀ ${seconds}s`);
      if (seekTimeoutRef.current) clearTimeout(seekTimeoutRef.current);
      seekTimeoutRef.current = setTimeout(() => setSeekFeedback(null), 700);
    }
  };

  const togglePlay = () => {
    if (activeMediaMode === 'PHOTOS') {
      setActiveMediaMode('VIDEO');
      return;
    }
    if (videoRef.current) {
      if (isPlaying) {
        videoRef.current.pause();
        setIsPlaying(false);
      } else {
        const playPromise = videoRef.current.play();
        if (playPromise !== undefined) {
          playPromise
            .then(() => setIsPlaying(true))
            .catch((err) => console.warn("Play error:", err));
        }
      }
    }
  };

  const toggleMute = () => {
    if (videoRef.current) {
      videoRef.current.muted = !isMuted;
      setIsMuted(!isMuted);
    }
  };

  // KEYBOARD CONTROLS LISTENERS
  useEffect(() => {
    if (!isOpen) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (['INPUT', 'TEXTAREA', 'SELECT'].includes((e.target as HTMLElement)?.tagName)) return;

      if (e.key === 'ArrowRight') {
        e.preventDefault();
        seekBy(5);
      } else if (e.key === 'ArrowLeft') {
        e.preventDefault();
        seekBy(-5);
      } else if (e.key === ' ' || e.code === 'Space') {
        e.preventDefault();
        togglePlay();
      } else if (e.key === 'm' || e.key === 'M') {
        e.preventDefault();
        toggleMute();
      } else if (e.key === 'Escape') {
        e.preventDefault();
        if (isPhotoLightboxOpen) {
          setIsPhotoLightboxOpen(false);
        } else {
          onClose();
        }
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, isPlaying, isMuted, duration, activeMediaMode, propertyImages.length, isPhotoLightboxOpen]);

  if (!isOpen || !property) return null;

  const handleTimeUpdate = () => {
    if (videoRef.current) {
      const current = videoRef.current.currentTime;
      const total = videoRef.current.duration;
      setCurrentTime(current);
      if (total && total > 0) {
        setDuration(total);
        setProgress((current / total) * 100);
      }
    }
  };

  const handleLoadedMetadata = () => {
    if (videoRef.current && videoRef.current.duration) {
      setDuration(videoRef.current.duration);
    }
  };

  const toggleFullscreen = () => {
    if (activeMediaMode === 'PHOTOS') {
      setIsPhotoLightboxOpen(true);
      return;
    }
    if (videoRef.current) {
      if (videoRef.current.requestFullscreen) {
        videoRef.current.requestFullscreen();
      }
    }
  };

  return (
    <>
      <AnimatePresence>
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          onClick={onClose}
          className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-slate-950/80 p-2 backdrop-blur-lg sm:p-4 lg:items-center lg:p-6"
        >
          <motion.div
            initial={{ opacity: 0, scale: 0.94, y: 20 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.94, y: 20 }}
            transition={{ type: 'spring', stiffness: 420, damping: 28 }}
            onClick={(e) => e.stopPropagation()}
            className="relative my-auto flex w-full max-w-5xl flex-col overflow-hidden rounded-3xl border border-slate-800 bg-slate-900 text-white shadow-2xl select-none lg:max-h-[calc(100dvh-3rem)] lg:flex-row"
          >
            
            {/* Close Button */}
            <button
              onClick={onClose}
              className="absolute top-4 right-4 z-30 w-10 h-10 rounded-full bg-slate-950/80 hover:bg-rose-600 text-white flex items-center justify-center backdrop-blur-md border border-white/20 transition-all shadow-lg"
            >
              <X className="w-5 h-5 text-white" />
            </button>

            {/* MAIN SHOWCASE VIEWPORT (VIDEO OR HIGH-RES PHOTO GALLERY) */}
            <div className="relative flex min-h-[280px] flex-col justify-center overflow-hidden bg-black group sm:min-h-[420px] lg:w-7/12 lg:min-h-[440px]">
              
              {/* TOP DUAL MEDIA MODE SWITCHER TABS */}
              <div className="absolute left-2 right-14 top-2 z-30 flex items-center gap-1.5 overflow-x-auto rounded-2xl border border-slate-800 bg-slate-950/85 p-1 shadow-lg backdrop-blur-md sm:left-4 sm:right-auto sm:top-4">
                <button
                  onClick={() => setActiveMediaMode('VIDEO')}
                  className={`px-3 py-1.5 rounded-xl text-[11px] font-extrabold flex items-center gap-1.5 transition-all ${
                    activeMediaMode === 'VIDEO'
                      ? 'bg-emerald-600 text-white shadow-md shadow-emerald-600/30'
                      : 'text-slate-400 hover:text-white'
                  }`}
                >
                  <VideoIcon className="w-3.5 h-3.5" />
                  <span>HD Video</span>
                </button>

                <button
                  onClick={() => setActiveMediaMode('PHOTOS')}
                  className={`px-3 py-1.5 rounded-xl text-[11px] font-extrabold flex items-center gap-1.5 transition-all ${
                    activeMediaMode === 'PHOTOS'
                      ? 'bg-emerald-600 text-white shadow-md shadow-emerald-600/30'
                      : 'text-slate-400 hover:text-white'
                  }`}
                >
                  <Camera className="w-3.5 h-3.5" />
                  <span>HD Photos ({propertyImages.length})</span>
                </button>
              </div>

              {/* Seeking Feedback Badge Overlay */}
              <AnimatePresence>
                {seekFeedback && activeMediaMode === 'VIDEO' && (
                  <motion.div
                    initial={{ opacity: 0, scale: 0.7 }}
                    animate={{ opacity: 1, scale: 1 }}
                    exit={{ opacity: 0, scale: 0.7 }}
                    className="absolute inset-0 z-30 pointer-events-none flex items-center justify-center"
                  >
                    <div className="bg-slate-950/90 text-emerald-400 font-mono font-black text-sm sm:text-base px-5 py-2.5 rounded-full border border-emerald-500/50 shadow-2xl backdrop-blur-md animate-bounce">
                      {seekFeedback}
                    </div>
                  </motion.div>
                )}
              </AnimatePresence>

              {/* MEDIA DISPLAY AREA */}
              {activeMediaMode === 'PHOTOS' ? (
                <div 
                  onClick={() => setIsPhotoLightboxOpen(true)}
                  className="relative flex min-h-[280px] h-full w-full cursor-pointer items-center justify-center bg-slate-950 group/photo sm:min-h-[420px] lg:min-h-[440px]"
                >
                  <AnimatePresence mode="wait">
                    <motion.img
                      key={activePhotoIdx}
                      initial={{ opacity: 0, scale: 0.96 }}
                      animate={{ opacity: 1, scale: 1 }}
                      exit={{ opacity: 0, scale: 0.96 }}
                      transition={{ duration: 0.25 }}
                      src={propertyImages[activePhotoIdx] || propertyImages[0]}
                      alt={`${property.title} Photo ${activePhotoIdx + 1}`}
                      className="w-full h-full object-cover max-h-[520px] group-hover/photo:scale-103 transition-transform duration-500"
                    />
                  </AnimatePresence>

                  {/* Hover Click to Expand Hint Badge */}
                  <div className="absolute inset-0 bg-slate-950/20 opacity-0 group-hover/photo:opacity-100 transition-opacity flex items-center justify-center pointer-events-none">
                    <span className="bg-slate-950/90 text-white text-xs font-mono font-bold px-4 py-2 rounded-full border border-emerald-500/40 backdrop-blur-md flex items-center gap-2 shadow-2xl">
                      <Maximize className="w-4 h-4 text-emerald-400" />
                      <span>Click to Open HD Fullscreen Window</span>
                    </span>
                  </div>

                  {/* Photo Carousel Left/Right Arrow Controls */}
                  {propertyImages.length > 1 && (
                    <>
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          setActivePhotoIdx((prev) => (prev - 1 + propertyImages.length) % propertyImages.length);
                        }}
                        className="absolute left-3 top-1/2 -translate-y-1/2 w-10 h-10 rounded-full bg-slate-950/80 hover:bg-emerald-600 text-white flex items-center justify-center backdrop-blur-md border border-white/20 transition-all z-20 shadow-lg"
                      >
                        <ChevronLeft className="w-6 h-6" />
                      </button>

                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          setActivePhotoIdx((prev) => (prev + 1) % propertyImages.length);
                        }}
                        className="absolute right-3 top-1/2 -translate-y-1/2 w-10 h-10 rounded-full bg-slate-950/80 hover:bg-emerald-600 text-white flex items-center justify-center backdrop-blur-md border border-white/20 transition-all z-20 shadow-lg"
                      >
                        <ChevronRight className="w-6 h-6" />
                      </button>
                    </>
                  )}

                  {/* Photo Counter Badge */}
                  <div className="absolute top-4 right-16 z-20 bg-slate-950/80 text-emerald-300 text-xs font-mono font-bold px-3 py-1 rounded-full border border-emerald-500/30 backdrop-blur-md">
                    Photo {activePhotoIdx + 1} of {propertyImages.length}
                  </div>
                </div>
              ) : (
                videoError ? (
                  <img
                    src={propertyImages[0]}
                    alt={property.title}
                    className="w-full h-full object-cover max-h-[520px]"
                  />
                ) : (
                  <video
                    ref={videoRef}
                    src={videoUrl}
                    autoPlay
                    playsInline
                    loop
                    muted={isMuted}
                    onError={() => setVideoError(true)}
                    onLoadedMetadata={handleLoadedMetadata}
                    onTimeUpdate={handleTimeUpdate}
                    onClick={togglePlay}
                    className="w-full h-full object-cover cursor-pointer max-h-[520px]"
                  />
                )
              )}

              {/* Custom Overlay Controls Bar + Interactive Filmstrip Reel */}
              <div className="absolute bottom-0 left-0 right-0 p-3 sm:p-4 bg-gradient-to-t from-slate-950 via-slate-950/85 to-transparent flex flex-col gap-2 z-20">
                
                {/* INTERACTIVE FILMSTRIP REEL (THUMBNAILS FOR VIDEO + HD PHOTOS) */}
                <div className="flex items-center gap-2 overflow-x-auto no-scrollbar py-1">
                  {/* VIDEO THUMBNAIL TILE */}
                  <button
                    onClick={() => setActiveMediaMode('VIDEO')}
                    className={`relative w-14 h-10 rounded-xl overflow-hidden shrink-0 border-2 transition-all ${
                      activeMediaMode === 'VIDEO' ? 'border-emerald-400 ring-2 ring-emerald-400/40 scale-105' : 'border-slate-700 opacity-70 hover:opacity-100'
                    }`}
                  >
                    <img src={propertyImages[0]} alt="Video Thumbnail" className="w-full h-full object-cover" />
                    <div className="absolute inset-0 bg-slate-950/50 flex items-center justify-center">
                      <Play className="w-4 h-4 text-emerald-400 fill-emerald-400" />
                    </div>
                  </button>

                  {/* PHOTO THUMBNAIL TILES */}
                  {propertyImages.map((img, idx) => (
                    <button
                      key={idx}
                      onClick={() => {
                        setActiveMediaMode('PHOTOS');
                        setActivePhotoIdx(idx);
                      }}
                      className={`relative w-14 h-10 rounded-xl overflow-hidden shrink-0 border-2 transition-all ${
                        activeMediaMode === 'PHOTOS' && activePhotoIdx === idx
                          ? 'border-emerald-400 ring-2 ring-emerald-400/40 scale-105'
                          : 'border-slate-700 opacity-70 hover:opacity-100'
                      }`}
                    >
                      <img src={img} alt={`Photo ${idx + 1}`} className="w-full h-full object-cover" />
                    </button>
                  ))}
                </div>

                {/* VIDEO SCRUBBER BAR (Only shown in VIDEO mode) */}
                {activeMediaMode === 'VIDEO' && (
                  <div className="space-y-1.5">
                    <div 
                      className="w-full h-2 bg-slate-800 rounded-full cursor-pointer overflow-hidden relative group/scrubber"
                      onClick={(e) => {
                        const rect = e.currentTarget.getBoundingClientRect();
                        const clickX = e.clientX - rect.left;
                        const newPercent = clickX / rect.width;
                        if (videoRef.current && videoRef.current.duration) {
                          const newTime = newPercent * videoRef.current.duration;
                          videoRef.current.currentTime = newTime;
                          setCurrentTime(newTime);
                          setProgress(newPercent * 100);
                        }
                      }}
                    >
                      <div 
                        className="h-full bg-gradient-to-r from-emerald-500 to-teal-400 rounded-full transition-all duration-100 shadow-[0_0_12px_rgba(16,185,129,0.8)]"
                        style={{ width: `${progress}%` }}
                      />
                    </div>
                  </div>
                )}

                {/* Action Control Buttons */}
                <div className="flex items-center justify-between gap-2 overflow-x-auto pt-1">
                  {activeMediaMode === 'VIDEO' ? (
                    <div className="flex shrink-0 items-center gap-2 sm:gap-3">
                      <button
                        onClick={togglePlay}
                        className="w-9 h-9 rounded-xl bg-white/15 hover:bg-emerald-600 text-white flex items-center justify-center backdrop-blur-md transition-all active:scale-95"
                        title={isPlaying ? "Pause (Space)" : "Play (Space)"}
                      >
                        {isPlaying ? <Pause className="w-4 h-4" /> : <Play className="w-4 h-4 fill-white" />}
                      </button>

                      <button
                        onClick={() => seekBy(-5)}
                        className="w-9 h-9 rounded-xl bg-white/15 hover:bg-slate-700 text-white flex items-center justify-center backdrop-blur-md transition-all active:scale-95 text-xs font-mono font-bold"
                        title="Rewind 5s (Left Arrow)"
                      >
                        <RotateCcw className="w-3.5 h-3.5" />
                      </button>

                      <button
                        onClick={() => seekBy(5)}
                        className="w-9 h-9 rounded-xl bg-white/15 hover:bg-slate-700 text-white flex items-center justify-center backdrop-blur-md transition-all active:scale-95 text-xs font-mono font-bold"
                        title="Forward 5s (Right Arrow)"
                      >
                        <RotateCw className="w-3.5 h-3.5" />
                      </button>

                      <button
                        onClick={toggleMute}
                        className="w-9 h-9 rounded-xl bg-white/15 hover:bg-slate-700 text-white flex items-center justify-center backdrop-blur-md transition-all active:scale-95"
                        title={isMuted ? "Unmute (M)" : "Mute (M)"}
                      >
                        {isMuted ? <VolumeX className="w-4 h-4 text-rose-400" /> : <Volume2 className="w-4 h-4 text-emerald-400" />}
                      </button>

                      <div className="flex items-center gap-1.5 bg-slate-950/90 border border-slate-700/80 px-3 py-1.5 rounded-xl font-mono text-xs font-black text-slate-200 backdrop-blur-md">
                        <span className="text-emerald-400">{formatTime(currentTime)}</span>
                        <span className="text-slate-500">/</span>
                        <span className="text-slate-300">{formatTime(duration)}</span>
                      </div>
                    </div>
                  ) : (
                    <div className="flex items-center gap-2">
                      <span className="text-xs font-mono font-extrabold text-emerald-400 bg-slate-950/90 px-3 py-1.5 rounded-xl border border-slate-700">
                        Photo {activePhotoIdx + 1} of {propertyImages.length}
                      </span>
                      <button
                        onClick={() => setIsPhotoLightboxOpen(true)}
                        className="bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-extrabold px-3.5 py-1.5 rounded-xl flex items-center gap-1.5 transition-colors shadow-md"
                      >
                        <Maximize className="w-3.5 h-3.5 text-white" />
                        <span>Open Fullscreen Window</span>
                      </button>
                    </div>
                  )}

                  <div className="flex shrink-0 items-center gap-2">
                    <button
                      onClick={toggleFullscreen}
                      className="w-9 h-9 rounded-xl bg-white/15 hover:bg-slate-700 text-white flex items-center justify-center backdrop-blur-md transition-all active:scale-95"
                      title={activeMediaMode === 'PHOTOS' ? "Open Photo Lightbox Window" : "Full Screen Video"}
                    >
                      <Maximize className="w-4 h-4 text-slate-300" />
                    </button>
                  </div>
                </div>

              </div>

            </div>

            {/* PROPERTY DETAILS SIDEBAR IN MODAL */}
            <div className="flex flex-col justify-between space-y-6 border-t border-slate-800 bg-slate-900 p-5 sm:p-8 lg:w-5/12 lg:overflow-y-auto lg:border-l lg:border-t-0">
              
              <div className="space-y-4">
                <div className="flex items-center gap-2">
                  <span className="text-[10px] font-extrabold text-emerald-400 uppercase tracking-wider bg-emerald-500/10 px-3 py-1 rounded-full border border-emerald-500/30 font-mono flex items-center gap-1">
                    <ShieldCheck className="w-3.5 h-3.5 text-emerald-400" /> 100% On-Site Escort Vetted
                  </span>
                </div>

                <h3 className="text-2xl font-black font-['Outfit',sans-serif] text-white leading-tight">
                  {property.title}
                </h3>

                <div className="flex items-center gap-2 text-xs text-slate-300 font-medium">
                  <MapPin className="w-4 h-4 text-emerald-400 shrink-0" />
                  <span>{property.sector}, Indore • {property.totalAreaSqFt} sq ft</span>
                </div>

                {/* Pricing Box */}
                <div className="bg-slate-950 p-4 rounded-2xl border border-slate-800 flex items-center justify-between">
                  <div>
                    <span className="text-[10px] text-slate-400 font-bold uppercase tracking-wider block">
                      {property.listingType === 'SALE' ? 'Asking Price' : 'Monthly Rent'}
                    </span>
                    <span className="text-2xl font-black text-white font-mono">
                      ₹{property.monthlyRent ? property.monthlyRent.toLocaleString('en-IN') : property.askingPrice?.toLocaleString('en-IN')}
                      {property.monthlyRent > 0 && <span className="text-xs font-normal text-slate-400">/mo</span>}
                    </span>
                  </div>

                  {property.securityDeposit ? (
                    <div className="text-right">
                      <span className="text-[10px] text-slate-400 font-bold uppercase tracking-wider block">Security Deposit</span>
                      <span className="text-xs font-extrabold text-emerald-400 bg-emerald-500/10 px-2.5 py-1 rounded-xl border border-emerald-500/30 font-mono inline-block">
                        ₹{property.securityDeposit.toLocaleString('en-IN')}
                      </span>
                    </div>
                  ) : null}
                </div>

                {/* Owner Contact */}
                <div className="flex items-center justify-between text-xs text-slate-400 bg-slate-950 px-3.5 py-2.5 rounded-xl border border-slate-800">
                  <span className="flex items-center gap-1.5 font-medium">
                    <Lock className="w-3.5 h-3.5 text-emerald-400" /> Direct Owner Phone
                  </span>
                  <span className="font-mono text-white font-extrabold">{property.ownerPhone}</span>
                </div>
              </div>

              {/* CTA Button */}
              <div className="pt-2">
                <motion.button
                  whileHover={{ scale: 1.03, y: -2 }}
                  whileTap={{ scale: 0.96 }}
                  onClick={() => {
                    onClose();
                    onBookTour(property);
                  }}
                  className="w-full bg-emerald-600 hover:bg-emerald-500 text-white font-extrabold text-xs py-4 rounded-2xl shadow-xl shadow-emerald-600/30 flex items-center justify-center gap-2.5 uppercase tracking-wider shimmer-glow"
                >
                  <Key className="w-4 h-4 text-white" />
                  <span>Book Escorted Tour Pass</span>
                </motion.button>
              </div>

            </div>

          </motion.div>
        </motion.div>
      </AnimatePresence>

      {/* FULL-SCREEN HD PHOTO LIGHTBOX PREVIEW WINDOW */}
      <AnimatePresence>
        {isPhotoLightboxOpen && (
          <motion.div
            initial={{ opacity: 0, scale: 0.95 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={{ opacity: 0, scale: 0.95 }}
            transition={{ duration: 0.2 }}
            onClick={() => setIsPhotoLightboxOpen(false)}
            className="fixed inset-0 z-[100] bg-slate-950/95 backdrop-blur-2xl flex flex-col justify-between p-4 sm:p-8 select-none"
          >
            {/* Lightbox Header Bar */}
            <div className="flex items-center justify-between text-white z-10" onClick={(e) => e.stopPropagation()}>
              <div className="flex items-center gap-3">
                <span className="text-xs font-mono font-bold bg-emerald-500/20 text-emerald-300 border border-emerald-500/40 px-3 py-1 rounded-full">
                  HD Photo Lightbox • Photo {activePhotoIdx + 1} of {propertyImages.length}
                </span>
                <h4 className="text-sm font-extrabold font-['Outfit'] text-slate-200 hidden sm:inline">{property.title}</h4>
              </div>

              <button
                onClick={() => setIsPhotoLightboxOpen(false)}
                className="w-11 h-11 rounded-full bg-white/10 hover:bg-rose-600 text-white flex items-center justify-center backdrop-blur-md border border-white/20 transition-all shadow-xl"
              >
                <X className="w-6 h-6 text-white" />
              </button>
            </div>

            {/* Main Full-Size Image Container */}
            <div className="relative flex-1 flex items-center justify-center my-4 overflow-hidden" onClick={(e) => e.stopPropagation()}>
              <motion.img
                key={activePhotoIdx}
                initial={{ opacity: 0, scale: 0.92 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.92 }}
                transition={{ duration: 0.2 }}
                src={propertyImages[activePhotoIdx] || propertyImages[0]}
                alt={`${property.title} Fullscreen Photo ${activePhotoIdx + 1}`}
                className="max-w-full max-h-[78vh] object-contain rounded-2xl shadow-2xl border border-slate-800"
              />

              {/* ENTERPRISE METADATA OVERLAY BADGES (LOCATION, PRICE, VASTU, ROOM TAG & VERIFIED SEAL) */}
              <div className="absolute top-4 left-4 z-20 flex flex-wrap gap-2 max-w-xl pointer-events-none">
                {/* ACTIVE PHOTO ROOM TAG BADGE */}
                {(() => {
                  const tagKey = property?.taggedMedia?.[activePhotoIdx]?.roomTag || ['LIVING_ROOM', 'MASTER_BEDROOM', 'KITCHEN', 'BATHROOM', 'BALCONY', 'BEDROOM', 'EXTERIOR', 'AMENITIES', 'FLOOR_PLAN'][activePhotoIdx % 9];
                  const tagConfig: Record<string, { label: string; emoji: string }> = {
                    GENERAL: { label: 'Property Photo', emoji: '📸' },
                    LIVING_ROOM: { label: 'Living Room', emoji: '🛋️' },
                    MASTER_BEDROOM: { label: 'Master Bedroom', emoji: '🛏️' },
                    BEDROOM: { label: 'Guest Bedroom', emoji: '🛏️' },
                    KITCHEN: { label: 'Modular Kitchen', emoji: '🍳' },
                    BATHROOM: { label: 'Bathroom', emoji: '🚿' },
                    BALCONY: { label: 'Balcony & View', emoji: '🌅' },
                    EXTERIOR: { label: 'Building Exterior', emoji: '🏢' },
                    AMENITIES: { label: 'Society Amenities', emoji: '🏊' },
                    FLOOR_PLAN: { label: 'Floor Plan', emoji: '📐' }
                  };
                  const tagInfo = tagConfig[tagKey] || tagConfig.GENERAL;
                  if (tagKey === 'GENERAL') return null;
                  return (
                    <span className="bg-gradient-to-r from-emerald-600 to-teal-600 text-white text-xs font-black px-3.5 py-1.5 rounded-xl border border-emerald-300/50 backdrop-blur-md shadow-xl flex items-center gap-1.5 animate-pulse">
                      <span>{tagInfo.emoji} {tagInfo.label}</span>
                    </span>
                  );
                })()}

                <span className="bg-slate-950/90 text-white text-xs font-black px-3.5 py-1.5 rounded-xl border border-slate-700 backdrop-blur-md shadow-xl flex items-center gap-1.5">
                  <MapPin className="w-3.5 h-3.5 text-emerald-400" />
                  <span>{property.sector ? `${property.sector}, Indore` : (property.city || 'Indore')}</span>
                </span>

                {property.monthlyRent ? (
                  <span className="bg-slate-950/90 text-emerald-400 font-mono text-xs font-black px-3.5 py-1.5 rounded-xl border border-emerald-500/40 backdrop-blur-md shadow-xl">
                    🏷️ ₹{property.monthlyRent.toLocaleString('en-IN')} / mo
                  </span>
                ) : null}

                {property.vastuFacing && property.vastuFacing !== 'Not Specified' && (
                  <span className="bg-slate-950/90 text-amber-300 text-xs font-bold px-3.5 py-1.5 rounded-xl border border-amber-500/40 backdrop-blur-md shadow-xl">
                    🧭 {property.vastuFacing}
                  </span>
                )}

                <span className="bg-emerald-950/90 text-emerald-300 text-xs font-extrabold px-3.5 py-1.5 rounded-xl border border-emerald-500/50 backdrop-blur-md shadow-xl flex items-center gap-1.5">
                  <ShieldCheck className="w-4 h-4 text-emerald-400" />
                  <span>On-Site Escort Vetted</span>
                </span>
              </div>

              {propertyImages.length > 1 && (
                <>
                  <button
                    onClick={() => setActivePhotoIdx((prev) => (prev - 1 + propertyImages.length) % propertyImages.length)}
                    className="absolute left-4 top-1/2 -translate-y-1/2 w-12 h-12 rounded-full bg-slate-900/90 hover:bg-emerald-600 text-white flex items-center justify-center border border-white/20 shadow-2xl transition-all"
                  >
                    <ChevronLeft className="w-7 h-7" />
                  </button>
                  <button
                    onClick={() => setActivePhotoIdx((prev) => (prev + 1) % propertyImages.length)}
                    className="absolute right-4 top-1/2 -translate-y-1/2 w-12 h-12 rounded-full bg-slate-900/90 hover:bg-emerald-600 text-white flex items-center justify-center border border-white/20 shadow-2xl transition-all"
                  >
                    <ChevronRight className="w-7 h-7" />
                  </button>
                </>
              )}
            </div>

            {/* Bottom Filmstrip Controls with Room Badges */}
            <div className="flex items-center justify-center gap-2 z-10 overflow-x-auto max-w-full p-2" onClick={(e) => e.stopPropagation()}>
              {propertyImages.map((img, idx) => {
                const tagKey = property?.taggedMedia?.[idx]?.roomTag || ['LIVING_ROOM', 'MASTER_BEDROOM', 'KITCHEN', 'BATHROOM', 'BALCONY', 'BEDROOM', 'EXTERIOR', 'AMENITIES', 'FLOOR_PLAN'][idx % 9];
                const tagEmojis: Record<string, string> = {
                  LIVING_ROOM: '🛋️',
                  MASTER_BEDROOM: '🛏️',
                  BEDROOM: '🛏️',
                  KITCHEN: '🍳',
                  BATHROOM: '🚿',
                  BALCONY: '🌅',
                  EXTERIOR: '🏢',
                  AMENITIES: '🏊',
                  FLOOR_PLAN: '📐'
                };
                const emoji = tagEmojis[tagKey];

                return (
                  <button
                    key={idx}
                    onClick={() => setActivePhotoIdx(idx)}
                    className={`relative w-16 h-12 rounded-xl overflow-hidden border-2 transition-all group shrink-0 ${
                      activePhotoIdx === idx ? 'border-emerald-400 ring-4 ring-emerald-400/30 scale-110' : 'border-slate-700 opacity-70 hover:opacity-100'
                    }`}
                  >
                    <img src={img} alt={`Thumbnail ${idx + 1}`} className="w-full h-full object-cover" />
                    {emoji && (
                      <span className="absolute bottom-0.5 right-0.5 bg-slate-950/90 text-[10px] px-1 rounded backdrop-blur-xs shadow-xs">
                        {emoji}
                      </span>
                    )}
                  </button>
                );
              })}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </>
  );
};

