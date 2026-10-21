import React, { useEffect, useState } from 'react';
import { Building2, ImageOff } from 'lucide-react';

interface OperationsPropertyThumbProps {
  imageUrl: string | null | undefined;
  title: string | null | undefined;
  listingId: number | null | undefined;
  listingStatus: string | null | undefined;
  className?: string;
}

/**
 * Stable operational property thumbnail.
 *
 * A public listing link exists only for an ACTIVE listing. The component never manufactures a photo,
 * never represents a missing image as availability, and exposes loading and failure states to
 * assistive technology.
 */
export const OperationsPropertyThumb: React.FC<OperationsPropertyThumbProps> = ({
  imageUrl,
  title,
  listingId,
  listingStatus,
  className = ''
}) => {
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
  /**
   * Current public visibility is a render-time gate, not a server-only promise. A stale or malformed
   * response that still carries an old cover URL cannot make an inactive listing look available.
   */
  const active = listingStatus === 'ACTIVE';
  const url = active && typeof imageUrl === 'string' && imageUrl.trim() ? imageUrl.trim() : null;

  useEffect(() => {
    setLoaded(false);
    setFailed(false);
  }, [url]);

  const linkable = active
    && typeof listingId === 'number'
    && Number.isSafeInteger(listingId)
    && listingId > 0;
  const label = title?.trim() ? `property photo: ${title.trim()}` : 'property photo';

  const body = (
    <span className={`oe-thumb-media${loaded ? ' oe-thumb-media--loaded' : ''}`}>
      {url && !failed ? (
        <>
          {!loaded ? <span className="oe-thumb-skeleton" aria-hidden="true" /> : null}
          <img
            src={url}
            alt={label}
            loading="lazy"
            decoding="async"
            onLoad={() => setLoaded(true)}
            onError={() => setFailed(true)}
          />
        </>
      ) : (
        <span className="oe-thumb-empty" role="img" aria-label={url ? 'Property photo unavailable' : 'No property photo'}>
          {failed ? <ImageOff size={18} aria-hidden="true" /> : <Building2 size={18} aria-hidden="true" />}
        </span>
      )}
    </span>
  );

  if (linkable) {
    return (
      <a
        className={`oe-thumb oe-thumb--link ${className}`.trim()}
        href={`/property/${listingId}`}
        aria-label={`Open property listing${title?.trim() ? `: ${title.trim()}` : ''}`}
      >
        {body}
      </a>
    );
  }
  return <span className={`oe-thumb ${className}`.trim()}>{body}</span>;
};
