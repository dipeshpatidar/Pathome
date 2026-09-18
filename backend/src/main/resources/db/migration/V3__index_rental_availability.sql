DO $$
BEGIN
    IF to_regclass('public.rental_details') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_rental_available_from
            ON rental_details (available_from);
    END IF;
END $$;
