# Gestão administrativa dos modos de tabuleiro — V1

A arquitetura de regras de jogo continua **exclusivamente no Java**.
Este módulo controla apenas quais definições de `GameModeDefinition`
podem ser escolhidas para **NOVOS** lobbies. Não cria geometrias,
frotas, scripts de poderes ou regras arbitrárias enviadas por HTTP.

## Primeira instalação e autorização

Migration Flyway `V6__game_mode_availability.sql`:
- `users.is_admin BOOLEAN NOT NULL DEFAULT FALSE`;
- `game_mode_settings(mode_id, enabled, updated_at, updated_by)`,
  com FK opcional para o usuário que fez a última alteração;
- Semeia **habilitados**: `classic`, `quick`, `triangular`,
  `triangle-tiles` e `hexagons`.
- **Novos modos registrados em Java**, sem linha nesta tabela,
  aparecem desabilitados até ativação administrativa.

**Bootstrap automático do primeiro admin:** no primeiro start de uma
instância com banco migrado e **sem admins**, a aplicação gera um
nickname e e-mail aleatórios e uma senha criptograficamente forte,
persiste o hash BCrypt e grava as credenciais em um arquivo legível
somente pelo usuário do sistema (permissão `0600` em Linux/POSIX).

Destino padrão: `~/.battleship/bootstrap-admin-credentials.txt`
(no Windows, sob a pasta do perfil do usuário que executa a API).
O arquivo é criado uma única vez, sem sobrescrita. A aplicação
**não imprime a senha em logs**; informa apenas o caminho absoluto.
Entre no painel usando e-mail/senha do arquivo, altere a senha
em `POST /api/auth/change-password` e apague o arquivo com segurança.
Proteja o acesso ao servidor e mantenha o caminho fora do repositório.

Configurações:
- `ADMIN_BOOTSTRAP_ENABLED=true` (padrão; CI usa `false`).
- `ADMIN_BOOTSTRAP_CREDENTIALS_FILE=/caminho/protegido/credenciais.txt`
  (opcional; recomendado sob o diretório privado do usuário do processo).

Com admin existente, nenhuma nova conta ou arquivo é criado.
**Se o banco for resetado e ainda existir um arquivo antigo**, o bootstrap
recusa sobrescrevê-lo e impede a inicialização; confira o estado do banco
e arquive/remova o arquivo antigo **antes** de reiniciar. Não mantenha
credenciais bootstrap em logs, backups públicos ou imagens Docker.

**Não existe rota pública para conceder permissões administrativas.**
Operadores também podem promover explicitamente uma conta já cadastrada:

```sql
UPDATE users SET is_admin = TRUE WHERE id = <ID_DO_ADMINISTRADOR>;
```

Substitua `<ID_DO_ADMINISTRADOR>` por um ID numérico verificado.
Revogação: `UPDATE users SET is_admin = FALSE WHERE id = ...;`.
A API verifica `is_admin` **diretamente no banco em toda operação
administrativa**, inclusive com JWT já emitido. Não confia numa claim
administrativa ou num `userId` enviado pelo frontend.

As permissões são **globais ao backend**; este projeto ainda não tem
organizações, equipes ou cargos de servidor. Futuramente, um modelo RBAC
pode substituir `is_admin`, sem alterar o contrato de disponibilidade.

## API de administração

Todas as rotas usam JWT de uma sessão ativa. Usuários comuns recebem
`403 Forbidden`; sem JWT, `401 Unauthorized`.

| Método | Rota | Descrição |
|---|---|---|
| GET | `/api/admin/game-modes` | Lista **todos** os modos registrados, habilitados ou não |
| PUT | `/api/admin/game-modes/{modeId}/availability` | Habilita ou desabilita um modo |

Payload de exemplo:

```http
PUT /api/admin/game-modes/hexagons/availability
Authorization: Bearer <token>
Content-Type: application/json

{"enabled":false}
```

A resposta é um `AdminGameModeResponse`:

```json
{
  "mode": {"id":"hexagons","name":"Hexagonal"},
  "enabled": false,
  "updatedAt": "2026-10-13T12:00:00Z",
  "updatedBy": 123
}
```

`mode` contém o **ModeSummary completo** (geometria, `cells`,
`tiles`, frota, direções de posicionamento e IDs das mecânicas);
o JSON acima mostra só os campos principais. `updatedBy` é um
**ID de saída** para auditoria, jamais identidade recebida no payload.
O serviço valida `enabled` como booleano obrigatório e rejeita
modeIds desconhecidos com `404`, corpo inválido com `400`.
Tentar desativar o **último modo disponível** retorna `409`.
Repetir o valor atual não altera `updatedAt` nem `updatedBy`.

## Contratos para jogadores

`GET /api/game-modes` permanece autenticado, com a **mesma estrutura
de resposta** (`List<ModeSummary>`), mas agora contém apenas os
modos habilitados. A listagem pública **não expõe `is_admin`,
`updatedBy` nem estado de outros modos ocultos**.

`POST /api/matchmaking/lobbies` aceita `{"modeId":"quick"}`,
`{"modeId":"hexagons"}` etc. e só cria sala se o modo estiver ativo.
`{"modeId":"<modo_desativado>"}` retorna `409 Conflict`,
sem reservar código ou associar o usuário a qualquer sala.
IDs desconhecidos continuam retornando `400`.
Sem corpo, o modo-padrão continua sendo `classic` (se habilitado).

A checagem de modo + alocação do lobby compartilha um lock
**na instância da API** com as alterações administrativas, evitando
a condição de corrida clássica entre desabilitar e criar sala.

### Lobbies existentes nunca são interrompidos

Desativar `hexagons` enquanto uma sala desse modo já existe:
- **bloqueia novas salas** no modo;
- **não impede** o segundo jogador de entrar na sala existente;
- não altera `LOBBY_UPDATED`, o posicionamento dos navios, turnos,
  disparos, reconexão ou finalização;
- preserva a definição de regras daquela partida em memória.

A disponibilidade é persistida no PostgreSQL, sobrevivendo reinícios;
lobbies e batalhas existentes continuam sendo um MVP **in-memory** de
instância única e são perdidos ao reiniciar, como antes.

## Para a futura interface administrativa

Uma tela simples pode usar `GET /api/admin/game-modes` e enviar
`PUT .../availability` a cada toggle. Mostrar nome, geometria,
quantidade de casas, frota, mecânicas, disponibilidade e último autor/
instante de alteração. Exigir confirmação antes de desativar o último
modo escolhido em um fluxo de navegação; o backend continua sendo a
autoridade.

Não criar controles no frontend para editar `geometry`,
`placement` ou `rules` neste momento. O próximo passo — criar
**modos personalizados** ou dimensionar tabuleiros com configurações
administráveis — exige um contrato de especificação versionada
validado contra implementações Java registradas. Isso é uma entrega
separada, não uma consequência implícita de habilitar/desabilitar.
