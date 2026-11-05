BEGIN READ ONLY;
SELECT count(*) FROM tb_user WHERE id IN (__A_ID__, __B_ID__);
SELECT count(*) FROM tb_job WHERE user_id IN (__A_ID__, __B_ID__);
SELECT count(*) FROM tb_application WHERE user_id IN (__A_ID__, __B_ID__);
SELECT count(*) FROM tb_import_task WHERE user_id IN (__A_ID__, __B_ID__);
SELECT count(*) FROM tb_user_photo WHERE user_id IN (__A_ID__, __B_ID__);
SELECT count(*) FROM tb_password_reset_token WHERE user_id IN (__A_ID__, __B_ID__);
SELECT count(*) FROM tb_user WHERE email = 'qa.kan33.sentinel+__RUN_ID__@example.test' AND name = 'Sentinela QA KAN33';
SELECT count(*) FROM tb_job WHERE user_id = (SELECT id FROM tb_user WHERE email = 'qa.kan33.sentinel+__RUN_ID__@example.test')
AND title = 'Vaga sentinela preservada';
SELECT count(*) FROM tb_application WHERE user_id = (SELECT id FROM tb_user WHERE email = 'qa.kan33.sentinel+__RUN_ID__@example.test')
AND status = 'SAVED' AND notes = 'Sentinela preservada';
SELECT count(*) FROM tb_application_status_history h JOIN tb_application a ON a.id = h.application_id
JOIN tb_user u ON u.id = a.user_id WHERE u.email = 'qa.kan33.sentinel+__RUN_ID__@example.test';
SELECT count(*) FROM tb_job_skill s JOIN tb_job j ON j.id = s.job_id
JOIN tb_user u ON u.id = j.user_id WHERE u.email = 'qa.kan33.sentinel+__RUN_ID__@example.test';
SELECT count(*) FROM tb_import_task WHERE user_id = (SELECT id FROM tb_user WHERE email = 'qa.kan33.sentinel+__RUN_ID__@example.test');
ROLLBACK;
