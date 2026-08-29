# Ironman Profit Tracker (IPT)

Ironman Profit Tracker is a client-only Fabric mod for Minecraft 26.1.2 / Hypixel SkyBlock. It tracks selected money-making methods using **NPC sell value only**. Bazaar pricing is never used.

## Tracked sources

- Diamond Mining
- Gold Mining
- Gemstone Mining
- Mycelium Mining
- Red Sand Mining
- Endstone Mining
- Fig Tree
- Mangrove Tree
- Helix Tree

Each source defines the sellable reward forms that can actually appear in sacks, including compacted forms. Gemstone tracking currently covers NPC-sellable rough, flawed and fine gemstones.

## Runtime architecture

The runtime pipeline is deliberately small and event-driven:

```text
Fabric GAME message
    -> SacksMessageParser
    -> RewardCorrelationTracker
    -> ProfitTrackerState
    -> persistent records / HUD
```

`ProfitSource` owns source definitions, NPC prices, and mined-material compaction metadata. `SacksMessageParser` only turns Hypixel messages and hover text into structured rewards. `RewardCorrelationTracker` removes known non-MMM causes such as matching Supercraft output and marks explicit stash-to-sacks transfers. `ProfitTrackerState` owns session/candidate behavior. `HypixelLocationTracker` uses the official Hypixel Mod API location event for island-specific guards such as suppressing false Gold Mining candidates on the SkyBlock Hub. Rendering and persistence are separate from parsing and accounting.

## Profit accounting and compaction

IPT values the **final item reported by Hypixel**. It does not add the raw ingredients on top of an enchanted/compacted item and it does not subtract those ingredients again.

For Diamond Mining, for example:

- Diamond: 8 coins
- Enchanted Diamond: 1,280 coins
- Enchanted Diamond Block: 204,800 coins

The parser deduplicates repeated semantic hover payloads and repeated parsed reward lines. It also rejects a sack parse if the total number of tracked reward items exceeds the `[Sacks] +X items` headline. This is specifically designed to prevent duplicate component/hover traversal from multiplying profit.

Mining Fiesta bonus drops are also NPC-valued when they appear in Sacks: Refined Mineral is 100,000 coins and Glossy Gemstone is 200,000 coins. Refined Mineral is generic to mining, so IPT only attributes it when the event/session already identifies the mining MMM; it cannot start a source by itself. Glossy Gemstone is Gemstone-Mining-specific.

Long-running counters use saturating arithmetic so malformed input or an extreme lifetime total cannot wrap into negative values.

## Supercraft protection

`You Supercrafted <item> x<amount>!` creates a short pending craft correlation. If a sack reward line with the same normalized item name and exact amount appears within the correlation window, **that reward line only** is suppressed.

A mixed sack event is not thrown away merely because one line was crafted. This preserves legitimate mining rewards that happen to arrive in the same sack notification.

## Stash handling

The explicit Hypixel message that transfers a stash directly into sacks arms a one-shot stash context for the **next sack addition**. The context expires after 15 seconds and is consumed by that next addition instead of acting like a broad timed mode.

Stash-derived tracked rewards:

- count toward lifetime profit;
- are stored separately as recovered-from-stash profit;
- do not start a session;
- do not resume a paused session;
- do not refresh the activity timer;
- do not contribute to session profit/hour.

## Session behavior

A source must be confirmed before a session starts:

- at least 10,000 coins of NPC value; and
- either at least two eligible reward events, or one event worth at least 50,000 coins;
- confirmation events must remain within the 25-second candidate window.

IPT parses the `(Last Xs.)` suffix from each sack notification and treats it as that notification's acquisition window. The candidate's first session activity begins at `notification time - X`. If an older/atypical sack message has no `Last Xs.` suffix, IPT falls back to the historical 30-second window for that notification. Candidate-confirmation time remains part of the session; the clock is not restarted at confirmation.

Once active, the source is locked. Rewards from another source build a switch candidate using the same confirmation rules instead of immediately stealing the session. Gold Mining candidates are additionally rejected while Hypixel's official location event identifies the current instance as the SkyBlock Hub, preventing unrelated Hub drops such as Diana/Mythological-event Gold from starting that MMM.

Live profit/hour is **sample-and-hold**: it is recalculated only when an eligible sack reward for the active MMM is credited, then remains stable until the next eligible sack update. This matches Hypixel's batched reward telemetry and avoids a fake downward drift between notifications. The `Last Xs.` value is not added on every update; doing that would double-count time already present between notification timestamps.

After 40 seconds without eligible tracked activity, the session becomes **PAUSED**. The 40-second period is only a detection window: once the pause is confirmed, session duration is frozen retroactively at the last eligible tracked reward. Paused/AFK time is excluded from profit/hour and from the session duration shown when the session ends. When a new eligible sack update resumes the source, IPT ends the pause at that message's acquisition-window start (`notification time - X`), so the productive `Last Xs.` window is counted while the earlier AFK gap remains excluded.

