package br.pucpr.prissma_server.report;

import br.pucpr.prissma_server.TestcontainersConfig;
import br.pucpr.prissma_server.projects.ConstructionProject;
import br.pucpr.prissma_server.projects.ConstructionProjectMember;
import br.pucpr.prissma_server.projects.ConstructionProjectMemberRepository;
import br.pucpr.prissma_server.projects.ConstructionProjectRepository;
import br.pucpr.prissma_server.stage.Stage;
import br.pucpr.prissma_server.stage.StageRepository;
import br.pucpr.prissma_server.users.Role;
import br.pucpr.prissma_server.users.User;
import br.pucpr.prissma_server.users.UserRepository;
import br.pucpr.prissma_server.workspaces.Workspace;
import br.pucpr.prissma_server.workspaces.WorkspaceMember;
import br.pucpr.prissma_server.workspaces.WorkspaceMemberRepository;
import br.pucpr.prissma_server.workspaces.WorkspaceRepository;
import br.pucpr.prissma_server.workspaces.WorkspaceRole;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integração do relatório: gera o PDF de verdade e abre com o PDFBox.
 *
 * As duas asserções que importam aqui são a contagem de páginas (a única
 * promessa do layout que o código pode quebrar sem avisar) e a acentuação — um
 * glifo fora da codificação da fonte vira '#' silenciosamente no renderer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(TestcontainersConfig.class)
