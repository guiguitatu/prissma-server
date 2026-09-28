package br.pucpr.prissma_server.schedule;

import br.pucpr.prissma_server.projects.ConstructionProject;
import br.pucpr.prissma_server.projects.ConstructionProjectMember;
import br.pucpr.prissma_server.projects.ConstructionProjectMemberRepository;
import br.pucpr.prissma_server.projects.ConstructionProjectRepository;
import br.pucpr.prissma_server.projects.ProjectPermission;
import br.pucpr.prissma_server.projects.ProjectPermissionService;
import br.pucpr.prissma_server.task.Task;
import br.pucpr.prissma_server.task.TaskRepository;
import br.pucpr.prissma_server.users.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ScheduleService {

    static final int MAX_RESPONSIBILITY_LENGTH = 100;
    static final BigDecimal MAX_HOURS_PER_DAY = BigDecimal.valueOf(24);

    private final ScheduleMemberRepository scheduleMemberRepository;
    private final ScheduleAllocationRepository allocationRepository;
    private final TaskRepository taskRepository;
    private final ConstructionProjectRepository projectRepository;
    private final ConstructionProjectMemberRepository memberRepository;
    private final ProjectPermissionService permissionService;

    public ScheduleService(ScheduleMemberRepository scheduleMemberRepository,
                           ScheduleAllocationRepository allocationRepository,
                           TaskRepository taskRepository,
                           ConstructionProjectRepository projectRepository,
                           ConstructionProjectMemberRepository memberRepository,
                           ProjectPermissionService permissionService) {
        this.scheduleMemberRepository = scheduleMemberRepository;
        this.allocationRepository = allocationRepository;
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
        this.memberRepository = memberRepository;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public TeamScheduleResponse getSchedule(Long projectId, String view, String date, Long userId) {
        requireProject(projectId);
        permissionService.requirePermission(projectId, userId, ProjectPermission.VIEW_PROJECT);

        ScheduleView scheduleView = ScheduleView.fromString(view);
        LocalDate reference = date == null || date.isBlank() ? LocalDate.now() : parseDate(date);
        LocalDate startDate = scheduleView.startOf(reference);
        LocalDate endDate = scheduleView.endOf(reference);
        List<LocalDate> days = startDate.datesUntil(endDate.plusDays(1)).toList();

        List<ConstructionProjectMember> members = memberRepository
                .findAllByConstructionProjectIdOrderByJoinedAtAscIdAsc(projectId).stream()
                .filter(member -> "ACTIVE".equals(member.getMembershipStatus()))
                .toList();

        Map<Long, String> responsibilities = new HashMap<>();
        for (ScheduleMember scheduleMember : scheduleMemberRepository.findAllByConstructionProjectId(projectId)) {
            responsibilities.put(scheduleMember.getUser().getId(), scheduleMember.getUserResponsibility());
        }

        Map<Long, Map<LocalDate, BigDecimal>> hoursByUser = new HashMap<>();
        for (ScheduleAllocation allocation : allocationRepository.findByProjectInPeriod(projectId, startDate, endDate)) {
            hoursByUser.computeIfAbsent(allocation.getUser().getId(), id -> new HashMap<>())
                    .put(allocation.getAllocationDate(), allocation.getHours());
        }

        Map<Long, List<Task>> tasksByUser = taskRepository
                .findAssignedInProjectDuringPeriod(projectId, startDate, endDate).stream()
                .collect(Collectors.groupingBy(task -> task.getAssigneeUser().getId()));

        List<MemberScheduleResponse> memberSchedules = new ArrayList<>();
        for (ConstructionProjectMember member : members) {
            User user = member.getUser();
            Map<LocalDate, BigDecimal> hoursByDate = hoursByUser.getOrDefault(user.getId(), Map.of());
            List<Task> userTasks = tasksByUser.getOrDefault(user.getId(), List.of());

            BigDecimal total = BigDecimal.ZERO;
            boolean hasOverlap = false;
            List<DayScheduleResponse> daySchedules = new ArrayList<>(days.size());
            for (LocalDate day : days) {
                BigDecimal hours = hoursByDate.getOrDefault(day, BigDecimal.ZERO);
                total = total.add(hours);
                List<ScheduledTaskResponse> dayTasks = userTasks.stream()
                        .filter(task -> coversDate(task, day))
                        .map(ScheduledTaskResponse::from)
                        .toList();
                // Sobreposição: mais de uma tarefa do mesmo integrante no mesmo dia.
                boolean overlapped = dayTasks.size() > 1;
                hasOverlap = hasOverlap || overlapped;
                daySchedules.add(new DayScheduleResponse(day, hours, hours.signum() > 0, overlapped, dayTasks));
            }

            memberSchedules.add(new MemberScheduleResponse(
                    user.getId(),
                    user.getName(),
                    member.getRoleInProject(),
                    responsibilities.get(user.getId()),
                    total,
                    hasOverlap,
                    daySchedules
            ));
        }

        return new TeamScheduleResponse(projectId, scheduleView, startDate, endDate,
                scheduleView.previousOf(startDate), scheduleView.nextOf(startDate), days, memberSchedules);
    }

    @Transactional
    public ScheduleMemberResponse updateResponsibility(Long projectId, Long memberUserId,
                                                      ScheduleResponsibilityRequest request, Long userId) {
        ConstructionProject project = requireProject(projectId);
        permissionService.requirePermission(projectId, userId, ProjectPermission.MANAGE_TEAMS);
        User memberUser = requireActiveMemberUser(projectId, memberUserId);

        ScheduleMember scheduleMember = scheduleMemberRepository
                .findByConstructionProjectIdAndUserId(projectId, memberUserId)
                .orElseGet(() -> {
                    ScheduleMember created = new ScheduleMember();
                    created.setConstructionProject(project);
                    created.setUser(memberUser);
                    created.setCreatedAt(Instant.now());
                    return created;
                });

        scheduleMember.setUserResponsibility(normalizeResponsibility(request.getUserResponsibility()));
        scheduleMember.setUpdatedAt(Instant.now());

        return ScheduleMemberResponse.from(scheduleMemberRepository.save(scheduleMember));
    }

    @Transactional
    public ScheduleAllocationResponse upsertAllocation(Long projectId, Long memberUserId, String date,
                                                       ScheduleAllocationRequest request, Long userId) {
        ConstructionProject project = requireProject(projectId);
        permissionService.requirePermission(projectId, userId, ProjectPermission.MANAGE_TEAMS);
        LocalDate allocationDate = parseDate(date);
        BigDecimal hours = requireHours(request.getAllocatedHours());
        User memberUser = requireActiveMemberUser(projectId, memberUserId);

        ScheduleAllocation allocation = allocationRepository
                .findByConstructionProjectIdAndUserIdAndAllocationDate(projectId, memberUserId, allocationDate)
                .orElseGet(() -> {
                    ScheduleAllocation created = new ScheduleAllocation();
                    created.setConstructionProject(project);
                    created.setUser(memberUser);
                    created.setAllocationDate(allocationDate);
                    created.setCreatedAt(Instant.now());
                    return created;
                });

        allocation.setHours(hours);
        allocation.setUpdatedAt(Instant.now());

        return ScheduleAllocationResponse.from(allocationRepository.save(allocation));
    }

    @Transactional
    public void deleteAllocation(Long projectId, Long memberUserId, String date, Long userId) {
        requireProject(projectId);
        permissionService.requirePermission(projectId, userId, ProjectPermission.MANAGE_TEAMS);
        LocalDate allocationDate = parseDate(date);

        ScheduleAllocation allocation = allocationRepository
                .findByConstructionProjectIdAndUserIdAndAllocationDate(projectId, memberUserId, allocationDate)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Allocation not found"));
        allocationRepository.delete(allocation);
    }

    private static boolean coversDate(Task task, LocalDate date) {
        LocalDate start = task.getPlannedStartDate() != null ? task.getPlannedStartDate() : task.getPlannedEndDate();
        LocalDate end = task.getPlannedEndDate() != null ? task.getPlannedEndDate() : task.getPlannedStartDate();
        return start != null && !date.isBefore(start) && !date.isAfter(end);
    }

    private ConstructionProject requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    }

    private User requireActiveMemberUser(Long projectId, Long memberUserId) {
        ConstructionProjectMember member = memberRepository
                .findByConstructionProjectIdAndUserId(projectId, memberUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "User is not a member of this project"));

        if (!"ACTIVE".equals(member.getMembershipStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "User is not an active member of this project");
        }
        return member.getUser();
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date is required");
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date must be in the format yyyy-MM-dd");
        }
    }

    private BigDecimal requireHours(BigDecimal hours) {
        if (hours == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Allocated hours are required");
        }
        if (hours.signum() <= 0 || hours.compareTo(MAX_HOURS_PER_DAY) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Allocated hours must be greater than 0 and at most 24");
        }
        if (hours.stripTrailingZeros().scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Allocated hours must have at most 2 decimal places");
        }
        return hours;
    }

    private String normalizeResponsibility(String responsibility) {
        if (responsibility == null || responsibility.isBlank()) {
            return null;
        }
        String trimmed = responsibility.trim();
        if (trimmed.length() > MAX_RESPONSIBILITY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "User responsibility must be at most " + MAX_RESPONSIBILITY_LENGTH + " characters");
        }
        return trimmed;
    }
}
