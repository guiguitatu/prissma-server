package br.pucpr.prissma_server.preferences;

import br.pucpr.prissma_server.EmbeddedPostgresConfig;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fluxo completo contra o Postgres real: a coluna é jsonb, então ida e volta
 * pelo banco é o que importa testar aqui.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(EmbeddedPostgresConfig.class)
@DisplayName("/users/me/preferences")
class UserPreferenceControllerIntegrationTest {

    private static final String URL = "/users/me/preferences/obra.visao-geral.layout";

    private static final String LAYOUT = """
            {"version":1,"widgets":[
              {"id":"budget","visible":true,"size":"narrow"},
              {"id":"attention","visible":true,"size":"wide"},
              {"id":"documents","visible":false,"size":"narrow"}]}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserPreferenceRepository preferenceRepository;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = user("Alice");
        bob = user("Bob");
    }

    private User user(String name) {
        User user = new User();
        user.setEmail(name.toLowerCase() + System.nanoTime() + "@example.com");
        user.setName(name);
        user.setPassword("password123");
        user.setRole(Role.ENG);
        return userRepository.save(user);
    }

    private RequestPostProcessor as(User user) {
        return authentication(new UsernamePasswordAuthenticationToken(
                user.getId(), null, List.of(new SimpleGrantedAuthority("ROLE_ENG"))));
    }

    @Test
    @DisplayName("sem token responde 401")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("nunca salvo responde 204")
    void noContentWhenNeverSaved() throws Exception {
        mockMvc.perform(get(URL).with(as(alice))).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PUT salva e o GET devolve o mesmo layout")
    void saveAndReadBack() throws Exception {
        mockMvc.perform(put(URL).with(as(alice)).contentType(MediaType.APPLICATION_JSON).content(LAYOUT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("obra.visao-geral.layout"))
                .andExpect(jsonPath("$.value.widgets[0].id").value("budget"))
                .andExpect(jsonPath("$.updatedAt").exists());

        mockMvc.perform(get(URL).with(as(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value.version").value(1))
                .andExpect(jsonPath("$.value.widgets.length()").value(3))
                .andExpect(jsonPath("$.value.widgets[2].visible").value(false));
    }

    @Test
    @DisplayName("um segundo PUT atualiza a mesma linha em vez de duplicar")
    void putUpserts() throws Exception {
        mockMvc.perform(put(URL).with(as(alice)).contentType(MediaType.APPLICATION_JSON).content(LAYOUT))
                .andExpect(status().isOk());
        mockMvc.perform(put(URL).with(as(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"widgets\":[]}"))
                .andExpect(status().isOk());

        assertThat(preferenceRepository.findAll())
                .filteredOn(p -> p.getUserId().equals(alice.getId()))
                .hasSize(1);
        mockMvc.perform(get(URL).with(as(alice)))
                .andExpect(jsonPath("$.value.widgets.length()").value(0));
    }

    @Test
    @DisplayName("cada usuário só enxerga a própria preferência")
    void preferencesAreIsolatedPerUser() throws Exception {
        mockMvc.perform(put(URL).with(as(alice)).contentType(MediaType.APPLICATION_JSON).content(LAYOUT))
                .andExpect(status().isOk());

        mockMvc.perform(get(URL).with(as(bob))).andExpect(status().isNoContent());

        mockMvc.perform(delete(URL).with(as(bob))).andExpect(status().isNoContent());
        mockMvc.perform(get(URL).with(as(alice))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("DELETE restaura o padrão e é idempotente")
    void deleteResets() throws Exception {
        mockMvc.perform(put(URL).with(as(alice)).contentType(MediaType.APPLICATION_JSON).content(LAYOUT))
                .andExpect(status().isOk());

        mockMvc.perform(delete(URL).with(as(alice))).andExpect(status().isNoContent());
        mockMvc.perform(delete(URL).with(as(alice))).andExpect(status().isNoContent());
        mockMvc.perform(get(URL).with(as(alice))).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("chave desconhecida responde 404")
    void unknownKey() throws Exception {
        mockMvc.perform(get("/users/me/preferences/qualquer.coisa").with(as(alice)))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/users/me/preferences/qualquer.coisa").with(as(alice))
                        .contentType(MediaType.APPLICATION_JSON).content(LAYOUT))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("layout inválido ou corpo que não é JSON respondem 400")
    void invalidBody() throws Exception {
        mockMvc.perform(put(URL).with(as(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"widgets\":[{\"id\":\"budget\",\"visible\":true,\"size\":\"huge\"}]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(URL).with(as(alice)).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get(URL).with(as(alice))).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("excluir o usuário apaga as preferências dele (ON DELETE CASCADE)")
    void cascadeOnUserDelete() throws Exception {
        mockMvc.perform(put(URL).with(as(alice)).contentType(MediaType.APPLICATION_JSON).content(LAYOUT))
                .andExpect(status().isOk());
        preferenceRepository.flush();

        userRepository.delete(alice);
        userRepository.flush();

        assertThat(preferenceRepository.findByUserIdAndPrefKey(alice.getId(), "obra.visao-geral.layout")).isEmpty();
    }
}
