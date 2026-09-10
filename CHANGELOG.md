# Changelog

## 0.4.6 - transfer-first Sacks accounting

- Replace the broad v33-v35 Sacks-removal/NPC heuristics with explicit quantity accounting.
- Accumulate `You bought back <item> x<amount> ...` messages within the current Sacks reporting interval and subtract only the exact matching quantity from the next immediate NPC-context Sacks batch.
- Close direct buyback eligibility after the next Sacks notification so an unmatched buyback cannot suppress later normal mining.
- Preserve short-lived buyback history across lobby switches only for manual-transfer acknowledgement; it cannot directly suppress fresh rewards after the boundary.
- Detect manual inventory -> Sacks deposits by sampling tracked player-inventory counts while a Sacks management screen is active. Cursor-held items are included so ordinary click/drag movement is not misclassified as a deposit.
- Subtract manual deposits item-for-item before candidate/session logic, including mixed batches such as 300 total - 100 deposited = 200 eligible earnings.
- Keep manual transfer evidence for up to 45 seconds and buyback history for up to two minutes, both bounded and exact-item-only.
- Stop using old `[Sacks] -X` history as a generic future-profit suppression source, eliminating the false-negative cases found in v35 simulation.
- Add regressions for accumulated buybacks, lobby-boundary behavior, mixed legitimate+transfer batches, unrelated items, stale transfers, and chest-cleanup deposits.
- Preserve the user-provided IPT icon.

## 0.4.5 - aggregated NPC buyback remainder fix

- Fixed a buyback case where the explicit `You bought back ... xN` quantity was suppressed, but the remainder of the same aggregated Sacks reward line could still be credited as mining profit.
- An explicit buyback match now acts as transaction context for the remainder of that same item line. Extra suppression is still capped by concrete recent Sacks-removal history, avoiding a blanket ignore of unrelated mining rewards.
- Added a regression for the observed Enchanted End Stone pattern: recent removal x640, explicit buyback x64, aggregated return x640 -> 0 mining profit.

## 0.4.4 - explicit NPC buyback correlation

- Parse Hypixel's explicit `You bought back <item> x<amount> for <coins> Coins!` chat message as authoritative buyback evidence.
- Correlate the exact normalized item and quantity against Sacks additions before they reach candidate/session accounting.
- Support partial mixed lines: if a buyback says x64 and the matching Sacks line contains x100, only the 64 explicitly bought back are removed unless another concrete sack-removal correlation accounts for more.
- Make buyback correlation two-way so either message order is safe: a late buyback chat message can confirm a Sacks addition already suppressed by the v33 NPC/removal fallback without arming a stale suppression for the next real mining reward.
- Keep v33's NPC Buyback-screen and tracked sack-removal logic as fallback protection for unusual ordering or hidden/modified chat.
- Expire unmatched explicit buyback correlations after 10 seconds to avoid suppressing unrelated later mining.
- Add pure regression tests for message-before-Sacks, Sacks-before-message, partial matching, and expiry.
- Preserve the user-provided IPT icon and all 0.4.3 gameplay/HUD behavior.

## 0.4.3 - NPC buyback / sack-return protection

- Fixed NPC buyback/redeposit additions being mistaken for newly-mined profit and starting sessions with extreme profit/hour values.
- Parse tracked `[Sacks] -X items` removals for correlation only; removals are never credited as profit or activity.
- Add a conservative sack-transfer ledger: an exact quick removal/redeposit reversal is suppressed even outside an NPC screen.
- Detect Buyback-capable Hypixel NPC shop screens and suppress transactional sack additions while the tracker is inactive or paused.
- In NPC context, support partial buybacks by consuming only the quantity that can be correlated to prior removals; during an actively-running session IPT remains conservative rather than blanket-dropping delayed mining batches.
- Keep a five-second NPC-shop grace window so the server response can arrive just after the merchant screen closes.
- Add regression tests for exact redeposits, partial NPC buybacks, and stale-removal false-positive protection.
- Restore the user-provided IPT icon in the packaged resources and set the project metadata/license file to MIT as previously requested.

## 0.4.2 - Startup crash fix

