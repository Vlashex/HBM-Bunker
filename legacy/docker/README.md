# DEAD EARTH SRP: один Compose на Docker Desktop

Только Minecraft-клиент работает непосредственно в Windows. Forge 1.12.2 / Java 8,
Playit.gg, CPU-модели, режиссёр и админка запускаются одним проектом
**dead-earth-srp**, в контексте **desktop-linux**.

## Сеть

| Сервис | Назначение | Доступ с Windows |
|---|---|---|
| minecraft | Forge, моды и текущий мир | 127.0.0.1:25565 |
| director | Выбор событий, журнал решений | Не публикуется |
| admin | Сценарии, аналитика и консоль человека | http://127.0.0.1:8766 |
| ollama | CPU-бэкенд основной модели и критика | Не публикуется |
| playit | Существующий туннель Minecraft | Адрес из аккаунта Playit |

Сеть campaign — обычный Docker bridge с исходящим NAT. Host networking,
privileged-контейнеры, GPU и Docker socket не используются.

director, admin и playit используют **network_mode: service:minecraft**.
Игровой мост и RCON доступны через их общее 127.0.0.1. Их порты не публикуются.
Дескриптор моста сохраняет проверки UUID кампании, эпохи и токена.
Playit продолжает обращаться к 127.0.0.1:25565 без правки туннеля в аккаунте.

