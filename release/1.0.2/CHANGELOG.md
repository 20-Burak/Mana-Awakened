# 1.0.2 - no more spells from an empty core

A fix from the play-test of 1.0.1.

- **Spells of other mods start only when your core holds their whole price.** With Mahou Tsukai a spell could be cast from an almost empty core: a spell costing 232,321 went off with 1,759 in the core and took only what was there ("-1,056"). Now a Mahou Tsukai, Iron's Spells or Ars Nouveau spell is refused unless the core holds its full price at the scaled rate. When it goes off, the core pays exactly that price, once, and the number rising over the Mana Heart is the whole price. A refused spell costs nothing and does not heat your body.
- **Held and channelled spells stop when you can no longer pay.** A spell that keeps paying while it runs (Iron's Fire Breath, Mahou's Fallen Down and the like) pays every step at the scaled price and stops as soon as the core cannot pay the next one. The core never goes below zero.
- **Mahou Tsukai's own regeneration no longer leaks in.** Every few seconds it could lift Mahou's mana above what your core holds for a moment.
- **"Not enough mana in your core: this spell costs N"** now also appears over the hotbar for a refused Ars Nouveau spell and a refused Mahou Tsukai scroll, as it already did for Iron's Spells.
- Nothing else changes: the prices, `integration.externalCostScale`, the damage of other mods' spells, free spells at Circle 10 and your own spells are as in 1.0.1. Worlds and characters from 1.0.1 load as they are.
