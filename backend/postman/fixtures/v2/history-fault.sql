-- Aplicar somente no cluster descartável criado por test-postman-v2.ps1.
-- O executor substitui __RUN_ID__ por 32 caracteres hexadecimais validados.
DO $guard$
BEGIN
    IF session_user <> 'acompanhaai_qa_admin'
       OR '__RUN_ID__' !~ '^[a-f0-9]{32}$' THEN
        RAISE EXCEPTION 'Hook exclusivo da QA descartavel, com runId resolvido pelo executor.';
    END IF;
END;
$guard$;

CREATE FUNCTION public.kan33_history_failure() RETURNS trigger
LANGUAGE plpgsql AS $function$
BEGIN
    IF EXISTS (
        SELECT 1 FROM public.tb_application a
        JOIN public.tb_user u ON u.id = a.user_id
        WHERE a.id = NEW.application_id
          AND u.email = 'qa.kan33.a+__RUN_ID__@example.test'
          AND a.notes = 'KAN33_SIMULATED_HISTORY_FAILURE'
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Falha de historico simulada pela QA KAN-33';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE TRIGGER kan33_history_failure
BEFORE INSERT ON public.tb_application_status_history
FOR EACH ROW EXECUTE FUNCTION public.kan33_history_failure();
