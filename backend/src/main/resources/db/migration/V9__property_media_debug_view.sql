-- V9: Developer/debugging read-only view for property media assets inspection
CREATE OR REPLACE VIEW property_media_debug_view AS
SELECT 
    pma.listing_id,
    l.title             AS listing_title,
    l.status            AS listing_status,
    l.origin_draft_id,
    pma.id              AS media_asset_id,
    pma.media_type,
    pma.room_tag,
    pma.caption,
    pma.is_primary_cover AS is_cover,
    pma.upload_request_id,
    pma.media_url,
    pma.cloudinary_public_id,
    pma.verification_status,
    pma.sector,
    pma.city,
    pma.price_tag,
    pma.vastu_facing,
    pma.uploaded_at
FROM property_media_assets pma
LEFT JOIN listings l ON l.id = pma.listing_id;
