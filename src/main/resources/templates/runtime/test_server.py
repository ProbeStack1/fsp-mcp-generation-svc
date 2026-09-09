import asyncio
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from server import call_tool, get_prompt, list_tools, parameter_text


class ProtocolUnitTests(unittest.TestCase):
    def test_parameter_serialization(self):
        self.assertEqual(parameter_text(True), 'true')
        self.assertEqual(parameter_text([1, 2]), '1,2')

    def test_unknown_tool_is_error(self):
        result = asyncio.run(call_tool('__unknown_tool__', {}))
        self.assertTrue(result.isError)
        self.assertEqual(result.content[0].type, 'text')

    def test_tool_catalog_preserves_schema(self):
        spec = json.loads(Path(__file__).resolve().parents[1].joinpath('server-spec.json').read_text())
        actual = asyncio.run(list_tools())
        expected = (spec.get('capabilities') or {}).get('tools', [])
        self.assertEqual([t.name for t in actual], [t['name'] for t in expected])
        for tool, original in zip(actual, expected):
            self.assertEqual(tool.inputSchema, original.get('inputSchema') or {'type': 'object'})

    def test_unknown_prompt_is_rejected(self):
        with self.assertRaises(ValueError):
            asyncio.run(get_prompt('__unknown_prompt__', {}))
