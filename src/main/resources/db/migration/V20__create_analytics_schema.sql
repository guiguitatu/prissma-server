-- Data warehouse analítico do PRISSMA (seção 8 da especificação de projeto).
--
-- ETL implementado 100% dentro do PostgreSQL:
--   Extract   -> os FROM/JOIN abaixo, lendo o schema operacional (public)
--   Transform -> o próprio SELECT: agregações, métricas derivadas, descarte de
--                registros incompletos (datas nulas, órfãos)
--   Load      -> a materialização da view (REFRESH MATERIALIZED VIEW), disparada
--                de hora em hora pelo AnalyticsRefreshService (@Scheduled)
--   Serving   -> AnalyticsRepository lê daqui com SELECT puro, sem transformar
--
-- Modelagem dimensional (star schema): dim_project é a dimensão compartilhada;
-- categoria e responsável entram como degenerate dimensions dentro do fato.
--
-- TODA view tem índice ÚNICO: é requisito do REFRESH MATERIALIZED VIEW
-- CONCURRENTLY, que mantém o dashboard legível durante a carga.
-- TODO fato carrega workspace_id: é o filtro de tenant da camada de serving.

CREATE SCHEMA IF NOT EXISTS analytics;


-- ---------------------------------------------------------------------------
-- dim_project — dimensão obra.
-- ---------------------------------------------------------------------------
CREATE MATERIALIZED VIEW analytics.dim_project AS
SELECT p.id                 AS project_id,
       p.workspace_id,
       p.title              AS project_title,
       p.status             AS project_status,
       p.project_type,
       p.category,
       p.planned_start_date,
       p.planned_end_date
  FROM construction_projects p;

CREATE UNIQUE INDEX uq_analytics_dim_project ON analytics.dim_project (project_id);
CREATE INDEX idx_analytics_dim_project_ws   ON analytics.dim_project (workspace_id);


-- ---------------------------------------------------------------------------
-- mv_last_refresh — carimbo do "Atualizado em" do dashboard.
-- NOW() é avaliado no instante do REFRESH, então o valor materializado é
-- exatamente a hora da última carga do DW.
-- ---------------------------------------------------------------------------
CREATE MATERIALIZED VIEW analytics.mv_last_refresh AS
SELECT 1 AS id, NOW() AS last_refresh_at;

CREATE UNIQUE INDEX uq_analytics_last_refresh ON analytics.mv_last_refresh (id);


