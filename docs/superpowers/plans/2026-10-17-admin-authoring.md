# Админка: постановка событий и авторские сценарии

Запрос: вручную запускать события и собирать собственные сценарии в существующей
локальной админке. Продолжаем автономную разработку в отдельной ветке; Computer
Use остаётся выключенным. Существующие миры и сервер без запроса не перезапускаем.

## Контракт

- Вкладка «Постановка»: выбор события/зоны, preview, явный запуск, отмена операций.
- Конструктор графа: общие настройки события, узлы, типизированные actions,
  ветви по триггерам/таймеру, terminal finish. Клонирование библиотечного события,
  сохранение черновика, проверка и публикация. Новые ID начинаются с `custom_`.
- Черновики — отдельная SQLite-библиотека, с optimistic revision; системные
  события не редактируются. Для публикации draft добавляется/заменяется в полном
  текущем каталоге, сохраняя rules/sounds/traces/waves и остальные события.
- Человек использует отдельные operator-команды через RCON. MCP/модель новых
  полномочий не получают. GET/status не создают цели и не вызывают brief().
- Native operator protocol1: `deadearth operator status`; остальные команды
  привязаны к expected worldEpoch. preview/run также к catalogRevision.
  run проверяет все текущие условия/лимиты и вычисляет бюджет на сервере,
  выбирая точное событие/зону вне случайного shortlist. Без force/bypass.
- stage path фиксирован: config/dead-earth/director/staging/<canonical UUID>.json.
  validate проверяет полный каталог и registry references; apply дополнительно
  проверяет expected catalogRevision и исходный файл, создаёт backup, атомарно
  заменяет каталог и обновляет память. Активные операции сохраняют frozen graph.
- HTTP GET /api/authoring возвращает nonce, state, server status, catalog, drafts.
  POST /api/authoring/{save,validate,publish,preview,run,cancel}:
  строгий Origin и X-Dead-Earth-Operator nonce, ограниченный JSON, exact fields.
  request_id обязателен. run/publish/cancel используют durable OperatorConsole
  receipts, без автоматических повторов uncertain.
- save: {request_id,event,draft_revision}; validate/publish:
  {request_id,draft_id,draft_revision,world_epoch,catalog_revision}; preview/run:
  {request_id,event_id,zone_id,world_epoch,catalog_revision}; cancel:
  {request_id,operation_id,world_epoch}. Body до256KiB; event до128KiB/24узлов.
- Native commands: status; preview <epoch> <revision> <event> <zone>;
  run <epoch> <revision> <request> <event> <zone>; cancel <epoch> <operation>;
  validate <epoch> <stage>; apply <epoch> <revision> <stage>.
- status: {protocol:1,campaignId,worldEpoch,catalogRevision,sourceHash,enabled,
  activeTicks,zones:[CinematicEngine.Zone]}. sourceHash — SHA256 точного UTF8
  источника, из которого загружен каталог. Проверки возвращают структурированный
  JSON {accepted,code,...}; успешный validate/apply также revision/sourceHash.

## Порядок

1. Native manual admission/catalog staging и tests (параллельная независимая задача).
2. Python draft storage/operator service/HTTP guards и tests (независимая задача).
3. UI конструктор и DOM tests; интеграция контрактов.
4. Совместный Python/Node/Java/GameTest прогон в отдельном мире. Таймеры ускоряются
   тестовым контекстом; реальные события проверяются без воздействия на live мир.
5. Коммиты и документация; безопасно обновить только админку после проверки.
   Если для работы требуется новый игровой JAR/перезапуск — явно сообщить статус.
