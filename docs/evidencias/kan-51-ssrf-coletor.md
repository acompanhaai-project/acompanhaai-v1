# KAN-51 — Coletor contra SSRF e conteúdo hostil

## Escopo e ambiente

- Coletor de páginas (`ImportPageCollector`), política de endereços
  (`ImportAddressPolicy`), leitura da página (`ImportPageReader`) e a
  importação por link pela API (`POST /api/import-tasks`).
- Servidores HTTP locais em `127.0.0.1` com porta aleatória e DNS roteirizado
  nos testes. Nenhum teste acessa metadados de nuvem, rede privada ou serviço
  interno real: os destinos internos são recusados antes da conexão, e o
  servidor local registra cada requisição recebida para provar isso.
- Base: `main` em `7678b67` (KAN-46), Spring Boot 4.0.9, 12/11/2026. A suíte
  roda em H2 localmente e em PostgreSQL nos jobs do CI.

## Cobertura

| Ataque | Casos | Resultado |
| --- | --- | --- |
| Loopback, privadas, link-local e reservadas | `127.0.0.1`, `127.0.0.2`, `localhost`, `LOCALHOST`, `0.0.0.0`, `10.0.0.1`, `192.168.0.1`, `100.64.0.1`, `169.254.169.254` | `SOURCE_NOT_ALLOWED`, sem conexão |
| Formas alternativas de IPv4 | `2130706433`, `0x7f.0.0.1`, `127.1`, `%31%32%37.0.0.1` | Recusadas, sem conexão |
| IPv6 e IPv4 embutido | `[::1]`, `[::]`, `[::ffff:127.0.0.1]`, `[::ffff:7f00:1]`, `[::ffff:a9fe:a9fe]`, `[64:ff9b::7f00:1]`, `[2002:7f00:1::]`, `[fd00::1]`, `[fe80::1]`, zona `%25eth0` | `SOURCE_NOT_ALLOWED`, sem conexão |
| DNS | Nome com resposta pública e privada ao mesmo tempo; nome que muda para `169.254.169.254` entre um redirecionamento e outro | Recusado; o host é resolvido de novo a cada salto, sem reaproveitar conexão |
| Redirecionamentos | Para `127.0.0.1`, `localhost`, `[::ffff:127.0.0.1]`, `0.0.0.0`, `169.254.169.254`, `[fd00::1]`, `//169.254.169.254`, `file:`, `ftp:`, `gopher:`, `javascript:`, `data:`, URL com credenciais; laço infinito | `SOURCE_NOT_ALLOWED` após o primeiro salto; laço encerrado em 5 redirecionamentos |
| URL disfarçada | `127.0.0.1\@host`, `host@127.0.0.1`, `127.0.0.1#@host`, espaços nas pontas | Recusada sem requisição |
| Esquemas, credenciais e portas | `file:`, `ftp:` e credenciais rejeitados pela API com 422 antes da fila; portas diferentes de 80/443 recusadas pela política de produção | Sem tarefa criada ou sem conexão |
| Respostas enormes | Corpo infinito, `Content-Length` acima do limite, gzip de 4 MiB descompactado | `SOURCE_NO_USABLE_CONTENT` em menos de 5 s, sem ler até o fim |
| Respostas lentas | Cabeçalho após 8 s, corpo a 1 byte a cada 100 ms | Interrompidas no tempo limite e devolvidas à fila |
| Cabeçalhos abusivos | 200 cabeçalhos; cabeçalho de 32 KiB | `SOURCE_NO_USABLE_CONTENT` |
| Conteúdo ativo | `onerror`, `iframe`, link `javascript:`, `svg` com script, `template`, `@import` em CSS | Fora do texto proposto; nenhum endereço ou instrução oculta aproveitado |
| JSON-LD malicioso | 5000 níveis de aninhamento, `@graph` profundo, JSON truncado, título de 500 mil caracteres | Ignorado ou limitado a 255 caracteres, sem exceção |
| Marcação patológica | 200 mil `<a` sem fechamento, comentários e atributos abertos, entidades repetidas | Leitura em menos de 2 s por página |

Pela API, 11 destinos internos passam pelo worker com o coletor e a política
de produção e terminam em `FAILED` com "A URL não é permitida pela política de
segurança.". Nenhum resultado é exposto e o servidor local não recebe
requisições.

## Resultado

- `ImportCollectorHostileInputTest`: 31 testes, 0 falhas.
- `ImportSsrfHttpIntegrationTest`: 14 testes, 0 falhas.
- `ImportPageReaderTest`: 16 testes (3 novos), 0 falhas.
- Suíte backend: 464 testes, 0 falhas, 8 ignorados.

Nenhum defeito foi encontrado: os novos casos confirmaram as proteções da
KAN-48 sem mudança no código de produção.

## Riscos residuais e recomendação

- Resolver o nome informado é inevitável. Por isso um usuário consegue fazer o
  servidor consultar DNS de qualquer domínio, sem receber o conteúdo de destinos
  internos.
- A defesa está na aplicação. Em produção (KAN-73), adicionar uma camada de
  rede, quando a hospedagem permitir: bloquear a saída do backend para
  `169.254.169.254` e para as redes privadas, liberando só o banco e os
  serviços que a aplicação usa.
