# Segurança da conta — alteração de senha V1

A alteração de senha ocorre exclusivamente na API de autenticação e nunca
aceita `userId` enviado pelo cliente. A identidade é obtida do JWT validado
e da sessão ativa, verificados no servidor.

## Alterar senha

`POST /api/auth/change-password`

Header: `Authorization: Bearer <accessToken>`.

Payload:
```json
{
  "currentPassword": "senha-atual-exemplo",
  "newPassword": "uma-nova-senha-segura"
}
```

Respostas:
- **204 No Content**: senha alterada com sucesso, **sem logout**.
  A sessão atual, o JWT ainda válido e o refresh token existente continuam
  funcionando normalmente. O cliente mantém seus tokens, não precisa refazer
  o login e não recebe tokens novos na resposta.
- **401 Unauthorized**: JWT ou sessão inválidos, revogados ou expirados.
- **400 VALIDATION_ERROR**: campos ausentes ou senha nova com menos de 8,
  mais de 72 caracteres, mais de 72 bytes UTF-8 ou somente espaços.
- **400 INVALID_CURRENT_PASSWORD**: senha atual incorreta.
- **400 PASSWORD_REUSE**: senha nova igual à senha atual.

A aplicação usa BCrypt para proteger a senha, conferindo sua correspondência
com o hash existente. Apenas o hash novo é persistido em uma transação:
**nenhuma sessão é revogada** e não há mudança de schema.

Logins concorrentes são serializados com a alteração pela linha da credencial:
o login valida novamente a senha **após** obter o lock, impedindo o uso da
senha antiga caso ela tenha sido trocada durante a tentativa de login.

A política preserva a sessão única já implementada: **trocar a senha não
é um novo login** e não provoca logout, reinicialização de sessão nem perda do
refresh token. O JWT continua válido até sua expiração normal, e o refresh
continua a rotacionar normalmente. Uma tentativa futura de login com a senha
antiga é rejeitada; um **novo login explícito com a senha nova** continua
substituindo a sessão anterior, conforme a regra de uma sessão por jogador.
Partidas em andamento não devem ser interrompidas pela troca de senha.

## Fora desta entrega

Recuperação de senha por e-mail, confirmação de e-mail, alteração de e-mail,
segundo fator e rate limiting. Antes da disponibilização pública, adicionar
limitação de tentativas nas rotas de login, cadastro e autenticação sensível.
Não registrar senhas, tokens ou o corpo da requisição em logs.
