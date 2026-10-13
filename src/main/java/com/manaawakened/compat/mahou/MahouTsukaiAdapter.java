package com.manaawakened.compat.mahou;

import com.manaawakened.compat.ApiProbe;
import com.manaawakened.compat.ApiProbe.Feature;
import com.manaawakened.compat.ApiProbe.Symbol;
import com.manaawakened.compat.MagicAdapter;
import com.manaawakened.compat.MagicAdapters;
import com.manaawakened.core.ExternalMagic;
import com.manaawakened.core.ManaData;
import com.manaawakened.registry.AttachmentRegistries;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import stepsword.mahoutsukai.dataattachments.DataAttachments;
import stepsword.mahoutsukai.dataattachments.mahou.IMahou;
import stepsword.mahoutsukai.item.spells.SpellScroll;
import stepsword.mahoutsukai.item.spells.secret.GandrSpellScroll;
import stepsword.mahoutsukai.mana.PlayerManaManager;

/**
 * Mahou Tsukai. It offers no mana events, so its stored mana is overwritten with the core every
 * tick and anything it took in between is charged to the core, times the cost multiplier ({@link ExternalMagic}).
 *
 * <p>What Mahou is shown (1.0.2): not the core itself but the whole units of Mahou mana the core can pay at the multiplier
 * ({@link MagicAdapters#mirrorUnits}): a core of 1,759 at x481 shows Mahou 3. Every Mahou spell checks its own cost against that
 * ({@code PlayerManaManager.drainMana} returns the full cost or takes nothing; scrolls, staffs, circles and the per-tick drains of
 * channelled spells such as Fallen Down all compare the result with their cost and do nothing when it falls short), so Mahou refuses
 * exactly the casts the core cannot pay, and whatever it takes the core pays in full. Until 1.0.1 it was shown the core unscaled:
 * a 483-mana spell passed Mahou's check at a core of 1,759 and the core then paid what it had (the owner's "-1,056" for 232,321).
 * Mahou's maximum stays the core's capacity, because its spell costs and damage (Gandr) scale with it.
 *
 * <p>Mahou regenerates every 100 ticks in its own player tick, which may run after ours; the mirror is therefore written again after
 * every player tick ({@link SettleListener}, lowest priority), so its regeneration is never there for a cast to use. A scroll released
 * while the core cannot pay it gets the red "not enough mana" line ({@link RefusalListener}); Mahou itself refuses silently.
 *
 * <p>Only the stored mana, the maximum and the "has magic" flag are written. Mahou's growth bookkeeping (the
 * mana-to-use counter that its {@code drainMana} lowers and that raises its maximum when it runs out) is never
 * touched; the maximum it raises is the core's capacity again on the next tick, as before 3.4.0. Mahou Tsukai has
 * no supported hook for spell damage (no damage event, no spell power attribute), so its spells get no Circle or
 * purity damage bonus. Only loaded when {@code mahoutsukai} is present.
 *
 * <p>Version tolerance (1.0.0): everything used is listed in {@link #FEATURES} and checked once when the adapter starts
 * ({@link ApiProbe}). Without the "has magic" flag, the client update or the scroll costs the bridge goes on without them; without the
 * player data or its mana the bridge cannot work, and Mahou keeps its own mana (one warning). Mahou Tsukai 1.21.0-v1.35.12 to
 * 1.21.1-v1.36.28 have every symbol with the same signature.
 */
public final class MahouTsukaiAdapter implements MagicAdapter {
    public static final String MOD_ID = ExternalMagic.MAHOU;

    private static final String PKG = "stepsword.mahoutsukai.";
    private static final String MAHOU = PKG + "dataattachments.mahou.IMahou";
    private static final String SCROLL = PKG + "item.spells.SpellScroll";
    public static final String CORE = "mana_mirror";
    public static final String HAS_MAGIC = "has_magic_flag";
    public static final String CLIENT_SYNC = "client_sync";
    public static final String REFUSAL = "refusal_message";
    /** What the bridge uses of Mahou Tsukai, by feature; names separated by "|" would be tried in order (no rename is known). */
    public static final List<Feature> FEATURES = List.of(
            Feature.essential(CORE,
                    Symbol.staticField(PKG + "dataattachments.DataAttachments", "PLAYER_ATTACHMENTS", "java.util.function.Supplier"),
                    Symbol.type(MAHOU),
                    Symbol.method(MAHOU, "getStoredMana", "int"),
                    Symbol.method(MAHOU, "setStoredMana", "void", "int"),
                    Symbol.method(MAHOU, "getMaxMana", "int"),
                    Symbol.method(MAHOU, "setMaxMana", "void", "int")),
            Feature.optional(HAS_MAGIC,
                    Symbol.method(MAHOU, "hasMagic", "boolean"),
                    Symbol.method(MAHOU, "setHasMagic", "void", "boolean")),
            Feature.optional(CLIENT_SYNC,
                    Symbol.staticMethod(PKG + "mana.PlayerManaManager", "updateClientMahou", "void", "net.minecraft.server.level.ServerPlayer", MAHOU)),
            Feature.optional(REFUSAL,
                    Symbol.type(SCROLL),
                    Symbol.method(SCROLL, "getInitialManaCost", "int"),
                    Symbol.method(SCROLL, "useAction", "void", "net.minecraft.world.item.ItemStack", "net.minecraft.world.level.Level",
                            "net.minecraft.world.entity.LivingEntity", "boolean"),
                    Symbol.method(PKG + "item.spells.secret.GandrSpellScroll", "getManaCostScaling", "int", MAHOU),
                    Symbol.staticMethod(PKG + "mana.PlayerManaManager", "countMana", "int", "net.minecraft.world.entity.player.Player")));

