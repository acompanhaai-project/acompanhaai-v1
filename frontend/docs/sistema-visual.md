# Sistema visual

Base provisória para navegação e componentes. O projeto de UX/UI definitivo
será definido antes da implementação das telas completas.

## Tokens

A fonte dos tokens é `src/styles/_tokens.scss`:

- Manrope servida localmente, nos pesos 400, 600, 700 e 800.
- Azul `#164ec7` para ações e texto principal `#0e2346`.
- Fundo `#f3f6fb`, superfícies brancas e divisórias `#d9e2ef`.
- Bordas de 8px nos controles e 16px nos painéis.
- Espaçamento baseado em múltiplos de 4px.

## Layout e acessibilidade

O conteúdo tem largura máxima de 1120px e margens de 32px em desktop e 16px
em telas pequenas. Layouts de duas colunas passam para uma coluna em 760px.

Usar controles HTML nativos, labels associados aos campos, foco visível e
mensagens de erro que não dependam apenas da cor. Preservar o link para pular
o conteúdo e o foco após mudanças de rota. Novas animações devem respeitar
`prefers-reduced-motion`.

Componentes compartilhados ficam em `src/app/shared/ui/`. Os estados de
carregamento, erro e conteúdo vazio são exemplos na rota de desenvolvimento
`/referencias`, indisponível no build de produção.
