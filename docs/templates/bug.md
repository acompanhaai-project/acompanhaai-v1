# Modelo de Bug — copiar para um item do tipo Problema

Preencher os campos entre colchetes e remover estas instruções ao usar.
Consultar o [fluxo de Bugs](../bugs.md). Não criar um cartão de exemplo como
se fosse defeito real. Não incluir senhas, tokens, cookies ou dados pessoais.

## Resumo

[Componente] — [ação/condição que provoca o defeito] — [efeito observado]

## Classificação e triagem

- Etapa de QA: [Reportado / Triagem / Correção / Revisão / Reteste / Fechado / Reaberto]
- Componente: [backend / frontend / testes / infraestrutura]
- Severidade proposta ou confirmada: [Crítica / Alta / Média / Baixa]
- Impacto e alcance: [jornada, usuários afetados e consequência observada]
- Prioridade: [P0 / P1 / P2 / P3; justificar a decisão]
- Contorno ou contenção: [procedimento validado / inexistente / não investigado]
- Reportado por e data: [responsável; data e fuso]
- Responsável pela triagem: [nome ou a definir]
- Responsável pela correção: [nome ou a definir]
- Responsável pelo reteste: [nome ou a definir]
- Meta de triagem: [severidade, janela acordada e prazo]
- Primeira triagem: [data, decisão e pendências; preencher quando realizada]

## Vínculos e versões

- História/tarefa e critério afetado: [chave, link e critério]
- Cartões relacionados/duplicatas: [links ou nenhum identificado]
- Bloqueia história/release: [qual e por quê / não bloqueia]
- Versão afetada: [commit ou tag]
- Release de correção pretendida: [versão existente / a definir]

## Ambiente

- Ambiente: [local / CI / homologação / produção; não informar credenciais]
- Sistema, runtime e versões relevantes: [Java/Spring/Node/navegador, conforme o caso]
- Banco e migração: [H2/PostgreSQL e versão Flyway; sem senha ou URL privada]
- Configuração relevante: [perfil, opção ou condição sem segredo]

## Pré-condições e massa de teste

[Contas fictícias, estado inicial, permissões e preparação reproduzível.
Descrever obtenção de sessão/token sem copiar valores.]

## Passos para reproduzir

1. [Primeira ação ou comando necessário.]
2. [Ação que provoca o comportamento.]
3. [Como observar o resultado.]

## Resultado esperado

[Comportamento exigido e referência ao critério ou contrato.]

## Resultado obtido

[Status, mensagem ou comportamento observado. Separar fatos de hipóteses.]

## Frequência e evidências

- Frequência: [X falhas em Y tentativas, com condições; ou ainda não medida]
- Evidência sanitizada: [relatório, captura ou trecho mínimo de log]
- Execução do CI, quando houver: [link e commit]
- Hipótese de causa: [hipótese explicitamente identificada / não investigada]

## Correção e revisão

- Causa confirmada: [preencher após investigação]
- Branch/commit/PR: [links ou identificadores]
- Alteração e impacto: [o que mudou e quais fluxos podem ser afetados]
- Verificações da correção: [testes e resultados reais]
- Revisão e CI: [responsável, data, resultado e pendências]

## Reteste

- Responsável e data: [nome; data e fuso]
- Commit/tag e ambiente retestados: [versão efetivamente executada]
- Reprodução original: [comando/passos, esperado e obtido]
- Regressão afetada: [cenários e resultados; justificar o que não foi executado]
- Repetições, se intermitente: [quantidade, condições e resultados]
- Evidência sanitizada: [links]
- Resultado: [Aprovado / Reprovado / Bloqueado; justificar]

## Decisão final

- Decisão: [Corrigido / Reaberto / Duplicado / Não é defeito; justificar]
- Cartão principal, se duplicado: [link]
- Versão de correção integrada: [commit/tag ou não aplicável]
- Release publicada: [versão / publicação pendente / não aplicável]
- Pendências e próximo passo: [ação, responsável e prazo, quando aplicável]
- Aceite: [responsável, data e justificativa]
