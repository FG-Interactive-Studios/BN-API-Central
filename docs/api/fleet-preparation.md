# Preparação da frota — PR 3

## Premissas

A partida de Batalha Naval só entra na preparação quando **dois jogadores
estão prontos** na mesma sala privada (`PREPARING`).

O frontend **não manda userId, ID da sala nem ID do adversário**. Todos
os endpoints consultam a sala autenticada pelo JWT validado e a associação
de jogador existente no lobby.

O backend mantém **dois tabuleiros secretos separados** por preparação.
O adversário pode consultar **somente indicadores** de posicionamento e
confirmação, jamais os navios ou suas coordenadas.

## Frota e coordenadas

Tabuleiro 10×10. Índices de linha e coluna de **0 a 9**.
Coordenadas iniciais são a ponta superior/esquerda de cada navio.
Orientações válidas: `HORIZONTAL`, `VERTICAL`.

| Tipo | Tamanho |
| --- | ---: |
| `CARRIER` | 5 |
| `BATTLESHIP` | 4 |
| `CRUISER` | 3 |
| `SUBMARINE` | 3 |
| `DESTROYER` | 2 |

É obrigatório posicionar **uma embarcação de cada tipo** (17 células).
Não pode ultrapassar os limites do tabuleiro nem sobrepor outro navio.
**Adjacência é permitida.**

## Endpoints autenticados

| Operação | Endpoint | Resposta |
| --- | --- | --- |
| Consultar **seu próprio** tabuleiro | `GET /api/matches/me/placement` | 200 |
| Substituir a frota completa | `PUT /api/matches/me/placement` | 200 |
| Gerar frota aleatória válida | `POST /api/matches/me/placement/random` | 200 |
| Confirmar a própria frota | `POST /api/matches/me/placement/confirm` | 200 |

Enviar sempre `Authorization: Bearer <accessToken>`.

Exemplo para substituir toda a frota, **atomicamente**:

```json
{
  "ships": [
    {"type":"CARRIER","row":0,"col":0,"orientation":"HORIZONTAL"},
    {"type":"BATTLESHIP","row":1,"col":0,"orientation":"HORIZONTAL"},
    {"type":"CRUISER","row":2,"col":0,"orientation":"HORIZONTAL"},
    {"type":"SUBMARINE","row":3,"col":0,"orientation":"HORIZONTAL"},
    {"type":"DESTROYER","row":4,"col":0,"orientation":"HORIZONTAL"}
  ]
}
```

A edição é possível enquanto a própria frota não foi confirmada. O endpoint
`/random` substitui a própria frota completa, com validações equivalentes.
Depois da confirmação, tentar mudar a frota retorna `409`. Confirmar
repetidamente sem editar é idempotente; confirmar uma frota vazia retorna
`409`. Request inválido/incompleto retorna `400`, sem substituir a
última frota válida.

## Resposta individual

Exemplo simplificado, retornado **somente ao dono da frota**:

```json
{
  "lobbyCode":"ABC234",
  "preparationId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "boardSize":10,
  "revision":3,
  "fleetPlaced":true,
  "fleetConfirmed":false,
  "opponentPlaced":false,
  "opponentConfirmed":false,
  "bothConfirmed":false,
  "yourShips":[
    {"type":"CARRIER","row":0,"col":0,"orientation":"HORIZONTAL"},
    {"type":"BATTLESHIP","row":1,"col":0,"orientation":"HORIZONTAL"},
    {"type":"CRUISER","row":2,"col":0,"orientation":"HORIZONTAL"},
    {"type":"SUBMARINE","row":3,"col":0,"orientation":"HORIZONTAL"},
    {"type":"DESTROYER","row":4,"col":0,"orientation":"HORIZONTAL"}
  ]
}
```

`yourShips` **nunca inclui posições do oponente**. Se o próprio
tabuleiro estiver vazio, retorna `[]`. O frontend pode desenhar as
células ocupadas a partir das posições dos cinco navios. A API preserva
o estado autoritativo para a futura etapa de tiros.

O campo `bothConfirmed` muda para `true` quando ambos confirmam. Isso
**não inicia automaticamente os tiros nesta PR**. A próxima PR de motor de
partida consumirá este estado e implementará começo, turnos e disparos.

`revision` aumenta em cada alteração de frota ou primeira confirmação.
`preparationId` identifica a rodada: uma saída/reentrada reinicia as
frotas e gera um identificador novo. O frontend deve ignorar notificações
antigas (ID de preparação ou revision incompatíveis).

## Notificações WebSocket

O backend envia `PREPARATION_UPDATED` com **uma resposta personalizada para
cada participante** da sala. Portanto, cada socket recebe `yourShips`
**da sua própria conta** e apenas booleanos sobre o adversário.

O cliente não pode emitir comandos de posicionamento via WebSocket nesta PR.
Usa REST para editar/confirmar e recebe eventos para atualizar o próprio
painel. A interface não deve mostrar os navios do oponente durante a partida.

## Reconexão e cancelamento

- Atualização de página, perda temporária do socket, troca de senha e refresh
  do JWT **não removem a frota**. Ao reconectar, faça
  `GET /api/matches/me/placement` para obter a fotografia atual autorizada.
- A saída do convidado limpa as duas frotas, mantém a sala do anfitrião em
  `WAITING` e permite um novo ciclo de preparação.
- A saída do anfitrião fecha a sala e elimina o estado secreto.
- Uma mesma sala só tem uma rodada de preparação por vez, e operações de
  lobby/posicionamento usam o **mesmo lock por instância** para impedir
  corridas de saída/reentrada.
- Fora de sala: `404`; sala ainda em `WAITING`: `409`;
  JWT inválido: `401`.

## Limites do projeto acadêmico

Todas as frotas e salas estão em **memória de uma única instância**. Um
reinício perde o progresso dessas partidas. Nenhuma migration ou dependência
foi adicionada. Não implementa disparos, dano, vitória nem persistência de
histórico de batalha; serão tratados na PR 4.
