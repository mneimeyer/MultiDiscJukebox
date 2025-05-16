# MultiDiscJukebox

**MultiDiscJukebox** is a plugin for [PaperMC](https://papermc.io) that enhances jukebox functionality in Minecraft by allowing a single block to hold and play multiple music discs, similar to a chest-sized playlist jukebox.

## Features

- Adds a new jukebox block that can store multiple discs (up to 27).
- Custom crafting recipe.
- Right-click to open and manage the disc inventory.
- Use a disc on the jukebox to automatically add it to the inventory.
- Plays one disc at a time and cycles through all stored discs.
- Breaking the block drops all contained discs.
- Supports custom discs from mods, plugins or datapacks. **Note**: Untested.
- Control playback with redstone like the vanilla jukebox. **Note**: Untested.
- Granular debug logging controlled by config.

## Installation

1. Download the plugin JAR from [Releases](https://github.com/mattneimeyer/MultiDiscJukebox/releases/latest) (or build it yourself).
2. Place the JAR in your server's `plugins/` directory.
3. Restart the server.
4. (Optional) Customize behavior in `plugins/MultiDiscJukebox/config.yml`.
5. (Optional) Modify known disc list in `plugins/MultiDiscJukebox/disc_durations.yml`.

## Usage

- Craft the MultiDisc Jukebox using 8 vanilla jukeboxes in a ring shape.
- Place the block and right-click it to open the interface.
- Insert any number of music discs (up to 27).
- It will begin playing and automatically rotate through discs.

## Commands

| Command                                      | Description                                          | Permission     |
|----------------------------------------------|------------------------------------------------------|----------------|
| `/multibox give [player] [amount]`           | Give self, or specified player, a MultiDisc Jukebox. | `multibox.admin` |
| `/multibox status`                           | Find nearest MultiDisc Jukebox.                      |                |
| `/multibox reload`                           | Reloads the plugin configuration                     | `multibox.dj`  |
| `/multibox discs`                            | List known discs and their duration.                 | `multibox.dj`  |
| `/multibox disc <add\|set> <name> <seconds>` | Add or set a disc's duration.                        | `multibox.dj` |

## Configuration

```yaml
# config.yml
# Logging configuration
logging:
  # Set to true to enable debug logging
  debug: false

  # Log levels (true to enable, false to disable)
  levels:
    info: true     # Basic information about operation
    warning: true  # Warnings that don't stop functionality
    error: true    # Errors that may affect functionality

  # Log categories (true to enable, false to disable)
  categories:
    disc_player: true    # Logs related to disc playback
    commands: true       # Logs related to commands
    block_events: true   # Logs related to block interactions
    disc_durations: true # Logs related to disc duration management```
```

## Compatibility

- Minecraft version: 1.21.4+
- Requires PaperMC (not guaranteed to work with Spigot or Bukkit)
- **Note**: Compatibility with Mod, Plugin or Datapack created discs is untested

## Building

This project uses Gradle for building.

To build:

```bash
./gradlew clean build
```

Output JAR will be in `build/libs`.

## License

This plugin is licensed under the MIT License. See the [LICENSE](./LICENSE) file for details.

## Credits

- Created by Matt Neimeyer.
- Thanks to the Paper community for their excellent API documentation and support!


---

**Note**: This plugin does not modify or replace existing Minecraft jukeboxes. It registers its own custom block using player head textures to represent the MultiDisc Jukebox.
