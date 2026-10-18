# Director Fine-tuning Implementation Plan

> **For agentic workers:** use executing-plans for parent and focused parallel worker for independent data/evaluation. Follow TDD, one final fresh review.

**Goal:** фактическое локальное обучение адаптера выбора тактик с честным сравнением.
**Architecture:** общая схема runtime/train context; отдельные dataset/evaluation,
Docker trainer, отчёт и проверка кандидата без автоматической замены production.
**Tech Stack:** Python3.12, PyTorch CUDA FP16/PEFT/Transformers, Docker Ubuntu WSL,
существующий CPU Ollama. Точные зависимости закрепить после compatibility probe.
**Spec:** ../specs/2026-10-18-director-finetuning-design.md

## Global Constraints

Серверные лимиты/миры/Java/MCP не менять. Не читать и не печатать токены.
История выбора не является ground truth игрового успеха. Обучение только локальное.
Модель получает только разрешённые варианты, не произвольные команды.
Использовать существующий worktree; не запускать Computer Use.

## Review Focus

- Train/test leakage через перестановки и общий случай: семейства делятся сначала.
- Порядковая подсказка option0: перестановки и баланс целевых индексов.
- Смена конфигурации/каталога: hash и публичные event ID, не зашитые UUID/координаты.
- OOM/ошибка контейнера: конечный run, сохранение отчёта/артефактов, production pin прежний.
- Формальная точность на синтетике не выдаётся за лучшее поведение в реальной игре.

## Tasks

1. Parent: TDD публичного event в cinematic context; единый prompt/schema helper
   для обучения и inference; исходный pin/permissions не меняются.
2. Dataset worker: training/dataset.py и evaluation.py + tests; deterministic JSONL,
   family split/hash/метки/permutation checks; не меняет runtime/model/trainer.
3. Parent: Docker GPU compatibility probe, закреплённые зависимости/ревизия,
   реальное обучение малым адаптером; export adapter и до/после evaluation.
4. Parent: CPU/GGUF проверка пригодного кандидата, сводный отчёт с ограничениями;
   полный Python/DOM/pack срез, свежий review и отдельные коммиты. Игровой full-pack
   не нужен, если Java/игровые моды не меняются. Новые веса не включать молча.
