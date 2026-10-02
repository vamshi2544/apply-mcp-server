# Prompt 1 of 3: MCP server, three prequal tools, mock backend

You are working in an empty (or nearly empty) project folder. Build a Spring Boot MCP (Model Context Protocol) server that exposes credit card prequalification as three AI-agent tools, backed by a deterministic in-memory mock. Do not call any real API. Do not use real customer data, real endpoints or credentials anywhere.

Work in the steps below, in order. After each step, tell me in one or two lines what you did. If a step fails, show me the exact error and stop.

## Step 0: Environment check

1. Run `java -version`. It must be 17 or later. If not: **STOP — ASK USER** to install JDK 17+.
2. Check for Maven (`mvn -v`). If missing, tell me and we will add the Maven Wrapper later.
3. If the folder already contains a `pom.xml` or `src/`, list what exists and **STOP — ASK USER** whether to continue or start clean.

## Step 1: pom.xml and dependency check

Create `pom.xml`:

- Parent: `org.springframework.boot:spring-boot-starter-parent:3.5.6`
- groupId `dev.applymcp`, artifactId `apply-mcp-server`, version `0.1.0-SNAPSHOT`
- Property `java.version` = `17`, property `spring-ai.version` = `1.1.0`
- dependencyManagement: import BOM `org.springframework.ai:spring-ai-bom:${spring-ai.version}` (type pom, scope import)
- Dependencies: `org.springframework.ai:spring-ai-starter-mcp-server-webmvc`, `org.springframework.boot:spring-boot-starter-actuator`, `org.springframework.boot:spring-boot-starter-test` (test scope)
- Plugin: `spring-boot-maven-plugin`

Then run `mvn -B dependency:resolve`. It must download `org.springframework.ai` 1.1.0 artifacts, `io.modelcontextprotocol.sdk:mcp-core` and `org.springaicommunity:mcp-annotations`. If resolution fails (for example the internal mirror does not have them, or SSL/proxy errors): **STOP — ASK USER** and show the failing artifact. Do not switch to other versions or other libraries without asking.

## Step 2: Package layout

Base package `dev.applymcp` (if I give you another base package, use that everywhere instead).

```
src/main/java/dev/applymcp/
  ApplyMcpServerApplication.java        @SpringBootApplication + @ConfigurationPropertiesScan
  prequal/backend/
    PrequalApi.java                     backend contract records
    BackendResponse.java
    PrequalBackend.java                 adapter interface
    MockPrequalBackend.java             deterministic mock
  prequal/tool/
    PrequalTools.java                   the three @McpTool methods
    PrequalMapper.java                  backend -> agent translation
    ToolResults.java                    agent-facing records
    ToolCallException.java
src/main/resources/application.yml
```

Architecture rule: the tool layer (`prequal/tool`) talks only to the `PrequalBackend` interface. It never knows whether the backend is mock or real. The backend layer never knows about MCP.

## Step 3: Backend contract (`PrequalApi.java`)

A final class holding nested records that mirror the REST APIs, internal codes included:

- `OffersRequest(Applicant applicant, Consent consent)` for `POST /v1/prequal/offers`
- `Applicant(String firstName, String lastName, LocalDate dob, String ssnLast4, Address address, Integer annualIncome)`
- `Address(String line1, String city, String state, String zip)`
- `Consent(boolean softInquiry, Instant timestamp)`
- `OffersResponse(String prequalId, String decisionCd, List<Offer> offers)` where decisionCd A1 = qualified, D1 = not qualified, R1 = needs more info
- `Offer(String offerId, String productCd, String productName, Integer creditLine, BigDecimal purchaseApr, Instant expiresAt)`
- `AcceptRequest(String prequalId, Instant acceptedAt)` for `POST /v1/prequal/offers/{offerId}/accept` with header `Idempotency-Key`
- `AcceptResponse(String applicationId, String statusCd)` where statusCd P = pending, A = approved, D = declined, V = needs verification
- `StatusResponse(String applicationId, String statusCd, Instant updatedAt)` for `GET /v1/applications/{applicationId}/status`
- `ApiError(String code, String message)`

