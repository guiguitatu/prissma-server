package br.pucpr.prissma_server.stage;

import br.pucpr.prissma_server.EmbeddedPostgresConfig;
import br.pucpr.prissma_server.projects.ConstructionProject;
import br.pucpr.prissma_server.projects.ConstructionProjectMember;
import br.pucpr.prissma_server.projects.ConstructionProjectMemberRepository;
import br.pucpr.prissma_server.projects.ConstructionProjectRepository;
import br.pucpr.prissma_server.users.Role;
import br.pucpr.prissma_server.users.User;
import br.pucpr.prissma_server.users.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@org.springframework.context.annotation.Import(EmbeddedPostgresConfig.class)
@DisplayName("StageController Integration Tests")
public class StageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StageRepository stageRepository;

    @Autowired
    private ConstructionProjectRepository projectRepository;

    @Autowired
    private ConstructionProjectMemberRepository memberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private br.pucpr.prissma_server.workspaces.WorkspaceRepository workspaceRepository;

    @Autowired
    private br.pucpr.prissma_server.workspaces.WorkspaceMemberRepository workspaceMemberRepository;

    private User ownerUser;
    private User engineerUser;
    private User foremanUser;
    private ConstructionProject project;

    private org.springframework.test.web.servlet.request.RequestPostProcessor auth(User user) {
        return authentication(new UsernamePasswordAuthenticationToken(
                user.getId(), null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));
    }

    private void addWorkspaceMember(br.pucpr.prissma_server.workspaces.Workspace workspace, User user) {
        var member = new br.pucpr.prissma_server.workspaces.WorkspaceMember();
        member.setWorkspace(workspace);
        member.setUserId(user.getId());
        member.setRole(br.pucpr.prissma_server.workspaces.WorkspaceRole.MEMBER);
        member.setAcceptedAt(Instant.now());
        member.setCreatedAt(Instant.now());
        member.setUpdatedAt(Instant.now());
        workspaceMemberRepository.save(member);
    }

    @BeforeEach
    void setUp() {
        // Create users
        ownerUser = new User();
        ownerUser.setEmail("owner" + System.nanoTime() + "@example.com");
        ownerUser.setName("Project Owner");
        ownerUser.setPassword("password123");
        ownerUser.setRole(Role.USER);
        ownerUser = userRepository.save(ownerUser);

        engineerUser = new User();
        engineerUser.setEmail("engineer" + System.nanoTime() + "@example.com");
        engineerUser.setName("Project Engineer");
        engineerUser.setPassword("password123");
        engineerUser.setRole(Role.USER);
        engineerUser = userRepository.save(engineerUser);

        foremanUser = new User();
        foremanUser.setEmail("foreman" + System.nanoTime() + "@example.com");
        foremanUser.setName("Project Foreman");
        foremanUser.setPassword("password123");
        foremanUser.setRole(Role.USER);
        foremanUser = userRepository.save(foremanUser);

        // Create workspace (toda obra pertence a um tenant desde a V14)
        br.pucpr.prissma_server.workspaces.Workspace workspace =
                new br.pucpr.prissma_server.workspaces.Workspace();
        workspace.setOwnerId(ownerUser.getId());
        workspace.setName("Workspace de Teste");
        workspace.setPrimary(true);
        workspace.setCreatedAt(Instant.now());
        workspace.setUpdatedAt(Instant.now());
        workspace = workspaceRepository.save(workspace);

        // engineer e foreman precisam de vínculo com o workspace (o
        // WorkspaceContextFilter resolve o contexto pela membership)
        addWorkspaceMember(workspace, engineerUser);
        addWorkspaceMember(workspace, foremanUser);

        // Create project
        project = new ConstructionProject();
        project.setWorkspaceId(workspace.getId());
        project.setTitle("Test Construction Project " + System.nanoTime());
        project.setStreet("Main Street");
        project.setNumber("100");
        project.setCity("Test City");
        project.setState("TS");
        project.setCep("12345-678");
        project.setProjectType("RESIDENTIAL");
        project.setCategory("HOUSE");
        project.setLandArea(BigDecimal.valueOf(250.0));
        project.setBuiltArea(BigDecimal.valueOf(180.0));
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(Instant.now());
        project = projectRepository.save(project);

        // Add owner member
        ConstructionProjectMember ownerMember = new ConstructionProjectMember();
        ownerMember.setConstructionProject(project);
        ownerMember.setUser(ownerUser);
        ownerMember.setRoleInProject("OWNER");
        ownerMember.setMembershipStatus("ACTIVE");
        ownerMember.setJoinedAt(Instant.now());
        memberRepository.save(ownerMember);

        // Add engineer member
        ConstructionProjectMember engineerMember = new ConstructionProjectMember();
        engineerMember.setConstructionProject(project);
        engineerMember.setUser(engineerUser);
        engineerMember.setRoleInProject("ENGINEER");
        engineerMember.setMembershipStatus("ACTIVE");
        engineerMember.setJoinedAt(Instant.now());
        memberRepository.save(engineerMember);

        // Add foreman member
        ConstructionProjectMember foremanMember = new ConstructionProjectMember();
        foremanMember.setConstructionProject(project);
        foremanMember.setUser(foremanUser);
        foremanMember.setRoleInProject("FOREMAN");
        foremanMember.setMembershipStatus("ACTIVE");
        foremanMember.setJoinedAt(Instant.now());
        memberRepository.save(foremanMember);
    }

    // ============= CREATE TESTS =============
    @Test
    @DisplayName("Should create stage with 201 Created and Location header")
    void testCreateStageSuccess() throws Exception {
        StageRequest request = new StageRequest(
                "Foundation",
                "Foundation work",
                1,
                "PLANNED",
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 7, 1),
                null,
                null
        );

        mockMvc.perform(post("/projects/" + project.getId() + "/stages")
                        .with(auth(ownerUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.name").value("Foundation"))
                .andExpect(jsonPath("$.displayOrder").value(1))
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    @DisplayName("Should create stage as ENGINEER")
    void testCreateStageAsEngineer() throws Exception {
        StageRequest request = new StageRequest(
                "Structure", "Structure work", 1, "PLANNED",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1),
                null, null
        );

        mockMvc.perform(post("/projects/" + project.getId() + "/stages")
                        .with(auth(engineerUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Structure"));
    }

    @Test
    @DisplayName("Should reject creation as FOREMAN with 403 Forbidden")
    void testCreateStageAsForeman() throws Exception {
        StageRequest request = new StageRequest(
                "Foundation", "desc", 1, "PLANNED",
                null, null, null, null
        );

        mockMvc.perform(post("/projects/" + project.getId() + "/stages")
                        .with(auth(foremanUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should reject creation with blank name")
    void testCreateStageBlankName() throws Exception {
        StageRequest request = new StageRequest(
                "", "desc", 1, "PLANNED",
                null, null, null, null
        );

        mockMvc.perform(post("/projects/" + project.getId() + "/stages")
                        .with(auth(ownerUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Should reject creation with null displayOrder")
    void testCreateStageNullDisplayOrder() throws Exception {
        StageRequest request = new StageRequest(
                "Foundation", "desc", null, "PLANNED",
                null, null, null, null
        );

        mockMvc.perform(post("/projects/" + project.getId() + "/stages")
                        .with(auth(ownerUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Should reject creation with negative displayOrder")
    void testCreateStageNegativeDisplayOrder() throws Exception {
        StageRequest request = new StageRequest(
                "Foundation", "desc", -1, "PLANNED",
                null, null, null, null
        );

        mockMvc.perform(post("/projects/" + project.getId() + "/stages")
                        .with(auth(ownerUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Should reject creation with duplicate displayOrder")
    void testCreateStageDuplicateDisplayOrder() throws Exception {
        // Create first stage
        Stage stage1 = new Stage();
        stage1.setName("Foundation");
        stage1.setDisplayOrder(1);
        stage1.setStatus("PLANNED");
        stage1.setConstructionProject(project);
        stage1.setCreatedAt(Instant.now());
        stage1.setUpdatedAt(Instant.now());
        stageRepository.save(stage1);

        // Try to create another with same displayOrder
        StageRequest request = new StageRequest(
                "Structure", "desc", 1, "PLANNED",
                null, null, null, null
        );

        mockMvc.perform(post("/projects/" + project.getId() + "/stages")
                        .with(auth(ownerUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Should reject creation with invalid planned dates")
    void testCreateStageInvalidDates() throws Exception {
        StageRequest request = new StageRequest(
                "Foundation", "desc", 1, "PLANNED",
                LocalDate.of(2026, 7, 1),  // after
                LocalDate.of(2026, 6, 1),  // before
                null, null
        );

        mockMvc.perform(post("/projects/" + project.getId() + "/stages")
                        .with(auth(ownerUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should reject creation for non-existent project with 404")
    void testCreateStageProjectNotFound() throws Exception {
        StageRequest request = new StageRequest(
                "Foundation", "desc", 1, "PLANNED",
                null, null, null, null
        );

        // Antes do escopo de workspace isto era 403 (o requirePermission não
        // validava a existência da obra); agora id fantasma é 404 genérico.
        mockMvc.perform(post("/projects/99999/stages")
                        .with(auth(ownerUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    // ============= LIST TESTS =============
    @Test
    @DisplayName("Should list all stages in project ordered by displayOrder")
    void testListStagesByProject() throws Exception {
        // Create multiple stages
        Stage stage1 = new Stage();
        stage1.setName("Foundation");
        stage1.setDisplayOrder(1);
        stage1.setStatus("PLANNED");
        stage1.setConstructionProject(project);
        stage1.setCreatedAt(Instant.now());
        stage1.setUpdatedAt(Instant.now());
        stageRepository.save(stage1);

        Stage stage2 = new Stage();
        stage2.setName("Structure");
        stage2.setDisplayOrder(2);
        stage2.setStatus("PLANNED");
        stage2.setConstructionProject(project);
        stage2.setCreatedAt(Instant.now());
        stage2.setUpdatedAt(Instant.now());
        stageRepository.save(stage2);

        mockMvc.perform(get("/projects/" + project.getId() + "/stages")
                        .with(auth(ownerUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Foundation"))
                .andExpect(jsonPath("$[1].name").value("Structure"))
                .andExpect(jsonPath("$[0].displayOrder").value(1))
                .andExpect(jsonPath("$[1].displayOrder").value(2));
    }

    @Test
    @DisplayName("Should return empty list when project has no stages")
    void testListStagesEmptyProject() throws Exception {
        mockMvc.perform(get("/projects/" + project.getId() + "/stages")
                        .with(auth(ownerUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("Should reject list for non-existent project")
    void testListStagesProjectNotFound() throws Exception {
        mockMvc.perform(get("/projects/99999/stages")
                        .with(auth(ownerUser)))
                .andExpect(status().isNotFound());
    }

    // ============= GET TESTS =============
    @Test
    @DisplayName("Should get stage by id successfully")
    void testGetStageById() throws Exception {
        Stage stage = new Stage();
        stage.setName("Foundation");
        stage.setDisplayOrder(1);
        stage.setStatus("PLANNED");
        stage.setDescription("Test description");
        stage.setConstructionProject(project);
        stage.setCreatedAt(Instant.now());
        stage.setUpdatedAt(Instant.now());
        stage = stageRepository.save(stage);

        mockMvc.perform(get("/stages/" + stage.getId())
                        .with(auth(ownerUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(stage.getId()))
                .andExpect(jsonPath("$.name").value("Foundation"))
                .andExpect(jsonPath("$.description").value("Test description"));
    }

    @Test
    @DisplayName("Should reject get for non-existent stage")
    void testGetStageNotFound() throws Exception {
        mockMvc.perform(get("/stages/99999")
                        .with(auth(ownerUser)))
                .andExpect(status().isNotFound());
    }

    // ============= UPDATE TESTS =============
    @Test
    @DisplayName("Should update stage successfully")
    void testUpdateStage() throws Exception {
        Stage stage = new Stage();
        stage.setName("Foundation");
        stage.setDisplayOrder(1);
        stage.setStatus("PLANNED");
        stage.setConstructionProject(project);
        stage.setCreatedAt(Instant.now());
        stage.setUpdatedAt(Instant.now());
        stage = stageRepository.save(stage);

        StageRequest updateRequest = new StageRequest(
                "Updated Foundation", "Updated desc", null, "IN_PROGRESS",
                null, null, null, null
        );

        mockMvc.perform(patch("/stages/" + stage.getId())
                        .with(auth(ownerUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Foundation"))
                .andExpect(jsonPath("$.description").value("Updated desc"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    @DisplayName("Should reject update as FOREMAN")
    void testUpdateStageAsForeman() throws Exception {
        Stage stage = new Stage();
        stage.setName("Foundation");
        stage.setDisplayOrder(1);
        stage.setConstructionProject(project);
        stage.setCreatedAt(Instant.now());
        stage.setUpdatedAt(Instant.now());
        stage = stageRepository.save(stage);

        StageRequest updateRequest = new StageRequest(
                "Updated", null, null, null, null, null, null, null
        );

        mockMvc.perform(patch("/stages/" + stage.getId())
                        .with(auth(foremanUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should reject update for non-existent stage")
    void testUpdateStageNotFound() throws Exception {
        StageRequest updateRequest = new StageRequest(
                "Updated", null, null, null, null, null, null, null
        );

        mockMvc.perform(patch("/stages/99999")
                        .with(auth(ownerUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Should update displayOrder successfully")
    void testUpdateStageDisplayOrder() throws Exception {
        Stage stage = new Stage();
        stage.setName("Foundation");
        stage.setDisplayOrder(1);
        stage.setConstructionProject(project);
        stage.setCreatedAt(Instant.now());
        stage.setUpdatedAt(Instant.now());
        stage = stageRepository.save(stage);

        StageRequest updateRequest = new StageRequest(
                null, null, 2, null, null, null, null, null
        );

        mockMvc.perform(patch("/stages/" + stage.getId())
                        .with(auth(ownerUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayOrder").value(2));
    }

    // ============= DELETE TESTS =============
    @Test
    @DisplayName("Should delete stage successfully with 204 No Content")
    void testDeleteStage() throws Exception {
        Stage stage = new Stage();
        stage.setName("Foundation");
        stage.setDisplayOrder(1);
        stage.setConstructionProject(project);
        stage.setCreatedAt(Instant.now());
        stage.setUpdatedAt(Instant.now());
        stage = stageRepository.save(stage);

        mockMvc.perform(delete("/stages/" + stage.getId())
                        .with(auth(ownerUser)))
                .andExpect(status().isNoContent());

        // Verify stage is deleted
        mockMvc.perform(get("/stages/" + stage.getId())
                        .with(auth(ownerUser)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Should reject delete as FOREMAN")
    void testDeleteStageAsForeman() throws Exception {
        Stage stage = new Stage();
        stage.setName("Foundation");
        stage.setDisplayOrder(1);
        stage.setConstructionProject(project);
        stage.setCreatedAt(Instant.now());
        stage.setUpdatedAt(Instant.now());
        stage = stageRepository.save(stage);

        mockMvc.perform(delete("/stages/" + stage.getId())
                        .with(auth(foremanUser)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should reject delete for non-existent stage")
    void testDeleteStageNotFound() throws Exception {
        mockMvc.perform(delete("/stages/99999")
                        .with(auth(ownerUser)))
                .andExpect(status().isNotFound());
    }

    // ============= REORDER TESTS =============
    @Test
    @DisplayName("Should reorder stages successfully")
    void testReorderStages() throws Exception {
        // Create stages
        Stage stage1 = new Stage();
        stage1.setName("Foundation");
        stage1.setDisplayOrder(1);
        stage1.setConstructionProject(project);
        stage1.setCreatedAt(Instant.now());
        stage1.setUpdatedAt(Instant.now());
        stage1 = stageRepository.save(stage1);

        Stage stage2 = new Stage();
        stage2.setName("Structure");
        stage2.setDisplayOrder(2);
        stage2.setConstructionProject(project);
        stage2.setCreatedAt(Instant.now());
        stage2.setUpdatedAt(Instant.now());
        stage2 = stageRepository.save(stage2);

        // Reorder
        List<Long> reorderedIds = List.of(stage2.getId(), stage1.getId());

        mockMvc.perform(post("/projects/" + project.getId() + "/stages/reorder")
                        .with(auth(ownerUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reorderedIds)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Stages reordered successfully"))
                .andExpect(jsonPath("$.stages.length()").value(2))
                .andExpect(jsonPath("$.stages[0].id").value(stage2.getId()))
                .andExpect(jsonPath("$.stages[0].displayOrder").value(1))
                .andExpect(jsonPath("$.stages[1].id").value(stage1.getId()))
                .andExpect(jsonPath("$.stages[1].displayOrder").value(2));

        // Verify order changed
        mockMvc.perform(get("/stages/" + stage2.getId())
                        .with(auth(ownerUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayOrder").value(1));

        mockMvc.perform(get("/stages/" + stage1.getId())
                        .with(auth(ownerUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayOrder").value(2));
    }

    @Test
    @DisplayName("Should reject reorder as FOREMAN")
    void testReorderStagesAsForeman() throws Exception {
        Stage stage = new Stage();
        stage.setName("Foundation");
        stage.setDisplayOrder(1);
        stage.setConstructionProject(project);
        stage.setCreatedAt(Instant.now());
        stage.setUpdatedAt(Instant.now());
        stage = stageRepository.save(stage);

        List<Long> reorderedIds = List.of(stage.getId());

        mockMvc.perform(post("/projects/" + project.getId() + "/stages/reorder")
                        .with(auth(foremanUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reorderedIds)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should reject reorder for non-existent project")
    void testReorderStagesProjectNotFound() throws Exception {
        List<Long> reorderedIds = List.of(1L);

        mockMvc.perform(post("/projects/99999/stages/reorder")
                        .with(auth(ownerUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reorderedIds)))
                .andExpect(status().isNotFound());
    }
}
