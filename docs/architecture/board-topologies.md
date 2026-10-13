# Geometrias de tabuleiro — células, vizinhança e renderização

## Tabuleiros suportados

| Mode ID | Geometria | Células jogáveis | Posicionamento |
| --- | --- | ---: | --- |
| `classic` | `RECTANGULAR` 10×10 | 100 quadrados | HORIZONTAL / VERTICAL |
| `quick` | `RECTANGULAR` 8×8 | 64 quadrados | HORIZONTAL / VERTICAL |
| `triangular` | `TRIANGULAR_MASK`, máscara de quadrados | 55 quadrados | HORIZONTAL / VERTICAL |
| `triangle-tiles` | `TRIANGULAR_TILES`, tesselação equilátera | 64 **triângulos** | TRI_RIGHT / TRI_DOWN_LEFT / TRI_DOWN_RIGHT |
| `hexagons` | `HEXAGONAL_TILES`, raio 4 | 61 **hexágonos** | HEX_VERTICAL / HEX_DIAGONAL_UP / HEX_DIAGONAL_DOWN |
| `octagons` | `OCTAGONAL_TILES`, 8×8 | 64 **octógonos** | HORIZONTAL / VERTICAL / DIAGONAL_UP / DIAGONAL_DOWN |

O modo antigo `triangular` **não foi alterado**: suas 55 casas quadradas
continuam organizadas numa máscara em forma de triângulo. O novo modo
`triangle-tiles` usa triângulos reais como células de jogo; cada triângulo
é uma casa selecionável, posicionável ou alvo de tiro.

## Coordenadas e vizinhos de `TRIANGULAR_TILES`

Uma geometria de lado `N` possui linhas `row = 0..N-1`. A linha
`r` tem `2*r + 1` pequenos triângulos, com
`col = 0..2*r`. Assim, existem `N*N` células, não `N*(N+1)/2`.

Por exemplo:

```text
row 0:              ▲
row 1:            ▲ ▼ ▲
row 2:          ▲ ▼ ▲ ▼ ▲
row 3:        ▲ ▼ ▲ ▼ ▲ ▼ ▲
```

Os triângulos de `col` par apontam para cima, os de `col` ímpar
para baixo. A vizinhança é **por aresta compartilhada**, nunca por
vértice ou deslocamento arbitrário de linhas/colunas. Cada célula tem no
máximo três vizinhos. A geometria define isso em
`BoardGeometry.neighbors(Cell)`, para que poderes futuros não precisem
presumir quatro direções ou células quadradas.

### Três eixos de posicionamento

`TriangularEdgePlacementRule` usa caminhos contínuos por arestas:
- `TRI_RIGHT`: avança uma célula à direita na mesma linha.
- `TRI_DOWN_RIGHT`: alterna descida diagonal e passo à direita.
- `TRI_DOWN_LEFT`: alterna descida diagonal e passo à esquerda.

Esses são **caminhos de células triangularmente adjacentes**, não
segmentos visuais retangulares. `HORIZONTAL` e `VERTICAL` não são
válidos em `triangle-tiles`. Inversamente, `TRI_*` não funciona nos
modos quadrados. Um `GameModeDefinition` com `PlacementRule`
incompatível é rejeitado na construção do modo.

O gerador de frotas automáticas usa
`mode.placement.directions()` e `mode.geometry.cells()`, em vez
de assumir todas as orientações de um enum ou um tabuleiro 10×10.

## Hexágonos regulares e octógonos regulares

### `hexagons`: 61 casas num tabuleiro hexagonal

O tabuleiro possui raio 4 e **61 hexágonos individuais clicáveis,
posicionáveis e atacáveis**, com seis vizinhos por aresta para células
internas. Utiliza coordenadas axiais deslocadas (`row,col`), não
índices presumidamente retangulares. O posicionamento linear tem três
eixos: `HEX_VERTICAL`, `HEX_DIAGONAL_UP` e
`HEX_DIAGONAL_DOWN`. Todas as casas de um navio compartilham arestas
sucessivas e seu sprite pode ser reto.

### `octagons`: 64 casas num tabuleiro 8×8

