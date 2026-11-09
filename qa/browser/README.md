# Jornadas de acesso e perfil no navegador — KAN-40

Suíte Selenium WebDriver/Java com JUnit, API Spring Boot real, Angular real e
SMTP capturado pelo GreenMail. A suíte cobre o acesso da KAN-36 e o perfil da
KAN-37 com requisições reais.

## Executar no Windows com PostgreSQL descartável

Pré-requisitos: Java 25, Node.js compatível com o frontend, Microsoft Edge e
Docker Desktop em execução. Instalar as dependências do frontend com `npm ci`
em `frontend/` antes da primeira execução. O Selenium Manager obtém o driver
compatível; a primeira execução pode precisar de acesso à rede.

Na raiz do projeto, pelo PowerShell:

```powershell
./qa/browser/run.ps1
```

Para usar um Chrome ou Chromium já disponível, defina
`ACOMPANHAAI_QA_CHROME_BINARY` com o caminho absoluto do executável antes de
rodar o comando. O Selenium Manager resolve o driver compatível. O navegador
padrão é o Edge.

O executor cria um contêiner PostgreSQL 18 exclusivo, com porta aleatória
publicada apenas em `127.0.0.1`. As migrações são aplicadas ao banco novo.
A senha descartável é passada à JVM pelo ambiente, sem integrar o comando Maven.
O contêiner é parado/removido no bloco `finally`, inclusive quando o teste falha.
Não usa o PostgreSQL local nem os arquivos de credenciais do projeto.

A suíte sobe a API em porta aleatória, inicia um Angular exclusivo com proxy
para essa API e captura SMTP em memória. Esses recursos são encerrados ao fim.
Contas usam UUID e domínio `example.test`. O banco e os e-mails são descartados;
nenhum e-mail é encaminhado para provedores externos.

## Execução rápida com H2

Para conferir a suíte sem Docker, a partir de `backend/`:

```powershell
./mvnw.cmd -Pbrowser-qa test
```

Essa execução usa H2 em memória e não substitui o aceite em PostgreSQL.
O perfil Maven adiciona os fontes de `qa/browser/src/test/java` e seleciona
somente `*BrowserIT`. Sem `-Pbrowser-qa`, a suíte normal não inicia navegador
nem compila esses fontes. O Selenium tem versão fixada no perfil, incluindo
seus módulos gerenciados pelo parent do Spring Boot.

## Cenários

Cada método roda em quatro combinações: viewport de 1440 e 390 pixels,
tema claro e escuro. O navegador usa viewport emulada, sem simular aparelho físico
ou comportamento específico de Safari/Android. As esperas são condicionais.

| Método | Verificações |
| --- | --- |
| `accountLifecycle` | Cadastro exclusivo, duplicidade e foco, credenciais inválidas, limpeza da senha, destino preservado, cookie HttpOnly, ausência de tokens/identidade em storage, restauração após recarga, logout em outra aba, recuperação SMTP, cooldown visível, redefinição, revogação da sessão anterior, senha antiga rejeitada, senha nova aceita e token de uso único |
| `keyboardAndFieldErrors` | Primeiro campo inválido, Tab entre campos, e-mail inválido e `aria-invalid`, confirmação divergente e mensagem associada, Enter para avançar no login, foco na senha, Space no botão de mostrar senha, erro na recuperação e link sem token |
| `profileLifecycle` | Retorno ao perfil após login, edição e persistência de nome/e-mail, foco no seletor de foto, prévia e cancelamento, envio PNG e substituição JPEG, limites de 2 MiB e 4 milhões de pixels, remoção e avatar padrão, senha atual incorreta e troca com novo login, confirmação de exclusão com e-mail, cancelamento com Escape e restauração de foco |

As jornadas também conferem ausência de rolagem horizontal. O tema é
selecionado pelo controle da interface e pela preferência de mídia emulada.
Somente screenshots de estados sem senhas preenchidas são produzidos.

## Evidências e limites

- Relatórios JUnit: `backend/target/surefire-reports/`.
- Screenshots e log do Angular: `backend/target/browser-qa/`.
- Esses diretórios são ignorados pelo Git. Não publicar dumps de DOM, cookies,
  mensagens SMTP, logs completos ou URLs com token.
- Registrar um resumo sanitizado em `docs/evidencias/`, com ambiente e resultado.
- A consulta de sessão após logout/redefinição cobre invalidação real. Não há
  espera de 30 minutos pelo vencimento por inatividade nesta suíte.
- Reenvio após 60 segundos, limites de requisições e falhas de rede têm
  verificações anteriores; esta suíte não substitui sua cobertura específica.
- Leitores de tela, Safari, Firefox, dispositivos físicos e ambiente de produção
  permanecem fora da cobertura desta suíte.

A KAN-40 depende da execução e do aceite das jornadas de acesso e perfil.
Problemas funcionais reproduzíveis encontrados devem seguir o
[fluxo de Bugs](../../docs/bugs.md), com vínculo à história afetada.

Referência: [documentação oficial do Selenium](https://www.selenium.dev/documentation/webdriver/getting_started/).
