# Loja cosmética MVP (sem dinheiro real)

**Escopo:** catálogo, créditos de **teste**, inventário pessoal e um item equipado por categoria.
Não existe integração financeira, comércio entre jogadores nem vantagem de combate.

## Endpoints (JWT obrigatório)

- `GET /api/store`: `{ "credits": 300, "items": [...] }`
- `POST /api/store/purchase/{itemId}`: desconta o preço **definido no servidor** e entrega o item; retorna o mesmo snapshot.
- `PUT /api/store/equip/{itemId}`: equipa item comprado na sua categoria; retorna o mesmo snapshot.

O item possui `id, category, name, description, price, enabled, owned, equipped`.
`category`: `SKIN` ou `SOUND`. `enabled=false`: item visível como "Em breve", não comprável.

## Valores provisórios para desenvolvimento

Cada conta recebe **300 créditos de teste** no primeiro acesso à loja (persistidos, sem recarga ao atualizar a página).
Skins: Original 0 (equipada por padrão), Coral 90, Abissal 140.
Sons: Padrão 0; Sonar Retrô e Mar Profundo ficam indisponíveis até a equipe de Game Design entregar os arquivos de áudio.
As skins têm mudanças visuais de CSS no mapa próprio; sons extras ainda não podem ser adquiridos.

**Os preços NÃO são decisão final de Game Design.** Não mostrar símbolo de moeda real nem anunciar compras reais. Não há mecanismo de ganhar créditos nesta etapa; a progressão será desenhada posteriormente.

## Segurança e atomicidade

- Nunca confiar em usuário, saldo ou preço enviado pelo navegador. O usuário vem exclusivamente do `Jwt.getSubject()`.
- Primeira abertura cria wallet, itens básicos e equipados através de `INSERT .. ON CONFLICT DO NOTHING`.
- Compra é transação única: `SELECT ... FOR UPDATE` na wallet, verificação de propriedade e saldo, débito e inserção em `player_items`. Duas compras simultâneas do mesmo item não devem duplicar débito.
- Equipar exige propriedade. `player_equipment` tem FK composta para `player_items`.
- Erros: `ITEM_NOT_FOUND` (404), `ITEM_UNAVAILABLE`, `ALREADY_OWNED`, `INSUFFICIENT_CREDITS`, `NOT_OWNED` (409).
- A migração V7 utiliza o Flyway já existente na API. Validar o banco em desenvolvimento antes de considerar deploy.

## Limitações

- Assets finais de embarcações/áudio dependem de Game Design.
- Não é marketplace, nem há integração com pagamento, moedas reais, skins sincronizadas entre jogadores ou histórico de créditos.
- Não promove nenhum usuário a administrador.
