# Frontend do Acompanhaai

Base Angular/TypeScript da KAN-38. O backend Spring Boot fica em `../backend/`.
O esboço entrega navegação e componentes visuais. A KAN-39 adiciona o
[cliente HTTP de sessão/CSRF](docs/cliente-http-kan-39.md), preparado para as
próximas telas. Login, perfil e candidaturas ainda não têm interface integrada.

## Executar

Pré-requisitos: Node.js 22.13+ na linha 22, ou Node.js 24, e npm 10.9.2+.
Validado localmente com Node.js 22.16.0 e npm 10.9.2. Angular 21 foi escolhido
por ser compatível com esse ambiente, conforme a
[tabela oficial](https://angular.dev/reference/versions).
O mínimo de Node.js 22.13 também atende ao ESLint 10 usado no projeto.
O lockfile registra as versões efetivamente instaladas. Não requer CLI global.

Na raiz do repositório:

```bash
cd frontend
npm ci
npm start
```

Abrir [http://localhost:4200](http://localhost:4200).
A interface desta tarefa funciona sem iniciar o backend e sem dados de teste.

## Estrutura e navegação

- `src/app/features/`: páginas independentes carregadas por rota.
- `src/app/shared/models/`: vocabulário compartilhado das oito etapas da API.
- `src/app/shared/ui/`: estados vazio, carregando, erro e campo de formulário.
- `src/styles/_tokens.scss`: cores, tipografia, espaçamentos e bordas.
- `src/app/app.*`: cabeçalho, navegação, conteúdo e rodapé.

Rotas públicas: `/inicio` e `/etapas`; `/` redireciona para `/inicio`.
Endereços desconhecidos apresentam página de recuperação.
`/referencias` demonstra componentes **somente no build de desenvolvimento**.
Ela não é um formulário de cadastro nem faz requisições à API.

O Angular usa componentes standalone, TypeScript estrito, formulários reativos
tipados e rotas lazy. Páginas futuras de conta/candidaturas ficam em seus
domínios; o cliente HTTP e interceptor ficam em `src/app/core/`.
Não há guard de autenticação nesta tarefa, pois não há rotas privadas entregues.

## Componentes e acessibilidade

- Usar controles HTML nativos e classes globais `.button`/`.secondary`.
- `FormField` recebe `controlId`, `label`, `hint` e `error`. O input projetado
  deve usar o mesmo ID e relacionar as mensagens em `aria-describedby`.
  O formulário consumidor define `aria-invalid` e validações.
- `ErrorState` anuncia a mensagem e só mostra ação se `retryLabel` for informado;
  o consumidor trata o evento `retry`. Nenhuma repetição automática é realizada.
- `LoadingState` usa anúncio de status e skeleton sem animação obrigatória.
- Navegação com link para pular conteúdo, foco no conteúdo após trocar rota,
  link ativo com `aria-current`, idioma pt-BR e títulos por página.
- Manrope é servida localmente pelo pacote Fontsource; nenhuma CDN é necessária.

Consultar o [sistema visual](docs/sistema-visual.md) para manter os componentes consistentes.

## Verificar

Dentro de `frontend/`:

```bash
npm run lint
npm run format:check
npm run test:ci
npm run build
```

Lint verifica TypeScript e templates, incluindo regras de acessibilidade.
Vitest/jsdom verifica navegação, estados e validação da prévia; não substitui
testes de navegador. A configuração usa um worker em thread para evitar a
inicialização intermitente de processos separados no Windows local.
Build de produção em `dist/acompanhaai-web/browser`.
A integração desses comandos ao GitHub Actions pertence à KAN-41.

O host de produção deve devolver `index.html` para rotas de interface
desconhecidas pelo servidor e preservar o tratamento de `/api` e assets.
Não há deploy configurado nesta tarefa.

## Próximas entregas

KAN-39: cliente de sessão/CSRF/erros e proxy local implementados; consultar seu guia.
KAN-36: cadastro, login, logout e recuperação de senha.
KAN-37: perfil e foto. Consulte o
[contrato HTTP](../docs/integracao-frontend.md) antes de integrar.
