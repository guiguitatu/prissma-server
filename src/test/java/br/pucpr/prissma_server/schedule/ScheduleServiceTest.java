package br.pucpr.prissma_server.schedule;

import br.pucpr.prissma_server.projects.ConstructionProject;
import br.pucpr.prissma_server.projects.ConstructionProjectMember;
import br.pucpr.prissma_server.projects.ConstructionProjectMemberRepository;
import br.pucpr.prissma_server.projects.ConstructionProjectRepository;
import br.pucpr.prissma_server.projects.ProjectPermission;
import br.pucpr.prissma_server.projects.ProjectPermissionService;
import br.pucpr.prissma_server.stage.Stage;
import br.pucpr.prissma_server.task.Task;
import br.pucpr.prissma_server.task.TaskRepository;
import br.pucpr.prissma_server.users.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleService Tests")
class ScheduleServiceTest {

    @Mock
    private ScheduleMemberRepository scheduleMemberRepository;

    @Mock
    private ScheduleAllocationRepository allocationRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private ConstructionProjectRepository projectRepository;

    @Mock
    private ConstructionProjectMemberRepository memberRepository;

    @Mock
    private ProjectPermissionService permissionService;

    @InjectMocks
    private ScheduleService service;

    private static final Long PROJECT_ID = 1L;
    private static final Long ACTOR_ID = 7L;
    private static final Long JOAO_ID = 10L;
    private static final Long MARIA_ID = 11L;

