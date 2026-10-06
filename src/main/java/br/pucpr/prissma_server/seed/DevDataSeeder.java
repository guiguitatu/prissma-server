package br.pucpr.prissma_server.seed;

import br.pucpr.prissma_server.attachments.storage.FileStorageService;
import br.pucpr.prissma_server.seed.SeedFiles.GeneratedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Seed de desenvolvimento: popula o banco com workspaces, obras e dados de
 * todos os módulos para testar a parte visual sem cadastrar nada na mão.
 *
 * Só existe no perfil {@code seed}:
 * <pre>
 *   ./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=seed"
 * </pre>
 * O app sobe normalmente depois da carga, então dá para logar em seguida.
 *
 * Idempotente: todo usuário da seed tem e-mail {@code @prissma.dev}. A cada
 * execução, os workspaces desses usuários (com as obras e tudo que pende delas)
 * e os próprios usuários são apagados e recriados, junto com os arquivos no
 * storage. Dados de outros usuários não são tocados.
 *
 * As datas são relativas ao dia da execução: a obra "em andamento" sempre tem
 * tarefas desta semana, o diário sempre tem registros recentes e o schedule
 * sempre mostra horas no período atual.
 */
@Component
@Profile("seed")
@Order(Ordered.LOWEST_PRECEDENCE)
public class DevDataSeeder implements ApplicationRunner {

    static final String EMAIL_DOMAIN = "@prissma.dev";
    static final String PASSWORD = "Prissma@123";
    static final String PENDING_INVITE_TOKEN = "seed-convite-pendente";

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageService storage;
    private final String frontendUrl;

    private final ZoneId zone = ZoneId.systemDefault();
    private final Random random = new Random(42);
    private LocalDate today;
    private String passwordHash;

    private User carla;
    private User marina;
    private User rafael;
    private User beatriz;
    private User joao;
    private User lucas;
    private User pedro;
    private User tiago;

    record User(long id, String name) {
    }

    public DevDataSeeder(JdbcTemplate jdbc,
                         PasswordEncoder passwordEncoder,
                         FileStorageService storage,
                         @Value("${security.password-reset.frontend-url}") String frontendUrl) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.storage = storage;
        this.frontendUrl = frontendUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        log.warn("Perfil 'seed' ativo: recriando os dados de demonstração ({}).", EMAIL_DOMAIN);

        today = LocalDate.now(zone);
        passwordHash = passwordEncoder.encode(PASSWORD);

        List<String> oldKeys = collectSeedStorageKeys();
        List<String> newKeys = new ArrayList<>();
        wipeSeedData();
        registerFileCleanup(oldKeys, newKeys);

        seedUsers();
        seedWorkspacesAndProjects(newKeys);

