# Prompt 2 of 3: real HTTP backend, mock/real switch, and tests

This project is a Spring Boot 3.5 + Spring AI 1.1 MCP server built by prompt 1. First, read the project: `pom.xml`, `application.yml`, everything under `prequal/backend` and `prequal/tool`. Confirm that `MockPrequalBackend`, `PrequalTools`, `PrequalMapper` exist and that `mvn -B compile` succeeds. If not: **STOP — ASK USER**.

Do not change tool names, tool descriptions or the `PrequalBackend` interface. Do not add real endpoints, real credentials or real customer data anywhere.

## Step 1: Real HTTP backend

`prequal/backend/PrequalHttpProperties.java`: `@ConfigurationProperties(prefix = "prequal.http")` record with `baseUrl`, `clientId`, `apiKey`, `channel`, `connectTimeout` (Duration), `readTimeout` (Duration). Compact constructor defaults: channel `AI_AGENT`, connect 2s, read 10s.

`prequal/backend/HttpPrequalBackend.java`: `@Component` with `@ConditionalOnProperty(name = "prequal.backend", havingValue = "http")`. Constructor takes `PrequalHttpProperties`, `RestClient.Builder`, `ObjectMapper`.

- Fail fast: if baseUrl, clientId or apiKey is blank, throw `IllegalStateException` naming the property and the env var, for example: `prequal.backend=http but prequal.http.base-url is not set. Set environment variable PREQUAL_BASE_URL or add it to config/application-real.yml. To use the mock instead, set prequal.backend=mock.`
- Build a `RestClient` with a `SimpleClientHttpRequestFactory` using the timeouts, base URL, and default headers `X-Client-Id`, `X-Api-Key`, `X-Channel`, `Accept: application/json`.
- Log at WARN after construction: `Prequal backend: REAL APIs via <baseUrl> (prequal.backend=http)`.
- `getOffers`: POST `/v1/prequal/offers`, header `X-Correlation-Id`, JSON body.
- `acceptOffer`: POST `/v1/prequal/offers/{offerId}/accept`, headers `X-Correlation-Id` and `Idempotency-Key`.
- `getStatus`: GET `/v1/applications/{applicationId}/status`, header `X-Correlation-Id`.
- One private helper wraps each call: success returns `BackendResponse.ok(status, body)`; `RestClientResponseException` returns `BackendResponse.error(status, code, ...)` where code is parsed from an `{"code","message"}` body, falling back to `HTTP_<status>`; `ResourceAccessException` (timeout or unreachable) returns 504 `BACKEND_TIMEOUT`.
- No retries anywhere in this class. Add a class comment explaining why: accept must never be retried automatically.

## Step 2: The switch (server configuration, never a tool parameter)

1. In `application.yml`, keep `prequal.backend: ${PREQUAL_BACKEND:mock}` and add:

```yaml
management:
  info:
    env:
      enabled: true
info:
  prequal:
    backend: ${prequal.backend}
prequal:
  http:
    base-url: ${PREQUAL_BASE_URL:}
    client-id: ${PREQUAL_CLIENT_ID:}
    api-key: ${PREQUAL_API_KEY:}
    channel: AI_AGENT
    connect-timeout: 2s
    read-timeout: 10s
```

   Add a comment block above `prequal:` explaining: one setting `prequal.backend` (mock|http) chosen at startup; set it by command line, env var `PREQUAL_BACKEND`, or profile `real`; an AI agent cannot change it.
2. Create `src/main/resources/application-real.yml` containing only `prequal.backend: http` and comments saying secrets go in env vars or `./config/application-real.yml`.
3. Create `config/application-real.yml.example` with placeholder `base-url`, `client-id`, `api-key` under `prequal.http`, and instructions to copy it to `config/application-real.yml`. Spring Boot loads `./config/application-real.yml` automatically when the profile is active.
4. `.gitignore`: `target/`, `*.log`, `.idea/`, `*.iml`, `.vscode/`, `.DS_Store`, `.env`, `config/application-real.yml`.
5. IntelliJ run configurations in `.run/` (Application type, so they work in Community edition):
   - `Apply MCP (mock).run.xml`: main class `dev.applymcp.ApplyMcpServerApplication`, module `apply-mcp-server`, program arguments `--prequal.backend=mock`, working directory `$PROJECT_DIR$`.
   - `Apply MCP (real API).run.xml`: same, program arguments `--spring.profiles.active=real`.
