package com.manaawakened.compat.mahou;

import com.manaawakened.compat.ApiProbe;
import com.manaawakened.compat.ApiProbe.Feature;
import com.manaawakened.compat.ApiProbe.Symbol;
import com.manaawakened.compat.MagicAdapter;
import com.manaawakened.compat.MagicAdapters;
import com.manaawakened.core.ExternalMagic;
import com.manaawakened.core.ManaData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import stepsword.mahoutsukai.dataattachments.DataAttachments;
import stepsword.mahoutsukai.dataattachments.mahou.IMahou;
import stepsword.mahoutsukai.mana.PlayerManaManager;

/**
 * Mahou Tsukai. It offers no mana events, so its stored mana is overwritten with the core every
 * tick and anything it took in between is charged to the core, times the cost multiplier ({@link ExternalMagic}).
 * Its own regeneration is simply overwritten. Mana is whole numbers on its side, so the mirror is the floor of the core value.
 *
 * <p>Only the stored mana, the maximum and the "has magic" flag are written. Mahou's growth bookkeeping (the
 * mana-to-use counter that its {@code drainMana} lowers and that raises its maximum when it runs out) is never
 * touched; the maximum it raises is the core's capacity again on the next tick, as before 3.4.0. Mahou Tsukai has
 * no supported hook for spell damage (no damage event, no spell power attribute), so its spells get no Circle or
 * purity damage bonus. Only loaded when {@code mahoutsukai} is present.
 *
 * <p>Version tolerance (1.0.0): everything used is listed in {@link #FEATURES} and checked once when the adapter starts
 * ({@link ApiProbe}). Without the "has magic" flag or the client update the bridge goes on without them; without the player data or
 * its mana the bridge cannot work, and Mahou keeps its own mana (one warning). Mahou Tsukai 1.21.0-v1.35.12 to 1.21.1-v1.36.28 have
 * every symbol with the same signature.
 */
public final class MahouTsukaiAdapter implements MagicAdapter {
    public static final String MOD_ID = ExternalMagic.MAHOU;

    private static final String PKG = "stepsword.mahoutsukai.";
    private static final String MAHOU = PKG + "dataattachments.mahou.IMahou";
    public static final String CORE = "mana_mirror";
    public static final String HAS_MAGIC = "has_magic_flag";
    public static final String CLIENT_SYNC = "client_sync";
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
                    Symbol.staticMethod(PKG + "mana.PlayerManaManager", "updateClientMahou", "void", "net.minecraft.server.level.ServerPlayer", MAHOU)));

    private final ApiProbe.Result api;
    private final Map<UUID, Integer> mirrored = new HashMap<>();
    /** The tick of each player's last Mahou spend. */
    private final Map<UUID, int[]> lastDrain = new HashMap<>();

    public MahouTsukaiAdapter() {
        api = ApiProbe.resolve(MahouTsukaiAdapter.class.getClassLoader(), FEATURES);
        if (!api.usable()) {
            throw new ApiProbe.Unavailable(api.offFeatures());
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
            boolean continuous = drained[0] == player.tickCount - 1;
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
        int target = (int) Math.max(0.0, Math.floor(data.mana()));
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
}
