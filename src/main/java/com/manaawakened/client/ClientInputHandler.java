package com.manaawakened.client;

import com.manaawakened.client.screen.SpellWheelScreen;
import com.manaawakened.core.ManaData;
import com.manaawakened.network.BreakthroughChargePayload;
import com.manaawakened.network.SpellSelectPayload;
import com.manaawakened.network.ToggleMeditationPayload;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellCaster;
import com.manaawakened.spell.SpellRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Turns key presses into requests. The client never decides anything itself: it asks, and the
 * server answers with a {@link com.manaawakened.network.SyncManaPayload}.
 */
public final class ClientInputHandler {
    /** Last B state we told the server about, so we only send edges (pressed / released). */
    private static boolean breakthroughHeld;

    private static final int FIRST_REPEAT_TICKS = 8;
    private static final int REPEAT_TICKS = 3;
    private static int castRepeat;

    /** Ticks a request to sit down may wait for the server's answer while the body holds still. */
    private static final int SIT_WAIT_TICKS = 10;
    private static int sitPending;
    /** Whether a movement or jump key was down last tick: only a new press breaks a meditation. */
    private static boolean movingBefore;
    /** Whether this meditation already asked to stand up (sent once). */
    private static boolean standRequested;

    private ClientInputHandler() {
    }

    /** Moves the selection to the next/previous spell and tells the server. */
    private static void cycle(SpellBook book, int direction) {
        var spells = book.spellList();
        if (spells.isEmpty() || Minecraft.getInstance().screen != null) {
            return;
        }
        int index = -1;
        for (int i = 0; i < spells.size(); i++) {
            if (spells.get(i).id() == book.selected()) {
                index = i;
            }
        }
        if (index < 0) {
            index = direction > 0 ? -1 : 0;
        }
        SpellRecipe next = spells.get(Math.floorMod(index + direction, spells.size()));
        book.select(next.id());
        PacketDistributor.sendToServer(new SpellSelectPayload(next.id(), false));
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.getConnection() == null) {
            breakthroughHeld = false;
            return;
        }
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        if (!minecraft.isPaused()) {
            data.predictClientTick();
            player.getData(AttachmentRegistries.SPELL_BOOK).predictClientTick();
            // Other players' aura clocks (charge, ritual) run between SyncAuraPayload corrections.
            if (minecraft.level != null) {
                for (var other : minecraft.level.players()) {
                    if (other != player && other.hasData(AttachmentRegistries.MANA_DATA)) {
                        other.getData(AttachmentRegistries.MANA_DATA).predictClientTick();
                    }
                }
            }
        }

        while (ModKeyMappings.SPELL_WHEEL.consumeClick()) {
            if (minecraft.screen == null) {
                minecraft.setScreen(new SpellWheelScreen());
            }
        }
        SpellBook memory = player.getData(AttachmentRegistries.SPELL_BOOK);
        while (ModKeyMappings.PASSIVES.consumeClick()) {
            if (minecraft.screen == null) {
                ClientScreens.openPassives();
            }
        }
        while (ModKeyMappings.QUICK_TARGETS.consumeClick()) {
            if (minecraft.screen == null) {
                QuickTargets.openMenu(minecraft, player);
            }
        }
        QuickTargets.tick(player);
        while (ModKeyMappings.CAST.consumeClick()) {
            if (minecraft.screen == null) {
                PacketDistributor.sendToServer(TargetPrefs.castRequest(memory));
                castRepeat = FIRST_REPEAT_TICKS;
            }
        }
        // Holding the cast key keeps casting: one request each time the spell is ready and the core can pay.
        if (minecraft.screen == null && ModKeyMappings.CAST.isDown()) {
            if (--castRepeat <= 0) {
                castRepeat = REPEAT_TICKS;
                SpellRecipe selected = memory.selectedSpell();
                if (selected != null && memory.cooldown(selected.id()) <= 0
                        && (data.isTranscendent() || data.mana() >= SpellCaster.costFor(player, data, selected))) {
                    PacketDistributor.sendToServer(TargetPrefs.castRequest(memory));
                }
            }
        } else {
            castRepeat = 0;
        }
        while (ModKeyMappings.NEXT_SPELL.consumeClick()) {
            cycle(memory, 1);
        }
        while (ModKeyMappings.PREVIOUS_SPELL.consumeClick()) {
            cycle(memory, -1);
        }

        while (ModKeyMappings.MEDITATE.consumeClick()) {
            boolean sit = !data.isMeditating();
            PacketDistributor.sendToServer(new ToggleMeditationPayload(sit, false));
            sitPending = sit ? SIT_WAIT_TICKS : 0;
        }
        if (sitPending > 0 && (data.isMeditating() || --sitPending == 0)) {
            sitPending = 0;
        }

        // Opening any screen counts as letting go, so a menu can never leave a charge running.
        boolean held = minecraft.screen == null && ModKeyMappings.BREAKTHROUGH.isDown();
        if (held != breakthroughHeld) {
            breakthroughHeld = held;
            PacketDistributor.sendToServer(new BreakthroughChargePayload(held));
        }
    }

    /**
     * A meditating body does not move. The walk and jump input is cleared before the game uses it, and a fresh press of a
     * movement or jump key asks the server to stand up (the server also breaks the meditation itself if the body moves). A key
     * already held when the meditation began has to be let go first. While a request to sit down waits for the server the body
     * already holds still, so it does not slide away from the spot it asked to sit on. Sneaking stays free: crouching absorbs a
     * refinement during meditation.
     */
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!(event.getEntity() instanceof LocalPlayer player)) {
            return;
        }
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        Input input = event.getInput();
        boolean moving = input.up || input.down || input.left || input.right || input.jumping;
        if (data.isMeditating()) {
            if (moving && !movingBefore && !standRequested) {
                standRequested = true;
                PacketDistributor.sendToServer(new ToggleMeditationPayload(false, true));
            }
            holdStill(input);
        } else {
            standRequested = false;
            if (sitPending > 0) {
                holdStill(input);
                Vec3 motion = player.getDeltaMovement();
                player.setDeltaMovement(0.0, motion.y, 0.0);
            }
        }
        movingBefore = moving;
    }

    private static void holdStill(Input input) {
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.jumping = false;
        input.forwardImpulse = 0.0F;
        input.leftImpulse = 0.0F;
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        breakthroughHeld = false;
        castRepeat = 0;
        movingBefore = false;
        standRequested = false;
        sitPending = 0;
    }
}
