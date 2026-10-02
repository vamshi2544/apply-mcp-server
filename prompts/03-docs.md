# Prompt 3 of 3: README, CLAUDE.md, Copilot config, and the HTML handbook

This project is a Spring Boot 3.5 + Spring AI 1.1 MCP server with three prequal tools, a mock and a real HTTP backend, a `prequal.backend` switch, and 30 tests (built by prompts 1 and 2). First, read the whole project, run `mvn -B test`, and confirm all tests pass. If not: **STOP — ASK USER**.

Write documentation only in this prompt. Do not change Java code or configuration. Write in plain, formal English: short direct sentences, no idioms, no marketing language. Describe only what the code actually does; read the code to check each claim.

## Step 1: mcp.json.example

```json
{
  "servers": {
    "apply-prequal": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

This is the format GitHub Copilot for JetBrains uses (Copilot Chat, Agent mode, tools icon, Add MCP Tools).

## Step 2: README.md

Sections:

1. What it is: MCP server exposing credit card prequalification as AI-agent tools; thin agent-facing layer over unchanged APIs; mock by default.
2. Tool table: name, what it does, risk (consent; irreversible with confirmation and idempotency key; read only). Mention every result has `nextStep` and internal codes never reach the agent.
3. Requirements and run: JDK 17+, `mvn spring-boot:run` or the IntelliJ run configurations, endpoint `http://localhost:8080/mcp`, health URL.
4. Tests: one line per test class.
5. Connect GitHub Copilot in IntelliJ: Agent mode, tools icon, Add MCP Tools, paste `mcp.json.example`, confirm the three tools. Note the "MCP servers in Copilot" organisation policy must be enabled on Business or Enterprise plans. Tip: Debug mode with a breakpoint in `PrequalTools` shows the arguments the model produced.
6. Test without AI: MCP Inspector command and connection settings.
7. Mock scenarios table: income < 20000; ZIP 00000; last names Expired, Slow, Verify; income bands for approved and pending; same key same offer; same key different offer; new key after acceptance.
8. Example conversation to try (5 steps, including "Accept the best one" to check the agent asks you to choose).
9. Project structure tree with one-line comments.
10. Switching to the real APIs: table of mock vs real for IntelliJ, terminal, env var; secrets in `config/application-real.yml` (gitignored) or `PREQUAL_*` env vars; fail-fast behaviour; how to check the active backend (log line and `/actuator/info`); what to align first (`PrequalApi`, `HttpPrequalBackend`, `PrequalMapper`).
11. Design rules list.
12. Link to `docs/index.html`.

## Step 3: CLAUDE.md

Guidance for AI coding assistants: what the project is; commands (test, run mock, run real); architecture of the two packages; rules: never change the backend contract to suit the agent; tool names `<product>_<action>`, never rename; every description states what, when, prerequisites and retry rule; every safety rule in a description is enforced in code and tested; never return or log SSN, DOB, income or address; never add automatic retries to state-changing calls; gateway details only in `HttpPrequalBackend` and config; the mock/real choice is configuration, never a tool parameter; no real data, endpoints or credentials in the repository; update `docs/index.html` when files, flows or rules change. Steps for adding a new apply product.

## Step 4: docs/index.html (the handbook)

One standalone HTML file that opens directly in a browser. All CSS inline in a `<style>` block. No JavaScript required, no external scripts, no images; diagrams are inline SVG. A Google Fonts link is allowed with system font fallbacks. Support light and dark mode with CSS custom properties and `@media (prefers-color-scheme: dark)`; SVG elements take colors from CSS classes that use those variables. Readable at phone width: wide tables and diagrams scroll inside their own `overflow-x: auto` container; SVGs use `viewBox` with `width:100%` and a sensible `min-width`. Sticky table of contents on the left on wide screens.

Sections, in this order:

