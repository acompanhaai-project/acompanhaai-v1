-- Preserve the legacy range and original text. Only an explicit exact BRL
-- amount with a known period can become the single salary; never infer a mean.
ALTER TABLE tb_job ADD COLUMN salary NUMERIC(15,2);

UPDATE tb_job
SET salary = salary_min
WHERE salary_min = salary_max
  AND salary_min > 0
  AND salary_currency = 'BRL'
  AND salary_period IS NOT NULL;

ALTER TABLE tb_job ADD CONSTRAINT ck_job_single_salary CHECK (
    salary IS NULL
    OR (salary > 0 AND salary_currency IS NOT NULL AND salary_currency = 'BRL'
        AND salary_period IS NOT NULL)
);
