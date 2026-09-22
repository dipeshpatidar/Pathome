-- V10: Add floor, total floors, and preferred tenant attributes
ALTER TABLE listings ADD COLUMN IF NOT EXISTS floor_number INTEGER;
ALTER TABLE listings ADD COLUMN IF NOT EXISTS total_floors INTEGER;

ALTER TABLE rental_details ADD COLUMN IF NOT EXISTS preferred_tenant VARCHAR(100);
