# Prissma Server

## Descrição
...

## Pré-requisitos
* Docker/Docker Desktop

## Como rodar o projeto localmente

Copie o arquivo de exemplo de variaveis:

```bash
cp .env.example .env
```

No Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

Depois ajuste apenas os valores necessarios no `.env` e execute:

```bash
docker compose up --build
```

Assim tanto a aplicação Spring Boot quanto o banco de dados PostgreSQL serão iniciados. A aplicação estará disponível em `http://localhost:8080`.

## Dados de demonstração (seed)
O perfil `seed` popula o banco com workspaces, obras e dados de todos os módulos
(etapas, tarefas, orçamento, diário, documentos, propostas com imagens, schedule,
convites) para testar o front sem cadastrar nada na mão. A aplicação sobe
normalmente depois da carga.

```bash
# app local (usa o Postgres em localhost:5432)
./mvnw spring-boot:run -Dspring-boot.run.profiles=seed

# app no docker compose (bash)
SPRING_PROFILES_ACTIVE=seed docker compose up --build
```

No PowerShell: `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=seed"` ou
`$env:SPRING_PROFILES_ACTIVE="seed"; docker compose up --build`.

Todos os usuários da seed têm e-mail `@prissma.dev` e senha `Prissma@123`
(ex.: `carla@prissma.dev`, dona do workspace principal). A lista completa sai
no log ao final da carga. Cada execução apaga e recria só os dados desses
usuários; o resto do banco não é tocado. Não use esse perfil em produção.
O Docker Compose inicia a aplicacao Spring Boot e um PostgreSQL local. A API fica
disponivel em `http://localhost:8080`.

## Cloud Run + Neon PostgreSQL

O backend aceita diretamente a connection string entregue pelo Neon. Nao e
necessario separar host, usuario e senha em variaveis diferentes.

No Neon, copie a connection string em **Connect**. Ela costuma ter este formato:

```text
postgresql://USUARIO:SENHA@HOST/BANCO?sslmode=require&channel_binding=require
```

No Cloud Run, configure somente:

```text
DATABASE_URL=postgresql://USUARIO:SENHA@HOST/BANCO?sslmode=require&channel_binding=require
```

A aplicacao converte internamente a URL do Neon para o formato JDBC esperado
pelo driver PostgreSQL, extrai usuario e senha e traduz `channel_binding` para
a propriedade JDBC `channelBinding`.

O pool Hikari usa no maximo 5 conexoes e nao mantem conexoes ociosas por
padrao, comportamento adequado para o Cloud Run e para um banco serverless.

O Flyway continua habilitado e executa automaticamente as migrations em
`src/main/resources/db/migration` quando a aplicacao inicia.

### Outras variaveis de producao

Tambem devem ser configuradas fora do repositorio:

```text
JWT_SECRET
APP_ADMIN_PASSWORD
SPRING_MAIL_USERNAME
SPRING_MAIL_PASSWORD
OPENAI_API_KEY
PASSWORD_RESET_FRONTEND_URL
```

Nunca coloque senhas, tokens ou connection strings reais em
`application.yaml`, `docker-compose.yaml` ou arquivos versionados. No Cloud
Run, prefira fornecer `DATABASE_URL` por Secret Manager.
