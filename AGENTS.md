# Nothing But Withers

Paper plugin for Minecraft 26.3 (protocol 777). Every eligible mob spawn is replaced by a
Wither that keeps the original mob's loot table and drops no Nether Star.

## Build and run

```bash
export JAVA_HOME=/opt/jdk25
/opt/maven/bin/mvn package
```

Requires Java 25. The jar is written to `target/mob-withers-1.0.0.jar`; copy it into a Paper
server's `plugins/` directory.

```bash
/opt/maven/bin/mvn test
```

runs the unit tests (`ConversionDecisionTest` covers the eligibility matrix without a server).

## Layout

- `MobWithersPlugin` — main class; wires up the collaborators and owns `/mobwithers`.
- `SpawnListener` — `CreatureSpawnEvent`, `SpawnerSpawnEvent`, `TrialSpawnerSpawnEvent`,
  `EntitySpawnEvent`. Cancels the original and schedules the replacement.
- `ConversionService` — eligibility rules, cap checks, Wither construction, identity stamping.
- `ConversionRequest` — captures everything needed from the source mob, because the cancelled
  spawn event discards it before the replacement is built.
- `WitherIndex` — tracks converted Withers and their per-chunk/per-world counts.
- `BlockEventListener` / `SculkReplacement` — infested blocks and the shrieker-driven Warden
  replacement.
- `LootListener` / `LootService` — retained loot tables and XP.
- `WaterSpawnGuard` — the crash-prevention water/ambient cap suppression.
- `AiLimiter` / `TpsGuard` / `TerrainGuard` — performance guards.
- `BossBarManager` — hides the stacked Wither boss bars.
- `Settings` — immutable config snapshot, swapped wholesale on reload.

## Testing with RCON

Selectors need an explicit world and origin, otherwise `@e` matches nothing:

```
execute in minecraft:overworld positioned 0 -59 0 run summon minecraft:zombie ~ ~ ~
```

Notes learned the hard way:

- Spawners do not fire without a nearby player, and not in lit areas. Place one inside a dark
  box near a player before expecting conversions.
- **Infested blocks only release their silverfish to a player in survival.** Digging one in
  creative spawns nothing, so a creative-mode test will look like a silent failure.
- A converted Wither inherits the source mob's loot table, and some tables are gated on
  `killed_by_player` (blaze is). Killing with `/kill` or `/damage` without a `by <player>`
  correctly drops nothing — check the table JSON before concluding loot is broken.
- Water mobs (squid, cod, salmon, tropical fish, pufferfish, dolphin) use separate vanilla
  spawn caps that a converted Wither leaves unfilled, which becomes an unbounded spawn loop.
  `WaterSpawnGuard` zeroes those caps on startup and re-applies them on an interval;
  `/mobwithers status` reports `water caps: zeroed` when this is working.

## Boss bars

Every Wither pushes its own vanilla boss bar, and a horde stacks them until the screen is
unreadable. `BossBarManager` hides each Wither's bar at the source: the engine sets visibility
to `true` exactly once, when the Wither is constructed, so a single `setVisible(false)` keeps
it hidden for the entity's whole life. Removing players from the bar instead is *not* stable —
the engine re-adds every tracked player on each entity update.

`/mobwithers status` reports `1 aggregate bars, 0 visible viewer pairs` at steady state. If
`visible viewer pairs` climbs, suppression has regressed.
