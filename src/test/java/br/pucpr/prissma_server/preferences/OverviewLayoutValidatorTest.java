package br.pucpr.prissma_server.preferences;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OverviewLayoutValidator")
class OverviewLayoutValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw.replace('\'', '"'));
    }

    @Test
    @DisplayName("aceita um layout bem formado, inclusive com widget que o servidor não conhece")
    void acceptsValidLayout() throws Exception {
        JsonNode layout = json("""
                {'version': 1, 'widgets': [
                  {'id': 'attention', 'visible': true, 'size': 'wide'},
                  {'id': 'budget', 'visible': false, 'size': 'narrow'},
                  {'id': 'widget-do-futuro', 'visible': true, 'size': 'narrow'}
                ]}""");

        assertThatCode(() -> OverviewLayoutValidator.validate(layout)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("aceita lista vazia (tudo removido)")
    void acceptsEmptyList() throws Exception {
        assertThatCode(() -> OverviewLayoutValidator.validate(json("{'version': 1, 'widgets': []}")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "[]",
            "{'widgets': []}",
            "{'version': 2, 'widgets': []}",
            "{'version': '1', 'widgets': []}",
            "{'version': 1}",
            "{'version': 1, 'widgets': {}}",
            "{'version': 1, 'widgets': [], 'extra': true}",
            "{'version': 1, 'widgets': ['budget']}",
            "{'version': 1, 'widgets': [{'id': 'Budget', 'visible': true, 'size': 'wide'}]}",
            "{'version': 1, 'widgets': [{'id': 'b', 'visible': true, 'size': 'wide'}]}",
            "{'version': 1, 'widgets': [{'id': 'budget', 'visible': 'yes', 'size': 'wide'}]}",
            "{'version': 1, 'widgets': [{'id': 'budget', 'visible': true, 'size': 'huge'}]}",
            "{'version': 1, 'widgets': [{'id': 'budget', 'visible': true}]}",
            "{'version': 1, 'widgets': [{'id': 'budget', 'visible': true, 'size': 'wide', 'x': 1}]}",
            "{'version': 1, 'widgets': [{'id': 'budget', 'visible': true, 'size': 'wide'},"
                    + " {'id': 'budget', 'visible': false, 'size': 'narrow'}]}"
    })
    @DisplayName("recusa layout malformado com 400")
    void rejectsInvalidLayout(String raw) throws Exception {
        JsonNode layout = json(raw);

        assertThatThrownBy(() -> OverviewLayoutValidator.validate(layout))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("recusa mais widgets que o teto")
    void rejectsTooManyWidgets() throws Exception {
        StringBuilder widgets = new StringBuilder();
        for (int i = 0; i <= OverviewLayoutValidator.MAX_WIDGETS; i++) {
            if (i > 0) {
                widgets.append(',');
            }
            widgets.append("{'id': 'w").append(i).append("x', 'visible': true, 'size': 'wide'}");
        }
        JsonNode layout = json("{'version': 1, 'widgets': [" + widgets + "]}");

        assertThatThrownBy(() -> OverviewLayoutValidator.validate(layout))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no máximo");
    }
}
