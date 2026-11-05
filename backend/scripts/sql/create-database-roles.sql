-- KAN-29: executar como administrador no banco acompanhaai, antes da API.
-- Sem senhas no SQL: definir depois usando \password no psql interativo.
-- O parâmetro expected_database é reservado ao executor isolado de QA.
BEGIN;

DO $$
DECLARE
    expected_database text := COALESCE(
        NULLIF(current_setting('acompanhaai.expected_database', true), ''),
        'acompanhaai'
    );
    role_name text;
    existing_role record;
BEGIN
    IF current_database() <> expected_database THEN
        RAISE EXCEPTION 'Banco diferente do destino esperado; operação cancelada.';
    END IF;
    IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
        RAISE EXCEPTION 'Provisionamento requer uma conexão administrativa.';
    END IF;
    FOREACH role_name IN ARRAY ARRAY['acompanhaai_app', 'acompanhaai_migrator'] LOOP
        SELECT oid, rolsuper, rolcreatedb, rolcreaterole, rolreplication,
            rolbypassrls, rolinherit, rolcanlogin
        INTO existing_role
        FROM pg_roles
        WHERE rolname = role_name;
        IF FOUND THEN
            IF existing_role.rolsuper OR existing_role.rolcreatedb
                OR existing_role.rolcreaterole OR existing_role.rolreplication
                OR existing_role.rolbypassrls OR existing_role.rolinherit
                OR NOT existing_role.rolcanlogin THEN
                RAISE EXCEPTION 'Role % já existe com atributos inesperados; revisar antes de continuar.', role_name;
            END IF;
            IF EXISTS (
                SELECT 1 FROM pg_auth_members
                WHERE member = existing_role.oid OR roleid = existing_role.oid
            ) THEN
                RAISE EXCEPTION 'Role % possui memberships inesperados; revisar antes de continuar.', role_name;
            END IF;
        ELSE
            EXECUTE format(
                'CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD NULL',
                role_name
            );
        END IF;
    END LOOP;
    EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database());
    EXECUTE format(
        'GRANT CONNECT ON DATABASE %I TO acompanhaai_app, acompanhaai_migrator',
        current_database()
    );
END
$$;

-- Schema dedicado ao projeto. Outras aplicações não devem compartilhar este banco.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO acompanhaai_app;
GRANT USAGE, CREATE ON SCHEMA public TO acompanhaai_migrator;

COMMIT;
