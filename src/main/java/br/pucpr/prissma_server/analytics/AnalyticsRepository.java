package br.pucpr.prissma_server.analytics;

import br.pucpr.prissma_server.analytics.AnalyticsDtos.BudgetMonthPoint;
import br.pucpr.prissma_server.analytics.AnalyticsDtos.BurndownPoint;
import br.pucpr.prissma_server.analytics.AnalyticsDtos.ExpenseCategoryLine;
import br.pucpr.prissma_server.analytics.AnalyticsDtos.MemberEffortLine;
import br.pucpr.prissma_server.analytics.AnalyticsDtos.OverdueStageLine;
import br.pucpr.prissma_server.analytics.AnalyticsDtos.ProjectOption;
import br.pucpr.prissma_server.analytics.AnalyticsDtos.SummaryTotals;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Camada de serving: lê o data warehouse com SELECT puro.
 *
 * Único ponto do projeto que usa JDBC em vez de Spring Data/JPQL. O motivo é o
 * alvo: visão materializada não é tabela, e mapeá-la como @Entity colide com
 * spring.jpa.hibernate.ddl-auto=validate, que confere o schema na subida. O
 * NamedParameterJdbcTemplate já vem junto com o starter de data-jpa, então não
 * há dependência nova.
 *
 * Nenhuma consulta aqui transforma dado: as agregações são somas de medidas já
 * calculadas na carga, usadas apenas para consolidar várias obras em uma linha.
 */
