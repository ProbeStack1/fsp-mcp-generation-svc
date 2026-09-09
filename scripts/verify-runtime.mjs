import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { readFile, writeFile, unlink } from 'node:fs/promises';
const language = process.argv[2] || 'typescript';
const postman = process.argv[3] === 'postman';
const auth = process.argv.includes('--auth');
const cwd = resolve('target/runtime-verification', language, auth ? 'auth-fixture' : '.');
const authEnv = auth ? { MCP_API_KEY: 'fixture-secret', MCP_API_KEY_HEADER: 'X-Fixture-Key' } : {};
const envFile = auth && process.argv.includes('--env-file');
const envPath = resolve(cwd, '.env');
const oldEnv = envFile ? await readFile(envPath, 'utf8').catch(() => null) : null;
if (envFile) await writeFile(envPath, 'MCP_API_KEY=fixture-secret\n');
const port = 18991;
let calls = 0;
const upstream = createServer((req, res) => {
  calls++;
  assert.equal(req.method, 'GET');
  if (postman) assert.ok(req.url.startsWith('/items/'));
  else assert.equal(req.url, '/items/hello%20world');
  res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ id: 'hello world', ok: true }));
});
await new Promise(resolve => upstream.listen(18990, '127.0.0.1', resolve));
const command = language === 'java' ? ['java', '-jar', 'target/mcp-fixture-0.1.0.jar']
  : language === 'python' ? [process.env.MCP_PYTHON || 'python', 'server.py'] : ['node', 'dist/index.js'];
