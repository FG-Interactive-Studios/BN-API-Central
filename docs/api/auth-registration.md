# Cadastro de usuário

`POST /api/auth/register` recebe JSON com `nickname`, `email` e `password`.
Exemplo:

```json
{"nickname":"Captain","email":"captain@example.com","password":"safe-password-123"}
```

Retorna `201 Created` com `id`, `nickname`, `email` normalizado e `createdAt`.
Falhas: `400 VALIDATION_ERROR` com `fieldErrors` ou `409 REGISTRATION_CONFLICT`.
Nickname tem 3–30 caracteres (sem caracteres de controle), é único sem distinção de caixa.
E-mail é normalizado para minúsculas e tem limite de 254 caracteres.
Senha tem 8–72 caracteres, máximo de 72 bytes UTF-8, e é armazenada somente como hash BCrypt.
A criação de `users` + `auth` é transacional; a migration `V2__registration_schema.sql` mantém a restrição de unicidade no banco.
Esta entrega não implementa login nem endpoints públicos para edição/remoção de contas.
