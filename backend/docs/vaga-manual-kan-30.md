# Vaga manual — KAN-30

Decisão de produto de 03/11/2026: salário único opcional, somente BRL e
habilidades sem duplicidade. Esta decisão substitui a faixa salarial prevista
originalmente na US03 do plano v1. O plano v1 permanece como registro histórico;
o cartão Jira precisa refletir este contrato antes do aceite.

## Contrato HTTP

Os endpoints existentes permanecem em `/api/applications`: POST para cadastro,
PUT `/{id}` para substituição, GET `/{id}` e GET para consulta/listagem.
Autenticação por sessão e CSRF nas escritas seguem o contrato atual.

Exemplo de cadastro:

```json
{
  "job": {
    "title": "Desenvolvedor Java",
    "companyName": "Empresa de exemplo",
    "description": "Vaga com remuneração de R$ 5.000,25 por mês.",
    "location": "Porto Alegre, RS",
    "workMode": "HYBRID",
    "salary": 5000.25,
    "salaryCurrency": "BRL",
    "salaryPeriod": "MONTH",
    "hardSkills": ["Java", "Spring Boot"],
    "softSkills": ["Comunicação"]
  },
  "status": "APPLIED"
}
```

POST retorna 201. PUT retorna 200 e exige também `status` e `version` no corpo.
O objeto `job` da resposta inclui os mesmos dados e seu `id`, tanto no cadastro
e edição quanto nas consultas e na listagem paginada. Não há acesso automático
ao link nem extração por IA nesta história; os valores são informados manualmente.

## Regras

- Título obrigatório, até 255 caracteres; empresa e localização opcionais até
  255, link até 2048 e descrição até 30000, conforme validações do DTO.
- Modalidade opcional: `REMOTE`, `HYBRID` ou `ONSITE`.
- Salário: decimal exato (`BigDecimal`/`NUMERIC(15,2)`), maior que zero, até 13
  dígitos inteiros e duas casas decimais. Não arredondar entradas inválidas.
- Com salário, informar `salaryCurrency: "BRL"` e uma periodicidade explícita:
  `HOUR`, `DAY`, `WEEK`, `MONTH` ou `YEAR`. Não presumir mês ou converter moedas.
- Sem salário, os três campos de remuneração devem ser nulos ou omitidos.
  Uma faixa, “a combinar” ou ausência de informação permanece na descrição;
  não calcular média, preencher zero nem consultar referências externas.
- Até 50 hard skills e 50 soft skills; cada nome deve ter de 1 a 150 caracteres
  após normalização, inclusive na representação em minúsculas usada no banco.
  Elementos nulos e nomes vazios são inválidos.
- Remover espaços nas extremidades e reduzir espaços repetidos a um único
  espaço. Comparar nomes em minúsculas com `Locale.ROOT`, preservando acentos.
  `Java` e `java` são duplicatas, inclusive quando enviados em tipos diferentes.
- Rejeitar duplicatas com 422; não remover silenciosamente. A resposta preserva
  a grafia normalizada e ordena os nomes dentro de cada tipo.
- PUT substitui os dados opcionais. Omissão ou `null` limpa campos escalares;
  listas omitidas, nulas ou vazias removem as habilidades daquele tipo.
  Ao limpar salário, limpar também moeda e periodicidade.
- Validar antes de alterar entidades. Uma falha não pode deixar vaga, habilidades
  ou histórico parcialmente alterados. Edição apenas dos detalhes não cria
  evento de status. Versão antiga retorna 409.
- A candidatura é sempre localizada pelo ID e pela conta autenticada. Outra
  conta recebe 404 e não pode consultar ou alterar os detalhes.

JSON/enums inválidos retornam 400; violações de validação e de regras de negócio
retornam 422. A API usa o tratamento de erros já existente.

## Persistência e compatibilidade

A V7 adiciona `tb_job.salary`. V1–V6 permanecem intactas. As colunas legadas
`salary_min`, `salary_max` e `salary_original_text` são preservadas.
Somente mínimo e máximo iguais, positivos, em BRL e com periodicidade definida
são copiados para o novo valor. Faixas, limites isolados, outras moedas, zero
ou ausência de periodicidade permanecem com `salary` nulo, sem inferências.

O CRUD passa a usar o novo valor único e não atualiza os limites legados.
`salary_currency` e `salary_period` continuam sendo os campos ativos, e podem
ser substituídos ou limpos em uma edição explícita. A resposta omite seus valores
legados quando o novo salário for nulo. Esta entrega não expõe faixas pela API.

