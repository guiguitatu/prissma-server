package br.pucpr.prissma_server.workspaces;

import br.pucpr.prissma_server.auth.AuthService;
import br.pucpr.prissma_server.auth.LoginResponse;
import br.pucpr.prissma_server.users.User;
import br.pucpr.prissma_server.users.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/workspaces")
@Tag(name = "Workspaces", description = "Workspace accounts, members, invitations and active workspace switching.")
public class WorkspaceController {

    private final WorkspaceService service;
    private final WorkspaceMemberService memberService;
    private final AuthService authService;
    private final UserRepository userRepository;

    public WorkspaceController(WorkspaceService service,
                               WorkspaceMemberService memberService,
                               AuthService authService,
                               UserRepository userRepository) {
        this.service = service;
        this.memberService = memberService;
        this.authService = authService;
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

    @GetMapping
    @Operation(summary = "List my workspaces",
            description = "Returns all workspaces available to the authenticated user.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Workspaces returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required")
    })
    public ResponseEntity<List<WorkspaceResponse>> list(Authentication auth) {
        Long userId = resolveUserId(auth);
        List<WorkspaceResponse> workspaces = service.listWorkspacesForUser(userId).stream()
                .map(w -> WorkspaceResponse.from(w, userId))
                .toList();
        return ResponseEntity.ok(workspaces);
    }

    @PostMapping
    @Operation(summary = "Create a workspace",
            description = "Creates a workspace owned by the authenticated user.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Workspace created"),
            @ApiResponse(responseCode = "400", description = "Invalid workspace data"),
            @ApiResponse(responseCode = "401", description = "Authentication required")
    })
    public ResponseEntity<WorkspaceResponse> create(@RequestBody CreateWorkspaceRequest request,
                                                    Authentication auth) {
        Long userId = resolveUserId(auth);
        Workspace workspace = service.createWorkspace(userId, request == null ? null : request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(WorkspaceResponse.from(workspace, userId));
    }

    /**
     * Troca de conta: valida o acesso e reemite o token com os claims do
     * workspace alvo. Sem vínculo -> 404 genérico (anti-enumeração).
     *
     * Limitação conhecida (MVP): o token antigo não é revogado — a API é
     * stateless, sem jti/sessões; ele vale até expirar (24h).
     */
    @PostMapping("/{id}/switch")
    @Operation(summary = "Switch active workspace",
            description = "Validates access to the requested workspace and issues a new JWT containing that workspace context.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Workspace switched and new token returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "Workspace or user not found")
    })
    public ResponseEntity<LoginResponse> switchWorkspace(@PathVariable Long id, Authentication auth) {
        Long userId = resolveUserId(auth);
        WorkspaceContext ctx = service.resolveContext(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return ResponseEntity.ok(new LoginResponse(authService.issueToken(user, ctx)));
    }

    // ---------- membros do workspace ativo ----------

    @GetMapping("/members")
    @Operation(summary = "List active workspace members",
            description = "Returns all members of the workspace represented by the current JWT context.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Workspace members returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "Active workspace not found")
    })
    public ResponseEntity<List<WorkspaceMemberDtos.WorkspaceMemberResponse>> listMembers() {
        return ResponseEntity.ok(memberService.listMembers(WorkspaceContext.current()));
    }

    @PostMapping("/members/invite")
    @Operation(summary = "Invite a workspace member",
            description = "Creates an invitation for a user to join the active workspace.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Invitation created"),
            @ApiResponse(responseCode = "400", description = "Invalid invitation data"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to invite workspace members"),
            @ApiResponse(responseCode = "404", description = "Workspace or target user not found")
    })
    public ResponseEntity<WorkspaceMemberDtos.MemberInviteResponse> invite(
            @RequestBody WorkspaceMemberDtos.InviteMemberRequest request,
            Authentication auth) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(memberService.invite(WorkspaceContext.current(), userId, request));
    }

    /** PÚBLICO (permitAll no SecurityConfig): o convidado ainda não tem conta. */
    @PatchMapping("/invites/{token}/accept")
    @Operation(summary = "Accept a workspace invitation",
            description = "Public endpoint used to accept a workspace invitation token. Authentication is not required.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invitation accepted"),
            @ApiResponse(responseCode = "400", description = "Invalid, expired or already-used invitation"),
            @ApiResponse(responseCode = "404", description = "Invitation not found")
    })
    public ResponseEntity<Void> acceptInvite(@PathVariable String token,
                                             @RequestBody(required = false) WorkspaceMemberDtos.AcceptInviteRequest request) {
        memberService.acceptInvite(token, request);
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/members/{id}")
    @Operation(summary = "Update a workspace member role",
            description = "Changes the role of a member in the active workspace.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Workspace member updated"),
            @ApiResponse(responseCode = "400", description = "Invalid workspace role"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to manage workspace members"),
            @ApiResponse(responseCode = "404", description = "Workspace member not found")
    })
    public ResponseEntity<WorkspaceMemberDtos.WorkspaceMemberResponse> updateMemberRole(
            @PathVariable Long id,
            @RequestBody WorkspaceMemberDtos.UpdateWorkspaceMemberRequest request,
            Authentication auth) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(memberService.updateMemberRole(WorkspaceContext.current(), userId, id,
                request == null ? null : request.role()));
    }

    @PatchMapping("/members/{id}/deactivate")
    @Operation(summary = "Deactivate a workspace member",
            description = "Deactivates a member in the active workspace without deleting the membership record.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Workspace member deactivated"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to manage workspace members"),
            @ApiResponse(responseCode = "404", description = "Workspace member not found")
    })
    public ResponseEntity<WorkspaceMemberDtos.WorkspaceMemberResponse> deactivateMember(
            @PathVariable Long id, Authentication auth) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(memberService.deactivateMember(WorkspaceContext.current(), userId, id));
    }

    @DeleteMapping("/members/{id}")
    @Operation(summary = "Remove a workspace member",
            description = "Removes a member from the active workspace.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Workspace member removed"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to manage workspace members"),
            @ApiResponse(responseCode = "404", description = "Workspace member not found")
    })
    public ResponseEntity<Void> removeMember(@PathVariable Long id, Authentication auth) {
        Long userId = resolveUserId(auth);
        memberService.removeMember(WorkspaceContext.current(), userId, id);
        return ResponseEntity.noContent().build();
    }
}
