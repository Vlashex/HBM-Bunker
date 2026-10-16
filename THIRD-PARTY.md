# Внешние компоненты

28 внешних JAR перечислены в `metadata/mods.lock.json`: версия, URL загрузки,
размер и SHA-256. Собственный HBM Easy Balance собирается из `pack/patch/`.

JAR модов, Minecraft, NeoForge, Java и библиотеки лаунчера не входят в историю Git.
Сборщик загружает точные файлы в локальный игнорируемый кеш, а PrismLauncher
самостоятельно получает игровые компоненты при запуске импортированного профиля.

Права и лицензии сторонних модов остаются у их авторов. Создание этого
репозитория не меняет условия использования их кода и ресурсов.
Этот MVP не назначает новую публичную лицензию всему модпаку.
Уведомление собственного патча сохранено в `pack/patch/PATCH-NOTICE.txt`.

Основные страницы:

- [Overgrown Vegetation](https://www.curseforge.com/minecraft/mc-mods/overgrown-vegetation)
- [Just Hammers](https://www.curseforge.com/minecraft/mc-mods/justhammers)
- [Mining Gadgets](https://www.curseforge.com/minecraft/mc-mods/mining-gadgets)
- [Charging Gadgets](https://www.curseforge.com/minecraft/mc-mods/charging-gadgets)
- [Applied Energistics 2](https://appliedenergistics.org/)

Точные URL файлов остальных компонентов находятся в lock-файле.
