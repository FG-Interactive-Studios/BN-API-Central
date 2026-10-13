# Encerramento de partidas: desistência e desconexão (MVP)

## Regras de encerramento

**Ações explícitas** e **queda temporária de conexão** são situações diferentes.

- `POST /api/matches/me/forfeit` (JWT, sem body) faz o jogador
  autenticado **desistir** de sua partida em `PLAYING`. O adversário é
  vencedor e `finishReason="ABANDONED"`.
- `DELETE /api/matchmaking/lobbies/me`, quando o lobby está em
  `PLAYING`, **também significa desistência**. A primeira chamada retorna
  204, finaliza a partida, mas mantém ambos os participantes vinculados
  ao resultado, para que possam consultar `GET /api/matches/me`.
- Após o jogo terminar (`FINISHED`), `DELETE /api/matchmaking/lobbies/me`
  apenas **sai da tela de resultado**. O outro participante ainda pode
  recuperar o resultado normalmente. Quando os dois saírem, o backend
  libera a sala, os tabuleiros e o estado em memória.
- Em `WAITING`/`PREPARING`, o `DELETE` mantém o comportamento anterior:
  anfitrião fecha a sala; convidado sai e devolve a sala a `WAITING`.
- Desistir repetidamente com a **mesma** conta é idempotente:
  nenhuma nova vitória nem nova revisão é criada.
  Se a partida já terminou de outra forma, o pedido de desistência
  recebe 409.

**Importante para o frontend:** como `DELETE` em `PLAYING`
significa desistir, exibir confirmação antes da ação. Um refresh da
página ou fechamento acidental de aba **não deve** enviar `DELETE`.
Uma partida finalizada permanece consultável até que cada jogador
saia explicitamente do lobby.

## Desconexão (somente durante PLAYING)

A presença é medida pelo **WebSocket autenticado** da PR #8.
O jogador é considerado conectado se tem ao menos um WebSocket
válido e aberto; não basta um JWT armazenado ou um request REST isolado.

- Quando a partida começa com um jogador sem socket, ou quando seu
  **último** socket cai, começa a contagem de reconexão.
- Prazo padrão **120 segundos** (configurável por
  `MATCH_DISCONNECT_GRACE_SECONDS`, entre 1 e 3600).
- A simples queda **não concede vitória nem perde a vaga**.
  Reabrir o WebSocket autenticado antes do prazo cancela o deadline
  e preserva tabuleiro, tiros e turno. Troca normal de access JWT
  não obriga o jogador a desconectar o WebSocket.
- Se o prazo de apenas um jogador esgotar **com o adversário online**,
  o adversário vence: `finishReason="DISCONNECT_TIMEOUT"`.
- Se **ambos** estiverem desconectados, nenhum deles ganha por
  conveniência. O jogo espera enquanto o outro ainda está dentro
  da própria tolerância; quando **ambos os prazos expirarem**, termina
  **sem vencedor**: `finishReason="BOTH_DISCONNECTED"`.
- O watchdog roda a cada cerca de **5 segundos**
  (`MATCH_DISCONNECT_SWEEP_MS`), portanto a finalização pode ocorrer
  alguns segundos depois do deadline.
- Eventos de conexão e watchdog compartilham o lock do lobby com os
  disparos e a desistência: nunca há dois vencedores atribuídos
  por ações concorrentes.

A regra de timeout **não se aplica a `WAITING` nem `PREPARING`**.
O frontend deve abrir o WebSocket **antes de começar a partida**;
se nenhum socket estiver conectado ao entrar em `PLAYING`, o relógio
já começa. Esse comportamento é intencional.

## Estado autoritativo e notificações

`GET /api/matches/me` retorna `BattleResponse` com os campos
preexistentes e mais:

```json
{
  "status": "PLAYING",
  "winnerId": null,
  "finishReason": null,
  "reconnectDeadlines": {
    "123": "2026-10-09T12:02:00Z"
  }
}
```

Os IDs em `reconnectDeadlines` são **chaves de saída do backend**,
nunca são enviados como identidade na solicitação.
Os deadlines são compartilhados entre **os dois participantes**; nenhum
dado secreto de tabuleiro é acrescentado.
Quando uma partida termina, `reconnectDeadlines={}`,
`status="FINISHED"` e `turnPlayerId=null`.

Valores de `finishReason`:

| Valor | Significado | Resultado |
| --- | --- | --- |
| `ALL_SHIPS_SUNK` | Vitória pelas regras do modo | `winnerId` preenchido |
| `ABANDONED` | Um jogador desistiu explicitamente | Adversário vencedor |
| `DISCONNECT_TIMEOUT` | Apenas um timeout com adversário online | Adversário vencedor |
| `BOTH_DISCONNECTED` | Ambos expiraram offline | Sem vencedor (`winnerId=null`) |

O evento `BATTLE_UPDATED` é disparado a participantes
autorizados quando o prazo de reconexão muda ou quando o jogo termina.
`LOBBY_UPDATED` também reflete a transição para `FINISHED`.
Eventos são **privados por jogador**: cada um recebe apenas
`yourShips` da própria conta. Recarregar o navegador exige
novo ticket WebSocket e recuperação via `GET /api/matches/me`.

## Escopo do projeto acadêmico

O estado é em memória, com **uma instância da API**; reiniciar o
processo descarta partidas e deadlines. Não há cron em banco,
multi-instância, ranking, penalidade persistente nem timeout **por turno**.
Este é um timeout **de presença**, não de tempo de resposta ao tiro.
Não foram adicionadas dependências, migrations ou serviços externos.
