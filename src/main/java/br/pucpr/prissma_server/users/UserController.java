package br.pucpr.prissma_server.users;

import br.pucpr.prissma_server.notifications.NotificationResponse;
import br.pucpr.prissma_server.notifications.NotificationService;
import br.pucpr.prissma_server.task.TaskResponse;
import br.pucpr.prissma_server.task.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
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
    public ResponseEntity<UserResponse> createUser(@RequestBody UserRequest request) {
        User user = service.createUser(request.toUser());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @GetMapping
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
    public ResponseEntity<UserResponse> getMe(Authentication auth) {
        Long userId = resolveUserId(auth);
        List<NotificationResponse> notifications = notificationService.listForUser(userId);
        return ResponseEntity.ok(UserResponse.from(service.getUserById(userId), notifications));
    }

    @GetMapping("/me/tasks")
    public ResponseEntity<List<TaskResponse>> getMyTasks(Authentication auth) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(taskService.listAssignedToUser(userId));
    }

    /** Marcar como lida so vale para a propria notificacao; a de outro da 404. */
    @PatchMapping("/me/notifications/{notificationId}/read")
    public ResponseEntity<NotificationResponse> markNotificationAsRead(@PathVariable Long notificationId,
                                                                       Authentication auth) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(notificationService.markAsRead(notificationId, userId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(UserResponse.from(service.getUserById(id)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<UserResponse> updateUser(@PathVariable Long id,
                                                   @RequestBody UserRequest request,
                                                   Authentication auth) {
        validator.validateOwnership(id, auth);
        User user = service.updateUser(id, request);
        return ResponseEntity.ok(UserResponse.from(user));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id, Authentication auth) {
        validator.validateOwnership(id, auth);
        service.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}
