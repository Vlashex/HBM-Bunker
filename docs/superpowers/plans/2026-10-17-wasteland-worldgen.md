# Wasteland Worldgen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Inline execution in existing linked worktree, per user autonomous development instruction.

**Goal:** Безжизненная поверхность с кратерами, стволами и мутной водой; HBM постройки сохраняются.
**Architecture:** Существующие noise/биомы сохраняются, меняются soil surface rules. Два bounded Features и BiomeModifier в собственном моде. Геометрия/палитра отдельно от Minecraft API.
**Tech Stack:** Minecraft1.21.1, NeoForge21.1.262, Java21/JUnit/GameTest; existing mod only.
**Spec:** docs/superpowers/specs/2026-10-17-wasteland-worldgen.md

## Global Constraints

- Последняя поправка: все HBM постройки сохранить, выключить только наземную vanilla архитектуру.
- Собственный ландшафт без радиации; обычная вода с грязным цветом, не токсичная радиоактивная жидкость.
- Сохранить руды/подземные сооружения/Nether/End и динамические угрозы сборки.
- Генерация меняет только текущий новый чанк; без force-load/фоновых переделок/пользовательских сохранений.
- Никаких новых тяжёлых модов. Настройки сервера, безопасная ограниченная палитра.

## Review Focus

- Стык соседних чанков/отрицательные координаты: одинаковая геометрия при любом порядке генерации.
- Вода, высота мира, дно/крутые склоны: ограниченные изменения, без выхода за bounds.
- Native HBM features vs plants: архитектура и руды остаются, зелень не возвращается через biome modifiers.
- Подводные/подземные/Nether структуры не отключаются широкой заменой generate-structures=false.
- Нативные паразиты и дальнейшее вторжение леса не удаляются вместе с мирной фауной.

## Task1: поле кратеров и палитра

Files: worldgen/WastelandField.java, WastelandSettings.java, WastelandFieldTest.java.
Interfaces: settings immutable bounded record; samples(seed,x,z) -> displacement; palette(seed,x,z) -> safe enum; candidates(seed,bounds) -> finite crater list.
- [ ] RED: границы/частота двух размеров, детерминированность, отрицательные координаты, разделение seed, семантика безопасной палитры.
- [ ] Минимальная реализация, GREEN целевые JUnit.
- [ ] Common TOML: размеры/частоты/стволы/веса, snapshot при первом использовании.
- [ ] Коммит ядра и настроек.

## Task2: генерация и biome policy

Files: worldgen/WastelandFeature.java, DeadTrunksFeature.java, WastelandBiomeModifier.java, WastelandWorldgen.java; content/WastelandContent.java; resources/data & assets.
Interfaces: Feature updates only origin chunk; biome modifier filters typed features by namespace/id; no radiation API.
- [ ] RED: actual biome effects/feature policy and structure data expectations.
- [ ] Добавить инертный ash, местную отделку/кратеры и leaf-free trunks; registry data.
- [ ] Overworld surface override preserves noise/router/ores; exact vanilla structure exclusions.
- [ ] Targeted GameTests in fresh full-pack world; prove HBM still registered/eligible and no radioactive surface palette.
- [ ] Коммит связной генерации.

## Task3: обычный мир и выпуск

- [ ] Новый обычный мир, ограниченная выборка чанков, stats для кратеров/стволов/зелени/материалов/структур/радиации.
- [ ] Настоящий клиент: общий вид, крупный кратер, мутная вода; screenshots viewed.
- [ ] Накопленный общий Java/Python/root прогон; review; docs/CHANGELOG/own artifact pins/local release. Не повторять дорогие прогоны без причины.

## Session ruling

User explicitly authorized continuous autonomous implementation, commits and local releases without per-step permission; latest request directly authorizes this change. No extra design/plan approval gate. Reuse existing linked worktree. Initial correction to remove HBM architecture superseded before implementation, no HBM disabling files created.