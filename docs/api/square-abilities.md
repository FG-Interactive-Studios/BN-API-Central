# Habilidades no tabuleiro quadrado — piloto técnico

> **Status:** implementação experimental para testes, **não é o balanceamento aprovado do GDD**.
> A issue BN-GameDesign #8 informa que energia, cooldown e condições de ativação
> foram revisados. Validar as regras mais recentes no GDD antes de produção.

## Escopo e segurança

O modo original `classic` (10×10) e `quick` (8×8) continuam com
`ABILITY=disabled`. O novo modo `classic-powers` usa a mesma frota e o mesmo
tabuleiro quadrado clássico de 10×10, mas habilita o módulo
`ABILITY=square-pilot-v1`. Apenas geometrias `RectangularGeometry` com
linhas = colunas podem receber esse módulo. Não há adaptação implícita para
máscaras triangulares, triângulos ou hexágonos.

Este piloto oferece somente duas habilidades:
- `double-shot` (Tiro duplo): recebe **duas casas distintas e nunca atacadas**,
  processa-as pelo motor normal e consome um único turno.
- `radar`: recebe **uma casa central** e devolve, **somente ao jogador que
  ativou**, a quantidade total de células ocupadas na vizinhança 3×3 limitada
  pelas bordas. Não informa as coordenadas dos navios.

**Regras provisórias:** cada habilidade pode ser usada uma vez por participante
a cada partida e cada ativação consome um turno. Ainda não há energia,
cooldown, condição de navio vivo, movimento, escudos, debuffs ou persistência
de uso. Nenhuma vantagem pode ser comprada pela loja.

## API

Requer JWT e uma partida `PLAYING` no modo `classic-powers`.

`POST /api/matches/me/abilities`

Tiro duplo:
```json
{"abilityId":"double-shot","targets":[{"row":1,"col":2},{"row":3,"col":4}]}
```

Radar:
```json
{"abilityId":"radar","targets":[{"row":5,"col":5}]}
```

Retorna o mesmo `BattleResponse` de `POST /shots`; publica
`BATTLE_UPDATED` com snapshots privados para ambos os jogadores e, se a
partida acabar, a atualização de lobby padrão.

O snapshot passa a incluir `yourAbilities` (id, name, usesRemaining) e
`yourRadarReports` (row, col, contacts). Esses dois campos mostram **apenas
as informações do titular da sessão**. O segundo participante não recebe
nem a origem nem o resultado da varredura.

Pré-condições e erros: 400 para campos, poder ou coordenadas inválidas; 409
para fora de turno, partida terminada, modo desabilitado, uso esgotado ou
célula já atacada. Não há gasto de uso/turno para comandos inválidos.

## Próximas etapas antes da versão final

1. Revisar as especificações atualizadas no GDD junto ao Game Design e ajustar
   custo, duração, limites, cooldowns, regras de desbloqueio e efeitos.
2. Implementar as demais habilidades com estratégias específicas e testes.
3. Integrar à tela de partida em uma PR separada, mantendo os botões
   indisponíveis quando `yourAbilities` vier vazio.
4. Testar fluxo completo REST + WebSocket, mobile e desktop; revisar QA.
