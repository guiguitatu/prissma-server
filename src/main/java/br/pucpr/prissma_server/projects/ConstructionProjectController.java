package br.pucpr.prissma_server.projects;

import br.pucpr.prissma_server.users.Role;
import br.pucpr.prissma_server.users.User;
import br.pucpr.prissma_server.users.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/projects")
@Tag(name = "Projects", description = "Construction projects, project members, permissions and progress overview.")
@SecurityRequirement(name = "bearerAuth")
public class ConstructionProjectController {

    private final ConstructionProjectService service;
    private final br.pucpr.prissma_server.task.TaskService taskService;
    private final UserRepository userRepository;

    public ConstructionProjectController(ConstructionProjectService service,
                                         br.pucpr.prissma_server.task.TaskService taskService,
                                         UserRepository userRepository) {
        this.service = service;
        this.taskService = taskService;
        this.userRepository = userRepository;
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
    @Operation(summary = "Create a construction project", description = "Creates a project owned by the authenticated user in the active workspace context.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Project created"),
            @ApiResponse(responseCode = "400", description = "Invalid project data"),
            @ApiResponse(responseCode = "401", description = "Authentication required")
    })
    public ResponseEntity<ConstructionProjectResponse> createProject(@RequestBody ConstructionProjectRequest request,
                                                                     Authentication auth) {
        Long userId = resolveUserId(auth);
        ConstructionProject project = service.createProject(request.toEntity(), userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ConstructionProjectResponse.from(project));
    }

    @GetMapping
    @Operation(summary = "List accessible projects",
            description = "Returns projects visible to the authenticated user. Platform administrators receive the global support view; workspace users are scoped by the active workspace and membership.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Projects returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "Authenticated user not found")
    })
    public ResponseEntity<List<ConstructionProjectResponse>> getAll(Authentication auth) {
        Long userId = resolveUserId(auth);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        List<ConstructionProject> projects;
        if (user.getRole() == Role.ADMIN) {
            // Staff da plataforma (D7): visão global de suporte, documentada.
            projects = service.getAll();
        } else {
            var ctx = br.pucpr.prissma_server.workspaces.WorkspaceContext.current();
            if (ctx == null) {
                projects = List.of();
            } else if (ctx.isElevated()) {
                projects = service.getAllForWorkspace(ctx.workspaceId());
            } else {
                projects = service.getAllForUser(userId, ctx.workspaceId());
            }
        }

        List<ConstructionProjectResponse> list = projects.stream().map(ConstructionProjectResponse::from).toList();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a construction project", description = "Returns one project when the authenticated user has access to it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Project returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the project"),
            @ApiResponse(responseCode = "404", description = "Project not found")
    })
    public ResponseEntity<ConstructionProjectResponse> getById(@PathVariable Long id, Authentication auth) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(ConstructionProjectResponse.from(service.getById(id, userId)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a construction project", description = "Partially updates a project when the authenticated user has the required project permission.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Project updated"),
            @ApiResponse(responseCode = "400", description = "Invalid project data"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to modify the project"),
            @ApiResponse(responseCode = "404", description = "Project not found")
    })
    public ResponseEntity<ConstructionProjectResponse> updateProject(@PathVariable Long id,
                                                                     @RequestBody ConstructionProjectRequest request,
                                                                     Authentication auth) {
        Long userId = resolveUserId(auth);
        ConstructionProject updated = service.updateProject(id, request, userId);
        return ResponseEntity.ok(ConstructionProjectResponse.from(updated));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a construction project", description = "Deletes a project when the authenticated user has permission to do so.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Project deleted"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to delete the project"),
            @ApiResponse(responseCode = "404", description = "Project not found")
    })
    public ResponseEntity<Void> deleteProject(@PathVariable Long id, Authentication auth) {
        Long userId = resolveUserId(auth);
        service.deleteProject(id, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{projectId}/members")
    @Operation(summary = "Add a project member", description = "Adds a user to the project with the requested project role.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Project member added"),
            @ApiResponse(responseCode = "400", description = "Invalid member data or duplicate membership"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to manage project members"),
            @ApiResponse(responseCode = "404", description = "Project or user not found")
    })
    public ResponseEntity<ConstructionProjectMemberResponse> addMember(@PathVariable Long projectId,
                                                                       @RequestBody AddProjectMemberRequest request,
                                                                       Authentication auth) {
        Long userId = resolveUserId(auth);
        ConstructionProjectMember member = service.addMember(projectId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ConstructionProjectMemberResponse.from(member));
    }

    @GetMapping("/{projectId}/members")
    @Operation(summary = "List project members", description = "Returns the members assigned to a construction project.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Project members returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access project members"),
            @ApiResponse(responseCode = "404", description = "Project not found")
    })
    public ResponseEntity<List<ConstructionProjectMemberResponse>> getMembers(@PathVariable Long projectId,
                                                                              Authentication auth) {
        Long userId = resolveUserId(auth);
        List<ConstructionProjectMemberResponse> members = service.getMembers(projectId, userId);
        return ResponseEntity.ok(members);
    }

    @PatchMapping("/{projectId}/members/{memberId}/role")
    @Operation(summary = "Update a project member role", description = "Changes the role assigned to an existing project member.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Project member role updated"),
            @ApiResponse(responseCode = "400", description = "Invalid project role"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to manage project members"),
            @ApiResponse(responseCode = "404", description = "Project or member not found")
    })
    public ResponseEntity<ConstructionProjectMemberResponse> updateMemberRole(@PathVariable Long projectId,
                                                                              @PathVariable Long memberId,
                                                                              @RequestBody UpdateMemberRoleRequest request,
                                                                              Authentication auth) {
        Long userId = resolveUserId(auth);
        ConstructionProjectMember member = service.updateMemberRole(projectId, userId, memberId,
                request == null ? null : request.roleInProject());
        return ResponseEntity.ok(ConstructionProjectMemberResponse.from(member));
    }

    @DeleteMapping("/{projectId}/members/{memberId}")
    @Operation(summary = "Remove a project member", description = "Removes an existing member from a construction project.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Project member removed"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to manage project members"),
            @ApiResponse(responseCode = "404", description = "Project or member not found")
    })
    public ResponseEntity<Void> removeMember(@PathVariable Long projectId,
                                             @PathVariable Long memberId,
                                             Authentication auth) {
        Long userId = resolveUserId(auth);
        service.removeMember(projectId, userId, memberId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{projectId}/roles/{role}/permissions")
    @Operation(summary = "Get permissions for a project role", description = "Returns the configured project permissions for the requested role.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role permissions returned"),
            @ApiResponse(responseCode = "400", description = "Invalid role"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access project permissions"),
            @ApiResponse(responseCode = "404", description = "Project not found")
    })
    public ResponseEntity<RolePermissionsResponse> getRolePermissions(@PathVariable Long projectId,
                                                                      @Parameter(description = "Project role identifier", example = "ENGINEER")
                                                                      @PathVariable String role,
                                                                      Authentication auth) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(service.getRolePermissions(projectId, userId, role));
    }

    @PutMapping("/{projectId}/roles/{role}/permissions")
    @Operation(summary = "Update permissions for a project role", description = "Replaces the permission set configured for a project role.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role permissions updated"),
            @ApiResponse(responseCode = "400", description = "Invalid role or permission list"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to manage project permissions"),
            @ApiResponse(responseCode = "404", description = "Project not found")
    })
    public ResponseEntity<RolePermissionsResponse> updateRolePermissions(@PathVariable Long projectId,
                                                                         @Parameter(description = "Project role identifier", example = "ENGINEER")
                                                                         @PathVariable String role,
                                                                         @RequestBody UpdateRolePermissionsRequest request,
                                                                         Authentication auth) {
        Long userId = resolveUserId(auth);
        List<String> permissions = request == null ? null : request.permissions();
        return ResponseEntity.ok(service.updateRolePermissions(projectId, userId, role, permissions));
    }

    @GetMapping("/{id}/acompanhamento")
    @Operation(summary = "Get project progress overview", description = "Returns the project progress summary derived from its stages and tasks.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Project progress returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the project"),
            @ApiResponse(responseCode = "404", description = "Project not found")
    })
    public ResponseEntity<AcompanhamentoResponse> getAcompanhamento(@PathVariable Long id,
                                                                    Authentication auth) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(taskService.summarizeProject(id, userId));
    }
}
