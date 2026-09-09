"""Generated MCP server with schema validation and HTTP tool bindings."""
import asyncio
import contextlib
import json
import logging
import os
import re
import time
from pathlib import Path
from urllib.parse import quote

import httpx
import jsonschema
from dotenv import load_dotenv
from mcp.server import Server
from mcp.server.lowlevel.helper_types import ReadResourceContents
from mcp.server.stdio import stdio_server
from mcp.server.streamable_http_manager import StreamableHTTPSessionManager
from mcp.types import (
    CallToolResult,
    GetPromptResult,
    Prompt,
    PromptArgument,
    PromptMessage,
    Resource,
    ResourceTemplate,
    TextContent,
    Tool,
)

load_dotenv(Path(__file__).with_name('.env'))
spec = json.loads(Path(__file__).with_name('server-spec.json').read_text(encoding='utf-8'))
caps = spec.get('capabilities') or {}
mcp = Server((spec.get('identity') or {}).get('displayName') or 'mcp-server', version=spec.get('version', '0.1.0'))


@mcp.list_tools()
async def list_tools():
    return [Tool(name=t['name'], description=t.get('description') or '', inputSchema=t.get('inputSchema') or {'type': 'object'}) for t in caps.get('tools', [])]


@mcp.call_tool()
async def call_tool(name, arguments):
    try:
        tool = next(t for t in caps.get('tools', []) if t['name'] == name)
        jsonschema.validate(arguments, tool.get('inputSchema') or {'type': 'object'})
        binding = tool.get('http')
        if not binding:
            raise ValueError('Tool has no HTTP binding. Configure its implementation before use.')
        base = os.getenv('UPSTREAM_BASE_URL') or binding.get('baseUrl')
        if not base:
            raise ValueError('Set UPSTREAM_BASE_URL')
        path = binding['path']
        headers = {'Accept': 'application/json'}
        if os.getenv('UPSTREAM_AUTHORIZATION'):
            headers['Authorization'] = os.environ['UPSTREAM_AUTHORIZATION']
        query = []
        for p in binding.get('parameters', []):
            value = arguments.get(p['argument'])
            if p['in'] == 'path':
                if value is None:
                    raise ValueError('Missing path argument: ' + p['argument'])
                path = path.replace('{' + p['name'] + '}', quote(parameter_text(value), safe=''))
            elif value is not None and p['in'] == 'query':
                query.extend((p['name'], parameter_text(v)) for v in (value if isinstance(value, list) and p.get('explode', True) else [value]))
            elif value is not None and p['in'] == 'header':
                headers[p['name']] = parameter_text(value)
        kwargs = {}
        if binding.get('bodyArgument') in arguments:
            kwargs['json'] = arguments[binding['bodyArgument']]
        async with httpx.AsyncClient(timeout=30) as client:
            response = await client.request(binding['method'], base.rstrip('/') + '/' + path.lstrip('/'), headers=headers, params=query, **kwargs)
        return CallToolResult(isError=not response.is_success, content=[TextContent(type='text', text=response.text)])
    except (ValueError, TypeError, StopIteration, jsonschema.ValidationError, httpx.HTTPError) as error:
        return CallToolResult(isError=True, content=[TextContent(type='text', text=str(error))])


@mcp.list_resources()
async def list_resources():
    return [Resource(uri=r['uriTemplate'], name=r['name'], description=r.get('description'), mimeType=r.get('mimeType')) for r in caps.get('resources', []) if '{' not in r['uriTemplate']]


@mcp.list_resource_templates()
async def list_templates():
    return [ResourceTemplate(uriTemplate=r['uriTemplate'], name=r['name'], description=r.get('description'), mimeType=r.get('mimeType')) for r in caps.get('resources', []) if '{' in r['uriTemplate']]


@mcp.read_resource()
async def read_resource(uri):
    for r in caps.get('resources', []):
        pattern = re.sub(r'\\\{[^}]+\\\}', r'([^/]+)', re.escape(r['uriTemplate']))
        match = re.fullmatch(pattern, str(uri))
        if match:
            content = r.get('content')
            if content is None:
                raise ValueError('Resource content is not configured')
            for key, value in zip(re.findall(r'\{([^}]+)\}', r['uriTemplate']), match.groups()):
                content = content.replace('{{' + key + '}}', value)
            return [ReadResourceContents(content=content, mime_type=r.get('mimeType') or 'text/plain')]
    raise ValueError('Unknown resource: ' + str(uri))


@mcp.list_prompts()
async def list_prompts():
    return [Prompt(name=p['name'], description=p.get('description'), arguments=[PromptArgument(**a) for a in p.get('arguments', [])]) for p in caps.get('prompts', [])]


