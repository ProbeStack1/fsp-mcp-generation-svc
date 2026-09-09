import { afterEach, expect, it, vi } from 'vitest';
import { callHttp } from '../src/http.js';
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });
it('maps path, query and JSON request body to the upstream API', async () => {
  vi.stubEnv('UPSTREAM_BASE_URL', 'https://example.test/v1');
  const fetchMock = vi.fn().mockResolvedValue(new Response('{"saved":true}', { status: 200 }));
  vi.stubGlobal('fetch', fetchMock);
  const result = await callHttp({ method: 'POST', path: '/items/{id}', bodyArgument: 'body', parameters: [
    { name: 'id', in: 'path', argument: 'id' }, { name: 'q', in: 'query', argument: 'q' },
    { name: 'ids', in: 'query', argument: 'ids', explode: false }, { name: 'enabled', in: 'query', argument: 'enabled' },
  ] }, { id: 'a b', q: 'c&d', ids: [1, 2], enabled: true, body: { value: 4 } });
  expect(String(fetchMock.mock.calls[0][0])).toBe('https://example.test/v1/items/a%20b?q=c%26d&ids=1%2C2&enabled=true');
  expect(fetchMock.mock.calls[0][1].body).toBe('{"value":4}');
  expect(result.isError).toBe(false);
});
it('does not report upstream errors as successful tool results', async () => {
  vi.stubEnv('UPSTREAM_BASE_URL', 'https://example.test');
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('denied', { status: 403 })));
  const result = await callHttp({ method: 'GET', path: '/' }, {});
  expect(result.isError).toBe(true);
  expect(result.content[0].text).toBe('denied');
});
it('unconfigured tools return explicit errors without making a request', async () => {
  const fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock);
  expect((await callHttp(null, {})).isError).toBe(true);
  expect(fetchMock).not.toHaveBeenCalled();
});
