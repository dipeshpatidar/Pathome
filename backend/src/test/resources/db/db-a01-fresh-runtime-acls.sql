GRANT USAGE ON SCHEMA public TO oe_dbf_runtime_cbb093e7;

GRANT SELECT ON TABLE
    public.users,
    public.employee_profiles,
    public.staff_access_grants,
    public.supported_cities,
    public.operating_teams,
    public.localities,
    public.listings,
    public.rental_details,
    public.sale_details,
    public.property_media_assets,
    public.user_favorites,
    public.property_visit_requests,
    public.visit_sessions,
    public.visit_session_items,
    public.lessor_profiles,
    public.property_upload_drafts,
    public.system_notifications
TO oe_dbf_runtime_cbb093e7;

-- The Lead workspace reads the latest proof state to render the approved A1-A7 UI.
-- It receives no insert, update, delete, outbox, proposal, association or receipt authority.
GRANT SELECT ON TABLE public.lead_phone_challenges
TO oe_dbf_runtime_cbb093e7;

REVOKE UPDATE ON TABLE
    public.users,
    public.employee_profiles,
    public.staff_access_grants,
    public.supported_cities,
    public.operating_teams,
    public.listings,
    public.lead_shortlist_items,
    public.lead_preferred_localities
FROM oe_dbf_runtime_cbb093e7;
GRANT UPDATE (created_at) ON TABLE public.users TO oe_dbf_runtime_cbb093e7;
GRANT UPDATE (base_salary) ON TABLE public.employee_profiles TO oe_dbf_runtime_cbb093e7;
GRANT UPDATE (grant_reason_code) ON TABLE public.staff_access_grants TO oe_dbf_runtime_cbb093e7;
GRANT UPDATE (created_at) ON TABLE public.supported_cities TO oe_dbf_runtime_cbb093e7;
GRANT UPDATE (created_at) ON TABLE public.operating_teams TO oe_dbf_runtime_cbb093e7;
GRANT UPDATE (created_at) ON TABLE public.listings TO oe_dbf_runtime_cbb093e7;

GRANT SELECT, INSERT, UPDATE ON TABLE
    public.lead_routing_queue,
    public.lead_source_events,
    public.lead_shortlists
TO oe_dbf_runtime_cbb093e7;

GRANT SELECT, INSERT ON TABLE
    public.lead_preferred_localities,
    public.lead_contact_preferences,
    public.lead_shares,
    public.lead_share_events,
    public.lead_activities,
    public.lead_command_receipts,
    public.lead_lookup_events,
    public.operational_audit_events
TO oe_dbf_runtime_cbb093e7;

GRANT SELECT, INSERT ON TABLE public.lead_shortlist_items
TO oe_dbf_runtime_cbb093e7;

GRANT DELETE ON TABLE
    public.lead_shortlist_items,
    public.lead_preferred_localities
TO oe_dbf_runtime_cbb093e7;

REVOKE SELECT ON SEQUENCE
    public.lead_routing_queue_id_seq,
    public.lead_preferred_localities_id_seq,
    public.lead_contact_preferences_id_seq,
    public.lead_shortlists_id_seq,
    public.lead_shortlist_items_id_seq,
    public.lead_activities_id_seq,
    public.lead_lookup_events_id_seq,
    public.operational_audit_events_id_seq
FROM oe_dbf_runtime_cbb093e7;

GRANT USAGE ON SEQUENCE
    public.lead_routing_queue_id_seq,
    public.lead_preferred_localities_id_seq,
    public.lead_contact_preferences_id_seq,
    public.lead_shortlists_id_seq,
    public.lead_shortlist_items_id_seq,
    public.lead_activities_id_seq,
    public.lead_lookup_events_id_seq,
    public.operational_audit_events_id_seq
TO oe_dbf_runtime_cbb093e7;
