package br.pucpr.prissma_server.preferences;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Formato do layout da Visão Geral:
 *
 * <pre>
 * { "version": 1,
 *   "widgets": [ { "id": "budget", "visible": true, "size": "narrow" }, … ] }
 * </pre>
 *
 * O servidor valida só a FORMA. Quais widgets existem é decisão do front (o
 * catálogo mora lá): um widget novo não exige deploy do servidor, e o front
 * descarta ids que não conhece ao ler um layout antigo.
 */
final class OverviewLayoutValidator {

    static final int VERSION = 1;
    static final int MAX_WIDGETS = 30;
    static final Pattern WIDGET_ID = Pattern.compile("^[a-z][a-z0-9-]{1,40}$");
    static final Set<String> SIZES = Set.of("wide", "narrow");
    static final Set<String> WIDGET_FIELDS = Set.of("id", "visible", "size");

    private OverviewLayoutValidator() {
    }

    static void validate(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw invalid("o layout precisa ser um objeto");
        }
        JsonNode version = value.get("version");
        if (version == null || !version.isInt() || version.intValue() != VERSION) {
            throw invalid("version precisa ser " + VERSION);
        }
        JsonNode widgets = value.get("widgets");
        if (widgets == null || !widgets.isArray()) {
            throw invalid("widgets precisa ser uma lista");
        }
        if (widgets.size() > MAX_WIDGETS) {
            throw invalid("no máximo " + MAX_WIDGETS + " widgets");
        }
        if (value.size() != 2) {
            throw invalid("campos permitidos: version e widgets");
        }

        Set<String> seen = new HashSet<>();
        for (JsonNode widget : widgets) {
            validateWidget(widget, seen);
        }
    }

    private static void validateWidget(JsonNode widget, Set<String> seen) {
        if (!widget.isObject()) {
            throw invalid("cada widget precisa ser um objeto");
        }
        Iterator<String> fields = widget.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!WIDGET_FIELDS.contains(field)) {
                throw invalid("campo desconhecido no widget: " + field);
            }
        }

        JsonNode id = widget.get("id");
        if (id == null || !id.isTextual() || !WIDGET_ID.matcher(id.textValue()).matches()) {
            throw invalid("id de widget inválido");
        }
        if (!seen.add(id.textValue())) {
            throw invalid("widget repetido: " + id.textValue());
        }

        JsonNode visible = widget.get("visible");
        if (visible == null || !visible.isBoolean()) {
            throw invalid("visible precisa ser true ou false (" + id.textValue() + ")");
        }

        JsonNode size = widget.get("size");
        if (size == null || !size.isTextual() || !SIZES.contains(size.textValue())) {
            throw invalid("size precisa ser wide ou narrow (" + id.textValue() + ")");
        }
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Layout inválido: " + reason);
    }
}
