# Arquitetura do Acompanhaai

## Objetivo

O Acompanhaai permite registrar vagas de interesse e acompanhar
candidaturas durante processos seletivos.

O usuário pode cadastrar uma vaga manualmente. Está planejada a
importação por URL ou descrição, com extração de informações e
revisão obrigatória antes de salvar.

## Visão geral

A arquitetura adota um backend monolítico organizado em camadas,
um frontend Angular e um banco PostgreSQL.

Fluxo de uma requisição:

Frontend → Resource → Service → Repository → PostgreSQL

O frontend e a importação ainda estão planejados. O backend já
possui autenticação, perfil e CRUD manual de candidaturas.

## Decisão 1 — Backend monolítico em camadas

### Escolha

Java 25 e Spring Boot 4.0+, com as seguintes responsabilidades:

| Camada | Responsabilidade |
|---|---|
| Entities | Representar os dados e relacionamentos persistidos. |
| Repositories | Consultar e persistir dados. |
| Services | Executar regras de negócio e coordenar transações. |
| Resources | Receber requisições HTTP e produzir respostas. |
| DTOs | Definir os dados de entrada e saída da API. |

As entidades não são retornadas diretamente pela API.
Os DTOs controlam quais informações podem entrar e sair.

### Motivo

Essa organização acompanha o roadmap de estudos e permite
desenvolver o produto sem a complexidade operacional de
serviços distribuídos.

### Consequências

O backend possui uma unidade principal de implantação.
Mudanças exigem preservar a separação de responsabilidades
para evitar regras de negócio espalhadas pelos controllers.

## Decisão 2 — Persistência e migrações

### Escolha

- PostgreSQL como banco de produção.
- Flyway para versionar alterações na estrutura.
- Hibernate configurado para validar a estrutura.
- H2 para desenvolvimento e testes locais.
- Banco de testes separado dos dados reais.

### Motivo

O domínio possui relacionamentos, restrições de integridade
e operações que precisam acontecer na mesma transação.

### Consequências

Alterações de estrutura exigem novas migrações.
Migrações já aplicadas em ambientes compartilhados devem
ser preservadas.

Testes em H2 não substituem a validação em PostgreSQL,
principalmente para SQL, tipos e comportamento específicos.

## Modelo principal

### User

Representa o proprietário dos dados e sua conta de acesso.

### Job

Representa as informações da oportunidade: título, empresa,
origem, descrição e detalhes adicionais.

### Application

Representa o acompanhamento pessoal daquela oportunidade:
etapa, data de candidatura, observações e histórico.

Uma vaga salva ainda pode não ter uma inscrição realizada.
Por isso, SAVED representa interesse; APPLIED representa
candidatura enviada.

Na implementação atual, cada candidatura referencia uma vaga
própria. Não existe um catálogo compartilhado de vagas entre
usuários.

### ApplicationStatusHistory

Registra mudanças de etapa. A alteração da etapa e a gravação
do histórico devem ocorrer na mesma transação.

### ImportTask

Representa uma solicitação de importação de vaga por URL ou
descrição. Registra a entrada, o estado do processamento,
as tentativas, o resultado extraído e possíveis falhas.

Toda ImportTask pertence obrigatoriamente a um User.
Essa relação identifica o proprietário e permite restringir
o acesso à tarefa e ao seu resultado.

A associação com Job é opcional. Uma importação pode começar
sem uma vaga cadastrada e ser vinculada a ela posteriormente,
conforme o fluxo de confirmação.

ImportTask acompanha o processamento; Job guarda os dados
da oportunidade; Application registra o acompanhamento
pessoal daquela oportunidade.

O processamento está planejado para um worker na mesma
aplicação, sem manter a requisição inicial aguardando
a coleta e a extração.

A entidade e sua estrutura de persistência já existem.
O worker e a confirmação da importação ainda serão implementados.

## Decisão 3 — Autenticação por sessão

### Escolha

Usar Spring Security, sessão HTTP e proteção CSRF.

O identificador da sessão é transportado pelo cookie JSESSIONID.
Requisições de escrita exigem um token CSRF válido.

Senhas são armazenadas como hash PBKDF2 com salt individual.
Senha e hash não são retornados nos DTOs de resposta.

### Motivo

A sessão atende ao acesso pelo navegador e permite manter
o controle da autenticação no servidor.

### Consequências

O frontend precisa preservar os cookies e renovar o token
CSRF após o login.