-- ---------------------------------------------------------------------------
-- fact_project_summary — os 4 KPIs do topo do dashboard. Grão: obra.
--
-- Guarda os componentes ADITIVOS (somas e contagens) junto dos indicadores já
-- calculados. O modo "Todas as obras" soma os componentes e deriva percentuais
-- e médias a partir deles, sem voltar ao banco operacional.
-- ---------------------------------------------------------------------------
CREATE MATERIALIZED VIEW analytics.fact_project_summary AS
WITH executed AS (
    SELECT b.construction_project_id AS project_id,
           SUM(e.amount)             AS budget_executed
      FROM expenses e
      JOIN budget_items    bi ON bi.id = e.budget_item_id
      JOIN project_budgets b  ON b.id  = bi.project_budget_id
     GROUP BY b.construction_project_id
),
stage_stats AS (
    SELECT s.construction_project_id AS project_id,
           COUNT(*)                                     AS total_stages,
           COUNT(*) FILTER (WHERE s.status = 'DONE')    AS done_stages,
           -- Desvio só existe para etapa concluída com os dois marcos preenchidos.
           -- Janela de 30 dias: é o "últimos 30 dias" do KPI.
           SUM(s.actual_end_date - s.planned_end_date) FILTER (
               WHERE s.status            = 'DONE'
                 AND s.actual_end_date  IS NOT NULL
                 AND s.planned_end_date IS NOT NULL
                 AND s.actual_end_date  >= CURRENT_DATE - 30) AS deviation_days_sum_30d,
           COUNT(*) FILTER (
               WHERE s.status            = 'DONE'
                 AND s.actual_end_date  IS NOT NULL
                 AND s.planned_end_date IS NOT NULL
                 AND s.actual_end_date  >= CURRENT_DATE - 30) AS deviation_stages_count_30d
      FROM stages s
     GROUP BY s.construction_project_id
),
task_stats AS (
    SELECT s.construction_project_id AS project_id,
           COUNT(*)                                  AS total_tasks,
           COUNT(*) FILTER (WHERE t.status = 'DONE') AS done_tasks,
           COUNT(*) FILTER (WHERE t.status            <> 'DONE'
                              AND t.planned_end_date IS NOT NULL
                              AND t.planned_end_date  < CURRENT_DATE) AS overdue_tasks
      FROM tasks t
      JOIN stages s ON s.id = t.stage_id
     GROUP BY s.construction_project_id
)
SELECT p.id           AS project_id,
       p.workspace_id,
       p.title        AS project_title,

       -- componentes aditivos
       COALESCE(b.planned_total,     0)::NUMERIC(14,2) AS budget_planned,
       COALESCE(ex.budget_executed,  0)::NUMERIC(14,2) AS budget_executed,
       COALESCE(ss.total_stages,     0)                AS total_stages,
       COALESCE(ss.done_stages,      0)                AS done_stages,
       COALESCE(ts.total_tasks,      0)                AS total_tasks,
       COALESCE(ts.done_tasks,       0)                AS done_tasks,
       COALESCE(ts.overdue_tasks,    0)                AS overdue_tasks,
       COALESCE(ss.deviation_days_sum_30d,     0)      AS deviation_days_sum_30d,
       COALESCE(ss.deviation_stages_count_30d, 0)      AS deviation_stages_count_30d,

       -- indicadores prontos (obra individual)
       ROUND(COALESCE(ex.budget_executed, 0)
             / NULLIF(b.planned_total, 0) * 100, 2)    AS budget_consumed_pct,
       ROUND(COALESCE(ss.done_stages, 0)::NUMERIC
             / NULLIF(ss.total_stages, 0) * 100, 2)    AS completion_pct,
       ROUND(ss.deviation_days_sum_30d::NUMERIC
             / NULLIF(ss.deviation_stages_count_30d, 0), 1) AS avg_deviation_days_30d
  FROM construction_projects p
  LEFT JOIN project_budgets b  ON b.construction_project_id = p.id
  LEFT JOIN executed       ex  ON ex.project_id = p.id
  LEFT JOIN stage_stats    ss  ON ss.project_id = p.id
  LEFT JOIN task_stats     ts  ON ts.project_id = p.id;

CREATE UNIQUE INDEX uq_analytics_project_summary ON analytics.fact_project_summary (project_id);
CREATE INDEX idx_analytics_project_summary_ws    ON analytics.fact_project_summary (workspace_id);


-- ---------------------------------------------------------------------------
-- fact_budget_monthly — gráfico "Orçamento previsto vs. executado ao longo do
-- tempo". Grão: obra x mês.
--
-- O modelo operacional guarda o previsto TOTAL (project_budgets.planned_total),
-- sem data: nem o total nem os itens de orçamento têm competência. A curva
-- prevista é portanto DERIVADA, distribuindo o planned_total linearmente pelos
-- meses entre planned_start_date e planned_end_date da obra. É a única métrica
-- obtida por convenção, e não por leitura direta.
-- ---------------------------------------------------------------------------
CREATE MATERIALIZED VIEW analytics.fact_budget_monthly AS
WITH planned AS (
    SELECT p.id AS project_id,
           m::DATE AS month,
           (b.planned_total / months.total)::NUMERIC(14,2) AS planned_amount
      FROM construction_projects p
      JOIN project_budgets b ON b.construction_project_id = p.id
     CROSS JOIN LATERAL (
            SELECT GREATEST(
                     (EXTRACT(YEAR  FROM p.planned_end_date)   * 12
                    + EXTRACT(MONTH FROM p.planned_end_date))
                   - (EXTRACT(YEAR  FROM p.planned_start_date) * 12
                    + EXTRACT(MONTH FROM p.planned_start_date)) + 1, 1)::NUMERIC AS total
          ) months
     CROSS JOIN LATERAL generate_series(DATE_TRUNC('month', p.planned_start_date),
                                        DATE_TRUNC('month', p.planned_end_date),
                                        INTERVAL '1 month') m
     -- obra sem cronograma planejado não entra na curva prevista
     WHERE p.planned_start_date IS NOT NULL
       AND p.planned_end_date   IS NOT NULL
       AND p.planned_end_date  >= p.planned_start_date
),
executed AS (
    SELECT b.construction_project_id            AS project_id,
           DATE_TRUNC('month', e.spent_at)::DATE AS month,
           SUM(e.amount)                        AS executed_amount
      FROM expenses e
      JOIN budget_items    bi ON bi.id = e.budget_item_id
      JOIN project_budgets b  ON b.id  = bi.project_budget_id
     GROUP BY b.construction_project_id, DATE_TRUNC('month', e.spent_at)
),
months AS (
    SELECT project_id, month FROM planned
    UNION
    SELECT project_id, month FROM executed
)
SELECT p.workspace_id,
       k.project_id,
       k.month,
       COALESCE(pl.planned_amount,  0)::NUMERIC(14,2) AS planned_amount,
       COALESCE(ex.executed_amount, 0)::NUMERIC(14,2) AS executed_amount,
       SUM(COALESCE(pl.planned_amount,  0))
           OVER (PARTITION BY k.project_id ORDER BY k.month)::NUMERIC(14,2) AS planned_cumulative,
       SUM(COALESCE(ex.executed_amount, 0))
           OVER (PARTITION BY k.project_id ORDER BY k.month)::NUMERIC(14,2) AS executed_cumulative
  FROM months k
  JOIN construction_projects p ON p.id = k.project_id
  LEFT JOIN planned  pl ON pl.project_id = k.project_id AND pl.month = k.month
  LEFT JOIN executed ex ON ex.project_id = k.project_id AND ex.month = k.month;

