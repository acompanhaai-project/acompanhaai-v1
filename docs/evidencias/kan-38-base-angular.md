# KAN-38 — base Angular

Revisão local em 05/11/2026, Windows 11. Node.js 22.16.0, npm 10.9.2,
Angular 21.2.26, CLI/build 21.2.29, TypeScript 5.9.3 e Vitest 4.0.8.
As versões completas estão no lockfile de `frontend/`.

## Entrega

| Critério                                     | Implementação                                                          | Verificação                                                      |
| -------------------------------------------- | ---------------------------------------------------------------------- | ---------------------------------------------------------------- |
| Base Angular/TypeScript e versão documentada | Aplicação standalone estrita em frontend/, lockfile próprio            | Build de produção e documentação local                           |
| Layout responsivo e tokens                   | Shell, Manrope local, cores/espaçamentos em SCSS                       | Capturas em 1440 e 390 px, sem overflow horizontal               |
| Componentes de formulário e estados          | FormField, EmptyState, LoadingState, ErrorState                        | Testes de validação, troca de estados e evento de nova tentativa |
| Navegação e organização por domínio          | Rotas lazy de início/etapas/404; referências apenas em desenvolvimento | Testes de rotas e verificação do build de produção no navegador  |
| Lint, acessibilidade e português             | ESLint TS/templates, pt-BR, labels, foco, skip-link, títulos           | Lint, teste de foco e inspeção automática axe                    |

## Procedimento reproduzível

Em `frontend/`, após instalar as dependências com `npm ci`:

```bash
npm run lint
npm run format:check
npm run test:ci
npm run build
npm start
```

Verificações no navegador:

1. Abrir `/inicio`, `/etapas`, `/referencias` e um endereço inexistente.
2. Conferir larguras de 1440 e 390 px, inclusive rodapé e navegação.
3. Em `/referencias`, alternar Vazio/Carregando/Erro. Enviar o título vazio,
   somente espaços e válido; confirmar erro relacionado ao input e ausência
   de envio à API.
4. Recarregar `/inicio`, usar Tab para alcançar “Pular para o conteúdo” e
   Enter para focar main sem recarregar a página. Navegar para etapas e
   confirmar foco no conteúdo e título atualizado.
5. Servir `dist/acompanhaai-web/browser` com fallback de SPA e acessar
   `/referencias`: deve mostrar página não encontrada, sem link no cabeçalho.

## Resultado

Lint e formatação aprovados. Oito testes de navegação, estados, validação e
foco aprovados, sem falhas. Build de produção aprovado, 245,45 kB iniciais,
dentro do orçamento de 500 kB inicial.
Backend, banco e fixtures Postman não foram alterados.

Inspeção automatizada em Edge headless 156.0.4314.58, via Playwright:
quatro rotas em duas larguras, sem overflow e sem violações detectadas
por axe nas regras WCAG 2 A/AA e WCAG 2.1 AA utilizadas.
Também passaram validação da prévia, troca de estados, foco, título da rota
e bloqueio das referências no build de produção. Sem erros JavaScript capturados.
Resultado sanitizado: [kan-38-browser.json](kan-38-browser.json).

A conferência das oito capturas não identificou problemas
materiais de legibilidade, hierarquia ou layout. Capturas e ferramentas
temporárias permanecem em `target/kan38-browser` e `target/ui-tools`, ignorados
pelo Git; não são dependências de produção nem parte da suíte versionada.

Falhas corrigidas durante a validação: ligação do submit ao FormGroup,
reatividade do erro do formulário e foco/skip-link na navegação Angular.
A primeira tentativa de instalação falhou no resolvedor npm; a instalação
foi concluída com Vitest fixado. ESLint 10 foi escolhido em lugar do 9
descontinuado. Foi configurado um worker em thread para evitar timeout
intermitente ao iniciar os processos separados do Vitest no Windows local.

## Limites e aceite

Esta entrega não inclui chamadas HTTP, autenticação, rotas privadas ou dados
de candidaturas. Cliente HTTP: KAN-39; telas de acesso: KAN-36;
perfil: KAN-37; frontend no CI: KAN-41.
Não foram realizados testes com leitor de tela, dispositivos físicos ou
jornadas de conta no navegador. Axe não comprova conformidade integral.
PR, CI e aprovação visual continuam necessários antes de encerrar
a tarefa. Nenhum deploy foi realizado.
