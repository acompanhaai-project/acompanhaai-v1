# Frontend do Acompanha Aí

Telas de entrada, cadastro e recuperação baseadas nos arquivos de interface fornecidos.
O backend Spring Boot permanece em `../backend/`.

## Executar

Na pasta `frontend/`, com Node.js 22.13+ e npm 10.9.2+:

Node.js 24 também é compatível. O projeto usa Angular 21 e não requer CLI global.
O lockfile registra as versões das dependências.

```bash
npm ci
npm start
```

Abrir http://localhost:4200/entrar. A raiz e os endereços desconhecidos
redirecionam para `/entrar`. A tela de cadastro está em `/cadastro`, acessível
pelo link **Criar conta** na tela de entrada.
O link **Esqueci minha senha** abre `/recuperar-senha`. A redefinição fica em
`/redefinir-senha?token=demo-design` para demonstração.

## Estrutura

- `src/app/auth-page/`: cartão de acesso, e-mail e inclinação do cartão.
- `src/app/signup-page/`: cadastro, requisitos de senha e estados do formulário.
- `src/app/recuperar-senha-page/`: solicitação do link, confirmação e reenvio.
- `src/app/redefinir-senha-page/`: validação simulada do link e nova senha.
- `src/app/shared/`: estilos do cartão, regras de senha e simulações de recuperação.
- `src/app/core/auth/`: cliente real de sessão e estado autenticado em memória.
- `src/app/core/http/`: interceptor, CSRF, fila de requisições e tratamento de erros.
- `src/app/header/`: cabeçalho e alternância entre temas.
- `src/app/neon-cursor/`: brilho com blur e ponto que acompanham o mouse.
- `src/styles.scss`: cores, temas, tipografia e grade iluminada pelo cursor.
- `src/app/app.config.ts`: rotas e providers do cliente HTTP, sessão/CSRF e erros globais.

As telas seguem os componentes dos ZIPs. A infraestrutura HTTP da KAN-39 e seus
testes estão preservados e configurados no bootstrap. O cadastro usa
`SessionApi.register()` para criar a conta na API. Login e recuperação ainda
usam simulações. A implementação pertence à KAN-86 / KAN-36, na sprint
S04 - Angular: acesso.
Consultar o [cliente de sessão/CSRF](docs/cliente-http-kan-39.md).

Os componentes são standalone, com TypeScript estrito, formulários reativos
tipados e rotas lazy. Não há rotas privadas ou guard de autenticação nesta
entrega. O padrão de cores, tipografia e efeitos está no
[sistema visual](docs/sistema-visual.md).

Os botões Google/Apple e a mensagem de código enviado são simulações locais,
conforme o protótipo do login. Não há autenticação ou envio de e-mail nessas
ações. Os links de navegação e políticas são placeholders (`href="#"`).

O cadastro cria uma conta real no banco configurado pelo backend e requer a
API em execução. Não autentica nem envia e-mail de confirmação. Não há e-mails
especiais que simulem erros nessa tela; duplicidade depende dos registros reais.
Consultar [tela de cadastro](docs/tela-cadastro.md).
As telas de recuperação também usam simulações. Consultar os
[cenários de recuperação](docs/recuperacao-senha.md) para demonstrar erros e sucesso.

## Verificar

```bash
npm run format:check
npm run lint
npm run test:ci
npm run build
```

O Google Fonts fornece Plus Jakarta Sans. O build precisa de acesso à internet
para incorporar a folha da fonte. Os testes automatizados verificam a infraestrutura
HTTP, sua configuração na aplicação e o cadastro. O cadastro também é conferido
no navegador contra uma API real com H2 isolado. Isso não valida os fluxos de
login e recuperação integrados, que continuam pendentes.

O lint verifica TypeScript e templates. Os testes usam Vitest/jsdom com um
worker em thread. O build de produção fica em `dist/acompanhaai-web/browser`.
A integração desses comandos ao GitHub Actions pertence à KAN-41.

Em produção, o host deve devolver `index.html` para rotas de interface e
preservar o tratamento de `/api` e assets. Não há deploy configurado nesta entrega.

## Próximas entregas

- KAN-36 / KAN-86: integrar o fluxo de acesso à API e proteger a navegação.
- KAN-37: implementar perfil e foto pela interface.
- KAN-40: validar as jornadas de conta integradas no navegador.
- KAN-41: adicionar build e qualidade do frontend ao CI.

Consultar o [contrato HTTP](../docs/integracao-frontend.md) antes de integrar.
