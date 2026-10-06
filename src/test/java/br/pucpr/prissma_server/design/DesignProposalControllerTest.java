package br.pucpr.prissma_server.design;

import br.pucpr.prissma_server.EmbeddedPostgresConfig;
import br.pucpr.prissma_server.projects.ConstructionProject;
import br.pucpr.prissma_server.projects.ConstructionProjectMember;
import br.pucpr.prissma_server.projects.ConstructionProjectMemberRepository;
import br.pucpr.prissma_server.projects.ConstructionProjectRepository;
import br.pucpr.prissma_server.users.Role;
import br.pucpr.prissma_server.users.User;
import br.pucpr.prissma_server.users.UserRepository;
import br.pucpr.prissma_server.workspaces.Workspace;
import br.pucpr.prissma_server.workspaces.WorkspaceMember;
import br.pucpr.prissma_server.workspaces.WorkspaceMemberRepository;
import br.pucpr.prissma_server.workspaces.WorkspaceRepository;
import br.pucpr.prissma_server.workspaces.WorkspaceRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integração das rotas de Propostas.
 *
 * A prévia por IA não entra aqui: o disparo chamaria a OpenAI de verdade. O job
 * é coberto por {@link EnvironmentPreviewJobServiceTest}, com o gateway mockado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(EmbeddedPostgresConfig.class)
