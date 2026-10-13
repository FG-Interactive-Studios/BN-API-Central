# Motor de batalha modular — PR 4

## Contrato e autenticação

O backend decide **todo o estado da partida**. O frontend envia apenas
uma intenção de disparo autenticada: **nunca** `userId`, `matchId`,
`lobbyCode`, resultado de tiro ou coordenadas secretas do adversário.

Pré-condições: duas contas na sala, ambas prontas e com todas as suas frotas
confirmadas. A segunda confirmação muda o lobby `PREPARING` → `PLAYING`
e inicializa uma `BattleRound` com o modo fixado na criação da sala.

## Endpoints

Requerem `Authorization: Bearer <accessToken>` e sessão válida.

| Rota | Operação | Resultado |
| --- | --- | --- |
| `GET /api/matches/me` | Recupera sua partida, privada | 200 |
| `POST /api/matches/me/shots` | Disparo na posição `{"row":0,"col":0}` | 200 |

Uma requisição de tiro contém apenas inteiros `row`/`col`, começando em
zero; a geometria **do modo selecionado** decide as células válidas.
`401` sem JWT/sessão, `404` quando fora de sala, `409` antes do jogo
iniciar, fora do próprio turno, na repetição de tiro ou após o fim do jogo.
Coordenadas inválidas ou ausentes: `400`. A execução é serializada com
entradas/saídas do lobby e comandos de preparação pelo mesmo lock de instância.

## Regras V1 realmente executadas

- Anfitrião da sala realiza o **primeiro** disparo.
- `standard-shot`: atinge exatamente uma célula, indica acerto/erro;
  identifica um navio como afundado **somente no último acerto do navio**.
- `alternating`: todo disparo válido consome o turno, inclusive acertos;
  segue o adversário.
- `all-ships-sunk`: vitória quando toda a frota inimiga foi atingida.
  O lobby muda para `FINISHED` e futuros tiros são recusados.
- `stationary` e `disabled` continuam como declarações: ainda não há
  movimentos, poderes, buffs, debuffs nem outros tipos de ação no protocolo.
- `classic`, `quick` e `triangular` compartilham as políticas acima, mas
  usam suas geometrias e frotas específicas. O triangular usa **máscara de
  células quadradas**, não ladrilhos triangulares.

A lógica executável está nas interfaces `AttackRule`, `TurnRule` e
`VictoryRule` e implementações `StandardShotRule`,
`AlternatingTurnRule`, `AllShipsSunkRule`. Os modos Java selecionam
implementações dos slots `ATTACK`, `TURN` e `VICTORY` sem `switch`
de modo na engine. Definições com meros IDs declarativos nesses slots
falham na construção, impedindo modos falsamente jogáveis.

Novos modos poderão incluir, por exemplo, turno adicional ao acertar ou
nova vitória, implementando essas interfaces e associando-as à definição
do modo. O contrato de futuros poderes por navio exige estado/ações
autoritativos adicionais e **não foi implementado** nesta PR.

## Visão específica de cada jogador

`GET /api/matches/me` e `BATTLE_UPDATED` têm o mesmo formato de visão
**personalizada por destinatário**:

```json
{
  "matchId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "lobbyCode":"ABC234",
  "mode":{"id":"classic","name":"Classico"},
  "status":"PLAYING",
  "revision":2,
  "turnPlayerId":11,
  "winnerId":null,
  "yourShips":[
    {"type":"CARRIER","row":0,"col":0,"orientation":"HORIZONTAL"}
  ],
  "shotsFired":[
    {"row":4,"col":8,"hit":false,"sunkShip":null}
  ],
  "shotsReceived":[]
}
```

O JSON é ilustrativo; `mode` é o catálogo público completo e
`yourShips` retorna a frota completa do **próprio** jogador (o exemplo
mostra apenas um navio por concisão).

Os disparos registrados são informações já conhecidas por ambos:
`shotsFired` contém tiros realizados pelo jogador autenticado;
`shotsReceived` contém ataques recebidos. O backend não inclui as
coordenadas **não atingidas** dos navios inimigos nas respostas nem nos
eventos, mesmo ao terminar uma partida. `sunkShip` só informa o tipo
quando o navio afunda. Os nomes/tamanhos das embarcações do modo são
públicos e aparecem no catálogo.

`revision` aumenta em cada disparo válido. O frontend deve descartar
atualizações com revisão mais antiga, identificando a mesma partida pelo
`matchId` (que muda se uma sala é reiniciada). `winnerId` e
`turnPlayerId` são IDs **retornados**, nunca enviados como identidade.

## WebSocket e reconexão

Eventos `BATTLE_UPDATED` são enviados de maneira privada aos dois
participantes após cada tiro válido e quando a segunda frota é confirmada.
`LOBBY_UPDATED` reflete a transição para `PLAYING`/`FINISHED`.
As mensagens WebSocket recebidas do cliente **não executam ações de jogo**;
o tiro utiliza REST autenticado, e o servidor publica o estado resultante.

Após recarregar a página ou reconectar o WebSocket, chamar
`GET /api/matches/me` para recuperar o estado autoritativo. Renovar JWT
ou alterar a senha não interrompe a partida. Desconectar **não significa
derrota**. Em `PLAYING`, sair explicitamente significa desistir; o adversário
vence, e o resultado é preservado até que cada participante o dispense.
A desconexão tem tolerância configurável. Não há timeout por turno nem AFK
enquanto um WebSocket válido permanece conectado.

## Encerramento por desistência ou desconexão

Implementado em [encerramento e reconexão](battle-termination.md).
Desistência voluntária ou saída explícita durante `PLAYING` atribui
vitória ao adversário, preservando o resultado até que os jogadores
o descartem. Queda de WebSocket tem prazo de tolerância; o jogo
não termina imediatamente. Após ambos expirarem desconectados, termina
sem vencedor.

## Fora desta etapa acadêmica

Movimentação, habilidades ativas/passivas, debuffs, ações especiais,
recompensas, ranking, replay persistente, timeout por turno e infraestrutura
multi-instância. Jogos/lobbies permanecem em memória de uma única instância
e são perdidos ao reiniciá-la. Sem migration nova.
