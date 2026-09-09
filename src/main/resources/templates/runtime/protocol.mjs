// Run against a STARTED generated server: MCP_SERVER_URL=http://localhost:8080/mcp node tests/protocol.mjs
// Tool calls may change upstream data. Use a test upstream and explicit MCP_TEST_ARGUMENTS JSON.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
const spec = JSON.parse(readFileSync(new URL('../mcp.json', import.meta.url), 'utf8'));
const endpoint = process.env.MCP_SERVER_URL;
const command = process.env.MCP_STDIO_COMMAND ? JSON.parse(process.env.MCP_STDIO_COMMAND) : null;
assert.ok(endpoint || Array.isArray(command) && command.length, 'Set MCP_SERVER_URL, or MCP_STDIO_COMMAND to a JSON command array for stdio');
const child = command ? spawn(command[0], command.slice(1), { windowsHide: true, stdio: ['pipe', 'pipe', 'inherit'] }) : null;
const pending = new Map();
if (child) {
  createInterface({ input: child.stdout }).on('line', line => {
    const message = JSON.parse(line);
    pending.get(message.id)?.resolve(message);
  });
  child.on('error', error => { for (const item of pending.values()) item.reject(error); });
  child.on('exit', code => { for (const item of pending.values()) item.reject(new Error(`Stdio server exited: ${code}`)); });
}
let sessionId;
let protocolVersion = '2025-03-26';
let nextId = 1;
async function rpc(method, params = {}, notification = false, expectError = false) {
  const headers = { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', 'MCP-Protocol-Version': protocolVersion };
  if (sessionId) headers['Mcp-Session-Id'] = sessionId;
  if (process.env.MCP_AUTH_TOKEN) headers.Authorization = `Bearer ${process.env.MCP_AUTH_TOKEN}`;
  if (process.env.MCP_API_KEY) headers[process.env.MCP_API_KEY_HEADER || 'X-API-Key'] = process.env.MCP_API_KEY;
  const id = nextId++;
  const body = { jsonrpc: '2.0', ...(notification ? {} : { id }), method, params };
  let result;
  if (child) {
    if (notification) { child.stdin.write(JSON.stringify(body) + '\n'); return; }
    let timer;
    try {
      result = await new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject });
        timer = setTimeout(() => reject(new Error(`${method}: stdio timeout`)), 30000);
        child.stdin.write(JSON.stringify(body) + '\n');
      });
    } finally { clearTimeout(timer); pending.delete(id); }
  } else {
  const response = await fetch(endpoint, { method: 'POST', headers, signal: AbortSignal.timeout(30000),
    body: JSON.stringify(body) });
  assert.ok(response.ok || expectError && response.status === 400, `${method}: HTTP ${response.status}`);
  sessionId = response.headers.get('mcp-session-id') || sessionId;
  if (notification) { await response.arrayBuffer(); return; }
  const text = await response.text();
  const messages = response.headers.get('content-type')?.includes('text/event-stream')
    ? text.split(/\r?\n\r?\n/).map(frame => frame.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n')).filter(Boolean).map(value => JSON.parse(value))
    : [JSON.parse(text)];
  result = messages.find(message => message.id === id);
  }
  assert.ok(result, `${method}: missing response for id ${id}`);
  assert.equal(result.jsonrpc, '2.0');
  if (expectError) {
    assert.ok(result.error || result.result?.isError === true, 'Invalid tool input was accepted');
    return result;
  }
  assert.equal(result.error, undefined, JSON.stringify(result.error));
  return result.result;
}
try {
const init = await rpc('initialize', { protocolVersion, capabilities: {}, clientInfo: { name: 'generated-protocol-tests', version: '1.0' } });
assert.ok(init.serverInfo?.name);
protocolVersion = init.protocolVersion;
await rpc('notifications/initialized', {}, true);
const actual = (await rpc('tools/list')).tools;
assert.deepEqual(actual.map(t => t.name).sort(), spec.tools.map(t => t.name).sort(), 'Tool catalog mismatch');
for (const expected of spec.tools) {
  const tool = actual.find(t => t.name === expected.name);
  assert.deepEqual([...(tool.inputSchema.required || [])].sort(), [...(expected.inputSchema?.required || [])].sort());
}
if (init.capabilities.resources) {
  const resources = (await rpc('resources/list')).resources;
  assert.deepEqual(resources.map(r => r.uri).sort(), spec.resources.filter(r => !r.uriTemplate.includes('{')).map(r => r.uriTemplate).sort());
  const templates = (await rpc('resources/templates/list')).resourceTemplates;
  assert.deepEqual(templates.map(r => r.uriTemplate).sort(), spec.resources.filter(r => r.uriTemplate.includes('{')).map(r => r.uriTemplate).sort());
  for (const resource of spec.resources.filter(r => r.content != null && !r.uriTemplate.includes('{'))) {
    const result = await rpc('resources/read', { uri: resource.uriTemplate });
    assert.equal(result.contents[0].text, resource.content);
  }
}
if (init.capabilities.prompts) {
  assert.deepEqual((await rpc('prompts/list')).prompts.map(p => p.name).sort(), spec.prompts.map(p => p.name).sort());
  for (const prompt of spec.prompts) {
    const args = Object.fromEntries((prompt.arguments || []).map(a => [a.name, 'test-value']));
    const result = await rpc('prompts/get', { name: prompt.name, arguments: args });
    assert.equal(result.messages[0].content.text, (prompt.template || '').replace(/\{\{\s*([^{}]+?)\s*\}\}/g, (_, key) => args[key] || ''));
  }
}
const fixtures = JSON.parse(process.env.MCP_TEST_ARGUMENTS || '{}');
if (process.env.MCP_TEST_KIND === 'integration' && spec.tools.length) {
  assert.ok(Object.keys(fixtures).length, 'Integration tests require MCP_TEST_ARGUMENTS fixtures; no tool calls have been tested');
}
if (process.env.MCP_TEST_KIND === 'fuzz') {
  for (const tool of spec.tools) {
    for (const invalid of [null, [], 17, 'invalid']) {
      await rpc('tools/call', { name: tool.name, arguments: invalid }, false, true);
    }
  }
}
if (process.env.MCP_TEST_KIND === 'load') {
  const start = performance.now();
  for (let batch = 0; batch < 10; batch++) await Promise.all(Array.from({ length: 5 }, () => rpc('ping')));
  assert.ok(performance.now() - start < Number(process.env.MCP_LOAD_BUDGET_MS || 10000), '50 concurrent-batch pings exceeded load budget');
}
for (const [name, args] of Object.entries(fixtures)) {
  const result = await rpc('tools/call', { name, arguments: args });
  assert.notEqual(result.isError, true, JSON.stringify(result));
  assert.ok(Array.isArray(result.content));
}
console.log('MCP handshake and capability contracts passed; tool fixtures executed:', Object.keys(fixtures).length);
} finally { if (child) { child.stdin.end(); child.kill(); } }
