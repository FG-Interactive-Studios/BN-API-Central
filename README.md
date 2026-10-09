# Battleship API

Backend do jogo de Batalha Naval multiplayer.

O projeto concentra as regras autoritativas da partida, autenticação, informações do jogador, matchmaking, economia/loja e comunicação em tempo real com o frontend.

## Stack

- Java 21
- Spring Boot
- Maven
- Spring Web / MVC
- Spring WebSocket
- Spring Security
- Spring Data JPA
- Bean Validation
- Flyway
- PostgreSQL
- Docker / Docker Compose

## Arquitetura

O backend é organizado **por domínio/feature**, mantendo **arquitetura em camadas dentro de cada domínio**.

Exemplo:

```text
src/main/java/.../
├── auth/
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── dto/
│   └── model/
├── user/
├── matchmaking/
├── match/
├── store/
└── shared/
```

Fluxo esperado:

```text
Controller
    ↓
Service
    ↓
Repository / Store
    ↓
PostgreSQL ou memória
```

Nem todo domínio precisa obrigatoriamente de Repository. Partidas ativas e filas de matchmaking podem utilizar armazenamento em memória quando apropriado.

## Domínios iniciais

### `auth`
Responsável por:
- cadastro;
- login;
- autenticação;
- emissão/renovação de tokens.

### `user`
Responsável por:
- perfil;
- nickname;
- avatar;
- saldo;
- dados exibidos no lobby;
- cosméticos equipados.

### `matchmaking`
Responsável por:
- entrada e saída da fila;
- pareamento de jogadores;
- criação de uma partida;
- transição para o estado de preparação.

### `match`
Responsável pelo núcleo do jogo:
- tabuleiro;
- frota;
- posicionamento;
- turnos;
- tiros;
- timeout;
- reconexão;
- vitória/derrota;
- estado autoritativo da partida.

### `store`
Responsável por:
- catálogo de cosméticos;
- compra;
- equipar/desequipar itens;
- integração com saldo do jogador.


## Autenticação e sessões

O backend emite tokens JWT assinados para identificar o jogador. Tokens de acesso
duram 15 minutos; sessões renováveis (refresh tokens rotativos) duram 30 dias.
O servidor verifica as sessões no banco a cada request protegido, permitindo
logout e revogação imediatos.

Endpoints públicos: `GET /api/health`, `POST /api/auth/register`,
`POST /api/auth/login` e `POST /api/auth/refresh`.

O perfil público `GET /api/users/{id}` também aceita acesso anônimo (apenas
IDs numéricos). Os demais endpoints protegidos incluem `GET /api/users/me`,
`PATCH /api/users/me` e `POST /api/auth/logout`, com
`Authorization: Bearer <accessToken>`.

A API mantém **uma única sessão ativa por jogador**. Um novo login revoga a
sessão anterior imediatamente, inclusive em outro dispositivo. Por isso a
listagem e a revogação de múltiplas sessões foram removidas. Para segurança da
conta, `POST /api/auth/change-password` exige o JWT, a senha atual e a
nova senha, **sem encerrar a sessão**. O jogador continua usando seus tokens,
inclusive durante uma partida. Para exibição, o cliente deve ler dados
atualizados do perfil privado ou público, não claims de nickname
potencialmente desatualizados no JWT.

**Desenvolvimento local:** o launch `BN API - Local DB (Debug)` ativa
`SPRING_PROFILES_ACTIVE=local` e gera automaticamente uma chave JWT aleatória
em memória quando `AUTH_JWT_SECRET_B64` não está configurada. Não é preciso
criar um arquivo `.env` apenas para isso. A chave muda a cada reinicialização,
invalidando tokens de acesso anteriores. Se a sessão e o refresh token ainda existirem no banco, o cliente pode renovar o acesso; caso contrário, será necessário um novo login.

**Produção:** `AUTH_JWT_SECRET_B64` continua **obrigatória**. Gere uma chave
aleatória de pelo menos 32 bytes em Base64 (por exemplo,
`openssl rand -base64 48`) e configure-a de forma persistente no ambiente.
Sem ela a API não inicia. Nunca use o perfil `local` em produção e não
versione segredos. Se uma chave explícita estiver configurada no perfil
`local`, ela também será validada e utilizada.

