# Nothing But Withers

Fabric mod for Minecraft 26.3. Every eligible mob spawn is replaced by a Wither that keeps the
original mob's loot table and drops no Nether Star.

## Build and run

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
/opt/gradle/gradle-9.6.0/bin/gradle build --offline
/opt/gradle/gradle-9.6.0/bin/gradle runServer
```

The dev server exposes RCON on `127.0.0.1:25575` (password `witherestest`). Set
`-Dnothingbutwithers.debug=true` (via `JAVA_TOOL_OPTIONS`) for per-entity conversion logging.

`run/server.properties` sets `pause-when-empty-seconds=0`. Without it the server stops ticking when
no player is online, so entity tick events never fire and spawn conversions silently stop working.

## Layout

- `spawn/SpawnInterceptor` — `ServerEntityEvents.ALLOW_LOAD`; refuses the original mob and converts.
- `conversion/WitherConversion` — eligibility rules, cap checks, `convertTo`, source loot roll.
- `conversion/WitherRegistry` — per-chunk and global converted-Wither counts.
- `conversion/ConvertedWitherData` — persisted attachments (`original_entity`).
- `conversion/Withers` — maps an entity id to its loot table.
- `mixin/` — loot override, boss-bar hiding, terrain-grief clamps.
- `config/ModConfig` — `config/nothingbutwithers.json`, written with defaults on first run.

## Testing with RCON

Selectors need an explicit world and origin, otherwise `@e` matches nothing:

```
execute in minecraft:overworld positioned 0 -59 0 run summon minecraft:zombie ~ ~ ~
```

Spawners do not fire without a nearby player. Water mobs (squid, fish, dolphin, axolotl, tadpole)
are deliberately never converted: they use separate vanilla spawn caps that a converted Wither
leaves unfilled, which becomes an unbounded spawn loop.

## Performance gotchas

Never count entities through an AABB overload (`Level#getEntities(EntityTypeTest, AABB, Predicate)`)
with a box that covers a large area. It walks every entity *section coordinate* inside the box, so a
world-sized box costs tens of milliseconds per call and will hang world generation and the server
tick. Use the AABB-free overload (`Level#getEntities(EntityTypeTest, Predicate)`), which iterates the
loaded entities directly.
