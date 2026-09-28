package br.pucpr.prissma_server.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A seed roda contra um Postgres PRÓPRIO, não o container compartilhado de
 * {@code TestcontainersConfig}: ela commita dados de verdade, e no container
 * compartilhado eles vazariam para as outras classes da suíte.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "seed"})
@Import(DevDataSeederTest.SeedPostgres.class)
@DisplayName("DevDataSeeder")
class DevDataSeederTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class SeedPostgres {
        private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

        static {
            POSTGRES.start();
        }

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> seedPostgresContainer() {
            return POSTGRES;
        }
    }

    private static final Path STORAGE_ROOT;

    static {
        try {
            STORAGE_ROOT = Files.createTempDirectory("prissma-seed-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("app.storage.local.root", STORAGE_ROOT::toString);
    }

    @Autowired
    private DevDataSeeder seeder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("popula todos os módulos e pode rodar de novo sem duplicar nem vazar arquivos")
    void seedsEverythingAndIsIdempotent() throws Exception {
        Map<String, Integer> first = counts();
        long firstFiles = countFiles();

        assertThat(first).allSatisfy((table, count) -> assertThat(count).as(table).isPositive());

        seeder.run(null);

        assertThat(counts()).isEqualTo(first);
        assertThat(countFiles()).isEqualTo(firstFiles);
    }

    @Test
    @DisplayName("usuário da seed loga e enxerga as obras e as imagens pela API")
    void seededUserCanUseTheApi() throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"carla" + DevDataSeeder.EMAIL_DOMAIN
                                + "\",\"password\":\"" + DevDataSeeder.PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = "Bearer " + objectMapper.readTree(body).get("token").asText();

        JsonNode projects = objectMapper.readTree(mockMvc.perform(get("/projects").header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(projects).hasSize(7);

        Map<String, Object> version = jdbc.queryForMap("""
                SELECT p.construction_project_id AS project_id, p.id AS proposal_id, s.id AS version_id
                  FROM design_submissions s JOIN design_proposals p ON p.id = s.proposal_id
                 WHERE p.title = 'Sala de estar integrada' AND s.version = 2""");
        mockMvc.perform(get("/projects/{p}/proposals/{id}/versions/{v}/image",
                        version.get("project_id"), version.get("proposal_id"), version.get("version_id"))
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG));

        long projectId = ((Number) version.get("project_id")).longValue();
        mockMvc.perform(get("/projects/{id}/budget", projectId).header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/projects/{id}/schedule", projectId).param("view", "WEEK")
                        .header("Authorization", token))
                .andExpect(status().isOk());
    }

    private Map<String, Integer> counts() {
        return Stream.of("users", "workspaces", "workspace_members", "member_invites", "construction_projects",
                        "construction_project_members", "project_role_permissions", "stages", "tasks",
                        "project_budgets", "budget_items", "expenses", "attachments",
                        "construction_diary_entries", "design_proposals", "design_submissions",
                        "design_approvals", "ai_environment_previews", "schedule_members", "schedule_allocations")
                .collect(java.util.stream.Collectors.toMap(t -> t,
                        t -> jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Integer.class)));
    }

    private long countFiles() throws IOException {
        try (Stream<Path> files = Files.walk(STORAGE_ROOT)) {
            return files.filter(Files::isRegularFile).count();
        }
    }
}