CREATE UNIQUE INDEX uq_analytics_budget_monthly ON analytics.fact_budget_monthly (project_id, month);
CREATE INDEX idx_analytics_budget_monthly_ws    ON analytics.fact_budget_monthly (workspace_id);


-- ---------------------------------------------------------------------------
-- fact_expense_category — gráfico "Despesas por categoria".
-- Grão: obra x categoria (categoria é degenerate dimension).
-- ---------------------------------------------------------------------------
CREATE MATERIALIZED VIEW analytics.fact_expense_category AS
WITH planned AS (
    SELECT b.construction_project_id AS project_id,
           bi.category,
           SUM(bi.planned_amount)    AS planned_amount
      FROM budget_items    bi
      JOIN project_budgets b ON b.id = bi.project_budget_id
     GROUP BY b.construction_project_id, bi.category
),
executed AS (
    SELECT b.construction_project_id AS project_id,
           bi.category,
           SUM(e.amount)             AS executed_amount
      FROM expenses e
      JOIN budget_items    bi ON bi.id = e.budget_item_id
      JOIN project_budgets b  ON b.id  = bi.project_budget_id
     GROUP BY b.construction_project_id, bi.category
),
categories AS (
    SELECT project_id, category FROM planned
    UNION
    SELECT project_id, category FROM executed
)
SELECT p.workspace_id,
       k.project_id,
       k.category,
       COALESCE(pl.planned_amount,  0)::NUMERIC(14,2) AS planned_amount,
       COALESCE(ex.executed_amount, 0)::NUMERIC(14,2) AS executed_amount,
       (COALESCE(ex.executed_amount, 0) > COALESCE(pl.planned_amount, 0)) AS exceeded
  FROM categories k
  JOIN construction_projects p ON p.id = k.project_id
  LEFT JOIN planned  pl ON pl.project_id = k.project_id AND pl.category = k.category
  LEFT JOIN executed ex ON ex.project_id = k.project_id AND ex.category = k.category;

CREATE UNIQUE INDEX uq_analytics_expense_category ON analytics.fact_expense_category (project_id, category);
CREATE INDEX idx_analytics_expense_category_ws    ON analytics.fact_expense_category (workspace_id);


