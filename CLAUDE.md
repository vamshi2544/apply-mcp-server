# CLAUDE.md

Guidance for AI coding assistants working in this repository.

## What this is

A Spring Boot MCP server exposing Apply product capabilities as AI agent tools. Capability 1 is credit card prequalification (offers, accept, status). Further apply products will be added as new tool classes following the same pattern.

## Commands

- Build and test: `./mvnw test`
- Run with mock: `./mvnw spring-boot:run` (MCP endpoint `http://localhost:8080/mcp`)
- Run with real APIs: `SPRING_PROFILES_ACTIVE=real ./mvnw spring-boot:run` (needs `config/application-real.yml` or `PREQUAL_*` env vars)
- Full handbook: `docs/index.html`. Update it when files, flows or rules change.

## Architecture

- `prequal/backend`: adapter layer. `PrequalBackend` interface with a mock (default) and an HTTP implementation. Backend records in `PrequalApi` mirror the REST APIs, internal codes included.
- `prequal/tool`: tool layer. `@McpTool` methods in `PrequalTools`, translation in `PrequalMapper`, agent-facing records in `ToolResults`. Throw `ToolCallException` to return an `isError` result with an actionable message.

## Rules

- Never change the backend API contract to suit the agent. Translate in the tool layer.
- Tool names are `<product>_<action>` in snake_case. Do not rename existing tools; add new ones.
- Every description states what the tool does, when to use it, what must happen first, and the retry rule.
- Every safety rule in a description must also be enforced in code and covered by a test.
- Never return or log SSN, date of birth, income or address. Log tool name, outcome, correlation id, duration only.
- Never add automatic retries to accept or any other state-changing call.
- Keep gateway-specific details (headers, keys, base URLs) in `HttpPrequalBackend` and configuration.
- The mock/real choice is server configuration (`prequal.backend`), never a tool parameter.
- No real customer data, real endpoints, or credentials in this repository. Mock data only.

## Adding a new apply product

1. Add `<product>/backend` with an API contract record class, a backend interface, a deterministic mock, and an HTTP implementation.
2. Add `<product>/tool` with tools, mapper and result records.
3. Add mock, tool, and protocol-level tests mirroring the prequal ones.
4. Update the README tool table and mock scenarios.
