import { spawn } from 'node:child_process';
import { createServer as httpServer } from 'node:http';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { writeFile, unlink, readdir } from 'node:fs/promises';
import assert from 'node:assert/strict';
const frontend = resolve('../forgesphere-api-lifecycle');
const { createServer } = await import(pathToFileURL(resolve(frontend, 'node_modules/vite/dist/node/index.js')));
const { chromium } = await import(pathToFileURL(resolve('target/ui-runner/node_modules/playwright/index.mjs')));
const harness = resolve(frontend, '.mcp-browser-verification.html');
const errors = []; let calls = 0; let output = '';
const upstream = httpServer((req, res) => {
  calls++; res.setHeader('Content-Type', 'application/json');
  res.end(JSON.stringify({ evidence: 'upstream-ui-result', path: req.url }));
});
let vite, browser, runtime;
try {
  await new Promise(resolve => upstream.listen(18990, '127.0.0.1', resolve));
  runtime = spawn(process.execPath, ['dist/index.js'], { cwd: resolve('target/runtime-verification/typescript'), windowsHide: true,
    env: { ...process.env, PORT: '18991', UPSTREAM_BASE_URL: 'http://127.0.0.1:18990' } });
  runtime.stdout.on('data', data => { output += data; }); runtime.stderr.on('data', data => { output += data; });
  await writeFile(harness, `<!doctype html><html><body><div id="root" style="height:100vh"></div><script type="module">
    import React from 'react'; import {createRoot} from 'react-dom/client'; import {MemoryRouter} from 'react-router-dom';
    import MCPTest from '/src/pages/MCPTest.jsx';
    createRoot(document.getElementById('root')).render(React.createElement(MemoryRouter,null,React.createElement(MCPTest)));
  </script></body></html>`);
  vite = await createServer({ root: frontend, server: { host: '127.0.0.1', port: 18777, strictPort: true }, logLevel: 'error' });
  await vite.listen();
  browser = await chromium.launch({ executablePath: process.env.MCP_BROWSER || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1050 } });
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/*', route => {
    const url = new URL(route.request().url());
    if (['18777', '18991'].includes(url.port) || url.hostname.includes('jsdelivr.net')) return route.continue();
    // The catalog/account service is outside this local test. MCP traffic goes to the real generated runtime.
    return route.fulfill({ status: 200, contentType: 'application/json', headers: {
      'Access-Control-Allow-Origin': 'http://127.0.0.1:18777', 'Access-Control-Allow-Credentials': 'true',
      'Access-Control-Allow-Headers': route.request().headers()['access-control-request-headers'] || '*',
    }, body: '[]' });
  });
  await page.goto('http://127.0.0.1:18777/.mcp-browser-verification.html');
  const css = (await readdir(resolve(frontend, 'dist/assets'))).find(name => name.startsWith('index-') && name.endsWith('.css'));
  if (css) await page.addStyleTag({ path: resolve(frontend, 'dist/assets', css) });
  await page.getByTestId('mcp-tab-inspector').click({ timeout: 60000 });
  await page.getByRole('button', { name: 'Connect local server', exact: true }).click();
  await page.getByTestId('register-name-input').fill('Browser fixture');
  await page.getByTestId('register-url-input').fill('http://127.0.0.1:18991/mcp');
  await page.getByTestId('register-submit-btn').click();
  await page.getByTestId('tool-get_item').click({ timeout: 30000 });
  await page.getByTestId('run-tool-btn').click();
  await page.getByText('upstream-ui-result', { exact: false }).first().waitFor({ timeout: 30000 });
  assert.ok(calls > 0, 'UI tool execution did not reach the upstream');
  await page.getByRole('button', { name: 'Resources', exact: true }).click();
  await page.getByLabel('Capability', { exact: true }).selectOption('0');
  await page.getByRole('button', { name: 'Read resource', exact: true }).click();
  await page.getByText('Hello guide', { exact: false }).first().waitFor();
  await page.getByRole('button', { name: 'Resource templates', exact: true }).click();
  await page.getByLabel('Capability', { exact: true }).selectOption('0');
  await page.getByLabel('Capability input').fill('docs://items/42');
  await page.getByRole('button', { name: 'Read resource', exact: true }).click();
  await page.getByText('Item 42', { exact: false }).first().waitFor();
  await page.getByRole('button', { name: 'Prompts', exact: true }).click();
  await page.getByLabel('Capability', { exact: true }).selectOption('0');
  await page.getByLabel('Capability input').fill('{"who":"Ada"}');
  await page.getByRole('button', { name: 'Get prompt', exact: true }).click();
  await page.getByText('Hello Ada', { exact: false }).first().waitFor();
  await page.screenshot({ path: resolve('target/mcp-ui-verification.png'), fullPage: true });
  assert.deepEqual(errors, []);
  console.log('Browser UI verified: connect, discover, invoke real tool, read resource/template and get prompt');
} catch (error) { console.error(output); console.error(errors); throw error; }
finally {
  await browser?.close(); await vite?.close(); runtime?.kill();
  await new Promise(resolve => upstream.close(resolve));
  await unlink(harness).catch(() => {});
}
