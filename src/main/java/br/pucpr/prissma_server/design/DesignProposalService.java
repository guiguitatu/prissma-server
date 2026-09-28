package br.pucpr.prissma_server.design;

import br.pucpr.prissma_server.attachments.storage.FileStorageService;
import br.pucpr.prissma_server.attachments.storage.StoredFile;
import br.pucpr.prissma_server.attachments.storage.UploadedFileValidator;
import br.pucpr.prissma_server.genai.EnvironmentPreviewRequest;
import br.pucpr.prissma_server.projects.ConstructionProject;
import br.pucpr.prissma_server.projects.ConstructionProjectRepository;
import br.pucpr.prissma_server.projects.ProjectPermission;
import br.pucpr.prissma_server.projects.ProjectPermissionService;
import br.pucpr.prissma_server.stage.Stage;
import br.pucpr.prissma_server.users.User;
import br.pucpr.prissma_server.users.UserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Propostas de design da obra e suas versões.
 *
 * A geração por IA não está aqui — mora no {@link EnvironmentPreviewJobService},
 * porque é assíncrona e tem um ciclo de vida próprio.
 */
@Service
public class DesignProposalService {

    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_TITLE_LENGTH = 255;
    static final int MAX_DESCRIPTION_LENGTH = 2000;

    private final DesignProposalRepository proposalRepository;
    private final DesignSubmissionRepository submissionRepository;
    private final DesignApprovalRepository approvalRepository;
    private final EnvironmentPreviewRepository previewRepository;
    private final ConstructionProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ProjectPermissionService permissionService;
    private final FileStorageService storage;
    private final UploadedFileValidator validator;
    private final EntityManager entityManager;

    public DesignProposalService(DesignProposalRepository proposalRepository,
                                 DesignSubmissionRepository submissionRepository,
                                 DesignApprovalRepository approvalRepository,
                                 EnvironmentPreviewRepository previewRepository,
                                 ConstructionProjectRepository projectRepository,
                                 UserRepository userRepository,
                                 ProjectPermissionService permissionService,
                                 FileStorageService storage,
                                 UploadedFileValidator validator,
                                 EntityManager entityManager) {
        this.proposalRepository = proposalRepository;
        this.submissionRepository = submissionRepository;
        this.approvalRepository = approvalRepository;
        this.previewRepository = previewRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.storage = storage;
        this.validator = validator;
        this.entityManager = entityManager;
    }

    @Transactional
    public ProposalResponse create(Long projectId, ProposalRequest request, MultipartFile file, Long userId) {
        ConstructionProject project = requireProject(projectId);
        permissionService.requirePermission(projectId, userId, ProjectPermission.MANAGE_PROPOSALS);

        String title = requireTitle(request.getTitle());
        if (proposalRepository.existsByConstructionProjectIdAndTitleIgnoreCase(projectId, title)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Já existe uma proposta com este título nesta obra");
        }

        User author = requireUser(userId);
        Instant now = Instant.now();

        DesignProposal proposal = new DesignProposal();
        proposal.setConstructionProject(project);
        proposal.setStage(resolveStage(request.getStageId(), projectId));
        proposal.setTitle(title);
        proposal.setDescription(normalizeDescription(request.getDescription()));
        proposal.setEnvironmentType(requireEnvironment(request.getEnvironmentType()));
        proposal.setCreatedByUser(author);
        proposal.setCreatedByName(author.getName());
        proposal.setCreatedAt(now);
        proposal.setUpdatedAt(now);
        proposalRepository.save(proposal);

        // Toda proposta nasce com a v1: é dela que o card tira status e imagem,
        // e é ela que a primeira prévia por IA vai suceder.
        DesignSubmission firstVersion = buildVersion(proposal, author, 1,
                proposal.getDescription(), false, now);
        storeFileInto(firstVersion, file, proposal.getId());
        submissionRepository.save(firstVersion);

        return ProposalResponse.detail(proposal, List.of(firstVersion));
    }

    @Transactional(readOnly = true)
    public ProposalPageResponse list(Long projectId, Integer page, Integer size, Long userId) {
        requireProject(projectId);
        permissionService.requirePermission(projectId, userId, ProjectPermission.VIEW_PROJECT);

        Pageable pageable = PageRequest.of(resolvePage(page), resolveSize(size));
        Page<DesignProposal> proposals = proposalRepository.findPageByProject(projectId, pageable);

        // Uma consulta para todas as versões da página: o card precisa da versão
        // mais recente e da contagem, e isso não pode virar uma query por card.
        List<Long> ids = proposals.getContent().stream().map(DesignProposal::getId).toList();
        Map<Long, List<DesignSubmission>> versionsByProposal = new HashMap<>();
        if (!ids.isEmpty()) {
            for (DesignSubmission submission : submissionRepository.findAllByProposalIds(ids)) {
                versionsByProposal
                        .computeIfAbsent(submission.getProposal().getId(), key -> new ArrayList<>())
                        .add(submission);
            }
        }

        List<ProposalResponse> content = proposals.getContent().stream()
                .map(proposal -> {
                    List<DesignSubmission> versions =
                            versionsByProposal.getOrDefault(proposal.getId(), List.of());
                    DesignSubmission latest = versions.isEmpty() ? null : versions.getFirst();
                    return ProposalResponse.card(proposal, latest, versions.size());
                })
                .toList();

        return new ProposalPageResponse(
                content,
                proposals.getNumber(),
                proposals.getSize(),
                proposals.getTotalElements(),
                proposals.getTotalPages(),
                proposals.isFirst(),
                proposals.isLast());
    }