`BackendResponse<T>(int status, T body, PrequalApi.ApiError error)` with static `ok(status, body)`, `error(status, code, message)`, `isSuccess()` (2xx) and `errorCode()`.

`PrequalBackend` interface:

- `BackendResponse<OffersResponse> getOffers(OffersRequest request, String correlationId)`
- `BackendResponse<AcceptResponse> acceptOffer(String offerId, AcceptRequest request, String idempotencyKey, String correlationId)`
- `BackendResponse<StatusResponse> getStatus(String applicationId, String correlationId)`

## Step 4: Mock backend (`MockPrequalBackend.java`)

`@Component` with `@ConditionalOnProperty(name = "prequal.backend", havingValue = "mock", matchIfMissing = true)`. Two constructors: a no-arg one annotated `@Autowired` using `Clock.systemUTC()`, and one taking a `Clock` (for tests). The constructor logs at INFO: `Prequal backend: MOCK (in-memory, no real APIs are called). Set prequal.backend=http for real APIs.`

In-memory state with `ConcurrentHashMap`s: prequals, idempotency keys, applications. Prequal ids `PQ-1001`, `PQ-1002`… Application ids `APP-55001`, `APP-55002`… Timestamps truncated to seconds.

getOffers rules:

- consent missing or false: 400 `CONSENT_REQUIRED`
- annualIncome below 20000: 200 with decisionCd `D1`, no offers
- zip `00000`: 200 with `R1`, no offers
- otherwise 200 with `A1` and two offers: `OF-<n>-1` "Cashback Card" product CB01, limit 3000, APR 27.99; `OF-<n>-2` "Travel Card" TR01, limit 5000, APR 25.49, where `<n>` is the numeric part of the prequal id. Offers expire in 30 days, except when lastName is "Expired" (case-insensitive): already expired one day ago.

acceptOffer rules (method is `synchronized`), checked in this order:

1. Fingerprint = prequalId + "|" + offerId. If the idempotency key was seen before: same fingerprint returns 200 with the original response (replay); different fingerprint returns 409 `IDEMPOTENCY_KEY_REUSED`.
2. Unknown prequalId: 404 `PREQUAL_NOT_FOUND`. Offer not in that prequal: 404 `OFFER_NOT_FOUND`.
3. Offer expired: 410 `OFFER_EXPIRED`.
4. Prequal already has an accepted application: 409 `ALREADY_ACCEPTED` with message `An offer from this prequal was already accepted. applicationId=<id>`.
5. Otherwise create the application. statusCd: `V` if lastName "Verify"; else `A` if income >= 40000; else `P`. Store it, store the key, return 201. Exception: if lastName is "Slow", return 202 instead of 201 (a later replay with the same key returns 200).

getStatus: unknown id returns 404 `APPLICATION_NOT_FOUND`, else 200.

## Step 5: Agent-facing results and translation

`ToolResults.java` with records:

- `OffersResult(String decision, String prequalId, List<OfferView> offers, String reason, String nextStep)`
- `OfferView(String offerId, String productName, Integer creditLimit, BigDecimal purchaseApr, Instant expiresAt)`
- `AcceptResult(String outcome, String applicationId, String applicationStatus, String message, String nextStep)`
- `StatusResult(String applicationId, String status, String meaning, Instant updatedAt, String nextStep)`

These records must never contain SSN, date of birth, income or address.

`ToolCallException extends RuntimeException`. Its message is shown to the AI model as an error result, so every message must say what went wrong and what to do next, without applicant data.

`PrequalMapper.java` (final, static methods):