let output = '';
const runtimeEnv = { ...process.env, ...authEnv, PORT: String(port), UPSTREAM_BASE_URL: 'http://127.0.0.1:18990' };
if (envFile) delete runtimeEnv.MCP_API_KEY;
const server = spawn(command[0], command.slice(1), { cwd, env: runtimeEnv, windowsHide: true });
server.stdout.on('data', data => { output += data; }); server.stderr.on('data', data => { output += data; });
server.on('error', error => { output += error.message; });
async function run(kind) {
  const child = spawn('node', [kind === 'fuzz' || kind === 'load' ? 'tests/protocol.mjs' : `tests/${kind}/run.mjs`], { cwd, windowsHide: true,
    env: { ...process.env, ...authEnv, MCP_TEST_KIND: kind, MCP_SERVER_URL: `http://127.0.0.1:${port}/mcp`, MCP_TEST_ARGUMENTS: JSON.stringify({ get_item: { id: 'hello world' } }) }, stdio: 'inherit' });
  await new Promise((resolve, reject) => { child.on('error', reject); child.on('exit', code => code === 0 ? resolve() : reject(new Error(`${kind} exited ${code}`))); });
}
try {
  let ready = false;
  for (let i = 0; i < 100; i++) {
    if (server.exitCode !== null) throw new Error(output);
    try { if ((await fetch(`http://127.0.0.1:${port}/healthz`)).ok) { ready = true; break; } } catch {}
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  assert.ok(ready, 'Server did not become healthy: ' + output);
  if (auth) {
    const rejected = await fetch(`http://127.0.0.1:${port}/mcp`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"jsonrpc":"2.0","id":1,"method":"ping"}' });
    assert.equal(rejected.status, 401);
    const metrics = await fetch(`http://127.0.0.1:${port}/metrics`);
    assert.match(await metrics.text(), /mcp_requests_total [1-9]/);
    const cors = await fetch(`http://127.0.0.1:${port}/mcp`, { method: 'OPTIONS', headers: { Origin: 'https://client.example', 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'X-Fixture-Key,MCP-Protocol-Version' } });
    assert.equal(cors.headers.get('access-control-allow-origin'), 'https://client.example');
    assert.match(cors.headers.get('access-control-allow-headers'), /x-fixture-key/i);
  }
  if (postman) {
    const require = createRequire(import.meta.url);
    const newman = require('../target/postman-runner/node_modules/newman');
    const summary = await new Promise((resolve, reject) => newman.run({
      collection: `${cwd}/postman/collection.json`,
      envVar: [{ key: 'mcpUrl', value: `http://127.0.0.1:${port}/mcp` }, { key: 'authToken', value: auth ? 'fixture-secret' : '' }],
      reporters: [], timeoutRequest: 10000,
    }, (error, summary) => error ? reject(error) : resolve(summary)));
    assert.deepEqual(summary.run.failures.map(f => ({ source: f.source?.name, message: f.error.message })), []);
    console.log(`${language}: Postman collection executed by Newman (${summary.run.stats.assertions.total} assertions)`);
  } else {
    await run('contract'); await run('integration');
    if (!auth) { await run('fuzz'); await run('load'); }
    const sdk = resolve('target/runtime-verification/typescript/node_modules/@modelcontextprotocol/sdk/dist/esm');
    const { Client } = await import(pathToFileURL(resolve(sdk, 'client/index.js')));
    const { StreamableHTTPClientTransport } = await import(pathToFileURL(resolve(sdk, 'client/streamableHttp.js')));
    const client = new Client({ name: 'sdk-verification', version: '1.0.0' });
    try {
      await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`), { requestInit: { headers: auth ? { 'X-Fixture-Key': 'fixture-secret' } : {} } }));
      assert.equal((await client.listTools()).tools.length, 1);
      assert.equal((await client.listResources()).resources.length, 1);
      assert.equal((await client.listResourceTemplates()).resourceTemplates.length, 1);
      assert.equal((await client.listPrompts()).prompts.length, 1);
      assert.equal((await client.readResource({ uri: 'docs://items/42' })).contents[0].text, 'Item 42');
      assert.equal((await client.getPrompt({ name: 'greet', arguments: { who: 'Ada' } })).messages[0].content.text, 'Hello Ada');
    } finally { await client.close(); }
    console.log(`${language}: official MCP SDK validated all capability response schemas`);
    const { StdioClientTransport } = await import(pathToFileURL(resolve(sdk, 'client/stdio.js')));
    const bridgeClient = new Client({ name: 'bridge-verification', version: '1.0.0' });
    try {
      await bridgeClient.connect(new StdioClientTransport({ command: process.execPath,
        args: [resolve('target/runtime-verification/typescript/client-configs/http-bridge.mjs'), `http://127.0.0.1:${port}/mcp`],
        env: { ...process.env, ...authEnv }, stderr: 'pipe' }));
      assert.equal((await bridgeClient.listTools()).tools[0].name, 'get_item');
      assert.equal((await bridgeClient.getPrompt({ name: 'greet', arguments: { who: 'Bridge' } })).messages[0].content.text, 'Hello Bridge');
    } finally { await bridgeClient.close(); }
    console.log(`${language}: generated stdio-to-HTTP client bridge verified`);
  }
  assert.ok(calls >= 2, 'Tool calls did not reach the upstream');
  if (auth) {
    let limited = false;
    for (let n = 0; n < 110; n++) {
      const reply = await fetch(`http://127.0.0.1:${port}/mcp`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Fixture-Key': 'fixture-secret' }, body: '{"jsonrpc":"2.0","id":1,"method":"ping"}' });
      await reply.arrayBuffer();
      if (reply.status === 429) { limited = true; break; }
    }
    assert.ok(limited, 'Configured request rate limit never rejected excess traffic');
    assert.equal((await fetch(`http://127.0.0.1:${port}/healthz`)).status, 200, 'Health must stay reachable after MCP rate limiting');
    console.log(`${language}: API-key, custom CORS headers, metrics and real rate-limit rejection passed`);
  }
  console.log(`${language}: live MCP server and upstream API verified (${calls} calls)`);
} catch (error) {
  console.error(output);
  throw error;
} finally {
  server.kill(); await new Promise(resolve => upstream.close(resolve));
  if (envFile) { if (oldEnv === null) await unlink(envPath); else await writeFile(envPath, oldEnv); }
}
