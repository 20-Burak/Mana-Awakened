# Mana Awakened

Manhwa-style magic cultivation for **Minecraft 1.21.1** on **NeoForge**. Meditate to gather mana, train your core and break through ten Circles, from Mortal to Transcendent Deity. Inscribe your own spells at the Inscription Altar (105 sigils, from fire bolts and meteor volleys to ore sensing and gates to other worlds), each cast with its own magic circles and gestures. Iron's Spells 'n Spellbooks, Ars Nouveau and Mahou Tsukai draw on the same mana pool when they are installed.

**Version 1.0.1** (the first public release was 1.0.0). License: [GPL-3.0](LICENSE). Author: Burak.

## Installation

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.0 or later (any 1.21.1 build) |
| Java | 21 |
| **Required** | [Player Animator](https://modrinth.com/mod/playeranimator) by KosmX, any **2.x** build for 1.21.1 (2.0.0 or later), on the client **and** the server |
| Optional | Iron's Spells 'n Spellbooks 1.21-3.4.4 or later, Ars Nouveau 5.8.2 or later, Mahou Tsukai 1.21.0-v1.35.12 or later (tested up to Iron's 1.21.1-3.16.3, Ars 5.13.3, Mahou 1.21.1-v1.36.28; newer builds are accepted and tried) |

1. Install NeoForge for Minecraft 1.21.1.
2. Put `mana_awakened-1.0.1.jar` and a Player Animator 2.x jar into the `mods` folder. A pack with Iron's Spells already has Player Animator (Iron's needs it too); keep only one copy.
3. On a server, install both on the server as well; every player needs them.

The first start writes `config/mana_awakened-client.toml` and `config/mana_awakened-server.toml`. Nothing else needs setting up: new players get a **Cultivator's Codex** in their inventory, which explains everything in the game.

## Keys

| Key | Action |
|---|---|
| **K** | Meditate on / off (on solid ground; see [Meditation](#meditation)). |
| **B** (hold 3 s) | Breakthrough to the next Circle. Needs a full core that has reached its threshold, and no fracture. |
| **N** (hold) | Spell Wheel: release over a spell to select it. |
| **U** | Cast the selected spell (hold to repeat). |
| **]** / **[** | Next / previous spell (Ü / Ğ on a Turkish keyboard). |
| **O** | Passive skills screen. |
| **J** | Quick target menu of the selected working spell (J again or Esc closes it). |
| **Shift** + mouse wheel | Steps a working spell that takes one target through your favourites. |

Every key can be rebound under *Options → Controls → Key Binds → Mana Awakened*. The defaults avoid the keys vanilla, Iron's Spells, Ars Nouveau, Mahou Tsukai and Curios use.

## How it works

### Circles and the core

You carry a **mana core**. Meditating fills it with mana from the air; once it is full it **trains** and grows. When the core has reached its Circle's **threshold** and is full, hold **B** to break through to the next Circle: a bigger core, stronger spells, new sigils and passives. There are ten Circles; at **Circle 10** (Transcendent Deity) mana is infinite, heat no longer builds up, and every spell from every mod costs nothing.

**Breakthrough outcomes**
- **Ascension**: +1 Circle, the core doubles, an arcane shockwave pushes mobs away. From the 5th Circle on, a successful roll calls a [heavenly trial](#heavenly-trials) first.
- **Mana Backlash** (the roll fails): the core empties, you take true damage (40% of your max health by default, never lethal unless the server allows it), your body heats up and the core **fractures**. A fractured core mends only while you meditate (60 s).
- **Vessel Rupture**: a mortal body cannot hold the 7th Circle. You are left at 1 HP, blinded, bleeding (no healing for 60 s), with a deep fracture. A [Primordial Dragon Heart](#primordial-dragon-heart) removes this limit.

**Success chance** = 100%, minus body heat above 50% (up to −0.5 at full heat), minus 2% per Circle you already have, plus up to +50% from [purity](#purity) and +10% for the Ancient Dragon God vessel, kept between 5% and 100%.

**Heat** rises when you cast and charge a breakthrough, and drains while you meditate. Overheating vents with fire. **Death** keeps your Circle, purity, vessel and any fracture; mana and heat are lost.

### Meditation

Press **K** while standing on **solid ground** (not in the air, in water or lava, on a ladder, riding, sleeping, flying or gliding; the message says why). You sink into a lotus pose, the camera lowers, and the meditation circles fade in under you. While you meditate:

- mana is absorbed 4 times as fast, body heat drains to 0, a full core trains and a fractured core mends;
- you cannot walk or jump. Sneaking still works, because the [refinement](#body-refinements) ritual is done crouching.

It ends when you press **K** again, and it **breaks** (with the reason on the action bar) when you press a movement or jump key, take any damage, are pushed or knocked more than 0.2 blocks or lifted, are teleported or change worlds, mount something, start flying or gliding, end up in water or lava, or log out. Under a low ceiling you stay seated until there is room to stand ("No room to stand up.").

### Training: Meditation Formations and ambient mana

Plain meditation trains the core at the base rate of your Circle. To train faster:

- **Meditation Formation**: place a **Meditation Core** and square rings of **Rune Plates** around it, all at the same height (8 stone bricks around an amethyst shard make 8 plates). When a ring is complete it forms and lights up. Stand on the core and meditate; the HUD shows "Formation tier N" (the tier of the structure, not your cultivation Circle). Right-click the core to see the formation and the next missing ring.

  | Tier | Rings | Footprint | Training |
  |---|---|---|---|
  | 1 | 1 | 3×3 | ×2 |
  | 2 | 2 | 5×5 | ×4 |
  | 3 | 3 | 7×7 | ×8 |
  | 4 | 4 | 9×9 | ×16 |
  | 5 | 5 | 11×11 | ×32 |

- **Ambient mana** (shown next to the Mana Heart; 1.0 is ordinary air) multiplies training. It is higher on **leylines** (large hidden regions of the Overworld: walk and watch the readout), in **mana-rich biomes** (cherry groves, meadows, flower forests, lush caves and mountains, and whatever a data pack adds to the tags `mana_awakened:mana_rich` / `mana_dense`), and near a **Mana Condenser**.
- **Mana Condenser**: burns **Forge Energy** (from any side) to keep a column of thick mana around it. Right-click it to set the energy per tick (0 to 10,000 FE/t), the field radius (4 to 32 blocks) and whether the area is shown in the world. The same energy over a wider field is thinner; the world's ceiling is ambient 6.
- **Crops** grow faster in thick mana (vanilla crops and modded ones that use NeoForge's growth event, such as Mystical Agriculture's seeds).

The HUD shows **▲ training ×N** while the core grows; hover over the Mana Heart (with the chat open) for the full breakdown.

### Core training in numbers

Each Circle has a **threshold** you must reach to break through, and a **cap** the core never passes. In Circle 0 the threshold is 500. After that it is **yours**: **your next threshold is five times the core you break through with; your cap is twice your threshold**. A breakthrough doubles the core, so everyone starts a Circle at 40% of their own threshold.

| Leaving Circle 0 at | Circle 1 starts at | Threshold | Cap |
|---|---|---|---|
| 500 (the threshold) | 1,000 | 2,500 | 5,000 |
| 750 | 1,500 | 3,750 | 7,500 |
| 1,000 (the cap) | 2,000 | 5,000 | 10,000 |

**Breaking through at the cap makes every later number ten times bigger per Circle instead of five**: always at the cap, the thresholds are 500, 5,000, 50,000 … 500 billion in Circle 9; always at the threshold, 500, 2,500, 12,500 … 976.6 million. Training speeds up to match: every Circle you leave full doubles the base rate of all later ones (half-full: ×1.5), so reaching the threshold takes as long on either path. What leaving full buys is the bigger core and the perfect clear. The defaults (server config, section `core`):

| | Value |
|---|---|
| First threshold | 500 (`firstThreshold`) |
| Next threshold | 5 × the core you break through with (`thresholdFactor`) |
| Cap | 2 × the threshold |
| Base rate (core per second) | 0.1 in Circles 0 and 1, then doubling each Circle; ×(1 + fullness) for every Circle behind you, Circle 0 included (`fullTrainingFactor` 2) |
| Slowdown | past the threshold the rate falls towards the cap |
| Multiplier | Meditation Formation tier × ambient mana × (Dragon Heart and refinements) × passives × purity, halved while fractured |

Plain meditation, no multipliers, from the start of a Circle to its threshold (the same on every path): Circle 0 1.1 h, 1 4.2 h, 2 10.4 h, 3 26 h, 4 65 h, 5 163 h; each later Circle 2.5 times the one before. A **perfect clear** (leaving a Circle with at least 99.5% fullness, the share of the way from the threshold to the cap) adds 5 [purity](#purity) for good and lets you choose **two** passives of that Circle.

Big numbers are shortened from a million on, to three digits: 1.23M, 45.6M, 4.56B, 7.89T (Turkish Mn, Mr, Tn). `/mana` always prints them in full.

### Purity

Purity (0 to 150) is a trait of your body: **refinements** give 100 points together and every **perfect clear** gives 5. Nothing else changes it. It is worth:

| Effect | At purity 0 / 75 / 150 |
|---|---|
| Breakthrough success chance | +0 / +25% / +50% |
| Spell damage (this mod's spells, and other mods' where they allow it) | ×1.00 / ×1.15 / ×1.30 |
| Mana absorption | ×1.00 / ×1.15 / ×1.30 |
| Core growth | ×1.00 / ×1.25 / ×1.50 |

### Body refinements

Rare materials can be absorbed **once** each. Meditate, **crouch** and hold the material in your main hand for 15 seconds.

| Refinement | Material | Circle | Takes | Grants |
|---|---|---|---|---|
| Warden Meridians | Warden Meridians (dropped by the Warden) | 3 | 1 | +60% core growth, +6 max health, +10 purity |
| Star Core | Nether Star | 4 | 1 | +80% core growth, +14 purity |
| Abyssal Eye | Abyssal Eye (dropped by the Elder Guardian) | 2 | 1 | +40% core growth, +14 purity |
| Netherite Skin | Netherite Ingot | 1 | 2 | +4 armour, +2 toughness, +10% core growth, +10 purity |
| Golden Marrow | Enchanted Golden Apple | 0 | 1 | +10 max health, +10% core growth, +6 purity |
| Undying Will | Totem of Undying | 2 | 1 | +4 max health, +2 armour, +10 purity |
| Shulker Carapace | Shulker Shell | 2 | 2 | +3 armour, +1 toughness, +5% core growth, +10 purity |
| Echo Insight | Echo Shard | 1 | 1 | +14 purity |
| Tidal Heart | Heart of the Sea | 1 | 1 | +30% core growth, +12 purity |

Refinements survive death. Data packs can change the materials through the item tags `mana_awakened:refinement/<id>` and add drop sources through the entity tags `mana_awakened:warden_meridian_bearers` and `mana_awakened:abyssal_eye_bearers`.

### Primordial Dragon Heart

The Ender Dragon drops a **Primordial Dragon Heart** (other bosses can be added with the entity tag `mana_awakened:dragon_heart_bearers`). Hold it and meditate for **30 s** without interruption:

| Vessel | How | Grants |
|---|---|---|
| Mortal Flesh | default | holds up to Circle 6 |
| Dragon Veins | first heart | Circles 7 to 10, +20 max health, immune to fire, core growth ×3 |
| Ancient Dragon God | second heart at Circle 9 or higher | +40 max health in total, +10% breakthrough chance, core growth ×5 |

Letting go of the heart, taking damage or leaving meditation starts the ritual over.

### Heavenly trials

From the **5th Circle** on, a successful breakthrough calls **guardians** around you. You ascend only when every one of them is defeated within 3 minutes while you stay within 48 blocks of where the trial began; a boss bar shows the guardians left and the time.

| Breaking into Circle | Guardians |
|---|---|
| 6 | 3 Vindicators, 2 Pillagers |
| 7 | 3 Wither Skeletons, 2 Vindicators |
| 8 | 3 Wither Skeletons, 2 Vindicators, 1 Witch |
| 9 | 4 Wither Skeletons, 3 Vindicators, 1 Witch |
| 10 | 5 Wither Skeletons, 3 Vindicators, 2 Witches |

Losing (time runs out, or you leave the arena) empties and fractures the core. Dying, logging out or the guardians vanishing is no verdict: no penalty, no ascension. Creative and spectator players and Peaceful worlds skip trials. Guardians drop nothing.

## Spells

### The Inscription Altar

Craft an **Inscription Altar** (a lectern, two amethyst shards and lapis, shapeless). It has two tabs.

- **Trials of Wisdom**: a sigil is understood, not bought. Choose a sigil you have the Circle for and solve its **ring of glyphs**: place the glyphs so every clue holds. Each puzzle has exactly one answer.
- **Workshop**: the sigils you know and your **spell book** (up to 24 spells). Create spells from a sigil, name them, and shape them with **modifiers**: Power, Range, Multi-cast, Pierce, Chain, Radius, Quicken, Efficiency, Linger, Echo, Leech, Knockback, Ignite, Frostbite and Devastation. Power and Range go up to 99; every other modifier is capped at your Circle. The editor shows the cost, cooldown and damage as you change them (− / +: 1, Shift 5, Ctrl 25, Ctrl+Shift the maximum).

Spell power and price both grow with your Circle, the power much faster: a spell is far better at a higher Circle.

### 105 sigils

Seventy-one **fighting sigils**: seventeen kinds (beam, strike, ward, restore, blink, nova, meteor, pillar, cone, wave, barrage, rain, vortex, sanctuary, buff, curse, dash) in ten elements (arcane, fire, frost, storm, earth, wind, light, nature, water, shadow), no two alike. Thirty-four **working spells**:

| Group | Spells |
|---|---|
| Finding the way | Wayfinder, Structure Compass, Ore Sense, Seer's Sight |
| Travelling | Homeward, Set Anchor / Recall Anchor, Rift Walk, Chrono Step, Dimension Gate |
| Mining and harvest | Earthbreaker, Tunnel Bore, Vein Reaver, Lumberjack Spirit, Harvest Rite, Mana Magnet, Forge Touch, Terraform |
| Building | Stone Rampart, Starlight Bridge, Ice Path, Spring, Wisp Light, Volcano |
| Sky and time | Weather Weave, Summon Tempest, Eternal Winter, Dayspring, Nightfall |
| Time and fate | Rewind, Second Life, Shadow Veil, Updraft, Hissing Charge |

Working spells that look for something take **targets** from the server's real registries, other mods' ores and structures included: Ore Sense finds up to 10 ores at once, each glowing in its own colour; Structure Compass and Rift Walk look for the nearest of up to 5 structures. Choose targets in the Workshop, or anywhere with the **quick target menu (J)**, which also keeps your recent and starred targets. A working spell that cannot do its work gives its mana back and says why.

### The elements change the world

With a spell's **Environment** switch on, its element remakes the ground where it lands: fire fuses sand and stone into glass and magma, deep cold turns ground and lava to ice and obsidian, storms call real lightning and thunderstorms, earth heaves up spears of rock, water floods and quenches lava, nature greens the land (and its curses wither it). What spells borrow from the world (lava pools, floods, spears, bridges) goes back by itself. Spells whose **Destruction** switch is on dig craters. Servers can switch all of it off (see [Configuration](#configuration)); terrain changes respect spawn protection and protection mods.

### How a cast looks

Every kind of spell has its own show and its own body gesture: a magic circle opens, the spell takes shape, travels and hits. A **meteor** opens a circle in the sky on your side of the target, a rock condenses in it and falls on a comet tail; a **nova** condenses a ball before your hands and throws it (aimed at the sky it bursts in the air at the end of its range); a **barrage** opens a small circle per projectile and releases them in turn, each curving into the creature it seeks. The hit lands when the show says it does, on the spot you aimed at when you cast. **Multi-cast** opens all its circles first and then launches in sequence; **Echo** repeats the volley as a second wave. Mana and the cooldown are paid at the cast; while your last circle is still opening, the next circle-opening spell is refused (instant spells such as a ward or a heal never are).

Glows, fireballs and blasts make way for the camera, so even a Circle-10 meteor volley never paints the whole screen.

## Passive skills

Every Circle has five passives (fifty in all); one per Circle can be active (two for a Circle you left with a perfect clear). Craft a **Passive Scroll** (paper, a themed ingredient and a catalyst for the Circle), right-click it to learn the passive, and choose it on the passive screen (**O**). Examples: *Hearth* keeps you fed from mana, *Skybound* lets you fly at a mana cost, *Mana Barrier* turns damage into mana loss. The Codex lists all fifty with their recipes.

## Shared mana with other magic mods

With **Iron's Spells 'n Spellbooks**, **Ars Nouveau** or **Mahou Tsukai** installed, their mana *is* your core: their maximum is your capacity, their own regeneration is off (meditation refills the pool), and every spell they cast is paid from the core and heats your body.

- **Their costs grow with your core**: a spell costing `amount` takes `amount × max(1, 0.1 × (capacity / 100)^0.9)` from the core (0.85 for Mahou Tsukai). A 10-mana Firebolt costs 10 up to a capacity of about 1,300, about 34 at 5,000 and about 144 at 25,000. Iron's own spell screens still show the unscaled cost; the real price shows as a rising "-34" on the Mana Heart, and the Mana Heart's tooltip and `/mana` show the multiplier ("External spells cost ×3.38"). The factor 0.1 is `integration.externalCostScale` (1.0 brings back the ten times steeper scaling of 1.0.0).
- **Their damage grows with your Circle**: +0% (Circles 0 to 2), +20% (3 to 4), +50% (5 to 6), +100% (7 to 9), +150% (10), times your purity bonus, at most ×4. Iron's (through its spell power) and Ars Nouveau get it; Mahou Tsukai has no hook for it.
- At **Circle 10** their spells are free.
- `/mana` lists the mods sharing the pool; `integration.unifyMagicMods = false` in the server config switches the bridge off, and `unifyIrons`, `unifyArs`, `unifyMahou` switch one mod off at a time (each mod then keeps its own mana).
- If an installed build lacks something the bridge needs, only that part (or that mod's bridge) is switched off, with one warning in the log; a build newer than the tested ones gets one log line and is tried anyway.

If MurimBodyTempering is installed as well, set its mana mode to `OFF` so only one mod owns the pool.

## HUD

The **Mana Heart** (top-left by default) shows the core, a mana gauge, a heat arc and one ring per Circle, with the Circle, mana, core ("Core 1,000 / 2,500 · max 5,000": core, threshold and cap), purity, heat and ambient mana beside it. Every cast shows what it really cost (a fading ghost on the gauge and a rising number); steady drains such as flight show as one "-2.5/s" readout. A **tempered health bar** replaces the hearts when your health grows large. Hover over the Mana Heart with the chat open for the training and purity breakdowns.

Client config (`config/mana_awakened-client.toml`, also in *Mods → Mana Awakened → Config*, applied at once):

| Key | Default | |
|---|---|---|
| `hud.style` | `HEART` | `HEART`, `SIMPLE` (a compact readout) or `OFF` |
| `hud.look` | `CALM` | the Circle-10 heart: `CALM` (one hue drifting through the spectrum) or `CLASSIC` (a rainbow) |
| `hud.hudAnchor` | `TOP_LEFT` | `TOP_LEFT`, `TOP_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_RIGHT` |
| `hud.hudOffsetX` / `hudOffsetY` | 0 / 6 | pixels from that corner |
| `hud.hudScale` | 1.0 | 0.5 to 2.0 |
| `hud.hudTextPlate` | true | a faint plate behind the text |
| `hud.temperedHealthBar` / `temperedArmorBar` | false / false | the tempered bars |
| `world_fx.level` | `FULL` | spell and aura effects in the world: `FULL`, `REDUCED` or `OFF` |
| `world_fx.auraBrightness` | 1.0 | 0.1 to 2.0 |
| `quick_targets.scrollModifier` | `SHIFT` | the key held with the mouse wheel for the quick targets (`SHIFT`, `CONTROL`, `ALT` or `OFF`) |

Your target colours and recent / starred targets are kept in `config/mana_awakened-target-colors.json` and `config/mana_awakened-quick-targets.json`.

## Configuration

The server config `mana_awakened-server.toml` (synced to clients) holds every balance number. The ones most servers care about:

| Key | Default | What it does |
|---|---|---|
| `magic.giveCodexOnFirstJoin` | true | new players get the Codex |
| `magic.spellsAffectEnvironment` / `spellTerrainLimit` | true / 1500 | whether spells may change terrain, and how many blocks one cast may change |
| `magic.spellsMayDestroy` / `spellDestructionLimit` | true / 3000 | whether spells with their Destruction switch on may break blocks, and how many per cast |
| `magic.spellDestructionRadius` / `spellDestructionHardness` | 14 / 50 | the widest crater, and the hardest block it can break (obsidian is 50) |
| `magic.spellsMayChangeSky` | true | weather and time-of-day spells |
| `magic.spellsMayTeleport` | true | travel spells (home, anchors, structures, other worlds) |
| `breakthrough.chargeSeconds` / `damageInterrupts` | 3 / true | the breakthrough hold |
| `backlash.damageFraction` / `canKill` | 0.4 / false | the failed-breakthrough damage |
| `trial.enabled` / `fromCircle` / `seconds` / `difficulty` | true / 5 / 180 / 1.0 | heavenly trials |
| `core.thresholdFactor` / `fullTrainingFactor` | 5.0 / 2.0 | the next threshold per core broken through with, and the rate bonus of a full Circle |
| `world.tierGrowth` | 2.0 | training per Meditation Formation tier (to the power of the tier) |
| `world.leylinesEnabled` / `maxAmbient` | true / 6.0 | leylines and the ambient ceiling |
| `world.condenserMaxEnergy` / `condenserMaxRadius` | 10000 / 32 | the Mana Condenser's limits |
| `world.cropGrowthBoost` | true | crops grow faster in thick mana |
| `integration.unifyMagicMods` | true | share one mana pool with Iron's / Ars / Mahou |
| `integration.unifyIrons` / `unifyArs` / `unifyMahou` | true / true / true | share the pool with that mod (ignored when `unifyMagicMods` is false) |
| `integration.externalCostScale` | 0.1 | multiplies the cost scaling of other mods' spells (1.0 = the scaling of 1.0.0) |
| `integration.ironsCostExponent` / `arsCostExponent` / `mahouCostExponent` | 0.9 / 0.9 / 0.85 | how other mods' costs grow with the core |
| `meditation.logStarts` | false | one log line per meditation start (for server admins) |

The rest (the core-training model, purity bonuses, heat, regeneration, the Dragon Heart, refinements) is commented in the file itself.

## Commands

| Command | Who |
|---|---|
| `/mana` | Anyone: your status and breakthrough chance. |
| `/mana info <player>` | Operators |
| `/mana set circle\|capacity\|vessel\|mana\|heat <players> <value>` | Operators (purity cannot be set; it comes from refinements and perfect clears) |
| `/mana set circle <players> <circle> <fullness>` | Operators, for testing a path: every Circle before left at `fullness` (0 = at the threshold, 1 = at the cap), with the threshold that leads to and its start core; `/mana set circle @s 9 1` is the player who always broke through at the cap (threshold 500B) |
| `/mana fill <players>` · `/mana heal <players>` · `/mana reset <players>` | Operators |
| `/mana refine give\|remove <players> <refinement\|all>` | Operators |
| `/mana trial start\|stop <players>` | Operators |
| `/mana sigil give <players> <sigil\|all>` · `/mana sigil forget <players>` | Operators: unlock sigils without the trial (with a spell of each), or forget every sigil |
| `/mana passive give <players> <passive\|all>` · `/mana passive forget <players>` | Operators |
| `/mana cheat all\|unlock\|spells\|cooldowns <players>` | Operators, for testing: `all` is Circle 10, every sigil, passive and refinement and a spell of each sigil |
| `/mana cheat modifiers <players> <level>` | Operators, for testing: sets every modifier of every spell **except Range** to `level` (within each modifier's cap). Range keeps the level it had, so the aim point stays near you; raise it in the Workshop if you want it. |

## Compatibility

- Runs on every NeoForge build for Minecraft 1.21.1 (21.1.0 and later).
- Works on its own; Iron's Spells, Ars Nouveau and Mahou Tsukai are optional and share the mana pool when present. Their own minimum NeoForge versions apply.
- Tested with Iron's Spells 1.21-3.4.4 to 1.21.1-3.16.3, Ars Nouveau 5.8.2 to 5.13.3 and Mahou Tsukai 1.21.0-v1.35.12 to 1.21.1-v1.36.28 (the oldest and newest builds on Modrinth, and the ones it was built with). Older builds are refused at start by NeoForge with a message naming the version needed; newer ones are always accepted.
- Player Animator is required on both sides (it draws the meditation pose and the casting gestures, layered under Iron's own cast animations).
- Ores, structures and worlds of other mods can be targeted by the working spells.
- Terrain changes go through spawn protection and block-break events, so protection mods can veto them.

## Save safety

Your progress is stored with your player data (`mana_awakened:mana_data`, `spell_book`, `passives`). Adding the mod to an existing world starts everyone as a Mortal. Each value is read on its own: a damaged value falls back to its default and never stops a world from loading. Health, armour and flight bonuses are temporary modifiers re-applied from your data, so removing the mod leaves nothing behind.

## License and source

Mana Awakened is free software under the [GNU General Public License v3.0](LICENSE). Building it yourself: see [DEVELOPING.md](DEVELOPING.md). The history of the internal pre-release versions is in [ROADMAP.md](ROADMAP.md).
