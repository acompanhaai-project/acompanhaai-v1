-- KAN-29: executar no banco acompanhaai, pelo Query Tool do pgAdmin.
-- Consulta somente metadados. Não lê dados da aplicação nem hashes de senha.
-- Use uma conexão já autenticada; não inclua credenciais neste arquivo.

BEGIN READ ONLY;

-- 1. Identificação da conexão usada nesta auditoria.
-- O usuário do pgAdmin pode ser diferente do usuário utilizado pela API.
SELECT
    current_database() AS database_name,
    session_user AS login_role,
    current_user AS effective_role,
    inet_server_addr() AS server_address,
    inet_server_port() AS server_port,
    current_setting('server_version') AS postgres_version;

-- 2. Roles próprias da instalação, sem consultar rolpassword ou pg_authid.
SELECT
    rolname,
    rolcanlogin,
    rolsuper,
    rolcreatedb,
    rolcreaterole,
    rolreplication,
    rolbypassrls,
    rolinherit
FROM pg_roles
WHERE rolname !~ '^pg_'
ORDER BY rolname;

-- 3. Vínculos de roles: uma conta restrita pode herdar poderes de outra.
SELECT
    member_role.rolname AS member_role,
    granted_role.rolname AS granted_role,
    membership.admin_option,
    membership.inherit_option,
    membership.set_option
FROM pg_auth_members AS membership
JOIN pg_roles AS member_role ON member_role.oid = membership.member
JOIN pg_roles AS granted_role ON granted_role.oid = membership.roleid
WHERE member_role.rolname !~ '^pg_'
ORDER BY member_role.rolname, granted_role.rolname;

-- 4. Proprietário do banco e permissões efetivas dos logins.
SELECT
    role.rolname,
    database.datname,
    pg_get_userbyid(database.datdba) AS database_owner,
    has_database_privilege(role.oid, database.oid, 'CONNECT') AS can_connect,
    has_database_privilege(role.oid, database.oid, 'CREATE') AS can_create_schema,
    has_database_privilege(role.oid, database.oid, 'TEMP') AS can_create_temp
FROM pg_roles AS role
CROSS JOIN pg_database AS database
WHERE role.rolcanlogin
    AND role.rolname !~ '^pg_'
    AND database.datname = current_database()
ORDER BY role.rolname;

-- 5. Permissões de criação nos schemas da aplicação.
SELECT
    role.rolname,
    schema.nspname AS schema_name,
    pg_get_userbyid(schema.nspowner) AS schema_owner,
    has_schema_privilege(role.oid, schema.oid, 'USAGE') AS can_use_schema,
    has_schema_privilege(role.oid, schema.oid, 'CREATE') AS can_create_objects
FROM pg_roles AS role
CROSS JOIN pg_namespace AS schema
WHERE role.rolcanlogin
    AND role.rolname !~ '^pg_'
    AND schema.nspname !~ '^pg_'
    AND schema.nspname <> 'information_schema'
ORDER BY role.rolname, schema.nspname;

-- 6. Permissões efetivas nas tabelas, incluindo histórico Flyway.
-- As funções has_* consideram concessões herdadas e PUBLIC.
-- Propriedade também precisa ser analisada: não equivale a um simples GRANT.
SELECT
    role.rolname,
    schema.nspname AS schema_name,
    relation.relname AS table_name,
    pg_get_userbyid(relation.relowner) AS table_owner,
    pg_has_role(role.oid, relation.relowner, 'USAGE') AS owner_role_available,
    has_table_privilege(role.oid, relation.oid, 'SELECT') AS can_select,
    has_table_privilege(role.oid, relation.oid, 'INSERT') AS can_insert,
    has_table_privilege(role.oid, relation.oid, 'UPDATE') AS can_update,
    has_table_privilege(role.oid, relation.oid, 'DELETE') AS can_delete,
    has_table_privilege(role.oid, relation.oid, 'TRUNCATE') AS can_truncate
FROM pg_roles AS role
CROSS JOIN pg_class AS relation
JOIN pg_namespace AS schema ON schema.oid = relation.relnamespace
WHERE role.rolcanlogin
    AND role.rolname !~ '^pg_'
    AND relation.relkind IN ('r', 'p')
    AND schema.nspname !~ '^pg_'
    AND schema.nspname <> 'information_schema'
ORDER BY role.rolname, schema.nspname, relation.relname;

-- 7. Permissões nas sequências usadas para gerar identificadores.
SELECT
    role.rolname,
    schema.nspname AS schema_name,
    sequence.relname AS sequence_name,
    pg_get_userbyid(sequence.relowner) AS sequence_owner,
    has_sequence_privilege(role.oid, sequence.oid, 'USAGE') AS can_use_sequence,
    has_sequence_privilege(role.oid, sequence.oid, 'SELECT') AS can_read_sequence,
    has_sequence_privilege(role.oid, sequence.oid, 'UPDATE') AS can_update_sequence
FROM pg_roles AS role
CROSS JOIN pg_class AS sequence
JOIN pg_namespace AS schema ON schema.oid = sequence.relnamespace
WHERE role.rolcanlogin
    AND role.rolname !~ '^pg_'
    AND sequence.relkind = 'S'
    AND schema.nspname !~ '^pg_'
    AND schema.nspname <> 'information_schema'
ORDER BY role.rolname, schema.nspname, sequence.relname;

-- 8. Concessões padrão para objetos futuros; grantee = 0 significa PUBLIC.
SELECT
    pg_get_userbyid(default_acl.defaclrole) AS object_creator,
    COALESCE(schema.nspname, '(todos os schemas)') AS schema_name,
    default_acl.defaclobjtype AS object_type,
    CASE
        WHEN privilege.grantee = 0 THEN 'PUBLIC'
        ELSE pg_get_userbyid(privilege.grantee)
    END AS grantee,
    privilege.privilege_type,
    privilege.is_grantable
FROM pg_default_acl AS default_acl
LEFT JOIN pg_namespace AS schema ON schema.oid = default_acl.defaclnamespace
CROSS JOIN LATERAL aclexplode(default_acl.defaclacl) AS privilege
ORDER BY object_creator, schema_name, object_type, grantee;

ROLLBACK;
