package dev.applymcp.prequal.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Proves the real-API path works without a real API: a tiny local HTTP server plays the part of the
 * gateway, and we check the requests HttpPrequalBackend sends and how it reads the responses.
 */
class HttpPrequalBackendTest {

    private HttpServer stub;
    private final Map<String, HttpExchangeRecord> received = new ConcurrentHashMap<>();
    private HttpPrequalBackend backend;

    @BeforeEach
    void start() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stub.createContext("/v1/prequal/offers", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/accept")) {
                record("accept", exchange);
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                if ("conflict-key".equals(key)) {
                    respond(exchange, 409, "{\"code\":\"IDEMPOTENCY_KEY_REUSED\",\"message\":\"reused\"}");
                } else {
                    respond(exchange, 201, "{\"applicationId\":\"APP-1\",\"statusCd\":\"A\"}");
                }
            } else {
                record("offers", exchange);
                respond(exchange, 200, """
                        {"prequalId":"PQ-9","decisionCd":"A1","offers":[{"offerId":"OF-9","productCd":"CB01",
                        "productName":"Cashback Card","creditLine":3000,"purchaseApr":27.99,
                        "expiresAt":"2026-11-01T00:00:00Z"}]}""");
            }
        });
        stub.createContext("/v1/applications", exchange -> {
            record("status", exchange);
            respond(exchange, 500, "oops");
        });
        stub.start();

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String baseUrl = "http://localhost:" + stub.getAddress().getPort();
        backend = new HttpPrequalBackend(
                new PrequalHttpProperties(baseUrl, "client-1", "key-1", null, Duration.ofSeconds(1), Duration.ofSeconds(2)),
                RestClient.builder(), mapper);
    }

    @AfterEach
    void stop() {
        stub.stop(0);
    }

    @Test
    void getOffersSendsGatewayHeadersAndParsesBody() {
        var response = backend.getOffers(new PrequalApi.OffersRequest(
                new PrequalApi.Applicant("Jane", "Doe", LocalDate.of(1990, 4, 12), "6789",
                        new PrequalApi.Address("100 Main St", "Dayton", "OH", "45402"), 85000),
                new PrequalApi.Consent(true, Instant.parse("2026-10-02T12:00:00Z"))), "corr-1");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().decisionCd()).isEqualTo("A1");
        assertThat(response.body().offers().get(0).offerId()).isEqualTo("OF-9");

        HttpExchangeRecord req = received.get("offers");
        assertThat(req.headers()).containsEntry("X-client-id", "client-1")
                .containsEntry("X-api-key", "key-1")
                .containsEntry("X-channel", "AI_AGENT")
                .containsEntry("X-correlation-id", "corr-1");
        assertThat(req.body()).contains("\"softInquiry\":true");
    }

    @Test
    void acceptSendsIdempotencyKeyAndMapsConflictCode() {
        var ok = backend.acceptOffer("OF-9", new PrequalApi.AcceptRequest("PQ-9", Instant.now()), "key-abc", "corr-2");
        assertThat(ok.status()).isEqualTo(201);
        assertThat(received.get("accept").headers()).containsEntry("Idempotency-key", "key-abc");

        var conflict = backend.acceptOffer("OF-9", new PrequalApi.AcceptRequest("PQ-9", Instant.now()), "conflict-key", "corr-3");
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.errorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void serverErrorIsReportedNotThrown() {
        var response = backend.getStatus("APP-1", "corr-4");
        assertThat(response.status()).isEqualTo(500);
        assertThat(response.isSuccess()).isFalse();
    }

    @Test
    void unreachableBackendBecomesTimeout() {
        HttpPrequalBackend dead = new HttpPrequalBackend(
                new PrequalHttpProperties("http://localhost:1", "c", "k", null, Duration.ofMillis(300), Duration.ofMillis(300)),
                RestClient.builder(), new ObjectMapper());
        assertThat(dead.getStatus("APP-1", "corr-5").errorCode()).isEqualTo("BACKEND_TIMEOUT");
    }

    @Test
    void missingSettingsFailAtStartup() {
        assertThatThrownBy(() -> new HttpPrequalBackend(
                new PrequalHttpProperties("", "c", "k", null, null, null), RestClient.builder(), new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PREQUAL_BASE_URL");
    }

    private void record(String name, HttpExchange exchange) throws IOException {
        Map<String, String> headers = new ConcurrentHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, v.get(0)));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.put(name, new HttpExchangeRecord(headers, body));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private record HttpExchangeRecord(Map<String, String> headers, String body) {
    }
}
