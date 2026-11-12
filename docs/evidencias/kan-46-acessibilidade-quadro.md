# KAN-46 — Responsividade e acessibilidade do quadro

## Escopo e ambiente

- Tela `/candidaturas`: cabeçalho, ações, filtros, colunas, cards, movimento
  de etapa, conflito no card, lista vazia, nenhum resultado e erro de carga.
- Build de produção do Angular 21 com API simulada, Chrome 153 headless e
  axe-core 4.14.0 (regras WCAG 2.0, 2.1 e 2.2 A/AA e boas práticas).
- Larguras de 1440, 390 e 320px; temas claro e escuro pela preferência do
  sistema.
- Base: `main` em `0cabce3` (KAN-45), 12/11/2026.

## Achados

| ID | Achado | Critério | Situação |
| --- | --- | --- | --- |
| A1 | "Nova candidatura" e "Importar vaga" apareciam colados ("Nova candidaturaImportar vaga"), parecendo um único link | 1.3.1 / legibilidade | Corrigido: grupo `nav` "Adicionar candidatura" com espaçamento |
| A2 | O contador da coluna era lido só como "1": o `aria-label` em `span` genérico é ignorado pelos leitores | 1.3.1 | Corrigido: o título da coluna anuncia "Triagem (1 candidatura)" e o número visual fica oculto para leitores |
| A3 | O rótulo visível do seletor é "Mover para", mas o nome acessível era "Mover {vaga} para outra etapa", sem o trecho visível contíguo; comando de voz por "Mover para" falharia | 2.5.3 | Corrigido: nome "Mover para outra etapa: {vaga}" |
| A4 | Todos os botões "Mover" tinham o mesmo nome, sem indicar a vaga ao navegar por formulário | 2.4.6 | Corrigido: o botão é descrito pelo título da vaga (e pelo aviso de data, quando houver) |
| A5 | Cabeçalho global com cinco links `href="#"` sem destino (logo, "Página inicial", "Avaliações de Empresas", "Cargos e salários", "País e idioma", "Empresas/Anunciar vagas") | 2.4.4 | Aberto: depende de decisão de produto; fora da tela do quadro |

## Verificações

| Verificação | Resultado |
| --- | --- |
| axe-core em 17 estados (quadro, destino escolhido, conflito, vazio, erro, nenhum resultado; 1440/390/320px; claro/escuro) | 0 violações antes e depois das correções; contraste aprovado nos dois temas |
| Ordem de foco por Tab | Cabeçalho → voltar → ações → pesquisa → botão → etapa → card a card (título, seletor); "Mover" entra na ordem quando há etapa escolhida |
| Foco visível | Indicador visível em todos os 28 elementos percorridos |
| Reflow em 320px e espaçamento de texto (1.4.12) | Sem rolagem horizontal nem texto cortado |
| Dependência de cor | Colunas com título em texto; resultado final ("Rejeitada", "Desistência") escrito no card; borda colorida só decorativa |
| Arrastar e soltar | Não existe; a etapa muda por seletor e botão, pelo teclado ou toque |
| Movimento | Efeitos de brilho respeitam `prefers-reduced-motion` |
| Árvore de acessibilidade (amostra) | Regiões nomeadas por coluna, títulos h1–h4 em ordem, busca com `role="search"`, avisos em `role="status"` e erros em `role="alert"` |

## Regressão automatizada

- Vitest: nomes acessíveis de coluna, seletor, botão e ações, além de axe-core
  estrutural no quadro com etapa escolhida (162 testes).
- Selenium no CI (`backend-browser-qa`): axe-core no Chrome real dentro de
  `boardLifecycle`, nas quatro combinações de largura e tema, no quadro
  carregado e com conflito no card. Inclui o contraste.

## Leitor de tela

A amostra automatizada usa a árvore de acessibilidade do Chrome. O roteiro
manual com NVDA no Windows (Firefox ou Edge) fica registrado para o aceite:

1. Abrir `/candidaturas` com três candidaturas em etapas diferentes.
2. Navegar por títulos (H) e conferir "Candidatura realizada (n candidaturas)".
3. Com Tab, conferir "Mover para outra etapa: {vaga}, caixa de combinação".
4. Escolher uma etapa com as setas, avançar para "Mover" e conferir a vaga na
   descrição; pressionar Enter e ouvir "Candidatura movida para …".
5. Pesquisar um termo sem resultado e ouvir "0 candidaturas encontradas."
