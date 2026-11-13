# KAN-63 — Acessibilidade e compreensão da revisão

## Escopo e ambiente

- Tela `/candidaturas/importar`: formulário, fila e leitura, revisão, atenção
  (falha ou link bloqueado), vaga salva e importação indisponível.
- Build de produção do Angular 21 com API simulada, Chrome 153 headless,
  axe-core 4.14.0 (WCAG 2.2 A/AA e boas práticas) e registro das regiões
  `status`/`alert`.
- Base: `main` em `21b2c3a` (KAN-62), 13/11/2026.

## Achados

| ID | Achado | Critério | Situação |
| --- | --- | --- | --- |
| R1 | Ao enviar a importação e quando a revisão ficava pronta, o conteúdo era trocado e o foco caía no início da página (`body`); teclado e leitor de tela perdiam o ponto | 2.4.3 | Corrigido: o foco vai para o título da nova etapa ("Na fila de importação", "Revise os dados da vaga", falha, "Vaga salva") |
| R2 | Mudanças assíncronas (fila → leitura → pronta) dependiam de regiões `status` recém-criadas, que muitos leitores não anunciam; a troca de "Na fila" para "Lendo a vaga" não era anunciada | 4.1.3 | Corrigido: região permanente anuncia "Importação na fila.", "Lendo a vaga.", "Dados prontos para revisão.", a falha e "Vaga salva." |
| R3 | O nome acessível dos campos incluía o selo e o trecho de evidência ("Título Trecho da vaga: “…”"), longo e confuso | 1.3.1 / 2.4.6 | Corrigido: nome só com o rótulo; selo e evidência como descrição (`aria-describedby`) |
| R4 | O selo "Não encontrado" continuava visível depois de a pessoa preencher o campo, parecendo erro | Compreensão | Ajuste de UX: "Não encontrado na vaga", que continua verdadeiro após o preenchimento |
| R5 | Salário 0 gerava a mensagem genérica "Confira os campos do formulário" | 3.3.1 / 3.3.3 | Corrigido: "Informe um salário maior que zero ou deixe o campo em branco." e dica "Deixe em branco se a vaga não informa o salário." |
| R6 | A descrição do link referenciava `link-hint` mesmo na importação por texto, quando o elemento não existe | 4.1.2 | Corrigido: referência só na importação por link |

## Verificações

| Verificação | Resultado |
| --- | --- |
| axe-core no formulário, na fila e na revisão | 0 violações antes e depois das correções |
| Foco após enviar e quando a revisão fica pronta | `body` antes; título da etapa depois |
| Anúncios | Sequência "Importação na fila." → "Lendo a vaga." → "Dados prontos para revisão." |
| Nomes acessíveis | "Título", "Empresa", "Localização", "Salário (R$)" etc., sem evidência no nome |
| Salário ausente × zero | Ausente: campo vazio, selo "Não encontrado na vaga" e envio com `salary`, moeda e período nulos. Zero: bloqueado com mensagem própria. Nos detalhes, ausente aparece como "Não informado" |
| Texto da página como HTML | `<img onerror>`, `<b>` e `<script>` aparecem como texto no campo, na evidência e na descrição; nenhum elemento é criado e nenhum script roda |

## Regressão automatizada

- Vitest (173 testes): nomes e descrições, anúncios e foco por etapa, salário
  ausente × zero e marcação hostil exibida como texto.
- Selenium no CI: a jornada de importação passa a exigir o foco no título da
  revisão e o selo "Não encontrado na vaga"; o axe-core já roda na revisão.

## Roteiro de demonstração

Sucesso:

1. Entrar e abrir "Importar vaga"; escolher "Descrição colada".
2. Colar uma vaga com cargo, empresa, local, salário mensal e requisitos;
   pressionar "Importar". O foco vai para "Na fila de importação".
3. Aguardar "Dados prontos para revisão.": o foco vai para "Revise os dados da
   vaga". Com Tab, cada campo é lido pelo nome, seguido do trecho de origem.
4. Ajustar um campo, escolher "Registrar inscrição" e salvar. Abre a candidatura.

Fallback:

1. Importar um link de site que bloqueia leitura ou uma descrição com o
   provedor de IA indisponível.
2. Link bloqueado: o foco vai para o título da falha, e é possível colar a
   descrição na mesma tela. IA indisponível: a revisão abre só com a descrição,
   e os campos mostram "Não encontrado na vaga".
3. Preencher título e empresa à mão, deixar o salário em branco e salvar.
   Nos detalhes, o salário aparece como "Não informado".

A demonstração com o Maicon e a sessão com leitor de tela real (NVDA) ficam
para o aceite do card, seguindo este roteiro.
