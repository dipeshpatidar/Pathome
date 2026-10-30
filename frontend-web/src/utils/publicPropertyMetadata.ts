import type { Property } from '../types';
import { publicPropertyCoverImage } from './operationsLeadPresentation.ts';

export const GENERIC_PUBLIC_TITLE = 'Pathome | Your Dreams, Our Efforts';
const METADATA_ATTRIBUTE = 'data-pathome-public-metadata';

export interface PublicPropertyMetadata {
  title: string;
  description: string;
  canonicalUrl: string;
  imageUrl: string | null;
}

const money = (value: unknown): string | null => {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) return null;
  return `₹${new Intl.NumberFormat('en-IN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 2
  }).format(value)}`;
};

const area = (value: unknown): string | null => {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) return null;
  return `${new Intl.NumberFormat('en-IN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 2
  }).format(value)} sq ft`;
};

export const buildPublicPropertyMetadata = (
  property: Property,
  origin: string
): PublicPropertyMetadata => {
  const location = [property.sector, property.city].filter(Boolean).join(', ');
  const rent = money(property.monthlyRent);
  const propertyArea = area(property.totalAreaSqFt);
  const facts = [
    location ? `Property in ${location}` : 'Public Pathome property',
    rent ? `Rent ${rent} per month` : null,
    property.bhk || null,
    propertyArea
  ].filter(Boolean);
  const canonicalOrigin = origin.replace(/\/+$/, '');
  return {
    title: `${property.title} | Pathome`,
    description: facts.join('. '),
    canonicalUrl: `${canonicalOrigin}/property/${property.id}`,
    imageUrl: publicPropertyCoverImage(property)
  };
};

const removeSyncedMetadata = (document: Document) => {
  document.head.querySelectorAll(`[${METADATA_ATTRIBUTE}]`).forEach((element) => element.remove());
};

const appendMeta = (
  document: Document,
  attribute: 'name' | 'property',
  key: string,
  content: string
) => {
  const element = document.createElement('meta');
  element.setAttribute(attribute, key);
  element.setAttribute('content', content);
  element.setAttribute(METADATA_ATTRIBUTE, 'true');
  document.head.appendChild(element);
};

export const syncPublicPropertyMetadata = (
  property: Property,
  document: Document = window.document
) => {
  const metadata = buildPublicPropertyMetadata(property, window.location.origin);
  removeSyncedMetadata(document);
  document.title = metadata.title;
  appendMeta(document, 'name', 'description', metadata.description);
  appendMeta(document, 'property', 'og:title', property.title);
  appendMeta(document, 'property', 'og:description', metadata.description);
  appendMeta(document, 'property', 'og:url', metadata.canonicalUrl);
  if (metadata.imageUrl) appendMeta(document, 'property', 'og:image', metadata.imageUrl);
  appendMeta(document, 'property', 'og:type', 'website');
  appendMeta(document, 'property', 'og:site_name', 'Pathome');

  const canonical = document.createElement('link');
  canonical.setAttribute('rel', 'canonical');
  canonical.setAttribute('href', metadata.canonicalUrl);
  canonical.setAttribute(METADATA_ATTRIBUTE, 'true');
  document.head.appendChild(canonical);
};

export const resetPublicPropertyMetadata = (document: Document = window.document) => {
  removeSyncedMetadata(document);
  document.title = GENERIC_PUBLIC_TITLE;
};
