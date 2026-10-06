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

@Tag(name = "Auth", description = "Autenticação e recuperação de senha")
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @Operation(
            summary = "Login",
            description = "Autentica o usuário com email e senha e retorna um JWT contendo os claims " +
                    "de identidade (subject, email, role) e, quando aplicável, o contexto do workspace " +
                    "(workspaceId, workspaceRole, isOwner)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login efetuado com sucesso, token retornado"),
            @ApiResponse(responseCode = "401", description = "Email ou senha inválidos")
    })
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(service.login(request.email(), request.password()));
    }

    @Operation(
            summary = "Solicitar redefinição de senha",
            description = "Gera um token de redefinição de senha com validade de 15 minutos e envia um " +
                    "email com o link de reset. Por segurança, sempre retorna 200 mesmo se o email não " +
                    "estiver cadastrado, para não expor quais emails existem na base."
    )
    @ApiResponse(responseCode = "200", description = "Requisição processada (email enviado, se existir usuário com o email informado)")
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        service.forgotPassword(request.email());
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "Redefinir senha",
            description = "Efetiva a troca de senha a partir do token recebido por email. O token é " +
                    "de uso único e expira 15 minutos após sua criação."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Senha redefinida com sucesso"),
            @ApiResponse(responseCode = "400", description = "Token inválido, expirado ou nova senha fora das regras de validação")
    })
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@RequestBody ResetPasswordRequest request) {
        service.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.ok().build();
    }
}