- Fixed a Minecraft 26.1.2 startup crash (`Components not bound yet`) caused by constructing mining bonus `ItemStack`s during enum initialization.
- Removed all eager `ItemStack` construction from `MiningBonus` and `ProfitSource`; HUD icon stacks are now created lazily only when the HUD/config UI actually needs them after Minecraft registries/components are ready.
- No accounting, session, parser, or HUD-layout behavior changed.

## 0.4.1 - v31

### Conditional Mining Fiesta HUD counters
- Track Refined Mineral and Glossy Gemstone quantities alongside their profit contribution during an active mining session.
- Append a Refined Mineral icon/count only after Refined Mineral has actually been detected in the current session.
- Append a Glossy Gemstone icon/count only after Glossy Gemstone has actually been detected in the current Gemstone Mining session.
- Do not reserve HUD space for zero-value Fiesta counters, keeping normal non-Fiesta mining sessions compact.
- Preserve detected bonus counters for the remainder of the current session so the HUD remains a useful session summary after the drop occurs.
- Candidate sessions carry their detected Fiesta bonus counts into the confirmed session, so bonuses from the first qualifying sack batch are not lost visually.

## 0.4.0 - v30

### Gold/Diana Hub contamination guard
- Use Hypixel's official Mod API location event to identify SkyBlock Hub instances.
- Reject and clear Gold Mining candidates while on the Hub, preventing Diana/Mythological-event Gold drops from starting a Gold Mining session.
- Hold a Gold candidate briefly while location is not yet known instead of guessing, then allow normal Gold tracking on non-Hub mining islands.

### Mining Fiesta profit
- Count Refined Mineral at its current 100,000-coin NPC sell price for identified/active mining sessions.
- Count Glossy Gemstone at its current 200,000-coin NPC sell price for Gemstone Mining.
- Keep generic Refined Mineral from starting an MMM by itself because the drop does not identify which mining method produced it.
- Mining-Fiesta bonus drops participate in craft/stash correlation and sack item-count sanity checks.

### Normalized mined-material HUD
- Add a mining-only material row below the configured stat rows.
- Convert all received material forms to raw-equivalent units and display the largest compacted equivalent, regardless of whether the player actually used a Personal Compactor.
- Example: 2,000 Enchanted Diamonds display as 12 Enchanted Diamond Blocks, 80 Enchanted Diamonds, and 0 Diamonds.
- Normalize each gemstone color independently before aggregating tier counts so IPT never combines different gemstone colors into impossible compacted items.
- Add regression coverage for Diamond compaction and gemstone family separation.

### Dependency
- Add Hypixel Mod API 1.0.2 as a required client dependency for authoritative instance/location detection.

## 0.3.6 - v29

### Sack-sampled profit/hour
- Change live profit/hour to sample-and-hold behavior: recalculate only when an eligible sack reward for the active source is credited instead of continuously dividing fixed profit by a growing wall-clock duration.
- Keep the HUD rate stable between sack notifications, eliminating the artificial downward drift between otherwise regular reward batches.
- Clip session-end accounting to the last credited sack measurement (or an earlier explicit source-switch boundary) so unmeasured post-notification time does not depress the final rate.

### `Last Xs.` timing
- Parse the numeric `(Last Xs.)` suffix instead of merely accepting it in the regex.
- Use `notification time - X` as the first activity boundary for a session/candidate.
- When resuming from PAUSED, end the pause at the new sack message's acquisition-window start so the productive window is included in active duration.
- Do **not** add `X` again on every update; elapsed time between notification timestamps already includes those windows, and adding each window separately would double-count duration.
- Retain a 30-second fallback only for sack messages that do not provide a `Last Xs.` suffix.
- Add a pure arithmetic regression for sack-window start calculations.

## 0.3.5 - v28

### Build/test fix
- Fixed the `activeDurationExcludesAfkPauseWindow` regression test, which accidentally used 30 seconds of active time while asserting the expected rate for 30 minutes.
- The production timing/accounting implementation is unchanged from v27.
- JUnit Platform runtime dependencies from v27 are retained; tests now execute with internally consistent time units.

## 0.3.4 - JUnit runtime fix

- Fixed `gradlew build` failing at `:test` under Gradle 9.5.1 because the JUnit Platform launcher was missing from the test runtime classpath.
- Declare JUnit Jupiter API, Jupiter engine, and JUnit Platform launcher explicitly with matching 5.13.4 / 1.13.4 versions.
- No gameplay, accounting, session timing, HUD, parser, or chat-message behavior changed from 0.3.3.

