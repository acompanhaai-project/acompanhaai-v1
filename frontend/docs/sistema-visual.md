# Sistema visual

O protótipo atual segue exclusivamente os estilos de `files.zip`.

- Fonte Plus Jakarta Sans, carregada do Google Fonts.
- Tema claro com fundo `#f4f7ff` e tema escuro com fundo `#050a1c`.
- Cores neon `#2f7bff` e `#00c2ff`.
- Cartão translúcido com largura máxima de 600px e raio de 22px.
- Brilho de 340px com blur de 22px e ponto luminoso de 12px.
- Grade de 56px iluminada ao redor do ponteiro.
- Inclinação do cartão e borda iluminada acompanham o mouse.
- Tema inicial conforme o sistema, com alternância pelo botão Tema.

Os tokens estão em `src/styles.scss`. Os estilos do cursor ficam no próprio
componente `src/app/neon-cursor/neon-cursor.component.ts`, conforme o ZIP.
