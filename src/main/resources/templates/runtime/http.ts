import { Ajv } from 'ajv';
const validator = new Ajv({ strict: false, allErrors: true });
export function validateInput(schema: any, args: unknown) {
  if (!schema) return null;
  const valid = validator.compile(schema);
  return valid(args) ? null : { isError: true, content: [{ type: 'text' as const, text: 'Invalid input: ' + validator.errorsText(valid.errors) }] };
}
export async function callHttp(binding: any, args: Record<string, any>) {
  if (!binding) return { isError: true, content: [{ type: 'text' as const, text: 'Tool has no HTTP binding. Configure its implementation before use.' }] };
  try {
    const base = process.env.UPSTREAM_BASE_URL || binding.baseUrl;
    if (!base) throw new Error('Set UPSTREAM_BASE_URL');
    let path = binding.path;
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (process.env.UPSTREAM_AUTHORIZATION) headers.Authorization = process.env.UPSTREAM_AUTHORIZATION;
    for (const p of binding.parameters || []) {
      if (p.in === 'path') {
        if (args[p.argument] == null) throw new Error(`Missing path argument: ${p.argument}`);
        path = path.replaceAll(`{${p.name}}`, encodeURIComponent(String(args[p.argument])));
      }
      if (p.in === 'header' && args[p.argument] != null) headers[p.name] = String(args[p.argument]);
    }
    const url = new URL(base.replace(/\/$/, '') + '/' + path.replace(/^\//, ''));
    for (const p of binding.parameters || []) {
      if (p.in === 'query' && args[p.argument] != null) {
        const values = Array.isArray(args[p.argument]) && p.explode !== false ? args[p.argument] : [args[p.argument]];
        for (const value of values) url.searchParams.append(p.name, String(value));
      }
    }
    const body = binding.bodyArgument ? args[binding.bodyArgument] : undefined;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(url, { method: binding.method, headers,
      body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30000) });
    const text = await response.text();
    return { isError: !response.ok, content: [{ type: 'text' as const, text }] };
  } catch (error) {
    return { isError: true, content: [{ type: 'text' as const, text: String(error) }] };
  }
}
