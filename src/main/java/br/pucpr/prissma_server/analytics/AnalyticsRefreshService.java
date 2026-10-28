package br.pucpr.prissma_server.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Carga (Load) do data warehouse analítico.
 *
 * Toda a transformação mora no SELECT das visões materializadas criadas pela
 * V20; aqui só se dispara o full refresh, que reexecuta esses SELECTs e regrava
 * o snapshot no schema analytics.
 *
 * CONCURRENTLY mantém as views legíveis durante a carga — o dashboard continua
 * respondendo com o snapshot anterior enquanto o novo é montado. Em troca, o
 * PostgreSQL exige um índice único em cada view (criados na V20) e PROÍBE o
 * comando dentro de um bloco de transação: por isso este método NÃO pode receber
 * @Transactional, e cada execute() roda em autocommit.
 */
@Service
public class AnalyticsRefreshService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsRefreshService.class);

    /**
     * Ordem de carga: a dimensão primeiro, o carimbo de atualização por último,
     * para que "Atualizado em" só avance depois que os fatos já estiverem no ar.
     * Lista fixa no código — identificador de objeto não aceita bind parameter
     * em DDL, então nada aqui pode vir de entrada do usuário.
     */
    private static final List<String> VIEWS = List.of(
            "analytics.dim_project",
            "analytics.fact_project_summary",
            "analytics.fact_budget_monthly",
            "analytics.fact_expense_category",
            "analytics.fact_task_burndown",
            "analytics.fact_overdue_stage",
            "analytics.fact_member_effort",
            "analytics.mv_last_refresh");

    private final JdbcTemplate jdbcTemplate;
    private final long refreshMs;

    public AnalyticsRefreshService(JdbcTemplate jdbcTemplate,
                                   @Value("${analytics.refresh-ms:3600000}") long refreshMs) {
        this.jdbcTemplate = jdbcTemplate;
        this.refreshMs = refreshMs;
    }

    @Scheduled(fixedDelayString = "${analytics.refresh-ms:3600000}")
    public void refresh() {
        long startedAt = System.nanoTime();
        int refreshed = 0;
        long totalRows = 0;

        log.info("[ETL] carga iniciada - {} views do schema analytics", VIEWS.size());

        for (String view : VIEWS) {
            long viewStartedAt = System.nanoTime();
            try {
                jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY " + view);
                long rows = countRows(view);

                refreshed++;
                totalRows += rows;
                log.info("[ETL]   {} -> snapshot com {} linhas em {}ms", view, rows, millisSince(viewStartedAt));
            } catch (RuntimeException e) {
                // Uma view quebrada não pode derrubar o resto do dashboard:
                // registra e segue, deixando aquela view com o snapshot anterior.
                log.error("[ETL]   {} -> FALHOU em {}ms (mantido o snapshot anterior)",
                        view, millisSince(viewStartedAt), e);
            }
        }

        // "linhas" é o TAMANHO do snapshot reconstruído, não o que mudou desde a
        // carga anterior: full refresh recalcula tudo, então duas rodadas sem
        // alteração no operacional dão exatamente o mesmo número.
        log.info("[ETL] carga concluida - {}/{} views, {} linhas no total, {}ms. Proxima em {}s.",
                refreshed, VIEWS.size(), totalRows, millisSince(startedAt),
                TimeUnit.MILLISECONDS.toSeconds(refreshMs));
    }

    /** Quantas linhas o snapshot novo ficou tendo — é a métrica de volume do log. */
    private long countRows(String view) {
        Long rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + view, Long.class);
        return rows == null ? 0 : rows;
    }

    private static long millisSince(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
