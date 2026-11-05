DO $guard$
BEGIN
    IF session_user <> 'acompanhaai_qa_admin' OR '__RUN_ID__' !~ '^[a-f0-9]{32}$' THEN
        RAISE EXCEPTION 'Sentinela exclusiva da QA descartavel.';
    END IF;
END;
$guard$;

INSERT INTO tb_user(name, email, password_hash, auth_version, created_at)
VALUES ('Sentinela QA KAN33', 'qa.kan33.sentinel+__RUN_ID__@example.test', 'hash-ficticio-sem-login', 0, CURRENT_TIMESTAMP);
INSERT INTO tb_job(user_id, title)
SELECT id, 'Vaga sentinela preservada' FROM tb_user WHERE email = 'qa.kan33.sentinel+__RUN_ID__@example.test';
INSERT INTO tb_application(user_id, job_id, status, notes, created_at, updated_at, version)
SELECT user_id, id, 'SAVED', 'Sentinela preservada', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM tb_job WHERE user_id = (SELECT id FROM tb_user WHERE email = 'qa.kan33.sentinel+__RUN_ID__@example.test');
INSERT INTO tb_application_status_history(application_id, to_status, changed_at)
SELECT id, 'SAVED', CURRENT_TIMESTAMP FROM tb_application
WHERE user_id = (SELECT id FROM tb_user WHERE email = 'qa.kan33.sentinel+__RUN_ID__@example.test');
INSERT INTO tb_job_skill(job_id, name, normalized_name, type)
SELECT id, 'Java sentinela', 'java sentinela', 'HARD' FROM tb_job
WHERE user_id = (SELECT id FROM tb_user WHERE email = 'qa.kan33.sentinel+__RUN_ID__@example.test');
INSERT INTO tb_import_task(user_id, job_id, input_type, input_text, status, attempt_count, created_at, updated_at)
SELECT user_id, id, 'TEXT', 'Sentinela de importacao', 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM tb_job WHERE user_id = (SELECT id FROM tb_user WHERE email = 'qa.kan33.sentinel+__RUN_ID__@example.test');