@DisplayName("DesignProposalController Integration Tests")
public class DesignProposalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConstructionProjectRepository projectRepository;

    @Autowired
    private ConstructionProjectMemberRepository memberRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;

    @Autowired
    private EntityManager entityManager;

    private User arquiteto;
    private User cliente;
    private ConstructionProject project;

    private RequestPostProcessor auth(User user) {
        return authentication(new UsernamePasswordAuthenticationToken(
                user.getId(), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private User novoUsuario(String prefixo) {
        User user = new User();
        user.setEmail(prefixo + System.nanoTime() + "@example.com");
        user.setName(prefixo.substring(0, 1).toUpperCase() + prefixo.substring(1));
        user.setPassword("password123");
        user.setRole(Role.USER);
        return userRepository.save(user);
    }

    private void addWorkspaceMember(Workspace workspace, User user) {
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspace(workspace);
        member.setUserId(user.getId());
        member.setRole(WorkspaceRole.MEMBER);
        member.setAcceptedAt(Instant.now());
        member.setCreatedAt(Instant.now());
        member.setUpdatedAt(Instant.now());
        workspaceMemberRepository.save(member);
    }

    private void addProjectMember(User user, String papel) {
        ConstructionProjectMember member = new ConstructionProjectMember();
        member.setConstructionProject(project);
        member.setUser(user);
        member.setRoleInProject(papel);
        member.setMembershipStatus("ACTIVE");
        member.setJoinedAt(Instant.now());
        memberRepository.save(member);
    }

    private MockMultipartFile parteProposta(String json) {
        return new MockMultipartFile("proposal", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes());
    }

    private static final String PROPOSTA_JSON = """
            {"title":"Sala de estar","description":"Painel ripado atras da TV","environmentType":"LIVING_ROOM"}
            """;

    @BeforeEach
    void setUp() {
        User dono = novoUsuario("dono");
        arquiteto = novoUsuario("arquiteto");
        cliente = novoUsuario("cliente");

        Workspace workspace = new Workspace();
        workspace.setOwnerId(dono.getId());
        workspace.setName("Construtora de Teste");
        workspace.setPrimary(true);
        workspace.setCreatedAt(Instant.now());
        workspace.setUpdatedAt(Instant.now());
        workspace = workspaceRepository.save(workspace);

        addWorkspaceMember(workspace, arquiteto);
        addWorkspaceMember(workspace, cliente);

        project = new ConstructionProject();
        project.setWorkspaceId(workspace.getId());
        project.setTitle("Vila Nova " + System.nanoTime());
        project.setStreet("Rua das Obras");
        project.setNumber("100");
        project.setCity("Curitiba");
        project.setState("PR");
        project.setCep("80000-000");
        project.setProjectType("RESIDENTIAL");
        project.setCategory("HOUSE");
        project.setLandArea(BigDecimal.valueOf(250.0));
        project.setBuiltArea(BigDecimal.valueOf(180.0));
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(Instant.now());
        project = projectRepository.save(project);

        addProjectMember(dono, "OWNER");
        addProjectMember(arquiteto, "ARCHITECT");
        addProjectMember(cliente, "USER");
    }

    private Long criarProposta() throws Exception {
        String body = mockMvc.perform(multipart("/projects/" + project.getId() + "/proposals")
                        .file(parteProposta(PROPOSTA_JSON))
                        .with(auth(arquiteto)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.valueOf(body.replaceAll(".*?\"id\":(\\d+).*", "$1"));
    }

    @Test
    @DisplayName("arquiteto cria proposta e ela ja nasce com a v1 em rascunho")
    void criaPropostaComPrimeiraVersao() throws Exception {
        mockMvc.perform(multipart("/projects/" + project.getId() + "/proposals")
                        .file(parteProposta(PROPOSTA_JSON))
                        .with(auth(arquiteto)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.title", is("Sala de estar")))
                .andExpect(jsonPath("$.environmentType", is("LIVING_ROOM")))
                .andExpect(jsonPath("$.versionCount", is(1)))
                .andExpect(jsonPath("$.latestVersion.version", is(1)))
                .andExpect(jsonPath("$.latestVersion.status", is("DRAFT")))
                .andExpect(jsonPath("$.latestVersion.hasImage", is(false)))
                .andExpect(jsonPath("$.latestVersion.generatedByAi", is(false)));
    }

    @Test
    @DisplayName("titulo repetido na mesma obra responde 409")
    void tituloRepetidoResponde409() throws Exception {
        criarProposta();

        mockMvc.perform(multipart("/projects/" + project.getId() + "/proposals")
                        .file(parteProposta(PROPOSTA_JSON))
                        .with(auth(arquiteto)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("ambiente fora do enum responde 400")
    void ambienteInvalidoResponde400() throws Exception {
        String json = """
                {"title":"Varanda","environmentType":"VARANDA_GOURMET"}
                """;

        mockMvc.perform(multipart("/projects/" + project.getId() + "/proposals")
                        .file(parteProposta(json))
                        .with(auth(arquiteto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("listagem devolve o card com a versao mais recente")
    void listagemDevolveCards() throws Exception {
        criarProposta();

        mockMvc.perform(get("/projects/" + project.getId() + "/proposals").with(auth(arquiteto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].title", is("Sala de estar")))
                .andExpect(jsonPath("$.content[0].latestVersion.version", is(1)))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    @DisplayName("upload de nova versao numera v2 e guarda a imagem")
    void novaVersaoNumeraV2() throws Exception {
        Long proposalId = criarProposta();

        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01};
        var arquivo = new MockMultipartFile("file", "planta.png", "image/png", png);

        mockMvc.perform(multipart("/projects/" + project.getId() + "/proposals/" + proposalId + "/versions")
                        .file(arquivo)
                        .param("description", "ajuste do painel")
                        .with(auth(arquiteto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version", is(2)))
                .andExpect(jsonPath("$.hasImage", is(true)))
                .andExpect(jsonPath("$.fileName", is("planta.png")));
    }

    @Test
    @DisplayName("arquivo cujo conteudo nao bate com o tipo declarado responde 415")
    void arquivoComTipoMentirosoResponde415() throws Exception {
        Long proposalId = criarProposta();

        var arquivo = new MockMultipartFile("file", "falso.png", "image/png",
                "isto nao e um png".getBytes());

        mockMvc.perform(multipart("/projects/" + project.getId() + "/proposals/" + proposalId + "/versions")
                        .file(arquivo)
                        .with(auth(arquiteto)))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("aprovar uma versao muda o status do card")
    void aprovarVersao() throws Exception {
        Long proposalId = criarProposta();

        String detalhe = mockMvc.perform(
                        get("/projects/" + project.getId() + "/proposals/" + proposalId)
                                .with(auth(arquiteto)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long versionId = Long.valueOf(detalhe.replaceAll(".*\"versions\":\\[\\{\"id\":(\\d+).*", "$1"));

        mockMvc.perform(patch("/projects/" + project.getId() + "/proposals/" + proposalId
                        + "/versions/" + versionId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\",\"comment\":\"Pode seguir\"}")
                        .with(auth(arquiteto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("APPROVED")));
    }

    @Test
    @DisplayName("versao sem imagem responde 404 no endpoint de imagem")
    void imagemDeVersaoSemArquivoResponde404() throws Exception {
        Long proposalId = criarProposta();

        String detalhe = mockMvc.perform(
                        get("/projects/" + project.getId() + "/proposals/" + proposalId)
                                .with(auth(arquiteto)))
                .andReturn().getResponse().getContentAsString();
        Long versionId = Long.valueOf(detalhe.replaceAll(".*\"versions\":\\[\\{\"id\":(\\d+).*", "$1"));

        mockMvc.perform(get("/projects/" + project.getId() + "/proposals/" + proposalId
                        + "/versions/" + versionId + "/image").with(auth(arquiteto)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("cliente le a grade mas nao cria proposta")
    void clienteSomenteLeitura() throws Exception {
        criarProposta();

        mockMvc.perform(get("/projects/" + project.getId() + "/proposals").with(auth(cliente)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));

        mockMvc.perform(multipart("/projects/" + project.getId() + "/proposals")
                        .file(parteProposta("""
                                {"title":"Cozinha","environmentType":"KITCHEN"}
                                """))
                        .with(auth(cliente)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("proposta de outra obra nao e alcancavel pela rota desta obra")
    void propostaDeOutraObraResponde404() throws Exception {
        Long proposalId = criarProposta();

        ConstructionProject outra = new ConstructionProject();
        outra.setWorkspaceId(project.getWorkspaceId());
        outra.setTitle("Outra obra " + System.nanoTime());
        outra.setStreet("Rua B");
        outra.setNumber("2");
        outra.setCity("Curitiba");
        outra.setState("PR");
        outra.setCep("80000-000");
        outra.setProjectType("RESIDENTIAL");
        outra.setCategory("HOUSE");
        outra.setLandArea(BigDecimal.valueOf(100.0));
        outra.setBuiltArea(BigDecimal.valueOf(80.0));
        outra.setCreatedAt(Instant.now());
        outra.setUpdatedAt(Instant.now());
        outra = projectRepository.save(outra);

        ConstructionProjectMember membro = new ConstructionProjectMember();
        membro.setConstructionProject(outra);
        membro.setUser(arquiteto);
        membro.setRoleInProject("ARCHITECT");
        membro.setMembershipStatus("ACTIVE");
        membro.setJoinedAt(Instant.now());
        memberRepository.save(membro);

        mockMvc.perform(get("/projects/" + outra.getId() + "/proposals/" + proposalId)
                        .with(auth(arquiteto)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("delete remove a proposta e o historico junto")
    void deleteRemoveProposta() throws Exception {
        Long proposalId = criarProposta();

        mockMvc.perform(delete("/projects/" + project.getId() + "/proposals/" + proposalId)
                        .with(auth(arquiteto)))
                .andExpect(status().isNoContent());

        // O 500 do DELETE so aparecia no flush do commit, que o @Transactional do
        // teste nunca chega a executar: sem este flush explicito a suite passava
        // com o servidor quebrado em producao.
        entityManager.flush();

        mockMvc.perform(get("/projects/" + project.getId() + "/proposals/" + proposalId)
                        .with(auth(arquiteto)))
                .andExpect(status().isNotFound());
    }
}
