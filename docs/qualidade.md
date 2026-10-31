# Estratégia de qualidade e evidências

Relacionamento: KAN-22 e tarefas de QA das demais entregas.
Esta estratégia não representa um relatório de testes executados.

## Cobertura atual e evolução

| Camada | Base existente | Evolução planejada |
|---|---|---|
| Integração Spring/MockMvc | Conta, autenticação, perfil, fotos e candidaturas | Novos campos, recuperação e importação |
| Persistência | Testes de mapeamento em H2 e migrações | PostgreSQL, concorrência e atualizações preservando dados |
| API | Fixture Postman v1 congelada | Regressão v2 para o novo contrato |
| Unidade | Sem suíte dedicada identificada nesta revisão | Regras isoladas quando trouxerem valor |
| Navegador | Frontend ainda não implementado | Selenium/Java, sessão, jornadas e acessibilidade |
| Desempenho/operação | Sem execução comprovada nesta entrega | Carga, restore, rollback e smoke |
| IA | Integração ainda não implementada | Conjunto de avaliação e falhas controladas |

O baseline histórico registrado no planejamento é de 26 testes de integração
e 90 requisições/246 asserções na fixture v1. Esses números não comprovam
uma execução atual e devem ser substituídos por evidências de cada rodada.

## Segurança da massa

Os testes existentes executam exclusões de registros. Nunca apontar a suíte
para produção ou para um banco de desenvolvimento com dados pessoais.

Usar nomes fictícios, domínios `example.com`/`example.test` e um banco de testes
separado. Rodar as suítes atuais sequencialmente no mesmo banco; elas não
foram projetadas para compartilhar essa massa em paralelo.

## Integração com H2

Na raiz, em PowerShell, fornecer explicitamente o datasource de teste para
evitar que configurações locais direcionem a suíte a outro banco:

```powershell
.\mvnw.cmd verify `
  "-Dspring.datasource.url=jdbc:h2:mem:acompanhaai-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" `
  "-Dspring.datasource.username=sa" `
  "-Dspring.datasource.password="
```

Os testes usam `@ActiveProfiles("test")`. Consultar o resultado do Maven e os
relatórios em `target/surefire-reports/`. Um build verde com H2 não comprova
compatibilidade completa com PostgreSQL.

## Integração com PostgreSQL

Criar previamente um banco descartável, como `acompanhaai_test`. O usuário
de teste deve ter acesso apenas ao ambiente apropriado.

```powershell
$credencialTeste = Get-Credential -Message "Credenciais exclusivas do banco de testes"
$env:SPRING_DATASOURCE_PASSWORD = $credencialTeste.GetNetworkCredential().Password
.\mvnw.cmd verify `
  "-Dspring.datasource.url=jdbc:postgresql://localhost:5433/acompanhaai_test" `
  "-Dspring.datasource.username=$($credencialTeste.UserName)"
```

Antes de executar, conferir que não existe parâmetro adicional sobrescrevendo
a senha e que a URL aponta exclusivamente ao banco de testes. Remover a
variável de ambiente e a variável `credencialTeste` ao terminar. Não passar
a senha em argumento de linha de comando.

## Fixture Postman v1

Seguir o [roteiro original](../postman/fixtures/v1/README.md). Ele executa
requisições contra a API real local; não é um servidor que substitui a API.

- Não editar `postman/fixtures/v1/`, seu executor ou seus hashes.
- Preservar também `postman/package.json`, `package-lock.json` e as regras
  de preservação estabelecidas nos arquivos AGENTS.md.
- Não adicionar testes de imagem à v1.
- Relatórios e ambiente exportado podem conter sessões: ficam locais.
- Executar a fixture em uma instância local dedicada a testes.
- Novos campos ou comportamento exigem v2, com pacote próprio se necessário.

Com a API local de testes iniciada:

```powershell
npm.cmd --prefix postman ci --no-audit --no-fund
npm.cmd --prefix postman run test:v1
```

O teste completo limpa as próprias contas ao finalizar com sucesso. Uma
interrupção pode deixar registros. O modo `--seed` mantém dados; sua limpeza
depende do ambiente salvo da execução. Não misturar rodadas sem conferir
esse estado e não publicar `postman/reports/`.

## Matriz mínima de aceite

| Domínio | Cenários |
|---|---|
| Contas | Cadastro, e-mail duplicado, entrada inválida e ausência de senha/hash na resposta |
| Autenticação | Credenciais válidas/inválidas, 401 anônimo, CSRF, rotação e logout |
| Isolamento | A não consulta, altera nem exclui dados de B |
| Perfil/foto | Foto opcional, upload inválido, limites, substituição e remoção; fora da fixture v1 |
| Candidaturas | CRUD, oito etapas, filtro, paginação, SAVED sem data e data futura |
| Histórico | Evento inicial, troca de etapa atômica e edição simples sem novo evento |
| Concorrência | Versão antiga, duplicidade e testes PostgreSQL dedicados |
| Migrações | Banco vazio e atualização com dados preservados |
| Importação futura | Coleta indisponível, cota, timeout, propriedade, confirmação repetida e conteúdo malicioso |

## Modelo de evidência

```text
Tarefa/critério:
Commit ou versão:
Ambiente e banco de testes (sem credenciais):
Pré-condições e dados fictícios:
Comando ou passos:
Resultado esperado:
Resultado observado:
Quantidade executada / falhas:
Evidência sanitizada:
Responsável e data:
Defeitos vinculados / reteste:
```

Copiar somente trechos necessários. Remover cookies, tokens, senhas, dados
pessoais e informações privadas antes de anexar evidências ao Jira ou PR.

## Bug e reteste

Registrar defeitos reais como `Problema`, com ambiente, versão, passos,
esperado/obtido, frequência, severidade, prioridade e vínculo com a tarefa.
Severidade descreve o impacto; prioridade determina a ordem de tratamento.
Retestar a reprodução e a regressão afetada antes de fechar.

Não criar Bugs fictícios para simular produtividade. Falha conhecida no
contrato novo não deve ser escondida alterando as expectativas da v1.

## Critério de conclusão

KAN-22 exige estratégia revisada, integridade da v1 conferida e nova execução
com resultado anexado. Os demais cartões exigem evidências específicas de
seus critérios. Lacunas e testes não executados devem permanecer explícitos.

## Evidência de execução — KAN-22 — 31/10/2026

- Node.js 22.16.0 e npm 10.9.2.
- Dependências instaladas com npm ci, usando o lockfile existente.
- API exclusiva na porta 18082.
- Banco H2 em memória: acompanhaai-postman-v1.
- Migrações V1 a V5 aplicadas.
- Integridade da fixture v1 verificada pelo executor.
- Execução completa: preparação, validações e limpeza.
- Resultado: 90 requisições e 246 asserções, sem falhas.
- Duração observada: 36,7 segundos.
- Fixture e dependências versionadas preservadas.

Observações:
- A instalação apresentou avisos de dependências descontinuadas.
  Atualizações deverão ser tratadas em uma versão futura.
- Esta rodada valida a API em H2; não substitui regressão em
  PostgreSQL, testes de navegador, carga ou produção.

