# Recuperação de senha

Interfaces fornecidas em `acompanha-ai-src_1.zip`, com os mesmos temas, efeitos
neon e cartão das telas de entrada e cadastro. O backend permanece sem alterações.

## Solicitar link

A rota `/recuperar-senha` pode receber `?email=design@example.test`. O link
**Esqueci minha senha** do login preenche esse parâmetro quando há e-mail digitado.

O formulário valida o endereço, mostra carregamento, confirmação e erro. Após
o envio simulado, o reenvio aguarda 30 segundos. A ação **Usar outro e-mail**
retorna ao formulário vazio. A confirmação usa a mesma mensagem para qualquer
conta; não verifica se o endereço existe.

## Redefinir

| Endereço local | Estado demonstrado |
| --- | --- |
| `/redefinir-senha` | Link inválido |
| `/redefinir-senha?token=expirado` | Link expirado |
| `/redefinir-senha?token=demo-design` | Formulário e sucesso |
| `/redefinir-senha?token=rede` | Erro de conexão ao salvar |

O formulário reutiliza as regras e o indicador de senha do cadastro, exige
confirmação e permite mostrar/ocultar os dois campos. Links inválidos oferecem
nova solicitação; a confirmação de sucesso oferece retorno ao login.

## Limites da demonstração

`AuthService` não executa HTTP. `rede@exemplo.com` simula erro na solicitação
ou reenvio. Os demais endereços válidos simulam confirmação. Tokens não vazios
diferentes de `expirado` são aceitos para demonstrar a interface.

Nenhum e-mail é enviado, nenhuma senha é alterada e não há consumo real de
token. O prazo de 30 minutos exibido na tela é texto do protótipo, sem contagem
de expiração. Os contratos e prazos reais serão alinhados na integração com a API.
