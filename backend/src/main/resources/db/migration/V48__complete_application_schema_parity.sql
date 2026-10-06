-- Reproduce current mapped column capacity and missing parser-era application objects on the
-- supported V1 legacy baseline without changing entity or business semantics.
DO $$
DECLARE
    column_spec RECORD;
    actual_type TEXT;
    actual_length INTEGER;
    actual_precision INTEGER;
    actual_scale INTEGER;
    nullable_state TEXT;
    has_null BOOLEAN;
    media_table REGCLASS := to_regclass(format('%I.%I', current_schema(), 'property_media_assets'));
    log_table REGCLASS := to_regclass(format('%I.%I', current_schema(), 'tenant_visit_logs'));
    parent_table REGCLASS;
    source_attnum SMALLINT;
    target_attnum SMALLINT;
    constraint_name TEXT;
    duplicate_found BOOLEAN;
    log_id_sequence REGCLASS;
    sequence_last_value BIGINT;
    sequence_called BOOLEAN;
    maximum_existing_id BIGINT;
BEGIN
    -- V9's read-only debug view projects these bounded strings; recreate it in this migration
    -- transaction so safe VARCHAR widening can proceed while the final view contract stays intact.
    EXECUTE format('DROP VIEW IF EXISTS %I.%I', current_schema(), 'property_media_debug_view');

    IF media_table IS NULL THEN
        RAISE EXCEPTION 'V48 cannot complete mapped media schema: property_media_assets is missing';
    END IF;

    -- These mapped coordinates are nullable, so historical rows require no invented values.
    FOR column_spec IN
        SELECT * FROM (VALUES ('latitude'), ('longitude')) AS expected(column_name)
    LOOP
        SELECT data_type INTO actual_type
          FROM information_schema.columns
         WHERE table_schema = current_schema() AND table_name = 'property_media_assets'
           AND column_name = column_spec.column_name;
        IF NOT FOUND THEN
            EXECUTE format('ALTER TABLE %I.%I ADD COLUMN %I DOUBLE PRECISION',
                current_schema(), 'property_media_assets', column_spec.column_name);
        ELSIF actual_type <> 'double precision' THEN
            RAISE EXCEPTION 'Existing property_media_assets.% has incompatible type %',
                column_spec.column_name, actual_type;
        END IF;
    END LOOP;

    -- Widen constrained strings to the current JPA default length. Existing broader TEXT or
    -- VARCHAR columns stay broader; no historical value is shortened or rewritten.
    FOR column_spec IN
        SELECT * FROM (VALUES
            ('employee_profiles', 'assigned_sector', 255),
            ('employee_profiles', 'role_type', 255),
            ('lead_routing_queue', 'phone_number', 255),
            ('lead_routing_queue', 'status', 255),
            ('lead_routing_queue', 'target_sector', 255),
            ('listings', 'bhk_count', 255),
            ('listings', 'city', 255),
            ('listings', 'listing_type', 255),
            ('listings', 'location_resolution', 255),
            ('listings', 'owner_phone_number', 255),
            ('listings', 'property_type', 255),
            ('listings', 'rental_mode', 255),
            ('listings', 'sector', 255),
            ('listings', 'status', 255),
            ('listings', 'workflow_status', 255),
            ('property_media_assets', 'city', 255),
            ('property_media_assets', 'media_type', 255),
            ('property_media_assets', 'price_tag', 255),
            ('property_media_assets', 'room_tag', 255),
            ('property_media_assets', 'sector', 255),
            ('property_media_assets', 'vastu_facing', 255),
            ('property_media_assets', 'verification_status', 255),
            ('rental_details', 'preferred_tenant', 255),
            ('sale_details', 'ownership_type', 255),
            ('system_notifications', 'recipient_user_id', 255),
            ('users', 'email', 255),
            ('users', 'phone_number', 255),
            ('users', 'role', 255)
        ) AS expected(table_name, column_name, expected_length)
    LOOP
        SELECT data_type, character_maximum_length
          INTO actual_type, actual_length
          FROM information_schema.columns
         WHERE table_schema = current_schema() AND table_name = column_spec.table_name
           AND column_name = column_spec.column_name;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'V48 expected mapped column %.% is missing',
                column_spec.table_name, column_spec.column_name;
        ELSIF actual_type = 'character varying' THEN
            IF actual_length IS NOT NULL AND actual_length < column_spec.expected_length THEN
                EXECUTE format('ALTER TABLE %I.%I ALTER COLUMN %I TYPE VARCHAR(%s)',
                    current_schema(), column_spec.table_name, column_spec.column_name,
                    column_spec.expected_length);
            END IF;
        ELSIF actual_type <> 'text' THEN
            RAISE EXCEPTION 'Existing %.% has incompatible type %',
                column_spec.table_name, column_spec.column_name, actual_type;
        END IF;
    END LOOP;

    -- BigDecimal mappings use Hibernate's NUMERIC(38,2) default. Widen only when the
    -- historical schema has lower precision with the same scale.
    FOR column_spec IN
        SELECT * FROM (VALUES
            ('employee_profiles', 'base_salary'),
            ('lead_routing_queue', 'budget'),
            ('rental_details', 'brokerage_amount'),
            ('rental_details', 'maintenance_charge'),
            ('rental_details', 'monthly_rent'),
            ('rental_details', 'security_deposit'),
            ('sale_details', 'asking_price'),
            ('sale_details', 'price_per_sq_ft')
        ) AS expected(table_name, column_name)
    LOOP
        SELECT data_type, numeric_precision, numeric_scale
          INTO actual_type, actual_precision, actual_scale
          FROM information_schema.columns
         WHERE table_schema = current_schema() AND table_name = column_spec.table_name
           AND column_name = column_spec.column_name;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'V48 expected mapped column %.% is missing',
                column_spec.table_name, column_spec.column_name;
        ELSIF actual_type <> 'numeric' OR actual_scale <> 2 THEN
            RAISE EXCEPTION 'Existing %.% has incompatible numeric definition (type %, scale %)',
                column_spec.table_name, column_spec.column_name, actual_type, actual_scale;
        ELSIF actual_precision < 38 THEN
            EXECUTE format('ALTER TABLE %I.%I ALTER COLUMN %I TYPE NUMERIC(38,2)',
                current_schema(), column_spec.table_name, column_spec.column_name);
        END IF;
    END LOOP;

    -- Required entity fields become NOT NULL only when historical rows already contain values.
    FOR column_spec IN
        SELECT * FROM (VALUES
            ('listings', 'bhk_count'),
            ('property_media_assets', 'media_url'),
            ('property_media_assets', 'room_tag')
        ) AS expected(table_name, column_name)
    LOOP
        SELECT is_nullable INTO nullable_state
          FROM information_schema.columns
         WHERE table_schema = current_schema() AND table_name = column_spec.table_name
           AND column_name = column_spec.column_name;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'V48 expected required mapped column %.% is missing',
                column_spec.table_name, column_spec.column_name;
        ELSIF nullable_state = 'YES' THEN
            EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.%I WHERE %I IS NULL)',
                current_schema(), column_spec.table_name, column_spec.column_name) INTO has_null;
            IF has_null THEN
                RAISE EXCEPTION 'Cannot enforce NOT NULL on %.%: existing rows contain NULL values',
                    column_spec.table_name, column_spec.column_name;
            END IF;
            EXECUTE format('ALTER TABLE %I.%I ALTER COLUMN %I SET NOT NULL',
                current_schema(), column_spec.table_name, column_spec.column_name);
        END IF;
    END LOOP;

    -- tenant_visit_logs was historically created outside Flyway. Create it on clean installs;
    -- validate and preserve an existing compatible Hibernate-created table on upgrades.
    log_table := to_regclass(format('%I.%I', current_schema(), 'tenant_visit_logs'));
    IF log_table IS NULL THEN
        CREATE TABLE tenant_visit_logs (
            id BIGINT GENERATED BY DEFAULT AS IDENTITY,
            tenant_id BIGINT NOT NULL,
            listing_id BIGINT NOT NULL,
            visit_sequence_number INTEGER NOT NULL,
            commitment_deposit_paid BOOLEAN NOT NULL,
            commitment_deposit_amount NUMERIC(38,2),
            txn_id VARCHAR(255),
            otp_code VARCHAR(255),
            geofence_verified BOOLEAN NOT NULL,
            verified_at TIMESTAMP WITHOUT TIME ZONE,
            created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
            CONSTRAINT tenant_visit_logs_pkey PRIMARY KEY (id),
            CONSTRAINT fk_tenant_visit_logs_tenant FOREIGN KEY (tenant_id) REFERENCES users(id),
            CONSTRAINT fk_tenant_visit_logs_listing FOREIGN KEY (listing_id) REFERENCES listings(id)
        );
        log_table := to_regclass(format('%I.%I', current_schema(), 'tenant_visit_logs'));
    ELSE
        IF (SELECT relkind FROM pg_class WHERE oid = log_table) <> 'r' THEN
            RAISE EXCEPTION 'Existing tenant_visit_logs is not a regular table';
        END IF;

        FOR column_spec IN
            SELECT * FROM (VALUES
                ('id', 'bigint', NULL::INTEGER, NULL::INTEGER, NULL::INTEGER, TRUE),
                ('tenant_id', 'bigint', NULL::INTEGER, NULL::INTEGER, NULL::INTEGER, TRUE),
                ('listing_id', 'bigint', NULL::INTEGER, NULL::INTEGER, NULL::INTEGER, TRUE),
                ('visit_sequence_number', 'integer', NULL::INTEGER, NULL::INTEGER, NULL::INTEGER, TRUE),
                ('commitment_deposit_paid', 'boolean', NULL::INTEGER, NULL::INTEGER, NULL::INTEGER, TRUE),
                ('commitment_deposit_amount', 'numeric', NULL::INTEGER, 38, 2, FALSE),
                ('txn_id', 'character varying', 255, NULL::INTEGER, NULL::INTEGER, FALSE),
                ('otp_code', 'character varying', 255, NULL::INTEGER, NULL::INTEGER, FALSE),
                ('geofence_verified', 'boolean', NULL::INTEGER, NULL::INTEGER, NULL::INTEGER, TRUE),
                ('verified_at', 'timestamp without time zone', NULL::INTEGER, NULL::INTEGER, NULL::INTEGER, FALSE),
                ('created_at', 'timestamp without time zone', NULL::INTEGER, NULL::INTEGER, NULL::INTEGER, TRUE)
            ) AS expected(column_name, expected_type, expected_length, expected_precision, expected_scale, required)
        LOOP
            SELECT data_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable
              INTO actual_type, actual_length, actual_precision, actual_scale, nullable_state
              FROM information_schema.columns
             WHERE table_schema = current_schema() AND table_name = 'tenant_visit_logs'
               AND column_name = column_spec.column_name;
            IF NOT FOUND THEN
                RAISE EXCEPTION 'Existing tenant_visit_logs is incompatible: column % is missing',
                    column_spec.column_name;
            ELSIF actual_type <> column_spec.expected_type THEN
                RAISE EXCEPTION 'Existing tenant_visit_logs.% has incompatible type %',
                    column_spec.column_name, actual_type;
            ELSIF actual_type = 'character varying' AND actual_length IS NOT NULL
                  AND actual_length < column_spec.expected_length THEN
                EXECUTE format('ALTER TABLE %I.%I ALTER COLUMN %I TYPE VARCHAR(%s)',
                    current_schema(), 'tenant_visit_logs', column_spec.column_name,
                    column_spec.expected_length);
            ELSIF actual_type = 'numeric' AND (actual_scale <> column_spec.expected_scale
                  OR actual_precision < column_spec.expected_precision) THEN
                IF actual_scale <> column_spec.expected_scale THEN
                    RAISE EXCEPTION 'Existing tenant_visit_logs.% has incompatible numeric scale %',
                        column_spec.column_name, actual_scale;
                END IF;
                EXECUTE format('ALTER TABLE %I.%I ALTER COLUMN %I TYPE NUMERIC(38,2)',
                    current_schema(), 'tenant_visit_logs', column_spec.column_name);
            END IF;

            IF column_spec.required AND nullable_state = 'YES' THEN
                EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.%I WHERE %I IS NULL)',
                    current_schema(), 'tenant_visit_logs', column_spec.column_name) INTO has_null;
                IF has_null THEN
                    RAISE EXCEPTION 'Existing tenant_visit_logs.% contains NULL values required by JPA',
                        column_spec.column_name;
                END IF;
                EXECUTE format('ALTER TABLE %I.%I ALTER COLUMN %I SET NOT NULL',
                    current_schema(), 'tenant_visit_logs', column_spec.column_name);
            END IF;
        END LOOP;

        IF pg_get_serial_sequence(format('%I.%I', current_schema(), 'tenant_visit_logs'), 'id') IS NULL THEN
            RAISE EXCEPTION 'Existing tenant_visit_logs.id has no identity or sequence-backed generator';
        END IF;

        SELECT conname INTO constraint_name FROM pg_constraint
         WHERE conrelid = log_table AND contype = 'p';
        IF FOUND THEN
            IF pg_get_constraintdef((SELECT oid FROM pg_constraint WHERE conrelid = log_table AND conname = constraint_name))
                    <> 'PRIMARY KEY (id)' THEN
                RAISE EXCEPTION 'Existing tenant_visit_logs primary key is incompatible';
            END IF;
        ELSE
            EXECUTE 'SELECT EXISTS (SELECT 1 FROM tenant_visit_logs GROUP BY id HAVING count(*) > 1)'
                INTO duplicate_found;
            IF duplicate_found THEN
                RAISE EXCEPTION 'Existing tenant_visit_logs contains duplicate IDs; primary key cannot be added safely';
            END IF;
            ALTER TABLE tenant_visit_logs ADD CONSTRAINT tenant_visit_logs_pkey PRIMARY KEY (id);
        END IF;

    END IF;

    -- Both fresh and historical tables must have the mapped direct references. Existing equivalent
    -- constraints under Hibernate-generated names are retained; invalid references fail explicitly.
    FOR column_spec IN
        SELECT * FROM (VALUES
            ('tenant_id', 'users', 'fk_tenant_visit_logs_tenant'),
            ('listing_id', 'listings', 'fk_tenant_visit_logs_listing')
        ) AS expected(source_column, target_table, constraint_name)
    LOOP
        parent_table := to_regclass(format('%I.%I', current_schema(), column_spec.target_table));
        IF parent_table IS NULL THEN
            RAISE EXCEPTION 'V48 cannot create tenant_visit_logs foreign key: %.% is missing',
                current_schema(), column_spec.target_table;
        END IF;
        SELECT attnum INTO source_attnum FROM pg_attribute
         WHERE attrelid = log_table AND attname = column_spec.source_column
           AND attnum > 0 AND NOT attisdropped;
        SELECT attnum INTO target_attnum FROM pg_attribute
         WHERE attrelid = parent_table AND attname = 'id'
           AND attnum > 0 AND NOT attisdropped;

        IF EXISTS (SELECT 1 FROM pg_constraint con
                   WHERE con.conrelid = log_table AND con.contype = 'f'
                     AND source_attnum = ANY(con.conkey)
                     AND (con.conkey <> ARRAY[source_attnum]::SMALLINT[]
                          OR con.confrelid <> parent_table
                          OR con.confkey <> ARRAY[target_attnum]::SMALLINT[]
                          OR NOT con.convalidated
                          OR con.confupdtype <> 'a' OR con.confdeltype <> 'a'
                          OR con.confmatchtype <> 's'
                          OR con.condeferrable OR con.condeferred)) THEN
            RAISE EXCEPTION 'Existing tenant_visit_logs.% foreign key has incompatible target, validation, or referential actions',
                column_spec.source_column;
        END IF;

        IF EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = log_table
                   AND conname = column_spec.constraint_name)
           AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = log_table
               AND conname = column_spec.constraint_name AND contype = 'f'
               AND conkey = ARRAY[source_attnum]::SMALLINT[] AND confrelid = parent_table
               AND confkey = ARRAY[target_attnum]::SMALLINT[] AND convalidated
               AND confupdtype = 'a' AND confdeltype = 'a' AND confmatchtype = 's'
               AND NOT condeferrable AND NOT condeferred) THEN
            RAISE EXCEPTION 'Constraint % exists with an incompatible definition', column_spec.constraint_name;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = log_table AND contype = 'f'
                       AND conkey = ARRAY[source_attnum]::SMALLINT[] AND confrelid = parent_table
                       AND confkey = ARRAY[target_attnum]::SMALLINT[] AND convalidated
                       AND confupdtype = 'a' AND confdeltype = 'a' AND confmatchtype = 's'
                       AND NOT condeferrable AND NOT condeferred) THEN
            EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.%I child LEFT JOIN %I.%I parent '
                'ON parent.id=child.%I WHERE child.%I IS NOT NULL AND parent.id IS NULL)',
                current_schema(), 'tenant_visit_logs', current_schema(), column_spec.target_table,
                column_spec.source_column, column_spec.source_column) INTO has_null;
            IF has_null THEN
                RAISE EXCEPTION 'Cannot add tenant_visit_logs.% foreign key: historical rows reference missing % rows',
                    column_spec.source_column, column_spec.target_table;
            END IF;
            EXECUTE format('ALTER TABLE %I.%I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I.%I(id)',
                current_schema(), 'tenant_visit_logs', column_spec.constraint_name,
                column_spec.source_column, current_schema(), column_spec.target_table);
        END IF;
    END LOOP;

    -- Preserve an already-ahead generator (deleted IDs may have been consumed); advance it only
    -- when explicit historical IDs are ahead of its current state.
    IF log_table IS NOT NULL THEN
        EXECUTE format('SELECT COALESCE(MAX(id), 0) FROM %I.%I', current_schema(), 'tenant_visit_logs')
            INTO maximum_existing_id;
        log_id_sequence := pg_get_serial_sequence(
            format('%I.%I', current_schema(), 'tenant_visit_logs'), 'id')::REGCLASS;
        IF log_id_sequence IS NULL THEN
            RAISE EXCEPTION 'tenant_visit_logs.id generator disappeared during schema reconciliation';
        END IF;
        EXECUTE format('SELECT last_value, is_called FROM %s', log_id_sequence)
            INTO sequence_last_value, sequence_called;
        IF maximum_existing_id > sequence_last_value
           OR (maximum_existing_id > 0 AND maximum_existing_id = sequence_last_value AND NOT sequence_called) THEN
            PERFORM setval(log_id_sequence, maximum_existing_id, TRUE);
        END IF;
    END IF;
