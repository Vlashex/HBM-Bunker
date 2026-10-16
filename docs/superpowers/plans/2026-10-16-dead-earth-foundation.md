# Dead Earth Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Создать сохраняемый серверный координатор и один ручной безопасный сценарий,
не меняя баланс существующего MVP.

**Architecture:** Java-мод отделяет чистую логику часов и сценариев от NeoForge.
Серверный адаптер хранит SavedData и исполняет шаги в основном потоке.
Первый сценарий показывает предупреждение; HBM, биология, MCP и LLM подключаются позже.

**Tech Stack:** Java 21, Minecraft 1.21.1, NeoForge 21.1.262, ModDevGradle 2.0.148,
Gradle 9.2.1, JUnit Jupiter 5.11.4, NeoForge GameTest.

**Spec:** [DEAD EARTH roadmap](../../DEAD-EARTH-ROADMAP.md),
[контракт сценариев](../../DEAD-EARTH-MCP.md).

Статус: **план будущей реализации**. Этот документ не означает, что Foundation уже
собран или испытан. Подробные планы климата, адаптеров и MCP составляются отдельно
после проверки их предпосылок. Первые три задачи ниже — самостоятельный срез.

## Global Constraints

- Minecraft **1.21.1**, NeoForge **21.1.262**, Java **21**; существующие версии модов не обновлять.
- **Кооператив 2–6 игроков**, сервер, один клиент и локальная модель на одном ПК с 32 ГБ RAM.
- HBM остаётся основой металлургии; сырьевой EMC и доход автоматизации сохраняются.
- WARDEN позже ограничен **0.90…1.10**; в Foundation он ещё не действует.
- Все обращения к игровому миру и изменения SavedData — в серверном потоке.
- Нет произвольных команд модели, разрушения мира и автоматического переноса старых сохранений.
- Нет JAR, миров, секретов и полных журналов в Git; работа только в отдельном тестовом профиле.

## Review Focus

- Сон, `/time set` и отсутствие игроков не ускоряют сценарные часы — тест задачи 1.
- Неизвестная будущая версия сохранения не перезаписывается значениями по умолчанию — задача 1.
- Дубликат или конфликт ID не создаёт второе событие — задача 2.
- Между preview и исполнением команда может выйти, а окно истечь — задача 2.
- Перезапуск после начатого эффекта не должен автоматически повторять неизвестный результат — задачи 2 и 3.

## Структура файлов и границы

Все пути от корня репозитория; перечисленные Java-файлы пока не существуют.

| Файл / каталог | Назначение |
|---|---|
| `mods/dead-earth/build.gradle`, `settings.gradle`, `gradle.properties` | Закреплённая сборка, unit-тесты, client/server/GameTest runs |
| `mods/dead-earth/gradle/wrapper/gradle-wrapper.properties`, `gradlew.bat`, `gradlew` | Официальная конфигурация запуска; wrapper JAR получается локально и не коммитится |
| `mods/dead-earth/src/main/templates/META-INF/neoforge.mods.toml` | Метаданные `dead_earth`, версия `0.1.0-dev`, зависимости |
| `mods/dead-earth/src/main/java/dev/deadearth/DeadEarth.java` | Регистрация мода и серверных обработчиков |
| `.../campaign/CampaignClock.java`, `CampaignState.java`, `CampaignStore.java` | Чистые часы/состояние и NeoForge SavedData |
| `.../scenario/ScenarioTypes.java`, `ScenarioEngine.java`, `ScenarioStore.java` | DTO, очередь, переходы, сериализация квитанций |
| `.../command/DeadEarthCommands.java` | Только операторские status/preview/submit/cancel для одного шаблона |
| `.../scenario/WarningBeaconExecutor.java` | Безопасное сообщение в игровом чате с ID сценария |
| `mods/dead-earth/src/test/java/dev/deadearth/` | JUnit для времени, сериализации DTO и движка |
| `mods/dead-earth/src/main/java/dev/deadearth/gametest/FoundationGameTests.java` | Проверка реального SavedData и серверного исполнителя в dev-стенде |
| `verification/dead-earth/foundation.md` | Краткие результаты; без сохранения и полных логов |

