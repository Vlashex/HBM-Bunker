# Dead Earth MCP gateway

Python 3.12 / Windows amd64. Официальный SDK `mcp==2.3.0`; зависимости закреплены
с хешами в `requirements-windows-py312.lock`. Исходники gateway наши; SDK устанавливается
в отдельную `.venv` из PyPI. Python пользователя не изменяется.

Из этой папки один раз выполни в PowerShell:

```powershell
./setup.ps1 -PythonPath 'C:/Path/To/Python312/python.exe'
```

На сервере оператор включает `deadearth bridge start`. Команда сообщает путь к
`dead_earth_bridge.json` внутри выбранного сохранения. Файл содержит секрет,
его нельзя пересылать, добавлять в Git или вставлять в чат.

MCP host запускает процесс:

```text
command: <папка director>/.venv/Scripts/python.exe
args: -m dead_earth_director --bridge <мир>/dead_earth_bridge.json
cwd: <папка director>
```

Вывод stdout — только MCP. Это gateway, без языковой модели и без собственного
цикла стратегического планирования. В нём семь ограниченных инструментов;
сейчас они управляют только тестовым сигналом. Остановить мост можно через
`deadearth bridge stop`. После перезапуска моста или сервера перезапусти gateway:
он намеренно не принимает новый дескриптор молча.

Проверки: `.venv/Scripts/python.exe -m unittest discover -s tests -v`
из исходного репозитория; тесты и venv в пользовательский архив не входят.

Документация SDK: https://github.com/modelcontextprotocol/python-sdk
PyPI: https://pypi.org/project/mcp/2.3.0/
