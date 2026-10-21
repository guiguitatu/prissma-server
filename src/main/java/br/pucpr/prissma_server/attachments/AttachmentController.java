package br.pucpr.prissma_server.attachments;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/projects/{projectId}/attachments")
@Tag(name = "Attachments", description = "Project, stage and task file attachments.")
@SecurityRequirement(name = "bearerAuth")
public class AttachmentController {

    private final AttachmentService service;

    public AttachmentController(AttachmentService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload an attachment",
            description = "Uploads a file attached to the project itself or, when target is STAGE or TASK, to the supplied stage/task identifier.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Attachment uploaded"),
            @ApiResponse(responseCode = "400", description = "Invalid target, missing target identifier or unsupported file"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to modify the project"),
            @ApiResponse(responseCode = "404", description = "Project, stage or task not found"),
            @ApiResponse(responseCode = "413", description = "File exceeds the configured upload limit")
    })
    public ResponseEntity<AttachmentResponse> upload(@PathVariable Long projectId,
                                                     @RequestParam("file") MultipartFile file,
                                                     @Parameter(description = "Attachment target", example = "PROJECT")
                                                     @RequestParam(value = "target", defaultValue = "PROJECT") AttachmentTarget target,
                                                     @Parameter(description = "Required when target is STAGE", example = "10")
                                                     @RequestParam(value = "stageId", required = false) Long stageId,
                                                     @Parameter(description = "Required when target is TASK", example = "25")
                                                     @RequestParam(value = "taskId", required = false) Long taskId,
                                                     Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Long targetId = switch (target) {
            case STAGE -> stageId;
            case TASK -> taskId;
            case PROJECT -> null;
        };
        Attachment saved = service.upload(projectId, target, targetId, file, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(AttachmentResponse.from(saved));
    }

    @GetMapping
    @Operation(summary = "List project attachments",
            description = "Lists project attachments and optionally filters them by stage or task.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attachments returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the project"),
            @ApiResponse(responseCode = "404", description = "Project not found")
    })
    public ResponseEntity<List<AttachmentResponse>> list(@PathVariable Long projectId,
                                                         @RequestParam(value = "stageId", required = false) Long stageId,
                                                         @RequestParam(value = "taskId", required = false) Long taskId,
                                                         Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        List<AttachmentResponse> list = service.list(projectId, stageId, taskId, userId).stream()
                .map(AttachmentResponse::from)
                .toList();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/{id}/download")
    @Operation(summary = "Download an attachment", description = "Downloads an attachment preserving its stored content type and file name.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attachment file returned",
                    content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                            schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the project"),
            @ApiResponse(responseCode = "404", description = "Project or attachment not found")
    })
    public ResponseEntity<Resource> download(@PathVariable Long projectId,
                                             @PathVariable Long id,
                                             Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        AttachmentService.DownloadPayload payload = service.download(projectId, id, userId);

        MediaType mediaType = payload.contentType() != null
                ? MediaType.parseMediaType(payload.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(payload.fileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(payload.resource());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an attachment", description = "Deletes an attachment from a project.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Attachment deleted"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to modify the project"),
            @ApiResponse(responseCode = "404", description = "Project or attachment not found")
    })
    public ResponseEntity<Void> delete(@PathVariable Long projectId,
                                       @PathVariable Long id,
                                       Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        service.delete(projectId, id, userId);
        return ResponseEntity.noContent().build();
    }
}