**Terminal:** inicie com o perfil `local` explicitamente; sem perfil ativo,
a API assume a política segura de produção. O Maven não carrega arquivos
`.env` automaticamente.

Os tokens contêm somente a identidade e informações mínimas para exibição.
O cliente nunca escolhe o ID do usuário autenticado: use `GET /api/users/me`
para carregar o perfil atual do próprio jogador. **Dados de outros jogadores
e permissões são sempre conferidos no servidor**.

Salas privadas (lobby para 2 jogadores, código de convite, prontidão
e transição para preparação): [API de Lobby](docs/api/private-lobbies.md).
As ações REST usam somente o JWT; nenhum `userId` é enviado pelo cliente.

Contrato de comunicação em tempo real:
[WebSocket autenticado](docs/api/realtime-websocket.md). O navegador solicita
um ticket de uso único com seu JWT, conecta sem transmitir `userId`, e recebe
eventos exclusivamente dirigidos ao seu jogador. Sala de espera e partida
serão implementadas em PRs seguintes.

Contrato e recomendações para o frontend:
[Autenticação e sessões](docs/api/auth-sessions.md),
[Segurança da conta](docs/api/account-security.md) e
[Perfis públicos/privados](docs/api/player-profiles.md).

**Modelo para produção:** use o arquivo [`.env.production.example`](.env.production.example)
como base, preencha os valores reais **fora do Git** e siga as
[instruções de implantação](docs/deployment/environment.md). A chave
JWT é obrigatória em produção; nenhum segredo real é versionado.

## Preparação da partida

A sala privada entra em `PREPARING` quando os dois jogadores marcam
prontidão. No novo domínio `match`, cada um posiciona sua própria frota
definida pelo modo da sala: Clássico 10×10, Rápido 8×8 ou Triangular,
com frota variável, posicionamento manual ou geração aleatória.
A confirmação bloqueia edições; o outro jogador recebe apenas
indicadores de posicionamento/prontidão, jamais coordenadas secretas.

O cliente não envia o próprio `userId` nem o código de uma sala para
manipular seus navios: a API usa somente o JWT e a sala do jogador.
A conexão WebSocket publica `PREPARATION_UPDATED` com snapshots privados
e a consulta REST restaura o estado após reconexão.

Contrato completo: [Preparação de frotas](docs/api/fleet-preparation.md).
A [arquitetura de modos de jogo](docs/architecture/composable-game-modes.md)
permite combinar geometria, frota e regras de posicionamento, preparando
extensões futuras para tiro, movimento, poderes e debuffs. O motor de
batalha já executa ataque, alternância de turnos e vitória; **movimento,
poderes e debuffs ainda não**.

## Motor de batalha

Após confirmação das duas frotas, a sala passa automaticamente para
`PLAYING`. `GET /api/matches/me` recupera o estado atual e
`POST /api/matches/me/shots` recebe somente `{"row":0,"col":0}`
com JWT. O servidor calcula acerto/erro, navio afundado, próximo turno e
vitória. `BATTLE_UPDATED` publica snapshots privados por jogador, com
`yourShips` limitado à própria frota. Ao vencer, a sala passa para
`FINISHED`.

As mecânicas `ATTACK`, `TURN` e `VICTORY` agora são estratégias Java
**executáveis e intercambiáveis**, em todos os modos registrados.
`MOVEMENT` e `ABILITY` seguem como contratos futuros (sem poderes
ativos nem navios móveis). Não existe timeout de turno nesta V1.

Contrato: [Motor de batalha](docs/api/battle-engine.md).

**Encerramento:** desistência voluntária e queda do WebSocket são
tratadas separadamente. Em `PLAYING`, `POST /api/matches/me/forfeit`
ou `DELETE /api/matchmaking/lobbies/me` produzem derrota por abandono,
mantendo o resultado consultável até os participantes saírem.
Desconexão inicia tolerância de 120 s por padrão, cancelada ao reconectar;
o servidor não declara vencedor arbitrário se ambos desaparecerem.
Veja [encerramento e reconexão](docs/api/battle-termination.md).