@DisplayName("ProjectReportController Integration Tests")
class ProjectReportControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired private ConstructionProjectRepository projectRepository;
    @Autowired private ConstructionProjectMemberRepository memberRepository;
    @Autowired private StageRepository stageRepository;

    private User owner;
    private User client;
    private User outsider;
    private ConstructionProject project;

    private RequestPostProcessor auth(User user) {
        return authentication(new UsernamePasswordAuthenticationToken(
                user.getId(), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private User user(String emailPrefix, String name) {
        User user = new User();
        user.setEmail(emailPrefix + System.nanoTime() + "@example.com");
        user.setName(name);
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

    private void addProjectMember(User user, String role) {
        ConstructionProjectMember member = new ConstructionProjectMember();
        member.setConstructionProject(project);
        member.setUser(user);
        member.setRoleInProject(role);
        member.setMembershipStatus("ACTIVE");
        member.setJoinedAt(Instant.now());
        memberRepository.save(member);
    }

    private Stage stage(String name, int order, String status,
                        LocalDate plannedEnd, LocalDate actualEnd) {
        Stage stage = new Stage();
        stage.setConstructionProject(project);
        stage.setName(name);
        stage.setDisplayOrder(order);
        stage.setStatus(status);
        stage.setPlannedStartDate(plannedEnd.minusDays(10));
        stage.setPlannedEndDate(plannedEnd);
        if (actualEnd != null) {
            stage.setActualStartDate(actualEnd.minusDays(10));
            stage.setActualEndDate(actualEnd);
        }
        stage.setCreatedAt(Instant.now());
        stage.setUpdatedAt(Instant.now());
        return stageRepository.save(stage);
    }

    @BeforeEach
    void setUp() {
        owner = user("dono", "Ana Conceição");
        client = user("cliente", "Cliente Final");
        outsider = user("estranho", "Estranho");

        Workspace workspace = new Workspace();
        workspace.setOwnerId(owner.getId());
        workspace.setName("Workspace do Relatório");
        workspace.setPrimary(true);
        workspace.setCreatedAt(Instant.now());
        workspace.setUpdatedAt(Instant.now());
        workspace = workspaceRepository.save(workspace);
        addWorkspaceMember(workspace, client);

        // O estranho tem workspace próprio: a obra abaixo precisa ser invisível
        // para ele, e com 404, não 403.
        Workspace other = new Workspace();
        other.setOwnerId(outsider.getId());
        other.setName("Outro Workspace");
        other.setPrimary(true);
        other.setCreatedAt(Instant.now());
        other.setUpdatedAt(Instant.now());
        workspaceRepository.save(other);

        project = new ConstructionProject();
        project.setWorkspaceId(workspace.getId());
        // Acentuação e cedilha no título: é o que o PDF precisa preservar.
        project.setTitle("Ampliação da Residência São João " + System.nanoTime());
        project.setStreet("Rua das Acácias");
        project.setNumber("42");
        project.setCity("Curitiba");
        project.setState("PR");
        project.setCep("80000-000");
        project.setProjectType("Residencial");
        project.setCategory("Reforma");
        project.setLandArea(BigDecimal.valueOf(300.0));
        project.setBuiltArea(BigDecimal.valueOf(180.0));
        project.setStatus("IN_PROGRESS");
        project.setPlannedStartDate(LocalDate.of(2026, 1, 5));
        project.setPlannedEndDate(LocalDate.of(2026, 12, 20));
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(Instant.now());
        project = projectRepository.save(project);

        addProjectMember(owner, "OWNER");
        addProjectMember(client, "USER");

        stage("Fundação", 1, "DONE", LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 16));
        stage("Alvenaria", 2, "DONE", LocalDate.of(2026, 6, 20), LocalDate.of(2026, 6, 18));
        stage("Instalações", 3, "IN_PROGRESS", LocalDate.of(2026, 6, 28), null);
    }

    private byte[] fetchReport(User requester) throws Exception {
        return mockMvc.perform(get("/projects/{id}/report", project.getId())
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-30")
                        .with(auth(requester)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                // filename= na forma simples e com a extensão .pdf. Só a forma
                // estendida (filename*=UTF-8'') faz clientes como o Postman
                // salvarem o arquivo com nome truncado e sem extensão.
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.matchesPattern(
                                "attachment; filename=\"relatorio-obra-\\d+-[\\d-]+-a-[\\d-]+\\.pdf\"")))
                .andReturn().getResponse().getContentAsByteArray();
    }

    @Test
    @DisplayName("gera um PDF de uma única página")
    void generatesSinglePagePdf() throws Exception {
        byte[] pdf = fetchReport(owner);

        assertTrue(pdf.length > 4, "o corpo não pode vir vazio");
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII),
                "o corpo precisa começar com a assinatura de PDF");

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages(), "o relatório precisa caber em uma página");
        }
    }

    @Test
    @DisplayName("preserva a acentuação do português no texto do PDF")
    void preservesPortugueseAccents() throws Exception {
        byte[] pdf = fetchReport(owner);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);

            // De propósito só trechos SEM text-transform: uppercase no CSS —
            // rótulos como "Relatório da obra" e os <th> saem maiúsculos do
            // renderer e não serviriam para comparar acento a acento.
            assertTrue(text.contains("Ampliação"), "esperava o título acentuado; veio: " + text);
            assertTrue(text.contains("Residência"), "esperava o título acentuado");
            assertTrue(text.contains("Fundação"), "esperava o nome da etapa acentuado");
            assertTrue(text.contains("Instalações"), "esperava o nome da etapa acentuado");

            assertFalse(text.contains("Amplia#"), "acento virou '#' - glifo ausente na fonte");
            assertFalse(text.contains("Funda#"), "cedilha virou '#' - glifo ausente na fonte");
        }
    }

    @Test
    @DisplayName("o e-mail dos membros nunca vai para o PDF")
    void doesNotLeakMemberEmails() throws Exception {
        byte[] pdf = fetchReport(owner);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertFalse(text.contains("@example.com"), "o PDF circula fora do sistema");
        }
    }

    @Test
    @DisplayName("cliente com apenas VIEW_PROJECT consegue emitir o relatório")
    void clientCanGenerate() throws Exception {
        byte[] pdf = fetchReport(client);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages());
        }
    }

    @Test
    @DisplayName("usuário de outro workspace recebe 404, não 403")
    void outsiderGets404() throws Exception {
        mockMvc.perform(get("/projects/{id}/report", project.getId()).with(auth(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("sem autenticação retorna 401")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get("/projects/{id}/report", project.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("data inicial posterior à final retorna 400")
    void invertedPeriodIsRejected() throws Exception {
        mockMvc.perform(get("/projects/{id}/report", project.getId())
                        .param("from", "2026-06-30")
                        .param("to", "2026-06-01")
                        .with(auth(owner)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("continua em uma página mesmo com muitas etapas")
    void staysSinglePageWithManyStages() throws Exception {
        for (int i = 4; i <= 40; i++) {
            stage("Etapa muito longa para forçar quebra de linha " + i, i, "DONE",
                    LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 15).plusDays(i));
        }

        byte[] pdf = fetchReport(owner);

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages(),
                    "o corte de etapas em ReportMetrics deve segurar a página");
        }
    }

    /**
     * Obra recém-criada, sem etapa, tarefa, orçamento ou membro: o PDF precisa
     * sair no MESMO formato, apenas com os campos em branco — não numa versão
     * reduzida nem com erro.
     */
    @Test
    @DisplayName("obra sem nenhum dado gera o PDF no formato padrão, com os campos em branco")
    void emptyProjectKeepsStandardLayout() throws Exception {
        ConstructionProject empty = new ConstructionProject();
        empty.setWorkspaceId(project.getWorkspaceId());
        empty.setTitle("Obra Sem Dados " + System.nanoTime());
        empty.setStreet("Rua Sem Movimentação");
        empty.setProjectType("Residencial");
        empty.setCategory("Construção");
        empty.setLandArea(BigDecimal.valueOf(100.0));
        empty.setBuiltArea(BigDecimal.valueOf(50.0));
        empty.setCreatedAt(Instant.now());
        empty.setUpdatedAt(Instant.now());
        empty = projectRepository.save(empty);

        // Sem membros de propósito: o owner alcança a obra por ser dono do
        // workspace, então dá para exercitar o bloco de equipe vazio.
        byte[] pdf = mockMvc.perform(get("/projects/{id}/report", empty.getId())
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-30")
                        .with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(1, document.getNumberOfPages());

            String text = new PDFTextStripper().getText(document);

            // Estrutura preservada, valores zerados/em branco.
            assertTrue(text.contains("0 / 0"), "os KPIs devem sair zerados; veio: " + text);
            assertTrue(text.contains("Nenhuma etapa com datas no período"),
                    "a tabela deve continuar existindo, com a linha em branco");
            assertTrue(text.contains("Sem orçamento cadastrado"),
                    "a seção de orçamento deve continuar no lugar");
            assertTrue(text.contains("Obra Sem Dados"), "os dados cadastrais continuam sendo exibidos");
        }
    }
}
