# Developing Mana Awakened

This is the developer's side of the repository: how to build the mod, run its tests, check it in a real window and find your way in the code. The player's guide is [README.md](README.md); the full history of the internal versions is in [ROADMAP.md](ROADMAP.md).

## Toolchain

- Java 21 (Mojang ships Java 21 with Minecraft 1.21.1).
- Gradle through the wrapper (`gradlew` / `gradlew.bat`, Gradle 9.2.1), ModDevGradle 2.0.148, NeoForge 21.1.256 to compile against, Parchment 2024.11.17 mappings.
- The finished jar declares `neoforge [21.1.0,)` and `minecraft [1.21.1,1.21.2)`: every NeoForge and Minecraft API it uses exists since the first 1.21.1 build.
- Player Animator (KosmX, mod id `playeranimator`) is a real dependency, resolved from Modrinth's Maven (`maven.modrinth:playeranimator:2.0.4+1.21.1-forge`, see `build.gradle`). It is compiled against and loaded in every run, never bundled (no jar-in-jar); players install it themselves. The jar declares `[2.0.0,3.0)`: the oldest (2.0.0+1.21.1) and the newest (2.0.4+1.21.1) NeoForge builds were each tested.

## The magic mods: from Maven, no manual jar

The bridges to the other magic mods (`compat/irons`, `compat/ars`, `compat/mahou`) import their classes, so the build compiles against them. They come from Maven, like Player Animator: a fresh clone builds with nothing copied by hand.

- **Compile** (`compileOnly`: never bundled, never a dependency of the published jar): Iron's Spells `maven.modrinth:irons-spells-n-spellbooks:1.21.1-3.16.3`, Ars Nouveau `maven.modrinth:ars-nouveau:5.13.3`, Mahou Tsukai `curse.maven:mahou-tsukai-342543:8860405` (1.21.1-v1.36.28 is on CurseForge only, so it comes through CurseMaven).
- **Runtime**: nothing, unless a build set is chosen with `-PmagicSet=oldest|ours|newest`. Without it every run (`runClient`, `runGameTestServer`) is PLAIN. The sets (all in `build.gradle`, `transitive = false`; a few Modrinth files are pinned by version id because the same version number names a Fabric or Forge file too):

