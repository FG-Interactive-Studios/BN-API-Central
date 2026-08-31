# AGENTS.md — Battleship API

## Missão

Você está trabalhando no backend de um jogo de Batalha Naval multiplayer para navegador.

Sua função é implementar alterações pequenas, seguras e coerentes com a arquitetura existente.

Antes de modificar código:
1. leia este arquivo;
2. leia o `README.md`;
3. leia o `CONTRIBUTING.md`;
4. inspecione a implementação existente;
5. não assuma contratos que ainda não existem.

## Stack obrigatória

- Java 21
- Spring Boot
- Maven
- Spring MVC
- Spring WebSocket
- Spring Security
- Spring Data JPA
- Bean Validation
- Flyway
- PostgreSQL

Não introduza novas tecnologias ou dependências sem necessidade explícita.

Especialmente, não adicione por conta própria:
- Redis;
- RabbitMQ;
- Kafka;
- WebFlux;
- GraphQL;
- microserviços;
- frameworks de mapeamento;
- novas bibliotecas de autenticação.

## Organização

O projeto é organizado por domínio/feature.

Exemplo:

```text
auth/
user/
matchmaking/
match/
store/
shared/
```

Dentro de um domínio, utilize as camadas necessárias:

```text
controller/
service/
repository/
dto/
model/
```

Não crie pastas/classes vazias apenas para completar uma estrutura.

## Regras arquiteturais

### Controllers
- finos;
- sem regra complexa;
- sem acesso direto ao banco;
- responsáveis por entrada/saída.

### Services
- concentram regras de negócio;
- coordenam domínio e persistência.

### Repositories/Stores
- encapsulam persistência/armazenamento.

### DTOs
- usados na fronteira da aplicação;
- entidades JPA não devem ser retornadas diretamente sem motivo.

## Multiplayer

O servidor é **server-authoritative**.

O frontend envia intenções.

O backend decide:
- validade do tiro;
- resultado;
- troca de turno;
- timeout;
- afundamento;
- vitória;
- recompensas;
- estado da partida.

Nunca envie ao adversário informação secreta do tabuleiro que ele não deveria conhecer.

## Estado de partida

Partidas ativas podem existir em memória.

Não transforme automaticamente:
- `GameMatch`;
- `Board`;
- `Ship`;

em entidades JPA.

Persista apenas aquilo que realmente precisa sobreviver à partida, como histórico, usuário, inventário ou recompensas.

## Concorrência

Ao trabalhar com matchmaking ou partidas:
- considere acesso concorrente;
- evite race conditions;
- não faça check-then-act inseguro;
- preserve isolamento entre partidas;
- não crie uma thread por jogador sem justificativa.

## Banco

- use Flyway;
- crie migrations incrementais;
- não use `ddl-auto=update` como mecanismo principal de schema;
- não grave secrets no repositório.

## API e contratos

Não invente nomes de endpoints, payloads ou eventos WebSocket se a tarefa não os especificar.

Ao precisar alterar contrato:
1. descreva a mudança;
2. atualize documentação;
3. mantenha consistência com o frontend.

## Segurança

- valide entradas;
- trate autorização;
- nunca confie em `userId` enviado pelo cliente quando a identidade puder vir da sessão/token;
- não exponha stack traces ou secrets;
- não desabilite segurança globalmente para “fazer funcionar”.

## Testes

Toda regra de jogo nova deve ter testes quando razoável.

Priorize testes de:
- turno;
- tiro;
- posicionamento;
- timeout;
- vitória;
- compra/recompensa.

## Estilo de trabalho

Antes de codificar uma tarefa:
1. identifique os arquivos que serão alterados;
2. proponha a menor solução suficiente;
3. implemente;
4. execute testes;
5. informe o que mudou e riscos remanescentes.

Evite refatorações amplas não solicitadas.

## Definição de pronto

Uma tarefa não está pronta se:
- não compila;
- quebra testes existentes;
- viola o contrato;
- introduz secret;
- depende de código que ninguém entende;
- adiciona complexidade sem necessidade.

Código gerado por IA deve ser compreensível e revisável pelo autor humano.