## Princípio server-authoritative

O cliente **não decide**:
- se um tiro acertou;
- se um navio afundou;
- de quem é o turno;
- quando o turno termina;
- quem venceu;
- qual é o estado real do tabuleiro adversário.

O cliente envia intenções. O servidor valida, altera o estado da partida e publica o resultado.

## Pré-requisitos

- Java 21
- Docker Desktop ou Docker Engine acessível
- Docker Compose
- Git

## Executando localmente

### Opção 1: VS Code (recomendado)

Use o launch `BN API - Local DB (Debug)` no VS Code.

Ele executa automaticamente a task `BN API: Verificar Docker Local`, que:
- verifica se o Docker está disponível;
- se o Docker estiver indisponível, exibe aviso e aborta a inicialização;
- se estiver disponível, reseta o banco local e sobe o PostgreSQL;
- inicia a aplicação em debug sem abrir o navegador ao final.

### Opção 2: terminal

1. Suba o PostgreSQL:

```bash
docker compose up -d
```

2. Opcionalmente, prepare um arquivo `.env` para consulta local (o Maven não o lê automaticamente):

```bash
cp .env.example .env
```

Valores esperados:

```env
DB_URL=jdbc:postgresql://localhost:5432/battleship
DB_USER=postgres
DB_PASSWORD=postgres
PORT=8080
```

> Não faça commit do arquivo `.env`.

3. Execute a aplicação:

Linux/macOS:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Windows:

```powershell
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
```

## Configuração do banco local

A aplicação usa PostgreSQL em container definido em `docker-compose.yml`.

```yaml
services:
  postgres:
    image: postgres:17
    container_name: batalha-naval-postgres
    environment:
      POSTGRES_DB: battleship
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
```

Quando o Docker estiver disponível, a task local do VS Code reseta o volume do banco para evitar o Flyway checksum mismatch durante a inicialização local.

## Health check

Com a aplicação em execução:

```http
GET http://localhost:8080/api/health
```

Resposta esperada:

```json
{
  "status": "UP"
}
```

## Banco de dados

As alterações de schema devem ser feitas via Flyway:

```text
src/main/resources/db/migration/
├── V1__initial_schema.sql
├── V2__create_skins.sql
└── ...
```

Evite depender de `ddl-auto=update`.

## REST e WebSocket

REST deve ser utilizado para operações como:
- autenticação;
- perfil;
- loja;
- histórico;
- dados de lobby.

WebSocket deve ser utilizado para eventos de partida e matchmaking em tempo real.

Exemplos de eventos futuros:

```text
MATCH_FOUND
PLACEMENT_STARTED
PLAYER_READY
MATCH_STARTED
TURN_STARTED
SHOT_RESULT
SHIP_SUNK
PLAYER_DISCONNECTED
PLAYER_RECONNECTED
MATCH_FINISHED
```

O contrato definitivo deve ser documentado antes da implementação de cada conjunto de eventos.

## Testes

Execute:

```bash
./mvnw test
```

Prioridades:
- regras de turno;
- validação de tiro;
- posicionamento de navios;
- condição de vitória;
- timeout;
- serviços;
- endpoints críticos.

## Fluxo de desenvolvimento

1. Crie uma branch a partir da `main`.
2. Implemente apenas o escopo da tarefa.
3. Execute os testes.
4. Abra Pull Request.
5. Aguarde review antes do merge.

Exemplo:

```bash
git checkout -b feature/user-profile
```

## Documentação relacionada

- [CONTRIBUTING.md](./CONTRIBUTING.md)
- [AGENTS.md](./AGENTS.md)
- [.env.example](./.env.example)

## Observações

- O debug local não abre o navegador automaticamente ao final do bootstrap.
- Se o Docker Desktop não estiver aberto, a aplicação local não tentará iniciar sem aviso explícito.