Octógonos **regulares não se encaixam sozinhos no plano**; os pequenos
vazios em forma de quadrados girados/losangos entre quatro octógonos
não são jogáveis. Apenas os **64 octógonos** são casas e alvos de tiros.

Os eixos de posicionamento são `HORIZONTAL`, `VERTICAL`,
`DIAGONAL_UP` e `DIAGONAL_DOWN`. No caso diagonal, o sprite segue
uma linha reta **entre os centros** dos octógonos, atravessando os
vazios decorativos. Essas lacunas não recebem tiros nem contam para
o comprimento do navio. `BoardGeometry.neighbors` permanece restrito
a vizinhanças **por aresta** (4), mesmo que o posicionamento diagonal
seja permitido.

### Sprites, casas e segurança

O backend expõe `mode.geometry.tiles` com os polígonos das casas e
`mode.placementDirections` com os eixos autorizados. O cliente deve
desenhar cada polígono, reconhecer o clique **no interior de uma casa**
e mandar só a coordenada `row,col` do triângulo/hexágono/octógono.
Vértices, arestas e espaços entre octógonos **não são casas extras**.

A API guarda o footprint real das frotas e valida tiro e colisão;
renderização/escala/âncora do sprite são responsabilidade de um futuro
renderer do frontend. Não foi adicionado contrato de sprite
(`shipVisual`, imagem, textura etc.) nesta etapa.

Selecione os novos modos criando sala com:

```json
{"modeId":"hexagons"}
```

```json
{"modeId":"octagons"}
```

## Contrato da API para desenhar o tabuleiro

O campo **público** `mode.geometry`, em
`GET /api/game-modes` e snapshots de lobby/partida, passa a conter:

```json
{
  "kind": "TRIANGULAR_TILES",
  "rows": 8,
  "columns": 15,
  "cells": [{"row":0,"col":0}, {"row":1,"col":0}],
  "tiles": [
    {
      "cell": {"row":0,"col":0},
      "shape": "TRIANGLE_UP",
      "vertices": [{"x":4,"y":0},{"x":3.5,"y":0.8660254038},{"x":4.5,"y":0.8660254038}]
    }
  ]
}
```

O exemplo contém apenas uma parte das listas. O servidor retorna
**todas** as 64 células e seus polígonos. `tiles[].vertices` contém
coordenadas 2D locais do tabuleiro, na ordem de contorno, adequadas
para desenhar um SVG `polygon`. Retangulares e a máscara antiga usam
`shape="SQUARE"` com quatro vértices; triângulos possuem três,
hexágonos seis e octógonos oito. Os nomes são `TRIANGLE_UP`,
`TRIANGLE_DOWN`, `HEXAGON` e `OCTAGON`.
Os clientes devem calcular os limites do desenho a partir dos vértices,
renderizar cada polígono e enviar seu `cell.row/col` quando clicado.
Não usar `geometry.columns` como prova de que todas as posições
entre `0..columns-1` são válidas em cada linha.

O campo adicional `mode.placementDirections` informa quais valores
de `orientation` podem ser enviados ao posicionar navios manualmente.
Os campos anteriores `kind`, `rows`, `columns`, `cells`,
`fleet` e `placement` continuam disponíveis.

A geometria e os polígonos são **metadados públicos**, não coordenadas
privadas de navios. `yourShips` continua sendo enviado apenas ao
proprietário. O resultado dos disparos continua autoritativo no backend.

## Compatibilidade e extensões

- `POST /api/matchmaking/lobbies` sem corpo segue usando `classic`.
- Para selecionar a nova tesselação: `{"modeId":"triangle-tiles"}`.
- Os modos anteriores são preservados sem mudança de ID, célula ou
  regras de vitória.
- A vizinhança do triângulo está disponível para futuras mecânicas de
  alcance/área/movimento. **Movimento e poderes ainda não foram
  implementados.** Como decidido na [issue #12](https://github.com/FG-Interactive-Studios/BN-API-Central/issues/12),
  cada poder precisará de comportamento compatível com a geometria antes
  de ser ativado.
- O estado do lobby e do jogo continua em memória numa única instância,
  sem mudança de schema ou dependências.
