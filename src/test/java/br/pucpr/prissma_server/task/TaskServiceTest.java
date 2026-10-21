package br.pucpr.prissma_server.task;

import br.pucpr.prissma_server.projects.ConstructionProject;
import br.pucpr.prissma_server.projects.ConstructionProjectRepository;
import br.pucpr.prissma_server.projects.ProjectPermission;
import br.pucpr.prissma_server.projects.ProjectPermissionService;
import br.pucpr.prissma_server.notifications.NotificationService;
import br.pucpr.prissma_server.notifications.NotificationType;
import br.pucpr.prissma_server.stage.Stage;
import br.pucpr.prissma_server.stage.StageRepository;
import br.pucpr.prissma_server.users.Role;
import br.pucpr.prissma_server.users.User;
import br.pucpr.prissma_server.users.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskService Tests")
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private UserRepository userRepository;

    @Mock private StageRepository stageRepository;
    @Mock private ConstructionProjectRepository projectRepository;
    @Mock private ProjectPermissionService permissionService;
    @Mock private NotificationService notificationService;

    @InjectMocks
    private TaskService service;

    private User user;
    private Task task;

    private void setId(Object entity, Long id) {
        try {
            java.lang.reflect.Field idField = entity.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(10L);
        user.setName("João Silva");
        user.setEmail("joao@example.com");
        user.setRole(Role.ENG);

        ConstructionProject project = new ConstructionProject();
        project.setId(1L);
        project.setTitle("Obra de Teste");

        Stage stage = new Stage();
        stage.setId(2L);
        stage.setName("Fundação");
        stage.setConstructionProject(project);

        task = new Task();
        setId(task, 100L);
        task.setStage(stage);
        task.setAssigneeUser(user);
        task.setTitle("Escavação");
        task.setDescription("Executar escavação da fundação");
        task.setPriority("HIGH");
        task.setStatus("TODO");
        task.setPlannedStartDate(LocalDate.of(2026, 6, 1));
        task.setPlannedEndDate(LocalDate.of(2026, 6, 5));
        task.setCreatedAt(Instant.parse("2026-06-01T10:00:00Z"));
        task.setUpdatedAt(Instant.parse("2026-06-01T10:00:00Z"));
    }

    @Test
    @DisplayName("Should list tasks assigned to a user")
    void listAssignedToUser_succeeds() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        when(taskRepository.findByAssigneeUserIdOrderByCreatedAtAscIdAsc(10L)).thenReturn(List.of(task));

        List<TaskResponse> responses = service.listAssignedToUser(10L);

        assertEquals(1, responses.size());
        assertEquals(100L, responses.getFirst().getId());
        assertEquals(2L, responses.getFirst().getStageId());
        assertEquals(1L, responses.getFirst().getProjectId());
        assertEquals("Fundação", responses.getFirst().getStageName());
        assertEquals(10L, responses.getFirst().getAssigneeUserId());
        assertEquals("João Silva", responses.getFirst().getAssigneeName());
        assertEquals("ENG", responses.getFirst().getAssigneeRole());
    }

    @Test
    @DisplayName("Should reject task listing when user does not exist")
    void listAssignedToUser_userNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.listAssignedToUser(99L));

        assertEquals("User not found", exception.getReason());
    }

    @Test
    void create_normalizesFieldsAndNotifiesNewEngineer() {
        User assignee = new User();
        assignee.setId(20L);
        assignee.setRole(Role.ENG);
        TaskRequest request = new TaskRequest("  Escavar  ", "desc", " high ", " done ",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2), 20L);
        when(stageRepository.findById(2L)).thenReturn(Optional.of(task.getStage()));
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        when(userRepository.findById(20L)).thenReturn(Optional.of(assignee));
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> {
            Task saved = invocation.getArgument(0);
            setId(saved, 101L);
            return saved;
        });

        TaskResponse response = service.create(2L, request, 10L);

        assertEquals("Escavar", response.getTitle());
        assertEquals("HIGH", response.getPriority());
        assertEquals("DONE", response.getStatus());
        verify(permissionService).requirePermission(1L, 10L, ProjectPermission.MANAGE_TASKS);
        verify(notificationService).notifyUser(eq(20L), eq(NotificationType.TASK_ASSIGNED), anyString(), anyString());
    }

    @Test
    void create_rejectsInvalidRequestAndDates() {
        when(stageRepository.findById(2L)).thenReturn(Optional.of(task.getStage()));
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));

        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> service.create(2L, null, 10L));
        assertEquals("Task request is required", missing.getReason());

        TaskRequest wrongDates = new TaskRequest("task", null, null, null,
                LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 1), null);
        ResponseStatusException dates = assertThrows(ResponseStatusException.class,
                () -> service.create(2L, wrongDates, 10L));
        assertEquals("Planned start date cannot be after planned end date", dates.getReason());
    }

    @Test
    void update_changesAssigneeAndClearsCompletionWhenReopened() {
        User assignee = new User();
        assignee.setId(20L);
        assignee.setRole(Role.ENG);
        task.setStatus("DONE");
        task.setCompletedAt(Instant.now());
        TaskRequest patch = new TaskRequest(null, "nova descrição", "low", "todo", null, null, 20L);
        when(stageRepository.findById(2L)).thenReturn(Optional.of(task.getStage()));
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        when(userRepository.findById(20L)).thenReturn(Optional.of(assignee));
        when(taskRepository.findByIdAndStageId(100L, 2L)).thenReturn(Optional.of(task));
        when(taskRepository.save(task)).thenReturn(task);

        TaskResponse response = service.update(2L, 100L, patch, 10L);

        assertEquals("LOW", response.getPriority());
        assertEquals("TODO", response.getStatus());
        assertEquals(null, task.getCompletedAt());
        verify(notificationService).notifyUser(eq(20L), eq(NotificationType.TASK_ASSIGNED), anyString(), anyString());
    }

    @Test
    void getListDeleteAndSummaryRespectProjectScope() {
        Stage stage = task.getStage();
        stage.setStatus("DONE");
        stage.setDisplayOrder(1);
        when(stageRepository.findById(2L)).thenReturn(Optional.of(stage));
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        when(taskRepository.findByStageIdOrderByCreatedAtAscIdAsc(2L)).thenReturn(List.of(task));
        when(taskRepository.findByIdAndStageId(100L, 2L)).thenReturn(Optional.of(task));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(stage.getConstructionProject()));
        when(stageRepository.findByConstructionProjectIdOrderByDisplayOrder(1L)).thenReturn(List.of(stage));
        when(taskRepository.findByStageConstructionProjectIdOrderByCreatedAtAscIdAsc(1L)).thenReturn(List.of(task));

        assertEquals(1, service.listByStage(2L, 10L).size());
        assertEquals(100L, service.get(2L, 100L, 10L).getId());
        service.delete(2L, 100L, 10L);
        assertEquals(1, service.summarizeProject(1L, 10L).getTotalEtapas());
        verify(taskRepository).delete(task);
        verify(permissionService, atLeastOnce()).requirePermission(1L, 10L, ProjectPermission.VIEW_PROJECT);
    }

    @Test
    void requestAccessorsPreserveValues() {
        TaskRequest request = new TaskRequest();
        request.setTitle("T");
        request.setDescription("D");
        request.setPriority("HIGH");
        request.setStatus("TODO");
        request.setPlannedStartDate(LocalDate.of(2026, 1, 1));
        request.setPlannedEndDate(LocalDate.of(2026, 1, 2));
        request.setAssigneeUserId(9L);
        request.setAssigneeName("Maria");

        assertEquals("T", request.getTitle());
        assertEquals("D", request.getDescription());
        assertEquals("HIGH", request.getPriority());
        assertEquals("TODO", request.getStatus());
        assertEquals(9L, request.getAssigneeUserId());
        assertEquals("Maria", request.getAssigneeName());
        assertEquals(LocalDate.of(2026, 1, 2), request.getPlannedEndDate());
    }
}

