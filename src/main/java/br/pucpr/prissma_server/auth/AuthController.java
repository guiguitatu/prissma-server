package br.pucpr.prissma_server.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication", description = "Public authentication and password recovery operations.")
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @Operation(
            summary = "Login",
            description = "Authenticates a user with email and password and returns a JWT containing identity claims and, when applicable, the active workspace context."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authentication successful and JWT returned"),
            @ApiResponse(responseCode = "400", description = "Invalid login request"),
            @ApiResponse(responseCode = "401", description = "Invalid email or password")
    })
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(service.login(request.email(), request.password()));
    }

    @Operation(
            summary = "Request a password reset",
            description = "Generates a password-reset token valid for 15 minutes and sends the reset link by email. For account-enumeration protection, the endpoint returns 200 even when the email is not registered."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request processed; email is sent when the account exists"),
            @ApiResponse(responseCode = "400", description = "Invalid password-reset request")
    })
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        service.forgotPassword(request.email());
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "Reset password",
            description = "Changes the user password using the one-time token received by email. Reset tokens expire 15 minutes after creation."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password reset successfully"),
            @ApiResponse(responseCode = "400", description = "Token is invalid or expired, or the new password does not satisfy validation rules")
    })
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@RequestBody ResetPasswordRequest request) {
        service.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.ok().build();
    }
}
