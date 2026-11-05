# KAN-35 — revisão local do contrato

Data: 05/11/2026. Ambiente: Windows 11, Node.js 22.16.0.
Contrato OpenAPI 3.0.3, versão documental 0.1.0.

## Escopo revisado

- Resources, DTOs e tratamento de erros atuais comparados ao OpenAPI.
- Modelos de resposta Application/Job separados da entrada, com campos
  obrigatórios e habilidades não nulas na saída.
- Null nos enums opcionais de modalidade, moeda e período; status nulo
  permitido no cadastro e recusado no PUT; histórico inicial com fromStatus nulo.
- Exemplos de candidatura com salário/habilidades, resposta sem salário,
  página vazia e erros 401/403/409/422.
- Corrigida a descrição YAML da resposta 413: a vírgula dentro de um objeto
  em linha criava uma propriedade extra e invalidava o documento OpenAPI.
- Guia atualizado com recuperação, foto, sessão/CSRF e rastreabilidade.

## Verificação

Ferramentas de validação instaladas somente em `target/contract-tools`,
diretório ignorado pelo Git. Nenhuma dependência Maven ou Postman foi alterada.
Versões utilizadas: `@apidevtools/swagger-parser` 13.0.0, `ajv-draft-04` 1.0.0
e `yaml` 2.9.1.
O parser OpenAPI verificou a estrutura e resolveu as referências; Ajv draft-04
verificou os exemplos contra os schemas resolvidos, sem validação de formatos
date/email/URI. A validação estrutural não executa regras entre campos do Service.

Resultado local: 19 operações com operationId único; 18 exemplos válidos;
sete verificações negativas recusaram campos obrigatórios ausentes e listas
de habilidades nulas na resposta. O cadastro com status nulo e o histórico
inicial nulo foram conferidos; PUT com status nulo foi recusado pelo schema.

Para repetir a validação estrutural do documento, instalar o parser temporário
e executar na pasta `backend/`:

```powershell
npm.cmd install --prefix target/contract-tools --no-save --ignore-scripts --no-audit --no-fund @apidevtools/swagger-parser@13.0.0
@'
const parser = require('./target/contract-tools/node_modules/@apidevtools/swagger-parser');

parser.validate('docs/openapi.yaml')
  .then(() => {
    console.log('OpenAPI valido');
  })
  .catch(error => {
    console.error(error.message);
    process.exitCode = 1;
  });
'@ | node
node postman/fixtures/v2/build.cjs --check
git diff --check
```

A coleção v2 foi conferida pelo builder: 97 requisições, scripts compilados,
sem divergência no artefato gerado. Não houve nova execução HTTP nesta tarefa;
as execuções anteriores continuam nas evidências KAN-33 e KAN-34.
Nenhum arquivo da fixture v1 foi alterado.

## Limites e aceite

Entrega local em arquivos versionáveis; nenhuma publicação externa realizada.
Revisão conjunta com frontend/QA, teste no navegador Angular, PR e CI desta
alteração ainda precisam ser registrados antes do encerramento de KAN-35.
O roteiro e a relação entre critérios, contrato e testes estão no
[guia de integração](../../../docs/integracao-frontend.md#rastreabilidade-e-aceite).
