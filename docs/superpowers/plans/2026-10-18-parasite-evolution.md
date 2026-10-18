# Parasite Evolution Implementation Plan

> **For agentic workers:** implement the owned tasks below; use test-driven-development and one final whole-branch review.

**Goal:** игрок видит последовательное развитие паразитов, оператор управляет фазой.
**Architecture:** отдельные сохранённые часы/политика; нативные адаптеры и волны
читают опубликованную фазу; админка использует фиксированный операторский RCON.
**Tech Stack:** Java21/NeoForge21.1.262, Python3.12, существующий HTML/CSS/JS.
**Spec:** ../specs/2026-10-18-parasite-evolution-design.md

## Global Constraints

Сохранить мир/кампанию, Java4.2 legacy schemas, модели/рецепты и клиент без шейдеров.
Миграция явно выбрана: из activeTicks. Нет новой консоли в MCP, новых тяжёлых модов,
автоматического повтора uncertain, загрузок чанков и удаления старых существ.

## Review Focus

- Пропавший обязательный save: не мигрировать повторно и не открывать поздние формы.
- Понижение при queued/restored волне: повторная проверка перед созданием.
- Нативная конверсия: запрет не должен удалять исходное существо.
- Двойной POST/таймаут: UUID квитанции не сдвигает фазу второй раз.
- Нечитаемый JSON/неизвестный entity ID: отклонить без замены предыдущего состояния.

## Tasks

1. Parent — config + pure EvolutionPolicy + EvolutionStore + EvolutionState;
   RED→GREEN unit, required marker, CoordinatorRuntime snapshot/tick/checkpoint,
   операторская команда и native migration tests. Commit при связном зелёном срезе.
2. Native adapter worker — EvolutionHooks и Evolution* mixins, собственные
   GameTests и manifest. Потребляет EvolutionState из spec; не меняет Runtime,
   конфиг/операторские команды. Проверяет настоящие пути и сохранение original.
3. Admin worker — новый admin_evolution.py, GET/POST, интерфейс и Python/DOM
   tests. Потребляет фиксированную команду/DTO spec; не меняет Java или Model/MCP.
4. Parent — фазовые pools и фильтр CinematicDirector при реальном спавне,
   JSON каталог, native tests queued/replay/понижения. Совместимый граф schema2.
5. Integration — полный unit/Python/DOM/pack, один full-pack GameTest в новом
   каталоге, короткий normal server/HTTP/RCON. Финальный свежий code review,
   исправления RED→GREEN, затем локальный релиз/документы/пины и коммиты.

Исполнение без остановок согласовано ранее пользователем. Неблокирующий вопрос
переноса получен: пересчитать по activeTicks, сохранить существующие объекты.
