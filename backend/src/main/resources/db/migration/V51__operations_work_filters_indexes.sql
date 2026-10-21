-- Additive indexes for the approved OE queue filter paths. No data, policy or timestamp changes.

CREATE INDEX IF NOT EXISTS idx_pvr_coordinator_work_filter
    ON property_visit_requests (coordinator_user_id, operating_team_id, supported_city_id, status, id)
    WHERE coordinator_user_id IS NOT NULL
      AND session_id IS NULL
      AND operational_scope_ready = TRUE;

CREATE INDEX IF NOT EXISTS idx_pvr_claimable_work_filter
    ON property_visit_requests (operating_team_id, supported_city_id, status, id)
    WHERE coordinator_user_id IS NULL
      AND session_id IS NULL
      AND operational_scope_ready = TRUE;

CREATE INDEX IF NOT EXISTS idx_vs_coordinator_work_filter
    ON visit_sessions (coordinator_user_id, operating_team_id, supported_city_id, status, id)
    WHERE coordinator_user_id IS NOT NULL
      AND operational_scope_ready = TRUE;

CREATE INDEX IF NOT EXISTS idx_vs_claimable_work_filter
    ON visit_sessions (operating_team_id, supported_city_id, status, id)
    WHERE coordinator_user_id IS NULL
      AND operational_scope_ready = TRUE;

-- Batch cover resolution reads every image for the page's listings in one query.
CREATE INDEX IF NOT EXISTS idx_media_listing_cover_order
    ON property_media_assets (listing_id, sort_order, uploaded_at DESC, id DESC);
