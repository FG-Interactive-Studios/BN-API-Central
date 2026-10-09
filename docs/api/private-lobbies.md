# Salas privadas — Lobby V1

## Autenticação

Toda ação REST exige `Authorization: Bearer <accessToken>`.
O frontend **não envia userId** em nenhum endpoint: a identidade vem
exclusivamente do JWT validado e da sessão atual. IDs de jogadores podem
aparecer na resposta como dados públicos de perfil.

## Endpoints

| Operação | Rota | Corpo | Resposta |
| --- | --- | --- | --- |
| Criar sala | `POST /api/matchmaking/lobbies` | vazio | 201 |
| Entrar por código | `POST /api/matchmaking/lobbies/join` | `{"code":"ABC234"}` | 200 |
| Minha sala | `GET /api/matchmaking/lobbies/me` | vazio | 200 |
| Prontidão | `PUT /api/matchmaking/lobbies/me/ready` | `{"ready":true}` ou false | 200 |
| Sair | `DELETE /api/matchmaking/lobbies/me` | vazio | 204 |

Exemplo de resposta:

```json
{
  "code":"ABCD23",
  "status":"WAITING",
  "revision":3,
  "capacity":2,
  "players":[
    {"id":10,"nickname":"Capitao","avatarId":"captain","ready":false,"connected":true},
    {"id":11,"nickname":"Almirante","avatarId":"submarine","ready":false,"connected":false}
  ]
}
```

Nenhuma resposta expõe e-mail, senha, sessão ou tabuleiro.

## Regras

- Código aleatório de seis caracteres, sem 0/1/I/O; pode ser digitado em minúsculas.
- Máximo de **dois jogadores** por sala e **uma sala por conta**.
- Entrar novamente na própria sala é idempotente, entrar em outra é 409.
- Código inválido é 400, inexistente é 404, sala cheia ou preparando é 409.
- Entrar um convidado reinicia a prontidão. Ambos podem definir prontidão
  enquanto a sala estiver `WAITING`.
- Os dois prontos produzem, **atomicamente**, `PREPARING`.
  A transição de volta pela operação de prontidão não é permitida.
- Convidado sair reabre a sala e limpa o status de pronto do anfitrião.
  Anfitrião sair encerra a sala para todos. Sair de novo é idempotente (204).
- `connected` reflete haver ao menos um socket autenticado e aberto
  daquela conta, não corresponde à prontidão. Desconectar não expulsa
  jogador e não marca derrota.
- `revision` cresce com as alterações; o cliente deve descartar
  snapshots de versões menores.

## Eventos em tempo real

Os sockets usam o ticket de conexão da
[camada WebSocket](realtime-websocket.md).

- `LOBBY_UPDATED`: `data` é o snapshot da sala, somente aos participantes.
- `LOBBY_LEFT`: `data={}`, confirma a saída própria.
- `LOBBY_CLOSED`: `data={}`, anfitrião encerrou a sala.

O cliente nunca escolhe destinatários de eventos nem envia comandos
de lobby pelo WebSocket (mensagens desconhecidas geram `UNSUPPORTED_EVENT`).

Em caso de recarga da página ou queda de conexão, o cliente obtém
novo ticket, reconecta e consulta `GET /api/matchmaking/lobbies/me`.
Ao conectar, o backend também emite `LOBBY_UPDATED` com o snapshot atual.

## MVP acadêmico

- Salas armazenadas somente em memória, uma instância da API,
  até 1000 salas simultâneas. Reiniciar a instância perde as salas.
- Não há limpeza automática por ausência prolongada do anfitrião.
- O estado `PREPARING` será integrado ao posicionamento de frota numa
  PR seguinte. Sem tabuleiro, disparos ou partida jogável nesta entrega.
- Sem novos schemas, migrations, dependências ou serviços externos.
