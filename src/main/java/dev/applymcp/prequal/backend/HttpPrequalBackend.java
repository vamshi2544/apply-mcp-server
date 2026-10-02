package dev.applymcp.prequal.backend;

import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Real backend: calls the prequal APIs through the API gateway (Apigee today, possibly Kong later).
 *
 * Gateway-specific details (base URL, client id, API key header) live only here and in configuration,
 * so a gateway change does not touch the tool layer.
 *
 * Retry policy: none in this class. Reads may be retried by the caller; accept must never be
 * retried automatically, because only the agent (with the same idempotency key) may decide that.
 */
@Component
@ConditionalOnProperty(name = "prequal.backend", havingValue = "http")
public class HttpPrequalBackend implements PrequalBackend {

    private static final Logger log = LoggerFactory.getLogger(HttpPrequalBackend.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpPrequalBackend(PrequalHttpProperties props, RestClient.Builder builder, ObjectMapper objectMapper) {
        requireSetting(props.baseUrl(), "prequal.http.base-url", "PREQUAL_BASE_URL");
        requireSetting(props.clientId(), "prequal.http.client-id", "PREQUAL_CLIENT_ID");
        requireSetting(props.apiKey(), "prequal.http.api-key", "PREQUAL_API_KEY");

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) props.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) props.readTimeout().toMillis());

        this.restClient = builder
                .baseUrl(props.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("X-Client-Id", props.clientId())
                .defaultHeader("X-Api-Key", props.apiKey())
                .defaultHeader("X-Channel", props.channel())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper;
        log.warn("Prequal backend: REAL APIs via {} (prequal.backend=http)", props.baseUrl());
    }

    /** Fail at startup, not on the first tool call, when real mode is selected without its settings. */
    private static void requireSetting(String value, String property, String envVar) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("prequal.backend=http but " + property + " is not set. Set environment variable "
                    + envVar + " or add it to config/application-real.yml. To use the mock instead, set prequal.backend=mock.");
        }
    }

    @Override
    public BackendResponse<PrequalApi.OffersResponse> getOffers(PrequalApi.OffersRequest request, String correlationId) {
        return call(() -> restClient.post()
                .uri("/v1/prequal/offers")
                .header("X-Correlation-Id", correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(PrequalApi.OffersResponse.class));
    }

    @Override
    public BackendResponse<PrequalApi.AcceptResponse> acceptOffer(String offerId, PrequalApi.AcceptRequest request,
                                                                  String idempotencyKey, String correlationId) {
        return call(() -> restClient.post()
                .uri("/v1/prequal/offers/{offerId}/accept", offerId)
                .header("X-Correlation-Id", correlationId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(PrequalApi.AcceptResponse.class));
    }

    @Override
    public BackendResponse<PrequalApi.StatusResponse> getStatus(String applicationId, String correlationId) {
        return call(() -> restClient.get()
                .uri("/v1/applications/{applicationId}/status", applicationId)
                .header("X-Correlation-Id", correlationId)
                .retrieve()
                .toEntity(PrequalApi.StatusResponse.class));
    }

    private <T> BackendResponse<T> call(Supplier<ResponseEntity<T>> request) {
        try {
            ResponseEntity<T> entity = request.get();
            return BackendResponse.ok(entity.getStatusCode().value(), entity.getBody());
        } catch (RestClientResponseException e) {
            return BackendResponse.error(e.getStatusCode().value(), parseErrorCode(e), "Backend returned an error");
        } catch (ResourceAccessException e) {
            log.warn("Backend unreachable or timed out: {}", e.getClass().getSimpleName());
            return BackendResponse.error(504, "BACKEND_TIMEOUT", "Backend did not respond in time");
        }
    }

    private String parseErrorCode(RestClientResponseException e) {
        try {
            PrequalApi.ApiError error = objectMapper.readValue(e.getResponseBodyAsByteArray(), PrequalApi.ApiError.class);
            return error.code();
        } catch (Exception ignored) {
            return "HTTP_" + e.getStatusCode().value();
        }
    }
}
