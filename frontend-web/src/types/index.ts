export type UserRole = 'GUEST' | 'TENANT' | 'EMPLOYEE' | 'SUB_ADMIN' | 'SUPER_ADMIN' | 'ADMIN';

export type RoomTag = 
  | 'GENERAL'
  | 'LIVING_ROOM' 
  | 'MASTER_BEDROOM'
  | 'BEDROOM' 
  | 'KITCHEN' 
  | 'BATHROOM'
  | 'BALCONY' 
  | 'EXTERIOR' 
  | 'AMENITIES' 
  | 'FLOOR_PLAN';

export interface RoomTagOption {
  value: RoomTag;
  label: string;
}

export const ROOM_TAG_OPTIONS: RoomTagOption[] = [
  { value: 'GENERAL', label: '🌐 General' },
  { value: 'LIVING_ROOM', label: '🛋️ Living' },
  { value: 'MASTER_BEDROOM', label: '🛏️ Master' },
  { value: 'BEDROOM', label: '🛏️ Bed' },
  { value: 'KITCHEN', label: '🍳 Kitchen' },
  { value: 'BATHROOM', label: '🚿 Bath' },
  { value: 'BALCONY', label: '🌅 Balcony' },
  { value: 'EXTERIOR', label: '🏢 Exterior' },
  { value: 'AMENITIES', label: '🏊 Amenities' },
  { value: 'FLOOR_PLAN', label: '📐 Plan' },
];

export const DEFAULT_SMART_TAG_SEQUENCE: RoomTag[] = [
  'LIVING_ROOM',
  'BEDROOM',
  'KITCHEN',
  'BATHROOM',
  'BALCONY',
  'EXTERIOR',
  'GENERAL'
];

export interface PropertyMediaAsset {
  id?: number;
  listingId: number;
  mediaUrl: string;
  cloudinaryPublicId?: string;
  mediaType: 'IMAGE' | 'VIDEO_WALKTHROUGH' | 'PANORAMA_360' | 'FLOOR_PLAN';
  roomTag: RoomTag;
  caption?: string;
  isPrimaryCover?: boolean;
  sector?: string;
  city?: string;
  priceTag?: string;
  latitude?: number;
  longitude?: number;
  vastuFacing?: string;
  verificationStatus?: string;
  uploadedAt?: string;
}

export interface UserProfile {
  id: number;
  email: string;
  fullName: string;
  role: UserRole;
  freeVisitsUsed: number;
  walletBalance: number;
  avatarUrl?: string;
  department?: string;
  permissions?: string[];
}

export interface BhkConfig {
  id: string;
  label: string;
  enabled: boolean;
  demandScore: string;
  avgRent: string;
}

export interface Property {
  id: number;
  title: string;
  listingType: 'RENT' | 'SALE';
  propertyType: 'FLAT' | 'HOUSE' | 'PLOT' | 'LAND';
  city?: string;
  sector: string;
  bhk?: '1RK' | '1BHK' | '2BHK' | '3BHK' | '4BHK' | string;
  monthlyRent: number;
  securityDeposit: number;
  askingPrice?: number;
  totalAreaSqFt: number;
  images: string[];
  taggedMedia?: PropertyMediaAsset[];
  videoUrl?: string;
  vastuScore?: number;
  vastuFacing?: string;
  verified: boolean;
  ownerPhone: string;
  latitude: number;
  longitude: number;
  bachelorAllowed?: boolean;
}
