package br.pucpr.prissma_server.preferences;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record PreferenceResponse(String key, JsonNode value, Instant updatedAt) {
}
