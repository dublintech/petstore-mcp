# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

The Swagger Petstore as a Spring Boot REST API, plus a separate MCP gateway that exposes it to AI clients as
tools, resources and prompts, and an OAuth 2.1 authorization server that is being wired in step by step (see the
security roadmap in `README.md`). It's a learning project about MCP architecture; see `README.md` for the user-facing
overview. Remote: https://github.com/dublintech/petstore-mcp

- Gradle 9.8.1 multi-module build (Groovy DSL); shared config in the root `build.gradle` `subprojects {}` block
- Java 21, Spring Boot 4.1.1 (Spring MVC), Spring AI 2.0.1 (MCP Java SDK 2.0.0) in `petstore-mcp` only;
  `petstore-auth` uses Spring Security 7.1 (`spring-boot-starter-security-oauth2-authorization-server`)
- Base package `com.dublintech.mcpproto1`; configuration in `application.properties` (not YAML)

| Module | Port | Main class |
|---|---|---|
| `petstore-api` | 8080 | `com.dublintech.mcpproto1.PetstoreApiApplication` |
| `petstore-mcp` | 8081 | `com.dublintech.mcpproto1.mcp.PetstoreMcpApplication` (MCP endpoint `/mcp`) |
| `petstore-auth` | 9000 | `com.dublintech.mcpproto1.auth.PetstoreAuthApplication` (issuer `http://localhost:9000`) |

## Commands

Use the wrapper (`./gradlew` in bash, `.\gradlew.bat` in PowerShell):

```bash
./gradlew test                                  # all tests, all modules
./gradlew :petstore-mcp:test --tests "com.dublintech.mcpproto1.mcp.ErrorSafetyTests"   # one class
./gradlew :petstore-api:bootRun                 # start the API first
./gradlew :petstore-mcp:bootRun                 # then the gateway
./gradlew :petstore-auth:bootRun                # authorization server (not yet required by the others)
./gradlew bootJar                               # jars in <module>/build/libs/
```

Gradle reports `test UP-TO-DATE` when nothing changed; add `--rerun` to force a run. No linter or formatter is
configured. The machine is memory-constrained: run jars with `java -Xmx256m -jar ...`, and expect long-running
background processes to be stopped under memory pressure.

`bruno/petstore-mcp` is a Bruno collection of raw MCP JSON-RPC requests; run it headless with
`npx -y @usebruno/cli@4.2.0 run` from that folder while both apps are up.

## Architecture rules

- **The gateway never depends on the API module.** `petstore-mcp` reaches the API only over HTTP through
  `mcp/client/PetstoreApiClient`, with its own models in `PetstoreModels` (tolerant readers, statuses as strings).
  Don't add `project(':petstore-api')` to the gateway's dependencies.
- **The API owns its contract.** The OpenAPI spec lives in `petstore-api/src/main/resources/openapi/` and is
  served at `GET /v2/openapi.json`. Don't copy it into the gateway.
- **Business logic lives in API services**, not controllers. Facade tools (`PetstoreFacadeTools`) orchestrate API
  calls and enforce task-level rules; only `buyPet` writes. Don't add write tools without asking.
- **MCP components** are `@Component` beans with `@McpTool` / `@McpResource` / `@McpPrompt` methods. They're
  singletons shared across sessions, so keep them stateless.

## Error handling (security-relevant)

- **API side:** every `ApiException` carries an `ErrorCode` (sent in `ApiResponse.type`) and, for validation
  errors, a typed `FieldError` (field, rule, limit). Add new codes to the enum; keep `message` for humans only.
- **Gateway side:** wrap every API call in `GatewayErrors.call(...)` or translate with `GatewayErrors.translate(...)`.
  Never put the API's free-text `message` into anything the model sees: it can echo stored or user-supplied text
  (prompt injection) or leak internals. `PetstoreApiClient` logs it under a reference ID and drops it.
- **Gateway-written messages** may contain only IDs, known enum values and numbers, never free-text data such as
  pet names. Throw `GatewayErrorException` for gateway messages; it deliberately has no cause, because Spring AI
  copies the root cause's message into tool results.
- New field names the gateway should accept from API error detail must be added to
  `PetstoreApiException.KNOWN_FIELDS`. `ErrorSafetyTests` holds the adversarial tests; extend it for new paths.

## Gotchas

- `spring.ai.mcp.server.protocol=STREAMABLE` must stay set; when unset, Spring AI 2.0.1 wires the legacy SSE
  transport and `/mcp` returns 404.
- Jackson 3: classes are in `tools.jackson.*`, but annotations are still `com.fasterxml.jackson.annotation.*`.
  Boot 4 test slices are in `org.springframework.boot.webmvc.test.autoconfigure`.
- MCP SDK 2.0 deprecates the schema record constructors; use the builders (`CallToolRequest.builder(...)` etc.).
- The API keeps state in memory; its test classes use `@DirtiesContext` so each starts from the sample data.
  Gateway tests mock `PetstoreApiClient` with `@MockitoBean`, so they don't need the API running.
- Claude Desktop (Microsoft Store build) reads its config from
  `%LOCALAPPDATA%\Packages\Claude_pzs8sxrjxfjjc\LocalCache\Roaming\Claude\claude_desktop_config.json` and
  reaches the gateway through `npx mcp-remote http://localhost:8081/mcp`. Its MCP logs are in
  `%LOCALAPPDATA%\Claude\Logs\`. It never calls `resources/templates/list`.

## Authorization server (`petstore-auth`)

- One pre-registered **public** client, `petstore-mcp-client` (no secret, PKCE required, consent on), with redirect
  URI `http://localhost:3334/oauth/callback` for `mcp-remote`; users `user1`–`user3` in `UserAccountsConfig`.
  Client settings live in `application.properties`.
- `AuthServerSecurityConfig` defines both security chains explicitly; declaring any `SecurityFilterChain` bean
  turns off all of Boot's defaults. The request cache only saves `/oauth2/authorize`, otherwise a stray browser
  request (favicon, DevTools probe, `/error`) replaces it and login ends on a Whitelabel 999 page.
  `AuthorizationCodeFlowTests` covers this.
- The signing key is generated at startup, so a restart invalidates every token. Public clients get no refresh
  token. The `resource` parameter is not yet copied into `aud` (needed for step 3).
- The OAuth endpoints are filters, not controllers. To list each chain's filters, run with
  `--logging.level.org.springframework.security.web.DefaultSecurityFilterChain=DEBUG`.
- In MockMvc tests, pass already-encoded URLs as `URI.create(...)`; `get(String)` encodes them a second time.

## Working agreements

- Ask before changing `build.gradle` files or adding dependencies.
- Work on a branch and merge through a GitHub PR (`gh pr create`, then `gh pr merge --merge`); commit and push
  only when asked.
