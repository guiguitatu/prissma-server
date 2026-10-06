package br.pucpr.prissma_server.diary;

import br.pucpr.prissma_server.attachments.Attachment;
import br.pucpr.prissma_server.attachments.AttachmentRepository;
import br.pucpr.prissma_server.notifications.NotificationService;
import br.pucpr.prissma_server.projects.ConstructionProject;
import br.pucpr.prissma_server.projects.ConstructionProjectMember;
import br.pucpr.prissma_server.projects.ConstructionProjectMemberRepository;
import br.pucpr.prissma_server.projects.ConstructionProjectRepository;
import br.pucpr.prissma_server.projects.ProjectPermission;
import br.pucpr.prissma_server.users.User;
import br.pucpr.prissma_server.users.UserRepository;
import br.pucpr.prissma_server.projects.ProjectPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DiaryService Tests")
class DiaryServiceTest {

    @Mock
    private DiaryEntryRepository diaryRepository;

    @Mock
    private ConstructionProjectRepository projectRepository;

    @Mock
    private ConstructionProjectMemberRepository memberRepository;

    @Mock
    private AttachmentRepository attachmentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProjectPermissionService permissionService;

    // O DiaryService notifica os demais membros do projeto ao criar/editar uma
    // entrada. Sem este mock o @InjectMocks injeta null nesse parametro do
    // construtor (o Mockito nao falha, so deixa o buraco) e o teste quebra com
    // NullPointerException no meio da regra de negocio, escondendo o que estava
    // sendo testado de verdade.
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private DiaryService service;

    private static final Long PROJECT_ID = 1L;
    private static final Long USER_ID = 7L;

    private ConstructionProject project;
    private User responsible;
    private DiaryEntryRequest validRequest;

    @BeforeEach
    void setUp() {
        project = new ConstructionProject();
        project.setId(PROJECT_ID);
        project.setTitle("Reforma Vila Nova");

        responsible = new User();
        responsible.setId(USER_ID);
        responsible.setName("Mestre de Obras");

        validRequest = new DiaryEntryRequest(
                Instant.now().minus(2, ChronoUnit.HOURS),
                "OCCURRENCE",
                null,
                "Concretagem da laje concluida no periodo da manha",
                null
        );
    }

    private void mockActiveMembership(Long userId) {
        ConstructionProjectMember member = new ConstructionProjectMember();
        member.setConstructionProject(project);
        member.setUser(responsible);
        member.setRoleInProject("FOREMAN");
        member.setMembershipStatus("ACTIVE");
        when(memberRepository.findByConstructionProjectIdAndUserId(PROJECT_ID, userId))
                .thenReturn(Optional.of(member));
    }

    private DiaryEntry entry(Long id, ConstructionProject owner) {
        DiaryEntry entry = new DiaryEntry();
        entry.setId(id);
        entry.setConstructionProject(owner);
        entry.setEntryDate(Instant.now().minus(1, ChronoUnit.DAYS));
        entry.setEntryType(DiaryEntryType.OCCURRENCE);
        entry.setResponsibleUser(responsible);
        entry.setResponsibleName("Mestre de Obras");
        entry.setDescription("Registro existente");
        entry.setCreatedAt(Instant.now());
        entry.setUpdatedAt(Instant.now());
        return entry;
    }

    // ============= CREATE =============

