package br.pucpr.prissma_server.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Contrato da API de analytics: um record por bloco do dashboard.
 *
 * Os fatos guardam medidas ADITIVAS (somas e contagens). Percentuais e médias
 * são razões entre elas — não somam entre obras — então são derivados aqui, na
 * borda, a partir dos componentes já agregados pelo banco. Nenhuma métrica é
 * recalculada a partir do schema operacional.
 */
public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    /** Item do filtro "Obra". */
    public record ProjectOption(Long projectId, String projectTitle, String projectStatus) {
    }

    /** Componentes aditivos somados pelo banco, antes de virarem indicador. */
    public record SummaryTotals(BigDecimal budgetPlanned,
                                BigDecimal budgetExecuted,
                                long totalStages,
                                long doneStages,
                                long totalTasks,
                                long doneTasks,
                                long overdueTasks,
                                long deviationDaysSum30d,
                                long deviationStagesCount30d) {
    }

    /** Os 4 KPIs do topo do dashboard. */
    public record Summary(BigDecimal budgetPlanned,
                          BigDecimal budgetExecuted,
                          BigDecimal budgetConsumedPct,
                          long totalStages,
                          long doneStages,
                          BigDecimal completionPct,
                          long totalTasks,
                          long doneTasks,
                          long overdueTasks,
                          BigDecimal avgDeviationDays30d) {

        public static Summary from(SummaryTotals t) {
            return new Summary(
                    t.budgetPlanned(),
                    t.budgetExecuted(),
                    percentage(t.budgetExecuted(), t.budgetPlanned()),
                    t.totalStages(),
                    t.doneStages(),
                    percentage(BigDecimal.valueOf(t.doneStages()), BigDecimal.valueOf(t.totalStages())),
                    t.totalTasks(),
                    t.doneTasks(),
                    t.overdueTasks(),
                    ratio(t.deviationDaysSum30d(), t.deviationStagesCount30d()));
        }

        /** Divisor zero devolve null: o front mostra "—", não 0%. */
        private static BigDecimal percentage(BigDecimal part, BigDecimal whole) {
            if (whole == null || whole.signum() == 0) {
                return null;
            }
            return part.multiply(BigDecimal.valueOf(100)).divide(whole, 2, RoundingMode.HALF_UP);
        }

        private static BigDecimal ratio(long sum, long count) {
            if (count == 0) {
                return null;
            }
            return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
        }
    }

    /** Ponto do gráfico "Orçamento previsto vs. executado ao longo do tempo". */
    public record BudgetMonthPoint(LocalDate month,
                                   BigDecimal plannedAmount,
                                   BigDecimal executedAmount,
                                   BigDecimal plannedCumulative,
                                   BigDecimal executedCumulative) {
    }

    /** Barra do gráfico "Despesas por categoria". */
    public record ExpenseCategoryLine(String category,
                                      BigDecimal plannedAmount,
                                      BigDecimal executedAmount,
                                      boolean exceeded) {
    }

    /** Ponto do "Burndown de tarefas". actualRemaining é null em semana futura. */
    public record BurndownPoint(LocalDate week,
                                long totalTasks,
                                long plannedRemaining,
                                Long actualRemaining) {
    }

    /** Linha da tabela "Etapas em atraso". */
    public record OverdueStageLine(Long stageId,
                                   Long projectId,
                                   String projectTitle,
                                   String stageName,
                                   String stageStatus,
                                   LocalDate plannedEndDate,
                                   long daysLate) {
    }

    /** Distribuição do esforço entre os membros da equipe. */
    public record MemberEffortLine(Long projectId,
                                   Long userId,
                                   String userName,
                                   String roleInProject,
                                   long assignedTasks,
                                   long doneTasks,
                                   long overdueTasks) {
    }

    /** Payload completo da tela: uma chamada carrega o dashboard inteiro. */
    public record AnalyticsDashboardResponse(OffsetDateTime lastRefreshAt,
                                             Summary summary,
                                             List<BudgetMonthPoint> budgetMonthly,
                                             List<ExpenseCategoryLine> expensesByCategory,
                                             List<BurndownPoint> burndown,
                                             List<OverdueStageLine> overdueStages,
                                             List<MemberEffortLine> memberEffort) {
    }
}
