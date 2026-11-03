-- Carregado somente quando a configuração de identidades separadas o habilita.
-- Novas tabelas devem ser incluídas explicitamente; sem GRANT amplo para o futuro.
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    public.tb_user,
    public.tb_user_photo,
    public.tb_job,
    public.tb_job_skill,
    public.tb_application,
    public.tb_application_status_history,
    public.tb_import_task,
    public.tb_password_reset_token
TO acompanhaai_app;

REVOKE ALL ON TABLE public.flyway_schema_history FROM PUBLIC, acompanhaai_app;

DO $$
DECLARE
    table_name text;
    sequence_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'tb_user', 'tb_job', 'tb_job_skill', 'tb_application',
        'tb_application_status_history', 'tb_import_task', 'tb_password_reset_token'
    ] LOOP
        sequence_name := pg_get_serial_sequence(format('public.%I', table_name), 'id');
        IF sequence_name IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON SEQUENCE %s FROM PUBLIC, acompanhaai_app', sequence_name);
            EXECUTE format('GRANT USAGE ON SEQUENCE %s TO acompanhaai_app', sequence_name);
        END IF;
    END LOOP;
END
$$;
