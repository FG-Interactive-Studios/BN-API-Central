# Oito poderes — piloto escalável para tabuleiros quadrados

> **Atenção:** efeitos abaixo são interpretações técnicas experimentais baseadas nos nomes
> das oito habilidades da issue [Game Design #32](https://github.com/FG-Interactive-Studios/BN-GameDesign/issues/32).
> O GDD foi revisado depois da planilha original e não estava acessível.
> **Não são regras finais aprovadas**. Validar com GD antes da homologação final.

## Modos

`classic-powers` possui geometria retangular 8×8 e frota de três classes do clássico
e módulo `square-pilot-v1`, habilitado por padrão na migration V8 quando ainda não configurado. A administração pode desabilitar o modo sem perder a configuração em reinícios.
`classic`, `quick` e geometrias não quadradas permanecem inalterados. As
estratégias funcionam também em geometria quadrada 8×8, com testes, sem
adicionar implicitamente poderes ao modo rápido existente.

## Arquitetura

- `AbilitySpec`: metadados declarativos do servidor (nome, limite, quantidade
  de alvos, tabuleiro próprio/inimigo e descrição).
- `SquareAbilityRule`: registro imutável de comportamentos independentes;
  inclui validações de forma, alvo e topologia, sem ramificações de id na engine.
- `AbilityContext`: visão interna do estado da partida utilizada apenas para
  planejar a ação.
- `AbilityOutcome` + `AbilityEffect`: efeitos tipados (ataque, detecção,
  proteção, sobrevivência, bloqueio e reposicionamento), aplicados no
  `BattleRound` sob o mutex já existente de matchmaking.
- `BattleRound`: contador autoritativo de ações, consumo de habilidades por
  jogador, duração dos estados, ataques bloqueados/repetíveis e verificação
  de vitória; mantém snapshots individuais e sem coordenadas secretas.
- Um poder novo registra sua especificação e uma estratégia com efeito tipado;
  novos tipos de efeito requerem uma implementação de execução e testes no motor.

## Regras provisórias do piloto

| ID | Habilidade | Alvos | Efeito proposto |
| --- | --- | --- | --- |
| `double-shot` | Tiro duplo | 2 inimigos | Dois disparos em um único turno |
| `air-strike` | Ataque aéreo | 1 inimigo | Ataque em área 2×2 partindo do canto superior esquerdo escolhido |
| `line-barrage` | Rajada em linha | 2 inimigos | Extremidades de uma linha horizontal ou vertical de três casas |
| `hull-shield` | Escudo de casco | 1 próprio | Protege uma embarcação intacta contra o próximo acerto, durante até duas ações inimigas |
| `last-chance` | Última chance | 0 | Bloqueia o próximo acerto fatal durante três ações inimigas |
| `radar` | Radar | 1 inimigo | Conta células ocupadas no entorno 3×3 de forma privada |
| `interference` | Interferência | 0 | Bloqueia poderes na próxima ação do adversário, mas não seu tiro normal |
| `retreat` | Retirada | 2 próprios | Move um navio intacto uma casa ortogonalmente sem mudar orientação |

Cada habilidade pode ser ativada **uma vez por jogador por partida** e gasta
**uma ação completa**. Esses números, condicionantes, cooldown e custo são
**provisórios**. Não há desbloqueio, nível, energia, embarcação viva obrigatória,
monetização ou dados persistentes até aprovação explícita do GDD.

## API e sigilo

`POST /api/matches/me/abilities` autenticado:

```json
{"abilityId":"line-barrage","targets":[{"row":1,"col":0},{"row":1,"col":2}]}
```

Para poderes sem alvo, enviar `"targets":[]`. `retreat` exige a origem
da embarcação (primeira casa original) e o novo início da mesma embarcação.

`BattleResponse.yourAbilities` contém, por poder, `id`, `name`,
`usesRemaining`, `targetCount`, `targetScope`, `description` e `locked`.
`yourRadarReports` contém apenas resultados do próprio jogador. O backend
rejeita alvos inválidos, repetidos, já definitivamente atingidos, ativação
fora de turno, ausência de permissão e limites excedidos.

`ShotRecord.blocked=true` indica tiro interceptado por proteção:
ele **não é um acerto nem uma célula permanentemente utilizada**; a casa
pode ser atacada de novo. O frontend deve ignorá-lo nas marcas permanentes.

## Revisão obrigatória antes do merge

- Conferir condições e balanceamento contra a versão mais recente do GDD.
- Validar em QA a movimentação, os poderes defensivos e o combate em
  smartphones e desktops, incluindo reconexão e eventos WebSocket.
- O catálogo inicial inclui `classic-powers`; a administração pode desabilitá-lo enquanto o GDD e a homologação final estão pendentes.


## Pontos de habilidade do GDD v1.2

Nos modos base com poderes, o servidor posiciona quatro marcadores apenas em casas vazias do próprio tabuleiro. `yourAbilityPointCells` é privado; acertar um marcador inimigo concede uma unidade em `yourAbilityPointsEarned`. Radar e Tiro Duplo são gratuitos; outras habilidades exigem 1 ponto e são `locked` sem saldo. O saldo é **apenas da partida**, sem vínculo com a moeda da loja. Contrato de relógios e pontos: [GDD v1.2](gdd-timers-and-ability-points.md).
