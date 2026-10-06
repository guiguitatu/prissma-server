package br.pucpr.prissma_server.diary;

import br.pucpr.prissma_server.attachments.Attachment;
import br.pucpr.prissma_server.attachments.AttachmentRepository;
import br.pucpr.prissma_server.notifications.NotificationService;
import br.pucpr.prissma_server.notifications.NotificationType;
import br.pucpr.prissma_server.projects.ConstructionProject;
import br.pucpr.prissma_server.projects.ConstructionProjectMember;
import br.pucpr.prissma_server.projects.ConstructionProjectMemberRepository;
import br.pucpr.prissma_server.projects.ConstructionProjectRepository;
import br.pucpr.prissma_server.projects.ProjectPermission;
import br.pucpr.prissma_server.projects.ProjectPermissionService;
import br.pucpr.prissma_server.users.User;
import br.pucpr.prissma_server.users.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;

@Service
public class DiaryService {

    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_DESCRIPTION_LENGTH = 2000;
    private final DiaryEntryRepository diaryRepository;
    private final ConstructionProjectRepository projectRepository;
    private final ConstructionProjectMemberRepository memberRepository;
    private final AttachmentRepository attachmentRepository;
    private final UserRepository userRepository;
    private final ProjectPermissionService permissionService;
    private final NotificationService notificationService;

    public DiaryService(DiaryEntryRepository diaryRepository,
                        ConstructionProjectRepository projectRepository,
                        ConstructionProjectMemberRepository memberRepository,
                        AttachmentRepository attachmentRepository,
                        UserRepository userRepository,
                        ProjectPermissionService permissionService,
                        NotificationService notificationService) {
        this.diaryRepository = diaryRepository;
        this.projectRepository = projectRepository;
        this.memberRepository = memberRepository;
        this.attachmentRepository = attachmentRepository;
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.notificationService = notificationService;
    }

    @Transactional
    public DiaryEntryResponse create(Long projectId, DiaryEntryRequest request, Long userId) {
        ConstructionProject project = requireProject(projectId);
        permissionService.requirePermission(projectId, userId, ProjectPermission.MANAGE_DIARY);

        DiaryEntryType type = DiaryEntryType.fromString(request.getEntryType());
        Instant entryDate = requireEntryDate(request.getEntryDate());
        String description = requireDescription(request.getDescription());

        Long responsibleId = request.getResponsibleUserId() != null
                ? request.getResponsibleUserId()
                : userId;
        User responsible = requireProjectMemberUser(projectId, responsibleId);

        DiaryEntry entry = new DiaryEntry();
        entry.setConstructionProject(project);
        entry.setEntryDate(entryDate);
        entry.setEntryType(type);
        entry.setResponsibleUser(responsible);
        entry.setResponsibleName(responsible.getName());
        entry.setDescription(description);
        entry.setAttachment(resolveAttachment(request.getAttachmentId(), projectId));

        Instant now = Instant.now();
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);

        DiaryEntryResponse response = DiaryEntryResponse.from(diaryRepository.save(entry));

        notifyProjectMembers(project, responsible, userId);

        return response;
    }

    @Transactional(readOnly = true)
    public DiaryEntryPageResponse list(Long projectId, Integer page, Integer size, Long userId) {
        requireProject(projectId);
        permissionService.requirePermission(projectId, userId, ProjectPermission.VIEW_PROJECT);

        Pageable pageable = PageRequest.of(resolvePage(page), resolveSize(size));
        Page<DiaryEntry> entries = diaryRepository.findPageByProject(projectId, pageable);
        return DiaryEntryPageResponse.from(entries);
    }

    @Transactional
    public void delete(Long projectId, Long entryId, Long userId) {
        DiaryEntry entry = requireEntryScopedToProject(projectId, entryId);
        permissionService.requirePermission(projectId, userId, ProjectPermission.MANAGE_DIARY);
        diaryRepository.delete(entry);
    }

    private ConstructionProject requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    }

    private void notifyProjectMembers(ConstructionProject project, User responsible, Long actorUserId) {
        List<Long> memberIds = memberRepository
                .findAllByConstructionProjectIdOrderByJoinedAtAscIdAsc(project.getId()).stream()
                .filter(m -> "ACTIVE".equals(m.getMembershipStatus()))
                .map(m -> m.getUser().getId())
                .toList();

        notificationService.notifyUsersExcept(
                memberIds,
                actorUserId,
                NotificationType.DIARY_ENTRY_CREATED,
                "Nova entrada no diário de obra",
                responsible.getName() + " registrou uma entrada no diário da obra \""
                        + project.getTitle() + "\".");
    }

    private DiaryEntry requireEntryScopedToProject(Long projectId, Long entryId) {
        DiaryEntry entry = diaryRepository.findById(entryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Diary entry not found"));
        if (entry.getConstructionProject() == null
                || !projectId.equals(entry.getConstructionProject().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Diary entry not found");
        }
        return entry;
    }

    private Instant requireEntryDate(Instant entryDate) {
        if (entryDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Entry date is required");
        }
        if (entryDate.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Entry date cannot be in the future");
        }
        return entryDate;
    }

    private String requireDescription(String description) {
        if (description == null || description.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Description is required");
        }
        String trimmed = description.trim();
        if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        return trimmed;
    }

    private User requireProjectMemberUser(Long projectId, Long responsibleUserId) {
        User user = userRepository.findById(responsibleUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Responsible user not found"));

        ConstructionProjectMember member = memberRepository
                .findByConstructionProjectIdAndUserId(projectId, responsibleUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Responsible user is not a member of this project"));

        if (!"ACTIVE".equals(member.getMembershipStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Responsible user is not an active member of this project");
        }
        return user;
    }

    private Attachment resolveAttachment(Long attachmentId, Long projectId) {
        if (attachmentId == null) {
            return null;
        }
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Attachment not found in this project"));
        if (attachment.getConstructionProject() == null
                || !projectId.equals(attachment.getConstructionProject().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Attachment not found in this project");
        }
        return attachment;
    }

    private int resolvePage(Integer page) {
        if (page == null) {
            return 0;
        }
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be zero or greater");
        }
        return page;
    }

    private int resolveSize(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (size < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Size must be greater than zero");
        }
        if (size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Size must be at most " + MAX_PAGE_SIZE);
        }
        return size;
    }
}
