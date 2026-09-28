package br.pucpr.prissma_server;

import br.pucpr.prissma_server.projects.ConstructionProject;
import br.pucpr.prissma_server.projects.ConstructionProjectMember;
import br.pucpr.prissma_server.projects.ConstructionProjectMemberRepository;
import br.pucpr.prissma_server.projects.ConstructionProjectRepository;
import br.pucpr.prissma_server.stage.Stage;
import br.pucpr.prissma_server.stage.StageRepository;
import br.pucpr.prissma_server.users.Role;
import br.pucpr.prissma_server.users.User;
import br.pucpr.prissma_server.users.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de isolamento por recurso: o usuário B (autenticado, mas NÃO membro)
 * tenta alcançar cada recurso da obra do usuário A e é barrado em todos.
 *
 * Com o escopo de workspace ativo, o não-pertencimento cross-workspace é
 * SEMPRE 404 genérico — nunca 403, para não revelar a existência de obras de
 * outras construtoras (anti-enumeração). Este é o teste que pega cada
 * regressão de escopo de tenant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(EmbeddedPostgresConfig.class)
@DisplayName("Resource isolation: non-member cannot reach another user's project resources")
public class ResourceIsolationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConstructionProjectRepository projectRepository;

    @Autowired
    private ConstructionProjectMemberRepository memberRepository;

    @Autowired
    private StageRepository stageRepository;

    @Autowired
    private br.pucpr.prissma_server.workspaces.WorkspaceRepository workspaceRepository;

    private User owner;
    private User outsider;
    private ConstructionProject project;
    private Stage stage;

    private org.springframework.test.web.servlet.request.RequestPostProcessor auth(User user) {
        return authentication(new UsernamePasswordAuthenticationToken(
                user.getId(), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setEmail("owner" + System.nanoTime() + "@example.com");
        owner.setName("Owner A");
        owner.setPassword("password123");
        owner.setRole(Role.USER);
        owner = userRepository.save(owner);

        outsider = new User();
        outsider.setEmail("outsider" + System.nanoTime() + "@example.com");
        outsider.setName("Outsider B");
        outsider.setPassword("password123");
        outsider.setRole(Role.USER);
        outsider = userRepository.save(outsider);

        br.pucpr.prissma_server.workspaces.Workspace workspace =
                new br.pucpr.prissma_server.workspaces.Workspace();
        workspace.setOwnerId(owner.getId());
        workspace.setName("Workspace A");
        workspace.setPrimary(true);
        workspace.setCreatedAt(Instant.now());
        workspace.setUpdatedAt(Instant.now());
        workspace = workspaceRepository.save(workspace);

        project = new ConstructionProject();
        project.setWorkspaceId(workspace.getId());
        project.setTitle("Obra Isolada " + System.nanoTime());
        project.setStreet("Rua A");
        project.setNumber("1");
        project.setCity("Curitiba");
        project.setState("PR");
        project.setCep("80000-000");
        project.setProjectType("RESIDENTIAL");
        project.setCategory("HOUSE");
        project.setLandArea(BigDecimal.valueOf(100));
        project.setBuiltArea(BigDecimal.valueOf(80));
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(Instant.now());
        project = projectRepository.save(project);

        ConstructionProjectMember ownerMember = new ConstructionProjectMember();
        ownerMember.setConstructionProject(project);
        ownerMember.setUser(owner);
        ownerMember.setRoleInProject("OWNER");
        ownerMember.setMembershipStatus("ACTIVE");
        ownerMember.setJoinedAt(Instant.now());
        memberRepository.save(ownerMember);

        stage = new Stage();
        stage.setName("Fundacao");
        stage.setDisplayOrder(1);
        stage.setStatus("PLANNED");
        stage.setConstructionProject(project);
        stage.setCreatedAt(Instant.now());
        stage.setUpdatedAt(Instant.now());
        stage = stageRepository.save(stage);
    }

    @Test
    @DisplayName("Sanity: the owner reaches their own project")
    void ownerCanReadOwnProject() throws Exception {
        mockMvc.perform(get("/projects/" + project.getId()).with(auth(owner)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /projects/{id} is denied to a non-member")
    void getProjectDenied() throws Exception {
        mockMvc.perform(get("/projects/" + project.getId()).with(auth(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH /projects/{id} is denied to a non-member")
    void patchProjectDenied() throws Exception {
        mockMvc.perform(patch("/projects/" + project.getId())
                        .with(auth(outsider))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /projects/{id} is denied to a non-member")
    void deleteProjectDenied() throws Exception {
        mockMvc.perform(delete("/projects/" + project.getId()).with(auth(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /projects/{id}/members is denied to a non-member")
    void getMembersDenied() throws Exception {
        mockMvc.perform(get("/projects/" + project.getId() + "/members").with(auth(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /projects/{id}/stages is denied to a non-member")
    void listStagesDenied() throws Exception {
        mockMvc.perform(get("/projects/" + project.getId() + "/stages").with(auth(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /stages/{id} is denied to a non-member")
    void getStageDenied() throws Exception {
        mockMvc.perform(get("/stages/" + stage.getId()).with(auth(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /stages/{stageId}/tasks is denied to a non-member")
    void listTasksDenied() throws Exception {
        mockMvc.perform(get("/stages/" + stage.getId() + "/tasks").with(auth(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /projects/{id}/budget is denied to a non-member (before the budget lookup)")
    void getBudgetDenied() throws Exception {
        mockMvc.perform(get("/projects/" + project.getId() + "/budget").with(auth(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /projects/{id}/attachments is denied to a non-member")
    void listAttachmentsDenied() throws Exception {
        mockMvc.perform(get("/projects/" + project.getId() + "/attachments").with(auth(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /projects/{id}/proposals is denied to a non-member")
    void listProposalsDenied() throws Exception {
        mockMvc.perform(get("/projects/" + project.getId() + "/proposals").with(auth(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /projects/{id}/proposals is denied to a non-member")
    void createProposalDenied() throws Exception {
        var proposta = new org.springframework.mock.web.MockMultipartFile(
                "proposal", "", MediaType.APPLICATION_JSON_VALUE,
                ("{\"title\":\"Sala de estar\",\"environmentType\":\"LIVING_ROOM\"}").getBytes());

        mockMvc.perform(multipart("/projects/" + project.getId() + "/proposals")
                        .file(proposta)
                        .with(auth(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Cross-workspace: outsider WITH their own workspace still gets 404")
    void crossWorkspaceOutsiderWithOwnWorkspaceGets404() throws Exception {
        // O caso forte: B tem construtora própria (contexto válido) e mesmo
        // assim a obra de A não existe para ele — 404 genérico, nunca 403.
        var workspaceB = new br.pucpr.prissma_server.workspaces.Workspace();
        workspaceB.setOwnerId(outsider.getId());
        workspaceB.setName("Workspace B");
        workspaceB.setPrimary(true);
        workspaceB.setCreatedAt(Instant.now());
        workspaceB.setUpdatedAt(Instant.now());
        workspaceRepository.save(workspaceB);

        mockMvc.perform(get("/projects/" + project.getId()).with(auth(outsider)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/projects/" + project.getId() + "/stages").with(auth(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Stale X-Workspace-Id header (someone else's workspace) does not grant access")
    void staleWorkspaceHeaderDoesNotGrantAccess() throws Exception {
        // Header apontando para o workspace de A: B não tem acesso a ele, o
        // filtro descarta o candidato e, sem fallback, responde 404 genérico.
        mockMvc.perform(get("/projects/" + project.getId())
                        .header("X-Workspace-Id", project.getWorkspaceId().toString())
                        .with(auth(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /projects does not leak another user's project")
    void listProjectsDoesNotLeak() throws Exception {
        mockMvc.perform(get("/projects").with(auth(outsider)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[?(@.id == " + project.getId() + ")]").isEmpty());
    }
}
