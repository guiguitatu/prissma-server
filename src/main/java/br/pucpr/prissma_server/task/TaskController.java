package br.pucpr.prissma_server.task;

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
@RequestMapping("/stages/{stageId}/tasks")
@Tag(name = "Tasks", description = "Task management inside construction project stages.")
@SecurityRequirement(name = "bearerAuth")
public class TaskController {

    private final TaskService service;

    public TaskController(TaskService service) {
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

    @PostMapping
    @Operation(summary = "Create a task", description = "Creates a task inside the selected stage.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Task created"),
            @ApiResponse(responseCode = "400", description = "Invalid task data"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to modify the project"),
            @ApiResponse(responseCode = "404", description = "Stage not found")
    })
    public ResponseEntity<TaskResponse> create(@PathVariable Long stageId,
                                               @Valid @RequestBody TaskRequest request,
                                               Authentication auth) {
        Long userId = resolveUserId(auth);
        TaskResponse response = service.create(stageId, request, userId);
        return ResponseEntity.created(URI.create("/stages/" + stageId + "/tasks/" + response.getId()))
                .body(response);
    }

    @GetMapping
    @Operation(summary = "List stage tasks", description = "Returns all tasks for a stage.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tasks returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the project"),
            @ApiResponse(responseCode = "404", description = "Stage not found")
    })
    public ResponseEntity<List<TaskResponse>> list(@PathVariable Long stageId,
                                                   Authentication auth) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(service.listByStage(stageId, userId));
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "Get a task", description = "Returns a task from the selected stage.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the project"),
            @ApiResponse(responseCode = "404", description = "Stage or task not found")
    })
    public ResponseEntity<TaskResponse> get(@PathVariable Long stageId,
                                            @PathVariable Long taskId,
                                            Authentication auth) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(service.get(stageId, taskId, userId));
    }

    @PatchMapping("/{taskId}")
    @Operation(summary = "Update a task", description = "Partially updates a task inside the selected stage.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task updated"),
            @ApiResponse(responseCode = "400", description = "Invalid task data"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to modify the project"),
            @ApiResponse(responseCode = "404", description = "Stage or task not found")
    })
    public ResponseEntity<TaskResponse> update(@PathVariable Long stageId,
                                               @PathVariable Long taskId,
                                               @Valid @RequestBody TaskRequest request,
                                               Authentication auth) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(service.update(stageId, taskId, request, userId));
    }

    @DeleteMapping("/{taskId}")
    @Operation(summary = "Delete a task", description = "Deletes a task from the selected stage.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Task deleted"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to modify the project"),
            @ApiResponse(responseCode = "404", description = "Stage or task not found")
    })
    public ResponseEntity<Void> delete(@PathVariable Long stageId,
                                       @PathVariable Long taskId,
                                       Authentication auth) {
        Long userId = resolveUserId(auth);
        service.delete(stageId, taskId, userId);
        return ResponseEntity.noContent().build();
    }
}
