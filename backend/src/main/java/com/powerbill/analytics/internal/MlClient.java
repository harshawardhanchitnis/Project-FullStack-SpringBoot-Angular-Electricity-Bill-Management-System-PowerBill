package com.powerbill.analytics.internal;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** Internal client for the FastAPI ML service. Every call degrades to an empty result when the service is down. */
@Component
class MlClient {
    private static final Logger LOG = LoggerFactory.getLogger(MlClient.class);
    private final RestClient client;
    private final RestClient fastClient;
    private final boolean enabled;

    MlClient(@Value("${gridiq.ml.enabled:true}") boolean enabled,
            @Value("${gridiq.ml.url:http://ml-service:8000}") String url,
            @Value("${gridiq.ml.api-key:}") String configuredKey,
            @Value("${gridiq.ml.generated-api-key:}") String generatedKey,
            @Value("${gridiq.ml.timeout:PT25S}") Duration timeout) {
        String apiKey = configuredKey.isBlank() ? generatedKey.strip() : configuredKey.strip();
        this.enabled = enabled && !apiKey.isBlank();
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        // Forecasts and anomaly scans fail fast to the run-rate fallback; the copilot may wait for a local LLM.
        var fastFactory = new JdkClientHttpRequestFactory(http);
        fastFactory.setReadTimeout(Duration.ofSeconds(8));
        this.client = RestClient.builder().baseUrl(url).requestFactory(factory)
                .defaultHeader("X-Internal-Api-Key", apiKey).build();
        this.fastClient = RestClient.builder().baseUrl(url).requestFactory(fastFactory)
                .defaultHeader("X-Internal-Api-Key", apiKey).build();
        if (!this.enabled) {
            LOG.info("The ML service is disabled; forecasts use the run-rate fallback.");
        }
    }

    boolean enabled() {
        return enabled;
    }

    Optional<JsonNode> post(String path, Object body, String correlationId) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            RestClient target = path.contains("/copilot/") ? client : fastClient;
            return Optional.ofNullable(target.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                    .header("X-Request-Id", correlationId == null ? "gridiq-core" : correlationId)
                    .body(body).retrieve().body(JsonNode.class));
        } catch (RestClientException error) {
            LOG.warn("ML service call to {} failed ({})", path, error.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
