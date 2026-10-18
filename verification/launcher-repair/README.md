# Восстановление запуска CPU-модели — 2026-10-18

Исправлены выбор Docker Engine, разрешение docker.exe, проверка pin и
сохранение диагностики supervisor. Текущая установка использует Ubuntu/WSL;
Docker Desktop отдельно обслуживает playit. Кеши и модели сохранены.

Проверки:

- Корневой unittest:18/18; director unittest:140/140.
- tools/release.py check:5.5.1,36 внешних модов,134 файла patch.
- Установленный model.ps1 start -Backend wsl: Pinned CPU model is ready;
  повторный start не пересоздал работающий контейнер.
- Реальный запрос qwen3.5:4b с num_gpu=0:валидный JSON за15.03сек,
  keep_alive=0. Имя/manifest SHA сверены с models.lock.json.
- Сервер запущен именно через DEAD EARTH - Start Server.lnk.
  Мир загружен:state=ready,Java и live-host живы,host сообщает no_players.
  Адрес127.0.0.1:25565,RCON/мост слушают loopback;playit снова запущен.
- Игровые моды, мир и профиль клиента не обновлялись. GameTest/клиент
  не запускались:изменения касаются только подготовки среды и launcher.

При восстановлении понадобилась ручная штатная остановка Docker Desktop и
перезагрузка зависшего WSL, затем холодный старт Ubuntu перед Desktop.
Это действие не встроено в launcher. Секреты, runtime-файлы и полные логи
в Git не включены.
