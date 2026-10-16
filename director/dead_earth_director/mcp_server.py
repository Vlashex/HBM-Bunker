"""MCP exposes a fixed catalog; there is no command or file execution tool."""
import asyncio
from typing import Any
from mcp.server import MCPServer
from mcp.types import ToolAnnotations
from .bridge import BridgeClient


def create_server(client: BridgeClient) -> MCPServer:
    server = MCPServer("Dead Earth")
    read = ToolAnnotations(read_only_hint=True, destructive_hint=False, idempotent_hint=True, open_world_hint=False)
    write = ToolAnnotations(read_only_hint=False, destructive_hint=False, idempotent_hint=True, open_world_hint=False)

    @server.tool(annotations=read)
    async def get_snapshot() -> dict[str, Any]:
        """Наблюдаемые факты кампании и возраст кеша; полного сканирования мира нет."""
        return await asyncio.to_thread(client.call, "get_snapshot", {})

    @server.tool(annotations=read)
    async def get_history(limit: int = 20) -> dict[str, Any]:
        """Последние 1–100 наблюдений; старые сведения не являются текущим состоянием."""
        return await asyncio.to_thread(client.call, "get_history", {"limit": limit})

    @server.tool(annotations=read)
    async def list_catalog() -> dict[str, Any]:
        """Каталог реализованных сценариев. Сейчас доступен только безопасный сигнал в чат."""
        return {"entries": await asyncio.to_thread(client.call, "list_catalog", {})}

    @server.tool(annotations=read)
    async def preview_scenario(request_id: str) -> dict[str, Any]:
        """Проверить сигнал без исполнения. request_id — канонический UUID запроса."""
        return await asyncio.to_thread(client.call, "preview_scenario", {"request_id": request_id})

    @server.tool(annotations=write)
    async def propose_scenario(request_id: str) -> dict[str, Any]:
        """Предложить безопасный сигнал. Повторяй тот же UUID при неизвестном исходе RPC."""
        return await asyncio.to_thread(client.call, "propose_scenario", {"request_id": request_id})

    @server.tool(annotations=read)
    async def get_scenario_status(scenario_id: str) -> dict[str, Any]:
        """Авторитетная серверная квитанция сценария по UUID."""
        return await asyncio.to_thread(client.call, "get_scenario_status", {"scenario_id": scenario_id})

    @server.tool(annotations=write)
    async def cancel_scenario(scenario_id: str, revision: int) -> dict[str, Any]:
        """Отменить будущий сигнал с ожидаемой ревизией; исполненный эффект не откатывается."""
        return await asyncio.to_thread(client.call, "cancel_scenario", {"scenario_id": scenario_id, "revision": revision})

    return server
