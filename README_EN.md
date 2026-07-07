# Chunk Scanner

**Client-side Progressive Chunk Scanner** — Asynchronously scans loaded chunks in Minecraft, working with extensible analyzers to automatically discover and log items of interest.

[![License: GNU AGPL v3](https://img.shields.io/badge/License-GNU%20APGL%20v3-green.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-blue.svg)](https://minecraft.net/)
[![Fabric Loader](https://img.shields.io/badge/Fabric%20Loader-%E2%89%A50.14.24-orange.svg)](https://fabricmc.net/)

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Command Reference](#command-reference)
- [Built-in Analyzers](#built-in-analyzers)
- [Database Browser](#database-browser)
- [GUI](#gui)
- [Configuration System](#configuration-system)
- [Xaero Waypoint Integration](#xaero-waypoint-integration)
- [Build](#build)
- [Project Structure](#project-structure)
- [License](#license)

---

## Overview

Chunk Scanner is a pure client-side Fabric mod that asynchronously scans loaded chunks within the player's view distance in the background. Unlike traditional chunk scanners, it **does not force-load new chunks**, so it has virtually no impact on servers and won't cause client lag.

The core design philosophy is extensibility: by registering different **Analyzers**, you can perform multiple types of scans on the same batch of chunks, with data stored independently without interference. Two practical analyzers are built in: **Sign Scanner** and **QShop Scanner**.

All scan results are persisted in a custom compact binary format and can be viewed, filtered, sorted, and exported through the built-in dual-axis scrollable GUI database browser.

---

## Features

- **Async Progressive Scanning** — Process chunks on background threads, zero main-thread lag
- **Adaptive Rate Control** — Dynamically adjusts scan speed based on per-tick actual cost, balancing performance and speed
- **Multi-task Parallelism** — Run multiple independent scan tasks simultaneously without interference
- **Extensible Analyzer Architecture** — Easily register custom analyzers via the `ChunkAnalyzer` interface
- **Compact Binary Database** — Custom `.dat` format with string pool compression and atomic writes
- **Context-aware Storage** — Automatically distinguishes between single-player worlds and multiplayer servers, data isolated by save
- **Dual-axis Scrollable GUI** — Database browser supports both vertical and horizontal scrolling for wide tables
- **Specialized Views** — Different analyzer types provide dedicated data parsing views (Sign, QShop trade panels)
- **QShop Filter & Sort** — Filter by buy/sell mode, price range, quantity range; sort by multiple fields
- **Xaero Waypoint Integration** — Click coordinates in the database browser to create Xaero waypoints
- **Cloth Config + ModMenu** — Optional dependencies providing a graphical configuration interface
- **Full I18n** — Supports Simplified Chinese and English

---

## Installation

### Prerequisites

| Component | Version |
|-----------|---------|
| Minecraft | 1.20.1 |
| Fabric Loader | ≥ 0.14.24 |
| Fabric API | Any version |
| Java | ≥ 17 |

### Optional Dependencies

| Mod | Purpose |
|-----|---------|
| [Cloth Config](https://modrinth.com/mod/cloth-config) | Graphical configuration UI |
| [Mod Menu](https://modrinth.com/mod/modmenu) | Mod menu entry |
| [Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap) / [World Map](https://modrinth.com/mod/xaeros-world-map) | Waypoint integration |

### Installation Steps

1. Ensure Fabric Loader and Fabric API are installed
2. Download `chunkscanner-<version>.jar`
3. Place it in the `.minecraft/mods/` directory
4. Launch the game and type `/cs help` to verify installation

---

## Quick Start

### 1. Scan Signs

```
/cs task begin sign
```

This will automatically scan all signs within view in the background, and save the results to the database.

### 2. Scan QShop Stores

```
/cs task begin qshop
```

Scans QShop-format shop signs on chests/barrels.

### 3. View Scan Status

```
/cs task status
```

### 4. Browse Scan Results

```
/cs db gui
```

In the GUI, select a database file and choose the corresponding view to see detailed data.

### 5. Stop Scanning

```
/cs task stop <id>
```

---

## Command Reference

All commands support both `/chunkscanner` and `/cs` aliases. Most parameters support Tab auto-completion.

### Task Management

| Command | Description |
|---------|-------------|
| `/cs task begin <analyzer> [id] [config...]` | Start a scan task |
| `/cs task stop <id>` | Stop a specific task |
| `/cs task pause <id>` | Pause a task |
| `/cs task resume <id>` | Resume a paused task |
| `/cs task stopall` | Stop all tasks |
| `/cs task status` | Show status of all tasks |
| `/cs task list` | List all available analyzers |
| `/cs task gui` | Open task management interface |
| `/cs task reload [restart]` | Hot-reload configuration |
| `/cs help` | Show help |

### Database Management

| Command | Description |
|---------|-------------|
| `/cs db gui` | Open database browser |
| `/cs db open [id]` | Directly open a specific database |
| `/cs db delete <id>` | Delete a database file |
| `/cs db reboot <id>` | Resume scanning from an existing database |
| `/cs db list` | List all database files |

### Launch Parameters

`/cs task begin` can accept task-level configurations in `key=value` format:

```
/cs task begin qshop myScan revisit=30 tasks=8 radius=2.0 wpName=Shops wpInit=¥ wpGroup=shops
```

Supported parameters:

| Parameter | Type | Description | Default |
|-----------|------|-------------|---------|
| `revisit` | int | Minimum revisit interval (seconds) | 60 |
| `tasks` | int | Max chunks processed per tick | 16 |
| `initTasks` | int | Initial chunks per tick | 2 |
| `targetNs` | int | Target per-tick cost (nanoseconds) | 5,000,000 |
| `flush` | int | Flush interval (ticks) | 100 |
| `threads` | int | Worker thread count | 2 |
| `radius` | float | Scan radius multiplier | 1.0 |
| `wpName` | string | Xaero waypoint name | Selected coordinate |
| `wpInit` | string | Waypoint initials | Target |
| `wpGroup` | string | Waypoint group | chunkscanner |

---

## Built-in Analyzers

### Sign Analyzer

- Scans all sign block entities in chunks (normal signs + hanging signs)
- Records both front and back text content
- Automatically cleans up removed sign records on re-scan

### QShop Analyzer

- Identifies QShop-format shop signs attached to containers (chests, barrels, etc.)
- Parses the 4-line standard format: owner, buy/sell mode + quantity, item name, unit price
- Supports three trade statuses: **Selling**, **Buying**, **Out of Stock**
- Supports system shops (quantity shown as "Infinite")
- Cross-chunk container detection for data accuracy

---

## Database Browser

Open the database browser via `/cs db gui`, which includes the following features:

### File List Page

- Browse all saved database files
- Display file name, analyzer type, file size
- Support **Resume Scan** (continue scanning from existing data)
- Support **Deleting** database files
- **Open Folder** button to open the storage directory

### KV View Page

Three view types, switchable based on analyzer type:

| View | Applicable Analyzer | Description |
|------|---------------------|-------------|
| **Raw** | All | Display raw key-value pairs in hex format |
| **Sign** | sign | Parse sign text, show location and both sides' content |
| **QShop** | qshop | Structured table: location, owner, type, quantity, item, price |

### QShop Filter System

- **Mode Filter**: All / Selling / Buying
- **Sort**: None / Price Ascending / Price Descending / Quantity Ascending / Quantity Descending
- **Text Filter**: Dimension, owner, item name (substring match supported)
- **Range Filter**: Price range, quantity range

### Export

- Export data to TSV format via the `Save As` button

---

## GUI

### Task Management UI (`/cs task gui`)

- Top action bar: Database button, scan ID input, analyzer selector, Create/Advanced buttons
- Active task list: each row shows analyzer name, scan ID, colored status bar, statistics
- Status bar color coding:
  - White = Pending
  - Dark Green = Scanned with no findings
  - Bright Green = Scanned with findings
  - Dark Blue = Over-revisit with no findings
  - Bright Blue = Over-revisit with findings
  - Red = Error
  - Yellow = Findings + Errors
- Hover tooltip shows detailed statistics
- Each task row has independent pause/resume and stop buttons

### Task Configuration UI

Opened via the `Advanced...` button, provides a scrollable configuration area with 10 parameters. All fields have gray placeholders showing global defaults — leave blank to use the default value.

---

## Configuration System

### Config File Location

- Cloth Config (preferred): Open via Mod Menu → Chunk Scanner for graphical configuration
- JSON fallback: `.minecraft/config/chunkscanner.json`

### Global Config Structure

```json
{
  "defaults": {
    "minRevisitIntervalSec": 60,
    "maxTasksPerTick": 16,
    "initialTasksPerTick": 2,
    "targetTickNs": 5000000,
    "flushIntervalTicks": 100,
    "workerThreads": 2,
    "scanRadiusMultiplier": 1.0
  },
  "waypoint": {
    "name": "Selected coordinate",
    "initials": "Target",
    "group": "chunkscanner"
  }
}
```

### Config Loading Strategy

1. First tries to load via Cloth Config API
2. Falls back to JSON file if Cloth Config is not installed
3. Each scan task can independently override global defaults (task-level config)
4. Supports hot-reload via `/cs task reload`; the `restart` sub-command fully restarts all active sessions

---

## Xaero Waypoint Integration

When Xaero's Minimap or Xaero's World Map is installed, clicking a location column in the database browser creates a waypoint.

- Uses Java reflection to detect Xaero availability and API version
- Automatically compatible with multiple Xaero API versions
- Supports custom waypoint name, initials, and group
- Falls back to displaying coordinates in chat if Xaero is unavailable

---

## Data Storage

### Storage Path

```
.minecraft/
└── chunkscanner/
    ├── local/              # Single-player world data
    │   └── <world name>/
    │       └── <scanId>.dat
    └── server/             # Multiplayer server data
        └── <server address>/
            └── <scanId>.dat
```

### Database Format

Custom compact binary format (`.dat`):

```
Header → String Pool → Chunk Meta → KV Records
```

- **Header**: Magic number, version, analyzer name, scanId
- **String Pool**: String pool with intern deduplication for repeated strings
- **Chunk Meta**: Chunk metadata (last scan time, status flags)
- **KV Records**: Key-value pair data (analyzer-defined format)

Writes use atomic operations: write to `.tmp` file first, then `ATOMIC_MOVE` to `.dat`.

---

## Adaptive Rate Control

Chunk Scanner features an intelligent rate regulation mechanism:

1. Checks actual processing cost vs. target cost every tick
2. **Speed Up**: actual cost < target/2 and fully loaded → chunks per tick +1
3. **Slow Down**: actual cost > target×2 → chunks per tick -1
4. Chunks are scheduled via a priority queue sorted by `lastScanTime`, prioritizing the least recently scanned chunks
5. Each chunk has a minimum revisit interval (default 60 seconds) to avoid redundant scans

This mechanism ensures the scanner runs silently in the background without affecting game framerate.

---

## Build

### Requirements

- JDK 17+
- Gradle (use the bundled Gradle Wrapper)

### Build Steps

```bash
# Clone the repository
git clone https://github.com/billy65536/chunkscanner.git
cd chunkscanner

# Build
./gradlew build

# Output at build/libs/chunkscanner-<version>.jar
```

### Development

```bash
# Generate IDE project files
./gradlew genSources

# Run Minecraft client
./gradlew runClient
```

---

## Project Structure

```
src/main/java/com/billy65536/chunkscanner/
├── ChunkScannerMod.java              # Main entry: initialization, command registration, callbacks
├── config/
│   ├── ChunkScannerConfig.java        # Global config data model
│   ├── ConfigLoader.java              # Config load/save
│   └── TaskConfig.java                # Task-level config
├── core/
│   ├── ChunkScanner.java              # Scan engine core
│   ├── ChunkAnalyzer.java             # Analyzer interface
│   ├── ChunkDb.java                   # Generic database interface
│   ├── ScanSession.java               # Scan session state
│   ├── AnalyzeResult.java             # Analysis result
│   ├── DbViewProvider.java            # DB view provider interface + registry
│   ├── LocatedPosition.java           # World location record
│   └── CoreUtil.java                  # Utility methods
├── components/
│   ├── analyzer/
│   │   ├── SignAnalyzer.java          # Sign analyzer
│   │   └── QShopAnalyzer.java         # QShop store analyzer
│   ├── db/
│   │   ├── BinaryChunkDb.java         # Binary database implementation
│   │   └── DbFileUtil.java            # Database file utilities
│   └── view_provider/
│       ├── SignDbViewProvider.java    # Sign specialized view
│       ├── QShopDbViewProvider.java   # QShop specialized view
│       └── QShopFilterScreen.java     # QShop filter screen
├── screen/
│   ├── ChunkScannerScreen.java        # Task management GUI
│   ├── DatabaseScreen.java            # Database browser GUI
│   └── TaskConfigScreen.java          # Task configuration GUI
├── gui/
│   ├── GuiUtil.java                   # GUI common utilities
│   ├── KvPageRenderer.java            # Database page renderer
│   ├── PlaceholderTextField.java      # Placeholder text field
│   ├── ScrollManager.java             # Scroll manager
│   ├── ScrollableListPanel.java       # Scrollable list panel
│   └── ScrollbarUtil.java             # Scrollbar renderer
└── integration/
    ├── ClothConfigIntegration.java     # Cloth Config integration
    ├── ModMenuIntegration.java         # ModMenu integration
    └── XaeroWaypointHelper.java       # Xaero waypoint integration
```

---

## License

This project is open-sourced under the [GNU AGPL v3 License](LICENSE).
