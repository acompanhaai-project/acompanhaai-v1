# Guia de contribuição

## Fluxo de trabalho

1. Selecionar a tarefa no Jira e movê-la para Em Progresso.
2. Atualizar a branch main.
3. Criar uma branch para a tarefa.
4. Implementar e verificar as alterações.
5. Criar commits pequenos e com propósito claro.
6. Enviar a branch e abrir um pull request para main.
7. Revisar as alterações e registrar as verificações realizadas.
8. Fazer merge após atender aos critérios de aceite.
9. Excluir a branch de trabalho após o merge.

O merge não encerra automaticamente a tarefa no Jira.
A conclusão depende da Definition of Done e do aceite.

## Branch principal

A branch main deve permanecer estável e apta a gerar uma versão.
Após a publicação inicial, as mudanças devem entrar por pull request.

## Nomes de branches

Formato:

<tipo>/<chave-jira>-<descricao-curta>

Usar descrição em minúsculas, sem espaços ou acentos,
com palavras separadas por hífen. Manter a chave Jira em maiúsculas.

| Tipo | Finalidade |
|---|---|
| feature | Nova funcionalidade |
| fix | Correção de defeito |
| docs | Documentação |
| refactor | Reorganização de código sem alterar comportamento |
| test | Criação ou ajuste de testes |
| chore | Configuração, ferramentas e manutenção |
| ci | Pipeline de integração e entrega |

Exemplos:

- feature/KAN-123-recuperacao-senha
- fix/KAN-124-validacao-email
- docs/KAN-125-convencoes-git
- ci/KAN-126-build-backend

Cada branch deve tratar uma tarefa ou mudança coesa.
Evitar acumular alterações sem relação no mesmo pull request.

## Mensagens de commit

Adotamos o seguinte padrão interno:

<tipo>(<escopo-opcional>): <chave-jira> <descricao>

Tipos: feat, fix, docs, refactor, test, chore e ci.

O tipo de commit para funcionalidade é feat;
o prefixo de branch correspondente é feature.

O escopo identifica a área afetada, como auth, profile ou applications.
A descrição deve explicar a mudança de forma objetiva, em português.
Não usar mensagens vagas como "ajustes" ou "corrigindo coisas".

Exemplos:

```text
feat(auth): KAN-123 adicionar recuperação de senha
fix(applications): KAN-124 corrigir validação da data de candidatura
docs: KAN-125 documentar convenções de contribuição
ci: KAN-126 executar testes do backend nos pull requests
```

Cada commit deve conter alterações relacionadas ao seu objetivo.
Nunca incluir senhas reais, chaves de API, cookies ou tokens de sessão.

## Pull requests

- Destino padrão: main.
- Título: KAN-123 — Descrição objetiva da alteração.
- Informar o problema, a solução e como a alteração foi verificada.
- Incluir link da tarefa no Jira.
- Revisar a aba Files changed antes do merge.
- Manter a chave Jira na mensagem final do squash.

Enquanto o projeto for individual, registrar a autorrevisão.
Quando houver outro colaborador, solicitar revisão independente.

## Verificações

Executar testes apropriados ao impacto da alteração.
Para mudanças somente de documentação, revisar conteúdo,
formatação, links e ausência de informações sensíveis.

A fixture Postman v1 e seus arquivos congelados devem ser preservados.
Evoluções dos testes devem usar uma nova versão.