- `decision(code)`: A1 QUALIFIED, D1 NOT_QUALIFIED, R1 NEEDS_MORE_INFO, else UNKNOWN.
- `applicationStatus(code)`: P PENDING, A APPROVED, D DECLINED, V NEEDS_VERIFICATION, else UNKNOWN.
- `toOffersResult`: non-2xx throws (see shared errors). QUALIFIED: reason "The applicant prequalified for N offer(s). This is not a final credit decision." nextStep "Present each offer's product name, credit limit and purchase APR to the applicant exactly as returned. If the applicant chooses one, confirm the choice with them, then call prequal_accept_offer with this prequalId and the chosen offerId." NOT_QUALIFIED: tell the applicant; do not call again with changed details unless they say details were entered incorrectly. NEEDS_MORE_INFO: ask the applicant to check name, date of birth, address and ZIP, then call again.
- `toAcceptResult`: 201 ACCEPTED (nextStep: give the application id, call prequal_get_application_status). 200 ALREADY_ACCEPTED (message: already processed with the same idempotency key, no new application; nextStep must contain "Do not call prequal_accept_offer again"). 202 IN_PROGRESS (do not use a new key; wait, then status or retry with the same key). Errors by code: IDEMPOTENCY_KEY_REUSED (key used for a different offer; new key only if the applicant explicitly chose a different offer); ALREADY_ACCEPTED (message must contain "already accepted"; a second application cannot be created; use status); OFFER_EXPIRED (tell the applicant; call prequal_get_offers again if they want); OFFER_NOT_FOUND or PREQUAL_NOT_FOUND (use exact ids from prequal_get_offers; do not invent ids); any 5xx (message must contain "SAME idempotency key": outcome unknown, retry once with the same key, never a new key).
- `toStatusResult`: 404 throws "No application exists with that id. Use the applicationId returned by prequal_accept_offer." Map each status to a plain meaning and next step; for PENDING the next step says not to check repeatedly.
- Shared errors: 5xx "temporarily unavailable" (safe to retry once for reads); 400 "rejected as invalid (<code>)", ask the applicant to correct inputs.

## Step 6: The tools (`PrequalTools.java`)

`@Component`. Use the MCP annotations from `org.springaicommunity.mcp.annotation`: `@McpTool` (attributes `name`, `title`, `description`, `annotations = @McpTool.McpAnnotations(title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint)`) and `@McpToolParam(description, required)`. Spring AI discovers these automatically; do not register them manually. Constructor injection of `PrequalBackend`; second constructor with `Clock` for tests.

Every tool body runs inside a helper `traced(toolName, supplier)` that creates a UUID correlation id, puts it in SLF4J `MDC` as `correlationId`, and in `finally` logs exactly one line `tool={} outcome={} correlationId={} durationMs={}` (outcome ok, rejected, or error) and removes the MDC key. Never log arguments.

Tool 1: name `prequal_get_offers`, title "Check credit card prequalification", hints readOnly false, destructive false, idempotent false, openWorld false. Description (use verbatim):

> Check whether an applicant prequalifies for credit card offers, using a soft credit inquiry that does not affect the applicant's credit score.
> Use this as the first step of any credit card prequalification.
> Before calling: collect every input from the applicant themselves, and get their explicit agreement to a soft credit inquiry. Set consentSoftInquiry to true only if they agreed.
> Returns a decision (QUALIFIED, NOT_QUALIFIED or NEEDS_MORE_INFO), a prequalId, and for QUALIFIED a list of offers. Follow the nextStep field in the result.
> This is not an application and not a final credit decision. Safe to retry once on a timeout.

Parameters (all required, each with a clear description): firstName, lastName, dateOfBirth (YYYY-MM-DD, 18 or older), ssnLast4 (exactly 4 digits; never ask for the full number), addressLine1, city, state (two-letter code), zipCode (5 digits), annualIncome (Integer, whole US dollars), consentSoftInquiry (Boolean, true only if the applicant explicitly agreed in this conversation).

Logic: if consent is not true, throw ToolCallException explaining the soft inquiry and asking for agreement (message contains "soft credit inquiry"). Validate: names, address, city not blank; dateOfBirth parses (message contains "YYYY-MM-DD"); age at least 18 (message contains "18"); ssnLast4 matches `\d{4}` (message contains "4 digits"); state matches `[A-Z]{2}` (message contains "two-letter"); zip `\d{5}`; income not null and >= 0. Build `OffersRequest` with consent timestamp now, call backend, return `PrequalMapper.toOffersResult`.

Tool 2: name `prequal_accept_offer`, title "Accept a prequalified credit card offer", hints readOnly false, destructive true, idempotent true, openWorld false. Description (verbatim):

