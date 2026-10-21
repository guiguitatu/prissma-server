package br.pucpr.prissma_server.diary;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/projects/{projectId}/diary-entries")
@Tag(name = "Construction Diary", description = "Daily construction diary entries associated with a project.")
@SecurityRequirement(name = "bearerAuth")
public class DiaryController {

    private final DiaryService service;

    public DiaryController(DiaryService service) {
        this.service = service;
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

    @PostMapping
    @Operation(summary = "Create a diary entry", description = "Creates a new construction diary entry for a project.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Diary entry created"),
            @ApiResponse(responseCode = "400", description = "Invalid diary entry data"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to modify the project"),
            @ApiResponse(responseCode = "404", description = "Project not found")
    })
    public ResponseEntity<DiaryEntryResponse> create(@PathVariable Long projectId,
                                                     @Valid @RequestBody DiaryEntryRequest request,
                                                     Authentication auth) {
        DiaryEntryResponse response = service.create(projectId, request, resolveUserId(auth));
        URI location = URI.create("/projects/" + projectId + "/diary-entries/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    @Operation(summary = "List diary entries", description = "Returns a paginated list of construction diary entries for a project.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Diary entries returned"),
            @ApiResponse(responseCode = "400", description = "Invalid pagination parameters"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the project"),
            @ApiResponse(responseCode = "404", description = "Project not found")
    })
    public ResponseEntity<DiaryEntryPageResponse> list(@PathVariable Long projectId,
                                                       @Parameter(description = "Zero-based page number", example = "0")
                                                       @RequestParam(required = false) Integer page,
                                                       @Parameter(description = "Page size", example = "20")
                                                       @RequestParam(required = false) Integer size,
                                                       Authentication auth) {
        return ResponseEntity.ok(service.list(projectId, page, size, resolveUserId(auth)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a diary entry", description = "Deletes a construction diary entry from the project.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Diary entry deleted"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to modify the project"),
            @ApiResponse(responseCode = "404", description = "Project or diary entry not found")
    })
    public ResponseEntity<Void> delete(@PathVariable Long projectId,
                                       @PathVariable Long id,
                                       Authentication auth) {
        service.delete(projectId, id, resolveUserId(auth));
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