Пути с `...` продолжают `mods/dead-earth/src/main/java/dev/deadearth`.
Исходный шаблон сборки:
[официальный NeoForge MDK 1.21.1](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle).
Версии Gradle и плагина проверены по его конфигурации 16 октября 2026; в задаче 1
зафиксировать использованный upstream commit и checksum дистрибутива, исключить
примерные предметы/блоки MDK. Если первая сборка выявит несовместимость, сначала
исправить и документировать toolchain, а не обновлять Minecraft или моды сборки.

## Task 1: Часы и сохраняемая кампания

**Files:** создать проект и файлы `campaign/` из таблицы;
тест `src/test/java/dev/deadearth/campaign/CampaignClockTest.java`.

**Interfaces:**

- `CampaignClock.advance(long activeTicks, boolean hasOnlinePlayers): long` — один
  серверный тик даёт `+1`, только если есть игроки; отрицательное время запрещено.
- `CampaignState` содержит `schemaVersion=1`, `campaignId: UUID`, `revision: long`,
  `activeTicks: long`; эпоха соединения создаётся заново при загрузке сервера.
- `CampaignStore` читает/пишет это состояние через SavedData и помечает изменения dirty.
  Неизвестная версия даёт диагностическую ошибку и выключает координатор без записи.

- [ ] **Step 1: Создать минимальную конфигурацию сборки и падающий тест.**

```java
@Test void advancesOnlyWithOnlinePlayers() {
    assertEquals(101L, CampaignClock.advance(100L, true));
    assertEquals(100L, CampaignClock.advance(100L, false));
}
```

  Добавить `rejectsNegativeTicks`, `rejectsUnknownSaveSchemaWithoutWriting`,
  `roundTripsCampaignState`. Входные dayTime и длительность сна не входят в API часов.
- [ ] **Step 2: Выполнить `./gradlew.bat test` из `mods/dead-earth`.**
  Ожидается ошибка отсутствующей реализации, а не скачивания зависимостей.
- [ ] **Step 3: Реализовать интерфейсы, SavedData и серверный tick hook.**
  `advance` вызывается ровно один раз за серверный тик; измерения не создают
  дополнительные часы. Изменения схемы не мигрируются неявно.
- [ ] **Step 4: Выполнить `./gradlew.bat test build`.**
  Все тесты задачи проходят. Включить lock/verification Gradle-зависимостей.
- [ ] **Step 5: Зафиксировать только исходники, проверки и конфигурацию.**
  Коммит `feat: add persistent Dead Earth campaign clock`.

## Task 2: Ограниченный движок одного сценария

**Files:** создать `scenario/ScenarioTypes.java`, `ScenarioEngine.java`,
`ScenarioStore.java`; тест `src/test/java/dev/deadearth/scenario/ScenarioEngineTest.java`.

**Interfaces:**

- `Proposal(requestId: UUID, worldEpoch: UUID, targetId: String,
  earliestTick: long, expiresTick: long)`; единственный catalog ID в этом срезе —
  `warning_beacon_v1`, допустимая цель — зарегистрированный `test_anchor`.
- `WorldView(epoch: UUID, activeTicks: long, onlinePlayers: int, anchorLoaded: boolean)`.
- `preview(Proposal, WorldView): Decision` возвращает `accepted` и типизированные причины.
- `submit(Proposal, WorldView): Receipt` возвращает стабильные scenario ID и состояние;
  точный дубль не создаёт новый экземпляр, конфликт payload с тем же ID отклоняется.
- `tick(WorldView, StepExecutor): List<Receipt>` исполняет не более одного готового
  шага за вызов; `StepExecutor.execute(UUID scenarioId): StepResult` сообщает результат.
- `cancel(UUID scenarioId, long expectedRevision): Receipt` отменяет только будущий шаг.

Для Foundation доступны `scheduled`, `running`, `completed`, `cancelled`, `expired`,
`uncertain`, `rejected`. Сохранённый `running` после перезапуска становится `uncertain`.
Полная модель состояний основного контракта будет расширена при добавлении предвестников.

- [ ] **Step 1: Написать падающие тесты движка.**
  `duplicateRequestExecutesOnce` проверяет один вызов fake executor после двух submit;
  `conflictingDuplicateRejected` — тот же ID и другая цель отклонены;
  `wrongEpochRejected` — ни одной резервации;
  `offlineOrUnloadedDefersUntilExpiry` — нет эффекта, затем `expired`;
  `cancelledStepDoesNotRun` — нет вызова исполнителя;
  `restoredRunningBecomesUncertainWithoutRetry` — executor после загрузки не вызывается.