> Accept one prequalified credit card offer and create a credit card application. This cannot be undone.
> Only call this after: (1) prequal_get_offers returned QUALIFIED, (2) the applicant has seen that offer's product name, credit limit and purchase APR, and (3) the applicant has explicitly said they want that specific offer. Never choose an offer on the applicant's behalf. Set applicantConfirmed to true only then.
> Idempotency: generate one new UUID as idempotencyKey for each acceptance. If the call times out or fails with an unknown outcome, retry with the SAME idempotencyKey; this never creates a duplicate. Never retry with a new key. Only one offer per prequalId can be accepted.
> Returns outcome ACCEPTED, ALREADY_ACCEPTED or IN_PROGRESS, plus an applicationId. Follow the nextStep field.

Parameters: prequalId, offerId, idempotencyKey (a UUID generated once and reused on retry), applicantConfirmed (Boolean). Logic: if applicantConfirmed is not true, throw (message contains "not confirmed"); ids not blank; idempotencyKey must match a UUID regex (message contains "UUID"); call backend; return `PrequalMapper.toAcceptResult`. Add a comment marking the audit point for production.

Tool 3: name `prequal_get_application_status`, title "Get credit card application status", hints readOnly true, destructive false, idempotent true, openWorld false. Description (verbatim):

> Get the current status of a credit card application created by prequal_accept_offer.
> Use this when the applicant asks about their application, or once right after an acceptance.
> Returns status PENDING, APPROVED, DECLINED or NEEDS_VERIFICATION with a plain-language meaning.
> Read-only and always safe to call, but do not call it repeatedly in a loop while the status is PENDING.

Parameter: applicationId. Logic: not blank, call backend, return `PrequalMapper.toStatusResult`.

## Step 7: application.yml

```yaml
spring:
  application:
    name: apply-mcp-server
  ai:
    mcp:
      server:
        name: apply-mcp-server
        version: 0.1.0
        type: SYNC
        protocol: STREAMABLE
        streamable-http:
          mcp-endpoint: /mcp
        instructions: >
          Tools for credit card prequalification. The flow is: prequal_get_offers (soft credit inquiry,
          requires the applicant's explicit consent), then prequal_accept_offer (irreversible, requires the
          applicant's explicit choice of one offer), then prequal_get_application_status.
          Follow the nextStep field in every result. Never invent ids, never choose an offer for the applicant,
          and never ask for a full Social Security number.
server:
  port: 8080
management:
  endpoints:
    web:
      exposure:
        include: health,info
prequal:
  backend: ${PREQUAL_BACKEND:mock}
logging:
  pattern:
    level: "%5p [%X{correlationId:-}]"
```

If Spring AI rejects `protocol: STREAMABLE` or the endpoint property, check the property metadata inside `spring-ai-autoconfigure-mcp-server-common-1.1.0.jar` (`META-INF/spring-configuration-metadata.json`) and use the names it lists. Tell me what you changed.

## Step 8: Build and verify

1. `mvn -B compile`. Fix compile errors without changing the design.
2. Start the app (`mvn spring-boot:run` or run `ApplyMcpServerApplication` in IntelliJ). The log must show `Prequal backend: MOCK`. `http://localhost:8080/actuator/health` must return UP.
3. Verify over MCP (either way):
   - MCP Inspector: `npx @modelcontextprotocol/inspector`, transport Streamable HTTP, URL `http://localhost:8080/mcp`, Connect. List tools (3 tools with the descriptions above), call `prequal_get_offers` with Jane / Doe / 1990-04-12 / 6789 / 100 Main St / Dayton / OH / 45402 / 85000 / true and expect QUALIFIED with two offers.
   - Or with curl (bash or Git Bash): POST `initialize` to `/mcp` with headers `Content-Type: application/json` and `Accept: application/json, text/event-stream`, read the `Mcp-Session-Id` response header, send it with `notifications/initialized`, then `tools/list`, then `tools/call`.
4. Call `prequal_accept_offer` with `applicantConfirmed: false` and confirm the result has `isError: true` and the "not confirmed" message.

When all checks pass, summarise the files created and stop. Prompt 2 adds the real backend, the switch and the tests.