1. Overview: what it is, the three tools with backend endpoint and nature, the core idea (APIs unchanged, translation layer), mock by default.
2. Architecture: inline SVG component diagram: Copilot (host + model) → [MCP] → Spring AI MCP (/mcp, JSON-RPC, sessions, schema) → PrequalTools → PrequalMapper → PrequalBackend interface → Mock | Http → [HTTPS] → Gateway (Apigee / Kong) → Prequal APIs (unchanged). Draw a dashed group around the parts in this repository. Colour our code, the Spring AI library and external systems differently, with a legend. Then a table of the three layers: what each owns and what it knows nothing about.
3. Every file and why: table with file, what it is, why we need it, grouped into Build and project, Configuration, Application code, Tests, Documentation. Include every file in the repository.
4. End-to-end flow: inline SVG sequence diagram with five lifelines (You, Copilot, Spring AI MCP, PrequalTools, PrequalBackend) and four labelled phases: Connect (initialize, tools/list), Prequalify (user asks, model asks for details and consent, tools/call get_offers through to the backend and back, offers shown), Accept (user confirms, Copilot asks approval, tools/call accept through to the backend, ACCEPTED), Status (tools/call status, APPROVED, answer to the user). Solid arrows for calls, dashed for responses. Highlight the PrequalTools lane. Text labels need a background halo so they stay readable over lifelines. Generate the SVG coordinates with a small script if that is more reliable, then paste the output into the HTML. Follow with four bullet points worth noticing.
5. Messages on the wire: start the server, capture real JSON-RPC messages, and show them (shortened where needed): initialize request and response (mention the `Mcp-Session-Id` header and the instructions), one tool from tools/list with schema and annotations, a tools/call for get_offers and its result, and the isError result from accept with `applicantConfirmed: false`. Explain that the schema is generated from the annotations.
6. Inside one tool call: a vertical pipeline for `prequal_accept_offer`: Spring AI receives and routes; `traced` creates the correlation id; rules enforced; backend request built; backend called; mapper translates; one log line; Spring AI responds.
7. What happens at startup: configuration order, one backend bean chosen by `@ConditionalOnProperty`, fail-fast, startup log line, annotation scanning registers the tools, `/mcp` published.
8. Mock or real API: explain why the switch is server configuration and not per request. A diagram (HTML/CSS boxes are fine) showing four sources in priority order (command line, env var, profile, default) → one setting `prequal.backend` → MockPrequalBackend or HttpPrequalBackend. Table: IntelliJ, terminal, jar, PCF later. How to check the active backend. Callouts: why not a per-call flag; production guard later.
9. Pointing at the real API: numbered checklist (contract fields, paths and headers, authentication including OAuth client credentials if needed, error body parsing, code tables, update the HTTP test, local credentials file, run and check). Warning about using test data only until InfoSec approves the AI host.
10. Safety rules: table of rule, where the model is told, where code enforces it, which test proves it.
11. Error handling map: backend outcome → tool result → what the model is told, grouped per tool.
12. Tests and verifying by hand: the four test levels, MCP Inspector, Copilot.
13. Running on a Mac and on a work laptop: setup steps; work-laptop differences (Maven mirror must serve Spring AI 1.1.x, `io.modelcontextprotocol.sdk` and `org.springaicommunity:mcp-annotations`; proxy and certificates; Copilot policy; keep code in the company repository; Java version).
14. Design decisions: table of decision and why (Spring AI Java; Streamable HTTP; curated tools not generated from OpenAPI; APIs unchanged; `@McpTool` for hints; flat parameters; isError with instructions; nextStep; deterministic mock; switch in configuration with fail-fast; no retries in the adapter; no caller authentication yet, gateway later).
15. Adding the next apply product.
16. Glossary: MCP, host, tool, JSON-RPC 2.0, Streamable HTTP, Mcp-Session-Id, tool annotations, isError, Spring profile, MDC, idempotency key.

## Step 5: Verify

1. Open `docs/index.html` in a browser in light and dark mode. Check that the three diagrams render, no text overlaps, and the page does not scroll sideways at a narrow width (only figures and tables scroll inside their frames).
2. Check every file name, class name, property and message quoted in the docs exists in the code exactly as written.
3. `mvn -B test` still passes.

Summarise the files written and stop.