- [ ] **Step 2: Выполнить `./gradlew.bat test --tests '*ScenarioEngineTest'`.**
  Ожидается отсутствие реализации либо провал перечисленных утверждений.
- [ ] **Step 3: Реализовать очередь, проверку и сохраняемые квитанции.**
  Условия повторно проверяются в `tick`, время берётся только из `WorldView`.
  DTO не содержит команды, произвольного NBT или путей к файлам. Не добавлять
  абстракцию всех будущих HBM-взрывов в этом срезе.
- [ ] **Step 4: Выполнить `./gradlew.bat test build`.**
  Все тесты задач 1–2 проходят, сериализация сохраняет ID и ревизии.
- [ ] **Step 5: Коммит `feat: add bounded scenario queue and receipts`.**

## Task 3: Проверка в настоящем сервере

**Files:** создать файлы `command/`, `WarningBeaconExecutor.java`,
`gametest/FoundationGameTests.java`, `verification/dead-earth/foundation.md`;
обновить `CHANGELOG.md`, инструкции разработки и `.gitignore` для локального стенда.

**Interfaces:**

- Операторские `/deadearth status`, `/deadearth preview <request_uuid>`,
  `/deadearth submit <request_uuid>`, `/deadearth cancel <scenario_uuid> <revision>`.
- Команды используют текущую эпоху и `test_anchor`; окно первого сценария —
  текущий активный тик + 200 до + 1200. Ни одного сетевого endpoint пока нет.
- При повторном submit того же request UUID команда возвращает сохранённую
  квитанцию: она не создаёт заново окно времени из текущего тика. Preview
  остаётся без побочных эффектов и не резервирует будущую допустимость.
- Исполнитель отправляет одно сообщение `[DEAD EARTH] Тестовый сигнал <scenario_id>`
  онлайн-игрокам; он не изменяет блоки, погоду, инвентари или состояние других модов.

- [ ] **Step 1: Зарегистрировать падающие GameTest-проверки.**
  `savedDataRoundTripPreservesRequest`, `commandsRequireOperator`,
  `warningRunsOnServerThread`, `duplicateSubmitProducesOneSignal`.
  Тесты проверяют реальный серверный адаптер, а не повторяют все unit-тесты движка.
- [ ] **Step 2: Выполнить `./gradlew.bat runGameTestServer`.**
  Ожидается провал функциональных утверждений до реализации команд/исполнителя.
- [ ] **Step 3: Реализовать команды и предупреждение; выполнить `test build runGameTestServer`.**
  Использовать отдельную dev-директорию, не профиль пользователя.
- [ ] **Step 4: Провести проверку перезапуска в отдельном тестовом мире.**
  Сохранить запланированный шаг, перезапустить — он остаётся в очереди;
  завершить, сохранить, перезапустить — второй сигнал не приходит.
  Смоделировать `running` перед сбоем — он становится `uncertain` и не повторяется.
  Сон, `/time set` и выход всех игроков не приближают активный дедлайн.
- [ ] **Step 5: Скопировать локально собранный мод только в отдельный стенд с закреплёнными 28 модами.**
  Запустить выделенный сервер и клиент; проверить подключение и один сигнал.
  Зафиксировать длительность, RAM, MSPT и наблюдаемый результат; не объявлять
  доказанной нагрузку шести игроков по одному локальному клиенту.
- [ ] **Step 6: Выполнить проверки репозитория и записать ограничения.**
  Из корня: `python -m unittest discover -s tests -v`, `python tools/release.py check`.
  Сохранить краткую сводку игровых испытаний, версии и хеш локального артефакта;
  не коммитить JAR и тестовый мир. Выпускной ZIP MVP пока не пересобирать новым модом.
- [ ] **Step 7: Коммит `feat: expose and verify manual Dead Earth warning scenario`.**

## Граница завершения

Foundation принят, когда сохраняемые часы и один ограниченный сценарий работают
в отдельном сервере с существующей сборкой, повторные заявки не дублируют сигнал,
а перезапуск и просроченное окно имеют проверенное поведение. Следующий план —
мёртвый world preset и первый климатический цикл. Ни MCP, ни нейросеть не являются
условием завершения этого среза.
