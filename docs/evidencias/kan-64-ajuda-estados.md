# KAN-64 — Ajuda e estados do produto

## Escopo e ambiente

- Angular 21, build de produção com API simulada, Chrome 153 headless e
  axe-core 4.14.0, em 1440 e 390px, nos temas claro e escuro.
- Base: `main` em `27796ea` (KAN-63), 13/11/2026.

## Entregas

| Item | Antes | Agora |
| --- | --- | --- |
| Página de ajuda (`/ajuda`) | Inexistente | Pública. Cobre primeiros passos, "Salvar uma vaga não é se inscrever", importação por link e por descrição colada, revisão obrigatória dos dados sugeridos, significado das 8 etapas e exclusão de dados. Mostra o caminho certo para quem entrou ou não |
| Cabeçalho (achado A5 da KAN-46) | Cinco links `href="#"` sem destino e logo sem destino; links escondidos no celular | Logo leva ao quadro (ou ao login); Candidaturas, Importar vaga, Ajuda e Minha conta (ou Entrar), com página atual marcada; navegação visível também no celular |
| Primeiro uso no quadro | "Você ainda não tem candidaturas cadastradas." e oito colunas vazias | Mesmo título, com orientação, aviso de que salvar não envia candidatura e atalhos para importar, cadastrar e entender o funcionamento |
| Revisão da importação | "Confira e complete as informações." | Avisa que os dados foram sugeridos automaticamente e podem conter erros; explica, junto da decisão, que salvar não envia candidatura |
| Descartar ou cancelar importação | Ação imediata, sem confirmação | Confirmação no próprio card ("Descartar esta importação?"), com foco no título e opção de manter |
| Erro 403 | Mostrava o texto da API "Acesso negado ou token CSRF inválido." | Mensagem do produto: "Não foi possível autorizar a operação. Confira sua sessão antes de tentar novamente." |

## Cobertura de estados

| Estado | Onde |
| --- | --- |
| Carregando | Quadro ("Carregando candidaturas…"), importação ("Carregando importação…"), fila e leitura com anúncio |
| Vazio | Primeiro uso no quadro |
| Nenhum resultado | Quadro filtrado (KAN-44) |
| Falha | Quadro com "Tentar novamente"; importação com título da falha e opção de colar a descrição; erro de rede e de sessão com mensagens do produto |
| Ação destrutiva | Excluir candidatura e excluir conta (já existentes); descartar e cancelar importação (novos) |

## Verificações

- axe-core: 0 violações no quadro vazio e na ajuda (1440/390px, claro/escuro),
  sem rolagem horizontal.
- Vitest: 181 testes (8 novos). Cobrem cabeçalho com e sem sessão, sem links
  `#`; ajuda com etapas, textos-chave e ausência de termos internos (API, CSRF,
  token, worker, provedor, JSON, servidor); primeiro uso; confirmação de
  descarte com foco; textos da revisão; 403 sem detalhe interno; e a rota
  pública da ajuda identificando a sessão.
- `npm run format:check`, `npm run lint` e `npm run build` passaram (avisos de
  orçamento de CSS já existentes nas telas de acesso).
