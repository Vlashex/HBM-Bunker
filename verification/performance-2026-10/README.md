# Нагрузочные измерения 18.10.2026

[Читаемый отчёт](../../docs/performance/2026-10-18-load-test.md) и
[машинная сводка](summary.json) относятся к **5.2.0 / coordinator 4.1.1**.
Защитные материалы следующего выпуска не участвовали в нагрузке.

Сводка содержит распределения MSPT/frame time, TPS/FPS, реальные количества
чанков/мобов/контейнеров, состояния камеры/Iris, память, отдельные execution и
native JFR samples, пины исходной версии, хеши сырых входов и JFR. Inclusive
стековые проценты пересекаются; длительности стадий worldgen идут параллельно.
Они не являются суммарным wall-time или процентом загрузки GPU.

## Источники

Вне Git, относительно корня рабочей папки задачи:

| Каталог | Что используется |
|---|---|
| `work/dead-earth/performance-3` | Холодная/готовая область, выгрузка. Первые shader-фазы и warm64 с фоновой генерацией — контекст прогрева |
| `work/dead-earth/performance-4` | 1500 AI/NoAI, shader on/off/on, 250 humans, шесть складов, климат, сохранение, прогретый baseline. Дрейфовавшие 250/750 исключены |
| `work/dead-earth/performance-5` | Повтор 250/750 с точной камерой, spiders/ram/humans, динамические траектории, UUID/Pos двух движущихся зомби |

Первые `performance-1/-2` — диагностические попытки с коротким транспортным
timeout. Неверные namespace-команды и неуспешная очистка до исправления harness
не считаются успешной массовой нагрузкой. Два современных cleanup smoke
3→0→3→0 и проверка реальных tagged counts пройдены до принятия матрицы.

Последние собственные процессы закрыты штатно после фазы 00:34:39.632 UTC+3.
Порты 25568/25578 закрыты. Основные сервер и клиент сохранили время запуска.
Полные логи, JFR, миры и runtime credentials в Git не включаются.

## Повторение

Используйте коммит `558d606`: это исходная 5.2.0 с instrumentation.
Текущий игровой выпуск с новыми материалами не эквивалентен этому
baseline. Нужны Java 21, Gradle cache, закреплённые JAR и шейдер в `.cache/`.
`tools/release.py fetch` и `fetch-shaders` загружают их с проверкой хешей.

`prepare.py` предназначен только для уже разрешённого локального тестового
сервера: создаёт новую папку и EULA-файл, loopback 25568/RCON25578, отдельный
normal world и тестовый клиент. Основную установку он не использует.

```powershell
python tools/loadtest/prepare.py C:/path/to/task/work/dead-earth/performance-new
```

В двух терминалах из `mods/dead-earth/` запустите соответствующие dev server
и client. Все `-P` аргументы с путями/адресами в PowerShell передавайте целиком
в кавычках:

```powershell
.\gradlew.bat runServer --offline --console=plain -PdeadEarthLoadTest `
  '-PdeadEarthLoadTestOutput=C:/path/to/task/work/dead-earth/performance-new' `
  '-PdeadEarthServerDirectory=run-server-performance-new'

.\gradlew.bat runClient --offline --console=plain -PdeadEarthLoadTest `
  '-PdeadEarthLoadTestOutput=C:/path/to/task/work/dead-earth/performance-new' `
  '-PdeadEarthClientDirectory=run-client-performance-new' `
  '-PdeadEarthSmokeServer=127.0.0.1:25568'
```

Из корня репозитория выберите **один** driver для конкретного прогона:

- `python tools/loadtest/run.py <output>` — baseline, chunks и полная нагрузка.
- `python tools/loadtest/remaining.py <output>` — валидируемая AI/NoAI,
  контейнерная и климатическая матрица без повторной генерации арены.
- `python tools/loadtest/extended.py <output>` — дополнение в уже подготовленном
  и повторно запущенном disposable world, с созданной ареной. Не запускайте его
  против произвольного сервера: он удаляет только свои шесть складских fixtures.

Для mobility samples запустите параллельно
`python tools/loadtest/motion_samples.py <output>`. Это ограниченные нативные
RCON-запросы. Driver проверяет marker, порт, level-name и loopback; не использует
консоль основного сервера и не добавляет полномочий MCP/модели.

Фазы длятся 20–35 секунд после фактической постановки нагрузки. Большая очередь
имеет отдельный bounded timeout; guard следит за свободной памятью. Не нужно
ждать реальных трёх часов/полного сезона. Сезон выбирается только через явно
включённые test controls. Это не ускорение wall-clock для заявления TPS.

## Сведение

```powershell
python tools/loadtest/analyze.py <output>
python -m unittest discover -s tools/loadtest -p 'test*.py' -v
```

`JfrSummary.java` компилируется обычным `javac` отдельно от Minecraft. Аргументы:
путь JFR и TSV `name<TAB>startEpochMs<TAB>endEpochMs`. Для итогового профиля
используйте `measurement-phases.tsv`, учитывающий settling и интервалы вне
запросов позиции. Вывод направьте в `client-jfr.tsv` / `server-*-jfr.tsv`.
После редукции трёх исходных каталогов:

```powershell
python tools/loadtest/collect_report.py C:/path/to/task
```

`collect_report.py` читает baseline pins из локального тега `v5.2.0`; секретные
server.properties/runtime descriptors не экспортируются. Укажите `JAVA_HOME`
на используемый JDK 21; fallback проверяет Java в PATH и стандартную Java Prism.

Обычный JAR отдельно проверен на отсутствие `dev/deadearth/loadtest/`.
16 тестов упаковщика, `release.py check`, analyzer regression, native cleanup
smoke и реальные игровые прогоны прошли. Этот перечень не является A/B замером
накладных расходов instrumentation.
