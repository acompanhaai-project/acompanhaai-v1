# Mock de teste v1 — congelado, sem imagem

Esta pasta representa o contrato observado em 28/10/2026. E um cenario de dados ficticios e testes executados contra a API real. Nao substitui o backend por um servidor simulado e nao popula o banco ao iniciar o Spring Boot.

## Arquivos

- `acompanhaai-v1.postman_collection.json`: colecao Postman 2.1 com 90 requisicoes e assercoes automaticas.
- `dados.json`: nomes, senhas ficticias e oito candidaturas usadas na colecao. Serve como documentacao; a colecao ja incorpora estes dados e pode ser importada sozinha.
- `run.cjs`: executor com Newman, restrito a HTTP em localhost/127.0.0.1, e verificacao de integridade.
- `sha256.json`: hashes dos arquivos congelados.
- `AGENTS.md`: instrucao para preservar esta versao em futuras alteracoes.

Nenhum endpoint de foto e chamado. Nenhum arquivo de imagem e criado ou enviado. O teste verifica apenas que uma conta nova tem `photoUrl: null`.

## Dados ficticios

| Campo | Valor |
|---|---|
| Usuario A | Ana Teste V1 |
| Email A inicial | `qa.ana.v1+<runId>@example.test` |
| Email A apos edicao | `qa.ana.editada.v1+<runId>@example.test` |
| Usuario B | Bruno Teste V1 |
| Email B | `qa.bruno.v1+<runId>@example.test` |
| Senha inicial dos dois | `MockV1!Senha2026` |
| Nova senha de A, durante o teste completo | `MockV1!NovaSenha2026` |
| Data das inscricoes | `2026-01-15` |
| Data em SAVED | `null` |

As senhas acima sao publicas e exclusivamente ficticias. Nao sao as credenciais do PostgreSQL. O executor nao le `application-local.properties`, nao usa JDBC e nao solicita a senha do banco.

Cada execucao da preparacao cria um `runId` novo (horario mais sufixo aleatorio), incorporado aos emails. Os dados-base continuam iguais; o sufixo evita colisoes com outros testes. IDs sao recebidos da API, nunca fixados ou presumidos. Links `example.com` sao ficticios e nao sao acessados.

| Status | Vaga da conta A | Empresa ficticia |
|---|---|---|
| SAVED | Desenvolvedor Java Junior | Empresa Aurora Ficticia |
| APPLIED | Backend Java e Spring Boot | Empresa Horizonte Ficticia |
| SCREENING | Analista de Sistemas Java | Empresa Nuvem Ficticia |
| INTERVIEW | Desenvolvedor Backend Pleno | Empresa Ponte Ficticia |
| OFFER | Engenheiro de Software Java | Empresa Farol Ficticia |
| HIRED | Desenvolvedor de APIs REST | Empresa Jardim Ficticia |
| REJECTED | Desenvolvedor Java Trainee | Empresa Trilho Ficticia |
| WITHDRAWN | Analista Desenvolvedor Backend | Empresa Vento Ficticia |

A conta B recebe uma candidatura SAVED com o mesmo link da primeira vaga de A, para comprovar isolamento e deduplicacao por usuario. A preparacao cria duas contas e nove candidaturas no total. Descricao: Java 25, Spring Boot, SQL, comunicacao e trabalho em equipe. Salario e habilidades estruturadas ainda nao fazem parte do CRUD manual e nao sao simulados por campos ignorados.

## Executar no Postman

1. Inicie o backend usando o PostgreSQL local. Para sessoes por HTTP, use o perfil `postgres-local`.
2. Importe esta colecao e `../../local.postman_environment.json` no Postman.
3. Selecione o ambiente **Acompanhaai - Local** (`baseUrl=http://localhost:8080`).
4. Use **Run collection**, uma iteracao, respeitando a ordem das tres pastas.
5. Confira os resultados na aba de testes do Runner.

As requisicoes controlam cookies JSESSIONID e tokens CSRF separadamente para A, B e a segunda sessao de A. O cookie jar esta desativado em cada requisicao para evitar misturar contas. Login renova a sessao e e seguido de nova consulta de CSRF. Nao e preciso copiar tokens manualmente.

Para apenas preencher o banco e explorar os dados, execute somente **01 - Preparacao**. Isso mantem as duas contas e nove candidaturas. A senha de ambos nesse ponto e a senha inicial. Anote os emails completos nas variaveis do ambiente, pois incluem `runId`. Ao usar o Runner do Postman, habilite a opcao de manter os valores atualizados das variaveis se quiser executar a limpeza posteriormente. Nao execute a preparacao novamente para limpar: isso gera outras contas.

## Executar pelo terminal

Na raiz do projeto, com Node.js 22 e o backend em execucao:

```powershell
npm.cmd --prefix postman ci --no-audit --no-fund
npm.cmd --prefix postman run test:v1
```

Para manter os dados e testar manualmente:

```powershell
npm.cmd --prefix postman run test:v1 -- --seed
```

Os emails efetivos aparecem no resumo e em `postman/reports/last-run.summary.json`. Para limpar as contas dessa execucao:

```powershell
npm.cmd --prefix postman run test:v1 -- --cleanup
```

Outra porta local pode ser informada sem editar a v1:

```powershell
npm.cmd --prefix postman run test:v1 -- --base-url http://localhost:18082
```

O teste completo executa **01 - Preparacao**, **02 - Validacoes** e **99 - Limpeza**. As validacoes alteram o nome/email de A, percorrem as etapas, removem uma candidatura e trocam sua senha. Ao terminar, ambas as contas sao excluidas; o backend remove seus dados associados por cascata. A limpeza exige identificadores registrados na execucao e emails com prefixo `qa.` e dominio `example.test`.

Se o processo for interrompido, podera haver dados ficticios restantes. O executor salva o ambiente ao concluir a execucao; a limpeza isolada depende de `postman/reports/last-run.environment.json`. Preserve esse arquivo ate limpar o cenario. Cada preparacao nova substitui o arquivo da ultima execucao; limpe o cenario anterior antes de criar outro.

## Cobertura

- Cadastro, normalizacao do email, email duplicado, campos invalidos e JSON malformado.
- Login correto/incorreto, renovacao de sessao, protecao CSRF e acesso anonimo.
- Consulta e edicao do perfil; conflito de email entre contas.
- Cadastro manual de todos os oito estados, consulta, filtro, paginacao e pagina vazia.
- Isolamento por proprietario na listagem, consulta, historico, atualizacao e exclusao.
- Link duplicado por usuario, data futura, data indevida em SAVED, titulo vazio e status desconhecido.
- Mudancas de etapas, historico em ordem, ausencia de eventos para edicao simples e conflito de versao antiga.
- Exclusao de candidatura, troca de senha com validacao da senha atual, revogacao de duas sessoes e logout.
- Exclusao das contas criadas e rejeicao de novo login dessas contas.
- Respostas sem as senhas ficticias em texto.

O teste via API nao substitui testes de concorrencia real, carga, recuperacao de senha, IA ou verificacao direta de cascatas no banco. Fotos foram excluidas deste cenario por solicitacao. A verificacao de integridade detecta alteracoes acidentais; nao e uma assinatura digital nem bloqueio fisico de arquivos.

## Regra de preservacao

Nao editar os arquivos desta pasta para acompanhar futuras mudancas do backend. Criar `v2` quando houver um novo contrato. Uma alteracao incompatível da API pode fazer a v1 falhar; preserve esse resultado como evidencia, sem adaptar as expectativas antigas. Apenas o ambiente externo e os relatorios variam entre execucoes.
