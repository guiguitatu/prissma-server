package br.pucpr.prissma_server.workspaces;

import br.pucpr.prissma_server.notifications.NotificationService;
import br.pucpr.prissma_server.notifications.NotificationType;
import br.pucpr.prissma_server.users.Role;
import br.pucpr.prissma_server.users.User;
import br.pucpr.prissma_server.users.UserRepository;
import br.pucpr.prissma_server.users.UserValidator;
import br.pucpr.prissma_server.workspaces.WorkspaceMemberDtos.AcceptInviteRequest;
import br.pucpr.prissma_server.workspaces.WorkspaceMemberDtos.InviteMemberRequest;
import br.pucpr.prissma_server.workspaces.WorkspaceMemberDtos.MemberInviteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * As regras anti-sonda e de hierarquia dos membros do workspace — cada teste
 * é uma lição do modelo de referência que precisa valer NO BACKEND, não só
 * na UI.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkspaceMemberService Tests")
class WorkspaceMemberServiceTest {

    private static final Long WS_ID = 7L;
    private static final Long ACTOR_ID = 1L;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceMemberRepository memberRepository;

    @Mock
    private MemberInviteRepository inviteRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserValidator userValidator;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private NotificationService notificationService;

    private WorkspaceMemberService service;

    private Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        service = new WorkspaceMemberService(
                workspaceRepository,
                memberRepository,
                inviteRepository,
                userRepository,
                userValidator,
                passwordEncoder,
                eventPublisher,
                notificationService,
                "http://localhost:3000");

