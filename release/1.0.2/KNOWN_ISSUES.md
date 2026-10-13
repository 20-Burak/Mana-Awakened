# Known issues (1.0.2)

Things a player may meet, and things that have not been checked yet.

## Behaviour

- **Iron's Spells shows the unscaled spell cost.** With a large core, Iron's spell wheel, tooltips and inscription table show the spell's own cost, while the core pays that cost times the multiplier (shown on the Mana Heart's tooltip and with `/mana` as "External spells cost ×N"; since 1.0.1 ten times lower than before). Changing Iron's own screens would need patching Iron's.
- **The other mods' own mana bars show at most part of a very big core** (they are hidden by default, `hud.hideOtherManaBars`): Iron's Spells holds at most 1,000,000 mana, Ars Nouveau and Mahou Tsukai at most 2,147,483,647. Spells are still paid from the whole core at the right price; only those bars stop at their limit (Ars Nouveau is shown at most 2,147,483,646, so a spell the core cannot pay is refused at any size). Since 1.0.2 Mahou Tsukai's bar, and Iron's while it casts, show what the core can pay at the scaled price, not the core itself.
- **Characters from 1.0.0 at Circle 2 or higher** who did not leave every Circle at its cap may now face a higher threshold than they had (it is worked out from how full they left each Circle), with a faster training speed to match. Their core is kept as it was.
- **Mahou Tsukai spells get no Circle or purity damage bonus.** Mahou Tsukai has no hook for it.
- **Iron's Spells refuses an unaffordable spell with its own line** when the core holds less than the spell's own (unscaled) cost; Mana Awakened's line with the scaled price ("this spell costs N") appears when the core holds the spell's own cost but not the scaled price.
- **Only Mahou Tsukai scrolls get the "not enough mana" line**: Mahou's staffs, weapons and magic circles are refused just the same, but silently (Mahou itself says nothing).
- **Mahou Tsukai's mana gems and batteries still pay for Mahou spells** together with the core, as Mahou intends; the mana in them was paid from the core at the price of the moment it was put in.
- **An Iron's Spells spell with a cast time** pays at the end of the cast. If something else drains the core during the cast, the spell still ends and the core pays what Iron's takes at that moment, which can be less than the full price (Iron's does not check again).
- **Two spells paid in the very same tick** (for example one of your own and a Mahou or Ars spell at once): if the second finds the core short after the first, it is not charged at all rather than charged in part.
- **Standing up under a low ceiling**: you stay seated until there is room ("No room to stand up."). A block placed into the space of a standing player is not moved out of the way.
- **A seated (meditating) player is smaller**: arrows aimed at a standing player's head pass over a meditating one.
- **Any damage breaks meditation**, even a hit fully absorbed by armour or absorption hearts.
- **Spell names stay in the language they were created in**: they are names you can edit, not translations.
- **A high-Circle Dash or Blink seen in third person** shows a bright trail between the two points and a small burst around the player for about a third of a second; the rest of the view stays clear (blasts are drawn at most about a third of their distance from the camera, and their shock rings stop short of it).
- **Large GUI scale on a very small window**: the Mana Condenser screen holds its text at every GUI scale down to an 854 × 480 window; on a smaller window the text is cut at the panel's edge instead of drawn outside it.
- **"Experimental features" warning when creating a world** with Iron's Spells, Ars Nouveau and Mahou Tsukai installed. It does not appear with Mana Awakened alone (tested); it comes from one of the other mods' world data, which one was not narrowed down.

## Other magic mods

- **Tested versions** (each with the full automated test suite for 1.0.0; 1.0.1 and 1.0.2 were tested with the newest build of each, Iron's Spells 1.21.1-3.16.3, Ars Nouveau 5.13.3 and Mahou Tsukai 1.21.1-v1.36.28): Iron's Spells 'n Spellbooks 1.21-3.4.4 (the oldest on Modrinth) and 1.21.1-3.16.3 (the newest), Ars Nouveau 5.8.2 (oldest on Modrinth) and 5.13.3 (newest), Mahou Tsukai 1.21.0-v1.35.12 (oldest on Modrinth), 1.21.1-v1.36.27 (newest on Modrinth) and 1.21.1-v1.36.28 (newest, CurseForge), Curios 9.2.0 and 9.5.1. Every class and method the integration uses is the same in all of them.
- **Older builds than those** (on CurseForge only: Iron's Spells 1.21-3.4.0 to 3.4.3, Ars Nouveau 1.21.0-5.0.8 to 5.8.1) were not tested and are refused at start by NeoForge, with a message naming the version Mana Awakened needs. Update the other mod, or remove Mana Awakened.
- **Newer builds** are always accepted. If one changes something the integration needs, only that part is switched off (or that mod keeps its own mana), with one warning in the log; the game goes on.
- **Iron's Spells before 1.21.1-3.10** was made for Player Animator 1.x; Mana Awakened requires Player Animator 2.x. The automated tests (server side) pass with Iron's 1.21-3.4.4 and Player Animator 2.0.4, but Iron's own cast animations with that pair were not looked at in a game window.
- Each mod can be taken out of the shared pool on its own in the server config (`integration.unifyIrons`, `unifyArs`, `unifyMahou`).

## Not checked yet

- **Other players' view of the meditation pose and the meditation circles** on a server or in LAN (the pose and circles are synced the same way as everything else, but nobody has looked at them from a second client yet).
- **Performance mods**: Sodium / Embeddium, Iris shaders and ImmediatelyFast have not been tested with the spell effects.
- **Knockback, explosions, teleports and ender pearls breaking meditation** were tested by the automated tests only, not by playing.
- **A real dedicated server** was not run with players; only the automated test server (which is a dedicated server).
