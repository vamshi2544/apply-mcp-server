# Apply MCP Server

An MCP (Model Context Protocol) server that exposes Apply product capabilities as tools for AI agents. The first capability is **credit card prequalification**: get offers, accept an offer, and check application status.

The server is a thin, agent-facing layer over the existing prequal REST APIs. The APIs are not changed. This repository runs against a **deterministic mock backend** by default, so it needs no network access, credentials, or real data.

## What it exposes

| Tool | What it does | Risk |
|---|---|---|
| `prequal_get_offers` | Soft-inquiry prequalification. Returns QUALIFIED, NOT_QUALIFIED or NEEDS_MORE_INFO, and offers. | Creates a prequal record. Requires explicit consent. |
| `prequal_accept_offer` | Accepts one offer and creates an application. | Irreversible. Requires explicit confirmation and an idempotency key. |
| `prequal_get_application_status` | Returns PENDING, APPROVED, DECLINED or NEEDS_VERIFICATION. | Read only. |

Every result includes a `nextStep` field telling the agent what to do next. Internal codes (`A1`, `P`, `RC014`) never reach the agent.

## Requirements

- Java 17 or later
- Maven is optional; the wrapper (`./mvnw`) downloads it

## Run it

```bash
./mvnw spring-boot:run
```

The MCP endpoint is `http://localhost:8080/mcp` (Streamable HTTP). Health check: `http://localhost:8080/actuator/health`.

In IntelliJ: open the folder as a Maven project and run `ApplyMcpServerApplication`.

## Run the tests

```bash
./mvnw test
```

- `MockPrequalBackendTest`: idempotency and decision rules of the mock.
- `PrequalToolsTest`: consent and confirmation enforcement, validation, PII not echoed, code translation.
- `McpEndpointIntegrationTest`: starts the server and talks to it with the official MCP Java client over the real protocol.
- `HttpPrequalBackendTest`: runs the real HTTP adapter against a local stub server.
- `BackendSelectionTest`: proves the mock is the default and the `real` profile selects the HTTP backend.

## Connect GitHub Copilot in IntelliJ

Copilot acts as the MCP host and provides the model, so no separate LLM is needed.

1. Start the server.
2. Open Copilot Chat and switch the mode to **Agent**.
3. Click the tools icon, then **Add MCP Tools**. This opens Copilot's `mcp.json`.
4. Paste the contents of [`mcp.json.example`](mcp.json.example) and save.
5. Open the tools icon again and confirm the three `prequal_*` tools are listed.
6. Ask: *"I want to check if I prequalify for a credit card."*

On Copilot Business or Enterprise, an organisation admin must enable the **MCP servers in Copilot** policy, or MCP tools will not appear.

Tip: run the server in Debug mode with a breakpoint in `PrequalTools` to see exactly what arguments the model produced.

## Test without any AI: MCP Inspector

```bash
npx @modelcontextprotocol/inspector
```

Choose transport **Streamable HTTP**, URL `http://localhost:8080/mcp`, then **Connect**. You can list tools, call them, and see the raw JSON-RPC messages.

## Mock scenarios

The mock is deterministic so every path can be exercised on purpose.

| Input | Result |
|---|---|
| Annual income below 20,000 | NOT_QUALIFIED |
| ZIP `00000` | NEEDS_MORE_INFO |
| Anything else | QUALIFIED with two offers |
| Last name `Expired` | Offers already expired; accept fails with "offer has expired" |
| Last name `Slow` | First accept returns IN_PROGRESS (202); retry with the same key returns ALREADY_ACCEPTED |
| Last name `Verify` | Application status NEEDS_VERIFICATION |
| Income 40,000 or more | Application APPROVED |
| Income 20,000 to 39,999 | Application PENDING |
| Same idempotency key, same offer | ALREADY_ACCEPTED (replay, no duplicate) |
| Same key, different offer | Error: key reused |
| New key, prequal already accepted | Error: already accepted |

Mock data is in memory and resets when the server restarts. Prequal ids start at `PQ-1001`; application ids at `APP-55001`.

## Example conversation to try

1. "Can I get a credit card?" (the agent should ask for details and consent before calling any tool)
2. Provide details: Jane Doe, born 1990-04-12, SSN last 4 6789, 100 Main St, Dayton, OH 45402, income 85000, and say you agree to the soft inquiry.
3. "Accept the best one." (the agent should ask you to choose, not choose for you)
4. "The Cashback Card, yes I confirm."
5. "What's my application status?"

## Project structure

```
src/main/java/dev/applymcp/
├── ApplyMcpServerApplication.java
└── prequal/
    ├── backend/                     Adapter layer: talks to the prequal APIs
    │   ├── PrequalApi.java          Backend contract (mirrors the REST APIs, internal codes included)
    │   ├── PrequalBackend.java      Adapter interface
    │   ├── MockPrequalBackend.java  Deterministic in-memory mock (default)
    │   ├── HttpPrequalBackend.java  Real HTTP client through the API gateway
    │   └── PrequalHttpProperties.java
    └── tool/                        Tool layer: what the agent sees
        ├── PrequalTools.java        @McpTool definitions, validation, safety rules
        ├── PrequalMapper.java       Code translation and next steps
        ├── ToolResults.java         Agent-facing result records
        └── ToolCallException.java   Becomes an isError tool result
```

## Switching to the real APIs

The backend is chosen by one server setting, `prequal.backend` (`mock` or `http`). It is read once at startup. It is not part of any tool call, so an AI agent cannot change it.

| Where | Mock | Real API |
|---|---|---|
| IntelliJ | Run **Apply MCP (mock)** | Run **Apply MCP (real API)** |
| Terminal | `./mvnw spring-boot:run` | `SPRING_PROFILES_ACTIVE=real ./mvnw spring-boot:run` |
| Environment variable | `PREQUAL_BACKEND=mock` | `PREQUAL_BACKEND=http` |

For real mode, provide the gateway settings without committing them:

```bash
cp config/application-real.yml.example config/application-real.yml   # gitignored
# fill in base-url, client-id, api-key
```

or set `PREQUAL_BASE_URL`, `PREQUAL_CLIENT_ID` and `PREQUAL_API_KEY` as environment variables. If real mode is selected and a setting is missing, the server refuses to start and names the missing setting.

Check which backend is live in the startup log (`Prequal backend: MOCK …` or `Prequal backend: REAL APIs via …`) or at `http://localhost:8080/actuator/info`.

Before the first real call, align `PrequalApi`, the paths and headers in `HttpPrequalBackend`, and the code tables in `PrequalMapper` with the real API contract. The checklist is in [docs/index.html](docs/index.html), section 09.

## Documentation

Open [`docs/index.html`](docs/index.html) in a browser for the full handbook: architecture, every file and why it exists, end-to-end sequence diagram, real wire messages, safety rules, error map, and setup for Mac and a work laptop.

To rebuild this project from scratch with an AI assistant (for example on a work laptop), use the three prompts in [`prompts/`](prompts/README.md).

## Design rules

- The APIs are not changed. The tool layer translates.
- Descriptions are the interface. Write them for a model with no internal context.
- Every rule in a description is also enforced in code (consent, confirmation, key format).
- Tool results never echo SSN, date of birth, income or address.
- Logs contain tool name, outcome, correlation id and duration only.
- Accept is never retried automatically. Only the agent retries, with the same idempotency key.
- Gateway details (Apigee today, possibly Kong later) live only in `HttpPrequalBackend` and configuration.

## Stack

Spring Boot 3.5, Spring AI 1.1 MCP server (`spring-ai-starter-mcp-server-webmvc`), MCP Java SDK, Java 17.
