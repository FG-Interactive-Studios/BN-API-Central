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


## Catálogo versionado e bootstrap automático (V9)

A API agora utiliza `src/main/resources/store/catalog-v1.json` como **fonte de verdade do catálogo inicial**, revisada junto com o código, executada após Flyway na inicialização por `StoreCatalogBootstrapRunner`. No primeiro boot após V9, registra os produtos novos com `INSERT ... ON CONFLICT DO NOTHING`; qualquer reinício repete a operação com segurança. A versão desse catálogo não é a versão de preço do produto: cada produto possui seu próprio campo `revision`.

**Segurança de migração:** os seis produtos da V7, suas categorias, preços, propriedade e equipamentos são preservados. A tabela `store_catalog_managed` registra o `applied_revision`; nenhuma propriedade comercial existente é sobrescrita ao adotar um item na versão inicial. Para atualizar nome, descrição, preço ou disponibilidade de um produto já cadastrado, a equipe precisa alterar seu registro no JSON e **aumentar explicitamente `revision`**; o bootstrap só aplica a atualização quando a revisão registrada no banco for inferior. Um binário antigo nunca reverte uma revisão mais recente. Não há migrações destrutivas em carteiras, itens comprados ou equipamento.

**Estados e aprovação:** `DRAFT` é obrigatoriamente indisponível e é re-desativado em cada inicialização caso alguém o tenha habilitado diretamente no banco; `RELEASED` exige `assetsVerified`, `priceApproved` e `approvedForSale` verdadeiros. Novos produtos sem preço aprovado entram com preço técnico 0 e **nunca** como itens gratuitos à venda. O frontend não tem autoridade para mudar preço nem disponibilidade. Não ativar nenhum pacote de asset até os arquivos efetivos serem importados e validados no frontend, além da revisão comercial. Para `CAPTAIN` e `BUNDLE`, ainda é necessária a implementação de propriedade de avatar e concessão atômica de componentes, respectivamente; ambas as categorias têm compra e equipamento bloqueados pela API mesmo que alguém altere `enabled` no banco.

**Produtos preparados na V9:** `skin_corsair`, `skin_space`, `skin_pirate`, `skin_magnate`, `captain_default`, `captain_corsair`, `captain_space`, `bundle_corsair` e `bundle_space`. As duas coleções contêm respectivamente frota e capitão de seus temas; a tabela `store_bundle_components` registra essas relações, mas **a compra de coleções está indisponível até a transação composta ser implementada**. Sons extras `sound_sonar` e `sound_deep` permanecem indisponíveis. Todos os novos produtos são `DRAFT` e sem preço aprovado.

**Operação:** o serviço requer PostgreSQL, que já é exigido pelas migrations Flyway. A flag `STORE_CATALOG_BOOTSTRAP_ENABLED=false` desabilita somente o registro automático para ambientes especiais; o padrão é habilitado. O perfil de teste H2 sem migrations mantém o bootstrap desligado. Os testes PostgreSQL verificam a carga automática, idempotência, preservação de preços e relação dos bundles. Esse processo é executado **ao iniciar a API**, não a cada atualização do catálogo do frontend, e não executa deploy por conta própria.
