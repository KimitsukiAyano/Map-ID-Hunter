# Map ID Hunter

A client-side Minecraft (Fabric) mod that **automates locking maps at the cartography table**
to help you obtain **round-numbered map IDs** such as `#777` or `#10000`.


---

## What it does

Minecraft keeps a counter for "the next map ID to be issued":

- Filling an empty map advances the counter by 1.
- Locking a map with a glass pane at the cartography table assigns a new ID to the locked copy and
  advances the counter by 1 more.

To land on a round number you bring the counter close to the target, then repeat
"use a map → lock it at the cartography table" until a locked copy hits the target ID.

This mod adds **two buttons** and a **target-ID field** to the cartography screen and does that
repetition for you.

| Button | Position | Behaviour |
| --- | --- | --- |
| **RUN** | below the arrow | Works only while the cartography GUI is open. **Only locks completed maps** already in your inventory — it never crafts, opens/closes the GUI, discards, or swaps hotbar slots (the fully "fair" mode). |
| **AUTO** | above the arrow | Full unattended loop: lock → discard unwanted maps → close the GUI → craft completed maps from empty maps → reopen the table → lock again, **repeating until the target ID is reached**. |

- **Target-ID field**: in the gap above the map preview. Digits only, up to 10 characters. It shares
  the same value as the config target ID and saves immediately on change. Both buttons are disabled
  while the field is empty or invalid.
- The two buttons are mutually exclusive: while one is running the other is disabled. The running
  button's label changes to `Stop`.
- Click a running button again to stop it (toggle).

---

## Requirements

| Item | Version |
| --- | --- |
| Minecraft | 1.21.11 |
| Loader | Fabric Loader 0.18.4 or newer |
| Java | 21 |
| Required | Fabric API (verified with `0.141.5+1.21.11`) |
| Optional (settings GUI) | ModMenu `17.0.0` + YACL `3.8.2+1.21.11-fabric` |

Build toolchain: Fabric Loom 1.14.10 / Yarn `1.21.11+build.6` / Gradle 9.x.

---

## Installation

1. Install Fabric Loader and **Fabric API**.
2. (Optional) Install **ModMenu** and **YACL** if you want the in-game settings screen.
3. Drop `roundmaphunter-<version>.jar` into your `mods/` folder.

The mod works without ModMenu / YACL; settings are stored in `config/roundmaphunter.json` either way.

---

## Usage

1. Bring the counter **close to** your target first. (The mod does not automate "using" maps to
   approach the target — that part is up to you.)
2. Open the cartography table and type the target ID into the **target-ID field**.
3. Press **RUN** to only lock completed maps, or **AUTO** to run the full loop from empty maps.
4. When the target ID is locked, the GUI stays open, a firework-blast sound plays, and a success
   message is logged.

> When using AUTO, try a **small gap first** (2–3 below the target).

---

## Configuration

`config/roundmaphunter.json` (or ModMenu → this mod → settings):

| Setting | Description |
| --- | --- |
| Enabled | Master on/off (also gates whether the buttons are shown). |
| Show AUTO button | Hide just the AUTO button. |
| Target map id (T) | The target ID; the same value as the in-GUI field. |
| Lock delay (ticks) | RUN's delay between locks (from 1). |
| AUTO: fast mode | Skip per-lock ID verification until near the target (assumes no one else consumes map IDs). |
| AUTO: verify threshold | Start verifying every lock once within this many locks of the target. |
| AUTO: container wait timeout | Max ticks to wait for a server container update. |
| AUTO: min action interval | Minimum ticks between actions (floor). |

---

## Building

```bash
./gradlew build
```

The output is `build/libs/roundmaphunter-<version>.jar`.

---

## Class layout (overview)

| Class | Role |
| --- | --- |
| `RoundMapHunterClient` | Client entrypoint: loads config and registers ticks. |
| `mixin.client.CartographyTableScreenMixin` | Adds the RUN/AUTO buttons and target-ID field to the cartography screen; consumes keys while the field is focused so number keys don't trigger hotbar swaps. |
| `autolock.AutoLockController` | RUN: fair, single-container locking (`clickSlot` only). |
| `autolock.AutoLoopController` | AUTO: full auto-loop including crafting and reopening (observation-based N). |
| `config.RoundMapHunterConfig` | JSON-persisted settings data. |
| `config.YaclConfigScreen` / `ModMenuIntegration` | ModMenu + YACL settings screen. |
| `RmhConstants` | Single home for tuning constants (button coords, delay floors, etc.). |

---

## License

[MIT](LICENSE)