## 0.3.3 - build reliability

- Fixed the two Gradle 9.5 deprecations reported by `processResources` and `jar` by capturing immutable values during configuration instead of reading `project` from task-execution closures.
- Replaced Fabric Loader JUnit with plain JUnit Jupiter for IPT's pure arithmetic regression tests. The tests do not touch Minecraft/Fabric state, so a Fabric launcher is unnecessary and can make `gradlew build` less reliable.
- No gameplay, accounting, pause-duration, session-message, or dynamic-HUD-color behavior changed from 0.3.2.

## 0.3.2 - session readability and source-themed HUD

### Chat readability
- Restore separator lines above and below the multi-line session-ended summary.

### Dynamic HUD accents
- Give every tracked MMM a source-specific accent color related to its primary item/theme.
- Use the active source accent for both the HUD border and the MMM name.
- Apply the same dynamic accent to the Mod Menu HUD preview.

## 0.3.1 - paused active-time sessions

### Session timing
- Exclude paused/AFK intervals from session duration and profit/hour.
- Once the 40-second inactivity threshold confirms a pause, retroactively start the pause at the last eligible tracked reward; the 40-second detection window therefore does not lower the rate.
- Resume the session clock only when the same tracked source receives another eligible reward.
- Track pause intervals explicitly so historical source-switch boundaries can clip pauses correctly instead of subtracting time that occurred after the old session logically ended.
- Session-end duration and highest profit/hour records now use the same active-only duration as the live HUD.
- Add regression coverage for clipped pause intervals and active-time profit/hour.

## 0.3.0 - architecture/correctness review

### Accounting and parsing
- Reworked sack hover traversal around `Component.toFlatList()` and direct hover events.
- Route unrelated GAME messages out through cheap prefix checks before regex/clock work.
- Precompiled reward patterns instead of rebuilding regexes in the parse hot path.
- Parse every configured reward line in a hover payload and prefer longer item names before shorter overlapping names.
- Deduplicate repeated semantic hover payloads and repeated reward lines.
- Reject impossible tracked parses whose item count exceeds the `[Sacks] +X items` headline.
- Use saturating arithmetic for reward multiplication and accumulated profit counters.

### Session correctness
- Replaced the incremental session stopwatch with one monotonic clock and direct elapsed-time calculation.
- Candidate-confirmation delay is now included in session duration instead of accidentally restarting the denominator at confirmation.
- Source-switch boundary timestamps are now actually used by session finalization.
- Select the strongest competing source from a mixed event deterministically instead of depending on enum iteration order.
- Preserve queued server-change end messages across disconnect/JOIN and only flush them after a usable connection is present.
- Treat JOIN as a hard session boundary as protection for Hypixel lobby transfers that omit a Fabric disconnect callback.

### Craft/stash correlation
- Extracted correlation logic from the session engine.
- Match Supercraft by normalized exact item name plus exact amount.
- Suppress only matching crafted reward lines rather than an entire mixed sack event.
- Make direct stash-to-sacks classification one-shot rather than a broad 15-second classification mode.
- Batch stash record persistence into one write per event.

### Performance and diagnostics
- DEBUG/TRACE are event-driven logger output only.
- Removed render/getter-driven profit/hour TRACE spam.
- Run non-render housekeeping at 4 Hz.
- Snapshot formatted HUD values at 4 Hz.
- Disable the IPT HUD after its first render exception for the current game session to prevent an exception loop.

### Fabric/project structure
- Mark the mod `environment: client`.
- Use Loom split client/main source sets and remove the no-op main initializer.
- Resolve config files using Fabric Loader's config directory.
- Use native screen buttons for focus/narration/keyboard behavior.
- Use the stable Mod Menu Maven dependency rather than an opaque artifact identifier.
- Add Fabric Loader JUnit tests for critical arithmetic.
- Keep the GitHub Java 25 build workflow as the CI build/test gate.

### Persistence
- Use shared temp-file/atomic-replacement persistence with fallback.
- Log persistence failures instead of silently swallowing them.
- Sanitize persisted statistics and configuration before use.
