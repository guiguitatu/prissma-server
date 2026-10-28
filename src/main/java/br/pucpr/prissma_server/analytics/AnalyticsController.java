package br.pucpr.prissma_server.analytics;

import br.pucpr.prissma_server.analytics.AnalyticsDtos.AnalyticsDashboardResponse;
import br.pucpr.prissma_server.analytics.AnalyticsDtos.ProjectOption;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/analytics")
@Tag(name = "Analytics", description = "Construction analytics dashboard served from the analytics data warehouse.")
@SecurityRequirement(name = "bearerAuth")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    private Long resolveUserId(Authentication auth) {
        Object principal = auth.getPrincipal();
        if (principal instanceof Long userId) {
            return userId;
        }
        if (principal instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(auth.getName());
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Get the analytics dashboard",
            description = "Returns every dashboard block in a single payload, read straight from the "
                    + "materialized views. Omit projectId to consolidate all projects in the workspace.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dashboard data returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the project"),
            @ApiResponse(responseCode = "404", description = "Workspace or project not found")
    })
    public AnalyticsDashboardResponse dashboard(Authentication auth,
                                                @Parameter(description = "Project to filter by; omit for all projects",
                                                        example = "1")
                                                @RequestParam(required = false) Long projectId) {
        return service.dashboard(resolveUserId(auth), projectId);
    }

    @GetMapping("/projects")
    @Operation(summary = "List projects available in the dashboard filter",
            description = "Projects the current user can analyse within the active workspace.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Projects returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "Workspace not found")
    })
    public List<ProjectOption> projects(Authentication auth) {
        return service.projects(resolveUserId(auth));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh the analytics data warehouse",
            description = "Runs the ETL load on demand instead of waiting for the scheduled cycle. "
                    + "Restricted to workspace owners and admins.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Data warehouse refreshed"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot refresh analytics"),
            @ApiResponse(responseCode = "404", description = "Workspace not found")
    })
    public ResponseEntity<Void> refresh() {
        service.refresh();
        return ResponseEntity.noContent().build();
    }
}