    @Test
    @DisplayName("Deve criar registro usando o usuario autenticado como responsavel")
    void createUsesAuthenticatedUserAsDefaultResponsible() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(responsible));
        mockActiveMembership(USER_ID);
        when(diaryRepository.save(any(DiaryEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        DiaryEntryResponse response = service.create(PROJECT_ID, validRequest, USER_ID);

        ArgumentCaptor<DiaryEntry> captor = ArgumentCaptor.forClass(DiaryEntry.class);
        verify(diaryRepository).save(captor.capture());
        DiaryEntry saved = captor.getValue();

        assertEquals(DiaryEntryType.OCCURRENCE, saved.getEntryType());
        assertEquals(USER_ID, saved.getResponsibleUser().getId());
        assertEquals("Mestre de Obras", saved.getResponsibleName());
        assertEquals(validRequest.getEntryDate(), saved.getEntryDate());
        assertNull(saved.getAttachment());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        assertEquals(DiaryEntryType.OCCURRENCE, response.entryType());
        assertEquals("Mestre de Obras", response.responsibleName());
        verify(permissionService).requirePermission(PROJECT_ID, USER_ID, ProjectPermission.MANAGE_DIARY);
    }

    @Test
    @DisplayName("Deve vincular anexo da propria obra")
    void createLinksAttachmentFromSameProject() {
        Attachment attachment = new Attachment();
        attachment.setConstructionProject(project);
        attachment.setFileName("laje.pdf");

        validRequest.setAttachmentId(42L);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(responsible));
        mockActiveMembership(USER_ID);
        when(attachmentRepository.findById(42L)).thenReturn(Optional.of(attachment));
        when(diaryRepository.save(any(DiaryEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        DiaryEntryResponse response = service.create(PROJECT_ID, validRequest, USER_ID);

        assertEquals("laje.pdf", response.attachmentFileName());
    }

    @Test
    @DisplayName("Nao deve vincular anexo de outra obra")
    void createRejectsAttachmentFromAnotherProject() {
        ConstructionProject other = new ConstructionProject();
        other.setId(99L);
        Attachment attachment = new Attachment();
        attachment.setConstructionProject(other);

        validRequest.setAttachmentId(42L);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(responsible));
        mockActiveMembership(USER_ID);
        when(attachmentRepository.findById(42L)).thenReturn(Optional.of(attachment));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(PROJECT_ID, validRequest, USER_ID));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(diaryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Nao deve aceitar data futura")
    void createRejectsFutureEntryDate() {
        validRequest.setEntryDate(Instant.now().plus(1, ChronoUnit.HOURS));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(PROJECT_ID, validRequest, USER_ID));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("future"));
    }

    @Test
    @DisplayName("Nao deve aceitar tipo invalido")
    void createRejectsInvalidType() {
        validRequest.setEntryType("ATRASO");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(PROJECT_ID, validRequest, USER_ID));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("Nao deve aceitar responsavel que nao e membro da obra")
    void createRejectsResponsibleOutsideProject() {
        validRequest.setResponsibleUserId(55L);
        User outsider = new User();
        outsider.setId(55L);
        outsider.setName("Fora da obra");

        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(userRepository.findById(55L)).thenReturn(Optional.of(outsider));
        when(memberRepository.findByConstructionProjectIdAndUserId(PROJECT_ID, 55L))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(PROJECT_ID, validRequest, USER_ID));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("member"));
    }

    @Test
    @DisplayName("Nao deve aceitar responsavel com vinculo inativo")
    void createRejectsInactiveResponsible() {
        ConstructionProjectMember inactive = new ConstructionProjectMember();
        inactive.setConstructionProject(project);
        inactive.setUser(responsible);
        inactive.setRoleInProject("FOREMAN");
        inactive.setMembershipStatus("INACTIVE");

        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(responsible));
        when(memberRepository.findByConstructionProjectIdAndUserId(PROJECT_ID, USER_ID))
                .thenReturn(Optional.of(inactive));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(PROJECT_ID, validRequest, USER_ID));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("Nao deve aceitar descricao em branco (bean validation nao roda sem o starter)")
    void createRejectsBlankDescription() {
        validRequest.setDescription("   ");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(PROJECT_ID, validRequest, USER_ID));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Description"));
        verify(diaryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Nao deve aceitar data ausente")
    void createRejectsMissingEntryDate() {
        validRequest.setEntryDate(null);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(PROJECT_ID, validRequest, USER_ID));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("required"));
    }

    @Test
    @DisplayName("Nao deve aceitar descricao acima do limite")
    void createRejectsOversizedDescription() {
        validRequest.setDescription("x".repeat(DiaryService.MAX_DESCRIPTION_LENGTH + 1));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(PROJECT_ID, validRequest, USER_ID));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(diaryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve exigir MANAGE_DIARY para criar")
    void createRequiresManageDiaryPermission() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "denied"))
                .when(permissionService)
                .requirePermission(PROJECT_ID, USER_ID, ProjectPermission.MANAGE_DIARY);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.create(PROJECT_ID, validRequest, USER_ID));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(diaryRepository, never()).save(any());
    }

    // ============= LIST =============

    @Test
    @DisplayName("Deve listar paginado com tamanho padrao e exigir apenas VIEW_PROJECT")
    void listUsesDefaultPageSize() {
        DiaryEntry recent = entry(2L, project);
        DiaryEntry older = entry(1L, project);
        Page<DiaryEntry> page = new PageImpl<>(List.of(recent, older),
                PageRequest.of(0, DiaryService.DEFAULT_PAGE_SIZE), 2);

        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(diaryRepository.findPageByProject(eq(PROJECT_ID), any(Pageable.class))).thenReturn(page);

        DiaryEntryPageResponse response = service.list(PROJECT_ID, null, null, USER_ID);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(diaryRepository).findPageByProject(eq(PROJECT_ID), captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(DiaryService.DEFAULT_PAGE_SIZE, captor.getValue().getPageSize());

        assertEquals(2, response.content().size());
        assertEquals(2L, response.content().get(0).id());
        assertEquals(2L, response.totalElements());
        assertEquals(1, response.totalPages());
        assertTrue(response.first());
        assertTrue(response.last());
        verify(permissionService).requirePermission(PROJECT_ID, USER_ID, ProjectPermission.VIEW_PROJECT);
    }

    @Test
    @DisplayName("Deve respeitar pagina e tamanho informados")
    void listHonoursRequestedPageAndSize() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(diaryRepository.findPageByProject(eq(PROJECT_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5), 12));

        service.list(PROJECT_ID, 2, 5, USER_ID);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(diaryRepository).findPageByProject(eq(PROJECT_ID), captor.capture());
        assertEquals(2, captor.getValue().getPageNumber());
        assertEquals(5, captor.getValue().getPageSize());
    }

    @Test
    @DisplayName("Deve recusar tamanho de pagina acima do teto")
    void listRejectsOversizedPage() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.list(PROJECT_ID, 0, DiaryService.MAX_PAGE_SIZE + 1, USER_ID));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(diaryRepository, never()).findPageByProject(any(), any());
    }

    @Test
    @DisplayName("Deve recusar pagina negativa")
    void listRejectsNegativePage() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.list(PROJECT_ID, -1, 10, USER_ID));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    // ============= DELETE =============

    @Test
    @DisplayName("Nao deve excluir registro de outra obra")
    void deleteRejectsEntryFromAnotherProject() {
        ConstructionProject other = new ConstructionProject();
        other.setId(99L);
        when(diaryRepository.findById(10L)).thenReturn(Optional.of(entry(10L, other)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.delete(PROJECT_ID, 10L, USER_ID));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(permissionService, never()).requirePermission(any(), any(), any());
        verify(diaryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("Nao deve excluir registro inexistente")
    void deleteRejectsMissingEntry() {
        when(diaryRepository.findById(10L)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.delete(PROJECT_ID, 10L, USER_ID));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("Deve excluir registro da propria obra exigindo MANAGE_DIARY")
    void deleteRemovesEntry() {
        DiaryEntry existing = entry(10L, project);
        when(diaryRepository.findById(10L)).thenReturn(Optional.of(existing));

        service.delete(PROJECT_ID, 10L, USER_ID);

        verify(permissionService).requirePermission(PROJECT_ID, USER_ID, ProjectPermission.MANAGE_DIARY);
        verify(diaryRepository).delete(existing);
    }
}
