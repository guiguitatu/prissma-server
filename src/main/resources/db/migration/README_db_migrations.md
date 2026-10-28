# Migrações do Banco de Dados PRISSMA

Este documento resume a **estrutura atual do banco** do PRISSMA, explicando o papel das principais tabelas e o **padrão de migrações** usado para evoluir o schema com segurança (via **Flyway**).

> Local sugerido para este arquivo no projeto: `src/main/resources/db/migration/README.md`

---

## Diagrama do Modelo de Dados

Quando a versão PNG do modelo estiver pronta, coloque-a na mesma pasta deste README e mantenha a linha abaixo:

```mermaid
erDiagram
    USERS {
        bigint id PK
        varchar name
        varchar email UK
        varchar password
        varchar role
    }

    PASSWORD_RESET_TOKENS {
        bigint id PK
        varchar token UK
        bigint user_id FK
        timestamptz expires_at
    }

    CONSTRUCTION_PROJECTS {
        bigint id PK
        varchar title UK
        varchar address
        varchar project_type
        varchar category
        numeric land_area
        numeric built_area
        varchar status
        date planned_start_date
        date planned_end_date
        timestamptz created_at
        timestamptz updated_at
    }

    CONSTRUCTION_PROJECT_MEMBERS {
        bigint id PK
        bigint construction_project_id FK
        bigint user_id FK
        varchar role_in_project
        varchar membership_status
        timestamptz joined_at
    }

    STAGES {
        bigint id PK
        bigint construction_project_id FK
        varchar name
        text description
        int display_order
        varchar status
        varchar pavimento
        date planned_start_date
        date planned_end_date
        date actual_start_date
        date actual_end_date
        timestamptz created_at
        timestamptz updated_at
    }

    TASKS {
        bigint id PK
        bigint stage_id FK
        bigint assignee_user_id FK
        varchar title
        text description
        varchar priority
        varchar status
        date planned_start_date
        date planned_end_date
        timestamptz completed_at
        timestamptz created_at
        timestamptz updated_at
    }

    PROJECT_BUDGETS {
        bigint id PK
        bigint construction_project_id FK,UK
        text description
        numeric planned_total
        timestamptz created_at
        timestamptz updated_at
    }

    BUDGET_ITEMS {
        bigint id PK
        bigint project_budget_id FK
        varchar category
        text description
        numeric planned_amount
    }

    EXPENSES {
        bigint id PK
        bigint budget_item_id FK
        bigint stage_id FK
        text description
        numeric amount
        varchar supplier
        text receipt_url
        date spent_at
        timestamptz created_at
    }

    DESIGN_SUBMISSIONS {
        bigint id PK
        bigint stage_id FK
        bigint author_user_id FK
        varchar title
        text description
        varchar version
        varchar status
        text file_url
        timestamptz submitted_at
    }

    DESIGN_APPROVALS {
        bigint id PK
        bigint design_submission_id FK
        bigint approver_user_id FK
        varchar approval_status
        text comment
        timestamptz approved_at
    }

    ATTACHMENTS {
        bigint id PK
        bigint construction_project_id FK
        bigint stage_id FK
        bigint task_id FK
        bigint design_submission_id FK
        bigint uploaded_by_user_id FK
        varchar file_name
        varchar file_type
        text file_url
        timestamptz uploaded_at
    }

    USERS ||--o{ PASSWORD_RESET_TOKENS : has

    USERS ||--o{ CONSTRUCTION_PROJECT_MEMBERS : participates
    CONSTRUCTION_PROJECTS ||--o{ CONSTRUCTION_PROJECT_MEMBERS : has_members

    CONSTRUCTION_PROJECTS ||--o{ STAGES : has
    STAGES ||--o{ TASKS : contains
    USERS o|--o{ TASKS : assigned_to

    CONSTRUCTION_PROJECTS o|--|| PROJECT_BUDGETS : has
    PROJECT_BUDGETS ||--o{ BUDGET_ITEMS : contains
    BUDGET_ITEMS ||--o{ EXPENSES : records
    STAGES o|--o{ EXPENSES : relates_to

    STAGES ||--o{ DESIGN_SUBMISSIONS : has
    USERS ||--o{ DESIGN_SUBMISSIONS : creates

    DESIGN_SUBMISSIONS ||--o{ DESIGN_APPROVALS : receives
    USERS ||--o{ DESIGN_APPROVALS : performs

    CONSTRUCTION_PROJECTS ||--o{ ATTACHMENTS : has
    STAGES o|--o{ ATTACHMENTS : stage_context
    TASKS o|--o{ ATTACHMENTS : task_context
    DESIGN_SUBMISSIONS o|--o{ ATTACHMENTS : design_context
    USERS o|--o{ ATTACHMENTS : uploaded_by
```

---

## Estratégia de Migração (Flyway)

O projeto usa **Flyway** para versionar e aplicar mudanças no banco.

### Fluxo atual de migrações
- `V1__create_initial_schema.sql`
- `V2__create_construction_projects_core.sql`
- `V3__create_budget_tables.sql`
- `V4__create_design_tables.sql`
- `V5__create_attachments_table.sql`
- `V6__add_arch_role.sql`
- `V7__create_user_addresses_and_split_project_address.sql`
- `V8__add_pavimento_field_to_stages.sql`

- ... (V9 a V19: permissões por papel, workspaces, convites, diário, propostas,
  agenda da equipe e notificações)
- `V20__create_analytics_schema.sql`
- `V21__create_user_preferences.sql`

### Schema `analytics` (data warehouse)

A partir da `V20` o banco tem um segundo schema, `analytics`, com o data warehouse
que alimenta o dashboard de BI. Ele é composto **só por visões materializadas**
(uma dimensão `dim_project` e seis fatos, mais o carimbo `mv_last_refresh`), em
modelagem dimensional *star schema*.

A transformação inteira mora no `SELECT` de cada view; a carga é um *full
refresh* (`REFRESH MATERIALIZED VIEW CONCURRENTLY`) disparado de hora em hora
pelo `AnalyticsRefreshService`. Nada é gravado nesse schema pela aplicação — ele
é derivado, e pode ser reconstruído a qualquer momento a partir do schema
operacional. Cada view tem um índice único: é requisito do `CONCURRENTLY`.

### Regras para novas migrações
- **Não edite** migrações já executadas em ambientes compartilhados.
- Para alterar o schema, **crie uma nova migração**.
- Use nomes descritivos (ex.: `V6__add_due_date_to_tasks.sql`).
- Prefira **1 responsabilidade clara** por migração.

---

## Visão Geral (bem resumida)

- **Autenticação:** `users`, `password_reset_tokens`
- **Domínio PRISSMA:** projetos, membros, etapas, tarefas, orçamento, gastos, submissões/aprovações de design e anexos.
