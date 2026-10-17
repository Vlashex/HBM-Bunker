# Аудит защиты строительных блоков

Аудит 17.10.2026, Minecraft 1.21.1 / NeoForge 21.1.262. В проверенных модах нет общей механики трёх классов стен V1/V2/V3. SecurityCraft имеет три уровня **инструмента** Reinforcer, создающие те же reinforced-блоки. HBM предлагает разные значения hardness и explosion resistance, но не общую политику защиты от мобов и болезней.

Основной практический вывод: отрицательная hardness и очень большая взрывостойкость не запрещают произвольному моду заменить блок через `setBlock`. В Mutationcraft найден именно такой путь; отдельный нативный тест подтвердил конверсию десяти материалов через `PutridBlock.randomTick`, включая принадлежащие владельцу reinforced-блоки SecurityCraft. Это не означает, что каждый моб ломает любую стену.

## Проверенные версии и границы

Декомпилированы локальные JAR из `.cache/mods/`. SHA-256 всех пяти файлов пересчитаны и совпали с `metadata/mods.lock.json`:

| Мод | JAR / версия | SHA-256 |
|---|---|---|
| SecurityCraft | `[1.21.1] SecurityCraft v1.10.2.1.jar` | `75ac9e73c60caf58df7069f167dbacc00a640e1418207dff654f56a5fdb5f229` |
| ImprovedMobs | `improvedmobs-1.21.1-1.16.0.b-neoforge.jar` | `696e1c29b89c7008616eee02baab5df66d8d87a9c0d31b595e53f23346a5c947` |
| Mutationcraft | `mutationcraft-legacy-neoforge-1.1-1.21.1.jar` | `61bf476b66fbb6822bf5b15b815e17e1585c0695ffa363aa8c14e97488cce2d7` |
| HBM | `ntm-decentralized-neoforge-1.21.1-1.0.0-beta.10.jar` | `df38bfbf1a0b16be7491357c5d2908706a62cfff9ef91cf403a29a72fb762dab` |
| Overgrown Vegetation | `overgrown_vegetation-0.1.9.jar` | `8124f29815f70d0c4c1e2afcb53410ae0c057b26d552a713fd67ac89943d4213` |

Аудит относится к названным классам и путям; произвольные сторонние инструменты, команды администратора и все возможные способности всех мобов не проверялись. Работающий пользовательский сервер и GUI не использовались.

## Укреплённые блоки и доступные уровни

**SecurityCraft.** Reinforcer lvl1 имеет 300 использований, lvl2 — 2700, lvl3 создаётся без durability. У lvl2/3 есть режим обратного преобразования. Все уровни выбирают блок через одну карту `IReinforcedBlock.VANILLA_TO_SECURITYCRAFT`: уровень инструмента не записывается как три разных защитных класса стены.

`BaseReinforcedBlock` наследует `OwnableBlock`, который назначает hardness `-1`; `SCContent.reinforcedCopy` задаёт `Float.MAX_VALUE` explosion resistance. Доступ владельца и снятие reinforced обрабатываются отдельными средствами SecurityCraft. Для Wither дополнительно отменяется `LivingDestroyBlockEvent`. Это сильная защита от обычного копания/взрывного расчёта, но не перехват каждого чужого `setBlock`.

**HBM.** Ниже значения из действительных регистраций `ModBlocks`, а не переводов названий:

| ID блока | Hardness | Explosion resistance | Особенность |
|---|---:|---:|---|
| `hbm:reinforced_glass` | 2 | 15 | Положительная hardness; не аналог SecurityCraft reinforced glass |
| `hbm:reinforced_stone` | 15 | 60 | Dense Stone |
| `hbm:reinforced_brick` | 15 | 180 | Reinforced Stone |
| `hbm:concrete_rebar` | 50 | 144 | Reinforced Concrete |
| `hbm:reinforced_ducrete` | 20 | 600 | Отмечен также как radiation resistant; это не защита от прямой замены |
| `hbm:concrete_super` | 150 | 600 | Über Concrete стареет `age=0..15`, затем удаляется/превращается в Moldy Debris |

