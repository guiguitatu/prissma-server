package br.pucpr.prissma_server.schedule;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/projects/{projectId}/schedule")
@Tag(name = "Schedule", description = "Project team schedule, responsibilities and daily allocations.")
@SecurityRequirement(name = "bearerAuth")
public class ScheduleController {

    private final ScheduleService service;

    public ScheduleController(ScheduleService service) {
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

    // Datas, view e corpos são validados no service (sem @Valid): erros de
    // conversão/validação do Spring cairiam no handler genérico e virariam 500.
    @GetMapping
    @Operation(summary = "Get project schedule", description = "Returns the project team schedule for the requested view and reference date.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Schedule returned"),
            @ApiResponse(responseCode = "400", description = "Invalid view or date"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the project"),
            @ApiResponse(responseCode = "404", description = "Project not found")
    })
    public ResponseEntity<TeamScheduleResponse> get(@PathVariable Long projectId,
                                                    @Parameter(description = "Schedule view requested by the client")
                                                    @RequestParam(required = false) String view,
                                                    @Parameter(description = "Reference date in ISO-8601 format (yyyy-MM-dd)", example = "2026-10-21")
                                                    @RequestParam(required = false) String date,
                                                    Authentication auth) {
        return ResponseEntity.ok(service.getSchedule(projectId, view, date, resolveUserId(auth)));
    }

    @PutMapping("/members/{memberUserId}/responsibility")
    @Operation(summary = "Update member responsibility", description = "Updates the responsibility assigned to a project member in the schedule.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Responsibility updated"),
            @ApiResponse(responseCode = "400", description = "Invalid responsibility data"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to manage the schedule"),
            @ApiResponse(responseCode = "404", description = "Project or member not found")
    })
    public ResponseEntity<ScheduleMemberResponse> updateResponsibility(@PathVariable Long projectId,
                                                                       @PathVariable Long memberUserId,
                                                                       @RequestBody ScheduleResponsibilityRequest request,
                                                                       Authentication auth) {
        return ResponseEntity.ok(service.updateResponsibility(projectId, memberUserId, request, resolveUserId(auth)));
    }

    @PutMapping("/members/{memberUserId}/allocations/{date}")
    @Operation(summary = "Create or update a daily allocation", description = "Creates or replaces a member allocation for a specific project date.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Allocation saved"),
            @ApiResponse(responseCode = "400", description = "Invalid date or allocation data"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to manage the schedule"),
            @ApiResponse(responseCode = "404", description = "Project or member not found")
    })
    public ResponseEntity<ScheduleAllocationResponse> upsertAllocation(@PathVariable Long projectId,
                                                                       @PathVariable Long memberUserId,
                                                                       @Parameter(description = "Allocation date in ISO-8601 format (yyyy-MM-dd)", example = "2026-10-21")
                                                                       @PathVariable String date,
                                                                       @RequestBody ScheduleAllocationRequest request,
                                                                       Authentication auth) {
        return ResponseEntity.ok(service.upsertAllocation(projectId, memberUserId, date, request, resolveUserId(auth)));
    }

    @DeleteMapping("/members/{memberUserId}/allocations/{date}")
    @Operation(summary = "Delete a daily allocation", description = "Removes a member allocation for a specific project date.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Allocation deleted"),
            @ApiResponse(responseCode = "400", description = "Invalid date"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to manage the schedule"),
            @ApiResponse(responseCode = "404", description = "Project, member or allocation not found")
    })
    public ResponseEntity<Void> deleteAllocation(@PathVariable Long projectId,
                                                 @PathVariable Long memberUserId,
                                                 @Parameter(description = "Allocation date in ISO-8601 format (yyyy-MM-dd)", example = "2026-10-21")
                                                 @PathVariable String date,
                                                 Authentication auth) {
        service.deleteAllocation(projectId, memberUserId, date, resolveUserId(auth));
        return ResponseEntity.noContent().build();
    }
}
