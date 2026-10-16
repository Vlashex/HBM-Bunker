# DEAD EARTH MCP Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans.

**Goal:** Ограниченное MCP-наблюдение и управление безопасным сценарием.
**Architecture:** Python stdio MCP → loopback TCP с токеном → кеш/ограниченная очередь
Java → серверный runtime. Мир доступен только главному потоку.
**Tech Stack:** Java 21, NeoForge 21.1.262, Python 3.12, official MCP SDK 2.3.0.
**Spec:** [MCP bridge](../specs/2026-10-16-mcp-bridge-design.md).

## Global Constraints

Не добавлять произвольные команды/файлы/спавны. Мост выключен по умолчанию.
Не менять внешние игровые моды или баланс. Не хранить токены, venv, миры и логи в Git.
Автономная работа и локальные выпуски разрешены; игровой прогон — на накопленный срез.

## Review Focus

1. Внешний адрес или устаревший дескриптор не расширяет полномочия.
2. Неавторизованный/огромный/глубокий запрос не завершает мост и не выполняет действие.
3. Таймаут до обработки не запускает отложенный запрос; повтор принятого UUID безопасен.
4. Выключение/перезапуск освобождают сокеты и запрещают прежнюю эпоху.
5. Кеш онлайн-состояния обновляется при отсутствии игроков, без обращения к миру из I/O.

### Task 1: Протокол и транспорт Java
**Files:** `bridge/BridgeProtocol.java`, `LocalBridge.java`, `ServerCallQueue.java`, tests.
- [ ] RED: токен/эпоха, unknown fields, frame/depth, лимит очереди, deadline и close.
- [ ] Реализовать ограниченный транспорт и интерфейс обработчика без Minecraft API.
- [ ] Проверить узкие тесты и коммит.

### Task 2: Интеграция с кампанией
**Files:** `bridge/BridgeController.java`, runtime/events/commands, GameTests.
- [ ] RED: отключён по умолчанию; cache projection и age; очередь безопасного сигнала.
- [ ] Реализовать операторский запуск, дескриптор и остановку; убрать секреты из ответов.
- [ ] Проверить затронутую логику и коммит, игровой прогон пока отложить.

### Task 3: MCP gateway
**Files:** `director/dead_earth_director/`, Python tests, requirements lock, startup docs.
- [ ] RED: проверка loopback-дескриптора, ограниченные аргументы/ответы, disconnect.
- [ ] Реализовать семь MCP-инструментов официальным SDK, без host/model integration.
- [ ] Проверить через настоящий SDK stdio client, зафиксировать зависимости и коммит.

### Task 4: Накопленная проверка и выпуск
- [ ] Весь Java/Python набор, свежий GameTest, полный сервер + реальный MCP round-trip.
- [ ] Сигнал, duplicate, отмена, остановка gateway, старая эпоха после перезапуска.
- [ ] Свежий финальный review, исправления RED→GREEN, фактическая сводка.
- [ ] Обновить поставку/документацию, создать локальный alpha и продолжить каталог событий.