        workspace = new Workspace();
        var idField = Workspace.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(workspace, WS_ID);
        workspace.setOwnerId(ACTOR_ID);
        workspace.setName("Construtora Teste");
    }

    private WorkspaceContext ownerCtx() {
        return new WorkspaceContext(WS_ID, WorkspaceRole.OWNER, true);
    }

    private WorkspaceContext adminCtx() {
        return new WorkspaceContext(WS_ID, WorkspaceRole.ADMIN, false);
    }

    private WorkspaceMember memberRow(Long id, Long userId, WorkspaceRole role) throws Exception {
        WorkspaceMember member = new WorkspaceMember();
        var idField = WorkspaceMember.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(member, id);
        member.setWorkspace(workspace);
        member.setUserId(userId);
        member.setRole(role);
        member.setActive(true);
        return member;
    }

    // ---------- convite ----------

    @Test
    @DisplayName("Permission is checked BEFORE the email is even looked at (no probing)")
    void invitePermissionBeforeValidation() {
        var ctx = new WorkspaceContext(WS_ID, WorkspaceRole.CLIENT, false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.invite(ctx, ACTOR_ID, new InviteMemberRequest("whatever", null, null)));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(userRepository, inviteRepository, userValidator);
    }

    @Test
    @DisplayName("Inviting a platform-staff email returns the SAME success payload, persisting nothing")
    void inviteStaffEmailIsSilentlyDropped() {
        User staff = new User();
        staff.setId(50L);
        staff.setRole(Role.ADMIN);

        when(workspaceRepository.findByIdAndDeletedAtIsNull(WS_ID)).thenReturn(Optional.of(workspace));
        when(userRepository.findByEmail("staff@prissma.com")).thenReturn(Optional.of(staff));

        MemberInviteResponse response = service.invite(ownerCtx(), ACTOR_ID,
                new InviteMemberRequest("  Staff@Prissma.com ", null, "MEMBER"));

        assertEquals("staff@prissma.com", response.invitedEmail());
        assertEquals("MEMBER", response.role());
        verify(inviteRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("Email is normalized (trim + lowercase) and the invite email is sent")
    void inviteNormalizesEmailAndPublishesEvent() {
        when(workspaceRepository.findByIdAndDeletedAtIsNull(WS_ID)).thenReturn(Optional.of(workspace));
        when(userRepository.findByEmail("nova@obra.com")).thenReturn(Optional.empty());
        when(inviteRepository.findByWorkspaceIdAndInvitedEmailAndAcceptedFalseAndDeletedAtIsNull(WS_ID, "nova@obra.com"))
                .thenReturn(Optional.empty());
        when(inviteRepository.save(any(MemberInvite.class))).thenAnswer(inv -> inv.getArgument(0));

        MemberInviteResponse response = service.invite(ownerCtx(), ACTOR_ID,
                new InviteMemberRequest(" Nova@Obra.COM ", "Nova Pessoa", "CLIENT"));

        assertEquals("nova@obra.com", response.invitedEmail());
        assertEquals("CLIENT", response.role());

        ArgumentCaptor<MemberInvite> saved = ArgumentCaptor.forClass(MemberInvite.class);
        verify(inviteRepository).save(saved.capture());
        assertEquals("nova@obra.com", saved.getValue().getInvitedEmail());
        assertEquals(64, saved.getValue().getTokenHash().length());

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        InviteEmailEvent emailEvent = (InviteEmailEvent) event.getValue();
        assertEquals("nova@obra.com", emailEvent.to());
        assertTrue(emailEvent.inviteLink().startsWith("http://localhost:3000/invite?token="));
    }

    @Test
    @DisplayName("A pending invite for the same email is RENEWED, not duplicated")
    void invitePendingSameEmailIsRenewed() {
        MemberInvite pending = new MemberInvite();
        pending.setWorkspace(workspace);
        pending.setInvitedEmail("nova@obra.com");
        pending.setTokenHash("old-hash");
        pending.setCreatedAt(Instant.now().minus(1, ChronoUnit.DAYS));

        when(workspaceRepository.findByIdAndDeletedAtIsNull(WS_ID)).thenReturn(Optional.of(workspace));
        when(userRepository.findByEmail("nova@obra.com")).thenReturn(Optional.empty());
        when(inviteRepository.findByWorkspaceIdAndInvitedEmailAndAcceptedFalseAndDeletedAtIsNull(WS_ID, "nova@obra.com"))
                .thenReturn(Optional.of(pending));
        when(inviteRepository.save(any(MemberInvite.class))).thenAnswer(inv -> inv.getArgument(0));

        service.invite(ownerCtx(), ACTOR_ID, new InviteMemberRequest("nova@obra.com", null, "ADMIN"));

        verify(inviteRepository).save(pending);
        assertNotEquals("old-hash", pending.getTokenHash());
        assertEquals(WorkspaceRole.ADMIN, pending.getRole());
    }

    @Test
    @DisplayName("Cannot invite an OWNER")
    void inviteOwnerRejected() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.invite(ownerCtx(), ACTOR_ID,
                        new InviteMemberRequest("alguem@obra.com", null, "OWNER")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("Inviting an existing active member -> 409")
    void inviteExistingMemberConflict() throws Exception {
        User existing = new User();
        existing.setId(30L);
        existing.setRole(Role.USER);

        when(workspaceRepository.findByIdAndDeletedAtIsNull(WS_ID)).thenReturn(Optional.of(workspace));
        when(userRepository.findByEmail("ja@membro.com")).thenReturn(Optional.of(existing));
        when(memberRepository.findByWorkspaceIdAndUserIdAndDeletedAtIsNull(WS_ID, 30L))
                .thenReturn(Optional.of(memberRow(9L, 30L, WorkspaceRole.MEMBER)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.invite(ownerCtx(), ACTOR_ID,
                        new InviteMemberRequest("ja@membro.com", null, "MEMBER")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    // ---------- aceite ----------

    @Test
    @DisplayName("Expired or already-consumed invite -> the same generic 400")
    void acceptExpiredInviteRejected() {
        MemberInvite invite = new MemberInvite();
        invite.setWorkspace(workspace);
        invite.setInvitedEmail("nova@obra.com");
        invite.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));

        String token = "tok";
        when(inviteRepository.findByTokenHashAndDeletedAtIsNull(WorkspaceMemberService.sha256Hex(token)))
                .thenReturn(Optional.of(invite));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.acceptInvite(token, null));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Invalid or expired invite", ex.getReason());
    }

    @Test
    @DisplayName("Accept with a new email creates the user (encoded password) and the membership")
    void acceptCreatesUserAndMembership() throws Exception {
        MemberInvite invite = new MemberInvite();
        invite.setWorkspace(workspace);
        invite.setInvitedEmail("nova@obra.com");
        invite.setRole(WorkspaceRole.CLIENT);
        invite.setInvitedBy(ACTOR_ID);
        invite.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));

        String token = "tok";
        when(inviteRepository.findByTokenHashAndDeletedAtIsNull(WorkspaceMemberService.sha256Hex(token)))
                .thenReturn(Optional.of(invite));
        when(userRepository.findByEmail("nova@obra.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("Senha@123")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User user = inv.getArgument(0);
            var idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, 77L);
            return user;
        });
        when(memberRepository.findByWorkspaceIdAndUserIdAndDeletedAtIsNull(WS_ID, 77L))
                .thenReturn(Optional.empty());
        when(memberRepository.save(any(WorkspaceMember.class))).thenAnswer(inv -> inv.getArgument(0));

        service.acceptInvite(token, new AcceptInviteRequest("Nova Pessoa", "Senha@123"));

        ArgumentCaptor<User> newUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(newUser.capture());
        assertEquals("encoded", newUser.getValue().getPassword());
        assertEquals(Role.USER, newUser.getValue().getRole());

        ArgumentCaptor<WorkspaceMember> membership = ArgumentCaptor.forClass(WorkspaceMember.class);
        verify(memberRepository).save(membership.capture());
        assertEquals(WorkspaceRole.CLIENT, membership.getValue().getRole());
        assertEquals(77L, membership.getValue().getUserId());
        assertNotNull(membership.getValue().getAcceptedAt());

        assertTrue(invite.isAccepted());
        verify(notificationService).notifyUser(
                ACTOR_ID,
                NotificationType.INVITE_ACCEPTED,
                "Convite aceito",
                "Nova Pessoa aceitou o convite para o workspace \"Construtora Teste\".");
    }

    // ---------- hierarquia ----------

    @Test
    @DisplayName("Nobody manages themselves")
    void cannotManageSelf() throws Exception {
        when(memberRepository.findById(9L)).thenReturn(Optional.of(memberRow(9L, ACTOR_ID, WorkspaceRole.ADMIN)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.deactivateMember(ownerCtx(), ACTOR_ID, 9L));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("The workspace owner cannot be managed")
    void ownerCannotBeManaged() throws Exception {
        when(memberRepository.findById(9L)).thenReturn(Optional.of(memberRow(9L, ACTOR_ID, WorkspaceRole.OWNER)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.removeMember(adminCtx(), 2L, 9L));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("An ADMIN cannot manage another ADMIN")
    void adminCannotManageAdmin() throws Exception {
        when(memberRepository.findById(9L)).thenReturn(Optional.of(memberRow(9L, 33L, WorkspaceRole.ADMIN)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updateMemberRole(adminCtx(), 2L, 9L, "MEMBER"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    @DisplayName("Deactivation touches ONLY workspace_members.is_active — never the global user")
    void deactivateTouchesOnlyMembership() throws Exception {
        WorkspaceMember target = memberRow(9L, 33L, WorkspaceRole.MEMBER);
        when(memberRepository.findById(9L)).thenReturn(Optional.of(target));
        when(memberRepository.save(any(WorkspaceMember.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(33L)).thenReturn(Optional.empty());

        service.deactivateMember(ownerCtx(), ACTOR_ID, 9L);

        assertFalse(target.isActive());
        assertNull(target.getDeletedAt());
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("Removal is a soft delete (deleted_at) — the partial UNIQUE allows re-inviting")
    void removeIsSoftDelete() throws Exception {
        WorkspaceMember target = memberRow(9L, 33L, WorkspaceRole.MEMBER);
        when(memberRepository.findById(9L)).thenReturn(Optional.of(target));
        when(memberRepository.save(any(WorkspaceMember.class))).thenAnswer(inv -> inv.getArgument(0));

        service.removeMember(ownerCtx(), ACTOR_ID, 9L);

        assertNotNull(target.getDeletedAt());
        verify(memberRepository, never()).delete(any());
        verify(memberRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("Member from another workspace -> 404 (scoped lookup)")
    void memberFromAnotherWorkspaceNotFound() throws Exception {
        Workspace other = new Workspace();
        var idField = Workspace.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(other, 999L);
        other.setOwnerId(5L);

        WorkspaceMember foreign = memberRow(9L, 33L, WorkspaceRole.MEMBER);
        foreign.setWorkspace(other);
        when(memberRepository.findById(9L)).thenReturn(Optional.of(foreign));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.removeMember(ownerCtx(), ACTOR_ID, 9L));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ---------- CLIENT ----------

    @Test
    @DisplayName("CLIENT cannot list the team")
    void clientCannotListMembers() {
        var ctx = new WorkspaceContext(WS_ID, WorkspaceRole.CLIENT, false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.listMembers(ctx));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(memberRepository);
    }
}