Поэтому `concrete_super` нельзя рекомендовать как постоянную стену V3 только по самой высокой hardness. Отдельного общего HP стены в перечисленных регистрациях нет.

## Конкретные пути изменения мира

| Путь | Какие ограничения реально применяет | Как меняет мир / граница вывода |
|---|---|---|
| ImprovedMobs `BlockBreakGoal` | AI-флаг breaker, цель/idle-настройка, `mobGriefing`, whitelist/blacklist, инструмент; hardness < 0 даёт нулевой прогресс | В конце вызывает `Level.destroyBlock(pos,canHarvest)` напрямую. Это не `BreakEvent` игрока и не обычный взрыв |
| Mutationcraft `PutridBlock.randomTick → spread → grow` | Главный tick проверяет config и вероятность. `canSpreadInto` исключает air/cave_air/putrid/vine; hardness, resistance, owner и защитный тег не проверяются | `grow` вызывает `setBlock` для гнили и иногда для лозы. Нативный `randomTick` подтвердил конверсию всех десяти тестовых материалов, включая три owned reinforced-блока SecurityCraft; границы проверки приведены ниже |
| Mutationcraft `HookBehavior.onSpawn` | Настройка `HOOKS_PLACE_PUTRID_BLOCKS` | Прямой `setBlock` под Hook, без проверки материала/владельца. Не распространять этот вывод на остальных мобов |
| Mutationcraft Assimilated Creeper / Hazmat Flamethrower | Нативные условия способности и config | Используют обычный `Level.explode(...MOB)`; это иной путь, где работают взрывной расчёт и события |
| Overgrown `ArboraTunnelGoal` | Отсекает hardness < 0, block entity, жидкости и соседние жидкости | Добывает через `destroyBlock(pos,false)` напрямую. Положительная hardness сама по себе не делает стену недоступной |
| Overgrown `CorruptionRegistry` / `BuildCorrosion` | Проверяет `corruption_immune`, отрицательную hardness, переключатель системы, правила block entity и spore inhibitors | Разрешённая конверсия идёт через `placeCorrupted → setBlock`, без player BreakEvent и взрыва. Значение resistance не является универсальным иммунитетом к болезни |
| Overgrown `SiegeBombEntity` | Кратер имеет собственные radius/max-resistance настройки | Взрыв для урона использует `ExplosionInteraction.NONE`, затем отдельный `carveMinimalCrater` вызывает `destroyBlock`. Фильтра affectedBlocks ванильного взрыва недостаточно |
| HBM parallel nuke | Реальный ray-код вычисляет сопротивление; при resistance ≥ 2000000 прекращает этот луч | Позднее `ChunkUtil.carveSnapshot` пишет сырой snapshot, обходя обычные setters/events. Принудительно заданная маска доказывает свойства примитива, а не способность нормальной ядерки пробить любой reinforced-блок |

В частности HBM `getNukeResistance` возвращает сопротивление самого блока, кроме специальных правил для жидкостей, sandstone и **ванильного** obsidian. `Float.MAX_VALUE` SecurityCraft выше порога остановки этого луча. Утверждение «HBM nuke обязательно уничтожит SecurityCraft reinforced» по найденному raw writer было бы неверным.

Текущая временная защита стартового бункера v5 имеет собственные нижние guards `setBlock/destroyBlock/LevelChunk.setBlockState` и HBM snapshot-адаптер. Она ограничена исходным снимком и deadline кампании; её наличие не делает все купленные reinforced-стены мира бессрочно защищёнными.

Дополнительный риск при изменении ImprovedMobs: если включить ненулевой `Restore delay`, его код запоминает предполагаемый дроп **до** `destroyBlock`; при сохранении блока нижним guard затем может выдать этот дроп через `Block.popResource`. По умолчанию restoreDelay=0. При будущей tier-политике отказывать следует уже на стадии выбора разрешённой добычи, до регистрации восстановления; отдельно проверить отсутствие дюпа.

## Нативная проверка

