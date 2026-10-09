# Modos de jogo componíveis — arquitetura V1

## Escopo implementado

O motor de preparação é configurado por **modo registrado no backend**,
não por flags ou regras fornecidas pelo navegador. Cada definição possui:

- `BoardGeometry`: células jogáveis, limites e vizinhanças.
- `FleetDefinition` e `ShipDefinition`: identificadores e comprimentos
  dos navios exigidos (não dependem mais do enum fixo de cinco navios).
- `PlacementRule`: estratégia **executável** para obter e validar o
  footprint de cada embarcação, com proibição de sobreposição.
- `RuleModule` por `RuleSlot`: contratos/identificadores de módulos
  de `MOVEMENT`, `ATTACK`, `TURN`, `VICTORY` e `ABILITY`.

**Execução real:** `PlacementRule`, `AttackRule`, `TurnRule` e
`VictoryRule` agora possuem interfaces e implementações executáveis.
O construtor de `GameModeDefinition` rejeita módulos declarativos nos
slots de combate, para não aceitar modos incapazes de executar tiros.
`MOVEMENT` e `ABILITY` continuam apenas contratos declarativos:
**não há navios móveis, habilidades, buffs ou debuffs funcionais**.

## Modos registrados

| ID | Geometria | Frota | Mecânicas atuais |
| --- | --- | --- | --- |
| `classic` | Retangular 10×10 | 5, 4, 3, 3, 2 | Posicionamento linear |
| `quick` | Retangular 8×8 | 4, 3, 2 | Posicionamento linear |
| `triangular` | Máscara triangular de 10 linhas (55 células) | 4, 3, 2 | Posicionamento linear |

O modo `triangular` tem **células quadradas formando uma máscara
triangular**, e não ladrilhos triangulares. Um tabuleiro de ladrilhos
triangulares pode ter uma implementação distinta de `BoardGeometry`
quando suas regras de vizinhança forem definidas.

Os três modos compartilham as mesmas políticas de combate executáveis
(e ainda declaram a ausência de movimento e poderes):
`MOVEMENT=stationary`, `ATTACK=standard-shot`,
`TURN=alternating`, `VICTORY=all-ships-sunk`,
`ABILITY=disabled`.

## API / Contratos

`GET /api/game-modes` — exige JWT, retorna array de definições públicas
com `id`, `name`, `geometry`, `fleet`, `placement` e `mechanics`.

O campo `geometry` fornece `kind`, `rows`, `columns` e `cells`.
`cells` são coordenadas **públicas das casas válidas**, não posições secretas
de navios. Isso permite ao frontend renderizar até geometrias irregulares.

`POST /api/matchmaking/lobbies` — JWT obrigatório, corpo opcional:

```json
{"modeId":"quick"}
```

Omitir corpo ou `modeId` mantém **`classic`**, preservando o contrato
anterior. IDs inexistentes retornam `400` **sem criar sala**. O modo é
fixado pelo anfitrião na criação e divulgado no campo `mode` do
`LobbyResponse`; entrar na sala não muda seu modo.

`GET /api/matches/me/placement`, `PUT /api/matches/me/placement`,
`POST /api/matches/me/placement/random` e
`POST /api/matches/me/placement/confirm` continuam funcionando.
O backend determina o modo a partir da sala do JWT. O cliente
**não escolhe outro modo, userId ou lobbyCode** ao manipular a frota.
A resposta de preparação também inclui `mode`. O campo legado
`boardSize` continua representando o número de **linhas** neste momento;
novos clientes devem preferir `mode.geometry`.

Formato de posicionamento não muda, apenas o conjunto de navios é
dependente do modo:

```json
{
  "ships": [
    {"type":"BATTLESHIP","row":0,"col":0,"orientation":"HORIZONTAL"},
    {"type":"CRUISER","row":1,"col":0,"orientation":"HORIZONTAL"},
    {"type":"DESTROYER","row":2,"col":0,"orientation":"HORIZONTAL"}
  ]
}
```

O exemplo é válido para `quick`; não para `classic` (faltam dois navios)
nem para certas posições do modo triangular. O servidor valida contra
geometria, frota, posicionamento e ocupação do modo **da sala**.

As posições permanecem **privadas por jogador** nas respostas REST e
nos eventos `PREPARATION_UPDATED`. Informações públicas sobre a
geometria e a lista de tipos de navios não revelam coordenadas.

## Como acrescentar um modo no Java

1. Definir/reutilizar uma `BoardGeometry`, uma `FleetDefinition`
   e uma `PlacementRule`.
2. Compor `RuleModule` dos slots obrigatórios; módulos podem
   sobrescrever `validate(GameModeDefinition)` para recusar geometria
   ou combinações incompatíveis.
3. Registrar um bean Spring do tipo `GameModeDefinition`. O
   `GameModeRegistry` descobre os beans, valida os IDs e as combinações
   e disponibiliza o catálogo. Modos duplicados falham no boot.
4. Criar testes para a geometria, a frota e eventuais regras.
   A criação de salas aceita automaticamente o novo `modeId`.

**Não** recebemos scripts, classes, handlers ou listas arbitrárias de
mecânicas do frontend. As definições são mantidas por desenvolvedores,
compiladas e validadas no servidor.

## Preparação para poderes, movimentos e debuffs

A organização prevê regras combináveis de ataque, turno, deslocamento,
habilidades e vitória, e definição de frota por modo. Na fase de combate,
essas interfaces precisarão de um **contexto de ação autoritativo**,
validadores de permissões, efeitos modificadores e resolução determinística
de conflitos. Para poderes vinculados a navios e seus debuffs, a evolução
natural será declarar essas características em uma definição por embarcação
e aplicar efeitos por estratégias explícitas, com regras de compatibilidade
entre habilidades, turnos e geometrias.

A execução de ataque, alternância de turnos e condição de vitória já
existe, com visão de partida e eventos privados por jogador:
[Motor de batalha](../api/battle-engine.md). Poderes ativos, debuffs,
movimentos e efeitos especiais **ainda não existem**. Serão acrescentados
como estratégias com semântica de ação, não como strings arbitrárias
fornecidas pelo cliente.

## Escopo operacional

Estado em memória, instância única, como lobby e preparação anteriores.
Os dados de modo na sala e na rodada são snapshots imutáveis, selecionados
no servidor; abandonar uma sala descarta a rodada e seus navios. Sem nova
migration, framework, dependência, motor de scripts ou banco adicional.
