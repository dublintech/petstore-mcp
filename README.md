# Petstore MCP

The [Swagger Petstore](https://petstore.swagger.io/) built as a Spring Boot REST API, with a separate
**MCP gateway** in front of it so AI clients such as Claude Desktop and Claude Code can use the store through
the [Model Context Protocol](https://modelcontextprotocol.io/): tools, resources and prompts.

It's a learning project. The point is less the pet store than the architecture questions it raises: how an MCP
server should sit in front of an existing API, and what changes when the caller is a language model.

```
Claude Desktop ─stdio─▶ mcp-remote ─Streamable HTTP─▶ petstore-mcp :8081 ─REST─▶ petstore-api :8080
  (MCP host)              (bridge)                    (MCP server / gateway)       (backend)
```

## Modules

| Module | Port | What it is |
|---|---|---|
| [`petstore-api`](petstore-api) | 8080 | The Petstore REST API: all 20 operations under `/v2`, an in-memory store seeded from [sample data](petstore-api/src/main/resources/sample-data) at startup, and its own spec at `GET /v2/openapi.json`. Knows nothing about MCP. |
| [`petstore-mcp`](petstore-mcp) | 8081 | The MCP gateway, served at `/mcp`. It has **no dependency** on `petstore-api` and reaches it only over HTTP through [`PetstoreApiClient`](petstore-mcp/src/main/java/com/dublintech/mcpproto1/mcp/client/PetstoreApiClient.java). |

Built with Java 21, Spring Boot 4.1.1 and Spring AI 2.0.1 (MCP Java SDK 2.0.0, protocol up to `2025-11-25`).

## Running it

Start each app in its own terminal, the API first:

```bash
./gradlew :petstore-api:bootRun     # http://localhost:8080/v2
./gradlew :petstore-mcp:bootRun     # http://localhost:8081/mcp
```

Run the tests for both modules with `./gradlew test`. (On Windows PowerShell, use `.\gradlew.bat`.)

Data lives in memory, so restarting the API resets it to the sample set. Sample users are `user1`–`user3`
with passwords `password1`–`password3`.

### Try the REST API

```bash
curl -s localhost:8080/v2/pet/1
curl -s "localhost:8080/v2/pet/findByStatus?status=available"
curl -s localhost:8080/v2/store/inventory
curl -s localhost:8080/v2/openapi.json
```

## What the gateway offers

**Tools** (the model decides when to call them):

| Tool | Kind |
|---|---|
| `searchPets` | Facade: filters by status, tag and category in one call, using several API calls |
| `buyPet` | Facade: checks availability, places the order and marks the pet pending; cancels the order if the last step fails. **The only write tool.** |
| `getPetById`, `findPetsByStatus`, `findPetsByTags`, `getInventory`, `getOrderById`, `getUserByName` | Read-only, one-to-one with API operations |

**Resources** (the user or app attaches them as context):

| URI | Kind |
|---|---|
| `petstore://docs/guide` | Fixed document: pet statuses and how buying works |
| `petstore://inventory` | Live data from the API |
| `petstore://categories` | Derived data: the API has no categories endpoint, so the gateway computes it |
| `petstore://pets/{petId}` | URI template |
| `petstore://pets/{petId}/card` | Binary: a generated PNG card, sent as a base64 blob |

**Prompts** (the user picks them as starting points): `stockReport`, which embeds the inventory and guide, and
`findPetFor`, which takes arguments and steers the model through `searchPets` and `buyPet`.

## Connecting a client

**Claude Code** supports HTTP MCP servers directly:

```bash
claude mcp add --transport http petstore http://localhost:8081/mcp
```

**Claude Desktop** launches local servers over stdio, so it reaches the HTTP gateway through the
[`mcp-remote`](https://www.npmjs.com/package/mcp-remote) bridge (needs Node.js). Add this to
`claude_desktop_config.json` (**Settings → Developer → Edit Config**), then fully quit and reopen Claude Desktop:

```json
{
  "mcpServers": {
    "petstore": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:8081/mcp"]
    }
  }
}
```

The Microsoft Store build of Claude Desktop keeps this file under
`%LOCALAPPDATA%\Packages\Claude_<id>\LocalCache\Roaming\Claude\`, not `%APPDATA%\Claude\`. Tools and prompts
appear under the **+** menu. Claude Desktop doesn't request resource templates, so the two `pets/{petId}`
resources only show up in clients that do.

**MCP Inspector**: run `npx @modelcontextprotocol/inspector`, choose *Streamable HTTP* and connect to
`http://localhost:8081/mcp`.

**Bruno**: open [`bruno/petstore-mcp`](bruno/petstore-mcp) as a collection for raw JSON-RPC requests covering the
session handshake, tools, resources (including templates), prompts and ending a session. Run requests `01` and
`02` first; `01` saves the `Mcp-Session-Id` for the rest.

## Design notes

- **The gateway is a backend-for-frontend for agents.** Facade tools package multi-step tasks and enforce the
  business rules in code, rather than relying on the model to chain low-level calls correctly.
- **One-way contract.** The API owns and publishes its OpenAPI spec; the gateway depends only on that HTTP
  contract, with its own tolerant-reader models.
- **Errors are written for the model by the gateway.** The API returns stable error codes plus typed
  field/rule detail. The gateway never forwards the API's free-text messages, because they can echo stored or
  user-supplied text (prompt injection) or leak internals. It logs them under a reference ID and maps codes to
  its own messages. Gateway messages include only IDs, known statuses and numbers. See
  [`ErrorSafetyTests`](petstore-mcp/src/test/java/com/dublintech/mcpproto1/mcp/ErrorSafetyTests.java).
- **Transport.** `spring.ai.mcp.server.protocol=STREAMABLE` is set explicitly: when unset, Spring AI 2.0.1
  wires the legacy SSE transport and `/mcp` returns 404. Sessions are stateful (`Mcp-Session-Id`), so running
  several gateway instances would need sticky sessions or the stateless mode.

## Known limitations

- No authentication on either app; both are meant for localhost.
- `buyPet` checks availability and then orders as separate calls, so two concurrent buyers could both pass the
  check. A real fix needs a conditional update in the API.
- Free text in successful results (such as pet names) still reaches the model, as structured data.
- Spring AI repeats each tool error message twice in the result text.