        log.warn("""

                ==================== SEED PRISSMA ====================
                Senha de todos: {}
                  carla{}   ENG  dona de 'Mendes Engenharia' e 'Mendes Reformas' (vazio); membro de 'Souza Arquitetura'
                  marina{}  ENG  ADMIN do workspace, engenheira das obras
                  rafael{}  ARQ  dono de 'Souza Arquitetura', arquiteto na Mendes
                  beatriz{} ARQ  arquiteta
                  joao{}    USER mestre de obras (MEMBER)
                  lucas{}   USER mestre de obras (MEMBER)
                  pedro{}   USER cliente (CLIENT, só leitura)
                  tiago{}   USER membro desativado
                Convite pendente: {}/invite?token={}
                ======================================================
                """,
                PASSWORD, EMAIL_DOMAIN, EMAIL_DOMAIN, EMAIL_DOMAIN, EMAIL_DOMAIN, EMAIL_DOMAIN,
                EMAIL_DOMAIN, EMAIL_DOMAIN, EMAIL_DOMAIN, frontendUrl, PENDING_INVITE_TOKEN);
    }

    // ------------------------------------------------------------------ limpeza

    private static final String SEED_USERS = "SELECT id FROM users WHERE email LIKE '%" + EMAIL_DOMAIN + "'";
    private static final String SEED_WORKSPACES = "SELECT id FROM workspaces WHERE owner_id IN (" + SEED_USERS + ")";
    private static final String SEED_PROJECTS =
            "SELECT id FROM construction_projects WHERE workspace_id IN (" + SEED_WORKSPACES + ")";

    private List<String> collectSeedStorageKeys() {
        List<String> keys = new ArrayList<>();
        keys.addAll(jdbc.queryForList(
                "SELECT file_url FROM attachments WHERE construction_project_id IN (" + SEED_PROJECTS + ")",
                String.class));
        keys.addAll(jdbc.queryForList("""
                SELECT s.file_url FROM design_submissions s
                  JOIN design_proposals p ON p.id = s.proposal_id
                 WHERE s.file_url IS NOT NULL AND p.construction_project_id IN (""" + SEED_PROJECTS + ")",
                String.class));
        for (String column : List.of("raw_image_key", "floor_plan_key")) {
            keys.addAll(jdbc.queryForList("""
                    SELECT a.%1$s FROM ai_environment_previews a
                      JOIN design_proposals p ON p.id = a.proposal_id
                     WHERE a.%1$s IS NOT NULL AND p.construction_project_id IN (%2$s)"""
                    .formatted(column, SEED_PROJECTS), String.class));
        }
        return keys;
    }

    /**
     * Apaga na ordem das FKs sem cascade: a obra leva etapas, tarefas,
     * orçamento, anexos, diário, propostas e schedule junto (ON DELETE CASCADE);
     * workspaces, memberships e convites precisam sair antes dos usuários.
     */
    private void wipeSeedData() {
        jdbc.update("DELETE FROM construction_projects WHERE workspace_id IN (" + SEED_WORKSPACES + ")");
        jdbc.update("DELETE FROM member_invites WHERE workspace_id IN (" + SEED_WORKSPACES + ")"
                + " OR invited_by IN (" + SEED_USERS + ")");
        jdbc.update("DELETE FROM workspace_members WHERE workspace_id IN (" + SEED_WORKSPACES + ")"
                + " OR user_id IN (" + SEED_USERS + ") OR invited_by IN (" + SEED_USERS + ")");
        jdbc.update("DELETE FROM workspaces WHERE owner_id IN (" + SEED_USERS + ")");
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + EMAIL_DOMAIN + "'");
    }

    /** Arquivos antigos só somem se a carga nova commitar; os novos somem se ela falhar. */
    private void registerFileCleanup(List<String> oldKeys, List<String> newKeys) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                deleteQuietly(status == STATUS_COMMITTED ? oldKeys : newKeys);
            }
        });
    }

    private void deleteQuietly(List<String> keys) {
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (RuntimeException e) {
                log.debug("Seed: não foi possível apagar {}", key, e);
            }
        }
    }

    // ------------------------------------------------------------------ usuários

    private void seedUsers() {
        carla = user("Carla Mendes", "carla", "ENG");
        marina = user("Marina Costa", "marina", "ENG");
        rafael = user("Rafael Souza", "rafael", "ARQ");
        beatriz = user("Beatriz Lima", "beatriz", "ARQ");
        joao = user("João Pereira", "joao", "USER");
        lucas = user("Lucas Ferreira", "lucas", "USER");
        pedro = user("Pedro Almeida", "pedro", "USER");
        tiago = user("Tiago Ramos", "tiago", "USER");

        jdbc.update("""
                INSERT INTO user_addresses (user_id, cep, street, city, state, number, complement)
                VALUES (?, '80250-104', 'Avenida Sete de Setembro', 'Curitiba', 'PR', '4200', 'Sala 1203')""",
                carla.id());
        jdbc.update("""
                INSERT INTO user_addresses (user_id, cep, street, city, state, number)
                VALUES (?, '80730-000', 'Rua Padre Anchieta', 'Curitiba', 'PR', '1500')""",
                rafael.id());
    }

    private User user(String name, String login, String role) {
        long id = insert("INSERT INTO users (name, email, password, role) VALUES (?, ?, ?, ?)",
                name, login + EMAIL_DOMAIN, passwordHash, role);
        return new User(id, name);
    }

    // ------------------------------------------------------------------ workspaces e obras

    private void seedWorkspacesAndProjects(List<String> newKeys) {
        long mendes = workspace(carla, "Mendes Engenharia", "12.345.678/0001-90", true);
        wsMember(mendes, carla, "OWNER", null, -400, true);
        wsMember(mendes, marina, "ADMIN", carla, -380, true);
        wsMember(mendes, rafael, "MEMBER", carla, -360, true);
        wsMember(mendes, beatriz, "MEMBER", carla, -200, true);
        wsMember(mendes, joao, "MEMBER", marina, -300, true);
        wsMember(mendes, lucas, "MEMBER", marina, -150, true);
        wsMember(mendes, pedro, "CLIENT", carla, -160, true);
        wsMember(mendes, tiago, "MEMBER", carla, -250, false);

        invite(mendes, "ana.ribeiro" + EMAIL_DOMAIN, "Ana Ribeiro", "MEMBER", marina,
                PENDING_INVITE_TOKEN, 5, false);
        invite(mendes, "carlos.dias" + EMAIL_DOMAIN, "Carlos Dias", "CLIENT", carla,
                UUID.randomUUID().toString(), -2, false);
        invite(mendes, "tiago" + EMAIL_DOMAIN, "Tiago Ramos", "MEMBER", carla,
                UUID.randomUUID().toString(), -240, true);

        // Segundo workspace da Carla, sem obras: testa o seletor de contas e o estado vazio.
        long reformas = workspace(carla, "Mendes Reformas", null, false);
        wsMember(reformas, carla, "OWNER", null, -30, true);

        long souza = workspace(rafael, "Souza Arquitetura", "98.765.432/0001-10", true);
        wsMember(souza, rafael, "OWNER", null, -500, true);
        wsMember(souza, carla, "MEMBER", rafael, -90, true);
        wsMember(souza, beatriz, "MEMBER", rafael, -120, true);

        ProjectSeeder seeder = new ProjectSeeder(newKeys);
        seeder.jardimBotanico(mendes);
        seeder.batel(mendes);
        seeder.aguaVerde(mendes);
        seeder.galpaoCic(mendes);
        seeder.altoDaXv(mendes);
        seeder.santaFelicidade(mendes);
        seeder.sobradoMerces(mendes);
        seeder.ecoville(souza);
    }

    private long workspace(User owner, String name, String document, boolean primary) {
        return insert("""
                INSERT INTO workspaces (owner_id, name, document, is_primary, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)""",
                owner.id(), name, document, primary, at(-400, 9, 0), at(-400, 9, 0));
    }

    private void wsMember(long workspaceId, User user, String role, User invitedBy, int acceptedOffset, boolean active) {
        jdbc.update("""
                INSERT INTO workspace_members (workspace_id, user_id, role, invited_by, accepted_at, is_active,
                                               created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                workspaceId, user.id(), role, invitedBy == null ? null : invitedBy.id(),
                at(acceptedOffset, 10, 0), active, at(acceptedOffset, 10, 0), at(acceptedOffset, 10, 0));
    }

    private void invite(long workspaceId, String email, String fullName, String role, User invitedBy,
                        String token, int expiresInDays, boolean accepted) {
        jdbc.update("""
                INSERT INTO member_invites (workspace_id, invited_email, full_name, role, invited_by, token_hash,
                                            expires_at, accepted, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                workspaceId, email, fullName, role, invitedBy.id(), sha256Hex(token),
                at(expiresInDays, 23, 59), accepted, at(expiresInDays - 7, 11, 0), at(expiresInDays - 7, 11, 0));
    }

    // ------------------------------------------------------------------ obras

    /** Uma instância por execução: guarda as chaves de storage criadas para o rollback. */
    private final class ProjectSeeder {

        private final List<String> newKeys;

        ProjectSeeder(List<String> newKeys) {
            this.newKeys = newKeys;
        }

        /** A obra completa: todos os módulos com todos os estados. */
        void jardimBotanico(long workspaceId) {
            long p = project(workspaceId, "Residencial Jardim Botânico", "RESIDENTIAL", "BUILDING", "IN_PROGRESS",
                    -150, 210, "1250.00", "2380.50", "80210-390", "Rua Engenheiro Ostoja Roguski", "690",
                    "Curitiba", "PR", "Bloco A");
            member(p, carla, "OWNER", "ACTIVE", -150);
            member(p, marina, "ENGINEER", "ACTIVE", -150);
            member(p, rafael, "ARCHITECT", "ACTIVE", -148);
            member(p, beatriz, "ARCHITECT", "INACTIVE", -140);
            member(p, joao, "FOREMAN", "ACTIVE", -145);
            member(p, lucas, "FOREMAN", "ACTIVE", -60);
            member(p, pedro, "USER", "ACTIVE", -150);

            // Override do mestre de obras: os defaults + MANAGE_STAGES. Mostra a
            // matriz de permissões customizada na aba Equipes.
            rolePermissions(p, "FOREMAN", "VIEW_PROJECT", "MANAGE_BUDGET", "MANAGE_TEAMS", "MANAGE_TASKS",
                    "MANAGE_ATTACHMENTS", "MANAGE_DIARY", "MANAGE_STAGES");

            long prelim = stage(p, 1, "Serviços preliminares", "Canteiro, tapumes, ligação provisória de água e luz.",
                    "DONE", -150, -132, -150, -130, "Térreo");
            long fundacao = stage(p, 2, "Fundação", "Estacas hélice contínua e blocos de coroamento.",
                    "DONE", -132, -95, -130, -90, "Subsolo");
            long estrutura = stage(p, 3, "Estrutura", "Pilares, vigas e lajes do 1º ao 8º pavimento.",
                    "IN_PROGRESS", -95, -8, -90, null, "1º ao 8º");
            long alvenaria = stage(p, 4, "Alvenaria", "Vedação em bloco cerâmico.",
                    "IN_PROGRESS", -30, 40, -25, null, "1º ao 4º");
            long instalacoes = stage(p, 5, "Instalações elétricas e hidráulicas", null,
                    "PLANNED", 10, 85, null, null, "Todos");
            long cobertura = stage(p, 6, "Cobertura", "Aguardando entrega das telhas termoacústicas.",
                    "BLOCKED", 20, 60, null, null, "Cobertura");
            long acabamento = stage(p, 7, "Acabamentos", "Revestimentos, pintura, louças e metais.",
                    "PLANNED", 80, 195, null, null, "Todos");
            stage(p, 8, "Paisagismo e entrega", null, "PLANNED", 190, 210, null, null, "Térreo");

            task(prelim, joao, null, "Montar canteiro de obras", null, "MEDIUM", "DONE", -150, -145);
            task(prelim, null, "Copel", "Ligação provisória de energia", null, "HIGH", "DONE", -148, -135);
            task(fundacao, marina, null, "Aprovar laudo de sondagem", "Conferir SPT com o projeto de fundação.",
                    "HIGH", "DONE", -132, -128);
            task(fundacao, null, "Geofundações Ltda", "Executar estacas hélice contínua", null,
                    "HIGH", "DONE", -128, -105);
            task(fundacao, joao, null, "Concretar blocos de coroamento", null, "MEDIUM", "DONE", -105, -92);

            // Estrutura: mistura de estados, uma tarefa atrasada da Carla e
            // duas do João no mesmo dia (sobreposição no schedule).
            task(estrutura, carla, null, "Revisar projeto estrutural do 7º pavimento",
                    "Compatibilizar com o arquitetônico antes da concretagem.", "HIGH", "IN_PROGRESS", -12, -2);
            task(estrutura, joao, null, "Concretar laje do 6º pavimento", null, "HIGH", "DONE", -20, -14);
            task(estrutura, joao, null, "Armar pilares do 7º pavimento", null, "HIGH", "IN_PROGRESS", -3, 2);
            task(estrutura, joao, null, "Receber aço CA-50 para o 8º pavimento", null, "MEDIUM", "TODO", 1, 1);
            task(estrutura, lucas, null, "Desformar laje do 5º pavimento", null, "LOW", "DONE", -25, -22);
            task(estrutura, null, "Concreteira Mix", "Programar bombeamento do 7º pavimento", null,
                    "MEDIUM", "TODO", 4, 6);
            task(estrutura, marina, null, "Ensaio de rompimento dos corpos de prova", null,
                    "MEDIUM", "BLOCKED", -6, -1);

            task(alvenaria, lucas, null, "Marcação da alvenaria do 3º pavimento", null, "MEDIUM", "IN_PROGRESS", -4, 3);
            task(alvenaria, lucas, null, "Elevar alvenaria do 2º pavimento", null, "MEDIUM", "DONE", -24, -10);
            task(alvenaria, carla, null, "Aprovar amostra de bloco cerâmico", null, "HIGH", "TODO", 0, 2);
            task(alvenaria, rafael, null, "Detalhar vergas e contravergas", null, "LOW", "TODO", 3, 9);

            task(instalacoes, carla, null, "Contratar empreiteira de instalações", "Três orçamentos no mínimo.",
                    "HIGH", "TODO", 2, 6);
            task(instalacoes, marina, null, "Compatibilizar elétrica com estrutura", null, "MEDIUM", "TODO", 10, 20);
            task(cobertura, carla, null, "Cobrar fornecedor das telhas", "Pedido atrasado há 10 dias.",
                    "HIGH", "BLOCKED", -10, -3);
            task(acabamento, rafael, null, "Definir paginação dos porcelanatos", null, "LOW", "TODO", 60, 75);
            task(acabamento, pedro, null, "Escolher metais dos banheiros", null, "MEDIUM", "TODO", 5, 12);

            long budget = budget(p, "Orçamento executivo aprovado em reunião com o cliente.", "1850000.00");
            long iFund = item(budget, "Fundação", "Estacas, blocos e vigas baldrame", "220000.00");
            long iEstr = item(budget, "Estrutura", "Concreto, aço e formas", "480000.00");
            long iAlv = item(budget, "Alvenaria", "Blocos, argamassa e mão de obra", "260000.00");
            long iInst = item(budget, "Instalações", "Elétrica, hidráulica e gás", "210000.00");
            item(budget, "Cobertura", "Estrutura metálica e telhas", "150000.00");
            item(budget, "Acabamentos", "Revestimentos, pintura, louças e metais", "380000.00");
            long iMao = item(budget, "Administração", "Equipe de obra, EPIs e canteiro", "150000.00");
            spread(iFund, fundacao, "209400.00", 5, -130, -92, "Medição fundação", "Geofundações Ltda");
            spread(iEstr, estrutura, "312750.00", 8, -90, -3, "Concreto usinado e aço", "Concreteira Mix");
            spread(iAlv, alvenaria, "58300.00", 3, -25, -2, "Blocos cerâmicos", "Cerâmica Paraná");
            expense(iInst, instalacoes, "Sinal para empreiteira de instalações", "12000.00", "Instala Sul", -6);
            spread(iMao, null, "126000.00", 5, -150, -1, "Folha da equipe de obra", null);
            expense(iMao, prelim, "Locação de container escritório", "4800.00", "Container Locações", -148);

            long planta = attachment(p, null, null, rafael, "Planta baixa - pavimento tipo.pdf",
                    SeedFiles.pdf("Planta baixa - pavimento tipo", "Pavimento tipo com 4 apartamentos de 2 e 3 quartos."), -140);
            attachment(p, null, null, carla, "Alvará de construção.pdf",
                    SeedFiles.pdf("Alvará de construção", "Alvará nº 2026/004512, emitido pela Prefeitura de Curitiba."), -152);
            attachment(p, null, null, marina, "Memorial descritivo.docx",
                    SeedFiles.docx("Memorial descritivo", "Especificação de materiais e serviços da obra."), -138);
            attachment(p, fundacao, null, marina, "Laudo de sondagem SPT.pdf",
                    SeedFiles.pdf("Laudo de sondagem SPT", "Seis furos de sondagem, nível d'água a 4,20 m."), -131);
            attachment(p, null, null, rafael, "Fachada principal.png",
                    SeedFiles.png("Fachada principal", new Color(0x5B7DB1)), -120);
            long fotoArmadura = attachment(p, estrutura, null, joao, "Armadura pilares 7º pav.png",
                    SeedFiles.png("Armadura pilares 7º pav.", new Color(0x7A6F5D)), -2);
            long fotoEntrega = attachment(p, null, null, joao, "Entrega de aço.png",
                    SeedFiles.png("Entrega de aço CA-50", new Color(0x4F6D5B)), -5);
            long fotoChuva = attachment(p, null, null, lucas, "Canteiro após chuva.png",
                    SeedFiles.png("Canteiro após a chuva", new Color(0x3E5C76)), -9);

            diary(p, 0, 7, 30, "WORKFORCE", joao, "Efetivo do dia: 14 pedreiros, 6 serventes, 2 armadores.", null);
            diary(p, 0, 11, 15, "OCCURRENCE", joao, "Início da armação dos pilares do 7º pavimento.", fotoArmadura);
            diary(p, -1, 8, 0, "WORKFORCE", joao, "Efetivo: 13 pedreiros, 6 serventes.", null);
            diary(p, -1, 14, 40, "DELIVERY", lucas, "Chegada de 12 paletes de bloco cerâmico 14x19x29.", null);
            diary(p, -2, 9, 10, "OCCURRENCE", marina, "Visita técnica da fiscalização da prefeitura, sem pendências.", planta);
            diary(p, -3, 16, 20, "IMPEDIMENT", carla, "Telhas termoacústicas não entregues; cobertura bloqueada.", null);
            diary(p, -5, 10, 0, "DELIVERY", joao, "Recebidas 18 t de aço CA-50 para o 7º e 8º pavimentos.", fotoEntrega);
            diary(p, -6, 7, 45, "WORKFORCE", joao, "Efetivo reduzido: 9 pedreiros por causa do feriado municipal.", null);
            diary(p, -8, 15, 0, "OCCURRENCE", marina, "Corpos de prova da laje do 6º enviados ao laboratório.", null);
            diary(p, -9, 13, 30, "IMPEDIMENT", lucas, "Chuva forte à tarde, serviços externos suspensos.", fotoChuva);
            diary(p, -12, 8, 20, "DELIVERY", lucas, "Entrega de 40 m³ de concreto usinado fck 30.", null);
            diary(p, -14, 17, 0, "OCCURRENCE", joao, "Concretagem da laje do 6º pavimento concluída.", null);

            scheduleMember(p, carla, "Coordenação");
            scheduleMember(p, marina, "Gestão da obra");
            scheduleMember(p, joao, "Estrutura");
            scheduleMember(p, lucas, "Alvenaria");
            scheduleMember(p, rafael, "Projeto arquitetônico");
            allocations(p, joao, -35, 35, "8.00", "4.00");
            allocations(p, lucas, -35, 35, "8.00", null);
            allocations(p, marina, -35, 35, "6.00", null);
            allocations(p, carla, -35, 35, "2.50", null);
            allocations(p, rafael, -14, 14, "3.00", null);

            long sala = proposal(p, null, "Sala de estar integrada", "Sala integrada à varanda, piso contínuo.",
                    "LIVING_ROOM", rafael, -40);
            long salaV1 = version(sala, 1, "REJECTED", "Primeira proposta, tons frios.", rafael, false,
                    SeedFiles.png("Sala de estar v1", new Color(0x6C7A89)), -40);
            approval(salaV1, pedro, "REJECTED", "Gostaria de tons mais quentes e iluminação indireta.", -36);
            long salaV2 = version(sala, 2, "APPROVED", "Madeira natural e iluminação indireta.", rafael, false,
                    SeedFiles.png("Sala de estar v2", new Color(0xA67C52)), -30);
            approval(salaV2, pedro, "APPROVED", "Perfeito, aprovado!", -28);

            long cozinha = proposal(p, acabamento, "Cozinha gourmet", "Ilha central com cooktop.", "KITCHEN", beatriz, -12);
            long cozinhaV1 = version(cozinha, 1, "PENDING_REVIEW", "Prévia gerada pela IA a partir da foto do ambiente.",
                    beatriz, true, SeedFiles.png("Cozinha gourmet (IA)", new Color(0x8E9B7A)), -11);
            preview(cozinha, "READY", beatriz, cozinhaV1, null, -11, "KITCHEN", "MODERN", true);

            long suite = proposal(p, null, "Suíte master", "Aguardando medidas finais.", "BEDROOM", rafael, -3);
            version(suite, 1, "DRAFT", "Rascunho inicial, sem imagem.", rafael, false, null, -3);

            long banheiro = proposal(p, null, "Banheiro social", null, "BATHROOM", beatriz, -6);
            version(banheiro, 1, "DRAFT", null, beatriz, false,
                    SeedFiles.png("Banheiro social", new Color(0x9FB4C7)), -6);
            preview(banheiro, "FAILED", beatriz, null,
                    "A OpenAI recusou a imagem de entrada: resolução abaixo do mínimo.", -5, "BATHROOM", "MINIMALIST", false);

            long varanda = proposal(p, null, "Varanda gourmet", "Churrasqueira e bancada em granito.", "BALCONY", rafael, -20);
            long varandaV1 = version(varanda, 1, "APPROVED", "Versão final gerada pela IA.", rafael, true,
                    SeedFiles.png("Varanda gourmet (IA)", new Color(0x7F5A3C)), -19);
            approval(varandaV1, carla, "APPROVED", null, -18);
            preview(varanda, "READY", rafael, varandaV1, null, -19, "BALCONY", "RUSTIC", true);
        }

        /** Comercial em andamento com orçamento estourado e tarefas atrasadas. */
        void batel(long workspaceId) {
            long p = project(workspaceId, "Edifício Comercial Batel", "COMMERCIAL", "BUILDING", "IN_PROGRESS",
                    -320, 45, "980.00", "5400.00", "80420-090", "Avenida do Batel", "1868", "Curitiba", "PR", null);
            member(p, carla, "OWNER", "ACTIVE", -320);
            member(p, marina, "ENGINEER", "ACTIVE", -320);
            member(p, lucas, "FOREMAN", "ACTIVE", -200);
            member(p, pedro, "USER", "ACTIVE", -300);

            long fund = stage(p, 1, "Fundação", null, "DONE", -320, -250, -320, -240, "Subsolo");
            long estr = stage(p, 2, "Estrutura", null, "DONE", -250, -120, -240, -100, "1º ao 12º");
            long fach = stage(p, 3, "Fachada ventilada", "Painéis ACM e vidro insulado.", "IN_PROGRESS",
                    -100, -15, -95, null, "Fachada");
            long inst = stage(p, 4, "Instalações e elevadores", null, "IN_PROGRESS", -80, 20, -70, null, "Todos");
            long acab = stage(p, 5, "Acabamentos", null, "PLANNED", 0, 45, null, null, "Todos");

            task(fach, lucas, null, "Instalar painéis ACM da face norte", null, "HIGH", "IN_PROGRESS", -30, -5);
            task(fach, carla, null, "Aprovar amostra de vidro insulado", null, "HIGH", "TODO", -8, -4);
            task(inst, null, "Elevadores Atlas", "Montagem dos elevadores sociais", null, "HIGH", "IN_PROGRESS", -40, 10);
            task(inst, marina, null, "Vistoria do Corpo de Bombeiros", null, "HIGH", "TODO", 3, 5);
            task(inst, lucas, null, "Passar cabeamento estruturado", null, "MEDIUM", "BLOCKED", -15, 0);
            task(acab, marina, null, "Cotação de piso elevado", null, "LOW", "TODO", 6, 14);
            task(estr, lucas, null, "Retirar escoramento do 12º", null, "MEDIUM", "DONE", -110, -102);

            long budget = budget(p, "Orçamento revisado após reajuste do aço.", "3200000.00");
            long iFund = item(budget, "Fundação", "Estacas e subsolo", "400000.00");
            long iEstr = item(budget, "Estrutura", "Concreto e aço", "900000.00");
            long iFach = item(budget, "Fachada", "ACM e vidros", "600000.00");
            long iInst = item(budget, "Instalações", "Elétrica, hidráulica, SPDA", "700000.00");
            long iElev = item(budget, "Elevadores", "Dois elevadores sociais e um de serviço", "600000.00");
            spread(iFund, fund, "415000.00", 4, -320, -240, "Medição fundação", "Geofundações Ltda");
            spread(iEstr, estr, "1020000.00", 8, -240, -100, "Concreto e aço", "Concreteira Mix");
            spread(iFach, fach, "640000.00", 5, -95, -2, "Painéis e vidros", "Fachadas Premium");
            spread(iInst, inst, "689000.00", 5, -70, -1, "Medição instalações", "Instala Sul");
            spread(iElev, inst, "250000.00", 2, -60, -10, "Parcela elevadores", "Elevadores Atlas");

            attachment(p, null, null, carla, "Projeto legal aprovado.pdf",
                    SeedFiles.pdf("Projeto legal aprovado", "Aprovação do projeto legal junto à prefeitura."), -330);
            long foto = attachment(p, fach, null, lucas, "Fachada norte.png",
                    SeedFiles.png("Fachada norte - ACM", new Color(0x44546A)), -4);

            diary(p, -1, 9, 0, "OCCURRENCE", lucas, "Painéis da face norte 70% instalados.", foto);
            diary(p, -4, 15, 30, "IMPEDIMENT", lucas, "Guindaste em manutenção, içamento de painéis parado.", null);
            diary(p, -7, 8, 0, "WORKFORCE", lucas, "Efetivo: 22 colaboradores de 3 empreiteiras.", null);

            scheduleMember(p, lucas, "Fachada");
            scheduleMember(p, marina, "Instalações");
            allocations(p, lucas, -21, 21, "9.00", "5.00");
            allocations(p, marina, -21, 21, "2.00", null);
        }

        /** Reforma concluída: tudo DONE, orçamento fechado abaixo do previsto. */
        void aguaVerde(long workspaceId) {
            long p = project(workspaceId, "Reforma Loja Água Verde", "COMMERCIAL", "RENOVATION", "COMPLETED",
                    -210, -20, "320.00", "280.00", "80620-010", "Avenida República Argentina", "2100",
                    "Curitiba", "PR", "Loja 3");
            member(p, carla, "OWNER", "ACTIVE", -210);
            member(p, joao, "FOREMAN", "ACTIVE", -210);
            member(p, beatriz, "ARCHITECT", "ACTIVE", -210);

            long demo = stage(p, 1, "Demolição", null, "DONE", -210, -195, -210, -196, "Térreo");
            long civil = stage(p, 2, "Obra civil", null, "DONE", -195, -120, -196, -118, "Térreo");
            long acab = stage(p, 3, "Acabamentos e vitrine", null, "DONE", -120, -25, -118, -22, "Térreo");

            task(demo, joao, null, "Demolir divisórias internas", null, "MEDIUM", "DONE", -210, -200);
            task(civil, joao, null, "Refazer contrapiso", null, "MEDIUM", "DONE", -190, -170);
            task(acab, beatriz, null, "Projeto luminotécnico da vitrine", null, "HIGH", "DONE", -120, -100);
            task(acab, joao, null, "Instalar vitrine de vidro temperado", null, "HIGH", "DONE", -45, -25);

            long budget = budget(p, "Reforma completa da loja.", "420000.00");
            long iDemo = item(budget, "Demolição", "Retirada e caçambas", "35000.00");
            long iCivil = item(budget, "Obra civil", "Contrapiso, paredes e forro", "185000.00");
            long iAcab = item(budget, "Acabamentos", "Pisos, pintura e vitrine", "200000.00");
            spread(iDemo, demo, "31800.00", 2, -210, -196, "Caçambas e mão de obra", "Remove Entulhos");
            spread(iCivil, civil, "178400.00", 4, -195, -118, "Medição obra civil", "Construtora Parceira");
            spread(iAcab, acab, "194250.00", 4, -118, -22, "Acabamentos", "Vidraçaria Curitiba");

            attachment(p, null, null, carla, "Termo de entrega.pdf",
                    SeedFiles.pdf("Termo de entrega", "Obra entregue ao cliente sem pendências."), -20);
            diary(p, -20, 16, 0, "OCCURRENCE", carla, "Entrega da obra ao cliente.", null);
        }

        /** Obra pausada e atrasada: etapas bloqueadas e impedimentos no diário. */
        void galpaoCic(long workspaceId) {
            long p = project(workspaceId, "Galpão Logístico CIC", "INDUSTRIAL", "BUILDING", "PAUSED",
                    -130, -5, "12000.00", "8500.00", "81460-010", "Rua João Bettega", "5200", "Curitiba", "PR", null);
            member(p, carla, "OWNER", "ACTIVE", -130);
            member(p, marina, "ENGINEER", "ACTIVE", -130);
            member(p, joao, "FOREMAN", "ACTIVE", -125);

            long terra = stage(p, 1, "Terraplanagem", null, "DONE", -130, -100, -128, -98, "Térreo");
            long fund = stage(p, 2, "Fundação", "Obra pausada aguardando licença ambiental.", "BLOCKED",
                    -100, -50, -95, null, "Térreo");
            stage(p, 3, "Estrutura metálica", null, "PLANNED", -50, -5, null, null, "Térreo");

            task(terra, joao, null, "Corte e aterro do platô", null, "HIGH", "DONE", -128, -100);
            task(fund, carla, null, "Obter licença ambiental de instalação", "Protocolo 2026-1187 no IAT.",
                    "HIGH", "BLOCKED", -60, -20);
            task(fund, joao, null, "Cravar estacas pré-moldadas", null, "HIGH", "BLOCKED", -95, -60);

            long budget = budget(p, null, "6500000.00");
            long iTerra = item(budget, "Terraplanagem", "Corte, aterro e compactação", "480000.00");
            long iFund = item(budget, "Fundação", "Estacas pré-moldadas", "900000.00");
            item(budget, "Estrutura metálica", "Pórticos e cobertura", "3100000.00");
            spread(iTerra, terra, "452000.00", 3, -128, -98, "Medição terraplanagem", "Terra Forte");
            spread(iFund, fund, "120000.00", 1, -95, -95, "Mobilização bate-estaca", "Estacas Sul");

            diary(p, -50, 10, 0, "IMPEDIMENT", carla, "Obra pausada: licença ambiental pendente.", null);
            diary(p, -20, 14, 0, "IMPEDIMENT", marina, "IAT solicitou complementação do estudo de drenagem.", null);
        }

        /** Planejamento: etapas e tarefas só planejadas, orçamento sem gastos. */
        void altoDaXv(long workspaceId) {
            long p = project(workspaceId, "Casa Alto da XV", "RESIDENTIAL", "BUILDING", "PLANNING",
                    30, 300, "450.00", "320.00", "80045-160", "Rua Schiller", "515", "Curitiba", "PR", null);
            member(p, carla, "OWNER", "ACTIVE", -10);
            member(p, rafael, "ARCHITECT", "ACTIVE", -10);
            member(p, pedro, "USER", "ACTIVE", -8);

            long proj = stage(p, 1, "Projetos", "Arquitetônico, estrutural e complementares.", "IN_PROGRESS",
                    -10, 25, -10, null, null);
            long fund = stage(p, 2, "Fundação", null, "PLANNED", 30, 70, null, null, "Térreo");
            stage(p, 3, "Estrutura", null, "PLANNED", 70, 150, null, null, "Térreo e superior");
            stage(p, 4, "Acabamentos", null, "PLANNED", 150, 300, null, null, null);

            task(proj, rafael, null, "Estudo preliminar", null, "HIGH", "IN_PROGRESS", -10, 5);
            task(proj, carla, null, "Reunião de briefing com o cliente", null, "MEDIUM", "DONE", -9, -9);
            task(proj, carla, null, "Contratar sondagem", null, "MEDIUM", "TODO", 4, 10);
            task(fund, null, "A definir", "Orçar fundação", null, "LOW", "TODO", 20, 28);

            long budget = budget(p, "Estimativa inicial por CUB.", "980000.00");
            item(budget, "Projetos", "Arquitetônico e complementares", "60000.00");
            item(budget, "Estrutura", "Concreto armado", "320000.00");
            item(budget, "Acabamentos", "Padrão alto", "600000.00");

            long proposta = proposal(p, proj, "Fachada frontal", "Primeiro estudo de volumetria.", "OTHER", rafael, -4);
            version(proposta, 1, "PENDING_REVIEW", "Estudo de volumetria.", rafael, false,
                    SeedFiles.png("Fachada frontal - estudo", new Color(0xB08968)), -4);
        }

        void santaFelicidade(long workspaceId) {
            long p = project(workspaceId, "Clínica Santa Felicidade", "COMMERCIAL", "RENOVATION", "CANCELLED",
                    -90, 60, "600.00", "410.00", "82015-000", "Avenida Manoel Ribas", "7000", "Curitiba", "PR", null);
            member(p, carla, "OWNER", "ACTIVE", -90);
            member(p, marina, "ENGINEER", "ACTIVE", -90);

            long levant = stage(p, 1, "Levantamento", null, "DONE", -90, -80, -90, -82, "Térreo");
            stage(p, 2, "Adequação RDC 50", "Cancelada pelo cliente.", "PLANNED", -80, 60, null, null, "Térreo");
            task(levant, marina, null, "Levantamento cadastral", null, "MEDIUM", "DONE", -90, -83);
            diary(p, -75, 11, 0, "OCCURRENCE", carla, "Cliente cancelou o contrato.", null);
        }

        /** Obra recém-criada, sem nada: estados vazios de todos os módulos. */
        void sobradoMerces(long workspaceId) {
            long p = project(workspaceId, "Sobrado Mercês", "RESIDENTIAL", "RENOVATION", "PLANNING",
                    null, null, "280.00", "190.00", null, null, null, null, null, null);
            member(p, carla, "OWNER", "ACTIVE", -1);
        }

        /** Obra de outro workspace (Souza Arquitetura), focada em propostas. */
        void ecoville(long workspaceId) {
            long p = project(workspaceId, "Apartamento Ecoville", "RESIDENTIAL", "RENOVATION", "IN_PROGRESS",
                    -60, 120, "180.00", "145.00", "81200-100", "Rua Monsenhor Ivo Zanlorenzi", "3500",
                    "Curitiba", "PR", "Apto 1402");
            member(p, rafael, "OWNER", "ACTIVE", -60);
            member(p, carla, "ENGINEER", "ACTIVE", -58);
            member(p, beatriz, "ARCHITECT", "ACTIVE", -58);

            long demo = stage(p, 1, "Demolição", null, "DONE", -60, -45, -60, -44, null);
            long marc = stage(p, 2, "Marcenaria", null, "IN_PROGRESS", -30, 60, -28, null, null);
            task(demo, null, "Reformas Rápidas", "Remover revestimentos", null, "MEDIUM", "DONE", -60, -46);
            task(marc, beatriz, null, "Projeto de marcenaria da cozinha", null, "HIGH", "IN_PROGRESS", -10, 4);
            task(marc, carla, null, "Validar pontos elétricos da marcenaria", null, "MEDIUM", "TODO", 1, 3);

            long budget = budget(p, null, "260000.00");
            long iMarc = item(budget, "Marcenaria", "Cozinha, dormitórios e home", "140000.00");
            item(budget, "Revestimentos", "Porcelanato e rodapés", "70000.00");
            spread(iMarc, marc, "42000.00", 2, -28, -3, "Sinal marcenaria", "Marcenaria Bella");

            long office = proposal(p, marc, "Home office", "Bancada em L e estante.", "OFFICE", beatriz, -9);
            version(office, 1, "PENDING_REVIEW", null, beatriz, false,
                    SeedFiles.png("Home office", new Color(0x5E6472)), -9);
            long jantar = proposal(p, null, "Sala de jantar", null, "DINING_ROOM", rafael, -25);
            long jantarV1 = version(jantar, 1, "APPROVED", "Mesa para 8 lugares.", rafael, true,
                    SeedFiles.png("Sala de jantar (IA)", new Color(0x9C6644)), -24);
            approval(jantarV1, rafael, "APPROVED", "Cliente aprovou por WhatsApp.", -22);
            preview(jantar, "READY", rafael, jantarV1, null, -24, "DINING_ROOM", "CONTEMPORARY", false);
            long garagem = proposal(p, null, "Garagem", null, "GARAGE", beatriz, -2);
            version(garagem, 1, "DRAFT", null, beatriz, false, null, -2);

            attachment(p, null, null, rafael, "Levantamento fotográfico.png",
                    SeedFiles.png("Levantamento fotográfico", new Color(0x6B705C)), -60);
            diary(p, -2, 10, 0, "DELIVERY", beatriz, "Chegada das chapas de MDF.", null);
        }

        // -------------------------------------------------------------- helpers de obra

        private long project(long workspaceId, String title, String type, String category, String status,
                             Integer startOffset, Integer endOffset, String landArea, String builtArea,
                             String cep, String street, String number, String city, String state, String complement) {
            OffsetDateTime created = at(startOffset == null ? -1 : Math.min(startOffset, 0) - 10, 9, 0);
            return insert("""
                    INSERT INTO construction_projects (workspace_id, title, project_type, category, status,
                        planned_start_date, planned_end_date, land_area, built_area,
                        cep, street, number, city, state, complement, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    workspaceId, title, type, category, status, day(startOffset), day(endOffset),
                    new BigDecimal(landArea), new BigDecimal(builtArea),
                    cep, street, number, city, state, complement, created, created);
        }

        private void member(long projectId, User user, String role, String status, int joinedOffset) {
            jdbc.update("""
                    INSERT INTO construction_project_members (construction_project_id, user_id, role_in_project,
                                                              membership_status, joined_at)
                    VALUES (?, ?, ?, ?, ?)""",
                    projectId, user.id(), role, status, at(joinedOffset, 9, 0));
        }

        private void rolePermissions(long projectId, String role, String... permissions) {
            for (String permission : permissions) {
                jdbc.update("""
                        INSERT INTO project_role_permissions (construction_project_id, role_in_project, permission)
                        VALUES (?, ?, ?)""", projectId, role, permission);
            }
        }

        private long stage(long projectId, int order, String name, String description, String status,
                           Integer plannedStart, Integer plannedEnd, Integer actualStart, Integer actualEnd,
                           String pavimento) {
            return insert("""
                    INSERT INTO stages (construction_project_id, name, description, display_order, status,
                        planned_start_date, planned_end_date, actual_start_date, actual_end_date, pavimento)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    projectId, name, description, order, status, day(plannedStart), day(plannedEnd),
                    day(actualStart), day(actualEnd), pavimento);
        }

        /** {@code assignee} null + {@code externalName} = responsável externo (terceirizado). */
        private void task(long stageId, User assignee, String externalName, String title, String description,
                          String priority, String status, int startOffset, int endOffset) {
            OffsetDateTime completedAt = "DONE".equals(status) ? at(Math.min(endOffset, 0), 17, 0) : null;
            jdbc.update("""
                    INSERT INTO tasks (stage_id, assignee_user_id, assignee_name, title, description, priority,
                        status, planned_start_date, planned_end_date, completed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    stageId, assignee == null ? null : assignee.id(), externalName, title, description,
                    priority, status, day(startOffset), day(endOffset), completedAt);
        }

        private long budget(long projectId, String description, String plannedTotal) {
            return insert("""
                    INSERT INTO project_budgets (construction_project_id, description, planned_total)
                    VALUES (?, ?, ?)""", projectId, description, new BigDecimal(plannedTotal));
        }

        private long item(long budgetId, String category, String description, String plannedAmount) {
            return insert("""
                    INSERT INTO budget_items (project_budget_id, category, description, planned_amount)
                    VALUES (?, ?, ?, ?)""", budgetId, category, description, new BigDecimal(plannedAmount));
        }

        private void expense(long itemId, Long stageId, String description, String amount, String supplier,
                             int spentOffset) {
            jdbc.update("""
                    INSERT INTO expenses (budget_item_id, stage_id, description, amount, supplier, spent_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    itemId, stageId, description, new BigDecimal(amount), supplier, day(spentOffset),
                    at(spentOffset, 18, 0));
        }

        /**
         * Divide {@code total} em {@code count} gastos entre dois dias, com
         * variação determinística: a curva S fica com cara de medição real e a
         * soma bate exatamente com o total.
         */
        private void spread(long itemId, Long stageId, String total, int count, int fromOffset, int toOffset,
                            String description, String supplier) {
            BigDecimal remaining = new BigDecimal(total);
            BigDecimal share = remaining.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
            for (int i = 0; i < count; i++) {
                BigDecimal amount;
                if (i == count - 1) {
                    amount = remaining;
                } else {
                    double factor = 0.75 + random.nextDouble() * 0.5;
                    amount = share.multiply(BigDecimal.valueOf(factor)).setScale(2, RoundingMode.HALF_UP);
                    remaining = remaining.subtract(amount);
                }
                int offset = count == 1 ? fromOffset : fromOffset + (toOffset - fromOffset) * i / (count - 1);
                String label = count == 1 ? description : description + " (" + (i + 1) + "/" + count + ")";
                expense(itemId, stageId, label, amount.toPlainString(), supplier, offset);
            }
        }

        private long attachment(long projectId, Long stageId, Long taskId, User uploader, String fileName,
                                GeneratedFile file, int uploadedOffset) {
            String key = store(file, "seed/projects/" + projectId);
            return insert("""
                    INSERT INTO attachments (construction_project_id, stage_id, task_id, uploaded_by_user_id,
                        file_name, file_type, file_url, uploaded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                    projectId, stageId, taskId, uploader.id(), fileName, file.contentType(), key,
                    at(uploadedOffset, 12, 0));
        }

        private void diary(long projectId, int dayOffset, int hour, int minute, String type, User responsible,
                           String description, Long attachmentId) {
            OffsetDateTime when = at(dayOffset, hour, minute);
            jdbc.update("""
                    INSERT INTO construction_diary_entries (construction_project_id, entry_date, entry_type,
                        responsible_user_id, responsible_name, description, attachment_id, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    projectId, when, type, responsible.id(), responsible.name(), description, attachmentId,
                    when, when);
        }

        private void scheduleMember(long projectId, User user, String responsibility) {
            jdbc.update("""
                    INSERT INTO schedule_members (construction_project_id, user_id, user_responsibility)
                    VALUES (?, ?, ?)""", projectId, user.id(), responsibility);
        }

        /** Horas em dias úteis; sábado só quando {@code saturdayHours} vem preenchido. */
        private void allocations(long projectId, User user, int fromOffset, int toOffset, String weekdayHours,
                                 String saturdayHours) {
            for (int offset = fromOffset; offset <= toOffset; offset++) {
                LocalDate date = today.plusDays(offset);
                DayOfWeek dow = date.getDayOfWeek();
                String hours = dow == DayOfWeek.SUNDAY ? null
                        : dow == DayOfWeek.SATURDAY ? saturdayHours
                        : weekdayHours;
                if (hours == null) {
                    continue;
                }
                jdbc.update("""
                        INSERT INTO schedule_allocations (construction_project_id, user_id, allocation_date, hours)
                        VALUES (?, ?, ?, ?)""", projectId, user.id(), date, new BigDecimal(hours));
            }
        }

        private long proposal(long projectId, Long stageId, String title, String description, String environment,
                              User creator, int createdOffset) {
            OffsetDateTime created = at(createdOffset, 10, 0);
            return insert("""
                    INSERT INTO design_proposals (construction_project_id, stage_id, title, description,
                        environment_type, created_by_user_id, created_by_name, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    projectId, stageId, title, description, environment, creator.id(), creator.name(),
                    created, created);
        }

        private long version(long proposalId, int number, String status, String description, User author,
                             boolean ai, GeneratedFile file, int submittedOffset) {
            OffsetDateTime submitted = at(submittedOffset, 14, 0);
            String key = file == null ? null : store(file, "seed/proposals/" + proposalId);
            String fileName = file == null ? null : "proposta-v" + number + "." + file.extension();
            long id = insert("""
                    INSERT INTO design_submissions (proposal_id, version, status, description, author_user_id,
                        author_name, generated_by_ai, file_url, file_name, file_type, submitted_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    proposalId, number, status, description, author.id(), author.name(), ai, key, fileName,
                    file == null ? null : file.contentType(), submitted, submitted);
            jdbc.update("UPDATE design_proposals SET updated_at = GREATEST(updated_at, ?) WHERE id = ?",
                    submitted, proposalId);
            return id;
        }

        private void approval(long submissionId, User approver, String status, String comment, int offset) {
            jdbc.update("""
                    INSERT INTO design_approvals (design_submission_id, approver_user_id, approval_status,
                        comment, approved_at)
                    VALUES (?, ?, ?, ?, ?)""",
                    submissionId, approver.id(), status, comment, at(offset, 16, 0));
        }

        /**
         * Prévia de IA já concluída (READY) ou falha (FAILED). Nunca PROCESSING:
         * a tela ficaria fazendo polling de um job que não existe.
         */
        private void preview(long proposalId, String status, User requester, Long resultSubmissionId,
                             String errorMessage, int createdOffset, String environment, String style,
                             boolean withFloorPlan) {
            String rawKey = store(SeedFiles.png("Foto do ambiente", new Color(0x8D8D8D)),
                    "seed/previews/" + proposalId);
            String planKey = withFloorPlan
                    ? store(SeedFiles.floorPlanPng("Planta marcada"), "seed/previews/" + proposalId)
                    : null;
            String options = """
                    {"environment":"%s","style":"%s","colors":["OFF_WHITE","NATURAL_WOOD"],"lighting":"WARM_INDIRECT",\
                    "flooring":"WOOD","generationMode":"PREVIEW","additionalInstructions":"Manter a janela original."}"""
                    .formatted(environment, style);
            jdbc.update("""
                    INSERT INTO ai_environment_previews (proposal_id, status, raw_image_key, floor_plan_key,
                        options_json, result_submission_id, error_message, requested_by_user_id,
                        created_at, completed_at)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)""",
                    proposalId, status, rawKey, planKey, options, resultSubmissionId, errorMessage,
                    requester.id(), at(createdOffset, 13, 0), at(createdOffset, 13, 1));
        }

        private String store(GeneratedFile file, String namespace) {
            String key = storage.store(new ByteArrayInputStream(file.bytes()), file.extension(), namespace)
                    .storageKey();
            newKeys.add(key);
            return key;
        }
    }

    // ------------------------------------------------------------------ utilitários

    private long insert(String sql, Object... args) {
        Long id = jdbc.queryForObject(sql + " RETURNING id", Long.class, args);
        if (id == null) {
            throw new IllegalStateException("INSERT sem id: " + sql);
        }
        return id;
    }

    private LocalDate day(Integer offset) {
        return offset == null ? null : today.plusDays(offset);
    }

    private OffsetDateTime at(int dayOffset, int hour, int minute) {
        return today.plusDays(dayOffset).atTime(hour, minute).atZone(zone).toOffsetDateTime();
    }

    private static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
