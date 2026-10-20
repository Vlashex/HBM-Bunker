"""MCP exposes a fixed catalog; there is no command or file execution tool."""
import asyncio
from typing import Any
from mcp.server import MCPServer
from mcp.types import ToolAnnotations
from mcp.server.mcpserver.exceptions import ToolError
from .bridge import BridgeClient, BridgeError, _arguments


SRP_OPERATIONS=frozenset({'get_snapshot','get_cinematic_brief','get_cinematic_queue','get_history',
                          'list_catalog','choose_cinematic','get_event_analytics','cancel_scenario'})


class BoundedMCPServer(MCPServer):
    allowed_operations=None

    async def call_tool(self, name, arguments, context=None):
        if self.allowed_operations is not None and name not in self.allowed_operations:
            raise ToolError("unknown_operation")
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
        if self.allowed_operations is not None:
            tools=[tool for tool in tools if tool.name in self.allowed_operations]
        for tool in tools:
            tool.input_schema["additionalProperties"] = False
        return tools


def create_server(client: BridgeClient, profile: str = "modern") -> MCPServer:
    if profile not in ("modern","srp"):raise ValueError("invalid_profile")
    server = BoundedMCPServer("Dead Earth")
    server.allowed_operations=SRP_OPERATIONS if profile=="srp" else None
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
    async def get_cinematic_brief() -> dict[str, Any]:
        """Допустимые события и зоны, уже рассчитанные сервером; без произвольных координат."""
        return await invoke("get_cinematic_brief", {})

    @server.tool(annotations=read)
    async def get_cinematic_queue() -> dict[str, Any]:
        """Очередь интерактивных событий, их этапы и конечные исходы."""
        return await invoke("get_cinematic_queue", {})

    @server.tool(annotations=read)
    async def get_event_analytics(operation_id: str) -> dict[str, Any]:
        """Сохранённые факты одной операции по UUID; без игроков, координат или изменений мира."""
        return await invoke("get_event_analytics", dict(operation_id=operation_id))

    @server.tool(annotations=write)
    async def choose_cinematic(request_id: str, goal_id: str, event_id: str, zone_id: str) -> dict[str, Any]:
        """Выбрать только пару из действующей цели. Сервер повторно проверяет score, лимиты и сроки."""
        return await invoke("choose_cinematic",dict(request_id=request_id,goal_id=goal_id,event_id=event_id,zone_id=zone_id))

    @server.tool(annotations=read)
    async def get_director_brief() -> dict[str, Any]:
        """Серверная цель D1 и допустимые варианты. Без атак и загрузки мира."""
        return await invoke("get_director_brief", {})

    @server.tool(annotations=read)
    async def preview_director(request_id: str, goal_id: str, variant: str, text: str) -> dict[str, Any]:
        """Проверить оформление действующей цели D1; эффекты в мире отсутствуют."""
        return await invoke("preview_director", dict(request_id=request_id, goal_id=goal_id, variant=variant, text=text))

    @server.tool(annotations=read)
    async def get_history(limit: int = 20) -> dict[str, Any] | list[Any]:
        """Последние 1–100 наблюдений; старые сведения не являются текущим состоянием."""
        return await invoke("get_history", {"limit": limit})

    @server.tool(annotations=read)
    async def list_catalog() -> dict[str, Any]:
        """Каталог реализованных сервером сценариев и интерактивных событий."""
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
        """Отменить ожидающий сценарий с заданной ревизией; исполненный эффект не откатывается."""
        return await invoke("cancel_scenario", {"scenario_id": scenario_id, "revision": revision})

    return server