| Set | Iron's Spells | Iron's Lib | Ars Nouveau | Mahou Tsukai | Curios | GeckoLib | Caelus |
|---|---|---|---|---|---|---|---|
| `oldest` | 1.21-3.4.4 | (not needed) | 5.8.2 | 1.21.0-v1.35.12 | 9.2.0+1.21.1 | 4.9.3 | 7.0.1+1.21.1 (Iron's before 3.10 needs it) |
| `ours` | 1.21.1-3.16.3 | 1.21.1-2.2.0 | 5.13.3 | 1.21.1-v1.36.28 (CurseMaven) | 9.5.1+1.21.1 | 4.9.3 | - |
| `newest` (on Modrinth) | 1.21.1-3.16.3 | 1.21.1-2.2.0 | 5.13.3 | 1.21.1-v1.36.27 | 9.5.1+1.21.1 | 4.9.3 | - |

`libs/` is no longer read by the build (it stays in `.gitignore`; an old checkout may still have jars there, they are ignored).

## Magic mod versions (surveyed for 1.0.0)

Every 1.21.1 NeoForge build of each mod on Modrinth (October 2026): Iron's Spells 24 builds (1.21-3.4.4 .. 1.21.1-3.16.3), Ars Nouveau 31 (5.8.2 .. 5.13.3), Mahou Tsukai 9 (1.21.0-v1.35.12 .. 1.21.1-v1.36.27; 1.21.1-v1.36.28, the newest, is on CurseForge only), Curios 10 (9.2.0 .. 9.5.1). CurseForge also lists a few builds older than Modrinth's oldest (Iron's 1.21-3.4.0 .. 3.4.3, Ars 1.21.0-5.0.8 .. 5.8.1); they were not tested and are below the declared ranges.

Every class, field and method the adapters touch, read from the compiled adapter bytecode (`javap -v`) and looked up by name and JVM descriptor in each build (`verify-tools/api-diff.ps1`):

| Mod | Symbols | Oldest | Ours | Newest (Modrinth) |
|---|---|---|---|---|
| Iron's Spells | 31 (ChangeManaEvent get/setNewMana, getOldMana; SpellPreCastEvent getCastSource/getSpellId/getSpellLevel; CastSource.consumesMana; SpellRegistry.getSpell; AbstractSpell.getManaCost; SpellOnCastEvent.setManaCost; MagicData getPlayerMagicData/getMana/setMana; AttributeRegistry MAX_MANA/MANA_REGEN/SPELL_POWER; SyncManaPacket TYPE and constructor; the classes; getEntity/setCanceled inherited from NeoForge) | 1.21-3.4.4: all present | 1.21.1-3.16.3: all present | = ours |
| Ars Nouveau | 26 (Max/ManaRegenCalcEvent setMax/setRegen; SpellCostCalcEvent.Pre/Post context and currentCost; SpellContext.getUnwrappedCaster; SpellDamageEvent.Pre caster and damage; CapabilityRegistry.getMana; ManaCap getCurrentMana/getMaxMana/setMaxMana/setMana/syncToClient; the classes) | 5.8.2: all present | 5.13.3: all present | = ours |
| Mahou Tsukai | 11 (DataAttachments.PLAYER_ATTACHMENTS; IMahou get/setStoredMana, get/setMaxMana, hasMagic/setHasMagic; PlayerManaManager.updateClientMahou; the classes) | 1.21.0-v1.35.12: all present (1.21.1-v1.36.0 too) | 1.21.1-v1.36.28: all present | 1.21.1-v1.36.27: all present |

No symbol is renamed or missing in any surveyed build, so the adapters call the mods directly. Each adapter still lists what it uses as features in its `FEATURES` table (`compat/ApiProbe`): at start every symbol is looked up once through `MethodHandles` (names separated by `|` are tried in order, for a future rename) and the handles are kept. A feature with something missing is switched off and the adapter goes on without it (Iron's: refusing unaffordable casts, cast tracking, the spell power bonus, the forced client sync, regeneration off; Ars: the scaled cost check, free casts at Circle 10, the damage bonus, the client sync, regeneration off; Mahou: the "has magic" flag, the client update). Without the regeneration hook, the mod's own regeneration is not credited to the core (the core value is pushed back every tick). If an essential part is missing (the mana event, the player data or the mana capability), that mod's integration is off with one warning; an adapter that throws later is switched off the same way (`compat/AdapterSet`); everything else runs on. A version outside the tested range gets one log line ("untested version ..., trying anyway") and is never refused (`MagicAdapters.TESTED`).

`neoforge.mods.toml` declares the optional dependencies open at the top, from the oldest build that passed: `irons_spellbooks [1.21-3.4.4,)`, `ars_nouveau [5.8.2,)`, `mahoutsukai [1.21.0-v1.35.12,)`, `curios [9.0.5+1.21.0,)` (Curios: the range Iron's itself declares). NeoForge refuses to start when an optional mod is installed outside its range, so a closed upper bound would lock out every future build; the game test `everyOptionalDependencyIsOpenAtTheTop` reads the built mods.toml and fails if one is closed.
## Building

```bat
.\gradlew.bat build -x test --no-daemon
```

The jar lands in `build\libs\mana_awakened-<version>.jar`. The `jar` task leaves out the game tests (`com/manaawakened/gametest/**`), the structures only they load (`data/mana_awakened/structure/**`) and every development file (`PROGRESS.md`, `DEVELOPING.md`, `tools/`, `templates/`, `release/`, `*.py`, `*.ps1`, `*.bbmodel`), and puts the GPL-3.0 `LICENSE` into the jar root. Check with `jar tf build\libs\mana_awakened-<version>.jar`: no entry starts with `com/manaawakened/gametest`.

`.\gradlew.bat build deployToInstances -x test --no-daemon` also copies the jar into the instance folders listed in `deploy_dirs`, removing older `mana_awakened-*.jar` files there first. Set `deploy_dirs` (folders separated by `;`) in your own `%USERPROFILE%\.gradle\gradle.properties`, not in the project's `gradle.properties`: they are paths on your computer and the project files are published.

Generated files:
- `src/main/resources/assets/mana_awakened/lang/en_us.json` and `tr_tr.json` are generated by `tools/gen_lang.py` from `tools/lang_data.py` (plus `tools/rite_texts.py` and `tools/spell_catalog.py`). Edit the inputs, then run `python tools/gen_lang.py`; both languages must have the same keys. The script imports Pillow for icon work it does not do here; without Pillow installed, put a stub `PIL` package on `PYTHONPATH`.
- `Sigil.java`, the sigil icons and their lang keys come from `tools/spell_catalog.py`.
- `src/main/resources/mana_awakened.png` (the 256 × 256 logo) is drawn by `tools/make_logo.ps1`.

## Testing

```bat
.\gradlew.bat runGameTestServer
```

boots a headless dedicated server and runs every game test in `com.manaawakened.gametest` (360 at 1.0.3), then exits; the log ends with `All 360 required tests passed :)`. Game tests are only registered when `neoforge.enabledGameTestNamespaces` names the mod (the dev runs set it), so they never run in a normal game, and the published jar does not contain them.

Every release is tested PLAIN once and WITH the magic mods for each build set:
- **PLAIN**: only Mana Awakened, Minecraft, NeoForge and Player Animator on the runtime classpath. The log says `No supported magic mods found; Mana Awakened runs with its own mana only.`
- **WITH the magic mods** (`-PmagicSet=oldest`, `ours`, `newest`): Iron's Spells, Ars Nouveau and Mahou Tsukai (and their libraries) of that set loaded. The log says `Mana Awakened now owns the mana of 'irons_spellbooks'` (and of `ars_nouveau`, `mahoutsukai`).

The maintainer's verification tools live **outside** the repository, in a sibling folder `verify-tools/`:

- `verify.init.gradle`, a Gradle init script:
  - adds `-Werror -Xlint:all` to every `JavaCompile` (one warning fails the build);
  - `-PplainRun` is kept for old command lines; since the magic mods come from Maven a run without `-PmagicSet` is already plain;
  - `-PpaJar=<jar>` replaces the Player Animator Maven dependency by that jar (compile and run), to test another build;
  - `-PgameDir=<folder>` makes every run use that game folder instead of `run/` (a fresh instance).

  ```bat
  .\gradlew.bat --init-script ..\verify-tools\verify.init.gradle compileJava
  .\gradlew.bat --init-script ..\verify-tools\verify.init.gradle runGameTestServer
  .\gradlew.bat --init-script ..\verify-tools\verify.init.gradle -PmagicSet=ours runGameTestServer
  ```
- `run-tests.ps1 plain` / `run-tests.ps1 magic oldest|ours|newest` runs a suite and prints only the verdict lines (`run-tests.ps1 plain "" <Player Animator jar>` against another Player Animator build).
- `mutate.ps1 -File <file> -Find "<exact text>" -Replace "<text>" [-Set ours]`: a mutation check. It hashes the file, applies the edit, runs the plain suite (or a WITH-mods set), prints the failed tests, restores the file and shows that the hash matches again. `mutate-101.ps1`, `mutate-102.ps1` and `mutate-103.ps1` run the mutations of 1.0.1, 1.0.2 and 1.0.3 one after the other into `mutate-101.log`, `mutate-102.log` and `mutate-103.log`.
- `game-driver.ps1`, dot-sourced in PowerShell: screenshots of the screen and keyboard / mouse input for the `runClient` window (`Snap`, `Burst`, `Frames`, `Sheet`, `Crop`, `Chat`, `Press`, `Click`, `Look`, `Maximize-Game`, `Size-Game`), used for the real-window checks.
- `pa/2.0.0/` and `pa/2.0.4/`: the oldest and the newest NeoForge builds of Player Animator for 1.21.1; `magic/<mod>/<version>/`: the surveyed Iron's, Ars, Mahou and Curios builds; all downloaded from Modrinth with their SHA-1 checked (`fetch-modrinth.ps1`). `api-diff.ps1` (the symbol table above) and `mods-toml.ps1` (a jar's declared dependencies).

**Rules the tests and checks enforce:** every new rule gets a game test and a mutation check (break it on purpose, see a test fail, restore it); client UI, HUD, rendering and keys are looked at in a real `runClient` window as well, because the game-test server is headless. Pure layout and timing code that the tests check lives in classes without client imports (`PopupLayout`, `CondenserLayout`, `EditorLayout`, `HudPlacement`, `HudNumbers`, `CameraNear`, `MeditationEase`, ...).

**The real window:** `.\gradlew.bat runClient -PmagicSet=ours` (with the magic mods), the world `run/saves/AltarCheck`. Back up `run/options.txt`, `run/config` and `run/saves/AltarCheck` before, and restore them byte-identically after. `run/` is not in the repository.

## Project rules

- NeoForge 1.21.1 APIs only: data attachments, `CustomPacketPayload` + `StreamCodec` registered in `RegisterPayloadHandlersEvent`, HUD through `RegisterGuiLayersEvent`. No legacy Forge APIs.
- Iron's / Ars / Mahou classes are imported only in `compat/irons`, `compat/ars` and `compat/mahou`, and only loaded after `ModList.get().isLoaded(...)`. The mod works without them.
- No per-frame allocation of geometry buffers, textures or large objects in render code: meshes are built once (`SpellMeshes`, `AuraMeshes`).
- Every id string uses `toLowerCase(Locale.ROOT)`.
- Saves are lenient: a new persistent field has a safe default and a lenient codec field; bump `ManaData.DATA_VERSION` and migrate. A broken value never throws and never corrupts a player file.
- The server validates everything a client sends and is the only authority on mana, damage, targets and progression. Bump `ModNetwork.PROTOCOL_VERSION` when a payload changes.
- All text in the language files, English and Turkish, through the generator inputs.
- Keys are `KeyMapping`s with a `KeyConflictContext`, always rebindable.

## Save format

Player data is the attachment `mana_awakened:mana_data` (with `mana_awakened:spell_book` and `mana_awakened:passives`) inside each player's `.dat`. Each field is read on its own: an unreadable value falls back to its default and an out-of-range value is clamped. The migrations of the internal versions (data formats 5, 6 and 7: the core-training model, its fullness fix and static purity; spell book format 2: targets stored by id) are described in the ROADMAP entries of those versions. Format 8 (1.0.1) stores the personal `threshold`; an older save gets the one its fullness history leads to (E_ref(circle) x the product of (1 + s)), keeps its core and has core and mana clamped to the new cap.

## Code map

```
com.manaawakened
├─ ManaAwakened            common entry point, id() helper (Locale.ROOT)
├─ client/                 keys (K, B, N, U, ], [, O, J; defaults in KeyDefaults, no client classes), input → payloads, QuickTargets (the quick menu key, Shift+wheel, the server's answers), simple readout, refinement tooltips, TargetPrefs (the catalogue the server sent, your target colours, recent and starred targets), client entry point
│   ├─ hud/                HudGeometry (arcs/rings), HudPlacement (where the mana HUD group goes, kept on screen; no client classes), HudLayout (the placement settings), SpendPopup and DrainRate (the spend number, ghost and drain readout; no client classes) + SpendFeedbackHud, HudNumbers (the mana and core lines with compact numbers; no client classes), HudTextLayout (rows, gaps, plate, keeping clear of the spell slot; no client classes), ManaHudText, ManaHeartLayer (+ TrainingTooltip, PurityTooltip), HudHover, HudTooltipScope (no client classes), TemperedBarsLayer, AmbientText, CalmHue (the calm Circle-10 hue)
│   ├─ anim/               MeditationAnimation (the lotus pose: a Player Animator layer, priority 40), MeditationStates (the seated size, the camera, the circles' fade), CastGestures (the caster's gesture per kind, timed by the cast packet)
│   ├─ fx/                 aura meshes, WorldAuraRenderer, SpellCircleRenderer (the choreography of every cast: the volley clock, the circles, the manifesting spell, its travel, impact and aftermath), SpellFxRenderer + SpellMeshes (the server-sent 3D effects), CameraNear (how glows, fireballs and crowds of blasts make way for the camera; no client classes), CondenserAreaRenderer, CircleRevealRenderer, FloatingDragonHeartRenderer
│   └─ screen/             MagicScreen (base; its click areas are reused), HitStack, EditorLayout, LabelFit, PopupLayout, CondenserLayout (the Condenser screen's text block; all four without client classes), TargetPicker, AltarScreen, QuickTargetScreen, PassiveScreen, CodexScreen, CondenserScreen, SpellWheelScreen
├─ command/ManaCommands    /mana
├─ compat/                 MagicAdapter + MagicAdapters (core, no third-party imports; the tested ranges and the per-mod switches), AdapterSet (one broken adapter is switched off alone), ApiProbe (what an adapter uses, checked once at start), ExternalCostLine
│   ├─ irons/              IronsSpellsAdapter   (only loaded when irons_spellbooks is present)
│   ├─ ars/                ArsNouveauAdapter    (only loaded when ars_nouveau is present)
│   └─ mahou/              MahouTsukaiAdapter   (only loaded when mahoutsukai is present)
├─ config/ManaConfig       server balance + client HUD config
├─ core/                   ManaData (attachment + safe codec), CoreModel (the core-training model: personal thresholds, cap, bonus, slowdown), CompactNumber (1.23M / 4.56B, three digits, cut), PurityBuffs, ExternalMagic (what other mods' spells cost the core and how hard they hit), MeditationEase (no game classes), CircleTier, VesselTier, Refinement, Refinements
├─ damage/ModDamageTypes   mana_backlash (true damage via vanilla bypass tags)
├─ effect/                 Core Fracture, Hemorrhage (no healing)
├─ entity/FloatingDragonHeart  the heart a slain Ender Dragon leaves floating above its exit portal (no gravity, no damage, taken by touch or right-click)
├─ event/CultivationEvents tick loop, login/respawn/death, damage interruption
├─ gametest/               the game tests (not in the published jar) and TestPlayers (mock players that record what the server sends them)
├─ item/                   DragonHeartItem, PassiveScrollItem, CodexItem
├─ block/                  RunePlate, MeditationCore, ManaCondenser (+ block entity: Forge Energy, settings, sync), InscriptionAltar
├─ puzzle/RingPuzzle       ring logic puzzle: generation (unique solution, no redundant clue) + check
├─ spell/                  the magic, in four layers:
│   ├─ catalogue           Sigil (105 sigils; generated by tools/spell_catalog.py), Element, Modifier, SpellRecipe, SpellStats, SigilPreview, SpellBook (attachment), SpellCaster, AltarService
│   ├─ fighting spells     SpellEffects (aimPoint: where a cast lands), SpellKinds, SpellTimelines (the five phases of every kind, Quicken, the impact tick, the volley clock), CastSpread (the seeded scatter), SpellFx / SpellSounds, DelayedTasks
│   ├─ the elements        ElementTerrain, ElementFx, SpellTerrain (budget, destruction craters), TerrainJournal (lent blocks, Rewind), Markers (glowing outlines)
│   └─ working spells      Rite, RiteTargets (ores, structures and worlds from the registries), FieldTargets (edits away from the altar), TargetLists, LegacyChoices (old saves only), TargetColors, Rites, RiteDig, RiteBuild, RiteSense, RiteSky, RiteSelf, RiteTravel, RiteEvents, SafeSpot, PlayerHistory
├─ passive/                Passive (the 50), PassiveData (attachment), PassiveHandler (effects)
├─ world/                  ManaField (ambient mana), MeditationCircle (the Meditation Formation's tiers; the class keeps its old name), CropGrowth
├─ network/                Sync (S2C), SpellCircleFx (S2C: one packet per cast), SpellFx (S2C: one 3D effect), ManaSpent (S2C), RiteTargets (S2C), SpellTargets (C2S), FieldTargets (C2S) + FieldTargetsResult (S2C), SpellSelect (C2S), ToggleMeditation (C2S), BreakthroughCharge (C2S), Condenser settings / state
├─ registry/               ModRegistries (items, blocks, the floating heart's entity type, ...), AttachmentRegistries
└─ server/                 rules, ticker, MeditationHandler, breakthrough, vessel/ritual, DragonHeartDrops (where a slain dragon's heart goes), RefinementHandler, TrialHandler, mana spending, SpendFeedback, sounds and particles
```

## Releasing

1. All checks above pass (both suites, `-Werror`, the real-window list).
2. Bump `mod_version` in `gradle.properties`, add the README / ROADMAP notes and the `release/<version>/` store files.
3. `.\gradlew.bat build -x test --no-daemon`, check `jar tf`.
4. The jar goes to the store pages; the source of that exact commit must be public (GPL-3.0).
