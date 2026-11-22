# GDD v1.2 — relógios de partida e pontos de habilidade

Referência de regra: [Game Design Document v1.2](https://docs.google.com/document/d/1FaZL_fbkirnFMeqXpmaEBfSZla3ko9VJ/edit); rastreamento técnico: [issue #20](https://github.com/FG-Interactive-Studios/BN-API-Central/issues/20).

## Modos contemplados
Somente os modos base `classic` e `classic-powers` usam os relógios GDD e a frota de três classes em 8×8. Modos experimentais mantêm seu código, mas ficam desativados por padrão. Uma instância da API mantém o estado em memória. A autorização, o resultado e o encerramento são decididos pelo servidor, nunca pela contagem visual no navegador.

## Preparação: 30 segundos
O início de `PREPARING` cria uma `PreparationRound` com `placementDeadlineAt` (UTC). A partir do instante de expiração, um watchdog resolve a preparação com o mutex compartilhado. Frota já enviada via PUT é preservada; um jogador sem frota salva recebe uma frota completa aleatória validada contra o modo. Ambas são confirmadas e inicia-se a batalha. Não ocorrem dois inícios sob callbacks simultâneos.

O contrato atual do PUT recebe a **frota inteira**, não posições parciais; portanto os navios ainda apenas no rascunho do navegador não são conhecidos pelo servidor e não poderão ser preservados no autoposicionamento. Um futuro endpoint de rascunho validado poderá evoluir isso.

## Batalha: sorteio inicial, 20 segundos por turno e 10 minutos por partida
Ao iniciar, o servidor sorteia o primeiro jogador. `turnDeadlineAt` e `matchDeadlineAt` são UTC e aparecem em `BattleResponse`. Ao expirar o turno, o jogador **perde a vez** (opção expressamente permitida no GDD) e o próximo jogador recebe um novo prazo. O servidor incrementa `yourConsecutiveMissedTurns`; uma jogada válida, incluindo habilidade, zera apenas a contagem daquele jogador. Três turnos **próprios** perdidos consecutivamente encerram com `finishReason="TURN_TIMEOUT_FORFEIT"` e vitória do oponente.

Ao expirar o limite total de partida, os navios sobreviventes são calculados pelos acertos efetivos em cada frota. Quem tiver mais navios não destruídos vence (`TIME_LIMIT`). Em caso de igualdade, ambos empatam (`TIME_LIMIT_DRAW`, `winnerId=null`). Encerramentos são idempotentes, com revisão monotônica, e não são sobrescritos por outro watchdog. Ao afundar a frota inteira antes do limite, `ALL_SHIPS_SUNK` prevalece.

## Reconexão: 15 segundos
`reconnectDeadlines` identifica os jogadores offline. A queda do último WebSocket autenticado inicia a tolerância padrão de 15 s. Enquanto há desconexão, o relógio de turno e o relógio total ficam pausados; `paused=true`. Ao reconectar antes do vencimento, os prazos são estendidos pelo tempo exato da pausa. Reconnect atrasado não desfaz derrota já vencida. Expiração de um jogador sozinho: `DISCONNECT_TIMEOUT`; ambos expirados: `BOTH_DISCONNECTED` sem vencedor. `MATCH_DISCONNECT_GRACE_SECONDS` continua configurável pelo ambiente para testes e operação.

O watchdog avalia os prazos aproximadamente a cada 500 ms, com o mesmo mutex do matchmaking e de todos os comandos. Os endpoints REST fazem uma checagem adicional antes de aceitar ações; o relógio do cliente **não** autoriza tiros atrasados.

## Habilidades: pontos no tabuleiro
Somente `classic-powers` recebe quatro pontos por tabuleiro, sorteados pelo servidor entre células vazias após a preparação. `yourAbilityPointCells` mostra **somente** os quatro marcadores do tabuleiro próprio; as posições do tabuleiro adversário não são divulgadas. Acertar, por tiro ou habilidade, uma casa válida que contém um ponto do rival concede 1 em `yourAbilityPointsEarned`. Tiro bloqueado ou repetido não concede ponto.

`double-shot` e `radar` são gratuitos conforme o GDD; as demais seis habilidades piloto custam um ponto por uso. A API valida o saldo de pontos e devolve `locked=true` nas habilidades sem pontos suficientes. Esse saldo pertence apenas à partida, **não** é a moeda da loja, não é comprado e não tem relação com o sistema de monetização. Os limites provisórios de usos dos oito poderes são preservados até aprovação final das habilidades pelo GD.

## Eventos e interface
Os mesmos eventos privados `PREPARATION_UPDATED`, `BATTLE_UPDATED` e `LOBBY_UPDATED` publicam as novas transições. O frontend deve formatar um countdown a partir dos instantes UTC e exibir status `paused`, o motivo do resultado e as células próprias de pontos sem expor informações do inimigo.

## Limitações e homologação
- O relógio não é persistido: reiniciar a instância interrompe e descarta partidas.
- A API mantém o fluxo atual de uma frota por tipo; a possibilidade de repetir classes depende da definição de [GD #9](https://github.com/FG-Interactive-Studios/BN-GameDesign/issues/9).
- Recompensas de XP e moedas, balanceamento final dos oito poderes e regras de empate nos perfis/loja precisam de homologação e implementação próprias.
- CI desativa a aplicação dos relógios em testes de contrato legados via `match.gdd-enforced=false`; os testes `GddMatchRulesTests` instanciam o relógio autoritativo explicitamente e exercitam limites e corridas sem sleeps. O padrão de runtime é `true`.
