-- Owner-approved additive source only. This migration has not been applied.
-- Preserve V61 and every existing association while allowing the same User to associate
-- separate lawfully requested properties with one Lead.

ALTER TABLE lead_visit_links
    ADD COLUMN listing_id BIGINT;

UPDATE lead_visit_links AS link
SET listing_id = proposal.listing_id
FROM lead_visit_proposals AS proposal
WHERE proposal.id = link.proposal_id
  AND link.listing_id IS NULL;

ALTER TABLE lead_visit_links
    ALTER COLUMN listing_id SET NOT NULL,
    ADD CONSTRAINT fk_lead_visit_link_listing
        FOREIGN KEY (listing_id) REFERENCES listings(id),
    DROP CONSTRAINT uk_lead_visit_link_lead;

ALTER TABLE lead_visit_links
    ADD CONSTRAINT uk_lead_visit_link_lead_user_listing
        UNIQUE (lead_id, user_id, listing_id);

CREATE INDEX idx_lead_visit_link_lead_history
    ON lead_visit_links(lead_id, confirmed_at, id);
