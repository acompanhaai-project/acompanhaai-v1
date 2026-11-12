# KAN-44 — Filtros, pesquisa e paginação da lista

## Escopo e ambiente

- Quadro `/candidaturas`: pesquisa por título ou empresa, filtro por etapa e
  paginação de `GET /api/applications` com `q`, `status`, `page` e `size=20`.
- A pesquisa usa o contrato já publicado em `openapi.yaml` (KAN-31): trecho
  literal, sem diferenciar maiúsculas, até 100 caracteres.
- Angular 21, Node.js 22.23.4 e npm 10.9.9.
- Base: `main` em `5c2cfad` (KAN-59).

## Comportamento

| Situação | Resultado |
| --- | --- |
| Pesquisa enviada (botão ou Enter) | Espaços removidos, até 100 caracteres, nova consulta a partir da página 0 |
| Etapa escolhida | Nova consulta imediata; só a coluna da etapa é exibida |
| Pesquisa e etapa juntas | `status` e `q` na mesma consulta |
| Filtros sem mudança | Nenhuma consulta repetida |
| Filtro trocado com consulta pendente | Consulta anterior cancelada; só o resultado mais recente aparece |
| Carregar mais | Próxima página mantendo os filtros |
| URL | `?etapa=...&busca=...`, atualizada sem criar entradas no histórico |
| Abrir detalhes e voltar (link ou navegador) | Filtros e campos restaurados; etapa desconhecida na URL é ignorada |
| Conta sem candidaturas | "Você ainda não tem candidaturas cadastradas." |
| Filtros sem resultado | "Nenhuma candidatura encontrada com esses filtros." e colunas ocultas |
| Limpar filtros | Campos zerados, lista completa e foco na pesquisa |
| Candidatura movida para fora da etapa filtrada | Sai da lista, total atualizado e aviso por `role="status"` |
| Resultado da busca | Anunciado para leitores de tela ("2 candidaturas encontradas.") |

O formulário usa `role="search"`, rótulos visíveis e controles com altura
mínima de 44px. No celular os campos quebram em linhas.

## Verificações automatizadas

```bash
cd frontend
npm run format:check
npm run lint
npm run test:ci
npm run build
```

Os 160 testes passaram, 8 deles novos. Cobrem consulta combinada e URL,
paginação filtrada, cancelamento da consulta anterior, vazio sem filtro versus
nenhum resultado, limpeza, filtros repetidos, candidatura movida para fora do
filtro, restauração pela URL, etapa inválida e o link de volta dos detalhes.
O build passou com os avisos de orçamento de CSS já existentes nas telas de
acesso.

## Navegador

Chrome headless com o build de produção e API simulada, em 1280px e 390px:
pesquisa "java" + etapa "Candidatura realizada" levou a
`/candidaturas?etapa=APPLIED&busca=java` com um resultado. Ao abrir os
detalhes e voltar, pelo link e pelo botão do navegador, a URL, os campos e o
resultado se mantiveram. Uma pesquisa sem correspondência mostrou o estado de
nenhum resultado.

## Isolamento entre contas

O frontend exibe somente o que a API devolve para a sessão. O backend
garante que pesquisa e etapa não trazem registros de outra conta em
`ApplicationTrackingIntegrationTest.combinesTitleOrCompanySearchWithStatusWithoutLeakingOtherAccounts`,
que roda em H2 e em PostgreSQL no CI.
