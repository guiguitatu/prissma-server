package br.pucpr.prissma_server.utils;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI prissmaOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("PRISSMA API")
                        .description("REST API for PRISSMA, a construction project management platform covering projects, stages, tasks, budgets, schedules, attachments, design proposals, reports, workspaces and AI-assisted environment previews.")
                        .version("1.0.0"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .name("bearerAuth")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT access token returned by POST /auth/login.")));
    }
}
