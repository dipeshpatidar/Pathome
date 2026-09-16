import React, { useState, useRef, useEffect } from 'react';
import { Property } from '../types';
import { ShieldCheck, ChevronLeft, ChevronRight, Camera } from 'lucide-react';
import { useNotification } from '../context/NotificationContext';

interface PropertyCardMediaProps {
  property: Property;
  onOpenVideoModal: (property: Property, initialMode?: 'VIDEO' | 'PHOTOS') => void;
  heightClass?: string;
}

const LOCAL_VIDEOS = [
  "/assets/videos/property_walkthrough_1.mp4",
  "/assets/videos/property_walkthrough_2.mp4",
  "/assets/videos/property_walkthrough_3.mp4",
  "/assets/videos/property_walkthrough_4.mp4"
];

export const PropertyCardMedia: React.FC<PropertyCardMediaProps> = ({
  property,
  onOpenVideoModal,
  heightClass = "h-64 sm:h-80 lg:h-[380px]"
}) => {
  const { notifySuccess } = useNotification();
  const [isHovered, setIsHovered] = useState<boolean>(false);
  const [isVideoPlaying, setIsVideoPlaying] = useState<boolean>(false);
  const [currentImgIdx, setCurrentImgIdx] = useState<number>(0);
  const videoRef = useRef<HTMLVideoElement>(null);

  const videoUrl = property.videoUrl || LOCAL_VIDEOS[(property.id || 0) % LOCAL_VIDEOS.length];

  // EXPLICIT PROGRAMMATIC AUTO-PLAY ON HOVER WITH MUTED ENFORCEMENT
  useEffect(() => {
    const videoEl = videoRef.current;
    if (!videoEl) return;

    if (isHovered) {
      videoEl.muted = true;
      videoEl.currentTime = 0;
      const playPromise = videoEl.play();
      if (playPromise !== undefined) {
        playPromise
          .then(() => {
            setIsVideoPlaying(true);
          })
          .catch((err) => {
            console.warn("Auto-play on hover suppressed:", err);
            setIsVideoPlaying(false);
          });
      }
    } else {
      videoEl.pause();
      setIsVideoPlaying(false);
    }
  }, [isHovered]);

  const handleNextImage = (e: React.MouseEvent) => {
    e.stopPropagation();
    setCurrentImgIdx((prev) => (prev + 1) % property.images.length);
  };

  const handlePrevImage = (e: React.MouseEvent) => {
    e.stopPropagation();
    setCurrentImgIdx((prev) => (prev - 1 + property.images.length) % property.images.length);
  };

  return (
    <div
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
      onClick={() => onOpenVideoModal(property, 'VIDEO')}
      className={`relative ${heightClass} rounded-3xl overflow-hidden shadow-xl shadow-slate-900/10 bg-slate-900 border border-slate-200/90 group-hover:shadow-2xl transition-all duration-500 cursor-pointer`}
    >
      {/* STATIC PHOTO BACKDROP - ALWAYS SHOWN AS BASE, FADES OUT ONLY WHEN VIDEO IS ACTIVELY PLAYING */}
      <div className={`absolute inset-0 z-0 transition-opacity duration-300 ${isVideoPlaying ? 'opacity-0' : 'opacity-100'}`}>
        <img
          src={property.images[currentImgIdx] || property.images[0]}
          alt={property.title}
          className="w-full h-full object-cover group-hover:scale-106 transition-transform duration-700 ease-out"
        />
      </div>

      {/* ALWAYS MOUNTED VIDEO CONTAINER - PRELOADED AND SHOWN WHEN PLAYING */}
      <div className={`absolute inset-0 z-0 bg-black transition-opacity duration-300 ${isVideoPlaying ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}>
        <video
          ref={videoRef}
          src={videoUrl}
          loop
          muted
          playsInline
          preload="auto"
          onPlaying={() => setIsVideoPlaying(true)}
          className="w-full h-full object-cover scale-105"
        />
      </div>

      {/* Dark Overlay Gradient */}
      <div className="absolute inset-0 bg-gradient-to-t from-slate-950/85 via-transparent to-slate-950/40 pointer-events-none z-10" />

      {/* Badges Bar (When not hovering video) */}
      {!isHovered && (
        <div className="absolute top-4 left-4 right-4 flex items-center justify-between z-20">
          <div className="flex items-center gap-1.5 flex-wrap">
            <span className="bg-slate-950/90 text-emerald-300 text-[10px] font-extrabold px-3 py-1.5 rounded-full border border-emerald-500/40 flex items-center gap-1.5 backdrop-blur-md shadow-lg font-mono">
              <ShieldCheck className="w-3.5 h-3.5 text-emerald-400" /> Escort Verified
            </span>

            {/* Direct HD Photos Lightbox Button */}
            <button
              onClick={(e) => {
                e.stopPropagation();
                onOpenVideoModal(property, 'PHOTOS');
              }}
              className="bg-slate-900/90 hover:bg-emerald-600 text-white text-[10px] font-extrabold px-3 py-1.5 rounded-full border border-white/20 flex items-center gap-1 backdrop-blur-md shadow-md transition-colors"
              title="Open HD Photos Gallery Window"
            >
              <Camera className="w-3.5 h-3.5 text-emerald-400" />
              <span>{property.images.length} HD Photos</span>
            </button>
          </div>

          <button
            onClick={(e) => { e.stopPropagation(); notifySuccess('Saved to bookmarks', `${property.title} is available in your saved properties.`); }}
            className="w-9 h-9 rounded-full bg-slate-900/80 hover:bg-emerald-600 text-white flex items-center justify-center backdrop-blur-md border border-white/25 shadow-lg transition-all text-xs shrink-0"
          >
            ★
          </button>
        </div>
      )}

      {/* Carousel Navigation Arrows */}
      {property.images.length > 1 && !isHovered && (
        <>
          <button
            onClick={handlePrevImage}
            className="absolute left-3.5 top-1/2 -translate-y-1/2 w-9 h-9 rounded-full bg-white/95 text-slate-900 flex items-center justify-center shadow-xl backdrop-blur-md transition-all opacity-0 group-hover:opacity-100 z-30 hover:bg-white"
          >
            <ChevronLeft className="w-5 h-5" />
          </button>
          <button
            onClick={handleNextImage}
            className="absolute right-3.5 top-1/2 -translate-y-1/2 w-9 h-9 rounded-full bg-white/95 text-slate-900 flex items-center justify-center shadow-xl backdrop-blur-md transition-all opacity-0 group-hover:opacity-100 z-30 hover:bg-white"
          >
            <ChevronRight className="w-5 h-5" />
          </button>
        </>
      )}
    </div>
  );
};
