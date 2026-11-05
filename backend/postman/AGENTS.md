# Preservacao do mock solicitado pelo usuario

O usuario determinou que o codigo deste mock nao seja alterado futuramente.

- `fixtures/v1/` e uma versao congelada: nao editar, reformatar, regenerar nem atualizar suas expectativas para acompanhar mudancas do backend.
- Preservar tambem `package.json` e `package-lock.json`, que fixam o executor da v1. Uma v2 que exija dependencias diferentes deve ter seu proprio pacote.
- Evolucoes devem criar `fixtures/v2/` (ou outra versao nova), mantendo a v1 intacta.
- Nao atualizar hashes para esconder uma alteracao da v1. O manifesto SHA-256 registra a entrega original.
- `local.postman_environment.json` pode ter a URL local ajustada; `reports/` e `node_modules/` sao gerados e nao integram o contrato congelado.
- Este cenario nao deve receber endpoints de imagem.
