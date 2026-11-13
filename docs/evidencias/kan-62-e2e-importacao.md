# KAN-62 — Importação e revisão ponta a ponta no navegador

## Ambiente

- Execução: [Backend CI #43490421155](https://github.com/acompanhaai-project/acompanhaai-v1/actions/runs/43490421155),
  job `backend-browser-qa`, commit `8d4a42d`, em 13/11/2026.
- Chrome headless do runner, Selenium 4.52.0, PostgreSQL 18 exclusivo do job.
- Worker de importação real com intervalo de 300 ms. O servidor local
  `ImportSimulation` publica páginas com JSON-LD e responde no formato do Gemini
  a partir do texto. O nome `vagas.qa.test` aponta para ele só nesta suíte.
- Chave fictícia; nenhuma chamada ao provedor real.

## Resultado

25 cenários, 0 falhas, incluindo os 3 novos de importação.

| Cenário | Verificações |
| --- | --- |
| `importFromTextAndSaveOnce` (1440px claro, 390px escuro) | Revisão preenchida com evidência, axe-core, recarga, sessão expirada com retorno à revisão após login, falha de rede na confirmação, clique duplo, uma única candidatura e importação concluída ao reabrir |
| `importFromPageAndReviewManuallyWhenTheProviderFails` (1440px claro) | JSON-LD como origem, decisão "Salva", provedor indisponível com duas tentativas, revisão manual com título obrigatório e as duas vagas no quadro filtrado |

## Defeito encontrado e corrigido

Na primeira execução, os três cenários pararam no login. O destino
`/candidaturas/importar` não estava na lista de retorno permitido após o login
(`safeReturnUrl`), e o usuário caía em `/conta`. Isso valia também para quem tinha
a sessão expirada durante a revisão. A lista passou a aceitar exatamente
`/candidaturas/importar` e `/candidaturas/importar/{id}`, com ID numérico.
Os testes cobrem a rejeição de `/importar/0`, `/importar/abc`,
`/importar//example.test` e `/importar/1/../../entrar`. Destinos externos
continuam bloqueados.

## Integração real com IA

Continua separada em `ExtractionEvaluationLiveTest`. O teste só roda com
`-Dextraction.eval=true`, usa 20 vagas fictícias por execução e espera entre as
chamadas. Fica fora do CI.
