# DEAD EARTH SRP — форк 1.12.2

Текущая версия — **V1 Stable (техническая версия 1.1.0)**. Полный состав и установка:
[README-PORT.md](README-PORT.md); изменения и итоговые проверки:
[карточка 1.1.0](../docs/releases/srp-1.1.0.md).

HBM CE + оригинальные Scape and Run: Parasites + Epic Siege, пустошь,
толстые зимние наледи, собственная оборона V1–V3, автоматизация и CPU-режиссёр.
OV, Mutationcraft и SecurityCraft исключены. Для недоступных на 1.12.2 механик
сделаны лёгкие замены; [карта переноса](metadata/mod-map.json).

В 1.1.0 добавлены единый каталог ресурсов HBM, 40 осадных профилей,
восприятие игроков сквозь блоки, атаки на реле с конечными HP, Bonsai Trees
и автоматическая подача/ускорение грядок. Инструкции по механикам:
[материалы](materials/README.md), [осады](combat/README.md),
[реле](combat/README-RELAY-SIEGE.md), [деревья](trees/README.md),
[грядки](farming/README.md).

Основной запуск сервера, туннеля, ИИ и админки теперь — Docker Compose через
START-SERVER.bat/STOP-SERVER.bat; [подготовка](docker/README.md).
Непосредственно в Windows работает клиент Java 8. Полный серверный прогон,
настоящий перезапуск, клиент OpenGL и Compose E2E пройдены; протоколы и
ограничения приведены в карточке выпуска. Страница:
[srp-v1.1.0](https://github.com/Vlashex/HBM-Bunker/releases/tag/srp-v1.1.0).

Modern-сборка остаётся отдельной. Её мир нельзя переносить в этот форк,
архивы форка следует распаковывать в отдельный профиль/сервер.
`storage/` и `tools/legacy_storage.py` — исторический срез 0.1.0;
актуальный полный сборщик — **tools/legacy_port.py**.