    // Quarta-feira: a semana correspondente vai de 10 a 16 de agosto de 2026.
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 8, 12);
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 10);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 8, 16);

    private ConstructionProject project;
    private User joao;
    private User maria;
    private Stage stage;

    @BeforeEach
    void setUp() {
        project = new ConstructionProject();
        project.setId(PROJECT_ID);
        project.setTitle("Residencial Aurora");

        joao = user(JOAO_ID, "João Souza");
        maria = user(MARIA_ID, "Maria Reis");

        stage = new Stage();
        stage.setId(50L);
        stage.setName("Estrutura");
    }

    private static User user(Long id, String name) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        return user;
    }

    private ConstructionProjectMember member(User user, String status) {
        ConstructionProjectMember member = new ConstructionProjectMember();
        member.setConstructionProject(project);
        member.setUser(user);
        member.setRoleInProject("FOREMAN");
        member.setMembershipStatus(status);
        return member;
    }

    private ScheduleAllocation allocation(User user, LocalDate date, String hours) {
        ScheduleAllocation allocation = new ScheduleAllocation();
        allocation.setConstructionProject(project);
        allocation.setUser(user);
        allocation.setAllocationDate(date);
        allocation.setHours(new BigDecimal(hours));
        return allocation;
    }

    private Task task(String title, User assignee, LocalDate start, LocalDate end) {
        Task task = new Task();
        task.setTitle(title);
        task.setStage(stage);
        task.setAssigneeUser(assignee);
        task.setPlannedStartDate(start);
        task.setPlannedEndDate(end);
        return task;
    }

    // ============= GET =============

    @Test
    @DisplayName("Deve montar a semana de segunda a domingo com horas, responsabilidade e tarefas do dia")
    void getWeekScheduleBuildsMemberRows() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(memberRepository.findAllByConstructionProjectIdOrderByJoinedAtAscIdAsc(PROJECT_ID))
                .thenReturn(List.of(member(joao, "ACTIVE"), member(maria, "INACTIVE")));

        ScheduleMember responsibility = new ScheduleMember();
        responsibility.setUser(joao);
        responsibility.setUserResponsibility("Estrutura");
        when(scheduleMemberRepository.findAllByConstructionProjectId(PROJECT_ID))
                .thenReturn(List.of(responsibility));

        when(allocationRepository.findByProjectInPeriod(PROJECT_ID, MONDAY, SUNDAY))
                .thenReturn(List.of(allocation(joao, MONDAY, "8.00"), allocation(joao, WEDNESDAY, "4.50")));

        Task formwork = task("Forma das vigas", joao, MONDAY, MONDAY.plusDays(1));
        Task pour = task("Concretagem", joao, null, WEDNESDAY);
        when(taskRepository.findAssignedInProjectDuringPeriod(PROJECT_ID, MONDAY, SUNDAY))
                .thenReturn(List.of(formwork, pour));

        TeamScheduleResponse response = service.getSchedule(PROJECT_ID, "week", WEDNESDAY.toString(), ACTOR_ID);

        assertEquals(ScheduleView.WEEK, response.view());
        assertEquals(MONDAY, response.startDate());
        assertEquals(SUNDAY, response.endDate());
        assertEquals(7, response.days().size());
        assertEquals(MONDAY.minusWeeks(1), response.previousDate());
        assertEquals(MONDAY.plusWeeks(1), response.nextDate());

        assertEquals(1, response.members().size(), "membro inativo nao aparece");
        MemberScheduleResponse row = response.members().get(0);
        assertEquals(JOAO_ID, row.userId());
        assertEquals("João Souza", row.userName());
        assertEquals("Estrutura", row.userResponsibility());
        assertEquals(0, new BigDecimal("12.5").compareTo(row.totalAllocatedHours()));

        DayScheduleResponse monday = row.days().get(0);
        assertTrue(monday.allocated());
        assertEquals(List.of("Forma das vigas"), monday.tasks().stream().map(ScheduledTaskResponse::title).toList());
        assertEquals("Estrutura", monday.tasks().get(0).stageName());

        DayScheduleResponse tuesday = row.days().get(1);
        assertFalse(tuesday.allocated());
        assertEquals(0, BigDecimal.ZERO.compareTo(tuesday.allocatedHours()));
        assertEquals(1, tuesday.tasks().size());

        DayScheduleResponse wednesday = row.days().get(2);
        assertTrue(wednesday.allocated());
        assertEquals(List.of("Concretagem"), wednesday.tasks().stream().map(ScheduledTaskResponse::title).toList());

        assertTrue(row.days().get(6).tasks().isEmpty());
        verify(permissionService).requirePermission(PROJECT_ID, ACTOR_ID, ProjectPermission.VIEW_PROJECT);
    }

    @Test
    @DisplayName("Visao mensal deve cobrir do primeiro ao ultimo dia do mes")
    void getMonthScheduleCoversWholeMonth() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(memberRepository.findAllByConstructionProjectIdOrderByJoinedAtAscIdAsc(PROJECT_ID)).thenReturn(List.of());

        TeamScheduleResponse response = service.getSchedule(PROJECT_ID, "MONTH", "2026-02-10", ACTOR_ID);

        assertEquals(LocalDate.of(2026, 2, 1), response.startDate());
        assertEquals(LocalDate.of(2026, 2, 28), response.endDate());
        assertEquals(28, response.days().size());
        assertEquals(LocalDate.of(2026, 1, 1), response.previousDate());
        assertEquals(LocalDate.of(2026, 3, 1), response.nextDate());
        verify(allocationRepository).findByProjectInPeriod(PROJECT_ID, response.startDate(), response.endDate());
    }

    @Test
    @DisplayName("Dia com mais de uma tarefa do mesmo integrante deve vir sobreposto")
    void getMarksOverlappedDays() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(memberRepository.findAllByConstructionProjectIdOrderByJoinedAtAscIdAsc(PROJECT_ID))
                .thenReturn(List.of(member(joao, "ACTIVE")));

        Task running = task("Alvenaria", joao, MONDAY, MONDAY.plusDays(1));
        Task added = task("Reboco", joao, MONDAY.plusDays(1), MONDAY.plusDays(1));
        when(taskRepository.findAssignedInProjectDuringPeriod(PROJECT_ID, MONDAY, SUNDAY))
                .thenReturn(List.of(running, added));

        TeamScheduleResponse response = service.getSchedule(PROJECT_ID, "WEEK", MONDAY.toString(), ACTOR_ID);

        MemberScheduleResponse row = response.members().get(0);
        assertTrue(row.hasOverlap());
        assertFalse(row.days().get(0).overlapped(), "segunda tem so uma tarefa");
        assertTrue(row.days().get(1).overlapped(), "terca tem duas tarefas");
        assertEquals(2, row.days().get(1).tasks().size());
        assertFalse(row.days().get(2).overlapped());
    }

    @Test
    @DisplayName("Integrante sem sobreposicao deve vir com hasOverlap false")
    void getWithoutOverlapKeepsFlagFalse() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(memberRepository.findAllByConstructionProjectIdOrderByJoinedAtAscIdAsc(PROJECT_ID))
                .thenReturn(List.of(member(joao, "ACTIVE")));

        when(taskRepository.findAssignedInProjectDuringPeriod(PROJECT_ID, MONDAY, SUNDAY))
                .thenReturn(List.of(task("Alvenaria", joao, MONDAY, MONDAY.plusDays(2))));

        TeamScheduleResponse response = service.getSchedule(PROJECT_ID, "WEEK", MONDAY.toString(), ACTOR_ID);

        MemberScheduleResponse row = response.members().get(0);
        assertFalse(row.hasOverlap());
        assertTrue(row.days().stream().noneMatch(DayScheduleResponse::overlapped));
    }

    @Test
    @DisplayName("Deve rejeitar view invalida com 400")
    void getRejectsInvalidView() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getSchedule(PROJECT_ID, "YEAR", null, ACTOR_ID));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("Deve rejeitar data invalida com 400")
    void getRejectsInvalidDate() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getSchedule(PROJECT_ID, "WEEK", "12/08/2026", ACTOR_ID));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("Deve retornar 404 para obra inexistente")
    void getReturnsNotFoundForUnknownProject() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getSchedule(PROJECT_ID, null, null, ACTOR_ID));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verifyNoInteractions(permissionService);
    }

    // ============= ALLOCATION =============

    @Test
    @DisplayName("Deve criar alocacao nova e devolver o nome do usuario")
    void upsertCreatesAllocation() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(memberRepository.findByConstructionProjectIdAndUserId(PROJECT_ID, JOAO_ID))
                .thenReturn(Optional.of(member(joao, "ACTIVE")));
        when(allocationRepository.findByConstructionProjectIdAndUserIdAndAllocationDate(PROJECT_ID, JOAO_ID, MONDAY))
                .thenReturn(Optional.empty());
        when(allocationRepository.save(any(ScheduleAllocation.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduleAllocationResponse response = service.upsertAllocation(PROJECT_ID, JOAO_ID, "2026-08-10",
                new ScheduleAllocationRequest(new BigDecimal("8")), ACTOR_ID);

        ArgumentCaptor<ScheduleAllocation> captor = ArgumentCaptor.forClass(ScheduleAllocation.class);
        verify(allocationRepository).save(captor.capture());
        assertEquals(MONDAY, captor.getValue().getAllocationDate());
        assertNotNull(captor.getValue().getCreatedAt());
        assertNotNull(captor.getValue().getUpdatedAt());

        assertEquals("João Souza", response.userName());
        assertEquals(0, new BigDecimal("8").compareTo(response.allocatedHours()));
        verify(permissionService).requirePermission(PROJECT_ID, ACTOR_ID, ProjectPermission.MANAGE_TEAMS);
    }

    @Test
    @DisplayName("Deve atualizar as horas de uma alocacao existente")
    void upsertUpdatesExistingAllocation() {
        ScheduleAllocation existing = allocation(joao, MONDAY, "8.00");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(memberRepository.findByConstructionProjectIdAndUserId(PROJECT_ID, JOAO_ID))
                .thenReturn(Optional.of(member(joao, "ACTIVE")));
        when(allocationRepository.findByConstructionProjectIdAndUserIdAndAllocationDate(PROJECT_ID, JOAO_ID, MONDAY))
                .thenReturn(Optional.of(existing));
        when(allocationRepository.save(existing)).thenReturn(existing);

        service.upsertAllocation(PROJECT_ID, JOAO_ID, "2026-08-10",
                new ScheduleAllocationRequest(new BigDecimal("6.5")), ACTOR_ID);

        assertEquals(0, new BigDecimal("6.5").compareTo(existing.getHours()));
    }

    @Test
    @DisplayName("Deve rejeitar horas fora de (0, 24] ou com mais de duas casas")
    void upsertRejectsInvalidHours() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        for (String hours : new String[]{"0", "-1", "24.01", "1.555"}) {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.upsertAllocation(PROJECT_ID, JOAO_ID, "2026-08-10",
                            new ScheduleAllocationRequest(new BigDecimal(hours)), ACTOR_ID),
                    "horas " + hours);
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }

        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> service.upsertAllocation(PROJECT_ID, JOAO_ID, "2026-08-10",
                        new ScheduleAllocationRequest(null), ACTOR_ID));
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        verify(allocationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve retornar 404 ao alocar usuario que nao e membro da obra")
    void upsertRejectsNonMember() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(memberRepository.findByConstructionProjectIdAndUserId(PROJECT_ID, JOAO_ID)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsertAllocation(PROJECT_ID, JOAO_ID, "2026-08-10",
                        new ScheduleAllocationRequest(new BigDecimal("8")), ACTOR_ID));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(allocationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve retornar 400 ao alocar membro inativo")
    void upsertRejectsInactiveMember() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(memberRepository.findByConstructionProjectIdAndUserId(PROJECT_ID, JOAO_ID))
                .thenReturn(Optional.of(member(joao, "INACTIVE")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsertAllocation(PROJECT_ID, JOAO_ID, "2026-08-10",
                        new ScheduleAllocationRequest(new BigDecimal("8")), ACTOR_ID));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("Deve propagar 403 sem permissao MANAGE_TEAMS")
    void upsertRequiresManageTeams() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(permissionService).requirePermission(PROJECT_ID, ACTOR_ID, ProjectPermission.MANAGE_TEAMS);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsertAllocation(PROJECT_ID, JOAO_ID, "2026-08-10",
                        new ScheduleAllocationRequest(new BigDecimal("8")), ACTOR_ID));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(allocationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve remover alocacao existente")
    void deleteRemovesAllocation() {
        ScheduleAllocation existing = allocation(joao, MONDAY, "8.00");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(allocationRepository.findByConstructionProjectIdAndUserIdAndAllocationDate(PROJECT_ID, JOAO_ID, MONDAY))
                .thenReturn(Optional.of(existing));

        service.deleteAllocation(PROJECT_ID, JOAO_ID, "2026-08-10", ACTOR_ID);

        verify(allocationRepository).delete(existing);
    }

    @Test
    @DisplayName("Deve retornar 404 ao remover alocacao inexistente")
    void deleteReturnsNotFound() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(allocationRepository.findByConstructionProjectIdAndUserIdAndAllocationDate(PROJECT_ID, JOAO_ID, MONDAY))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.deleteAllocation(PROJECT_ID, JOAO_ID, "2026-08-10", ACTOR_ID));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ============= RESPONSIBILITY =============

    @Test
    @DisplayName("Deve gravar responsabilidade sem espacos e devolver o nome do usuario")
    void updateResponsibilityTrimsValue() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(memberRepository.findByConstructionProjectIdAndUserId(PROJECT_ID, MARIA_ID))
                .thenReturn(Optional.of(member(maria, "ACTIVE")));
        when(scheduleMemberRepository.findByConstructionProjectIdAndUserId(PROJECT_ID, MARIA_ID))
                .thenReturn(Optional.empty());
        when(scheduleMemberRepository.save(any(ScheduleMember.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduleMemberResponse response = service.updateResponsibility(PROJECT_ID, MARIA_ID,
                new ScheduleResponsibilityRequest("  Instalações  "), ACTOR_ID);

        assertEquals(MARIA_ID, response.userId());
        assertEquals("Maria Reis", response.userName());
        assertEquals("Instalações", response.userResponsibility());
        verify(permissionService).requirePermission(PROJECT_ID, ACTOR_ID, ProjectPermission.MANAGE_TEAMS);
    }

    @Test
    @DisplayName("Responsabilidade em branco deve limpar o valor")
    void updateResponsibilityBlankClearsValue() {
        ScheduleMember existing = new ScheduleMember();
        existing.setConstructionProject(project);
        existing.setUser(maria);
        existing.setUserResponsibility("Instalações");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(memberRepository.findByConstructionProjectIdAndUserId(PROJECT_ID, MARIA_ID))
                .thenReturn(Optional.of(member(maria, "ACTIVE")));
        when(scheduleMemberRepository.findByConstructionProjectIdAndUserId(PROJECT_ID, MARIA_ID))
                .thenReturn(Optional.of(existing));
        when(scheduleMemberRepository.save(existing)).thenReturn(existing);

        ScheduleMemberResponse response = service.updateResponsibility(PROJECT_ID, MARIA_ID,
                new ScheduleResponsibilityRequest("   "), ACTOR_ID);

        assertNull(response.userResponsibility());
    }

    @Test
    @DisplayName("Deve rejeitar responsabilidade com mais de 100 caracteres")
    void updateResponsibilityRejectsTooLong() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(memberRepository.findByConstructionProjectIdAndUserId(PROJECT_ID, MARIA_ID))
                .thenReturn(Optional.of(member(maria, "ACTIVE")));
        when(scheduleMemberRepository.findByConstructionProjectIdAndUserId(PROJECT_ID, MARIA_ID))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updateResponsibility(PROJECT_ID, MARIA_ID,
                        new ScheduleResponsibilityRequest("x".repeat(101)), ACTOR_ID));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(scheduleMemberRepository, never()).save(any());
    }
}
