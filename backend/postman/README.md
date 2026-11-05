# Testes da API

- `fixtures/v1/`: contrato de regressão congelado, sem endpoints de imagem.
- `fixtures/v2/`: verificações dos campos e fluxos adicionados posteriormente.
- `local.postman_environment.json`: configuração local sem credenciais.
- `reports/` e `node_modules/`: arquivos gerados, ignorados pelo Git.

## Preservação da v1

Não alterar dados, scripts, asserções ou dependências da v1 para acompanhar
mudanças na API. Novos contratos e verificações devem usar a v2 ou outra versão.
O manifesto SHA-256 registra os arquivos da entrega. Não atualizar hashes para
ocultar alterações no comportamento dos testes.

As instruções de execução estão nos READMEs de cada versão. Executar os
comandos a partir de `backend/`, salvo indicação diferente no roteiro.
