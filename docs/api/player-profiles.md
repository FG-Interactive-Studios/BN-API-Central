
# Perfis do jogador — V1

A identidade nas rotas privadas vem sempre do JWT autenticado (`sub`).
O cliente **não fornece o ID do proprietário** em edições. O JWT assinado
é somente uma credencial; o backend consulta as informações atuais no banco,
inclusive após a alteração do nickname.

## Meu perfil

`GET /api/users/me` — exige `Authorization: Bearer <accessToken>`.

Retorna `200` com `id`, `nickname`, `email`, `avatarId`, `createdAt`.
Nunca retorna hash da senha, sessões, tokens ou dados administrativos.

`PATCH /api/users/me` — mesmo cabeçalho; pelo menos um campo presente:

```json
{"nickname":"NovoNick","avatarId":"submarine"}
```

Qualquer campo omitido (`null`) permanece inalterado. Valores aceitos em
`avatarId`: `captain` (padrão), `submarine`, `destroyer`, `carrier`.
`nickname` deve ter 3–30 caracteres após trim, sem caracteres de controle,
e é único sem diferenciar maiúsculas/minúsculas. Os outros atributos da
conta não podem ser modificados por esse endpoint.

Resposta `200` com o perfil atualizado; `400 VALIDATION_ERROR` para dados
inválidos e `409 NICKNAME_TAKEN` para colisões de nickname.
O nickname que aparece no JWT já emitido pode estar desatualizado; o frontend
deve usar `GET /api/users/me` ou a resposta do PATCH para exibição.

## Perfil público

`GET /api/users/{id}` — acesso **sem autenticação** somente para ID numérico
de jogador. Retorna `200` com **exatamente** `id`, `nickname`, `avatarId` e
`createdAt`, ou `404 PLAYER_NOT_FOUND`. Não vaza e-mail nem credenciais.
`GET /api/users/me` continua privado apesar de compartilhar o mesmo prefixo.

Os avatares são apenas identificadores estáveis; o frontend deverá associá-los
a imagens do catálogo quando o design estiver disponível. Não há upload de
arquivos nem aceitação de URL remota na V1.

## Regra de sessão única

Um jogador possui **no máximo uma sessão não revogada**. Um login novo,
inclusive em outro dispositivo/navegador, revoga o anterior no mesmo commit.
Tokens JWT e refresh anteriores passam a ser rejeitados.
A migration V4 reconcilia sessões antigas preservando os registros históricos
e cria um índice parcial único em PostgreSQL.
A autenticação do login é serializada por lock no registro de credenciais,
prevenindo dois logins concorrentes válidos para o mesmo jogador.

Endpoints removidos por redundância: `GET /api/auth/sessions`,
`POST /api/auth/logout-all` e `DELETE /api/auth/sessions/{sessionId}`.
Permanece `POST /api/auth/logout` para encerrar a sessão ativa.

**UX:** uma nova autenticação em outro dispositivo encerra imediatamente a
anterior; em uma partida, isso não deve resultar automaticamente em derrota.
A lógica futura do jogo deverá manter seu estado no servidor e oferecer
reconexão mediante uma autenticação válida.

## Fora deste escopo

Upload de imagem, alteração de e-mail, moedas, ranking, inventário e
estatísticas. A mudança de senha está no domínio `auth` e é documentada em
[Segurança da conta](account-security.md).
