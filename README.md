# Petstore MCP

The [Swagger Petstore](https://petstore.swagger.io/) built as a Spring Boot REST API, with a separate
**MCP gateway** in front of it so AI clients such as Claude Desktop and Claude Code can use the store through
the [Model Context Protocol](https://modelcontextprotocol.io/): tools, resources and prompts.

It's a learning project. The point is less the pet store than the architecture questions it raises: how an MCP
server should sit in front of an existing API, and what changes when the caller is a language model.

```
Claude Desktop ─stdio─▶ mcp-remote ─Streamable HTTP + token─▶ petstore-mcp :8081 ─REST─▶ petstore-api :8080
  (MCP host)              (bridge)                            (MCP server / gateway)       (backend)
                              │ login, tokens                         │ signing keys
                              ▼                                       ▼
                         petstore-auth :9000 (authorization server) ◀─┘
```

## Modules

| Module | Port | What it is |
|---|---|---|
| [`petstore-api`](petstore-api) | 8080 | The Petstore REST API: all 20 operations under `/v2`, an in-memory store seeded from [sample data](petstore-api/src/main/resources/sample-data) at startup, and its own spec at `GET /v2/openapi.json`. Knows nothing about MCP. |
| [`petstore-mcp`](petstore-mcp) | 8081 | The MCP gateway, served at `/mcp`. It has **no dependency** on `petstore-api` and reaches it only over HTTP through [`PetstoreApiClient`](petstore-mcp/src/main/java/com/dublintech/mcpproto1/mcp/client/PetstoreApiClient.java). |
| [`petstore-auth`](petstore-auth) | 9000 | The OAuth 2.1 authorization server: login, consent and signed JWT access tokens (Authorization Code + PKCE). The gateway accepts only its tokens; see [Security](#security). |

Built with Java 21, Spring Boot 4.1.1 and Spring AI 2.0.1 (MCP Java SDK 2.0.0, protocol up to `2025-11-25`).

## Running it

Start each app in its own terminal:

```bash
./gradlew :petstore-api:bootRun     # http://localhost:8080/v2
./gradlew :petstore-auth:bootRun    # http://localhost:9000
./gradlew :petstore-mcp:bootRun     # http://localhost:8081/mcp
```

Run the tests for all modules with `./gradlew test`. (On Windows PowerShell, use `.\gradlew.bat`.)

Data lives in memory, so restarting the API resets it to the sample set. Sample users are `user1`–`user3`
with passwords `password1`–`password3`. The authorization server also keeps everything in memory, including its
signing key, so restarting it logs every client out.

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

**Claude Desktop** launches local servers over stdio, so it reaches the HTTP gateway through the
[`mcp-remote`](https://www.npmjs.com/package/mcp-remote) bridge (needs Node.js), which also does the OAuth login.
Add this to `claude_desktop_config.json` (**Settings → Developer → Edit Config**), then fully quit and reopen
Claude Desktop:

```json
{
  "mcpServers": {
    "petstore": {
      "command": "npx",
      "args": ["-y", "mcp-remote@0.15.0", "http://localhost:8081/mcp", "3334",
               "--static-oauth-client-info", "{\"client_id\":\"petstore-mcp-client\"}",
               "--auth-timeout", "120"]
    }
  }
}
```

On startup a browser opens at the authorization server: log in and approve the scopes. `3334` is the callback
port registered for the client (`http://127.0.0.1:3334/oauth/callback`), and `--static-oauth-client-info` names
the pre-registered client, because `petstore-auth` doesn't support dynamic client registration. `mcp-remote`
keeps the tokens in `~/.mcp-auth` and refreshes them without the browser.

The Microsoft Store build of Claude Desktop keeps this file under
`%LOCALAPPDATA%\Packages\Claude_<id>\LocalCache\Roaming\Claude\`, not `%APPDATA%\Claude\`. Tools and prompts
appear under the **+** menu. Claude Desktop doesn't request resource templates, so the two `pets/{petId}`
resources only show up in clients that do.

Claude Code, the MCP Inspector and the [Bruno collection](bruno/petstore-mcp) don't have a login set up yet, so
the gateway answers them with `401`. (Bruno holds raw JSON-RPC requests for the session handshake, tools,
resources, prompts and ending a session; `01` saves the `Mcp-Session-Id` for the rest.)

### Watching the traffic

[`scripts/trace/trace.ps1`](scripts/trace/trace.ps1) puts [mitmweb](https://mitmproxy.org/) (`pip install
mitmproxy`) in front of all three apps and records every HTTP request between Claude Desktop's `mcp-remote`
processes, the browser, the gateway, the authorization server and the API. Each flow is labelled with the process
that sent it. Start it first, then the apps on the back-door ports it prints. Recordings go to `build/trace/` and
contain passwords and tokens.

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

## Security

Security is being added step by step, following the
[MCP authorization spec](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization) (OAuth 2.1).

### What problem OAuth solves, in plain terms

Without it, the store has no locks: any program on the machine can browse, buy a pet or read any user's details,
and nobody is ever asked who they are. OAuth adds four things:

1. **Who are you?** You prove who you are once, to one trusted place (the authorization server), and every
   request then carries proof that it's on your behalf.
2. **What may you do?** When you log in, you approve what the app may do for you, such as browsing
   (`petstore.read`) but not buying (`petstore.buy`). The agent gets exactly those powers.
3. **Your password is never handed around.** You type it only at the authorization server. Claude Desktop,
   `mcp-remote`, the gateway and the API never see it; they get a **token** instead.
4. **A leak does limited damage.** A token expires quickly, works at only one service, allows only the actions
   you approved, and reveals nothing about your password.

It works like a hotel key card:

| Hotel | This system |
|---|---|
| Reception checks your ID once | You log in at the **authorization server** |
| You get a key card, not the master key | You get an **access token**, not your password |
| The card opens your room and the gym, not other rooms | **Scopes** and **audience**: only certain actions, at certain services |
| It stops working at checkout | The token **expires** |
| Each door reads the card itself, without phoning reception | The gateway and API **verify the token's signature themselves** |

This matters more with AI because you are lending power to an agent. OAuth is built for lending limited power
without handing over your keys, like a car's valet key that can drive but not open the boot. The token stays
inside `mcp-remote`, so the model never sees it.

OAuth controls who may do what. It does not stop a malicious tool description, a prompt injection, or a user
approving something they shouldn't; the safe error handling, confirmation before `buyPet` and least-privilege
tools above cover those.

### Roadmap

| Step | What | Status |
|---|---|---|
| 0 | Origin validation on `/mcp` (DNS rebinding) and loopback binding | Done |
| 1 | Authorization server (`petstore-auth`): logins, consent, signed JWT access tokens | Done |
| 2 | API checks tokens (resource server) | Planned |
| 3 | Gateway checks tokens and advertises its authorization server (`401` + protected resource metadata) | Done |
| 4 | Claude Desktop logs in through `mcp-remote` (Authorization Code + PKCE, rotating refresh tokens) | Done |
| 5 | Gateway gets its own token for the API (no token passthrough) | Planned |
| 6 | Token exchange so the API knows which user is behind a gateway call | Planned |
| 7 | Per-user data: users see only their own orders | Planned |
| 8 | Step-up: `buyPet` needs an extra `petstore.buy` consent | Planned |

Steps 2 and 5 go together: once the API demands a token, the gateway needs its own.

The gateway accepts a token only if it is signed by `petstore-auth`, unexpired, issued by `http://localhost:9000`
and meant for it (`aud` = `http://localhost:8081/mcp`, from the `resource` parameter), and every request needs
`petstore.read`. [`TokenValidationTests`](petstore-mcp/src/test/java/com/dublintech/mcpproto1/mcp/security/TokenValidationTests.java)
tries the tokens that must fail.

## Known limitations

- Authentication is not finished yet (see the security roadmap): the API itself still accepts anyone, so all
  three apps are meant for localhost only.
- The authorization server's signing key, sessions and refresh tokens are in memory, so a restart means logging in
  again.
- `buyPet` checks availability and then orders as separate calls, so two concurrent buyers could both pass the
  check. A real fix needs a conditional update in the API.
- Free text in successful results (such as pet names) still reaches the model, as structured data.
- Spring AI repeats each tool error message twice in the result text.
