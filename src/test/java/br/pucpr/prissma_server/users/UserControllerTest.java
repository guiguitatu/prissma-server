package br.pucpr.prissma_server.users;

import br.pucpr.prissma_server.task.TaskResponse;
import br.pucpr.prissma_server.task.TaskService;
import br.pucpr.prissma_server.notifications.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // O WorkspaceContextFilter (bean de Filter) entra no slice web mesmo com
    // addFilters=false — o mock satisfaz a construção dele.
    @MockitoBean
    private br.pucpr.prissma_server.workspaces.WorkspaceService workspaceService;

    @MockitoBean
    private UserService service;

    @MockitoBean
    private UserValidator validator;

    @MockitoBean
    private TaskService taskService;

    // O UserController recebe o NotificationService no construtor. Num
    // @WebMvcTest so os beans do slice web sao criados, entao sem este mock o
    // contexto nem sobe: falha com NoSuchBeanDefinitionException antes de
    // executar qualquer teste.
    @MockitoBean
    private NotificationService notificationService;

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(10L, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    @DisplayName("GET /users/me/tasks returns the authenticated user's tasks")
    void getMyTasks_returnsTasks() throws Exception {
        TaskResponse task = new TaskResponse(
                100L,
                2L,
                1L,
                "Fundação",
                10L,
                "João Silva",
                "ENG",
                "Escavação",
                "Executar escavação",
                "HIGH",
                "TODO",
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 5),
                Instant.parse("2026-06-01T10:00:00Z")
        );

        when(taskService.listAssignedToUser(10L)).thenReturn(List.of(task));

        mockMvc.perform(get("/users/me/tasks")
                        .principal(auth())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(100L))
                .andExpect(jsonPath("$[0].stageId").value(2L))
                .andExpect(jsonPath("$[0].projectId").value(1L))
                .andExpect(jsonPath("$[0].stageName").value("Fundação"))
                .andExpect(jsonPath("$[0].assigneeUserId").value(10L))
                .andExpect(jsonPath("$[0].assigneeName").value("João Silva"))
                .andExpect(jsonPath("$[0].status").value("TODO"));
    }
}
