DO $$
DECLARE
    training_table REGCLASS := to_regclass(format('%I.%I', current_schema(), 'parser_training_examples'));
    model_table REGCLASS;
    table_kind "char";
    table_has_rows BOOLEAN;
    column_spec RECORD;
    actual_column_type TEXT;
    actual_column_required BOOLEAN;
    training_id_attnum SMALLINT;
    model_id_attnum SMALLINT;
    model_version_attnum SMALLINT;
    constraint_name TEXT;
    constraint_columns SMALLINT[];
    duplicate_found BOOLEAN;
    index_name TEXT;
    index_columns TEXT[];
    actual_index_oid OID;
    actual_index_table OID;
    actual_index_unique BOOLEAN;
    actual_index_valid BOOLEAN;
    actual_index_btree BOOLEAN;
    actual_index_unpredicated BOOLEAN;
    actual_index_without_includes BOOLEAN;
    actual_index_columns TEXT[];
    rendered_columns TEXT;
    model_sequence TEXT;
    maximum_model_id BIGINT;
    sequence_last_value BIGINT;
    sequence_called BOOLEAN;
    sequence_increment BIGINT;
    sequence_min_value BIGINT;
    sequence_max_value BIGINT;
    sequence_cache_size BIGINT;
    sequence_cycles BOOLEAN;
    sequence_next_value NUMERIC;
    generator_identity "char";
    generator_default TEXT;
