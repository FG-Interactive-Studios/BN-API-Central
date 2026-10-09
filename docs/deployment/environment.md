# Ambiente de produção — BN-API-Central

O projeto disponibiliza [`.env.production.example`](../../.env.production.example)
como **modelo**, não como configuração pronta para execução. O modelo
contém apenas *placeholders*, não senhas nem chaves válidas.

## Variáveis reconhecidas pela aplicação

| Variável | Obrigatória em produção | Descrição |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | Recomendada (`prod`) | Define explicitamente o perfil de produção; **nunca** use `local` em produção |
| `PORT` | Não | Porta HTTP, padrão `8080` |
| `DB_URL` | Sim | URL JDBC do PostgreSQL, incluindo host, porta e database |
| `DB_USER` | Sim | Usuário PostgreSQL dedicado, de privilégio mínimo compatível com Flyway |
| `DB_PASSWORD` | Sim | Senha da conta de banco |
| `AUTH_JWT_SECRET_B64` | **Sim** | Chave secreta Base64 persistente, decodificando em pelo menos 32 bytes aleatórios |

A configuração atual de `application.yaml` lê exatamente essas variáveis.
Existem valores padrão de banco apenas para desenvolvimento: **não** confie
nos defaults ao iniciar em produção.

## Como preparar

1. Copie o modelo para um arquivo **não versionado** (`.env.production`),
   preferencialmente fora do checkout Git.
2. Ajuste `DB_URL`, `DB_USER` e `DB_PASSWORD` com dados reais do PostgreSQL.
   Se a API rodar em container, use um hostname acessível a partir do
   **container**; não use `localhost` para acessar outro container.
3. Gere uma chave uma única vez:

   ```bash
   openssl rand -base64 48
   ```

   Configure o resultado em `AUTH_JWT_SECRET_B64`. Use um gerenciador de
   segredos do ambiente de implantação quando disponível.
4. Proteja o arquivo real, por exemplo `chmod 600 .env.production` em Linux,
   e restrinja o acesso ao usuário responsável pelo serviço.

## Aplicar as variáveis

O Docker recebe um arquivo de ambiente com `--env-file`, por exemplo:

```bash
docker run --rm --env-file /caminho/seguro/.env.production -p 8080:8080 ghcr.io/fg-interactive-studios/bn-api-central:latest
```

Esse exemplo **não** instala PostgreSQL, TLS/reverse proxy nem configura
persistência do banco. Garanta que o banco esteja acessível e com backup.
O `docker-compose.yml` do repositório é destinado ao desenvolvimento local,
não é um template de produção.

Para execução Java/Maven fora do Docker, configure as mesmas variáveis no
serviço/processo (systemd, orquestrador ou shell) antes de iniciar.
**Spring Boot não carrega automaticamente o arquivo `.env.production`**.

## Requisitos de segurança

- O perfil `local` permite geração de uma chave JWT temporária. **Não use**
  esse perfil em produção. Fora de `local`, uma chave ausente, curta ou
  inválida faz a aplicação recusar a inicialização.
- Use a **mesma chave persistente** entre instâncias da API; não a troque
  a cada deploy. Uma rotação de chave invalida access tokens já emitidos.
  Refresh tokens continuam sujeitos às sessões armazenadas no banco.
- Armazene `DB_PASSWORD` e `AUTH_JWT_SECRET_B64` fora do Git, imagens Docker
  e logs. Nunca cole esses valores em tickets ou PRs.
- Use HTTPS no acesso público à API e transporte seguro para o banco.
- Flyway é executado na inicialização; providencie permissões adequadas às
  migrations e um processo de backup/rollback de banco.
- Antes de expor login publicamente, configure limitação de tentativas de
  autenticação na borda/API, como já documentado em
  [autenticação e sessões](../api/auth-sessions.md).