Тестовый `tools/loadtest/java/dev/deadearth/loadtest/ProtectionProbe.java`, подключаемый при `deadEarthLoadTest`, выполнен в отдельном свежем тестовом сервере. Сохранён [нативный отчёт 5.2.0](../verification/protection/native-5.2.0.json): все десять ID зарегистрированы, и настоящий `PutridBlock.randomTick` превратил каждый target в `mutationcraft:putrid_block`.

| Материалы | Проверено / конвертировано через `randomTick` | Нативный Overgrown `canCorrupt` |
|---|---:|---|
| `minecraft:stone` | 1 / 1 | `true` |
| SecurityCraft `reinforced_stone`, `reinforced_stone_bricks`, `reinforced_glass` | 3 / 3 | `false` для всех трёх |
| Шесть HBM-стен из таблицы выше | 6 / 6 | `true` для всех шести |

У всех трёх блоков SecurityCraft назначен владелец (`securityCraftOwnerSet=true`), hardness равна `-1`, resistance — `Float.MAX_VALUE`. Во всех десяти строках `starterBunkerProtectsRemoval=false`, а `activeTicks=13`: результат относится к свойствам материалов вне защищённого стартового объекта, а не к обходу временной защиты SafeBunker.

Probe использовал маленькую предварительно пустую арену и восстановил свои 36 позиций в `finally`; итоговый `arenaRestored=true`. Последовательность проверки:

1. Считывает настоящие hardness/resistance; владельца SecurityCraft назначает через нативный `IOwnable.setOwner`.
2. Вызывает настоящий `PutridBlock.canSpreadInto`.
3. Вызывает настоящий публичный `randomTick` с детерминированным малым random roll; config и сезонный множитель остаются действовать.
4. Отдельно, с явной маркировкой обхода config/probability gate, вызывает нативный private `grow` после predicate. Это проверка примитива конверсии.
5. Считывает нативный Overgrown `canCorrupt`; predicate не равен успешной полной конверсии или всем способностям Overgrown.

Для каждого материала выполнен один детерминированный нативный `randomTick`: `randomSeed=5188`, первый random roll — `7.01048770467283E-5`, `naturalPutridSpreadingEnabled=true`. Config и сезонный множитель не обходились. Отдельный прямой вызов private `grow` после predicate также дал гниль, но он намеренно обходит gates `randomTick` и не нужен для основного вывода: конверсия уже произошла через публичный нативный tick.

Это ограниченная проверка конкретного пути распространения Putrid. Она не измеряет частоту естественного заражения, не воспроизводит AI/бой всех мобов и не проверяет обычные взрывы. `Overgrown.canCorrupt=true` означает только разрешение predicate, а не доказанную полную конверсию; `false` для SecurityCraft подтверждено именно для проверенных блоков и текущей конфигурации. Произвольного `level.destroyBlock`, представляемого как способность любого моба, в Probe нет.

Ожидание ответа RCON истекло через 6 секунд во время генерации новой арены. Сохранённый файл содержит результаты всех десяти материалов и успешное восстановление арены, подтверждая завершение единственного вызова; повторного вызова не было. Это таймаут ожидания тестового управления, а не ошибка gameplay.

Для следующего ограниченного набора нужны настоящие `ImprovedMobs.BlockBreakGoal` с разрешёнными целью/инструментом/списком, `ArboraTunnelGoal.canDig/breakAndCorrupt`, `CorruptionRegistry.placeCorrupted`, `SiegeBombEntity.carveMinimalCrater` на маленьком радиусе и оба HBM snapshot writer. Контроли: обычный блок должен изменяться; защищённый — оставаться без дропа; игрок должен разбирать свой материал; после expiry стартовой защиты исход должен определяться самим материалом. Проверки вести в новом мире, а не на работающем сервере человека.

## Рекомендация по V1/V2/V3

Новые тяжёлые моды не нужны. Если требуются именно три понятных класса, лёгкий вариант — три обычных строительных блока внутри Dead Earth, с рецептами через текущую металлургию HBM и без block entity на каждую стену. Сначала требуется отдельная точная спецификация:

