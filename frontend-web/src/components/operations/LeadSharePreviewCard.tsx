import React, { useEffect, useState } from 'react';
import { ExternalLink, Home, RefreshCw } from 'lucide-react';

import type { Property } from '../../types';
import { propertyService } from '../../services/propertyService';
import {
  formatPublicArea,
  formatPublicRent,
  loadPublicPropertyPreview
} from '../../utils/publicPropertyPreview';
import {
  publicPropertyCoverImage,
  publicPropertyIdFromLink
} from '../../utils/operationsLeadPresentation';

type PreviewState = 'invalid' | 'loading' | 'ready' | 'error';

/**
 * A bounded, read-only view of the first ordered public property in a prepared share.
 * The endpoint is public and the component displays only its allowlisted public projection.
 */
export const LeadSharePreviewCard: React.FC<{ link: string }> = ({ link }) => {
  const propertyId = publicPropertyIdFromLink(link);
  const headingId = `lead-share-preview-${propertyId ?? 'unavailable'}`;
  const [state, setState] = useState<PreviewState>(
    propertyId === null ? 'invalid' : 'loading'
  );
  const [property, setProperty] = useState<Property | null>(null);
  const [retry, setRetry] = useState(0);
  const [imageFailed, setImageFailed] = useState(false);

  useEffect(() => {
    if (propertyId === null) {
      setProperty(null);
      setState('invalid');
      return undefined;
    }
    const controller = new AbortController();
    let active = true;
    setProperty(null);
    setImageFailed(false);
    setState('loading');
    loadPublicPropertyPreview(
      propertyId,
      (id, signal) => propertyService.getPublicProperty(id, signal),
      { externalSignal: controller.signal }
    )
      .then((result) => {
        if (!active || controller.signal.aborted) return;
        setProperty(result);
        setState('ready');
      })
      .catch(() => {
        if (!active || controller.signal.aborted) return;
        setProperty(null);
        setState('error');
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [propertyId, retry]);

  const cover = property ? publicPropertyCoverImage(property) : null;
  const rent = property ? formatPublicRent(property.monthlyRent) : null;
  const location = property
    ? [property.sector, property.city].filter(Boolean).join(', ')
    : '';
  const facts = property
    ? [property.bhk, formatPublicArea(property.totalAreaSqFt)]
        .filter(Boolean)
        .join(' · ')
    : '';
  const imageAlt = property
    ? `Public cover photo of ${property.title}${location ? ` in ${location}` : ''}`
    : '';

  return (
    <section className="ld-card ld-public-preview" aria-labelledby={headingId}>
      <header className="ld-card-head">
        <div>
          <p className="oe-eyebrow">Current public preview</p>
          <h3 id={headingId}>The customer’s first shortlisted home</h3>
        </div>
        <Home size={20} aria-hidden="true" />
      </header>

      {state === 'loading' ? (
        <div className="ld-public-preview-loading" role="status" aria-live="polite">
          <span className="oe-skel" aria-hidden="true" />
          <div>
            <span className="oe-skel" aria-hidden="true" />
            <span className="oe-skel" aria-hidden="true" />
          </div>
          <span className="sr-only">Loading the current public property preview.</span>
        </div>
      ) : state === 'ready' && property ? (
        <>
          <article className="ld-public-preview-body">
            {cover && !imageFailed ? (
              <img
                src={cover}
                alt={imageAlt}
                loading="lazy"
                decoding="async"
                referrerPolicy="no-referrer"
                onError={() => setImageFailed(true)}
              />
            ) : (
              <div
                className="ld-public-preview-fallback"
                role="img"
                aria-label="No public cover photo available for this property"
              >
                <Home size={24} aria-hidden="true" />
                <span>No public cover photo available</span>
              </div>
            )}
            <div className="ld-public-preview-copy">
              <h4>{property.title}</h4>
              {location ? <p className="ld-public-preview-location">{location}</p> : null}
              {facts ? <p>{facts}</p> : null}
              {rent ? (
                <p className="ld-public-preview-rent">
                  <strong>{rent}</strong> / month
                </p>
              ) : null}
              <a
                href={link}
                target="_blank"
                rel="noopener noreferrer"
                className="oe-btn oe-btn--secondary oe-btn--sm"
              >
                Open full property details
                <ExternalLink size={14} aria-hidden="true" />
              </a>
            </div>
          </article>
          <p className="oe-footnote">
            These facts and the genuine cover photo come from the property’s current public page.
            The saved message below remains the exact snapshot you reviewed.
          </p>
        </>
      ) : (
        <div className="ld-public-preview-error" role="status" aria-live="polite">
          <p>
            {state === 'invalid'
              ? 'This saved link is not a valid public property detail address. No image or private data was loaded.'
              : 'The current public preview could not be loaded. The exact saved link is still available below.'}
          </p>
          <div className="ld-actions">
            {state === 'error' ? (
              <button
                type="button"
                className="oe-btn oe-btn--secondary oe-btn--sm"
                onClick={() => setRetry((value) => value + 1)}
              >
                <RefreshCw size={14} aria-hidden="true" />Retry preview
              </button>
            ) : null}
            {propertyId !== null ? (
              <a
                href={link}
                target="_blank"
                rel="noopener noreferrer"
                className="oe-btn oe-btn--secondary oe-btn--sm"
              >
                Open public page
                <ExternalLink size={14} aria-hidden="true" />
              </a>
            ) : null}
          </div>
        </div>
      )}
    </section>
  );
};
