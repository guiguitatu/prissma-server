package br.pucpr.prissma_server.design;

import br.pucpr.prissma_server.genai.EnvironmentPreviewRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/projects/{projectId}/proposals")
@Tag(name = "Design Proposals", description = "Design proposals, versions, approval status and AI-generated previews.")
@SecurityRequirement(name = "bearerAuth")
public class DesignProposalController {

    private final DesignProposalService service;
    private final EnvironmentPreviewJobService previewJobService;

    public DesignProposalController(DesignProposalService service,
                                    EnvironmentPreviewJobService previewJobService) {
        this.service = service;
        this.previewJobService = previewJobService;
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

    /**
     * Criação em multipart porque o arquivo da v1 é opcional: uma proposta pode
     * nascer só com título e ambiente e receber a imagem pela prévia da IA.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Create a design proposal",
            description = "Creates a design proposal. The proposal metadata is multipart JSON and the initial image file is optional.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Design proposal created"),
            @ApiResponse(responseCode = "400", description = "Invalid proposal data or file"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to create proposals for the project"),
            @ApiResponse(responseCode = "404", description = "Project not found"),
            @ApiResponse(responseCode = "413", description = "Uploaded file exceeds the configured limit")
    })
    public ResponseEntity<ProposalResponse> create(@PathVariable Long projectId,
                                                   @Valid @RequestPart("proposal") ProposalRequest request,
                                                   @RequestPart(value = "file", required = false) MultipartFile file,
                                                   Authentication auth) {
        ProposalResponse response = service.create(projectId, request, file, resolveUserId(auth));
        URI location = URI.create("/projects/" + projectId + "/proposals/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    @Operation(summary = "List design proposals", description = "Returns a paginated list of design proposals for a project.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Design proposals returned"),
            @ApiResponse(responseCode = "400", description = "Invalid pagination parameters"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the project"),
            @ApiResponse(responseCode = "404", description = "Project not found")
    })
    public ResponseEntity<ProposalPageResponse> list(@PathVariable Long projectId,
                                                     @Parameter(description = "Zero-based page number", example = "0")
                                                     @RequestParam(required = false) Integer page,
                                                     @Parameter(description = "Page size", example = "20")
                                                     @RequestParam(required = false) Integer size,
                                                     Authentication auth) {
        return ResponseEntity.ok(service.list(projectId, page, size, resolveUserId(auth)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a design proposal", description = "Returns a design proposal with its current project-visible data.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Design proposal returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the project"),
            @ApiResponse(responseCode = "404", description = "Project or proposal not found")
    })
    public ResponseEntity<ProposalResponse> get(@PathVariable Long projectId,
                                                @PathVariable Long id,
                                                Authentication auth) {
        return ResponseEntity.ok(service.get(projectId, id, resolveUserId(auth)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a design proposal", description = "Partially updates the proposal metadata.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Design proposal updated"),
            @ApiResponse(responseCode = "400", description = "Invalid proposal data"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to update the proposal"),
            @ApiResponse(responseCode = "404", description = "Project or proposal not found")
    })
    public ResponseEntity<ProposalResponse> update(@PathVariable Long projectId,
                                                   @PathVariable Long id,
                                                   @Valid @RequestBody ProposalRequest request,
                                                   Authentication auth) {
        return ResponseEntity.ok(service.update(projectId, id, request, resolveUserId(auth)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a design proposal", description = "Deletes a design proposal from the project.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Design proposal deleted"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to delete the proposal"),
            @ApiResponse(responseCode = "404", description = "Project or proposal not found")
    })
    public ResponseEntity<Void> delete(@PathVariable Long projectId,
                                       @PathVariable Long id,
                                       Authentication auth) {
        service.delete(projectId, id, resolveUserId(auth));
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @PostMapping(value = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Add a proposal version", description = "Uploads a new image version for an existing design proposal.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Proposal version created"),
            @ApiResponse(responseCode = "400", description = "Invalid version file or description"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to modify the proposal"),
            @ApiResponse(responseCode = "404", description = "Project or proposal not found"),
            @ApiResponse(responseCode = "413", description = "Uploaded file exceeds the configured limit")
    })
    public ResponseEntity<ProposalVersionResponse> addVersion(
            @PathVariable Long projectId,
            @PathVariable Long id,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description,
            Authentication auth) {
        ProposalVersionResponse response =
                service.addVersion(projectId, id, description, file, resolveUserId(auth));
        URI location = URI.create(
                "/projects/" + projectId + "/proposals/" + id + "/versions/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @PatchMapping("/{id}/versions/{versionId}/status")
    @Operation(summary = "Change proposal version status", description = "Updates the approval/status state of a specific proposal version.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Proposal version status updated"),
            @ApiResponse(responseCode = "400", description = "Invalid status transition or request"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to change the proposal status"),
            @ApiResponse(responseCode = "404", description = "Project, proposal or version not found")
    })
    public ResponseEntity<ProposalVersionResponse> changeStatus(
            @PathVariable Long projectId,
            @PathVariable Long id,
            @PathVariable Long versionId,
            @Valid @RequestBody ProposalStatusRequest request,
            Authentication auth) {
        return ResponseEntity.ok(
                service.changeStatus(projectId, id, versionId, request, resolveUserId(auth)));
    }

    @GetMapping("/{id}/versions/{versionId}/image")
    @Operation(summary = "Get a proposal version image", description = "Returns the image associated with a proposal version for inline display.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Proposal version image returned",
                    content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                            schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the proposal"),
            @ApiResponse(responseCode = "404", description = "Project, proposal, version or image not found")
    })
    public ResponseEntity<Resource> versionImage(@PathVariable Long projectId,
                                                 @PathVariable Long id,
                                                 @PathVariable Long versionId,
                                                 Authentication auth) {
        DesignProposalService.VersionImage image =
                service.loadVersionImage(projectId, id, versionId, resolveUserId(auth));

        MediaType mediaType = image.contentType() != null
                ? MediaType.parseMediaType(image.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;

        // inline: a tela mostra a imagem no card, não oferece download.
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(image.fileName() != null ? image.fileName() : "previa", StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(image.resource());
    }

    /**
     * Dispara a prévia por IA. Responde 202: a geração leva de 30 a 60s e
     * continua depois que esta requisição termina.
     */
    @PostMapping(value = "/{id}/previews", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Request an AI proposal preview",
            description = "Queues an AI-generated preview for a proposal using a raw image, an optional floor plan and preview options.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Preview request accepted for processing"),
            @ApiResponse(responseCode = "400", description = "Invalid image, floor plan or preview options"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to modify the proposal"),
            @ApiResponse(responseCode = "404", description = "Project or proposal not found"),
            @ApiResponse(responseCode = "413", description = "Uploaded image exceeds the configured limit")
    })
    public ResponseEntity<PreviewResponse> requestPreview(
            @PathVariable Long projectId,
            @PathVariable Long id,
            @RequestPart("rawImage") MultipartFile rawImage,
            @RequestPart(value = "floorPlan", required = false) MultipartFile floorPlan,
            @Valid @RequestPart("options") EnvironmentPreviewRequest options,
            Authentication auth) {
        PreviewResponse response = previewJobService.request(
                projectId, id, rawImage, floorPlan, options, resolveUserId(auth));
        URI location = URI.create(
                "/projects/" + projectId + "/proposals/" + id + "/previews/" + response.id());
        return ResponseEntity.accepted().location(location).body(response);
    }

    @GetMapping("/{id}/previews/{previewId}")
    @Operation(summary = "Get an AI proposal preview", description = "Returns the current state and result metadata for a previously requested AI preview.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Preview returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the proposal"),
            @ApiResponse(responseCode = "404", description = "Project, proposal or preview not found")
    })
    public ResponseEntity<PreviewResponse> getPreview(@PathVariable Long projectId,
                                                      @PathVariable Long id,
                                                      @PathVariable Long previewId,
                                                      Authentication auth) {
        return ResponseEntity.ok(
                previewJobService.get(projectId, id, previewId, resolveUserId(auth)));
    }
}
