# MCP implementation and verification

Verified locally on 2026-09-09. Changes span `fsp-mcp-generation-svc`, `fsp-api-test-svc`, `fsp-contract-testing-svc`, and `forgesphere-api-lifecycle`. Existing downloaded archives and deployed servers must be regenerated/redeployed to receive these fixes.

Cleanup after verification: generated runtime fixtures, portable Python, Newman/Playwright installations and the browser screenshot under `target/` were removed at the user's request. Results below record the completed runs. Reinstall verification dependencies to reproduce them. Keep `src/main/resources/templates/runtime/`: these are production generator templates required in the service artifact. Regression tests and reusable verification scripts are retained as source.

## Implemented behavior

- OpenAPI import preserves tool JSON schemas, local references, HTTP method, upstream URL, path/query/header arguments and JSON request bodies. The frontend wizard uses the corrected parser. Generated tools validate arguments and execute real upstream HTTP requests; upstream failures remain failures.
- Generated TypeScript, Python and Spring Boot servers expose MCP initialization, tools, resources, resource templates and prompts. Python and TypeScript also support stdio. HTTP session negotiation, initialized notifications, protocol headers and streaming responses are handled by the clients.
- MCP Test performs real connection/discovery and tool calls. Resources and prompts have list/read/get controls. Local browser connections support custom API-key headers; connection failures are no longer represented as successful discovery or placeholder tools. Streaming responses are matched by JSON-RPC ID without waiting for the stream to close.
- Downloadable mocks implement the MCP protocol and validate arguments, serve configured mock responses, read resources and render prompts. Mock responses are intentionally fixtures rather than upstream production data.
- Generated native and protocol tests execute assertions. Selected integration/contract checks call supplied tool fixtures; negative and latency checks score actual responses. CI no longer hides failed tests. Postman collections initialize the connection, propagate sessions, parse JSON/SSE and assert responses using executable scripts.
- Language-aware code analysis uses the TypeScript parser and Python AST. Java analysis is only applied to Java source. MCP rules inspect source and manifest content, exclude dependencies/build artifacts, and report unavailable or failed analysis instead of passing it silently.
- Clone/version UI actions call the backend. Clones get fresh identity and requested name/version; new versions preserve lineage and validate version progression. Generated files, deployment/repository references and previous execution results are reset. Version-specific repository names and unique version keys avoid reusing the parent's deployment identity.
- Generated API-key authentication, CORS, rate limiting, metrics, health endpoints and local `.env` loading have executable implementations. Default connection ports match the generated language runtime.

## Evidence

| Check | Result / scope |
| --- | --- |
| Generator service | 13 JUnit tests passed: generation, OpenAPI bindings, options, lifecycle and mock runtime |
| API-test transport | 3 tests passed using a real local HTTP server, including stateless initialization, auth failure and an open SSE response stream |
| Contract analysis | 5 tests passed: actual parser errors/findings, applicability and exclusions |
| Frontend | 12 Node regression tests passed; Vite production build passed |
| Generated TypeScript | TypeScript compilation passed; 9 native tests across HTTP, authenticated and stdio fixtures passed |
| Generated Spring Boot | Default and authenticated Maven packages/tests passed |
| Generated Python | 4 native tests and Ruff passed |
| Live HTTP MCP | All three languages passed capability schemas, real upstream calls, protocol runners and client bridge checks |
| Authenticated runtime | All three passed `.env` key loading, missing-key rejection, CORS, metrics and rate limiting |
| Postman execution | Authenticated Newman collection passed 15 assertions per language |
| Stdio and mock ZIP | Node/Python generated stdio and downloadable mock passed actual SDK capability calls |
| Browser MCP Test | Connect, discovery, real tool execution, resource/template reads and prompt get passed |

Live verification scripts start generated servers and a controlled real HTTP upstream. They check official MCP SDK response schemas, tools/resources/templates/prompts, generated protocol runners and the generated stdio-to-HTTP client bridge. Authenticated checks cover missing-key rejection, custom CORS headers, metrics and actual rate-limit rejection. Postman verification uses Newman, rather than inspecting collection JSON alone.

The browser verification opens the actual `MCPTest` component in headless Chrome and connects to a generated server. Tool execution reaches the real local upstream; resource/template reads and prompt rendering are exercised. External account/catalog endpoints are fixture routes, so this is not a full authenticated platform deployment test. The screenshot was removed during temporary-artifact cleanup; rerunning the script recreates `target/mcp-ui-verification.png`.

## Reproduce

Run service JUnit tests with `mvn test`. Generator tests create fixtures under `target/runtime-verification`; install their generated dependencies and build TypeScript/Java before running the scripts. The scripts require Node, Java, Python with generated requirements, and (for Postman/browser verification) Newman and Playwright. The verification setup used `target/postman-runner`, `target/ui-runner`, and `target/python-runtime`; these temporary installations have been removed. Inspect script paths before reinstalling dependencies or running on another machine.

From this service directory, run sequentially because the scripts share local ports:

```powershell
$env:MCP_PYTHON=(Resolve-Path target/python-runtime/python.exe).Path
foreach ($language in @('typescript','java','python')) {
  node scripts/verify-runtime.mjs $language
  node scripts/verify-runtime.mjs $language --auth --env-file
  node scripts/verify-runtime.mjs $language postman --auth
}
node scripts/verify-stdio.mjs typescript
node scripts/verify-stdio.mjs python
node scripts/verify-mock.mjs
node scripts/verify-ui.mjs
```

For contract-service source analysis outside Docker, set `MCP_ANALYSIS_PYTHON` to a Python executable and `MCP_TYPESCRIPT_MODULE` to the installed TypeScript module directory. The updated Dockerfile installs these parser dependencies; the Docker image itself was not built during this verification.

## Boundaries and remaining deployment verification

- Live MongoDB persistence/concurrency, authenticated full-wizard navigation, GCS archive storage, repository provisioning and cloud CI/deployment were not exercised. Clone/version tests verify service persistence calls and frontend behavior with controlled dependencies; they are not proof of a live cloud deployment. Restart/deploy the updated services and frontend, then regenerate a project and verify its saved clone/version lineage in the target environment.
- Java stdio, legacy SSE generation, OAuth and custom authentication implementations are unsupported and rejected, rather than presented as working generated runtimes.
- OpenAPI external/recursive references, non-JSON request bodies and unsupported parameter serialization require additional support. Unsupported import cases fail explicitly. Legacy Postman/Insomnia imports were not upgraded to the same real HTTP-binding coverage as OpenAPI.
- Automatically generated example arguments cannot guarantee valid business data for every upstream API. Supply controlled fixture values for real integration execution, especially mutating operations.
- Fuzz tests use a bounded negative-input corpus; load tests use 50 pings. These are executable checks, not comprehensive fuzzing or production capacity certification. MCP source rules include heuristic checks; parser analysis is not a complete security audit.
- Desktop client configuration and the bridge were verified through the SDK, not through installed Claude/Cursor/VS Code applications. Rate limiting is per runtime process.

Protocol references: [MCP transports](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports), [official Python SDK ASGI guidance](https://github.com/modelcontextprotocol/python-sdk/blob/main/docs/run/asgi.md), and [MCP Inspector](https://github.com/modelcontextprotocol/inspector).
