import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';
const language = process.argv[2] || 'typescript';
const cwd = resolve('target/runtime-verification', language, 'stdio-fixture');
let calls = 0;
const upstream = createServer((req, res) => {
  calls++; assert.equal(req.url, '/items/hello%20world');
  res.setHeader('Content-Type', 'application/json'); res.end('{"ok":true}');
});
await new Promise(resolve => upstream.listen(18990, '127.0.0.1', resolve));
try {
  const command = language === 'python' ? [process.env.MCP_PYTHON || 'python', 'server.py'] : [process.execPath, 'dist/index.js'];
  const runner = spawn(process.execPath, ['tests/integration/run.mjs'], { cwd, windowsHide: true, stdio: 'inherit',
    env: { ...process.env, MCP_STDIO_COMMAND: JSON.stringify(command), UPSTREAM_BASE_URL: 'http://127.0.0.1:18990', MCP_TEST_ARGUMENTS: '{"get_item":{"id":"hello world"}}' } });
  await new Promise((resolve, reject) => { runner.on('error', reject); runner.on('exit', code => code === 0 ? resolve() : reject(new Error(`Stdio test exited ${code}`))); });
  assert.equal(calls, 1);
  console.log(`${language}: generated stdio server performed actual MCP capability and upstream calls`);
} finally { await new Promise(resolve => upstream.close(resolve)); }
