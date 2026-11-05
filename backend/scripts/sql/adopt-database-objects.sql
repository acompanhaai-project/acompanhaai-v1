-- KAN-29: executar como administrador após create-database-roles.sql.
-- Adota somente as tabelas conhecidas; não usa REASSIGN OWNED nem recria dados.
BEGIN;

DO $$
DECLARE
    expected_database text := COALESCE(
        NULLIF(current_setting('acompanhaai.expected_database', true), ''),
        'acompanhaai'
    );
    allowed_tables text[] := ARRAY[
        'flyway_schema_history', 'tb_user', 'tb_user_photo', 'tb_job',
        'tb_job_skill', 'tb_application', 'tb_application_status_history',
        'tb_import_task', 'tb_password_reset_token'
    ];
    object_record record;
    sequence_name text;
BEGIN
    IF current_database() <> expected_database THEN
        RAISE EXCEPTION 'Banco diferente do destino esperado; operação cancelada.';
    END IF;
    IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
        RAISE EXCEPTION 'Adoção requer conexão administrativa.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'acompanhaai_app')
        OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'acompanhaai_migrator') THEN
        RAISE EXCEPTION 'Execute primeiro create-database-roles.sql.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_class AS relation
        JOIN pg_namespace AS schema ON schema.oid = relation.relnamespace
        WHERE schema.nspname = 'public'
            AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
            AND NOT relation.relname = ANY(allowed_tables)
    ) THEN
        RAISE EXCEPTION 'Objeto desconhecido em public; revisar o escopo antes de alterar permissões.';
    END IF;

    FOR object_record IN
        SELECT relation.oid, relation.relname, pg_get_userbyid(relation.relowner) AS owner_name
        FROM pg_class AS relation
        JOIN pg_namespace AS schema ON schema.oid = relation.relnamespace
        WHERE schema.nspname = 'public'
            AND relation.relkind IN ('r', 'p')
            AND relation.relname = ANY(allowed_tables)
    LOOP
        IF object_record.owner_name NOT IN (current_user, 'postgres', 'acompanhaai_migrator') THEN
            RAISE EXCEPTION 'Proprietário inesperado de %; revisar manualmente.', object_record.relname;
        END IF;
        -- O PostgreSQL acompanha a troca de proprietário nas sequências vinculadas.
        EXECUTE format('ALTER TABLE public.%I OWNER TO acompanhaai_migrator', object_record.relname);
        EXECUTE format('REVOKE ALL ON public.%I FROM PUBLIC, acompanhaai_app', object_record.relname);
        IF object_record.relname <> 'flyway_schema_history' THEN
            EXECUTE format(
                'GRANT SELECT, INSERT, UPDATE, DELETE ON public.%I TO acompanhaai_app',
                object_record.relname
            );
        END IF;
        IF EXISTS (
            SELECT 1 FROM pg_attribute
            WHERE attrelid = object_record.oid AND attname = 'id' AND NOT attisdropped
        ) THEN
            sequence_name := pg_get_serial_sequence(format('public.%I', object_record.relname), 'id');
            IF sequence_name IS NOT NULL THEN
                EXECUTE format('REVOKE ALL ON SEQUENCE %s FROM PUBLIC, acompanhaai_app', sequence_name);
                EXECUTE format('GRANT USAGE ON SEQUENCE %s TO acompanhaai_app', sequence_name);
            END IF;
        END IF;
    END LOOP;
END
$$;

COMMIT;
