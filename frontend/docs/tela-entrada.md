# Tela de entrada

A rota `/entrar` apresenta exclusivamente a interface fornecida em `files.zip`.
A raiz e os caminhos desconhecidos redirecionam para ela.

## Fontes

- `src/app/app.component.ts`
- `src/app/app.routes.ts`
- `src/app/app.config.ts`
- `src/app/auth-page/auth-page.component.ts`, `.html` e `.scss`
- `src/app/header/header.component.ts`, `.html` e `.scss`
- `src/app/neon-cursor/neon-cursor.component.ts`
- `src/main.ts`, `src/index.html` e `src/styles.scss`

Os arquivos soltos na raiz do ZIP são idênticos aos contidos no arquivo
`acompanha-ai-src.zip`. Foi utilizada uma única cópia dos fontes.
A formatação foi organizada sem combinar componentes da interface anterior.

Os callbacks de acesso são simulações. A mensagem de código enviado não
representa envio real de e-mail. Não há integração com o backend nesta tela.
