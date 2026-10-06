package br.pucpr.prissma_server.task;

import br.pucpr.prissma_server.notifications.NotificationService;
import br.pucpr.prissma_server.notifications.NotificationType;
import br.pucpr.prissma_server.projects.AcompanhamentoResponse;
import br.pucpr.prissma_server.projects.AcompanhamentoStageResponse;
import br.pucpr.prissma_server.projects.ConstructionProject;
import br.pucpr.prissma_server.projects.ConstructionProjectRepository;
import br.pucpr.prissma_server.projects.ProjectPermission;
import br.pucpr.prissma_server.projects.ProjectPermissionService;
import br.pucpr.prissma_server.stage.Stage;
import br.pucpr.prissma_server.stage.StageRepository;
import br.pucpr.prissma_server.users.Role;
import br.pucpr.prissma_server.users.User;
import br.pucpr.prissma_server.users.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TaskService {

    private static final List<String> TASK_STATUSES = List.of("TODO", "IN_PROGRESS", "BLOCKED", "DONE");
    private static final List<String> STAGE_STATUSES = List.of("PLANNED", "IN_PROGRESS", "BLOCKED", "DONE");
    private static final List<String> PRIORITIES = List.of("LOW", "MEDIUM", "HIGH");

    private final TaskRepository taskRepository;
    private final StageRepository stageRepository;
    private final ConstructionProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ProjectPermissionService permissionService;
    private final NotificationService notificationService;

    public TaskService(TaskRepository taskRepository,
                       StageRepository stageRepository,
                       ConstructionProjectRepository projectRepository,
                       UserRepository userRepository,
                       ProjectPermissionService permissionService,
                       NotificationService notificationService) {
        this.taskRepository = taskRepository;
        this.stageRepository = stageRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.notificationService = notificationService;
    }

    @Transactional
    public TaskResponse create(Long stageId, TaskRequest request, Long actorUserId) {
        Stage stage = loadStage(stageId);
        User actor = loadUser(actorUserId);
        requireTaskManagement(stage.getConstructionProject().getId(), actor);

        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Task request is required");
        }
        requireText(request.getTitle(), "Title is required");
        validateDates(request.getPlannedStartDate(), request.getPlannedEndDate());

        Task task = new Task();
        task.setStage(stage);
        task.setTitle(request.getTitle().trim());
        task.setDescription(request.getDescription());
        task.setPriority(normalizeOrDefault(request.getPriority(), "MEDIUM", PRIORITIES, "Priority must be LOW, MEDIUM, or HIGH"));
        task.setStatus(normalizeOrDefault(request.getStatus(), "TODO", TASK_STATUSES, "Status must be TODO, IN_PROGRESS, BLOCKED, or DONE"));
        task.setPlannedStartDate(request.getPlannedStartDate());
        task.setPlannedEndDate(request.getPlannedEndDate());
        applyAssignee(task, request);
        applyStatusSideEffects(task, task.getStatus());

        Instant now = Instant.now();
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        Task saved = taskRepository.save(task);

        // Notifica DEPOIS do save: antes disso a tarefa nao tem id e, se o
        // insert falhar, a notificacao morre junto no rollback.
        notifyAssignee(saved, null, actorUserId);

        return TaskMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> listByStage(Long stageId, Long actorUserId) {
        Stage stage = loadStage(stageId);
        User actor = loadUser(actorUserId);
        requireProjectAccess(stage.getConstructionProject().getId(), actor);

        return taskRepository.findByStageIdOrderByCreatedAtAscIdAsc(stageId).stream()
                .map(TaskMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> listAssignedToUser(Long userId) {
        loadUser(userId);

        return taskRepository.findByAssigneeUserIdOrderByCreatedAtAscIdAsc(userId).stream()
                .map(TaskMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public TaskResponse get(Long stageId, Long taskId, Long actorUserId) {
        Stage stage = loadStage(stageId);
        User actor = loadUser(actorUserId);
        requireProjectAccess(stage.getConstructionProject().getId(), actor);

        Task task = taskRepository.findByIdAndStageId(taskId, stageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
        return TaskMapper.toResponse(task);
    }

    @Transactional
    public TaskResponse update(Long stageId, Long taskId, TaskRequest request, Long actorUserId) {
        Stage stage = loadStage(stageId);
        User actor = loadUser(actorUserId);
        requireTaskManagement(stage.getConstructionProject().getId(), actor);

        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Task request is required");
        }
        validateDates(request.getPlannedStartDate(), request.getPlannedEndDate());

        Task task = taskRepository.findByIdAndStageId(taskId, stageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));

        if (request.getTitle() != null) {
            requireText(request.getTitle(), "Title is required");
            task.setTitle(request.getTitle().trim());
        }
        if (request.getDescription() != null) {
            task.setDescription(request.getDescription());
        }
        if (request.getPriority() != null) {
            task.setPriority(normalizeOrDefault(request.getPriority(), null, PRIORITIES, "Priority must be LOW, MEDIUM, or HIGH"));
        }
        if (request.getStatus() != null) {
            task.setStatus(normalizeOrDefault(request.getStatus(), null, TASK_STATUSES, "Status must be TODO, IN_PROGRESS, BLOCKED, or DONE"));
            applyStatusSideEffects(task, task.getStatus());
        }
        if (request.getPlannedStartDate() != null || request.getPlannedEndDate() != null) {
            if (request.getPlannedStartDate() != null) {
                task.setPlannedStartDate(request.getPlannedStartDate());
            }
            if (request.getPlannedEndDate() != null) {
                task.setPlannedEndDate(request.getPlannedEndDate());
            }
        }

        // Guardado ANTES de applyAssignee: so o que mudou de dono vira aviso.
        // Sem isso, qualquer PATCH de prioridade ou data reenviaria "voce
        // recebeu uma tarefa" para quem ja era o responsavel.
        Long previousAssigneeId = task.getAssigneeUser() == null ? null : task.getAssigneeUser().getId();
        applyAssignee(task, request);

        task.setUpdatedAt(Instant.now());
        Task saved = taskRepository.save(task);

        notifyAssignee(saved, previousAssigneeId, actorUserId);

        return TaskMapper.toResponse(saved);
    }

    @Transactional
    public void delete(Long stageId, Long taskId, Long actorUserId) {
        Stage stage = loadStage(stageId);
        User actor = loadUser(actorUserId);
        requireTaskManagement(stage.getConstructionProject().getId(), actor);

        Task task = taskRepository.findByIdAndStageId(taskId, stageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
        taskRepository.delete(task);
    }

    @Transactional(readOnly = true)
    public AcompanhamentoResponse summarizeProject(Long projectId, Long actorUserId) {
        ConstructionProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        User actor = loadUser(actorUserId);
        requireProjectAccess(projectId, actor);

        List<Stage> stages = stageRepository.findByConstructionProjectIdOrderByDisplayOrder(projectId);
        List<Task> tasks = taskRepository.findByStageConstructionProjectIdOrderByCreatedAtAscIdAsc(projectId);

        Map<Long, List<Task>> tasksByStageId = tasks.stream()
                .collect(Collectors.groupingBy(task -> task.getStage().getId(), LinkedHashMap::new, Collectors.toList()));

        List<AcompanhamentoStageResponse> stageSummaries = new ArrayList<>();
        for (Stage stage : stages) {
            List<Task> stageTasks = tasksByStageId.getOrDefault(stage.getId(), List.of());
            stageSummaries.add(AcompanhamentoStageResponse.from(stage, stageTasks));
        }

        Map<String, Long> stageStatusCounts = countByStatus(stages, STAGE_STATUSES, Stage::getStatus);
        Map<String, Long> taskStatusCounts = countByStatus(tasks, TASK_STATUSES, Task::getStatus);

        long totalTasks = tasks.size();
        long completedTasks = taskStatusCounts.getOrDefault("DONE", 0L);
        long completedStages = stageStatusCounts.getOrDefault("DONE", 0L);

        return new AcompanhamentoResponse(
                project.getId(),
                project.getTitle(),
                project.getStatus(),
                stages.size(),
                (int) completedStages,
                (int) totalTasks,
                (int) completedTasks,
                stageStatusCounts,
                taskStatusCounts,
                stageSummaries
        );
    }

    /**
     * Avisa o responsavel de que a tarefa e dele.
     *
     * Dois casos nao geram nada: tarefa atribuida a um nome avulso (sem
     * usuario) e quem se atribui a propria tarefa -- a pessoa acabou de fazer
     * isso, nao precisa que o sistema conte.
     */
    private void notifyAssignee(Task task, Long previousAssigneeId, Long actorUserId) {
        User assignee = task.getAssigneeUser();
        if (assignee == null) {
            return;
        }
        Long assigneeId = assignee.getId();
        if (Objects.equals(assigneeId, previousAssigneeId) || Objects.equals(assigneeId, actorUserId)) {
            return;
        }

        String projectTitle = task.getStage().getConstructionProject().getTitle();
        notificationService.notifyUser(
                assigneeId,
                NotificationType.TASK_ASSIGNED,
                "Nova tarefa atribuída a você",
                "A tarefa \"" + task.getTitle() + "\" da obra \"" + projectTitle + "\" foi atribuída a você.");
    }

    /** Leitura de tarefa: basta VIEW_PROJECT (todo papel de obra tem). */
    private void requireProjectAccess(Long projectId, User actor) {
        permissionService.requirePermission(projectId, actor.getId(), ProjectPermission.VIEW_PROJECT);
    }

    /**
     * Escrita de tarefa: exige MANAGE_TASKS.
     *
     * Antes bastava ser membro ativo, o que dava a um cliente (papel USER, cuja
     * unica permissao e VIEW_PROJECT) o direito de criar, editar e apagar
     * tarefas da obra que ele apenas acompanha.
     */
    private void requireTaskManagement(Long projectId, User actor) {
        permissionService.requirePermission(projectId, actor.getId(), ProjectPermission.MANAGE_TASKS);
    }

    private Stage loadStage(Long stageId) {
        return stageRepository.findById(stageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stage not found"));
    }

    private User loadUser(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User id is required");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private User loadAssignee(Long userId) {
        User assignee = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignee user not found"));
        if (assignee.getRole() != Role.ENG) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Assignee user must have role ENG");
        }
        return assignee;
    }

    private void applyAssignee(Task task, TaskRequest request) {
        if (request.getAssigneeUserId() != null) {
            task.setAssigneeUser(loadAssignee(request.getAssigneeUserId()));
            task.setAssigneeName(null);
            return;
        }

        if (request.getAssigneeName() != null) {
            requireText(request.getAssigneeName(), "Assignee name is required");
            task.setAssigneeUser(null);
            task.setAssigneeName(request.getAssigneeName().trim());
        }
    }

    private void validateDates(java.time.LocalDate start, java.time.LocalDate end) {
        if (start != null && end != null && start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Planned start date cannot be after planned end date");
        }
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private String normalizeOrDefault(String value,
                                      String defaultValue,
                                      List<String> allowed,
                                      String errorMessage) {
        String normalized = value == null ? defaultValue : value.trim().toUpperCase();
        if (normalized == null) {
            return defaultValue;
        }
        if (normalized.isBlank()) {
            if (defaultValue != null) {
                return defaultValue;
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage);
        }
        if (!allowed.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage);
        }
        return normalized;
    }

    private void applyStatusSideEffects(Task task, String status) {
        if (Objects.equals(status, "DONE")) {
            if (task.getCompletedAt() == null) {
                task.setCompletedAt(Instant.now());
            }
        } else {
            task.setCompletedAt(null);
        }
    }

    private <T> Map<String, Long> countByStatus(List<T> items,
                                                List<String> allowedStatuses,
                                                Function<T, String> extractor) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String status : allowedStatuses) {
            counts.put(status, 0L);
        }
        for (T item : items) {
            String status = extractor.apply(item);
            if (status == null) {
                continue;
            }
            counts.put(status, counts.getOrDefault(status, 0L) + 1L);
        }
        return counts;
    }
}