@Repository
public class AnalyticsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AnalyticsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Recorte de leitura de uma request.
     *
     * @param projectId   obra escolhida no filtro, ou null para "Todas as obras"
     * @param allProjects true quando o papel no workspace alcança todas as obras;
     *                    false restringe às obras em que o usuário é membro ativo
     */
    public record Scope(Long workspaceId, Long projectId, Long userId, boolean allProjects) {
    }

    /**
     * Filtro aplicado a TODA leitura do DW.
     *
     * O recorte por membership consulta construction_project_members ao vivo, e
     * não uma view materializada: métrica pode estar até uma hora atrasada,
     * permissão não pode. Membro removido perde o acesso na hora.
     */
    private String where(Scope scope) {
        StringBuilder sql = new StringBuilder(" WHERE workspace_id = :workspaceId");
        if (scope.projectId() != null) {
            sql.append(" AND project_id = :projectId");
        }
        if (!scope.allProjects()) {
            sql.append(" AND project_id IN (SELECT construction_project_id"
                    + " FROM construction_project_members"
                    + " WHERE user_id = :userId AND membership_status = 'ACTIVE')");
        }
        return sql.toString();
    }

    private MapSqlParameterSource params(Scope scope) {
        return new MapSqlParameterSource()
                .addValue("workspaceId", scope.workspaceId())
                .addValue("projectId", scope.projectId())
                .addValue("userId", scope.userId());
    }

    /** Carimbo do "Atualizado em": a hora da última carga do DW. */
    public OffsetDateTime findLastRefreshAt() {
        List<OffsetDateTime> rows = jdbc.query(
                "SELECT last_refresh_at FROM analytics.mv_last_refresh",
                new MapSqlParameterSource(),
                (rs, i) -> rs.getObject("last_refresh_at", OffsetDateTime.class));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ProjectOption> findProjects(Scope scope) {
        String sql = "SELECT project_id, project_title, project_status FROM analytics.dim_project"
                + where(scope) + " ORDER BY project_title";

        return jdbc.query(sql, params(scope), (rs, i) -> new ProjectOption(
                rs.getLong("project_id"),
                rs.getString("project_title"),
                rs.getString("project_status")));
    }

    /**
     * Soma os componentes aditivos do recorte. Com uma obra só, a soma devolve a
     * própria linha; com várias, consolida — mesma consulta nos dois casos.
     */
    public SummaryTotals findSummaryTotals(Scope scope) {
        String sql = """
                SELECT COALESCE(SUM(budget_planned),             0) AS budget_planned,
                       COALESCE(SUM(budget_executed),            0) AS budget_executed,
                       COALESCE(SUM(total_stages),               0) AS total_stages,
                       COALESCE(SUM(done_stages),                0) AS done_stages,
                       COALESCE(SUM(total_tasks),                0) AS total_tasks,
                       COALESCE(SUM(done_tasks),                 0) AS done_tasks,
                       COALESCE(SUM(overdue_tasks),              0) AS overdue_tasks,
                       COALESCE(SUM(deviation_days_sum_30d),     0) AS deviation_days_sum_30d,
                       COALESCE(SUM(deviation_stages_count_30d), 0) AS deviation_stages_count_30d
                  FROM analytics.fact_project_summary""" + where(scope);

        return jdbc.queryForObject(sql, params(scope), (rs, i) -> new SummaryTotals(
                rs.getBigDecimal("budget_planned"),
                rs.getBigDecimal("budget_executed"),
                rs.getLong("total_stages"),
                rs.getLong("done_stages"),
                rs.getLong("total_tasks"),
                rs.getLong("done_tasks"),
                rs.getLong("overdue_tasks"),
                rs.getLong("deviation_days_sum_30d"),
                rs.getLong("deviation_stages_count_30d")));
    }

    public List<BudgetMonthPoint> findBudgetMonthly(Scope scope) {
        String sql = """
                SELECT month,
                       SUM(planned_amount)      AS planned_amount,
                       SUM(executed_amount)     AS executed_amount,
                       SUM(planned_cumulative)  AS planned_cumulative,
                       SUM(executed_cumulative) AS executed_cumulative
                  FROM analytics.fact_budget_monthly"""
                + where(scope) + " GROUP BY month ORDER BY month";

        return jdbc.query(sql, params(scope), (rs, i) -> new BudgetMonthPoint(
                rs.getObject("month", LocalDate.class),
                rs.getBigDecimal("planned_amount"),
                rs.getBigDecimal("executed_amount"),
                rs.getBigDecimal("planned_cumulative"),
                rs.getBigDecimal("executed_cumulative")));
    }

    public List<ExpenseCategoryLine> findExpensesByCategory(Scope scope) {
        String sql = """
                SELECT category,
                       SUM(planned_amount)  AS planned_amount,
                       SUM(executed_amount) AS executed_amount
                  FROM analytics.fact_expense_category"""
                + where(scope) + " GROUP BY category ORDER BY SUM(executed_amount) DESC, category";

        return jdbc.query(sql, params(scope), (rs, i) -> {
            BigDecimal planned = rs.getBigDecimal("planned_amount");
            BigDecimal executed = rs.getBigDecimal("executed_amount");
            return new ExpenseCategoryLine(rs.getString("category"), planned, executed,
                    executed.compareTo(planned) > 0);
        });
    }

    public List<BurndownPoint> findBurndown(Scope scope) {
        String sql = """
                SELECT week,
                       SUM(total_tasks)       AS total_tasks,
                       SUM(planned_remaining) AS planned_remaining,
                       SUM(actual_remaining)  AS actual_remaining
                  FROM analytics.fact_task_burndown"""
                + where(scope) + " GROUP BY week ORDER BY week";

        return jdbc.query(sql, params(scope), (rs, i) -> new BurndownPoint(
                rs.getObject("week", LocalDate.class),
                rs.getLong("total_tasks"),
                rs.getLong("planned_remaining"),
                nullableLong(rs, "actual_remaining")));
    }

    public List<OverdueStageLine> findOverdueStages(Scope scope) {
        String sql = """
                SELECT stage_id, project_id, project_title, stage_name,
                       stage_status, planned_end_date, days_late
                  FROM analytics.fact_overdue_stage"""
                + where(scope) + " ORDER BY days_late DESC, project_title, stage_name";

        return jdbc.query(sql, params(scope), (rs, i) -> new OverdueStageLine(
                rs.getLong("stage_id"),
                rs.getLong("project_id"),
                rs.getString("project_title"),
                rs.getString("stage_name"),
                rs.getString("stage_status"),
                rs.getObject("planned_end_date", LocalDate.class),
                rs.getLong("days_late")));
    }

    public List<MemberEffortLine> findMemberEffort(Scope scope) {
        String sql = """
                SELECT project_id, user_id, user_name, role_in_project,
                       assigned_tasks, done_tasks, overdue_tasks
                  FROM analytics.fact_member_effort"""
                + where(scope) + " ORDER BY done_tasks DESC, user_name";

        return jdbc.query(sql, params(scope), (rs, i) -> new MemberEffortLine(
                rs.getLong("project_id"),
                rs.getLong("user_id"),
                rs.getString("user_name"),
                rs.getString("role_in_project"),
                rs.getLong("assigned_tasks"),
                rs.getLong("done_tasks"),
                rs.getLong("overdue_tasks")));
    }

    /** Semana futura não tem linha real: o valor vem nulo e assim deve seguir. */
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