- HP стены, hardness для инструмента игрока и explosion resistance — три разные величины. Положительная hardness не создаёт HP; нужен явный ограниченный damage-процесс, если HP предполагается.
- Класс определяет правила для конкретных источников: обычное копание, специальные breach-способности, гниль/лозы, Overgrown corruption, siege crater, обычный взрыв, HBM nuclear snapshot. Числа и перечень разрешённых атак нельзя заменять названием V3.
- Теги классов и адаптеры должны проверять каждый путь до изменений и регистрации дропа. Для Putrid важны **оба назначения**, target и vine: одного отказа `canSpreadInto` недостаточно из-за fallback `grow`.
- Overgrown уже имеет `corruption_immune` для datapack-совместимости, но этот тег не останавливает Mutationcraft. ImprovedMobs имеет списки/теги добычи; они тоже не являются общей защитой от остальных модов.
- Владение/демонтаж игроком, ремонт, сохранение HP, пределы очередей и реакция на неизвестный адаптер задаются явно. Нижний guard не должен случайно запрещать законный демонтаж и нативные изменения дверных свойств.

Альтернатива без новых визуальных блоков — назначить классы выбранным HBM стенам тегами и общей политикой. Это создаст настоящую новую механику интеграции, а не обнаружит уже существующую. В этом аудите ни один вариант не внедряется.

## Где проверять доказательства

Исходные классы находятся в названных JAR. Локальная Vineflower 1.10.1-декомпиляция: `work/dead-earth/protection-audit-20261017/{security,improved,mutation,overgrown,hbm}/source/`. Номера ниже относятся к этой декомпиляции, не к оригинальным debug LineNumberTable:

| Мод | Путь класса внутри JAR → метод / строки декомпиляции |
|---|---|
| SecurityCraft | `net/geforcemods/securitycraft/SCContent.class` → Reinforcer registrations 4482–4491; `reinforcedCopy` 5062–5067 |
| SecurityCraft | `net/geforcemods/securitycraft/blocks/OwnableBlock.class` → `withReinforcedDestroyTime` 56–58 |
| SecurityCraft | `net/geforcemods/securitycraft/items/UniversalBlockReinforcerItem.class` → единая конверсия 111–140; режим 161–163 |
| SecurityCraft | `net/geforcemods/securitycraft/SCEventHandler.class` → Wither `onLivingDestroyEvent` 532–533 |
| ImprovedMobs | `io/github/flemmli97/improvedmobs/common/entities/ai/BlockBreakGoal.class` → `tick` 92–115 |
| ImprovedMobs | `io/github/flemmli97/improvedmobs/common/utils/Utils.class` → `canBreakBlocks/canBreakState` 52–68; `getBlockStrength` 139–163 |
| ImprovedMobs | `io/github/flemmli97/improvedmobs/common/config/values/BreakableBlocks.class` → `canBreak` 27–53; `common/utils/BlockRestorationData.class` → 47–58, 94 |
| Mutationcraft | `com/asestefan/mutationcraft/block/PutridBlock.class` → `randomTick/spread/canSpreadInto/grow` 38–79 |
| Mutationcraft | `com/asestefan/mutationcraft/behavior/HookBehavior.class` → `onSpawn` 24–32 |
| Overgrown | `com/yayuy/overgrownvegetation/entity/ArboraTunnelGoal.class` → `canDig/breakAndCorrupt` 264–292 |
| Overgrown | `com/yayuy/overgrownvegetation/corruption/CorruptionRegistry.class` → `placeCorruptedInScope` 308–320; `canCorrupt` 391–444 |
| Overgrown | `com/yayuy/overgrownvegetation/entity/SiegeBombEntity.class` → `detonateAt/carveMinimalCrater` 100–118 |
| HBM | `com/hbm/blocks/ModBlocks.class` → glass 2661–2679; walls 4758, 4789, 4806–4810, 4832–4838 |
| HBM | `com/hbm/blocks/generic/BlockUberConcrete.class` → `randomTick` 39–71 |
| HBM | `com/hbm/explosion/ExplosionNukeRayBatched.class` → `getNukeResistance` 62–71; `ExplosionNukeRayParallelized.class` → 1633–1638, 2324 |

Последние два explosion-класса уже имели локальную декомпиляцию в `work/dead-earth/v5-hbm/`; обычные исходники игрового мода этим аудитом не изменены.
