# KAN-45 — Fluxo manual completo de candidaturas

## Ambiente

- Execução: [Backend CI #43260228702](https://github.com/acompanhaai-project/acompanhaai-v1/actions/runs/43260228702),
  job `backend-browser-qa`, commit `0bdcc7f`, em 12/11/2026.
- `ubuntu-latest`, Corretto 25, Node.js 22, Chrome do runner (headless) com
  driver do Selenium Manager e Selenium 4.52.0.
- API Spring Boot e Angular iniciados pela suíte; PostgreSQL 18 exclusivo do
  job, com migrações aplicadas em banco vazio.
- Contas com UUID em `example.test`, removidas ao fim do cenário. O banco é
  descartado com o job.

## Resultado

22 cenários, 0 falhas, 0 erros e 0 ignorados. O resumo por cenário fica na
página da execução; relatórios JUnit, capturas e versão do ambiente ficam no
artefato `testes-navegador-<commit>`.

| Método | Combinações | Resultado |
| --- | --- | --- |
| `manualFlowAcrossAccounts` | 1440px claro, 390px escuro | 2 de 2 |
| `accountLifecycle` | 1440/390px, claro/escuro | 4 de 4 |
| `keyboardAndFieldErrors` | 1440/390px, claro/escuro | 4 de 4 |
| `profileLifecycle` | 1440/390px, claro/escuro | 4 de 4 |
| `boardLifecycle` | 1440/390px, claro/escuro | 4 de 4 |
| `applicationLifecycle` | 1440/390px, claro/escuro | 4 de 4 |

## Fluxo manual entre contas

| Etapa | Verificação |
| --- | --- |
| Criação | Candidatura criada pela interface e histórico inicial na tela |
| Edição | Local e anotações persistidos e exibidos nos detalhes |
| Conflito 409 | Alteração feita em outra aba; o envio do rascunho mostra o aviso de conflito, preserva o texto digitado e recarrega os dados da outra aba |
| Erro de rede | API bloqueada no navegador; mensagem de falha de conexão, dados preservados e novo envio aceito após liberar a rede |
| Quadro | Pesquisa pelo identificador exclusivo e etapa na URL; card movido para fora da etapa filtrada sai da lista com aviso |
| Histórico | "Candidatura realizada → Entrevista" na tela e `APPLIED`, `INTERVIEW` na API |
| Voltar dos detalhes | URL, pesquisa e etapa restauradas |
| Outra conta | Quadro vazio, pesquisa sem resultado, detalhes por URL sem dados e 404 em consulta, histórico, alteração e exclusão pela API |
| Limpeza | Candidatura excluída pela interface (404 em seguida) e exclusão das duas contas (204) |

## Ajustes de estabilidade

A primeira execução no CI, em Chrome no Linux, expôs duas esperas frágeis em
jornadas da KAN-40. Elas passavam no Edge local, mas falharam uma vez cada:

- `profileLifecycle` lia as iniciais do avatar logo após cancelar a prévia da
  foto e encontrava o texto vazio. Agora espera o texto "PE".
- `boardLifecycle` pressionava Tab antes de o botão "Mover" ser habilitado,
  e o foco ia para o card seguinte. Agora espera o botão ficar clicável. O
  mesmo cuidado foi aplicado ao clique em "Mover" do fluxo novo.

Nenhum dos dois casos era defeito da aplicação, então não foi aberto Bug.

## Limites

- A execução local no Windows não foi concluída: o Selenium Manager não
  obteve o driver do Edge 157. A suíte depende de navegador Chromium (CDP).
- Firefox, Safari, leitores de tela e dispositivos físicos ficam fora desta
  suíte.
