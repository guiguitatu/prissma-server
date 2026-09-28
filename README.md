# Prissma Server

## Descrição
...

## Pré-requisitos
* Docker/Docker Desktop

## Como rodar o projeto
Tando em ambiente Windowns ou Linux, basta rodar o comando abaixo:

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