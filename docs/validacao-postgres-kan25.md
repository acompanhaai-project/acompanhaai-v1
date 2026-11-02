# Validação PostgreSQL — KAN-25 — 02/11/2026

## Ambiente isolado

PostgreSQL 18.6 instalado no Windows, executado em instância temporária
no loopback 127.0.0.1:55432. Dados dentro de target/kan25-postgres/data,
fora do versionamento. Banco acompanhaai_kan25_clean; usuário exclusivo de
teste acompanhaai_qa. Autenticação trust somente nessa instância temporária
local. Essa configuração não deve ser usada em produção.

O banco habitual na porta 5433 e suas credenciais não foram acessados.
Ao terminar, a instância temporária foi encerrada. Seus arquivos permaneceram
em target, e os relatórios PostgreSQL foram copiados para target/kan25-postgres-reports.

## Migrações e regressão

```bash
./mvnw.cmd --batch-mode --no-transfer-progress verify "-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:55432/acompanhaai_kan25_clean" "-Dspring.datasource.username=acompanhaai_qa" "-Dspring.datasource.password="
```

```text
Tests run: 84, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 02:20 min
Finished at: 2026-11-02T03:54:14-03:00
```

- Banco vazio: Flyway aplicou V1 a V6, sem edição das migrações anteriores.
- Hibernate validou o schema PostgreSQL real.
- Histórico Flyway confirmou versões 1–6 com success=true.
- Restrições de unicidade, expiração, hash, relacionamento e exclusão foram
  exercitadas pelo conjunto de testes no PostgreSQL.
- O fluxo HTTP → SMTP GreenMail → token → nova senha → login passou usando
  PostgreSQL, sem provedor externo nem destinatários reais.

### Atualização de V5 com dados existentes

[PostgresMigrationIntegrationTest](../src/test/java/com/maiconamaral/acompanhaai/PostgresMigrationIntegrationTest.java)
é habilitado somente quando spring.datasource.url aponta para PostgreSQL.
Cria um schema aleatório exclusivo, aplica V1–V5 e insere usuário, vaga e
candidatura em entrevista. Aplica somente V6, valida checksums e verifica
preservação de nome, e-mail, hash de senha, versão de autenticação, etapa,
notas e versão da candidatura. A nova tabela de tokens inicia vazia.
Uma nova chamada migrate executa zero migrações. O schema exclusivo do teste
é removido ao final; public não é removido.

O teste não demonstra restauração de backup nem migração de toda possível
base legada de produção. Apenas os dados e a transição descritos foram verificados.

### Concorrência e atomicidade

Os testes de Service foram executados com conexões PostgreSQL reais:

- Duas emissões simultâneas para a mesma conta resultam em uma emissão.
- Duas redefinições simultâneas com o mesmo token resultam em um sucesso;
  authVersion incrementa uma vez e a senha corresponde à tentativa vencedora.
- Rollback restaura token, senha, authVersion e tokens adicionais.
- O teste do limitador também confirma sua cota sob solicitações concorrentes.

Não foi executado teste de carga; estes são cenários funcionais concorrentes.

## CI preparado

O workflow backend-ci.yml mantém backend-build-test com H2 e adiciona
backend-postgres-test com serviço postgres:18 descartável. Ambos executam verify
e disponibilizam relatórios próprios. GreenMail roda embutido, sem Mailpit ou
credenciais SMTP. A senha no serviço CI é um valor de teste de vida curta.
O teste V5→V6 é ignorado em H2 e obrigatório na execução PostgreSQL.

Resultado local não equivale a CI verde. A abertura do PR, execução no GitHub
Actions, revisão e merge permanecem pendentes até evidência remota.

## Regressão final em H2

```bash
./mvnw.cmd --batch-mode --no-transfer-progress verify "-Dspring.datasource.url=jdbc:h2:mem:acompanhaai-kan25-final;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" "-Dspring.datasource.username=sa" "-Dspring.datasource.password="
```

```text
Tests run: 84, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
Total time: 01:52 min
Finished at: 2026-11-02T03:57:28-03:00
```

São 83 testes executados com sucesso e um ignorado intencionalmente:
PostgresMigrationIntegrationTest, exclusivo de PostgreSQL. Não há falhas.