Playit 1.0 запускает штатный playitd: его локальный IPC-сокет находится в
/run/playit/playitd.sock. Для этого каталога выделен отдельный tmpfs 1 MiB
с правами 0700; корневая файловая система остаётся только для чтения.
Сокет не публикуется и не разделяется с Windows или другими сервисами. Это [штатный Linux IPC-путь Playit](https://github.com/playit-cloud/playit-agent/blob/master/packages/playit-ipc/src/paths.rs).

Minecraft внутри контейнера слушает все интерфейсы, но игровой порт и порт
админки опубликованы **только на loopback Windows**. RCON 25575 остаётся
внутри контейнерной сети. Консоль доступна только человеку через отдельные
Origin/nonce проверки; MCP и модельные инструменты её не получают.

Только явный DEAD_EARTH_CONTAINER_MODE=1 разрешает дополнительный URL
**http://ollama:11434** и bind админки 0.0.0.0. Другие service DNS запрещены.
Обычный запуск Python в Windows по-прежнему ограничен 127.0.0.1.

## Однократная подготовка

1. Штатно остановить прежний SRP-сервер и его режиссёра/админку.
   Соседнюю современную сборку и Docker в Ubuntu не останавливать.
2. Из корня репозитория выполнить:

~~~powershell
director/.venv/Scripts/python.exe tools/legacy_compose.py prepare --server-root "C:/projects/DEAD-EARTH-SRP/work/legacy-playtest-server-..." --data-dir "C:/projects/DEAD-EARTH-SRP/work/compose-server"
~~~

Команда создаёт новый каталог и local .env, копирует только активный мир,
конфиги, моды, Forge libraries и журналы ИИ. Игровые данные прежнего сервера
и другие сохранённые миры сохраняются. На время копирования удерживается
штатная блокировка Windows supervisor.

Запущенный источник, существующий каталог назначения, чужой pack marker и
ссылки за пределы сервера запрещены. Только в новой копии задаются внутренние
порты, service URL и новый закрытый пароль RCON. Правила игры и пины моделей
сохраняются. При неудаче частичная новая копия сохраняется для диагностики;
автоматического удаления нет.

3. Создать **legacy/docker/secrets.env** по secrets.env.example и сохранить
   существующий ключ Playit. Файл исключён из Git. Не помещать ключ в команды,
   отчёты и скриншоты. Старый dead-earth-playit остановить непосредственно
   перед включением нового Compose-туннеля.
4. Собрать образы:

~~~powershell
director/.venv/Scripts/python.exe tools/legacy_compose.py build
~~~

Сборка скачивает базовые образы и Python для Java-контейнера, но не Forge,
моды и веса моделей. Dockerfile-specific dockerignore передаёт только
Python-код и entrypoints; секреты, архивы и миры не входят в build context.

5. Один раз импортировать **существующие** закреплённые веса офлайн:

~~~powershell
director/.venv/Scripts/python.exe tools/legacy_compose.py import-models --source "C:/projects/DEAD-EARTH-SRP/work/ollama-export"
~~~

Экспорт должен содержать .ollama/models либо manifests и blobs. Старый том
Ubuntu/WSL сначала экспортируется без удаления. Временный импортёр работает
с **--network none**, сверяет SHA-256 двух manifest и всех referenced blobs
до записи. Несовпадающие существующие файлы не перезаписываются. Повторный
импорт идентичных файлов не дублирует их. Старые volumes не удаляются.

При старте нет ollama pull, загрузки весов, замены модели или обучения.

## Запуск и остановка

~~~powershell
docker --context desktop-linux compose --env-file legacy/docker/.env -f legacy/docker/compose.yaml up -d --build --wait --wait-timeout 240
~~~

Или **legacy/docker/START.bat**. BAT сначала запускает Docker Desktop через
официальный Desktop CLI, а при сбое холодного запуска — подписанный Docker backend из той же установки. `Start-Desktop.ps1` ждёт готовности движка desktop-linux,
выполняет config --quiet и поднимает Compose. Неисправный Docker Desktop.exe
не вызывается; автозагрузки весов нет.

~~~powershell
docker --context desktop-linux compose --env-file legacy/docker/.env -f legacy/docker/compose.yaml down
~~~

Или **legacy/docker/STOP.bat**. Флаг --volumes не используется. При SIGTERM
Minecraft получает save-all flush и stop через закрытый stdin. На сохранение
выделено до 120 секунд. Режиссёру даётся до 110 секунд закончить текущий
ограниченный запрос; Playit получает SIGINT и до 15 секунд на штатную остановку.
Общий --timeout не задаётся: Compose использует stop_grace_period каждого сервиса.
SIGINT поддерживает [официальный playitd](https://github.com/playit-cloud/playit-agent/blob/master/packages/playitd/src/daemon.rs).

При обновлении модов сначала останавливается Compose и обновляется именно
его каталог данных; prepare поверх существующего каталога не запускается.

compose.yaml записан в **JSON-синтаксисе YAML**, который поддерживает Compose.
Инфраструктурные контракты поэтому проверяются стандартным json Python,
без дополнительной зависимости от YAML-парсера.

## Режимы ИИ

Две разные закреплённые модели используют один CPU-бэкенд по очереди.
Сохраняется proposal → critique → revision с общим deadline. Настройка
переключается в админке и хранится в общей ai-control.sqlite3.

- single: действует основная модель; критик выгружается через keep_alive=0
  с подтверждением /api/ps;
- dual: критик участвует по очереди с основной моделью;
- одновременно загружена максимум одна модель; параллельных запросов нет.

Пины и настройки берутся из скопированного director.example.toml. Если
нужные веса отсутствуют, режиссёр сообщает ошибку и перезапускается, админка
показывает недоступность. Другая модель автоматически не выбирается.

Лимиты по умолчанию: Java heap 4 GiB / контейнер 5 GiB, Ollama 6 GiB и 4 CPU,
director и admin по 256 MiB, Playit 128 MiB. Это настройки, а не результат
нагрузочного теста. Память Windows, клиента и Docker Desktop считается отдельно.
Heap и лимит контейнера Minecraft задаются в local .env.

## Диагностика

~~~powershell
director/.venv/Scripts/python.exe tools/legacy_compose.py check
director/.venv/Scripts/python.exe tools/legacy_compose.py status
~~~

check использует **compose config --quiet**. Не публиковать обычный полный
вывод compose config и inspect контейнера Playit: там могут быть секреты.

Healthcheck Minecraft требует живого порта и авторизованного ответа игрового
моста, director — свежего host acknowledgement, admin — HTTP health,
Ollama — каталога API. Работающий процесс Playit сам по себе не доказывает
доступность снаружи: требуется Minecraft status через внешний .gg адрес.

Изолированные проверки:

~~~powershell
director/.venv/Scripts/python.exe -m unittest discover -s tests -p test_legacy_compose.py -v
cd director
.venv/Scripts/python.exe -m unittest discover -s tests -p test_container_network.py -v
~~~

Перед финальной установкой требуются общий прогон модов, настоящий запуск
Compose, проверка админки/консоли, реальный выбор моделей в single и dual
с проверкой выгрузки критика, затем Minecraft status через .gg.
Unit-проверки не заменяют эти проверки интеграции.

Документация Docker:
[network_mode service](https://docs.docker.com/reference/compose-file/services/#network_mode),
[порядок старта и healthcheck](https://docs.docker.com/compose/how-tos/startup-order/).


## Native manifests-v2 Ollama 0.40.6

Старый named manifest может быть downgrade anchor: Ollama добавляет к нему
служебные слои удержания manifest/list. Его SHA отличается от SHA закреплённого
runnable manifest. Импортёр находит **точный pinned blob**, проверяет его байты
и все ссылки на config/веса. Пин в конфигурации не заменяется SHA обёртки.

В новой копии создаются classic и manifests-v2 regular-file refs из тех же
байтов, включая заданный alias dead-earth-critic:2b-q4. Это штатный portable
fallback формата Ollama; WSL symlinks и служебные anchor-обёртки не переносятся.
Имена исходных тегов и их регистр не используются для угадывания весов.
При отсутствии exact pinned bytes импорт прекращается до записи.

После импорта требуется подтвердить имена и полные digest ответом /api/tags
на закреплённом Ollama image. Это отдельная проверка; одного успеха копирования
для подтверждения доступности моделей недостаточно.

Официальная реализация:
[WriteLegacyAnchor и OpenVerifiedManifest](https://github.com/ollama/ollama/blob/v0.40.6/manifest/manifest.go),
[канонические пути v2](https://github.com/ollama/ollama/blob/v0.40.6/manifest/paths.go).


Проверка 20.10.2026: из реального экспорта подготовлена новая canonical копия.
Оба исходных manifest и все referenced blobs прошли SHA-256 проверку.
На закреплённом Ollama 0.40.6 в отдельном контейнере Ubuntu /api/tags вернул
qwen3.5:4b с digest d8b0f5e9760cd1682034f292d7ef72ec46f432149be0df7574bf2d6e92e38c04
и dead-earth-critic:2b-q4 с digest 1e2d21a4f03ab9e4a9c1976a4b7e88f21de61d65f2c5485d8fde8942be73f1e7.
Новая копия подключалась read-only, исходный volume не подключался.
Список resident models был пуст, inference не запускался. Собственный
проверочный контейнер штатно остановлен и удалён. Это подтверждает формат
store, alias и API identity; игровой Compose и выбор событий моделями
проверяются отдельно.