-- ---------------------------------------------------------------------------
-- fact_task_burndown — gráfico "Burndown de tarefas". Grão: obra x semana.
--
-- planned_remaining: total de tarefas menos as que DEVERIAM estar prontas até o
--                    fim da semana (planned_end_date).
-- actual_remaining : total menos as efetivamente concluídas até o fim da semana
--                    (completed_at). NULO para semanas futuras — é o que faz a
--                    linha real parar no presente, como no protótipo.
-- ---------------------------------------------------------------------------
CREATE MATERIALIZED VIEW analytics.fact_task_burndown AS
WITH project_tasks AS (
    SELECT s.construction_project_id AS project_id,
           t.planned_end_date,
           t.completed_at::DATE      AS completed_on
      FROM tasks t
      JOIN stages s ON s.id = t.stage_id
),
bounds AS (
    -- LEAST/GREATEST ignoram NULL: obra só com datas nulas fica com
    -- first_week NULL e é descartada pelo WHERE do CTE weeks.
    SELECT project_id,
           DATE_TRUNC('week', LEAST(MIN(planned_end_date),    MIN(completed_on)))::DATE AS first_week,
           DATE_TRUNC('week', GREATEST(MAX(planned_end_date), MAX(completed_on)))::DATE AS last_week,
           COUNT(*) AS total_tasks
      FROM project_tasks
     GROUP BY project_id
),
weeks AS (
    SELECT b.project_id, b.total_tasks, w::DATE AS week
      FROM bounds b
     CROSS JOIN LATERAL generate_series(b.first_week, b.last_week, INTERVAL '1 week') w
     WHERE b.first_week IS NOT NULL
       AND b.last_week  IS NOT NULL
)
SELECT p.workspace_id,
       wk.project_id,
       wk.week,
       wk.total_tasks,
       (wk.total_tasks - COUNT(*) FILTER (
            WHERE pt.planned_end_date IS NOT NULL
              AND pt.planned_end_date <= wk.week + 6)) AS planned_remaining,
       CASE WHEN wk.week <= CURRENT_DATE
            THEN (wk.total_tasks - COUNT(*) FILTER (
                     WHERE pt.completed_on IS NOT NULL
                       AND pt.completed_on <= wk.week + 6))
       END AS actual_remaining
  FROM weeks wk
  JOIN construction_projects p  ON p.id = wk.project_id
  JOIN project_tasks         pt ON pt.project_id = wk.project_id
 GROUP BY p.workspace_id, wk.project_id, wk.week, wk.total_tasks;

CREATE UNIQUE INDEX uq_analytics_task_burndown ON analytics.fact_task_burndown (project_id, week);
CREATE INDEX idx_analytics_task_burndown_ws    ON analytics.fact_task_burndown (workspace_id);


-- ---------------------------------------------------------------------------
-- fact_overdue_stage — tabela "Etapas em atraso". Grão: etapa atrasada.
-- Atraso = etapa ainda não concluída cujo prazo planejado já passou.
-- O status vai cru (PLANNED/IN_PROGRESS/BLOCKED); o rótulo é do front.
-- ---------------------------------------------------------------------------
CREATE MATERIALIZED VIEW analytics.fact_overdue_stage AS
SELECT s.id                            AS stage_id,
       p.workspace_id,
       p.id                            AS project_id,
       p.title                         AS project_title,
       s.name                          AS stage_name,
       s.status                        AS stage_status,
       s.planned_end_date,
       (CURRENT_DATE - s.planned_end_date) AS days_late
  FROM stages s
  JOIN construction_projects p ON p.id = s.construction_project_id
 WHERE s.status            <> 'DONE'
   AND s.planned_end_date IS NOT NULL
   AND s.planned_end_date  < CURRENT_DATE;

CREATE UNIQUE INDEX uq_analytics_overdue_stage ON analytics.fact_overdue_stage (stage_id);
CREATE INDEX idx_analytics_overdue_stage_ws    ON analytics.fact_overdue_stage (workspace_id);


-- ---------------------------------------------------------------------------
-- fact_member_effort — "distribuição do esforço entre os membros da equipe"
-- (Objetivo de Negócios). Grão: obra x usuário.
--
-- Parte dos MEMBROS, não das tarefas: membro ativo sem tarefa atribuída aparece
-- zerado, que é a informação relevante para alocação de recursos.
-- ---------------------------------------------------------------------------
CREATE MATERIALIZED VIEW analytics.fact_member_effort AS
SELECT p.workspace_id,
       m.construction_project_id AS project_id,
       m.user_id,
       u.name                    AS user_name,
       m.role_in_project,
       COUNT(t.id)                                  AS assigned_tasks,
       COUNT(t.id) FILTER (WHERE t.status = 'DONE') AS done_tasks,
       COUNT(t.id) FILTER (WHERE t.status            <> 'DONE'
                             AND t.planned_end_date IS NOT NULL
                             AND t.planned_end_date  < CURRENT_DATE) AS overdue_tasks
  FROM construction_project_members m
  JOIN users                 u ON u.id = m.user_id
  JOIN construction_projects p ON p.id = m.construction_project_id
  LEFT JOIN stages s ON s.construction_project_id = m.construction_project_id
  LEFT JOIN tasks  t ON t.stage_id = s.id
                    AND t.assignee_user_id = m.user_id
 WHERE m.membership_status = 'ACTIVE'
 GROUP BY p.workspace_id, m.construction_project_id, m.user_id, u.name, m.role_in_project;

CREATE UNIQUE INDEX uq_analytics_member_effort ON analytics.fact_member_effort (project_id, user_id);
CREATE INDEX idx_analytics_member_effort_ws    ON analytics.fact_member_effort (workspace_id);
