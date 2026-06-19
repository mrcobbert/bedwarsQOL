# BedwarsQOL

A Hypixel BedWars quality-of-life mod for **Minecraft 1.8.9 (Forge)** featuring several modules and a custom GUI.

Adheres to the [Hypixel Allowed Modifications](https://support.hypixel.net/hc/en-us/articles/6472550754962-Hypixel-Allowed-Modifications)
policy — no automation of player actions, no unfair advantages.

## Install

1. Have a **Minecraft Forge 1.8.9** instance
2. Download the latest **`BedwarsQOL-1.8.9-forge-<version>.jar`** from the
   [**Releases**](../../releases/latest) page.
3. Drop it into your instance's `mods/` folder.
4. Launch the game. Press **Right Shift** (or run `/bedwarsqol`) to open the settings (bind can be changed in minecraft settings).

**NOTE:** If ypu want Stats and AntiSnipe features — see below, there a bit more setup.

## Optional: enable Hypixel stats

If you want Hypixel stats you must create a Cloudflare account, this is free/private and also easy. You must do this because stats and flags are served by a tiny **Cloudflare Worker that you self-host**.

## Instructions (5-10 minutes depending on how technical you are)

1. Install [Node.js](https://nodejs.org), then do this:

   ```sh
   git clone https://github.com/MrCobbert/bedwarsqol.git
   cd bedwarsqol/server/stats-worker
   npm install
   npx wrangler login        # opens a browser to authorize your Cloudflare account
   npx wrangler deploy
   ```

   `wrangler deploy` prints your Worker URL, e.g. `https://bedwarsqol-stats.<your-subdomain>.workers.dev`.

2. **(Optional) Require a token** so only your client can use the Worker: 

   ```sh
   npx wrangler secret put STATS_TOKEN      # paste any random string/password
   ```

3. In Minecraft:

```
/bedwarsqol statsurl https://bedwarsqol-stats.<your-subdomain>.workers.dev # the url it printed after you did wranger deploy
/bedwarsqol statstoken <token>     # only if you set the optional STATS_TOKEN above
```

That's **it**.
Now you can enable **Hypixel Stats** in the GUI (Hypixel Stats section).

## Features: BedwarsQOL — Features

### HUD (draggable/resizable)
  - **Potion** - active potion effects + timers
  - **Armor** - your equipped armor
  - **Info** - FPS, CPS, TPS, ping
  - **Inventory** - your stored items at a glance
  - **Gen Timers** - diamond/emerald spawn countdowns
  - **Keystrokes** - WASD + spacebar display
  
  **NOTE** - most HUD modules have an "In Game Only" option = show only during a BedWars game

### Combat
  - **Hand Position** - move/resize your held item (X / Y / Z / Scale)
  - **TNT Countdown** - fuse timer over nearby TNT (adjustable radius)
  - **Disable Esc Menu** - stop Esc opening the pause menu mid-combat

### Visuals
  - **Block Overlay** - highlight the block you look at (style / color / opacity / see-through)
  - **Tab Numeric Ping** - show latency as "123ms" instead of signal bars
  - **Hide Tab Header/Footer** - hide the server's tab header/footer text
  - **Tab + Scoreboard size scaling** - in setting

### AntiSnipe (opponent intel — needs the stats backend)
  - **Hypixel Stats** - opponents' BedWars stats on nametag + tab (level, rank, FKDR, WLR)
  - **Party Report** - announce flagged/sweaty enemies to party chat once per game

### Other
  - Keybinds : custom chat macros (press a key -> send a message)
  - Practice/Debug : spawn clientside practice dummies
  - Custom settings GUI (Right Shift or /bedwarsqol), GUI scale options


## Showcase

### GUI
![BedwarsQOL demo](assets/demo1.png)

### HUD Editor
![BedwarsQOL demo](assets/demo2.png)

## Build from source (for developers)

Requires JDK 21 (Temurin recommended) for Gradle.

```sh
./gradlew build
```

Output: `versions/1.8.9-forge/build/libs/BedwarsQOL-1.8.9-forge-<version>.jar`
(the remapped production jar; ignore the `-dev.jar`, which is for the `runClient` dev environment).

## Credits

- [Tabler Icons](https://tabler.io/icons) (MIT) — settings nav icons.
- [Inter](https://rsms.me/inter/) (SIL OFL) — UI font.

## License

MIT — see [LICENSE](./LICENSE).
