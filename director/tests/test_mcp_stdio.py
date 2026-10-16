import asyncio
from pathlib import Path
import sys
import unittest
from mcp import Client
from mcp.client.stdio import StdioServerParameters
from test_bridge import endpoint, success


class McpStdioTests(unittest.TestCase):
    def test_real_sdk_negotiates_stdio_and_only_bounded_tools_are_exposed(self):
        async def exchange(path):
            params = StdioServerParameters(command=sys.executable,
                args=["-m", "dead_earth_director", "--bridge", str(path)],
                cwd=Path(__file__).resolve().parents[1], env={"PYTHONUTF8": "1"})
            async with Client(params, read_timeout_seconds=10) as client:
                tools = (await client.list_tools()).tools
                self.assertEqual({tool.name for tool in tools}, {
                    "get_snapshot", "get_history", "list_catalog", "preview_scenario",
                    "propose_scenario", "get_scenario_status", "cancel_scenario"})
                result = await client.call_tool("get_snapshot")
                self.assertEqual(result.structured_content, {"activeTicks": 42})
                denied = await client.call_tool("execute_command", {"command": "stop"})
                self.assertTrue(denied.is_error)
        with endpoint(lambda r: success(dict(activeTicks=42))) as (path, requests):
            asyncio.run(exchange(path))
            self.assertEqual([r["operation"] for r in requests], ["get_snapshot"])


if __name__ == "__main__":
    unittest.main()
