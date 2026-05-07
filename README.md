# forgesphere-mcp-generation-svc

A lean Spring Boot microservice that generates runnable MCP (Model
Context Protocol) server codebases from a declarative wizard spec.

* **Java 17 + Spring Boot 3.1** · MongoDB · Webflux (only for the
  outbound probe client) · No other heavyweight deps.
* **No shared auth filter.** The consuming platform mounts its own auth
  filter in front of this service (OAuth / API key / whatever). See
  `CorsConfig.java` for the configurable origin allow-list.
* **Context path** — all endpoints live under `/mcp-generate/v1/api`.

## Quick start

```bash
cd /app/sphere/project/forgesphere-mcp-generation-svc
mvn -DskipTests spring-boot:run
# or package + run:
mvn -DskipTests package && java -jar target/forgesphere-mcp-generation-svc-1.0.0.jar
```

Requires a local MongoDB at `mongodb://localhost:27017` (override with
`MONGO_URL`). Service listens on port `8100` by default (override with
`SERVER_PORT`).

## Endpoints

| Method | Path | What |
|--------|------|------|
| POST   | `/projects`                        | Create a wizard project |
| GET    | `/projects?ownerEmail=…&workspaceId=…` | List projects (filtered) |
| GET    | `/projects/{id}`                   | Fetch one project |
| PUT    | `/projects/{id}`                   | Update (patch semantics) |
| DELETE | `/projects/{id}`                   | Delete |
| POST   | `/projects/{id}/generate`          | Materialise files into Mongo |
| POST   | `/projects/generate-inline`        | Generate from a raw spec (no DB write) |
| GET    | `/projects/{id}/files`             | List generated file paths + sizes |
| GET    | `/projects/{id}/files/content?path=…` | Fetch single file content |
| GET    | `/projects/{id}/download`          | Stream project as a `.zip` |
| GET    | `/projects/{id}/client-configs`    | Claude / Cursor / ForgeQ snippets |
| POST   | `/probe`                           | Proxy-probe any MCP URL (`mock:true` supported) |
| POST   | `/call`                            | Proxy-call one tool (`mock:true` supported) |
| GET    | `/token`                           | Generate a 32-byte hex bearer token |

All responses use `{ success, data, error }` envelopes. Errors return
HTTP 400 / 500 with the same shape.

## Generators

Three language generators implement `CodeGenerator`:

* **TypeScriptGenerator** — primary. Uses `@modelcontextprotocol/sdk`.
  Emits `package.json`, `tsconfig.json`, `src/index.ts`, `src/server.ts`,
  one file per tool under `src/tools/`, `.env.example`, `Dockerfile`,
  `mcp.json`, `README.md`, `.gitignore`.
* **PythonGenerator** — uses the `mcp` PyPI package. Single `server.py`
  with all tools/resources/prompts registered via decorators.
* **JavaSpringGenerator** — Spring Boot scaffold, raw JSON-RPC over HTTP.

Add a new language by dropping a class annotated `@Component` that
implements `CodeGenerator`; `McpGenerationService` auto-discovers it.

## Data model

Single document collection `mcp_projects` with embedded
`identity / capabilities / runtime / transport / auth / generated`. See
`model/McpProject.java` for the full shape. No tables beyond that.

## What this service explicitly doesn't do

* No GitHub push (out of scope — forgeq already has a GitHub adapter).
* No CI/CD pipeline provisioning.
* No "publish to official MCP registry".
* No execution of user code — the probe proxy only speaks HTTP JSON-RPC.
