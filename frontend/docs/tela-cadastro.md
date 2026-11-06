# Tela de cadastro

Interface de `/cadastro` baseada em `acompanha-ai-src.zip`, com componentes
standalone, `OnPush` e formulário reativo tipado. O login mantém o mesmo padrão
visual e inclui navegação para o cadastro. Os dois compartilham os estilos do
cartão e a diretiva de iluminação e inclinação.

## Comportamento

- Nome completo com nome e sobrenome e validação de e-mail.
- Senha com pelo menos oito caracteres, minúscula, maiúscula, número e símbolo.
- Indicador visual de força e checklist de requisitos.
- Confirmação de senha e botões para mostrar/ocultar os dois campos.
- Foco no primeiro campo inválido após envio.
- Campos bloqueados durante carregamento e apresentação de erro ou sucesso.
- Retorno para `/entrar` pelo link da tela e pela confirmação de sucesso.

## Simulação

`src/app/shared/auth.service.ts` usa um atraso de 1,6 segundo e não faz chamadas
HTTP. A mensagem de confirmação por e-mail é parte da demonstração; nenhum
e-mail é enviado e nenhuma conta é persistida.

| E-mail | Resultado simulado |
| --- | --- |
| `existente@exemplo.com` | E-mail já cadastrado |
| `rede@exemplo.com` | Erro de conexão |
| Outro e-mail válido | Sucesso |

Os requisitos de senha e os estados desta interface precisarão ser alinhados
ao contrato real da API na etapa de integração.
