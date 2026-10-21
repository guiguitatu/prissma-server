package br.pucpr.prissma_server.stage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping
@Tag(name = "Stages", description = "Construction project stage management and ordering.")
@SecurityRequirement(name = "bearerAuth")
public class StageController {

    private final StageService service;

    public StageController(StageService service) {
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

    @PostMapping("/projects/{projectId}/stages")
    @Operation(summary = "Create a project stage", description = "Creates a new stage inside a construction project.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Stage created"),
            @ApiResponse(responseCode = "400", description = "Invalid stage data"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to modify the project"),
            @ApiResponse(responseCode = "404", description = "Project not found")
    })
    public ResponseEntity<StageResponse> create(
            @PathVariable Long projectId,
            @Valid @RequestBody StageRequest request,
            Authentication auth) {
        Long userId = resolveUserId(auth);
        StageResponse response = service.create(projectId, request, userId);
        return ResponseEntity.created(URI.create("/stages/" + response.getId())).body(response);
    }

    @GetMapping("/projects/{projectId}/stages")
    @Operation(summary = "List project stages", description = "Returns all stages belonging to a project in their current order.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Stages returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the project"),
            @ApiResponse(responseCode = "404", description = "Project not found")
    })
    public ResponseEntity<List<StageResponse>> listByProject(@PathVariable Long projectId,
                                                             Authentication auth) {
        List<StageResponse> stages = service.listByProject(projectId, resolveUserId(auth));
        return ResponseEntity.ok(stages);
    }

    @GetMapping("/stages/{id}")
    @Operation(summary = "Get a stage", description = "Returns a stage by its identifier.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Stage returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the stage project"),
            @ApiResponse(responseCode = "404", description = "Stage not found")
    })
    public ResponseEntity<StageResponse> get(@PathVariable Long id, Authentication auth) {
        StageResponse response = service.get(id, resolveUserId(auth));
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/stages/{id}")
    @Operation(summary = "Update a stage", description = "Partially updates an existing project stage.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Stage updated"),
            @ApiResponse(responseCode = "400", description = "Invalid stage data"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to modify the project"),
            @ApiResponse(responseCode = "404", description = "Stage not found")
    })
    public ResponseEntity<StageResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody StageRequest request,
            Authentication auth) {
        Long userId = resolveUserId(auth);
        StageResponse response = service.update(id, request, userId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/stages/{id}")
    @Operation(summary = "Delete a stage", description = "Deletes a project stage.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Stage deleted"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to modify the project"),
            @ApiResponse(responseCode = "404", description = "Stage not found")
    })
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            Authentication auth) {
        Long userId = resolveUserId(auth);
        service.delete(id, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/projects/{projectId}/stages/reorder")
    @Operation(summary = "Reorder project stages", description = "Applies a new stage order using the supplied list of stage identifiers.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Stages reordered"),
            @ApiResponse(responseCode = "400", description = "Invalid stage order"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to modify the project"),
            @ApiResponse(responseCode = "404", description = "Project or stage not found")
    })
    public ResponseEntity<StageReorderResponse> reorder(
            @PathVariable Long projectId,
            @RequestBody List<Long> stageIds,
            Authentication auth) {
        Long userId = resolveUserId(auth);
        service.reorder(projectId, stageIds, userId);
        List<StageResponse> stages = service.listByProject(projectId, userId);
        return ResponseEntity.ok(new StageReorderResponse("Stages reordered successfully", stages));
    }
}