END
$$;

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

DO $$
DECLARE
    index_spec RECORD;
    table_oid OID := to_regclass(format('%I.%I', current_schema(), 'tenant_visit_logs'));
    index_oid OID;
    index_relkind "char";
    indexed_table OID;
    unique_index BOOLEAN;
    valid_index BOOLEAN;
    btree_index BOOLEAN;
    unpredicated BOOLEAN;
    indexed_columns TEXT[];
BEGIN
    FOR index_spec IN
        SELECT * FROM (VALUES
            ('idx_visit_tenant_listing', ARRAY['tenant_id', 'listing_id']::TEXT[]),
            ('idx_visit_otp', ARRAY['otp_code']::TEXT[])
        ) AS expected(index_name, columns)
    LOOP
        index_oid := to_regclass(format('%I.%I', current_schema(), index_spec.index_name));
        IF index_oid IS NOT NULL THEN
            SELECT relkind INTO index_relkind FROM pg_class WHERE oid = index_oid;
            IF index_relkind <> 'i' THEN
                RAISE EXCEPTION 'Relation % exists but is not the expected index', index_spec.index_name;
            END IF;
            SELECT ix.indrelid, ix.indisunique, ix.indisvalid,
                   access_method.amname = 'btree', ix.indpred IS NULL,
                   ARRAY(SELECT pg_get_indexdef(ix.indexrelid, position, TRUE)
                           FROM generate_series(1, ix.indnkeyatts) AS position ORDER BY position)
              INTO indexed_table, unique_index, valid_index, btree_index, unpredicated, indexed_columns
              FROM pg_index ix
              JOIN pg_class index_class ON index_class.oid = ix.indexrelid
              JOIN pg_am access_method ON access_method.oid = index_class.relam
             WHERE ix.indexrelid = index_oid;
            IF NOT FOUND OR indexed_table IS DISTINCT FROM table_oid
               OR unique_index IS DISTINCT FROM FALSE OR valid_index IS DISTINCT FROM TRUE
               OR btree_index IS DISTINCT FROM TRUE OR unpredicated IS DISTINCT FROM TRUE
               OR indexed_columns IS DISTINCT FROM index_spec.columns THEN
                RAISE EXCEPTION 'Index % exists with an incompatible definition', index_spec.index_name;
            END IF;
        ELSE
            EXECUTE format('CREATE INDEX %I ON %I.%I (%s)', index_spec.index_name,
                current_schema(), 'tenant_visit_logs',
                (SELECT string_agg(format('%I', column_name), ', ' ORDER BY ordinal)
                   FROM unnest(index_spec.columns) WITH ORDINALITY AS cols(column_name, ordinal)));
        END IF;
    END LOOP;
END
$$;
