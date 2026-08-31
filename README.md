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

## Princípio server-authoritative

O cliente **não decide**:
- se um tiro acertou;
- se um navio afundou;
- de quem é o turno;
- quando o turno termina;
- quem venceu;
- qual é o estado real do tabuleiro adversário.

O cliente envia intenções. O servidor valida, altera o estado da partida e publica o resultado.

## Executando localmente

### Pré-requisitos

- Java 21
- Docker + Docker Compose
- Git

### 1. Suba o PostgreSQL

```bash
docker compose up -d
```

### 2. Configure as variáveis de ambiente

Copie o exemplo:

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

### 3. Execute a aplicação

Linux/macOS:

```bash
./mvnw spring-boot:run
```

Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

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

Consulte também:
- [CONTRIBUTING.md](./CONTRIBUTING.md)
- [AGENTS.md](./AGENTS.md)
