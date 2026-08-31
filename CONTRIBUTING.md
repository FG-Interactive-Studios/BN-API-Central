# Contributing — Battleship API

Este documento define o padrão mínimo de contribuição do backend.

## Branches

Não desenvolva diretamente na `main`.

Padrões recomendados:

```text
feature/<descricao>
fix/<descricao>
refactor/<descricao>
test/<descricao>
docs/<descricao>
```

Exemplos:

```text
feature/auth-login
feature/matchmaking-queue
fix/duplicate-shot
test/match-service
```

## Pull Requests

Cada PR deve:
- ter um objetivo claro;
- evitar alterações não relacionadas;
- estar compilando;
- ter testes quando a regra alterada for testável;
- explicar brevemente o que foi feito;
- informar qualquer alteração de contrato REST/WebSocket ou banco.

Evite PRs gigantes. Prefira entregas menores e revisáveis.

## Arquitetura

Organize por domínio e mantenha as camadas.

```text
feature/
├── controller/
├── service/
├── repository/
├── dto/
└── model/
```

### Controller
Deve:
- receber a entrada;
- validar formato/autorização;
- delegar regra de negócio;
- devolver resposta.

Não deve:
- conter regra complexa da partida;
- acessar banco diretamente.

### Service
Deve:
- concentrar regras de negócio;
- coordenar operações;
- trabalhar com modelos/repositorios/stores.

### Repository / Store
Deve:
- encapsular persistência ou armazenamento;
- evitar vazar detalhes de acesso a dados para Controller.

## DTOs

Prefira DTOs de request/response.

Evite expor entidades JPA diretamente para o frontend.

Exemplo:

```java
public record FireRequest(
    UUID matchId,
    int x,
    int y
) {}
```

## Banco e migrations

Toda alteração estrutural do banco deve possuir migration Flyway.

Não:
- altere banco manualmente como solução permanente;
- edite migrations já aplicadas em ambientes compartilhados.

Crie uma nova migration.

## Regras de multiplayer

O servidor é a autoridade da partida.

Nunca confie no cliente para:
- resultado de tiro;
- posição de navio adversário;
- troca de turno;
- timeout;
- vitória;
- recompensa.

## Código

Busque:
- nomes claros;
- métodos pequenos;
- baixo acoplamento;
- responsabilidade única;
- composição antes de abstrações desnecessárias.

Evite criar camadas/classes apenas para “seguir padrão” se elas não têm responsabilidade real.

## Testes

Regras do jogo devem ser testadas sempre que possível.

Casos importantes:
- tiro fora do turno;
- tiro repetido;
- coordenada inválida;
- navio sobreposto;
- timeout;
- afundamento;
- vitória;
- abandono/desconexão.

## IA no desenvolvimento

Ferramentas de IA são permitidas e incentivadas.

Porém:
- o autor do PR é responsável pelo código;
- não faça merge de código que você não consegue explicar;
- peça à IA para ler `AGENTS.md` antes de alterar o repositório;
- revise imports, dependências, segurança e edge cases;
- não permita que a IA invente endpoints ou contratos sem alinhamento.

## Antes de abrir PR

Checklist:

```text
[ ] A aplicação compila
[ ] Os testes passam
[ ] A feature está dentro do escopo
[ ] Não existem secrets commitados
[ ] Contratos alterados estão documentados
[ ] Migrations necessárias foram incluídas
[ ] Código gerado por IA foi revisado
```
