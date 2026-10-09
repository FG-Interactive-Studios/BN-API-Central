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
- **204 No Content**: senha alterada com sucesso, **sessão revogada imediatamente**.
  O cliente deve apagar os tokens locais e apresentar o login; a resposta não
  fornece outro JWT ou refresh token.
- **401 Unauthorized**: JWT ou sessão inválidos, revogados ou expirados.
- **400 VALIDATION_ERROR**: campos ausentes ou senha nova com menos de 8,
  mais de 72 caracteres, mais de 72 bytes UTF-8 ou somente espaços.
- **400 INVALID_CURRENT_PASSWORD**: senha atual incorreta.
- **400 PASSWORD_REUSE**: senha nova igual à senha atual.

A aplicação usa BCrypt para proteger a senha, conferindo sua correspondência
com o hash existente. Apenas o hash novo é persistido. A alteração do hash e
a revogação da sessão ocorrem na **mesma transação** e não há mudança de schema.

Logins concorrentes são serializados com a alteração pela linha da credencial:
o login valida novamente a senha **após** obter o lock, impedindo o uso da
senha antiga caso ela tenha sido trocada durante a tentativa de login.

A política preserva a sessão única já implementada: após a troca, nenhuma
sessão permanece ativa até um novo login. Em futuros jogos multiplayer, a
autenticação não deve eliminar o estado da partida no servidor.

## Fora desta entrega

Recuperação de senha por e-mail, confirmação de e-mail, alteração de e-mail,
segundo fator e rate limiting. Antes da disponibilização pública, adicionar
limitação de tentativas nas rotas de login, cadastro e autenticação sensível.
Não registrar senhas, tokens ou o corpo da requisição em logs.
