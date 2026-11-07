# KAN-40 — Jornadas de acesso no navegador

## Execução — 06/11/2026

- Base: commit `9edcbb1`, com a suíte adicionada na branch
  `test/KAN-40-jornadas-acesso`.
- Java 25.0.4.1, Spring Boot 4.0.9 e Selenium 4.52.0.
- Microsoft Edge 156.0.4314.58 em modo headless.
- PostgreSQL 18.6 em contêiner exclusivo, com migrações V1 a V7.
- GreenMail 2.1.15: SMTP real capturado em memória, ligado ao loopback.
- Angular real, proxy e API em portas próprias; sem mocks de endpoints.
- Viewports de 1440 e 390 pixels, temas claro e escuro.
- Contas exclusivas com UUID e domínio `example.test`.

Comando executado na raiz, no PowerShell:

```powershell
./qa/browser/run.ps1
```

Resultado: **8 testes, 0 falhas, 0 erros e 0 ignorados**. A suíte levou
aproximadamente 152,6 segundos; o comando Maven completo levou 3min04s.
A execução final não apresentou o aviso de incompatibilidade CDP observado
na primeira versão da dependência Selenium.

Resumo sanitizado: [kan-40-jornadas-acesso.json](kan-40-jornadas-acesso.json).

Também foi executado, em `backend/`, sem o perfil Selenium:

```powershell
./mvnw.cmd test "-Dtest=AccountSessionHttpIntegrationTest" "-Dspring.config.import="
```

Os dois testes HTTP de sessão passaram, sem iniciar navegador. Não foi
reexecutada a suíte completa do backend nem os testes Angular nesta entrega.

## Matriz de cobertura

| Critério / cenário | Evidência automatizada | Resultado |
| --- | --- | --- |
| Cadastro com dados exclusivos | `accountLifecycle`, nas quatro combinações | Aprovado |
| Cadastro duplicado, mensagem e foco no e-mail | `accountLifecycle` | Aprovado |
| Senha incorreta, limpeza do campo e nova tentativa | `accountLifecycle` | Aprovado |
| Login com retorno à rota privada, query e fragmento | `accountLifecycle` | Aprovado |
| Cookie HttpOnly e ausência de identidade/tokens em storage | `accountLifecycle` | Aprovado |
| Restauração após recarga e acesso ao login já autenticado | `accountLifecycle` | Aprovado |
| Sessão invalidada por logout em outra aba | `accountLifecycle`, seguido de recarga da primeira aba | Aprovado |
| Solicitação de recuperação, e-mail SMTP e cooldown visível | `accountLifecycle` | Aprovado |
| Redefinição, revogação da sessão anterior e senha antiga rejeitada | `accountLifecycle` | Aprovado |
| Nova senha aceita e token reutilizado recusado | `accountLifecycle` | Aprovado |
| Primeiro campo inválido, foco, mensagens e ARIA | `keyboardAndFieldErrors`, nas quatro combinações | Aprovado |
| Tab entre campos, Enter no login e Space para mostrar senha | `keyboardAndFieldErrors` | Aprovado |
| Confirmação de senha divergente, e-mail inválido e link ausente | `keyboardAndFieldErrors` | Aprovado |
| Ausência de rolagem horizontal em desktop/mobile | Ambos os métodos | Aprovado |
| Consultar/editar perfil, trocar senha com senha atual e excluir conta | Depende da interface KAN-37 | Pendente |
| Enviar, substituir e remover foto, preview e avatar padrão | Depende da interface KAN-37 | Pendente |

## Artefatos e revisão

O executor grava relatórios JUnit em `backend/target/surefire-reports/` e oito
screenshots em `backend/target/browser-qa/`, dos estados autenticado e link
inválido, sem senhas preenchidas. Capturas de desktop claro e erro em tema
escuro também foram inspecionadas visualmente. Os diretórios são ignorados
pelo Git; a evidência versionada contém somente o resumo e esta matriz.

O SMTP não encaminha mensagens externas. Token e conteúdo do e-mail permanecem
na memória do processo de teste. O resumo não inclui senhas, cookies, tokens,
mensagens SMTP nem dumps de DOM. Frontend e SMTP de QA são encerrados ao final;
o contêiner exclusivo é removido pelo executor.

Não foi identificado novo defeito funcional nos cenários executados. O ajuste
de HTTP/1.1 da checagem de prontidão e a atualização do Selenium foram correções
da infraestrutura de teste, sem alteração das telas ou da lógica de produção.

## Limites e aceite

- A sessão foi invalidada por logout e redefinição. Não foi aguardado o
  vencimento real de 30 minutos por inatividade.
- A viewport mobile usa Edge emulado, sem dispositivo físico ou navegador móvel.
- Teclado e foco foram exercitados nos cenários da matriz; isso não representa
  auditoria completa com leitores de tela ou de acessibilidade.
- Reenvio após o cooldown, rate limiting e falhas de rede têm evidências
  anteriores e não são reexecutados nesta suíte.
- O banco usa uma identidade descartável de QA; esta execução não substitui
  os testes específicos de isolamento dos papéis do PostgreSQL.
- SMTP externo, staging e produção não foram validados.
- O modo H2 descrito no guia é uma alternativa; o resultado registrado aqui
  corresponde à execução com PostgreSQL.

**KAN-40 permanece parcialmente atendida:** automação dos fluxos de acesso
implementada; perfil/foto dependem da KAN-37 e o aceite final continua pendente.
Nenhum cartão foi encerrado automaticamente.

Procedimento e localização dos fontes: [guia Selenium](../../qa/browser/README.md).