    @Transactional(readOnly = true)
    public ProposalResponse get(Long projectId, Long proposalId, Long userId) {
        DesignProposal proposal = requireProposalScopedToProject(projectId, proposalId);
        permissionService.requirePermission(projectId, userId, ProjectPermission.VIEW_PROJECT);
        return ProposalResponse.detail(proposal,
                submissionRepository.findAllByProposalOrderByVersionDesc(proposalId));
    }

    @Transactional
    public ProposalResponse update(Long projectId, Long proposalId, ProposalRequest request, Long userId) {
        DesignProposal proposal = requireProposalScopedToProject(projectId, proposalId);
        permissionService.requirePermission(projectId, userId, ProjectPermission.MANAGE_PROPOSALS);

        String title = requireTitle(request.getTitle());
        if (proposalRepository.existsByConstructionProjectIdAndTitleIgnoreCaseAndIdNot(
                projectId, title, proposalId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Já existe uma proposta com este título nesta obra");
        }

        proposal.setTitle(title);
        proposal.setDescription(normalizeDescription(request.getDescription()));
        proposal.setEnvironmentType(requireEnvironment(request.getEnvironmentType()));
        proposal.setStage(resolveStage(request.getStageId(), projectId));
        proposal.setUpdatedAt(Instant.now());
        proposalRepository.save(proposal);

        return ProposalResponse.detail(proposal,
                submissionRepository.findAllByProposalOrderByVersionDesc(proposalId));
    }

    @Transactional
    public void delete(Long projectId, Long proposalId, Long userId) {
        DesignProposal proposal = requireProposalScopedToProject(projectId, proposalId);
        permissionService.requirePermission(projectId, userId, ProjectPermission.MANAGE_PROPOSALS);

        // As linhas somem por CASCADE; os arquivos não têm quem os apague depois —
        // tanto os das versões quanto as imagens de entrada das prévias, que nunca
        // viraram attachments da obra.
        //
        // As chaves vêm por projeção, e não pelas entidades: uma versão carregada
        // continua gerenciada apontando para a proposta que o delete abaixo marca
        // para remoção, e o flush do commit morre com TransientObjectException.
        List<String> keys = new ArrayList<>(submissionRepository.findFileUrlsByProposal(proposalId));
        keys.addAll(previewRepository.findRawImageKeysByProposal(proposalId));
        keys.addAll(previewRepository.findFloorPlanKeysByProposal(proposalId));

        // As filhas saem explicitamente, e antes da proposta.
        //
        // So as projecoes acima nao bastam: basta uma versao ou previa ter sido
        // carregada em qualquer ponto da MESMA transacao (o POST que criou a
        // proposta, por exemplo) para ela continuar gerenciada apontando para a
        // proposta que o delete abaixo marca para remocao. No flush do commit o
        // Hibernate encontra essa referencia e estoura TransientObjectException.
        //
        // O derived delete do Spring Data faz SELECT + remove por entidade, entao
        // o persistence context fica coerente — coisa que um DELETE em massa
        // (@Modifying) nao faria, por passar por fora dele.
        submissionRepository.deleteByProposalId(proposalId);
        previewRepository.deleteByProposalId(proposalId);

        proposalRepository.delete(proposal);

        // Depois do delete: se ele falhar, a transação volta atrás e os arquivos
        // ainda precisam existir.
        keys.forEach(this::safeDelete);
    }

    @Transactional
    public ProposalVersionResponse addVersion(Long projectId,
                                              Long proposalId,
                                              String description,
                                              MultipartFile file,
                                              Long userId) {
        DesignProposal proposal = requireProposalScopedToProject(projectId, proposalId);
        permissionService.requirePermission(projectId, userId, ProjectPermission.MANAGE_PROPOSALS);

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O arquivo da versão é obrigatório");
        }

        User author = requireUser(userId);
        Instant now = Instant.now();

        DesignSubmission version = buildVersion(proposal, author,
                submissionRepository.findMaxVersion(proposalId) + 1,
                normalizeDescription(description), false, now);
        storeFileInto(version, file, proposalId);
        submissionRepository.save(version);

        proposal.setUpdatedAt(now);
        proposalRepository.save(proposal);

        return ProposalVersionResponse.from(version);
    }

