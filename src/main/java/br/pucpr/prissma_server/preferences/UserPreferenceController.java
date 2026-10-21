package br.pucpr.prissma_server.preferences;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/me/preferences")
@Tag(name = "Preferences", description = "UI preferences of the authenticated user, such as the project overview layout.")
@SecurityRequirement(name = "bearerAuth")
public class UserPreferenceController {

    private final UserPreferenceService service;

    public UserPreferenceController(UserPreferenceService service) {
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

    @GetMapping("/{key}")
    @Operation(summary = "Get a preference",
            description = "Returns the saved value. 204 means the user never saved it and the client should use its default.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Preference returned"),
            @ApiResponse(responseCode = "204", description = "Preference never saved"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "Unknown preference key")
    })
    public ResponseEntity<PreferenceResponse> get(
            @Parameter(description = "Preference key", example = "obra.visao-geral.layout") @PathVariable String key,
            Authentication auth) {
        return service.get(resolveUserId(auth), key)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/{key}")
    @Operation(summary = "Save a preference",
            description = "Creates or replaces the value. The body is validated against the format of the key.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Preference saved"),
            @ApiResponse(responseCode = "400", description = "Invalid value for this key"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "Unknown preference key"),
            @ApiResponse(responseCode = "413", description = "Value too large")
    })
    public ResponseEntity<PreferenceResponse> put(
            @Parameter(description = "Preference key", example = "obra.visao-geral.layout") @PathVariable String key,
            @RequestBody JsonNode value,
            Authentication auth) {
        return ResponseEntity.ok(service.put(resolveUserId(auth), key, value));
    }

    @DeleteMapping("/{key}")
    @Operation(summary = "Reset a preference",
            description = "Deletes the saved value so the client falls back to its default. Idempotent.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Preference reset"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "Unknown preference key")
    })
    public ResponseEntity<Void> delete(
            @Parameter(description = "Preference key", example = "obra.visao-geral.layout") @PathVariable String key,
            Authentication auth) {
        service.delete(resolveUserId(auth), key);
        return ResponseEntity.noContent().build();
    }

    /**
     * Body que nem é JSON. Sem este handler local, o handler genérico do
     * ExceptionHandlerAdvice responderia 500 para um erro do cliente.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body("Corpo da requisição não é um JSON válido");
    }
}
