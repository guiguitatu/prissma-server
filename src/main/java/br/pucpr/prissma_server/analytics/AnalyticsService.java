package br.pucpr.prissma_server.analytics;

import br.pucpr.prissma_server.analytics.AnalyticsDtos.AnalyticsDashboardResponse;
import br.pucpr.prissma_server.analytics.AnalyticsDtos.ProjectOption;
import br.pucpr.prissma_server.analytics.AnalyticsDtos.Summary;
import br.pucpr.prissma_server.analytics.AnalyticsRepository.Scope;
import br.pucpr.prissma_server.projects.ProjectPermission;
import br.pucpr.prissma_server.projects.ProjectPermissionService;
import br.pucpr.prissma_server.workspaces.WorkspaceContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Serving do módulo de analytics: resolve o recorte de acesso e monta o payload
 * do dashboard a partir do data warehouse.
 *
 * Sem @Transactional: são leituras isoladas em visões materializadas, fora do
 * contexto JPA.
 */
@Service
public class AnalyticsService {

    private final AnalyticsRepository repository;
    private final AnalyticsRefreshService refreshService;
    private final ProjectPermissionService permissionService;

    public AnalyticsService(AnalyticsRepository repository,
                            AnalyticsRefreshService refreshService,
                            ProjectPermissionService permissionService) {
        this.repository = repository;
        this.refreshService = refreshService;
        this.permissionService = permissionService;
    }

    /**
     * Monta o recorte da request.
     *
     * Com uma obra escolhida, quem decide é o ProjectPermissionService, que já
     * trata ADMIN global, corte entre workspaces e membership — e responde 404
     * genérico quando a obra é de outro tenant, para não revelar sua existência.
     *
     * Sem obra escolhida ("Todas as obras"), papel elevado no workspace alcança
     * todas as obras dele; os demais só enxergam aquelas em que são membros.
     */
    private Scope scope(Long userId, Long projectId) {
        WorkspaceContext ctx = WorkspaceContext.current();
        if (ctx == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found");
        }

        if (projectId != null) {
            permissionService.requirePermission(projectId, userId, ProjectPermission.VIEW_PROJECT);
            return new Scope(ctx.workspaceId(), projectId, userId, true);
        }

        return new Scope(ctx.workspaceId(), null, userId, ctx.isElevated());
    }

    public AnalyticsDashboardResponse dashboard(Long userId, Long projectId) {
        Scope scope = scope(userId, projectId);

        return new AnalyticsDashboardResponse(
                repository.findLastRefreshAt(),
                Summary.from(repository.findSummaryTotals(scope)),
                repository.findBudgetMonthly(scope),
                repository.findExpensesByCategory(scope),
                repository.findBurndown(scope),
                repository.findOverdueStages(scope),
                repository.findMemberEffort(scope));
    }

    public List<ProjectOption> projects(Long userId) {
        return repository.findProjects(scope(userId, null));
    }

    /**
     * Dispara a carga do DW sob demanda, sem esperar o ciclo de uma hora.
     * Restrito a OWNER/ADMIN do workspace: é uma operação de manutenção, e um
     * refresh custa uma releitura completa do banco operacional.
     */
    public void refresh() {
        WorkspaceContext ctx = WorkspaceContext.current();
        if (ctx == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found");
        }
        if (!ctx.isElevated()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only workspace owners or admins can refresh analytics");
        }
        refreshService.refresh();
    }
}
