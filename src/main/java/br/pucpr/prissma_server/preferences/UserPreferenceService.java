package br.pucpr.prissma_server.preferences;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

/**
 * Preferências de interface do usuário autenticado.
 *
 * Não existe leitura nem escrita de preferência de OUTRO usuário: o id vem
 * sempre do token, e não há rota que o receba como parâmetro.
 */
@Service
public class UserPreferenceService {

    /** Layout de tela é pequeno; o teto impede usar a tabela como armazenamento. */
    static final int MAX_VALUE_BYTES = 16 * 1024;

    private final UserPreferenceRepository repository;
    private final ObjectMapper objectMapper;

    public UserPreferenceService(UserPreferenceRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Optional<PreferenceResponse> get(Long userId, String key) {
        PreferenceKey preferenceKey = PreferenceKey.fromKey(key);
        return repository.findByUserIdAndPrefKey(userId, preferenceKey.key())
                .map(this::toResponse);
    }

    @Transactional
    public PreferenceResponse put(Long userId, String key, JsonNode value) {
        PreferenceKey preferenceKey = PreferenceKey.fromKey(key);
        preferenceKey.validate(value);

        String json = write(value);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Preferência maior que " + MAX_VALUE_BYTES / 1024 + " KB");
        }

        Instant now = Instant.now();
        UserPreference preference = repository.findByUserIdAndPrefKey(userId, preferenceKey.key())
                .orElseGet(() -> {
                    UserPreference created = new UserPreference();
                    created.setUserId(userId);
                    created.setPrefKey(preferenceKey.key());
                    created.setCreatedAt(now);
                    return created;
                });
        preference.setValue(json);
        preference.setUpdatedAt(now);
        return toResponse(repository.save(preference));
    }

    /** "Restaurar padrão". Idempotente: apagar o que não existe não é erro. */
    @Transactional
    public void delete(Long userId, String key) {
        PreferenceKey preferenceKey = PreferenceKey.fromKey(key);
        repository.deleteByUserIdAndPrefKey(userId, preferenceKey.key());
    }

    private PreferenceResponse toResponse(UserPreference preference) {
        return new PreferenceResponse(preference.getPrefKey(), read(preference.getValue()), preference.getUpdatedAt());
    }

    private String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valor de preferência inválido");
        }
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Preferência gravada com JSON inválido", e);
        }
    }
}