    @Transactional
    public ProposalVersionResponse changeStatus(Long projectId,
                                                Long proposalId,
                                                Long versionId,
                                                ProposalStatusRequest request,
                                                Long userId) {
        DesignProposal proposal = requireProposalScopedToProject(projectId, proposalId);
        permissionService.requirePermission(projectId, userId, ProjectPermission.MANAGE_PROPOSALS);

        DesignSubmission version = requireVersionScopedToProposal(proposalId, versionId);
        ProposalStatus status = ProposalStatus.fromString(request.getStatus());
        User approver = requireUser(userId);
        Instant now = Instant.now();

        version.setStatus(status);
        version.setUpdatedAt(now);
        submissionRepository.save(version);

        // O parecer vira linha em design_approvals para a obra guardar quem
        // aprovou, quando e com qual comentário — o status sozinho esquece isso.
        if (status == ProposalStatus.APPROVED || status == ProposalStatus.REJECTED) {
            DesignApproval approval = new DesignApproval();
            approval.setDesignSubmission(version);
            approval.setApproverUser(approver);
            approval.setApprovalStatus(status == ProposalStatus.APPROVED ? "APPROVED" : "REJECTED");
            approval.setComment(normalizeDescription(request.getComment()));
            approval.setApprovedAt(now);
            approvalRepository.save(approval);
        }

        proposal.setUpdatedAt(now);
        proposalRepository.save(proposal);

        return ProposalVersionResponse.from(version);
    }

    @Transactional(readOnly = true)
    public VersionImage loadVersionImage(Long projectId, Long proposalId, Long versionId, Long userId) {
        requireProposalScopedToProject(projectId, proposalId);
        permissionService.requirePermission(projectId, userId, ProjectPermission.VIEW_PROJECT);

        DesignSubmission version = requireVersionScopedToProposal(proposalId, versionId);
        if (version.getFileUrl() == null || version.getFileUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Esta versão ainda não tem imagem");
        }

        Resource resource = storage.load(version.getFileUrl());
        return new VersionImage(resource, version.getFileName(), version.getFileType());
    }

    // ---- usado pelo job de prévia, que não pode checar permissão (roda fora da requisição) ----

    DesignProposal requireProposalScopedToProject(Long projectId, Long proposalId) {
        DesignProposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposta não encontrada"));
        if (proposal.getConstructionProject() == null
                || !projectId.equals(proposal.getConstructionProject().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposta não encontrada");
        }
        return proposal;
    }

    DesignSubmission requireVersionScopedToProposal(Long proposalId, Long versionId) {
        DesignSubmission version = submissionRepository.findById(versionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Versão não encontrada"));
        if (version.getProposal() == null || !proposalId.equals(version.getProposal().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Versão não encontrada");
        }
        return version;
    }

    DesignSubmission buildVersion(DesignProposal proposal,
                                  User author,
                                  int version,
                                  String description,
                                  boolean generatedByAi,
                                  Instant now) {
        DesignSubmission submission = new DesignSubmission();
        submission.setProposal(proposal);
        submission.setAuthorUser(author);
        submission.setAuthorName(author != null ? author.getName() : "Sistema");
        submission.setDescription(description);
        submission.setVersion(version);
        submission.setStatus(ProposalStatus.DRAFT);
        submission.setGeneratedByAi(generatedByAi);
        submission.setSubmittedAt(now);
        submission.setUpdatedAt(now);
        return submission;
    }

    void safeDelete(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        try {
            storage.delete(storageKey);
        } catch (RuntimeException ignored) {
            // Arquivo órfão não justifica derrubar a operação do usuário.
        }
    }

    // ---- privados ----

    private void storeFileInto(DesignSubmission version, MultipartFile file, Long proposalId) {
        if (file == null || file.isEmpty()) {
            return;
        }

        String contentType = validator.normalizeContentType(file.getContentType());
        validator.validateContentType(contentType);
        validator.validateSize(file.getSize());

        String fileName = validator.sanitizeFileName(file.getOriginalFilename());
        String extension = validator.resolveExtension(contentType);

        StoredFile stored;
        try (InputStream header = file.getInputStream()) {
            validator.verifyMagicBytes(header, contentType);
            try (InputStream payload = file.getInputStream()) {
                stored = storage.store(payload, extension, "proposals/" + proposalId);
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Não foi possível salvar o arquivo. Tente novamente em alguns instantes.");
        }

        version.setFileUrl(stored.storageKey());
        version.setFileName(fileName);
        version.setFileType(contentType);
    }

    private ConstructionProject requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private Stage resolveStage(Long stageId, Long projectId) {
        if (stageId == null) {
            return null;
        }
        Stage stage = entityManager.find(Stage.class, stageId);
        if (stage == null || stage.getConstructionProject() == null
                || !projectId.equals(stage.getConstructionProject().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Etapa não encontrada nesta obra");
        }
        return stage;
    }

    private String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O título é obrigatório");
        }
        String trimmed = title.trim();
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O título deve ter no máximo " + MAX_TITLE_LENGTH + " caracteres");
        }
        return trimmed;
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String trimmed = description.trim();
        if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A descrição deve ter no máximo " + MAX_DESCRIPTION_LENGTH + " caracteres");
        }
        return trimmed;
    }

    private EnvironmentPreviewRequest.EnvironmentType requireEnvironment(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O ambiente é obrigatório");
        }
        try {
            return EnvironmentPreviewRequest.EnvironmentType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ambiente inválido: " + value);
        }
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

    public record VersionImage(Resource resource, String fileName, String contentType) {
    }
}
