# MobWithers

Every mob that spawns becomes a fully functional Wither that keeps the loot table of the mob
it replaced.

Built for Paper 26.3 (protocol 777) on Java 25.

## What it does

A converted Wither has vanilla Wither behaviour — 300 health, 40 block aggro range, black and
blue skulls, Wither II on hit, block destruction and the projectile-immune armour phase below
half health — but on death it rolls the loot table of the mob it replaced. A Skeleton-Wither
drops bones and arrows, a Blaze-Wither drops blaze rods, an Enderman-Wither drops ender
pearls, and a Zombie-Wither drops rotten flesh.

The Ender Dragon is never converted, so the game stays completable.

## Spawn paths covered

| Path | Hook |
| --- | --- |
| Natural spawning, chunk generation, structure placement, breeding | `CreatureSpawnEvent` |
| Monster spawners, blaze spawners, silverfish spawners | `SpawnerSpawnEvent` + `PreSpawnerSpawnEvent` |
| Trial chambers, ominous item spawners | `TrialSpawnerSpawnEvent` |
| Mobs placed by other plugins or commands | `EntitySpawnEvent` |
| Infested blocks (silverfish) | `CreatureSpawnEvent` with reason `SILVERFISH_BLOCK` |
| Sculk shriekers (Warden) | `BlockReceiveGameEvent` (`SHRIEK`) + Warden suppression |

The Warden replacement is a persistent Wither: unlike the vanilla Warden it never despawns
and never burrows away.

## The water mob crash

Vanilla tracks water creatures under separate caps (`WATER_AMBIENT`, `WATER_CREATURE`,
`WATER_UNDERGROUND_CREATURE`, `AXOLOTL`). A converted Wither is not a water mob, so those
caps never fill and the server keeps spawning replacements — thousands of Withers per second,
then a crash.

`WaterSpawnGuard` zeroes the water and ambient caps on startup and re-applies them on an
interval, so the loop cannot start. The plugin logs a warning if something else overrides
the caps.

## Performance

Hundreds of Withers pathfinding and exploding blocks in enclosed structures will tank the
TPS without limits. The plugin applies:

- a per-world cap (`max-withers-per-world`, default 48) and a per-chunk cap (default 2);
- conversion paused while TPS is below `min-tps-to-convert`;
- AI frozen for Withers further than `ai-freeze-distance` from every player;
- explosion block removal clamped to `max-blocks-destroyed-per-explosion`, suspended while
  TPS is low, with a protected-block list.

Boss bars are the other half of the clutter problem. Every Wither pushes its own vanilla boss
bar, and a horde stacks them until the screen is unreadable. `BossBarManager` hides each
Wither bar at the source — the engine sets visibility to `true` exactly once, when the Wither
is constructed, so one `setVisible(false)` keeps it hidden for the entity's whole life — and
optionally reports the horde through a single aggregate bar instead.

## Commands

`/mobwithers <status|reload|purge|stats|halt|resume>` (alias `/mw`), permission
`mobwithers.admin`.

## Configuration

All settings live in `config.yml` and are hot-reloadable. The notable ones:

```yaml
conversion:
  natural-mobs: true
  spawner-mobs: true
  block-event-mobs: true
  external-mobs: true
  excluded-types: [WITHER, ENDER_DRAGON]
  skip-named-mobs: true
  skip-tamed-mobs: true
  skip-vehicles: true

loot:
  suppress-nether-star: true
  roll-original-loot-table: true
  use-looting: true
  overwrite-wither-experience: true
```

`skip-named-mobs` and `skip-tamed-mobs` are on by default so player pets and named mobs
survive. `excluded-types` is deliberately minimal — the point of the plugin is that every
mob becomes a Wither. Re-add a type (for example `VILLAGER` to keep trading) if you need it.

## Building

```sh
mvn package
```

The jar lands in `target/mob-withers-1.0.0.jar`. Copy it into your server's `plugins/`
directory. Requires Java 25.

## How the loot table is retained

The original mob's loot table key is stored on the Wither in its persistent data container
(`mobwithers:original_loot_table`), alongside `mobwithers:original_mob` and
`mobwithers:spawn_reason`. On death `LootListener` hands the entity to `LootService`, which
clears the vanilla drops, rolls the stored table and adds the result.

The roll uses a `LootContext` carrying the *looted entity*. This matters: without it vanilla
rejects the context with "Missing required parameters" and the table silently returns
nothing. Looting is applied by handing vanilla the killer and letting it read the
enchantment off the killer's weapon, which is how vanilla drops work; the builder's
`lootingModifier` is deprecated and non-functional in current Minecraft.
