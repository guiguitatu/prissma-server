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
import br.pucpr.prissma_server.workspaces.WorkspaceMemberDtos.WorkspaceMemberResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WorkspaceMemberService {

    private static final int INVITE_EXPIRY_DAYS = 7;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final MemberInviteRepository inviteRepository;
    private final UserRepository userRepository;
    private final UserValidator userValidator;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationService notificationService;
    private final String frontendUrl;

    public WorkspaceMemberService(WorkspaceRepository workspaceRepository,
                                  WorkspaceMemberRepository memberRepository,
                                  MemberInviteRepository inviteRepository,
                                  UserRepository userRepository,
                                  UserValidator userValidator,
                                  PasswordEncoder passwordEncoder,
                                  ApplicationEventPublisher eventPublisher,
                                  NotificationService notificationService,
                                  @Value("${security.password-reset.frontend-url}") String frontendUrl) {
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.inviteRepository = inviteRepository;
        this.userRepository = userRepository;
        this.userValidator = userValidator;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
        this.notificationService = notificationService;
        this.frontendUrl = frontendUrl;
    }

    // ---------- listagem ----------

    /** Membros do workspace ativo. CLIENT não vê a equipe (403). */
    @Transactional(readOnly = true)
    public List<WorkspaceMemberResponse> listMembers(WorkspaceContext ctx) {
        if (ctx == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found");
        }
        if (ctx.role() == WorkspaceRole.CLIENT) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Clients cannot list workspace members");
        }

        List<WorkspaceMember> members = memberRepository
                .findAllByWorkspaceIdAndDeletedAtIsNullOrderByIdAsc(ctx.workspaceId());
        Map<Long, User> users = userRepository
                .findAllById(members.stream().map(WorkspaceMember::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        return members.stream()
                .map(m -> {
                    User user = users.get(m.getUserId());
                    return new WorkspaceMemberResponse(
                            m.getId(),
                            m.getUserId(),
                            user != null ? user.getName() : null,
                            user != null ? user.getEmail() : null,
                            m.getRole().name(),
                            m.isActive(),
                            m.getAcceptedAt());
                })
                .toList();
    }

    // ---------- convite ----------

    @Transactional
    public MemberInviteResponse invite(WorkspaceContext ctx, Long actorId, InviteMemberRequest request) {
        // 1. PERMISSÃO PRIMEIRO — antes de olhar qualquer dado do request.
        WorkspacePermissionPolicy.require(ctx, WorkspaceAction.INVITE_MEMBERS);

        // 2. Validação de entrada.
        if (request == null || request.email() == null || request.email().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        String email = request.email().trim().toLowerCase();
        userValidator.validateEmail(email);
        WorkspaceRole role = WorkspaceRole.fromString(request.role() == null ? "MEMBER" : request.role());
        if (role == WorkspaceRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot invite an OWNER");
        }

        Workspace workspace = workspaceRepository.findByIdAndDeletedAtIsNull(ctx.workspaceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));

        Instant now = Instant.now();
        Instant expiresAt = now.plus(INVITE_EXPIRY_DAYS, ChronoUnit.DAYS);

        User existing = userRepository.findByEmail(email).orElse(null);
        if (existing != null) {
            if (existing.getRole() == Role.ADMIN) {
                // Staff da plataforma nunca entra num workspace por convite —
                // mas a resposta é IDÊNTICA ao sucesso (anti-sonda, lição §10.2).
                return new MemberInviteResponse(email, role.name(), expiresAt);
            }
            boolean alreadyMember = memberRepository
                    .findByWorkspaceIdAndUserIdAndDeletedAtIsNull(ctx.workspaceId(), existing.getId())
                    .isPresent();
            if (alreadyMember) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "User is already a member of this workspace");
            }
        }

        String token = generateToken();

        // Convite pendente do mesmo e-mail é RENOVADO, nunca duplicado.
        MemberInvite invite = inviteRepository
                .findByWorkspaceIdAndInvitedEmailAndAcceptedFalseAndDeletedAtIsNull(ctx.workspaceId(), email)
                .orElseGet(() -> {
                    MemberInvite fresh = new MemberInvite();
                    fresh.setWorkspace(workspace);
                    fresh.setInvitedEmail(email);
                    fresh.setCreatedAt(now);
                    return fresh;
                });
        invite.setFullName(request.fullName());
        invite.setRole(role);
        invite.setInvitedBy(actorId);
        invite.setTokenHash(sha256Hex(token));
        invite.setExpiresAt(expiresAt);
        invite.setUpdatedAt(now);
        inviteRepository.save(invite);

        String link = frontendUrl + "/invite?token=" + token;
        eventPublisher.publishEvent(new InviteEmailEvent(email, workspace.getName(), link));

        return new MemberInviteResponse(email, role.name(), expiresAt);
    }

    /** Aceite PÚBLICO: cria a conta se o e-mail ainda não existir. */
    @Transactional
    public void acceptInvite(String token, AcceptInviteRequest request) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired invite");
        }

        MemberInvite invite = inviteRepository.findByTokenHashAndDeletedAtIsNull(sha256Hex(token))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired invite"));

        Instant now = Instant.now();
        if (invite.isAccepted() || invite.getExpiresAt().isBefore(now)) {
            // Mesmo 400 genérico para expirado/consumido — sem oráculo de estado.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired invite");
        }

        User user = userRepository.findByEmail(invite.getInvitedEmail()).orElse(null);
        if (user == null) {
            String password = request == null ? null : request.password();
            userValidator.validatePassword(password);

            user = new User();
            user.setEmail(invite.getInvitedEmail());
            String name = request != null && request.fullName() != null && !request.fullName().isBlank()
                    ? request.fullName().trim()
                    : invite.getFullName();
            if (name == null || name.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Full name is required");
            }
            user.setName(name);
            user.setPassword(passwordEncoder.encode(password));
            user.setRole(Role.USER);
            user = userRepository.save(user);
        }

        WorkspaceMember member = memberRepository
                .findByWorkspaceIdAndUserIdAndDeletedAtIsNull(invite.getWorkspace().getId(), user.getId())
                .orElseGet(() -> {
                    WorkspaceMember fresh = new WorkspaceMember();
                    fresh.setWorkspace(invite.getWorkspace());
                    fresh.setUserId(null);
                    fresh.setCreatedAt(now);
                    return fresh;
                });
        member.setUserId(user.getId());
        member.setRole(invite.getRole());
        member.setInvitedBy(invite.getInvitedBy());
        member.setAcceptedAt(now);
        member.setActive(true);
        member.setUpdatedAt(now);
        memberRepository.save(member);

        invite.setAccepted(true);
        invite.setUpdatedAt(now);
        inviteRepository.save(invite);
        
        notificationService.notifyUser(
                invite.getInvitedBy(),
                NotificationType.INVITE_ACCEPTED,
                "Convite aceito",
                user.getName() + " aceitou o convite para o workspace \""
                        + invite.getWorkspace().getName() + "\".");
    }

    // ---------- gestão ----------

    @Transactional
    public WorkspaceMemberResponse updateMemberRole(WorkspaceContext ctx, Long actorId,
                                                    Long memberId, String roleName) {
        WorkspacePermissionPolicy.require(ctx, WorkspaceAction.MANAGE_MEMBERS);
        WorkspaceMember member = loadManagedMember(ctx, actorId, memberId, "change the role of");

        WorkspaceRole newRole = WorkspaceRole.fromString(roleName);
        if (newRole == WorkspaceRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot promote a member to OWNER");
        }

        member.setRole(newRole);
        member.setUpdatedAt(Instant.now());
        memberRepository.save(member);
        return toResponse(member);
    }

    /** Toca SÓ is_active desta membership — nunca o usuário global (lição §10.1). */
    @Transactional
    public WorkspaceMemberResponse deactivateMember(WorkspaceContext ctx, Long actorId, Long memberId) {
        WorkspacePermissionPolicy.require(ctx, WorkspaceAction.MANAGE_MEMBERS);
        WorkspaceMember member = loadManagedMember(ctx, actorId, memberId, "deactivate");

        member.setActive(false);
        member.setUpdatedAt(Instant.now());
        memberRepository.save(member);
        return toResponse(member);
    }

    /** Soft delete: a linha ganha deleted_at; a UNIQUE parcial permite reconvidar. */
    @Transactional
    public void removeMember(WorkspaceContext ctx, Long actorId, Long memberId) {
        WorkspacePermissionPolicy.require(ctx, WorkspaceAction.MANAGE_MEMBERS);
        WorkspaceMember member = loadManagedMember(ctx, actorId, memberId, "remove");

        Instant now = Instant.now();
        member.setActive(false);
        member.setDeletedAt(now);
        member.setUpdatedAt(now);
        memberRepository.save(member);
    }

    /**
     * Carrega o membro validando as regras de hierarquia:
     * pertence ao workspace ativo · ninguém gerencia a si mesmo · o OWNER da
     * conta é intocável · ADMIN não gerencia outro ADMIN.
     */
    private WorkspaceMember loadManagedMember(WorkspaceContext ctx, Long actorId, Long memberId, String verb) {
        WorkspaceMember member = memberRepository.findById(memberId)
                .filter(m -> m.getDeletedAt() == null)
                .filter(m -> m.getWorkspace().getId().equals(ctx.workspaceId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));

        if (member.getUserId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "You cannot " + verb + " yourself");
        }
        boolean targetIsOwner = member.getRole() == WorkspaceRole.OWNER
                || member.getWorkspace().getOwnerId().equals(member.getUserId());
        if (targetIsOwner) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The workspace owner cannot be managed");
        }
        if (ctx.role() == WorkspaceRole.ADMIN && member.getRole() == WorkspaceRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "An ADMIN cannot manage another ADMIN");
        }
        return member;
    }

    private WorkspaceMemberResponse toResponse(WorkspaceMember member) {
        User user = userRepository.findById(member.getUserId()).orElse(null);
        return new WorkspaceMemberResponse(
                member.getId(),
                member.getUserId(),
                user != null ? user.getName() : null,
                user != null ? user.getEmail() : null,
                member.getRole().name(),
                member.isActive(),
                member.getAcceptedAt());
    }

    // ---------- token ----------

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** sha256 hex — lookup O(1) via índice único (bcrypt em loop era o anti-padrão). */
    static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
