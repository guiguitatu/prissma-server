package br.pucpr.prissma_server.genai;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/environment-previews")
@Tag(name = "AI Environment Preview", description = "AI-assisted environment preview generation from project images and optional floor plans.")
@SecurityRequirement(name = "bearerAuth")
public class EnvironmentPreviewController {

    private final EnvironmentPreviewService service;

    public EnvironmentPreviewController(EnvironmentPreviewService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Generate an AI environment preview",
            description = "Generates a PNG environment preview from a raw image, an optional floor plan and rendering options.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Preview image generated",
                    content = @Content(mediaType = MediaType.IMAGE_PNG_VALUE,
                            schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "400", description = "Invalid image, floor plan or preview options"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "413", description = "Uploaded image exceeds the configured size limit"),
            @ApiResponse(responseCode = "502", description = "AI image provider failed to generate the preview")
    })
    public ResponseEntity<byte[]> generate(
            @RequestPart("rawImage") MultipartFile rawImage,
            @RequestPart(value = "floorPlan", required = false) MultipartFile floorPlan,
            @Valid @RequestPart("options") EnvironmentPreviewRequest options) {
        byte[] image = service.generate(rawImage, floorPlan, options);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(image);
    }
}
