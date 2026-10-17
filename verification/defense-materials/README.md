# Защитные материалы 5.3.0

Координатор 4.2.0, Minecraft 1.21.1 / NeoForge 21.1.262.
[Машинный отчёт](report.json), [рецепты и настройки](../../docs/DEFENSE-MATERIALS.md).

## Пройдено

- 187 unit /38 suites, без ошибок и пропусков.
- Все 111 required full-pack GameTest в новом `run-gametest-defense-full-1`:
  36.07 с игровых проверок; полный Gradle `test build runGameTestServer` —
  2 мин 28 с, exit0. В полном логе нет Coordinator pause/CME.
- Реальные native Putrid/Hook/Overgrown, копатель ImprovedMobs с ненулевым
  restoreDelay, Arbora tunnel, Siege crater, обычный взрыв и HBM VNT. Ядерный
  batched writer допускается; его принудительная маска не доказывает силу
  конкретной бомбы.
- Sparse HP после сохранения/чтения, общие HP двух половин двери, верхняя
  половина при разборке игроком даёт ровно один предмет; истощение HP удаляет
  обе половины без дропа. State changes не лечат дверь.
- Деструктивные player/HP/migration scopes ограничены миром и позицией;
  вложенный callback не получает права удалить соседний V3.
- Точная миграция steel с журналом, сохранением deadline/identity и смешанное
  восстановление старого бункера. Legacy-breaker только ставит очередь,
  не пишет журнал в AI-тике.
- Реальные рецепты и остатки всех Reinforcer, последние использования и
  сохранение компонентов. AE2 encoded/decoded pattern + assembler remainder
  подтверждают повторяемый lvl3 и изменение exact key изнашиваемого lvl1/lvl2.

## Обычный мир и клиент

Новый normal world `defense_normal_test`, seed7355319, server25568/RCON25578,
стартовый Outpost Zero включён. Основная установка не использовалась.
Клиент с полной сборкой подключился и штатно закрылся после probe:

- 9 item models /387 native block states, missing models/textures=0.
- Настоящий IrisRenderingPipeline, shaderPackInUse=true, fallback=false,
  shaderError=false, OpenGL error=0.
- Исходный SavedData-снимок бункера: `native_capture`, 6578 блоков, 18 boxes,
  3744 `dead_earth:defense_wall_v1`, ноль `outpostzero:steel_block`.
  Deadline216000 — три часа campaign ticks.
- Координатор enabled=true, сохранение мира и остановка через штатный RCON,
  сервер/клиент exit0. Screenshots и Computer Use не применялись.

Обычный JAR не содержит `dev/deadearth/loadtest/`. Канонический серверный TOML
сверен с реально загруженным конфигом; значения совпадают.
Игровые проверки уже включали отключение старого steel-рецепта. При финальной
упаковке изменены только версия дескриптора и имя этого lowcode-патча с 1.3.0
на 1.4.0; его игровые JSON после прогона не менялись. Новые метаданные отдельно
проверяет упаковщик.

## Диагностика и границы

Первый targeted18/21 и второй20/22 не выдаются за успешные прогоны. Исправлены
fixture API Mutationcraft Toggle, sparse-grid форма AE2 и наблюдение предметов
в framework-forced chunk: просто FULL chunk не делает entity sections видимыми.
Обнаруженный реальный SafeBunker CME исправлен отдельным коммитом `ab8ce05`;
регрессия проверяет двух существующих монстров и сохранение пассивной коровы.

RFTools7.0.12 по закреплённому коду вызывает `Recipe.getRemainingItems` и
оставляет остаток внутри при EXTC; машина не запускалась. Полный ME crafting
request/GUI не проверялся. Универсальный HP-урон всех пуль и неизвестных raw
writers не заявлен. Полная синхронная запись растущего migration journal —
отдельная неизмеренная разовая стоимость.

Большие логи/миры остаются в рабочей папке задачи:
`work/dead-earth/defense-verification` и `work/dead-earth/defense-normal-1`.
Нагрузочный [отчёт 5.2.0](../../docs/performance/2026-10-18-load-test.md)
не является замером производительности этих новых блоков.