BEGIN
    IF training_table IS NULL THEN
        RAISE EXCEPTION 'Cannot complete parser persistence schema: parser_training_examples is missing from the supported V1 baseline';
    END IF;

    SELECT relkind INTO table_kind FROM pg_class WHERE oid = training_table;
    IF table_kind <> 'r' THEN
        RAISE EXCEPTION 'Existing parser_training_examples is not a regular PostgreSQL table';
    END IF;

    EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.%I)', current_schema(), 'parser_training_examples')
       INTO table_has_rows;

    FOR column_spec IN
        SELECT * FROM (VALUES
            ('id', 'character varying(36)', 'VARCHAR(36)', TRUE),
            ('batch_id', 'character varying(36)', 'VARCHAR(36)', TRUE),
            ('property_index', 'integer', 'INTEGER', TRUE),
            ('input_source', 'character varying(16)', 'VARCHAR(16)', TRUE),
            ('review_status', 'character varying(40)', 'VARCHAR(40)', TRUE),
            ('dataset_partition', 'character varying(16)', 'VARCHAR(16)', TRUE),
            ('raw_prompt', 'text', 'TEXT', TRUE),
            ('prompt_hash', 'character varying(64)', 'VARCHAR(64)', TRUE),
            ('initial_prediction', 'text', 'TEXT', TRUE),
            ('final_reviewed_values', 'text', 'TEXT', FALSE),
            ('parser_version', 'character varying(80)', 'VARCHAR(80)', TRUE),
            ('published_listing_id', 'bigint', 'BIGINT', FALSE),
            ('reviewed_by', 'character varying(254)', 'VARCHAR(254)', FALSE),
            ('created_by', 'character varying(254)', 'VARCHAR(254)', TRUE),
            ('exclusion_reason', 'character varying(500)', 'VARCHAR(500)', FALSE),
            ('label_count', 'integer', 'INTEGER', TRUE),
            ('eligible_label_count', 'integer', 'INTEGER', TRUE),
            ('created_at', 'timestamp without time zone', 'TIMESTAMP WITHOUT TIME ZONE', TRUE),
            ('updated_at', 'timestamp without time zone', 'TIMESTAMP WITHOUT TIME ZONE', TRUE),
            ('published_at', 'timestamp without time zone', 'TIMESTAMP WITHOUT TIME ZONE', FALSE),
            ('curated_at', 'timestamp without time zone', 'TIMESTAMP WITHOUT TIME ZONE', FALSE)
        ) AS expected(column_name, column_type, add_type, required)
    LOOP
        SELECT format_type(a.atttypid, a.atttypmod), a.attnotnull
          INTO actual_column_type, actual_column_required
          FROM pg_attribute a
         WHERE a.attrelid = training_table AND a.attname = column_spec.column_name
           AND a.attnum > 0 AND NOT a.attisdropped;

        IF NOT FOUND THEN
            IF column_spec.required AND table_has_rows THEN
                RAISE EXCEPTION 'Cannot add required parser_training_examples.%; existing rows need a product-approved value',
                    column_spec.column_name;
            END IF;
        ELSIF (column_spec.column_name IN ('created_at', 'updated_at', 'published_at', 'curated_at')
                   AND actual_column_type <> 'timestamp without time zone')
           OR (column_spec.column_name NOT IN ('created_at', 'updated_at', 'published_at', 'curated_at')
                   AND actual_column_type <> column_spec.column_type)
           OR actual_column_required <> column_spec.required THEN
            RAISE EXCEPTION 'Existing parser_training_examples column % is incompatible (type %, NOT NULL %)',
                column_spec.column_name, actual_column_type, actual_column_required;
        END IF;
    END LOOP;

    FOR column_spec IN
        SELECT * FROM (VALUES
            ('id', 'character varying(36)', 'VARCHAR(36)', TRUE),
            ('batch_id', 'character varying(36)', 'VARCHAR(36)', TRUE),
            ('property_index', 'integer', 'INTEGER', TRUE),
            ('input_source', 'character varying(16)', 'VARCHAR(16)', TRUE),
            ('review_status', 'character varying(40)', 'VARCHAR(40)', TRUE),
            ('dataset_partition', 'character varying(16)', 'VARCHAR(16)', TRUE),
            ('raw_prompt', 'text', 'TEXT', TRUE),
            ('prompt_hash', 'character varying(64)', 'VARCHAR(64)', TRUE),
            ('initial_prediction', 'text', 'TEXT', TRUE),
            ('final_reviewed_values', 'text', 'TEXT', FALSE),
            ('parser_version', 'character varying(80)', 'VARCHAR(80)', TRUE),
            ('published_listing_id', 'bigint', 'BIGINT', FALSE),
            ('reviewed_by', 'character varying(254)', 'VARCHAR(254)', FALSE),
            ('created_by', 'character varying(254)', 'VARCHAR(254)', TRUE),
            ('exclusion_reason', 'character varying(500)', 'VARCHAR(500)', FALSE),
            ('label_count', 'integer', 'INTEGER', TRUE),
            ('eligible_label_count', 'integer', 'INTEGER', TRUE),
            ('created_at', 'timestamp without time zone', 'TIMESTAMP WITHOUT TIME ZONE', TRUE),
            ('updated_at', 'timestamp without time zone', 'TIMESTAMP WITHOUT TIME ZONE', TRUE),
            ('published_at', 'timestamp without time zone', 'TIMESTAMP WITHOUT TIME ZONE', FALSE),
            ('curated_at', 'timestamp without time zone', 'TIMESTAMP WITHOUT TIME ZONE', FALSE)
        ) AS expected(column_name, column_type, add_type, required)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_attribute a
             WHERE a.attrelid = training_table AND a.attname = column_spec.column_name
               AND a.attnum > 0 AND NOT a.attisdropped
        ) THEN
            EXECUTE format('ALTER TABLE %I.%I ADD COLUMN %I %s%s',
                current_schema(), 'parser_training_examples', column_spec.column_name,
                column_spec.add_type, CASE WHEN column_spec.required THEN ' NOT NULL' ELSE '' END);
        END IF;
    END LOOP;

    SELECT a.attnum INTO training_id_attnum
      FROM pg_attribute a
     WHERE a.attrelid = training_table AND a.attname = 'id'
       AND a.attnum > 0 AND NOT a.attisdropped;
    SELECT conname, conkey INTO constraint_name, constraint_columns
      FROM pg_constraint WHERE conrelid = training_table AND contype = 'p';
    IF FOUND THEN
        IF constraint_columns <> ARRAY[training_id_attnum]::SMALLINT[] THEN
            RAISE EXCEPTION 'Existing parser_training_examples primary key is incompatible';
        END IF;
    ELSE
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.%I GROUP BY id HAVING count(*) > 1)',
            current_schema(), 'parser_training_examples') INTO duplicate_found;
        IF duplicate_found THEN
            RAISE EXCEPTION 'Existing parser_training_examples contains duplicate IDs; primary key cannot be added safely';
        END IF;
        ALTER TABLE parser_training_examples ADD CONSTRAINT parser_training_examples_pkey PRIMARY KEY (id);
    END IF;

    FOR index_name, index_columns IN
        SELECT * FROM (VALUES
            ('idx_parser_example_status_created', ARRAY['review_status', 'created_at']::TEXT[]),
            ('idx_parser_example_partition_status', ARRAY['dataset_partition', 'review_status']::TEXT[]),
            ('idx_parser_example_batch_property', ARRAY['batch_id', 'property_index']::TEXT[]),
            ('idx_parser_example_hash_status', ARRAY['prompt_hash', 'review_status']::TEXT[])
        ) AS expected(name, columns)
    LOOP
        actual_index_oid := NULL;
        actual_index_table := NULL;
        actual_index_unique := NULL;
        actual_index_valid := NULL;
        actual_index_btree := NULL;
        actual_index_unpredicated := NULL;
        actual_index_without_includes := NULL;
        actual_index_columns := NULL;

        SELECT cls.oid, ix.indrelid, ix.indisunique, ix.indisvalid,
               access_method.amname = 'btree', ix.indpred IS NULL,
               ix.indnatts = ix.indnkeyatts,
               ARRAY(SELECT pg_get_indexdef(ix.indexrelid, position, TRUE)
                       FROM generate_series(1, ix.indnkeyatts) AS position
                      ORDER BY position)
          INTO actual_index_oid, actual_index_table, actual_index_unique, actual_index_valid,
               actual_index_btree, actual_index_unpredicated, actual_index_without_includes,
               actual_index_columns
          FROM pg_class cls
          JOIN pg_namespace ns ON ns.oid = cls.relnamespace
          LEFT JOIN pg_index ix ON ix.indexrelid = cls.oid
          LEFT JOIN pg_am access_method ON access_method.oid = cls.relam
         WHERE ns.nspname = current_schema() AND cls.relname = index_name;

        IF actual_index_oid IS NOT NULL THEN
            IF actual_index_table IS DISTINCT FROM training_table
               OR actual_index_unique IS DISTINCT FROM FALSE
               OR actual_index_valid IS DISTINCT FROM TRUE
               OR actual_index_btree IS DISTINCT FROM TRUE
               OR actual_index_unpredicated IS DISTINCT FROM TRUE
               OR actual_index_without_includes IS DISTINCT FROM TRUE
               OR actual_index_columns IS DISTINCT FROM index_columns THEN
                RAISE EXCEPTION 'Index % exists with an incompatible definition', index_name;
            END IF;
        ELSE
            SELECT string_agg(format('%I', column_name), ', ' ORDER BY ordinality)
              INTO rendered_columns
              FROM unnest(index_columns) WITH ORDINALITY AS columns(column_name, ordinality);
            EXECUTE format('CREATE INDEX %I ON %I.%I (%s)', index_name,
                current_schema(), 'parser_training_examples', rendered_columns);
        END IF;
    END LOOP;

    model_table := to_regclass(format('%I.%I', current_schema(), 'parser_model_versions'));
    IF model_table IS NULL THEN
        CREATE TABLE parser_model_versions (
            id BIGINT GENERATED BY DEFAULT AS IDENTITY,
            model_version VARCHAR(100) NOT NULL,
            artifact_path VARCHAR(500) NOT NULL,
            artifact_checksum VARCHAR(64) NOT NULL,
            dataset_fingerprint VARCHAR(64) NOT NULL,
            metrics_json TEXT NOT NULL,
            holdout_example_count INTEGER NOT NULL,
            decision_reason VARCHAR(500),
            model_status VARCHAR(16) NOT NULL,
            created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
            activated_at TIMESTAMP WITHOUT TIME ZONE,
            CONSTRAINT parser_model_versions_pkey PRIMARY KEY (id),
            CONSTRAINT uk_parser_model_version UNIQUE (model_version)
        );
    END IF;

    model_table := to_regclass(format('%I.%I', current_schema(), 'parser_model_versions'));
    SELECT relkind INTO table_kind FROM pg_class WHERE oid = model_table;
    IF table_kind <> 'r' THEN
        RAISE EXCEPTION 'Existing parser_model_versions is not a regular PostgreSQL table';
    END IF;

    EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.%I)', current_schema(), 'parser_model_versions')
       INTO table_has_rows;

    FOR column_spec IN
        SELECT * FROM (VALUES
            ('id', 'bigint', 'BIGINT GENERATED BY DEFAULT AS IDENTITY', TRUE),
            ('model_version', 'character varying(100)', 'VARCHAR(100)', TRUE),
            ('artifact_path', 'character varying(500)', 'VARCHAR(500)', TRUE),
            ('artifact_checksum', 'character varying(64)', 'VARCHAR(64)', TRUE),
            ('dataset_fingerprint', 'character varying(64)', 'VARCHAR(64)', TRUE),
            ('metrics_json', 'text', 'TEXT', TRUE),
            ('holdout_example_count', 'integer', 'INTEGER', TRUE),
            ('decision_reason', 'character varying(500)', 'VARCHAR(500)', FALSE),
            ('model_status', 'character varying(16)', 'VARCHAR(16)', TRUE),
            ('created_at', 'timestamp without time zone', 'TIMESTAMP WITHOUT TIME ZONE', TRUE),
            ('activated_at', 'timestamp without time zone', 'TIMESTAMP WITHOUT TIME ZONE', FALSE)
        ) AS expected(column_name, column_type, add_type, required)
    LOOP
        SELECT format_type(a.atttypid, a.atttypmod), a.attnotnull
          INTO actual_column_type, actual_column_required
          FROM pg_attribute a
         WHERE a.attrelid = model_table AND a.attname = column_spec.column_name
           AND a.attnum > 0 AND NOT a.attisdropped;

        IF NOT FOUND THEN
            IF column_spec.required AND table_has_rows THEN
                RAISE EXCEPTION 'Cannot add required parser_model_versions.%; existing rows need a product-approved value',
                    column_spec.column_name;
            END IF;
        ELSIF (column_spec.column_name IN ('created_at', 'activated_at')
                   AND actual_column_type <> 'timestamp without time zone')
           OR (column_spec.column_name NOT IN ('created_at', 'activated_at')
                   AND actual_column_type <> column_spec.column_type)
           OR actual_column_required <> column_spec.required THEN
            RAISE EXCEPTION 'Existing parser_model_versions column % is incompatible (type %, NOT NULL %)',
                column_spec.column_name, actual_column_type, actual_column_required;
        END IF;
    END LOOP;

    FOR column_spec IN
        SELECT * FROM (VALUES
            ('id', 'bigint', 'BIGINT GENERATED BY DEFAULT AS IDENTITY', TRUE),
            ('model_version', 'character varying(100)', 'VARCHAR(100)', TRUE),
            ('artifact_path', 'character varying(500)', 'VARCHAR(500)', TRUE),
            ('artifact_checksum', 'character varying(64)', 'VARCHAR(64)', TRUE),
            ('dataset_fingerprint', 'character varying(64)', 'VARCHAR(64)', TRUE),
            ('metrics_json', 'text', 'TEXT', TRUE),
            ('holdout_example_count', 'integer', 'INTEGER', TRUE),
            ('decision_reason', 'character varying(500)', 'VARCHAR(500)', FALSE),
            ('model_status', 'character varying(16)', 'VARCHAR(16)', TRUE),
            ('created_at', 'timestamp without time zone', 'TIMESTAMP WITHOUT TIME ZONE', TRUE),
            ('activated_at', 'timestamp without time zone', 'TIMESTAMP WITHOUT TIME ZONE', FALSE)
        ) AS expected(column_name, column_type, add_type, required)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_attribute a
             WHERE a.attrelid = model_table AND a.attname = column_spec.column_name
               AND a.attnum > 0 AND NOT a.attisdropped
        ) THEN
            IF column_spec.column_name = 'id' THEN
                IF table_has_rows THEN
                    RAISE EXCEPTION 'Cannot add parser_model_versions.id to a populated table';
                END IF;
                EXECUTE format('ALTER TABLE %I.%I ADD COLUMN id BIGINT GENERATED BY DEFAULT AS IDENTITY',
                    current_schema(), 'parser_model_versions');
            ELSE
                EXECUTE format('ALTER TABLE %I.%I ADD COLUMN %I %s%s',
                    current_schema(), 'parser_model_versions', column_spec.column_name,
                    column_spec.add_type, CASE WHEN column_spec.required THEN ' NOT NULL' ELSE '' END);
            END IF;
        END IF;
    END LOOP;

    SELECT a.attnum INTO model_id_attnum
      FROM pg_attribute a
     WHERE a.attrelid = model_table AND a.attname = 'id'
       AND a.attnum > 0 AND NOT a.attisdropped;
    SELECT a.attnum INTO model_version_attnum
      FROM pg_attribute a
     WHERE a.attrelid = model_table AND a.attname = 'model_version'
       AND a.attnum > 0 AND NOT a.attisdropped;

    IF NOT EXISTS (
        SELECT 1 FROM pg_attribute a
         WHERE a.attrelid = model_table AND a.attname = 'id'
           AND a.attidentity IN ('a', 'd')
    ) AND pg_get_serial_sequence(format('%I.%I', current_schema(), 'parser_model_versions'), 'id') IS NULL THEN
        RAISE EXCEPTION 'Existing parser_model_versions.id has no identity/sequence generator';
    END IF;

    SELECT conname, conkey INTO constraint_name, constraint_columns
      FROM pg_constraint WHERE conrelid = model_table AND contype = 'p';
    IF FOUND THEN
        IF constraint_columns <> ARRAY[model_id_attnum]::SMALLINT[] THEN
            RAISE EXCEPTION 'Existing parser_model_versions primary key is incompatible';
        END IF;
    ELSE
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.%I GROUP BY id HAVING count(*) > 1)',
            current_schema(), 'parser_model_versions') INTO duplicate_found;
        IF duplicate_found THEN
            RAISE EXCEPTION 'Existing parser_model_versions contains duplicate IDs; primary key cannot be added safely';
        END IF;
        ALTER TABLE parser_model_versions ADD CONSTRAINT parser_model_versions_pkey PRIMARY KEY (id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = model_table AND contype = 'u'
           AND conkey = ARRAY[model_version_attnum]::SMALLINT[]
    ) THEN
        IF EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = model_table AND conname = 'uk_parser_model_version') THEN
            RAISE EXCEPTION 'Constraint uk_parser_model_version exists with an incompatible definition';
        END IF;
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.%I GROUP BY model_version HAVING count(*) > 1)',
            current_schema(), 'parser_model_versions') INTO duplicate_found;
        IF duplicate_found THEN
            RAISE EXCEPTION 'Existing parser_model_versions contains duplicate model versions; unique constraint cannot be added safely';
        END IF;
        ALTER TABLE parser_model_versions
            ADD CONSTRAINT uk_parser_model_version UNIQUE (model_version);
    END IF;

    index_name := 'idx_parser_model_status_created';
    index_columns := ARRAY['model_status', 'created_at']::TEXT[];
    actual_index_oid := NULL;
    actual_index_table := NULL;
    actual_index_unique := NULL;
    actual_index_valid := NULL;
    actual_index_btree := NULL;
    actual_index_unpredicated := NULL;
    actual_index_without_includes := NULL;
    actual_index_columns := NULL;

    SELECT cls.oid, ix.indrelid, ix.indisunique, ix.indisvalid,
           access_method.amname = 'btree', ix.indpred IS NULL,
           ix.indnatts = ix.indnkeyatts,
           ARRAY(SELECT pg_get_indexdef(ix.indexrelid, position, TRUE)
                   FROM generate_series(1, ix.indnkeyatts) AS position
                  ORDER BY position)
      INTO actual_index_oid, actual_index_table, actual_index_unique, actual_index_valid,
           actual_index_btree, actual_index_unpredicated, actual_index_without_includes,
           actual_index_columns
      FROM pg_class cls
      JOIN pg_namespace ns ON ns.oid = cls.relnamespace
      LEFT JOIN pg_index ix ON ix.indexrelid = cls.oid
      LEFT JOIN pg_am access_method ON access_method.oid = cls.relam
     WHERE ns.nspname = current_schema() AND cls.relname = index_name;

    IF actual_index_oid IS NOT NULL THEN
        IF actual_index_table IS DISTINCT FROM model_table
           OR actual_index_unique IS DISTINCT FROM FALSE
           OR actual_index_valid IS DISTINCT FROM TRUE
           OR actual_index_btree IS DISTINCT FROM TRUE
           OR actual_index_unpredicated IS DISTINCT FROM TRUE
           OR actual_index_without_includes IS DISTINCT FROM TRUE
           OR actual_index_columns IS DISTINCT FROM index_columns THEN
            RAISE EXCEPTION 'Index % exists with an incompatible definition', index_name;
        END IF;
    ELSE
        CREATE INDEX idx_parser_model_status_created
            ON parser_model_versions (model_status, created_at);
    END IF;

    LOCK TABLE parser_model_versions IN ACCESS EXCLUSIVE MODE;
    EXECUTE format('SELECT COALESCE(MAX(id), 0) FROM %I.%I', current_schema(), 'parser_model_versions')
       INTO maximum_model_id;
    model_sequence := pg_get_serial_sequence(
        format('%I.%I', current_schema(), 'parser_model_versions'), 'id');
    IF model_sequence IS NULL THEN
        RAISE EXCEPTION 'parser_model_versions.id has no owned identity/sequence generator';
    END IF;
    SELECT a.attidentity, pg_get_expr(d.adbin, d.adrelid)
      INTO generator_identity, generator_default
      FROM pg_attribute a
      LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
     WHERE a.attrelid = model_table AND a.attname = 'id';
    IF generator_identity IS NULL OR generator_identity NOT IN ('a', 'd') THEN
      IF (
        generator_default IS NULL
        OR generator_default !~ '^nextval[(].+::regclass[)]$'
        OR NOT EXISTS (
            SELECT 1
              FROM pg_attrdef d
              JOIN pg_depend dep ON dep.classid = 'pg_attrdef'::REGCLASS AND dep.objid = d.oid
             WHERE d.adrelid = model_table AND d.adnum = model_id_attnum
               AND dep.refclassid = 'pg_class'::REGCLASS
               AND dep.refobjid = model_sequence::REGCLASS AND dep.refobjsubid = 0
        )
      ) THEN
          RAISE EXCEPTION 'parser_model_versions.id has an incompatible sequence default';
      END IF;
    END IF;
    SELECT seqincrement, seqmin, seqmax, seqcache, seqcycle
      INTO sequence_increment, sequence_min_value, sequence_max_value, sequence_cache_size, sequence_cycles
      FROM pg_sequence WHERE seqrelid = model_sequence::REGCLASS;
    IF sequence_increment IS NULL OR sequence_increment <= 0
       OR sequence_cycles OR sequence_cache_size <> 1 THEN
        RAISE EXCEPTION 'parser_model_versions.id requires a noncycling ascending generator with cache 1';
    END IF;
    EXECUTE format('SELECT last_value, is_called FROM %s', model_sequence)
        INTO sequence_last_value, sequence_called;
    sequence_next_value := sequence_last_value::NUMERIC
        + CASE WHEN sequence_called THEN sequence_increment ELSE 0 END;
    IF sequence_next_value < sequence_min_value OR sequence_next_value > sequence_max_value THEN
        RAISE EXCEPTION 'parser_model_versions.id generator is exhausted or outside its configured bounds';
    END IF;
    IF maximum_model_id >= sequence_max_value THEN
        RAISE EXCEPTION 'parser_model_versions.id generator cannot advance beyond preserved ID %', maximum_model_id;
    END IF;
    IF sequence_next_value <= maximum_model_id THEN
        IF maximum_model_id::NUMERIC + sequence_increment > sequence_max_value THEN
            RAISE EXCEPTION 'parser_model_versions.id generator cannot advance beyond preserved ID %', maximum_model_id;
        END IF;
        PERFORM setval(model_sequence::REGCLASS, maximum_model_id, TRUE);
    END IF;
END
$$;