    private final ApiProbe.Result api;
    private final Map<UUID, Integer> mirrored = new HashMap<>();
    /** The tick of each player's last Mahou spend. */
    private final Map<UUID, int[]> lastDrain = new HashMap<>();

    public MahouTsukaiAdapter() {
        api = ApiProbe.resolve(MahouTsukaiAdapter.class.getClassLoader(), FEATURES);
        if (!api.usable()) {
            throw new ApiProbe.Unavailable(api.offFeatures());
        }
        NeoForge.EVENT_BUS.register(new SettleListener());
        if (api.isOn(REFUSAL)) {
            NeoForge.EVENT_BUS.register(new RefusalListener());
        }
    }

    @Override
    public String modId() {
        return MOD_ID;
    }

    @Override
    public List<String> offFeatures() {
        return api.offFeatures();
    }

    @Override
    public void tick(ServerPlayer player, ManaData data) {
        IMahou mahou = player.getData(DataAttachments.PLAYER_ATTACHMENTS);
        if (mahou == null) {
            return;
        }
        int stored = mahou.getStoredMana();
        Integer last = mirrored.get(player.getUUID());
        if (last != null && stored < last) {
            // Mahou has no cast events: a drop right after one in the previous tick is a channelled spell's steady drain, the first is a cast
            int[] drained = lastDrain.computeIfAbsent(player.getUUID(), id -> new int[] {Integer.MIN_VALUE});
            boolean continuous = drained[0] >= player.tickCount - 1;
            drained[0] = player.tickCount;
            MagicAdapters.reportSpent(player, data, MOD_ID, last - stored, continuous);
        }
        push(player, data, mahou);
    }

    @Override
    public void forceSync(ServerPlayer player, ManaData data) {
        IMahou mahou = player.getData(DataAttachments.PLAYER_ATTACHMENTS);
        if (mahou == null) {
            return;
        }
        push(player, data, mahou);
        if (api.isOn(CLIENT_SYNC)) {
            PlayerManaManager.updateClientMahou(player, mahou);
        }
    }

    @Override
    public void forget(ServerPlayer player) {
        mirrored.remove(player.getUUID());
        lastDrain.remove(player.getUUID());
    }

    private void push(ServerPlayer player, ManaData data, IMahou mahou) {
        int target = (int) Math.min(Integer.MAX_VALUE, MagicAdapters.mirrorUnits(data, MOD_ID));
        int max = (int) Math.min(Integer.MAX_VALUE, Math.floor(data.maxMana()));
        if (mahou.getMaxMana() != max) {
            mahou.setMaxMana(max);
        }
        if (mahou.getStoredMana() != target) {
            mahou.setStoredMana(target);
        }
        if (api.isOn(HAS_MAGIC) && !mahou.hasMagic()) {
            mahou.setHasMagic(true);
        }
        mirrored.put(player.getUUID(), target);
    }

    /**
     * After every other handler of the player's tick: what Mahou took is charged and the mirror written again, so the mana Mahou
     * regenerates in its own player tick (every 100 ticks, possibly after ours) is gone before any cast can see it.
     */
    private final class SettleListener {
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void afterPlayerTick(PlayerTickEvent.Post event) {
            if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer) && player.isAlive() && MagicAdapters.isEnabled(MOD_ID)) {
                tick(player, player.getData(AttachmentRegistries.MANA_DATA));
            }
        }
    }

    /**
     * A Mahou scroll released while the core cannot pay it: Mahou refuses it itself (it is shown only what the core can pay) but
     * says nothing, so the player gets the red "not enough mana" line with the scaled cost. Only the scrolls whose cost is known
     * before the cast: those that use Mahou's plain scroll cast (their initial cost) and Gandr (its cost from the maximum).
     */
    private final class RefusalListener {
        @SubscribeEvent
        public void onRelease(LivingEntityUseItemEvent.Stop event) {
            if (!(event.getEntity() instanceof ServerPlayer player) || player.isCreative() || !MagicAdapters.isEnabled(MOD_ID)
                    || !(event.getItem().getItem() instanceof SpellScroll scroll)) {
                return;
            }
            ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
            IMahou mahou = player.getData(DataAttachments.PLAYER_ATTACHMENTS);
            if (data.isTranscendent() || mahou == null) {
                return;
            }
            int cost = scrollCost(scroll, mahou);
            if (cost > 0 && PlayerManaManager.countMana(player) < cost) {
                MagicAdapters.refuse(player, MagicAdapters.scaledCost(data, MOD_ID, cost));
            }
        }
    }

    /** What a scroll costs Mahou when cast: Gandr's cost from the maximum, a plain scroll's initial cost, 0 when not known before. */
    static int scrollCost(SpellScroll scroll, IMahou mahou) {
        if (scroll instanceof GandrSpellScroll gandr) {
            return gandr.getManaCostScaling(mahou);
        }
        try {
            boolean plain = scroll.getClass().getMethod("useAction", ItemStack.class, Level.class, LivingEntity.class, boolean.class)
                    .getDeclaringClass() == SpellScroll.class;
            return plain ? scroll.getInitialManaCost() : 0;
        } catch (NoSuchMethodException missing) {
            return 0;
        }
    }
}
