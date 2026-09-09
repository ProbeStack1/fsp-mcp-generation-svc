const { Server } = require('@modelcontextprotocol/sdk/server/index.js');
const { StdioServerTransport } = require('@modelcontextprotocol/sdk/server/stdio.js');
const types = require('@modelcontextprotocol/sdk/types.js');
const Ajv = require('ajv');
const spec = __SPEC__;
const validator = new Ajv({ strict: false, allErrors: true });
const server = new Server({ name: 'mcp-mock', version: '1.0.0' }, { capabilities: { tools: {}, resources: {}, prompts: {} } });
server.setRequestHandler(types.ListToolsRequestSchema, async () => ({ tools: spec.tools.map(t => ({ name: t.name, description: t.description || '', inputSchema: t.inputSchema || { type: 'object' } })) }));
server.setRequestHandler(types.CallToolRequestSchema, async request => {
  const tool = spec.tools.find(t => t.name === request.params.name);
  if (!tool) throw new Error('Unknown tool');
  const valid = validator.compile(tool.inputSchema || { type: 'object' });
  if (!valid(request.params.arguments || {})) return { isError: true, content: [{ type: 'text', text: 'Invalid input: ' + validator.errorsText(valid.errors) }] };
  return { content: [{ type: 'text', text: JSON.stringify(spec.responses[tool.name]) }] };
});
server.setRequestHandler(types.ListResourcesRequestSchema, async () => ({ resources: spec.resources.filter(r => !r.uriTemplate.includes('{')).map(r => ({ name: r.name, uri: r.uriTemplate, mimeType: r.mimeType || 'application/json' })) }));
server.setRequestHandler(types.ListResourceTemplatesRequestSchema, async () => ({ resourceTemplates: spec.resources.filter(r => r.uriTemplate.includes('{')).map(r => ({ name: r.name, uriTemplate: r.uriTemplate, mimeType: r.mimeType || 'application/json' })) }));
server.setRequestHandler(types.ReadResourceRequestSchema, async request => {
  const resource = spec.resources.find(r => {
    const pattern = r.uriTemplate.split(/\{[^}]+\}/).map(s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('[^/]+');
    return new RegExp('^' + pattern + '$').test(request.params.uri);
  });
  if (!resource) throw new Error('Unknown resource');
  return { contents: [{ uri: request.params.uri, mimeType: resource.mimeType || 'application/json', text: JSON.stringify(spec.resourceData[resource.name]) }] };
});
server.setRequestHandler(types.ListPromptsRequestSchema, async () => ({ prompts: spec.prompts.map(p => ({ name: p.name, description: p.description || '', arguments: (p.arguments || []).map(a => ({ name: a.name, description: a.description || '', required: Boolean(a.required) })) })) }));
server.setRequestHandler(types.GetPromptRequestSchema, async request => {
  const prompt = spec.prompts.find(p => p.name === request.params.name);
  if (!prompt) throw new Error('Unknown prompt');
  const args = request.params.arguments || {};
  for (const arg of prompt.arguments || []) if (arg.required && !(arg.name in args)) throw new Error('Missing required prompt argument: ' + arg.name);
  const text = (prompt.template || '').replace(/\{\{\s*([^{}]+?)\s*\}\}/g, (_, key) => args[key] || '');
  return { messages: [{ role: 'user', content: { type: 'text', text } }] };
});
server.connect(new StdioServerTransport()).catch(error => { console.error(error); process.exitCode = 1; });
