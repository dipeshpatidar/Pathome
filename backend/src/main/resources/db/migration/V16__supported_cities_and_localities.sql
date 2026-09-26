-- V16: Canonical localities for supported cities (Indore, Bhopal, Pune)
-- Enables authoritative locality-to-city resolution across supported Pathome markets.

INSERT INTO localities (city, sector_name, created_at)
VALUES
    ('Pune', 'Baner', NOW()),
    ('Pune', 'Wakad', NOW()),
    ('Pune', 'Hinjewadi', NOW()),
    ('Pune', 'Kharadi', NOW()),
    ('Pune', 'Viman Nagar', NOW()),
    ('Bhopal', 'MP Nagar', NOW()),
    ('Bhopal', 'Arera Colony', NOW()),
    ('Bhopal', 'Kolar Road', NOW()),
    ('Bhopal', 'Hoshangabad Road', NOW()),
    ('Indore', 'Vijay Nagar', NOW()),
    ('Indore', 'Nanda Nagar', NOW()),
    ('Indore', 'Bhawarkua', NOW()),
    ('Indore', 'Nipania', NOW()),
    ('Indore', 'AB Road', NOW()),
    ('Indore', 'Super Corridor', NOW()),
    ('Indore', 'LIG Circle', NOW()),
    ('Indore', 'Old Palasia', NOW()),
    ('Indore', 'Rau', NOW()),
    ('Indore', 'Mahalaxmi Nagar', NOW()),
    ('Indore', 'Scheme 78', NOW()),
    ('Indore', 'Scheme 140', NOW()),
    ('Indore', 'Saket Nagar', NOW()),
    ('Indore', 'Tilak Nagar', NOW()),
    ('Indore', 'Kalani Nagar', NOW())
ON CONFLICT (city, sector_name) DO NOTHING;
