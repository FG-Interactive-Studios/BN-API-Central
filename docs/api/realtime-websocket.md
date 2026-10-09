# Realtime / WebSocket — V1

## Princípio de autenticação

**O cliente nunca envia `userId` para se identificar.** O jogador é
identificado exclusivamente a partir de um JWT de acesso validado pela API
(`sub` e `sid`), e o backend verifica a sessão ativa no PostgreSQL.

Navegadores não permitem adicionar manualmente o header `Authorization`
ao handshake `new WebSocket(...)`. Por isso usamos um **ticket efêmero**:
não colocamos JWT nem refresh token em URL ou parâmetro de consulta.

1. Com seu access JWT, faça `POST /api/realtime/ticket` com
   `Authorization: Bearer <accessToken>`, sem body.
2. A API responde `201` com:
   ```json
   {"ticket":"<random-opaque-ticket>","expiresAt":"2026-10-09T12:00:30Z"}
   ```
3. Em até **30 segundos**, abra `ws://localhost:8080/ws` (em produção,
   `wss://...`) oferecendo os subprotocolos
   `battleship.v1` e `bn-ticket.<ticket>`.
4. O backend consome o ticket **uma única vez** e vincula à conexão o
   usuário e a sessão identificados pelo JWT. O subprotocolo negociado
   é apenas `battleship.v1` — o ticket não é ecoado na resposta.
5. Ao receber `CONNECTED`, o cliente pode interagir com os eventos disponíveis.

Exemplo ilustrativo (browser):

```js
const ticketResponse = await fetch("/api/realtime/ticket", {
  method: "POST",
  headers: { Authorization: `Bearer ${accessToken}` },
});
if (!ticketResponse.ok) throw new Error("Could not authorize realtime connection");
const { ticket } = await ticketResponse.json();

const ws = new WebSocket("wss://api.example.com/ws", [
  "battleship.v1",
  `bn-ticket.${ticket}`,
]);
ws.onmessage = (event) => console.log(JSON.parse(event.data));
ws.onopen = () => ws.send(JSON.stringify({ type: "PING" }));
```

Observe que nenhum identificador de usuário, partida, senha ou refresh token
é enviado no WebSocket.

## Contrato V1

Mensagens JSON enviadas pelo **servidor**:

```json
{"type":"CONNECTED","data":{}}
{"type":"PONG","data":{"serverTime":"2026-10-09T12:00:00Z"}}
{"type":"ERROR","data":{"code":"UNSUPPORTED_EVENT"}}
```

A única mensagem que o **cliente** pode enviar na V1 é:

```json
{"type":"PING"}
```

Outros tipos recebem `ERROR/UNSUPPORTED_EVENT`; JSON inválido ou campos
extras (inclusive `userId`) recebem `ERROR/INVALID_EVENT`.
Mensagens de texto superiores a 4096 caracteres são encerradas pelo servidor.

O handler oferece `sendToPlayer(userId, type, data)` exclusivamente a
**serviços internos do backend**. O próprio cliente não escolhe destinatários,
não publica eventos arbitrários nem entra em canais de outros usuários.
Regras de lobby, associação a salas e autorização de ações de jogo serão
implementadas nas PRs seguintes, não neste transporte.

## Sessões, desconexão e reconexão

- O WebSocket **não expira automaticamente aos 15 minutos** do JWT inicial.
  O access token foi validado para emitir o ticket; depois a validade depende
  da sessão ativa no banco, de até 30 dias.
- Cada mensagem recebida, notificação enviada e a varredura de conexões
  (a cada aproximadamente 15 segundos) verifica se a sessão ainda está ativa.
  Ao ser revogada, a conexão é fechada com código **1008**.
- Refresh JWT não desconecta o socket existente. Um novo login em outro
  dispositivo revoga a sessão antiga; mudança de senha **não faz logout**.
- Após queda de rede ou refresh da página, emita um **novo ticket** usando
  o JWT válido atual (ou renove o access JWT via refresh antes, se necessário)
  e abra novo WebSocket.
- É possível manter mais de uma aba/WS com a **mesma sessão autenticada**;
  a política de uma sessão por jogador segue valendo.
- A API não armazena eventos para replay nem estado de partidas nesta PR.
  A próxima camada deverá recuperar o snapshot atual da sala/partida após
  reconexão. Uma queda do socket não deve, por si só, equivaler a derrota.

## Origens e implantação

O handshake aceita por padrão somente **mesma origem** (Spring WebSocket).
Se o frontend estiver em outra origem, configure explicitamente
`REALTIME_ALLOWED_ORIGINS`, como
`http://localhost:4200` para desenvolvimento. Lista separada por vírgula
é aceita; `*` é proibido. Em produção use `wss://` e restrinja as origens.

Tickets e conexões ficam **na memória da instância da API**. Para o MVP
acadêmico, rode uma instância; sistemas com múltiplas instâncias exigiriam
afinidade de sessão ou armazenamento distribuído de tickets e fan-out.
Não logue tickets, tokens, mensagens sensíveis nem protocolos completos.

## Testes

A suíte de integração testa emissão via JWT, handshake WebSocket real,
reutilização de ticket, rejeição de query string, isolamento de eventos,
revogação/logout com fechamento de socket e múltiplas abas após refresh.

Não há migração de banco, novas dependências ou serviços externos nesta PR.