6. If Maven is installed, add the Maven Wrapper with `mvn -N wrapper:wrapper`. If that fails on the corporate network, skip it and tell me.

## Step 3: Tests (JUnit 5 + AssertJ)

Create these five test classes. Use a fixed `Clock` at `2026-10-02T12:00:00Z` where time matters.

1. `prequal/backend/MockPrequalBackendTest` (10 tests): qualified gives two offers; income < 20000 gives D1; zip 00000 gives R1; accept returns 201 then the same key replays 200 with the same applicationId; same key with a different offer gives 409 IDEMPOTENCY_KEY_REUSED; a new key for an already-accepted prequal gives 409 ALREADY_ACCEPTED; lastName Expired gives 410; lastName Slow gives 202 then 200; income 30000 gives status P and lastName Verify gives V; unknown application gives 404.
2. `prequal/tool/PrequalToolsTest` (10 tests): full happy path offers, accept, status APPROVED; the `OffersResult.toString()` does not contain "6789", "1990-04-12", "85000" or "100 Main St"; consent false throws with "soft credit inquiry"; confirmation false throws with "not confirmed"; key "abc" throws with "UUID"; retry with the same key gives ALREADY_ACCEPTED and nextStep contains "Do not call prequal_accept_offer again"; second acceptance with a new key throws with "already accepted"; bad date, bad SSN, under 18, and "Ohio" each throw with the expected message fragment; mapper maps 202 to IN_PROGRESS and 504 to a message containing "SAME idempotency key"; code tables map A1, D1, R1, unknown, and V.
3. `prequal/backend/HttpPrequalBackendTest` (5 tests): start a `com.sun.net.httpserver.HttpServer` on a random localhost port that acts as the API. Check getOffers sends `X-Client-Id`, `X-Api-Key`, `X-Channel`, `X-Correlation-Id` headers and parses the body; accept sends `Idempotency-Key` and a 409 body `{"code":"IDEMPOTENCY_KEY_REUSED"}` maps to that error code; a 500 is returned as a response, not thrown; pointing at `http://localhost:1` gives `BACKEND_TIMEOUT`; a blank base URL throws `IllegalStateException` mentioning `PREQUAL_BASE_URL`. Register `JavaTimeModule` on the test ObjectMapper. Note that the JDK server reports header names with only the first letter capitalised (for example `X-client-id`).
4. `BackendSelectionTest` (2 tests, `@Nested` `@SpringBootTest(webEnvironment = NONE)`): default context has a `MockPrequalBackend`; with `@ActiveProfiles("real")` and properties for base-url, client-id and api-key, the context has an `HttpPrequalBackend`.
5. `McpEndpointIntegrationTest` (3 tests): `@SpringBootTest(webEnvironment = RANDOM_PORT)`. Connect with the official MCP Java client: `HttpClientStreamableHttpTransport.builder("http://localhost:" + port).endpoint("/mcp").build()`, `McpClient.sync(transport).build()`, `initialize()`, `closeGracefully()` after each test. Assert `listTools()` returns exactly the three tool names, accept has `destructiveHint` true and its description contains "SAME idempotencyKey", status has `readOnlyHint` true; run offers, accept and status over the protocol and assert QUALIFIED, ACCEPTED, APPROVED (parse the `TextContent` text as JSON); calling accept with `applicantConfirmed: false` returns `isError` true with "not confirmed".

## Step 4: Verify

1. `mvn -B test` (or `./mvnw -B test`): 30 tests, 0 failures. Fix code, not tests, unless a test contradicts this prompt.
2. Start with `PREQUAL_BACKEND=http` and no other settings. The app must refuse to start with the message naming `PREQUAL_BASE_URL`.
3. Start normally. Log shows `Prequal backend: MOCK`; `http://localhost:8080/actuator/info` returns `{"prequal":{"backend":"mock"}}`.
4. In IntelliJ, the run dropdown shows "Apply MCP (mock)" and "Apply MCP (real API)".

Summarise what changed and stop. Prompt 3 writes the documentation.