@mcp.get_prompt()
async def get_prompt(name, arguments):
    p = next((p for p in caps.get('prompts', []) if p['name'] == name), None)
    if p is None:
        raise ValueError('Unknown prompt: ' + name)
    arguments = arguments or {}
    for arg in p.get('arguments', []):
        if arg.get('required') and arg['name'] not in arguments:
            raise ValueError('Missing prompt argument: ' + arg['name'])
    text = re.sub(r'\{\{\s*([^{}]+?)\s*\}\}', lambda m: str(arguments.get(m[1], '')), p.get('template') or '')
    return GetPromptResult(messages=[PromptMessage(role='user', content=TextContent(type='text', text=text))])


def parameter_text(value):
    if isinstance(value, list):
        return ','.join(parameter_text(item) for item in value)
    return json.dumps(value) if isinstance(value, bool) else str(value)


async def run_stdio():
    async with stdio_server() as (read, write):
        await mcp.run(read, write, mcp.create_initialization_options())


def create_app():
    from starlette.applications import Starlette
    from starlette.middleware.cors import CORSMiddleware
    from starlette.responses import JSONResponse, PlainTextResponse
    from starlette.routing import Route
    manager = StreamableHTTPSessionManager(app=mcp, json_response=True)
    advanced = spec.get('advanced') or {}
    health_options = advanced.get('healthCheck') or {}
    metrics_options = advanced.get('metrics') or {}
    cors_options = advanced.get('cors') or {}
    rate_options = advanced.get('rateLimit') or {}
    counters = {'requests': 0}
    buckets = {}

    @contextlib.asynccontextmanager
    async def lifespan(app):
        async with manager.run():
            yield

    async def health(request):
        return JSONResponse({'status': 'ok'})

    async def metrics(request):
        return PlainTextResponse('# TYPE mcp_requests_total counter\nmcp_requests_total ' + str(counters['requests']) + '\n')

    async def endpoint(scope, receive, send):
        from starlette.requests import Request
        request = Request(scope)
        auth = spec.get('auth') or {}
        if auth.get('kind') == 'bearer':
            token = os.getenv('MCP_AUTH_TOKEN')
            if not token or request.headers.get('authorization') != 'Bearer ' + token:
                await JSONResponse({'error': 'Unauthorized'}, status_code=401)(scope, receive, send)
                return
        if auth.get('kind') == 'api-key':
            token = os.getenv('MCP_API_KEY')
            if not token or request.headers.get(auth.get('headerName') or 'X-API-Key') != token:
                await JSONResponse({'error': 'Unauthorized'}, status_code=401)(scope, receive, send)
                return
        await manager.handle_request(scope, receive, send)

    class McpEndpoint:
        async def __call__(self, scope, receive, send):
            await endpoint(scope, receive, send)

    routes = [Route('/mcp', McpEndpoint(), methods=['GET', 'POST', 'DELETE'])]
    if health_options.get('enabled', True):
        routes.extend([Route(health_options.get('path') or '/healthz', health), Route('/readyz', health)])
    if metrics_options.get('enabled', False):
        routes.append(Route(metrics_options.get('path') or '/metrics', metrics))
    app = Starlette(lifespan=lifespan, routes=routes)

    class HttpOptions:
        async def __call__(self, scope, receive, send):
            if scope['type'] != 'http':
                await app(scope, receive, send)
                return
            start = time.monotonic()
            counters['requests'] += 1
            if scope['path'] == '/mcp' and rate_options.get('enabled', False):
                for key in list(buckets):
                    if buckets[key][1] <= start:
                        del buckets[key]
                client = (scope.get('client') or ('unknown', 0))[0]
                bucket = buckets.setdefault(client, [0, start + 60])
                bucket[0] += 1
                if bucket[0] > rate_options.get('requestsPerMinute', 60):
                    await JSONResponse({'error': 'Rate limit exceeded'}, status_code=429)(scope, receive, send)
                    return

            async def capture(message):
                if message['type'] == 'http.response.start' and (advanced.get('logging') or {}).get('enabled', True):
                    logging.getLogger('uvicorn.error').info('%s %s status=%s durationMs=%.2f', scope['method'], scope['path'], message['status'], (time.monotonic() - start) * 1000)
                await send(message)
            await app(scope, receive, capture)

    wrapped = HttpOptions()
    if cors_options.get('enabled', True):
        return CORSMiddleware(wrapped, allow_origins=[s.strip() for s in (cors_options.get('allowedOrigins') or '*').split(',')], allow_methods=['GET', 'POST', 'DELETE'], allow_headers=['*'], expose_headers=['Mcp-Session-Id', 'MCP-Protocol-Version'])
    return wrapped


if __name__ == '__main__':
    if (spec.get('transport') or {}).get('kind') == 'stdio':
        asyncio.run(run_stdio())
    else:
        import uvicorn
        uvicorn.run(create_app(), host='0.0.0.0', port=int(os.getenv('PORT', '8080')))
