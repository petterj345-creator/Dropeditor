# MythicDropEditor

[![Build](https://github.com/YOUR_USERNAME/MythicDropEditor/actions/workflows/build.yml/badge.svg)](https://github.com/YOUR_USERNAME/MythicDropEditor/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Paper](https://img.shields.io/badge/Paper-1.21.x-blue)](https://papermc.io)
[![MythicMobs](https://img.shields.io/badge/MythicMobs-5.x-red)](https://mythiccraft.io)

A **live in-game GUI editor** for MythicMobs droptables. No more hunting through YAML files: drag items in, type the drop chance, type the amount, hit save. The plugin writes the changes back to disk and reloads MythicMobs so they go live instantly.

Supports **vanilla items** and **MMOItems** out of the box.

## Features

- `/droptable` opens a paginated list of every MythicMob on your server
- Built-in **search** to filter mobs by name
- Drag and drop items from your inventory into the editor to add them as drops
- Auto-detects MMOItems by NBT and saves them as `MMOITEM{type=...;id=...}`
- Click to set drop chance (1-100%) or amount (single number or `min-max` range)
- Shift-click to remove a drop
- One save button: writes YAML, runs `/mm reload`, done
- Preserves advanced drop entries (conditions, custom items with metadata) untouched

## Requirements

| Requirement | Version |
|-------------|---------|
| Paper / Spigot | 1.21.x |
| Java | 21 |
| MythicMobs | 5.x |
| MMOItems | Optional (auto-detected) |

## Installation

Either download the latest `MythicDropEditor-x.x.x.jar` from [Releases](../../releases) or build it yourself (see below). Then drop it into your server's `plugins/` folder and restart.

## Building from source

```bash
git clone https://github.com/YOUR_USERNAME/MythicDropEditor.git
cd MythicDropEditor
mvn package
```

The compiled jar appears at `target/MythicDropEditor-1.0.0.jar`.

If Maven can't resolve `Mythic-Dist:5.6.1`, change the version in `pom.xml` to whatever MythicMobs version is on your server. Check available versions at the [Lumine repo](https://mvn.lumine.io/repository/maven-public/io/lumine/Mythic-Dist/).

## Usage

1. Run `/droptable` to open the mob list.
2. Click the sign at the bottom to filter by name.
3. Click a spawner to open that mob's drop editor.
4. To add a new drop:
   - Drag or shift-click an item from your inventory into the editor.
   - Chat prompt: **"How high should the drop chance be? Type 1-100."** → type e.g. `25`.
   - Chat prompt: **"How many? Type a number or range (e.g. 1-5)."** → type e.g. `1-3`.
5. To edit existing drops:
   - **Left-click** → change chance
   - **Right-click** → change amount
   - **Shift-click** → remove
6. Click the **emerald block** to save. The plugin writes the YAML and reloads MythicMobs.

## How drops are stored

The plugin auto-detects how your mob's drops are configured:

- **Inline drops** in `plugins/MythicMobs/Mobs/<file>.yml` → edited in place.
- **Referenced droptable** (`DropTable: SomeName`) → edits `plugins/MythicMobs/DropTables/<file>.yml` instead.

Drop lines the plugin doesn't recognize (e.g. custom MythicMobs items with metadata, conditions) are kept verbatim and shown as a counter in the info pane. **Nothing is ever deleted.**

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `mythicdropeditor.use` | Open and edit droptables | op |

## Project layout

```
src/main/java/dev/server/dropeditor/
├── MythicDropEditor.java        Main plugin class
├── command/
│   └── DroptableCommand.java    /droptable handler
├── droptable/
│   ├── DropEntry.java           One drop (vanilla or MMOItem)
│   └── DroptableManager.java    YAML read/write + MythicMobs reload
├── gui/
│   ├── GuiManager.java          Builds mob list + editor inventories
│   └── GuiListener.java         All click/drag handling
└── util/
    ├── ChatPromptManager.java   Intercepts next chat message for input
    ├── ItemBuilder.java         Fluent item creation
    └── MMOItemsHook.java        Reflection-based MMOItems detection
```

## Known limitations

- The plugin reloads ALL of MythicMobs on save, which can briefly lag the server on big setups.
- Conditions (per-player level, world, biome, etc.) aren't editable in the GUI yet. Add them manually in YAML.
- Drop entries with full MythicMobs custom-item syntax (lots of metadata) aren't editable through the GUI but are preserved.

## Contributing

PRs welcome. Open an issue first for big changes so we can discuss.

## License

MIT. See [LICENSE](LICENSE).
