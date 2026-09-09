// Local stdio adapter for clients that cannot read an HTTP URL from their JSON config.
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import * as types from '@modelcontextprotocol/sdk/types.js';
const remote = new Client({ name: 'generated-http-bridge', version: '1.0.0' });
const headers = {};
if (process.env.MCP_AUTH_TOKEN) headers.Authorization = `Bearer ${process.env.MCP_AUTH_TOKEN}`;
if (process.env.MCP_API_KEY) headers[process.env.MCP_API_KEY_HEADER || 'X-API-Key'] = process.env.MCP_API_KEY;
await remote.connect(new StreamableHTTPClientTransport(new URL(process.argv[2]), { requestInit: { headers } }));
const capabilities = remote.getServerCapabilities();
const server = new Server(remote.getServerVersion(), { capabilities: {
  ...(capabilities.tools ? { tools: {} } : {}), ...(capabilities.resources ? { resources: {} } : {}),
  ...(capabilities.prompts ? { prompts: {} } : {}),
} });
if (capabilities.tools) {
  server.setRequestHandler(types.ListToolsRequestSchema, request => remote.listTools(request.params));
  server.setRequestHandler(types.CallToolRequestSchema, request => remote.callTool(request.params));
}
if (capabilities.resources) {
  server.setRequestHandler(types.ListResourcesRequestSchema, request => remote.listResources(request.params));
  server.setRequestHandler(types.ListResourceTemplatesRequestSchema, request => remote.listResourceTemplates(request.params));
  server.setRequestHandler(types.ReadResourceRequestSchema, request => remote.readResource(request.params));
}
if (capabilities.prompts) {
  server.setRequestHandler(types.ListPromptsRequestSchema, request => remote.listPrompts(request.params));
  server.setRequestHandler(types.GetPromptRequestSchema, request => remote.getPrompt(request.params));
}
await server.connect(new StdioServerTransport());
process.stdin.on('end', async () => { await remote.close(); await server.close(); });
