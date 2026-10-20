import asyncio
from pathlib import Path
import sys
import unittest
from mcp import Client
from mcp.client.stdio import StdioServerParameters
from test_bridge import endpoint, success, CAMPAIGN


class McpStdioTests(unittest.TestCase):
    def test_sdk_rejects_raw_unknown_fields_and_coercible_delays(self):
        choice=dict(request_id="00000000-0000-4000-8000-000000000003",catalog_id="relay_broadcast_v1",target_id="relay:00000000-0000-4000-8000-000000000004",variant="rally",delay_ticks=400)
        async def exchange(path):
            params=StdioServerParameters(command=sys.executable,args=["-m","dead_earth_director","--bridge",str(path)],cwd=Path(__file__).resolve().parents[1],env={"PYTHONUTF8":"1"})
            async with Client(params,read_timeout_seconds=10) as client:
                for name in ["preview_scenario","propose_scenario"]:
                    for changes in [dict(unexpected="text"),dict(delay_tick=1200),dict(delay_ticks="400"),dict(delay_ticks=400.0),dict(delay_ticks=True)]:
                        result=await client.call_tool(name,choice | changes)
                        self.assertTrue(result.is_error,(name,changes))
                self.assertFalse((await client.call_tool("get_history")).is_error)
        with endpoint(lambda r:success({})) as (path,requests):
            asyncio.run(exchange(path))
            self.assertEqual([r["operation"] for r in requests],["get_history"])
            self.assertEqual(requests[0]["arguments"],{"limit":20})

    def test_sdk_forwards_bounded_radio_choice(self):
        choice=dict(request_id="00000000-0000-4000-8000-000000000003",catalog_id="relay_broadcast_v1",target_id="relay:00000000-0000-4000-8000-000000000004",variant="rally",delay_ticks=400)
        async def exchange(path):
            params=StdioServerParameters(command=sys.executable,args=["-m","dead_earth_director","--bridge",str(path)],cwd=Path(__file__).resolve().parents[1],env={"PYTHONUTF8":"1"})
            async with Client(params,read_timeout_seconds=10) as client:
                result=await client.call_tool("propose_scenario",choice)
                self.assertFalse(result.is_error)
                self.assertEqual(result.structured_content,{"state":"SCHEDULED"})
        with endpoint(lambda r:success({"state":"SCHEDULED"})) as (path,requests):
            asyncio.run(exchange(path))
            self.assertEqual(requests[0]["arguments"],choice)

    def test_sdk_forwards_director_preview_and_rejects_extra_authority(self):
        args=dict(request_id="00000000-0000-4000-8000-000000000003",
                  goal_id="00000000-0000-4000-8000-000000000004",variant="radio_static",text="Черновик.")
        async def exchange(path):
            params=StdioServerParameters(command=sys.executable,args=["-m","dead_earth_director","--bridge",str(path)],
                cwd=Path(__file__).resolve().parents[1],env={"PYTHONUTF8":"1"})
            async with Client(params,read_timeout_seconds=10) as client:
                self.assertFalse((await client.call_tool("get_director_brief")).is_error)
                self.assertFalse((await client.call_tool("preview_director",args)).is_error)
                self.assertTrue((await client.call_tool("preview_director",args | dict(count=20))).is_error)
        with endpoint(lambda r:success({})) as (path,requests):
            asyncio.run(exchange(path))
            self.assertEqual([r["operation"] for r in requests],["get_director_brief","preview_director"])


    def test_sdk_cinematic_selection_forwards_only_the_allowed_pair(self):
        args=dict(request_id="00000000-0000-4000-8000-000000000003",goal_id="00000000-0000-4000-8000-000000000004",
                  event_id="spore_lure",zone_id="00000000-0000-4000-8000-000000000005")
        async def exchange(path):
            params=StdioServerParameters(command=sys.executable,args=["-m","dead_earth_director","--bridge",str(path)],
                cwd=Path(__file__).resolve().parents[1],env={"PYTHONUTF8":"1"})
            async with Client(params,read_timeout_seconds=10) as client:
                self.assertFalse((await client.call_tool("get_cinematic_brief")).is_error)
                self.assertFalse((await client.call_tool("get_cinematic_queue")).is_error)
                self.assertFalse((await client.call_tool("choose_cinematic",args)).is_error)
                for change in [dict(count=10),dict(x=0),dict(command="summon"),dict(event_id="/op player"),dict(zone_id=True)]:
                    self.assertTrue((await client.call_tool("choose_cinematic",args | change)).is_error)
        with endpoint(lambda r:success({})) as (path,requests):
            asyncio.run(exchange(path))
            self.assertEqual([r["operation"] for r in requests],["get_cinematic_brief","get_cinematic_queue","choose_cinematic"])
            self.assertEqual(requests[-1]["arguments"],args)

    def test_expected_bridge_failure_reaches_model_as_safe_reason(self):
        async def exchange(path):
            params = StdioServerParameters(command=sys.executable,
                args=["-m", "dead_earth_director", "--bridge", str(path)],
                cwd=Path(__file__).resolve().parents[1], env={"PYTHONUTF8": "1"})
            async with Client(params, read_timeout_seconds=10) as client:
                result = await client.call_tool("get_snapshot")
                self.assertTrue(result.is_error)
                text = "\n".join(getattr(item, "text", "") for item in result.content)
                self.assertIn("unauthorized", text)
                self.assertNotIn("a" * 64, text)
        with endpoint(lambda r: dict(ok=False, error=dict(code="unauthorized"))) as (path, requests):
            asyncio.run(exchange(path))

    def test_real_sdk_negotiates_stdio_and_only_bounded_tools_are_exposed(self):
        async def exchange(path):
            params = StdioServerParameters(command=sys.executable,
                args=["-m", "dead_earth_director", "--bridge", str(path)],
                cwd=Path(__file__).resolve().parents[1], env={"PYTHONUTF8": "1"})
            async with Client(params, read_timeout_seconds=10) as client:
                tools = (await client.list_tools()).tools
                self.assertEqual({tool.name for tool in tools}, {
                    "get_snapshot", "get_history", "list_catalog", "preview_scenario",
                    "propose_scenario", "get_scenario_status", "cancel_scenario", "get_director_brief", "preview_director", "get_cinematic_brief", "get_cinematic_queue", "get_event_analytics", "choose_cinematic"})
                analytics=next(t for t in tools if t.name=="get_event_analytics")
                self.assertTrue(analytics.annotations.read_only_hint)
                self.assertFalse(analytics.input_schema["additionalProperties"])
                for args in ({"operation_id":"bad"},{"operation_id":CAMPAIGN,"extra":1}):
                    self.assertTrue((await client.call_tool("get_event_analytics",args)).is_error)
                result = await client.call_tool("get_snapshot")
                self.assertEqual(result.structured_content, {"activeTicks": 42})
                denied = await client.call_tool("execute_command", {"command": "stop"})
                self.assertTrue(denied.is_error)
        with endpoint(lambda r: success(dict(activeTicks=42))) as (path, requests):
            asyncio.run(exchange(path))
            self.assertEqual([r["operation"] for r in requests], ["get_snapshot"])


    def test_srp_profile_lists_and_dispatches_only_confirmed_native_operations(self):
        supported={'get_snapshot','get_cinematic_brief','get_cinematic_queue','get_history',
                   'list_catalog','choose_cinematic','get_event_analytics','cancel_scenario'}
        choice=dict(request_id="00000000-0000-4000-8000-000000000003",
                    goal_id="00000000-0000-4000-8000-000000000004",event_id="native_scout",
                    zone_id="00000000-0000-4000-8000-000000000005")
        async def exchange(path):
            params=StdioServerParameters(command=sys.executable,
                args=["-m","dead_earth_director","--bridge",str(path),"--profile","srp"],
                cwd=Path(__file__).resolve().parents[1],env={"PYTHONUTF8":"1"})
            async with Client(params,read_timeout_seconds=10) as client:
                tools=(await client.list_tools()).tools
                self.assertEqual({tool.name for tool in tools},supported)
                for tool in tools:self.assertFalse(tool.input_schema["additionalProperties"])
                catalog=next(tool for tool in tools if tool.name=='list_catalog')
                self.assertNotIn('радиосообщения',catalog.description)
                for name,args in [('get_director_brief',{}),('preview_director',{}),
                                  ('preview_scenario',dict(request_id=CAMPAIGN)),
                                  ('propose_scenario',dict(request_id=CAMPAIGN)),
                                  ('get_scenario_status',dict(scenario_id=CAMPAIGN)),
                                  ('execute_command',dict(command='stop')),
                                  ('console',dict(command='stop'))]:
                    with self.subTest(operation=name):self.assertTrue((await client.call_tool(name,args)).is_error)
                for change in (dict(command='stop'),dict(count=10),dict(zone_id=True)):
                    self.assertTrue((await client.call_tool('choose_cinematic',choice|change)).is_error)
                for name,args in [('get_snapshot',{}),('get_cinematic_brief',{}),('get_cinematic_queue',{}),
                                  ('get_history',{}),('list_catalog',{}),('choose_cinematic',choice),
                                  ('get_event_analytics',dict(operation_id=CAMPAIGN)),
                                  ('cancel_scenario',dict(scenario_id=CAMPAIGN,revision=0))]:
                    with self.subTest(operation=name):self.assertFalse((await client.call_tool(name,args)).is_error)
        def respond(request):
            if request['operation']=='get_history':return success([dict(tick=1,eventId='native_scout')])
            if request['operation']=='list_catalog':return success([dict(eventId='native_scout',faction='siege')])
            return success(dict(status='ready'))
        with endpoint(respond) as (path,requests):
            asyncio.run(exchange(path))
            self.assertEqual({request['operation'] for request in requests},supported)
            self.assertEqual(len(requests),len(supported),'Rejected operations must not reach the bridge')
            history=next(request for request in requests if request['operation']=='get_history')
            self.assertEqual(history['arguments'],dict(limit=20))

    def test_create_server_rejects_unknown_profile(self):
        from dead_earth_director.mcp_server import create_server
        with self.assertRaises(ValueError):create_server(None,profile='unrestricted')


if __name__ == "__main__":
    unittest.main()
