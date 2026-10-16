"""MCP exposes a fixed catalog; there is no command or file execution tool."""
import asyncio
from typing import Any
from mcp.server import MCPServer
from mcp.types import ToolAnnotations
from mcp.server.mcpserver.exceptions import ToolError
from .bridge import BridgeClient, BridgeError, _arguments


class BoundedMCPServer(MCPServer):
    async def call_tool(self, name, arguments, context=None):
        # Validate the raw dict before SDK/Pydantic can discard or coerce fields.
        raw = {} if arguments is None else arguments
        if type(raw) is not dict:
            raise ToolError("invalid_arguments")
        checked = {"limit": 20} | raw if name == "get_history" else raw
        try:
            _arguments(name, checked)
        except BridgeError as error:
            raise ToolError(str(error)) from None
        return await super().call_tool(name, raw, context)

    async def list_tools(self):
        tools = await super().list_tools()
        for tool in tools:
            tool.input_schema["additionalProperties"] = False
        return tools


def create_server(client: BridgeClient) -> MCPServer:
    server = BoundedMCPServer("Dead Earth")
    read = ToolAnnotations(read_only_hint=True, destructive_hint=False, idempotent_hint=True, open_world_hint=False)
    write = ToolAnnotations(read_only_hint=False, destructive_hint=False, idempotent_hint=True, open_world_hint=False)

    async def invoke(operation, arguments):
        try:
            return await asyncio.to_thread(client.call, operation, arguments)
        except BridgeError as error:
            raise ToolError(str(error)) from None

    @server.tool(annotations=read)
    async def get_snapshot() -> dict[str, Any]:
        """Наблюдаемые факты кампании и возраст кеша; полного сканирования мира нет."""
        return await invoke("get_snapshot", {})

    @server.tool(annotations=read)
    async def get_history(limit: int = 20) -> dict[str, Any]:
        """Последние 1–100 наблюдений; старые сведения не являются текущим состоянием."""
        return await invoke("get_history", {"limit": limit})

    @server.tool(annotations=read)
    async def list_catalog() -> dict[str, Any]:
        """Каталог реализованных сценариев. Сигнал и три варианта радиосообщения известной станции."""
        return {"entries": await invoke("list_catalog", {})}

    @server.tool(annotations=read)
    async def preview_scenario(request_id: str, catalog_id: str = "warning_beacon_v1",
                               target_id: str = "test_anchor", variant: str = "standard", delay_ticks: int = 200) -> dict[str, Any]:
        """Проверить выбор из каталога без эффекта; target_id берётся из обнаруженных объектов."""
        return await invoke("preview_scenario", {"request_id": request_id, "catalog_id": catalog_id,
                            "target_id": target_id, "variant": variant, "delay_ticks": delay_ticks})

    @server.tool(annotations=write)
    async def propose_scenario(request_id: str, catalog_id: str = "warning_beacon_v1",
                               target_id: str = "test_anchor", variant: str = "standard", delay_ticks: int = 200) -> dict[str, Any]:
        """Предложить выбор из каталога. При неизвестном исходе повторяй UUID и все параметры."""
        return await invoke("propose_scenario", {"request_id": request_id, "catalog_id": catalog_id,
                            "target_id": target_id, "variant": variant, "delay_ticks": delay_ticks})

    @server.tool(annotations=read)
    async def get_scenario_status(scenario_id: str) -> dict[str, Any]:
        """Авторитетная серверная квитанция сценария по UUID."""
        return await invoke("get_scenario_status", {"scenario_id": scenario_id})

    @server.tool(annotations=write)
    async def cancel_scenario(scenario_id: str, revision: int) -> dict[str, Any]:
        """Отменить будущий сигнал с ожидаемой ревизией; исполненный эффект не откатывается."""
        return await invoke("cancel_scenario", {"scenario_id": scenario_id, "revision": revision})

    return server
