import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
const sdk = resolve('target/runtime-verification/typescript/node_modules/@modelcontextprotocol/sdk/dist/esm');
const { Client } = await import(pathToFileURL(resolve(sdk, 'client/index.js')));
const { StdioClientTransport } = await import(pathToFileURL(resolve(sdk, 'client/stdio.js')));
const transport = new StdioClientTransport({ command: process.execPath, args: [resolve('target/runtime-verification/typescript/mock/index.js')], stderr: 'pipe' });
const client = new Client({ name: 'mock-verification', version: '1.0.0' });
try {
  await client.connect(transport);
  assert.equal((await client.listTools()).tools[0].name, 'get_item');
  const call = await client.callTool({ name: 'get_item', arguments: { id: 'hello' } });
  assert.ok(!call.isError); assert.ok(JSON.parse(call.content[0].text));
  assert.equal((await client.callTool({ name: 'get_item', arguments: {} })).isError, true);
  assert.equal((await client.listResources()).resources[0].uri, 'docs://guide');
  assert.equal((await client.listResourceTemplates()).resourceTemplates[0].uriTemplate, 'docs://items/{id}');
  assert.ok((await client.readResource({ uri: 'docs://items/42' })).contents[0].text);
  assert.equal((await client.listPrompts()).prompts[0].name, 'greet');
  assert.equal((await client.getPrompt({ name: 'greet', arguments: { who: 'Ada' } })).messages[0].content.text, 'Hello Ada');
  await assert.rejects(() => client.getPrompt({ name: 'greet', arguments: {} }));
  console.log('Generated mock ZIP: real SDK stdio handshake, tool validation, resources/templates and prompts passed');
} finally { await client.close(); }