A lobby/server boundary ends the session. IPT handles both a normal Fabric disconnect and Hypixel transfers where a JOIN can occur without a preceding disconnect. Final rate/duration accounting is clipped to the last credited sack measurement, so unmeasured time after the final sack update cannot create a fake final rate decrease. End messages are queued across the connection boundary and are only inserted after a usable new client connection/chat exists.

## Performance model

IPT does not poll chat or parse sacks every tick. Message parsing runs only when Fabric delivers a non-overlay GAME message, and unrelated messages are discarded by cheap prefix checks before regex/correlation work.

Lightweight housekeeping runs every 5 client ticks (about 4 Hz at normal 20 TPS) for candidate expiry, craft/stash expiry, pause detection and queued chat delivery. The HUD still renders normally every frame, but formatted/accounting values are snapshotted at 4 Hz and HUD rendering never emits TRACE output.

If HUD rendering itself throws a runtime exception, IPT logs it once and disables its HUD for the remainder of that game session instead of repeatedly throwing every frame.

## Diagnostics

Diagnostic levels are:

- `OFF`
- `ERRORS`
- `DEBUG`
- `TRACE`

Diagnostics are event-driven and written through the mod logger to `latest.log`; they are not spammed into Minecraft chat. TRACE is intended for short reproductions and records sack parsing, reward correlation and session transitions without logging per-frame profit/hour getters.

## HUD and configuration

The HUD uses Fabric's HUD element registry and Minecraft's 26.1 render-state extraction API.
Its border and tracked MMM name use a dynamic source accent (for example light blue for Diamond Mining and gold for Gold Mining), with matching colors in the configuration preview. Mining sessions also show a compacted-equivalent material row below the normal stats. IPT stores raw-equivalent units internally, so 2,000 Enchanted Diamonds are displayed as 12 Enchanted Diamond Blocks + 80 Enchanted Diamonds rather than leaving 2,000 at the middle tier. Gemstone colors are compacted independently before their tier counts are combined.

The Mod Menu screen uses native Minecraft `Button` widgets created during screen initialization, so normal focus, keyboard navigation and narration behavior are retained. The tracker preview remains draggable.

Options include:

- HUD enable/disable
- Profit
- Profit/hour
- Highest session profit
- Highest profit/hour
- Position
- Scale
- Diagnostic level

## Commands

```text
/ipt stats <tracked_mmm>
/ipt clearstats <tracked_mmm>
/ipt confirm
```

Short source names include `diamond`, `gold`, `gemstone`, `mycelium`, `red_sand`, `endstone`, `fig`, `mangrove`, and `helix`.

A statistics reset confirmation expires after 30 seconds.

## Persistence

IPT resolves its files through Fabric Loader's configured config directory rather than assuming the process working directory.

Files:

```text
config/ironman-profit-tracker.json
config/ironman-profit-tracker-records.json
```

Config and record writes use a temporary file plus atomic replacement where supported, with a normal replacement fallback. Loaded configuration and records are sanitized before use; invalid negative/non-finite persisted statistics do not enter the live tracker.

## Project/build conventions

The project is declared client-only in `fabric.mod.json` and uses Loom split environment source sets. Minecraft-independent utilities live in `src/main`; Minecraft client code lives in `src/client`.

Minecraft 26.1 requires Java 25. The build uses the non-obfuscated `net.fabricmc.fabric-loom` plugin and normal Gradle dependency configurations expected by the 26.1+ toolchain.

Critical profit/hour/backdate/counter/compaction arithmetic has plain JUnit Jupiter regression tests under `src/test/java`. The GitHub build workflow uses Java 25 and `gradlew build`, so those tests run as part of CI.

## Build

Requirements used by this project:

- Minecraft 26.1.2
- Java 25
- Fabric Loader 0.19.3
- Fabric API 0.155.2+26.1.2
- Hypixel Mod API 1.0.2+build.1+mc26.1 (required)
- Mod Menu 18.0.0 (optional runtime integration)

Build with the included Gradle wrapper:

```text
./gradlew build
```

The environment used to prepare this source review cannot perform the authoritative Fabric build because it does not have the project's Java 25/Fabric dependency set available locally and cannot fetch the Gradle distribution. A dependency-less Java syntax scan and the pure arithmetic regression harness are still run before packaging; the user's local Gradle build remains the final compile/API check.

## Mining Fiesta HUD counters

- Mining Fiesta bonus drops are shown only after they are actually detected: Refined Mineral appears as its own count for applicable mining sessions, and Glossy Gemstone appears for Gemstone Mining. Zero-value bonus counters do not consume HUD space.
