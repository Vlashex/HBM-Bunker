# Внешние компоненты

36 внешних JAR текущего среза перечислены в `metadata/mods.lock.json`: версия, URL загрузки,
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
- [Farmer's Delight](https://modrinth.com/mod/farmers-delight) — vectorwing, MIT;
- [Thirst Was Reclaimed](https://modrinth.com/mod/thirst-was-reclaimed) — mlus, форк Thirst Was Taken/ghen, MIT.

Точные URL файлов остальных компонентов находятся в lock-файле.

Защитные стены ссылаются на текстуру установленного Outpost Zero; его PNG не
копируется. Текстуры дверей, люков и предметов SecurityCraft 1.10.2.1 включены
без изменения пикселей по MIT; затемнение V2/V3 задаёт модель. Полный текст
лицензии и атрибуция:
[Лицензии ресурсов защиты](docs/DEFENSE-ASSET-LICENSES.md).

Для v5 добавлены Inventory Sorter (cpw, GPLv3), Sodium (CaffeineMC,
Polyform Shield1.0.0), Jade (Snownee,
CC-BY-NC-SA4.0), FerriteCore (malte0811, MIT), ModernFix (embeddedt, LGPL3.0),
Block Outline Customizer (автор и исходный проект указаны в lock).
С 5.3.1 Iris и MakeUp Ultra Fast не распространяются в текущем составе.
Их исторические пины и атрибуция доступны в теге v5.3.0. Точные версии и ссылки
на исходники текущих модов находятся в lock-файле.
[Состав клиента](docs/V5-CLIENT.md).

MCP gateway использует [официальный Python SDK](https://github.com/modelcontextprotocol/python-sdk)
версии 2.3.0 (MIT). Python-зависимости перечислены с SHA-256 в
`director/requirements-windows-py312.lock`; установщик получает их с PyPI.
Готовая среда Python и пакеты третьих сторон в ZIP и Git не включены.
