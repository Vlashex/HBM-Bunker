# Проверка постановки событий — 2026-10-17

База `f0b0aac`, ветка `feat/admin-event-authoring`, координатор4.1.0,
Minecraft1.21.1/NeoForge21.1.262/Java21. Отчёт описывает новый срез,
а не переиспользует результаты v5.

| Проверка | Результат |
|---|---|
| `gradlew.bat test build --offline --console=plain` |180 unit, 0 ошибок; новый JAR собран |
| `runGameTestServer -PdeadEarthGameTestDirectory=run-gametest-authoring-1` |90/90,32.36с игрового прогона; свежий мир, полный серверный набор |
| Python `director/.venv/Scripts/python.exe -m unittest discover -s tests -v` |59/59 |
| `node director/tests/admin_ui_test.cjs` |Обзор/история/каталог/консоль без регрессий |
| `node director/tests/authoring_ui_test.cjs` |Конструктор, копия без изменения оригинала, сохранение правок при polling, редактирование триггера и роли, save/validate/publish gate, nonce, uncertain без повтора |
| Корневые Python-тесты упаковщика |16/16 |
| `python tools/release.py check` |35внешних модов,120файлов патча, версия5.1.0 |
| Реальный HTTP → RCON |Черновик/validate/apply, новые revision/sourceHash, точная backup-копия, повтор без второго apply, запрет запуска без игроков/выключенного режима, отмена отсутствующей операции |

Сквозной тест: `director/tests/authoring_smoke.py --server-root
mods/dead-earth/run-server-authoring-1` из корня (исполняется Python из director
venv). Скрипт отказывается работать вне отдельной папки `run-server-authoring-*`
и портов25566/25576. Он создаёт только звуковой сценарий без волны, сохраняет
тестовый каталог и штатно останавливает этот тестовый сервер после проверки.
Рабочий сервер25565 и его мир в испытаниях не участвовали.

RED зафиксирован до реализации: отсутствующий native operator API,
отсутствующий Python-модуль и отсутствующий JS редактора. Отдельный regression
для `configured` состояния RCON также прошёл RED→GREEN. В Java проверены
выбор вне shortlist, новый расчёт бюджета, replay, recovery/лимиты/неполные зоны,
ошибки staged графа/регистра, stale epoch/revision/source, atomic backup и
сохранение frozen-графа работающей операции при публикации.

Не выполнялись Computer Use, снимки экрана и визуальный осмотр браузера.
Измеренный прирост FPS/MSPT не заявляется. Игровой клиент не перезапускался:
новая функция меняет серверный допуск и локальный веб-интерфейс.
