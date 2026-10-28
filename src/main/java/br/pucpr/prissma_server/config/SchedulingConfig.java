package br.pucpr.prissma_server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Agendamento de tarefas.
 *
 * Habilita o @Scheduled, hoje usado só pelo AnalyticsRefreshService: é ele que
 * executa a carga (Load) do data warehouse analítico de hora em hora.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