Em produção, o fluxo exige HTTPS e cookie Secure.
Atualmente, as sessões ficam na memória da aplicação:
reiniciar o backend exige um novo login.

Persistência ou compartilhamento de sessões será avaliado
antes da publicação e de eventual execução em múltiplas instâncias.

## Decisão 4 — Frontend Angular

### Escolha planejada

Angular e TypeScript para autenticação, perfil, formulários,
quadro de candidaturas e revisão de importações.

### Motivo

Permitir uma interface tipada, organizada em componentes
e integrada ao contrato da API.

### Consequências

O frontend deve tratar validações, sessão expirada,
conflitos de edição e falhas de comunicação.

A implantação deve preferir a mesma origem para frontend
e API. Uso de origens diferentes exige configuração explícita
de CORS e cookies.

## Decisão 5 — Importação assíncrona

### Escolha planejada

Registrar uma tarefa de importação e processá-la por um worker
na mesma aplicação.

Fluxo:

1. Receber URL ou descrição.
2. Registrar a tarefa.
3. Coletar o conteúdo, quando houver URL.
4. Extrair e validar as informações.
5. Apresentar uma proposta ao usuário.
6. Salvar somente após revisão e confirmação.

### Motivo

Coleta de páginas e chamadas de IA podem ser lentas ou falhar.
O processamento assíncrono permite acompanhar o estado
sem manter a requisição inicial esperando pela conclusão.

### Consequências

O worker precisará controlar concorrência, expiração da posse,
tentativas e recuperação após falhas.

A coleta terá limites de tempo e tamanho e proteção contra
acesso a endereços internos.

Páginas bloqueadas ou que exigem login terão como alternativa
a descrição colada pelo usuário.

## Decisão 6 — Extração assistida por IA

### Escolha planejada

Usar a Gemini Developer API como provedor inicial de IA,
utilizando a modalidade gratuita enquanto estiver disponível
e atender às necessidades do projeto.

O modelo específico e as cotas serão confirmados no Google
AI Studio durante a implementação. Provedor e modelo serão
configuráveis, permitindo substituição futura.

### Motivo

Permitir experimentar a extração estruturada de informações
de vagas sem custo inicial de chamadas, dentro das cotas
gratuitas disponíveis.

### Consequências

- A gratuidade e os limites precisam ser reavaliados na implementação.
- A integração será executada exclusivamente pelo backend.
- A chave será fornecida por configuração privada, fora do Git.
- Somente conteúdo público da vaga será enviado ao provedor,
  sem dados pessoais, currículos ou observações privadas.
- Falhas ou esgotamento de cota não impedirão o cadastro manual.
- Não haverá mudança automática para um serviço pago.
- A adequação para produção será reavaliada antes da publicação.

### Regras

- Extrair somente informações sustentadas pela descrição.
- Não inventar salário ou habilidades ausentes.
- Manter campos ausentes vazios.
- Permitir correção antes da confirmação.
- Guardar credenciais do provedor somente no backend.
- Tratar instruções presentes na página como conteúdo externo,
  sem permitir que alterem as regras do processamento.

### Referências da decisão

Consulta em 30/10/2026: a modalidade gratuita da Gemini Developer API
existe para modelos elegíveis, com limites dependentes do projeto/modelo.
A disponibilidade para esta conta será confirmada na implementação.

- [Preços e modalidade gratuita](https://ai.google.dev/gemini-api/docs/pricing).
- [Cotas e limites](https://ai.google.dev/gemini-api/docs/rate-limits).
- [Condições de uso e tratamento de dados](https://ai.google.dev/gemini-api/terms).

Os termos de serviços não pagos preveem uso de entradas/respostas para
melhoria dos produtos e possível revisão humana. Por isso, restringir o
envio ao conteúdo público da vaga, removendo dados pessoais presentes nele;
textos fornecidos pelo usuário também precisam respeitar essa restrição.

## Segurança e configuração

Credenciais reais ficam fora do Git.

Dados de produção, desenvolvimento e testes devem ser separados.
Relatórios com cookies ou tokens não devem ser publicados.

A foto de perfil é opcional e atualmente é armazenada no banco,
com validação de tamanho e formato.

## Limites desta documentação

Este documento registra a arquitetura atual e as decisões
planejadas. Não comprova a implementação das funcionalidades
futuras nem a aprovação da aplicação para produção.
