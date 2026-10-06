package br.pucpr.prissma_server.users;

import br.pucpr.prissma_server.notifications.NotificationResponse;
import br.pucpr.prissma_server.notifications.NotificationService;
import br.pucpr.prissma_server.task.TaskResponse;
import br.pucpr.prissma_server.task.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
@Tag(name = "Users", description = "Operations for user registration, lookup and account management.")
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        bearerFormat = "JWT",
        scheme = "bearer",
        description = "JWT access token"
)
public class UserController {

    private final UserService service;
    private final UserValidator validator;
    private final TaskService taskService;
    private final NotificationService notificationService;

    public UserController(UserService service,
                          UserValidator validator,
                          TaskService taskService,
                          NotificationService notificationService) {
        this.service = service;
        this.validator = validator;
        this.taskService = taskService;
        this.notificationService = notificationService;
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
    @Operation(summary = "Create a user", description = "Registers a new user.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User created",
                    content = @Content(schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    public ResponseEntity<UserResponse> createUser(@RequestBody UserRequest request) {
        User user = service.createUser(request.toUser());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @GetMapping
    @Operation(summary = "List users", description = "Returns all users.")
    @ApiResponse(responseCode = "200", description = "Users returned")
    public ResponseEntity<List<UserResponse>> getUsers() {
        List<UserResponse> users = service.getUsers().stream().map(UserResponse::from).toList();
        return ResponseEntity.ok(users);
    }

    /**
     * Unico endpoint que devolve notificacoes, e sempre as do proprio
     * autenticado: o id vem do token, nunca do path ou do corpo. Sem parametro
     * de usuario, nao ha o que adulterar.
     */
    @GetMapping("/me")
    @Operation(summary = "Get the authenticated user",
            description = "Returns the authenticated user's profile and notifications.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated user returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required")
    })
    public ResponseEntity<UserResponse> getMe(Authentication auth) {
        Long userId = resolveUserId(auth);
        List<NotificationResponse> notifications = notificationService.listForUser(userId);
        return ResponseEntity.ok(UserResponse.from(service.getUserById(userId), notifications));
    }

    @GetMapping("/me/tasks")
    @Operation(summary = "List my tasks",
            description = "Returns tasks assigned to the authenticated user.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tasks returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required")
    })
    public ResponseEntity<List<TaskResponse>> getMyTasks(Authentication auth) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(taskService.listAssignedToUser(userId));
    }

    /** Marcar como lida so vale para a propria notificacao; a de outro da 404. */
    @PatchMapping("/me/notifications/{notificationId}/read")
    @Operation(summary = "Mark a notification as read",
            description = "Marks one notification belonging to the authenticated user as read.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Notification marked as read"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "Notification not found for the authenticated user")
    })
    public ResponseEntity<NotificationResponse> markNotificationAsRead(
            @Parameter(description = "Notification identifier", example = "1")
            @PathVariable Long notificationId,
            Authentication auth) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(notificationService.markAsRead(notificationId, userId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a user by ID", description = "Returns a user identified by its ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User returned"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserResponse> getUserById(
            @Parameter(description = "User identifier", example = "1")
            @PathVariable Long id) {
        return ResponseEntity.ok(UserResponse.from(service.getUserById(id)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a user",
            description = "Updates a user. The authenticated user must own the target account.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User does not own the target account"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserResponse> updateUser(
            @Parameter(description = "User identifier", example = "1")
            @PathVariable Long id,
            @RequestBody UserRequest request,
            Authentication auth) {
        validator.validateOwnership(id, auth);
        User user = service.updateUser(id, request);
        return ResponseEntity.ok(UserResponse.from(user));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a user",
            description = "Deletes a user. The authenticated user must own the target account.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "User deleted"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User does not own the target account"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<Void> deleteUser(
            @Parameter(description = "User identifier", example = "1")
            @PathVariable Long id,
            Authentication auth) {
        validator.validateOwnership(id, auth);
        service.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}