Habilidades usam `tb_job_skill`, com substituição dentro da transação da
candidatura. A edição mantém entradas equivalentes, remove as ausentes e insere
as novas. A validação da API impede duplicatas entre tipos; a restrição legada
do banco continua sendo por vaga, tipo e nome normalizado. Nenhuma habilidade
legada é apagada pela migração.

Payloads antigos, contendo somente os campos básicos, continuam aceitos.
A fixture Postman v1 e suas dependências permanecem congeladas; a regressão
Postman v2 pertence à KAN-33.

## Verificação

- `ManualJobDetailsIntegrationTest`: criação, consulta, listagem, edição,
  remoção, valores opcionais, limites, duplicatas, propriedade e versão antiga.
- `SingleSalaryMigrationIntegrationTest`: atualização V6→V7 em H2 com valores
  exatos, faixas, limites isolados, outra moeda e dados ausentes.
- `DatabaseRoleMigrationIntegrationTest`: atualização e instalação limpa em
  PostgreSQL com migrator separado e leitura pela conta restrita.
- Suíte existente: preservar autenticação, histórico, fotos e recuperação.

Registrar resultados somente após execução. CI remoto, revisão do PR e aceite
do cartão são etapas posteriores à validação local.

## Evidência local — 03/11/2026

Java Corretto 25.0.4.1, Spring Boot 4.0.9 e Maven Wrapper 3.9.16.
Configurações de teste usam cópias dos YAML versionados sem importar arquivos
locais de credenciais. Resumo sanitizado: [resultado local](evidencias/kan-30-validacao-local.json).

| Execução                                             | Resultado                                                                                      |
| ---------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| H2 2.4.240, `verify`                                 | 130 encontrados: 123 aprovados, 7 exclusivos de PostgreSQL ignorados; zero falhas/erros        |
| PostgreSQL 18.6, `./scripts/test-database-roles.ps1` | 2 testes de provisionamento/migração + 127 de regressão aprovados; zero falhas/erros/ignorados |
| Novos cenários HTTP do CRUD                          | 25 aprovados em cada execução                                                                  |
| Fixture Postman v1                                   | 9 hashes SHA-256 conferidos; coleção não executada nesta rodada                                |

O total 129 da execução com PostgreSQL inclui o teste independente de migração
H2 (`SingleSalaryMigrationIntegrationTest`); os outros 128 usam PostgreSQL.
A conta restrita executou o CRUD e a conta migrator aplicou a V7 e o callback.
O cluster temporário em `target` foi encerrado pelo executor.

Para reproduzir o H2, copiar `src/main/resources/application.yml` e
`src/test/resources/application-test.yml` para `target/kan30-h2-config/`, remover
apenas o bloco `spring.config.import` da cópia do primeiro arquivo e executar:

```powershell
./mvnw.cmd --batch-mode --no-transfer-progress verify "-Dspring.config.location=file:./target/kan30-h2-config/" "-DargLine=-Xms32m -Xmx384m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=96m"
```

O teste de migração H2 usa Hikari para manter as sessões físicas abertas durante
o teste, evitando o problema de CHECK com sessão encerrada do H2 2.4.240.
As restrições das migrações históricas não foram alteradas.

| Critério atualizado                                                  | Cobertura                                                                                                             |
| -------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| Receber e retornar detalhes no cadastro, edição, consulta e listagem | `persistsDetailsAndReturnsThemOnCreateGetAndList`, `replacesSkillsReusesUnchangedNamesAndCanClearAllDetails`          |
| Salário único, BRL, periodicidade e ausência como null               | `acceptsOldPayloadAndLeavesUnknownSalaryNull`, casos parametrizados `rejectsInvalidDetailsWithoutCreatingPartialData` |
| Tamanhos, precisão e habilidades sem duplicidade                     | Casos parametrizados de `invalidDetails`, incluindo duplicatas entre tipos e caixa/espaços                            |
| Isolamento por conta e remoção de habilidades                        | `otherAccountCannotReadOrChangeDetailsAndDeletionRemovesSkills`                                                       |
| Consistência em edição inválida, versão antiga e conflito de URL     | `invalidEditAndStaleVersionPreserveSalarySkillsAndHistory`, `conflictingUrlEditPreservesJobSkillsAndStatusHistory`    |
| Preservação de dados e migração limpa                                | `SingleSalaryMigrationIntegrationTest` e `DatabaseRoleMigrationIntegrationTest`                                       |

A revisão do diff, o CI remoto e o aceite da história permanecem pendentes.
