# v5 — удобство, рендер и оптимизация

Подготовлен следующий состав для Minecraft1.21.1 / NeoForge21.1.262.
Это изменения исходников v5, установленный релиз4.3.0 автоматически не меняется.

| Компонент | Версия | Сторона | Назначение |
|---|---|---|---|
| [Inventory Sorter](https://www.curseforge.com/minecraft/mc-mods/inventory-sorter) |24.0.24|обе|Сортировка средней кнопкой мыши, перенос колёсиком|
| [Jade](https://modrinth.com/mod/jade/version/eYz2YBGT) |15.10.6|обе|Название и информация о блоке под прицелом|
| [Block Outline Customizer](https://modrinth.com/mod/block-outline-customizer/version/saq7itzZ) |1.4.0|клиент|Янтарная рамка толщиной3 без заливки и анимации|
| [Sodium](https://modrinth.com/mod/sodium/version/Pb3OXVqC) |0.6.13|клиент|Оптимизация рендера и обновления чанков|
| [Iris](https://modrinth.com/mod/iris/version/t3ruzodq) |1.8.12|клиент|Загрузчик шейдеров, закреплён вместе с Sodium0.6.13|
| [FerriteCore](https://modrinth.com/mod/ferrite-core) |7.0.3|обе|Снижение расхода памяти структурами данных|
| [ModernFix](https://modrinth.com/mod/modernfix) |5.27.26|обе|Оптимизации загрузки и расхода памяти, исправления совместимости|
| [MakeUp Ultra Fast](https://modrinth.com/shader/makeup-ultra-fast-shaders/version/pgb0JjoN) |9.5g|клиент|Шейдер с настраиваемыми эффектами|

Весь добавленный набор занимает5586988байт, около5.33MiB, без новых игровых
измерений, контента или обязательных тяжёлых библиотек. Общий состав:35внешних
JAR плюс два собственных JAR. Шейдер считается отдельно.

## Профили

По умолчанию в новом клиенте включён MakeUp, официальный `low`: тени, умеренный
свет и облака; без DOF и motion blur. Параметры взяты из `shaders.properties`
закреплённого ZIP. Для слабого ПК в архиве лежит `client-presets/weak-pc`, для
отключения шейдера — `client-presets/no-shaders`; инструкция находится там же.
Sodium остаётся включён при отключённых шейдерах.

Шейдер добавляет нагрузку GPU, он не является оптимизатором. Ни гарантированные
60FPS, ни прирост FPS по одному размеру JAR не заявляются. Видимость наледи,
следов, оборудования HBM и янтарной рамки требует общего клиентского прогона.
OptiFine, Embeddium, Fabric API, Connector и LOD-моды не добавляются.

## Воспроизводимая упаковка

Моды закреплены в `metadata/mods.lock.json`, ZIP шейдера — в
`metadata/shaders.lock.json`, оба с размером и SHA256. `side=client` исключает
рендер-моды из серверного выбора `mods_for_side(rows,"server")`; отсутствующий
`side` у старых пинов означает `both`. Это не заявление, что каждый старый
мод обязан стоять на сервере: сохраняется прежний состав.

```sh
python tools/release.py fetch
python tools/release.py fetch-shaders
python tools/release.py check
python tools/release.py build
```

`build` работает из проверенного кеша и не скачивает зависимости автоматически.
`--shader-cache` позволяет выбрать отдельный кеш. `pack/client-config/` входит
только в клиентский архив: Iris, BOC и настройки MakeUp не навязываются серверу.
Сырые аккаунты, миры и конфиги установленных профилей не используются.

Опубликованные SHA1/SHA512 Modrinth проверены дополнительно к SHA256. Для
Inventory Sorter публичный API CurseForge не предоставил hash; файл получен с
официального CDN, размер сверён с API, CDN ETag — с MD5. SHA256 закреплён после
этой проверки. Совместимость диапазона Minecraft в Iris подтверждена alias
Minecraft1.21.1→1.21 из FML4.0.45 целевого NeoForge, но не заменяет запуск игры.
