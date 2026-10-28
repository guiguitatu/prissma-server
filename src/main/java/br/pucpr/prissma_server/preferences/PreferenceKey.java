package br.pucpr.prissma_server.preferences;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Chaves de preferência aceitas, cada uma com a validação do próprio formato.
 *
 * Uma tela nova que queira salvar preferência entra aqui — a tabela é
 * genérica, mas o servidor não guarda JSON arbitrário.
 */
public enum PreferenceKey {

    /** Layout dos widgets da Visão Geral da obra, igual em todas as obras. */
    PROJECT_OVERVIEW_LAYOUT("obra.visao-geral.layout", OverviewLayoutValidator::validate);

    private final String key;
    private final Consumer<JsonNode> validator;

    PreferenceKey(String key, Consumer<JsonNode> validator) {
        this.key = key;
        this.validator = validator;
    }

    public String key() {
        return key;
    }

    /** @throws ResponseStatusException 400 com o motivo, se o valor não serve */
    public void validate(JsonNode value) {
        validator.accept(value);
    }

    /** Chave desconhecida é 404: a "preferência" pedida simplesmente não existe. */
    public static PreferenceKey fromKey(String key) {
        return Arrays.stream(values())
                .filter(k -> k.key.equals(key))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown preference: " + key));
    }
}
