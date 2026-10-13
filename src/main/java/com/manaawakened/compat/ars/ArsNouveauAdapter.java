package com.manaawakened.compat.ars;

import com.hollingsworth.arsnouveau.api.event.ManaRegenCalcEvent;
import com.hollingsworth.arsnouveau.api.event.MaxManaCalcEvent;
import com.hollingsworth.arsnouveau.api.event.SpellCostCalcEvent;
import com.hollingsworth.arsnouveau.api.event.SpellDamageEvent;
import com.hollingsworth.arsnouveau.common.capability.ManaCap;
import com.hollingsworth.arsnouveau.setup.registry.CapabilityRegistry;
import com.manaawakened.compat.ApiProbe;
import com.manaawakened.compat.ApiProbe.Feature;
import com.manaawakened.compat.ApiProbe.Symbol;
import com.manaawakened.compat.MagicAdapter;
import com.manaawakened.compat.MagicAdapters;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.ExternalMagic;
import com.manaawakened.core.ManaData;
import com.manaawakened.registry.AttachmentRegistries;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Ars Nouveau. Its {@code ManaCap} mirrors the core: max mana and regeneration come from the
 * calc events (regen is 0), spends are read back every tick as the difference from the last mirrored
 * value and cost the core that difference times the cost multiplier ({@link ExternalMagic}), and a transcendent
 * core makes every spell free. The cost Ars checks before a cast ({@link SpellCostCalcEvent.Pre}) is scaled the same
 * way, so Ars refuses a spell the core cannot pay, and its spell damage ({@link SpellDamageEvent.Pre}) gets the
 * external damage multiplier of the caster's Circle and purity. Only loaded when {@code ars_nouveau} is present.
 *
 * <p>Version tolerance (1.0.0): everything used is listed in {@link #FEATURES} and checked once when the adapter starts
 * ({@link ApiProbe}); each event listener is its own small class, registered only when its feature is on. A build without the
 * regeneration event keeps its regeneration, but what it regenerates is never credited to the core (the core value is pushed back
 * every tick). Without the mana capability or the max-mana event the bridge cannot work, and Ars keeps its own mana (one warning).
 * Ars Nouveau 5.8.2 to 5.13.3 have every symbol with the same signature.
 *
 * <p>Big cores (1.0.1): Ars keeps its mana as a {@code double} (exact) but its maximum as an {@code int}, so it is shown at most
 * 2,147,483,647 of the core; spends inside that are read exactly. A scaled cost above that number is checked as that number.
 */
public final class ArsNouveauAdapter implements MagicAdapter {
    public static final String MOD_ID = ExternalMagic.ARS;
    private static final double EPSILON = 0.001;

    private static final String PKG = "com.hollingsworth.arsnouveau.";
    private static final String CAP = PKG + "common.capability.ManaCap";
    private static final String LIVING = "net.minecraft.world.entity.LivingEntity";
    public static final String CORE = "mana_mirror";
    public static final String REGEN_OFF = "regeneration_off";
    public static final String COST_CHECK = "scaled_cost_check";
    public static final String FREE_AT_TEN = "free_at_circle_10";
    public static final String DAMAGE = "spell_damage_bonus";
    public static final String CLIENT_SYNC = "client_sync";
    /** What the bridge uses of Ars Nouveau, by feature; names separated by "|" would be tried in order (no rename is known). */
    public static final List<Feature> FEATURES = List.of(
            Feature.essential(CORE,
                    Symbol.type(PKG + "api.event.MaxManaCalcEvent"),
                    Symbol.method(PKG + "api.event.MaxManaCalcEvent", "setMax", "void", "int"),
                    Symbol.staticMethod(PKG + "setup.registry.CapabilityRegistry", "getMana", CAP, LIVING),
                    Symbol.method(CAP, "getCurrentMana", "double"),
                    Symbol.method(CAP, "getMaxMana", "int"),
                    Symbol.method(CAP, "setMaxMana", "void", "int"),
                    Symbol.method(CAP, "setMana", "double", "double")),
            Feature.optional(REGEN_OFF,
                    Symbol.type(PKG + "api.event.ManaRegenCalcEvent"),
                    Symbol.method(PKG + "api.event.ManaRegenCalcEvent", "setRegen", "void", "double")),
            Feature.optional(COST_CHECK,
                    Symbol.type(PKG + "api.event.SpellCostCalcEvent$Pre"),
                    Symbol.field(PKG + "api.event.SpellCostCalcEvent$Pre", "context", PKG + "api.spell.SpellContext"),
                    Symbol.field(PKG + "api.event.SpellCostCalcEvent$Pre", "currentCost", "int"),
                    Symbol.method(PKG + "api.spell.SpellContext", "getUnwrappedCaster", LIVING)),
            Feature.optional(FREE_AT_TEN,
                    Symbol.type(PKG + "api.event.SpellCostCalcEvent$Post"),
                    Symbol.field(PKG + "api.event.SpellCostCalcEvent$Post", "context", PKG + "api.spell.SpellContext"),
                    Symbol.field(PKG + "api.event.SpellCostCalcEvent$Post", "currentCost", "int"),
                    Symbol.method(PKG + "api.spell.SpellContext", "getUnwrappedCaster", LIVING)),
            Feature.optional(DAMAGE,
                    Symbol.type(PKG + "api.event.SpellDamageEvent$Pre"),
                    Symbol.field(PKG + "api.event.SpellDamageEvent$Pre", "caster", LIVING),
                    Symbol.field(PKG + "api.event.SpellDamageEvent$Pre", "damage", "float")),
            Feature.optional(CLIENT_SYNC,
                    Symbol.method(CAP, "syncToClient", "void", "net.minecraft.server.level.ServerPlayer")));

    private final ApiProbe.Result api;
    private final Map<UUID, Double> mirrored = new HashMap<>();

    public ArsNouveauAdapter() {
        api = ApiProbe.resolve(ArsNouveauAdapter.class.getClassLoader(), FEATURES);
        if (!api.usable()) {
            throw new ApiProbe.Unavailable(api.offFeatures());
        }
        NeoForge.EVENT_BUS.register(new MaxManaListener());
        if (api.isOn(REGEN_OFF)) {
            NeoForge.EVENT_BUS.register(new RegenListener());
        }
        if (api.isOn(COST_CHECK)) {
            NeoForge.EVENT_BUS.register(new CostCheckListener());
        }
        if (api.isOn(FREE_AT_TEN)) {
            NeoForge.EVENT_BUS.register(new FreeCastListener());
        }
        if (api.isOn(DAMAGE)) {
            NeoForge.EVENT_BUS.register(new DamageListener());
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

    /** The calc events also fire on the client for the HUD; the attachment is synced there, so answer on both sides. */
    private static boolean enabled() {
        return ManaConfig.SERVER_SPEC.isLoaded() && MagicAdapters.isEnabled(MOD_ID);
    }

    private static final class MaxManaListener {
        @SubscribeEvent
        public void onMaxMana(MaxManaCalcEvent event) {
            if (event.getEntity() instanceof Player player && enabled()) {
                double max = player.getData(AttachmentRegistries.MANA_DATA).maxMana();
                event.setMax((int) Math.min(Integer.MAX_VALUE, Math.floor(max)));
            }
        }
    }

    private static final class RegenListener {
        @SubscribeEvent
        public void onRegen(ManaRegenCalcEvent event) {
            if (event.getEntity() instanceof Player && enabled()) {
                event.setRegen(0.0);
            }
        }
    }

    /**
     * The cost Ars checks a cast against: scaled like the spend will be, on both sides (the client's copy of the core has the same
     * capacity, so Ars's own cost display, where it uses this event, agrees with what is paid).
     */
    private static final class CostCheckListener {
        @SubscribeEvent
        public void onSpellCheck(SpellCostCalcEvent.Pre event) {
            if (event.context != null && event.context.getUnwrappedCaster() instanceof Player player && enabled()) {
                ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
                if (!data.isTranscendent()) {
                    double scaled = Math.ceil(event.currentCost * ExternalMagic.costMultiplier(data, MOD_ID));
                    event.currentCost = (int) Math.min(Integer.MAX_VALUE, scaled);
                }
            }
        }
    }

    private static final class FreeCastListener {
        @SubscribeEvent
        public void onSpellCost(SpellCostCalcEvent.Post event) {
            if (event.context != null && event.context.getUnwrappedCaster() instanceof ServerPlayer player && enabled()
                    && player.getData(AttachmentRegistries.MANA_DATA).isTranscendent()) {
                event.currentCost = 0;
            }
        }
    }

    /** Ars spell damage dealt by a cultivator: the external damage multiplier of the Circle and purity. */
    private static final class DamageListener {
        @SubscribeEvent
        public void onSpellDamage(SpellDamageEvent.Pre event) {
            if (event.caster instanceof ServerPlayer player && enabled()) {
                event.damage = (float) (event.damage * ExternalMagic.powerMultiplier(player.getData(AttachmentRegistries.MANA_DATA)));
            }
        }
    }

    @Override
    public void tick(ServerPlayer player, ManaData data) {
        ManaCap cap = CapabilityRegistry.getMana(player);
        if (cap == null) {
            return;
        }
        double current = cap.getCurrentMana();
        Double last = mirrored.get(player.getUUID());
        if (last != null) {
            double diff = current - last;
            if (diff < -EPSILON) {
                MagicAdapters.reportSpent(player, data, MOD_ID, -diff);
            } else if (diff > EPSILON && api.isOn(REGEN_OFF)) {
                // without the regeneration event Ars's own regeneration would show up here: it is not credited
                MagicAdapters.reportGained(player, data, diff);
            }
        }
        push(player, data, cap);
    }

    @Override
    public void forceSync(ServerPlayer player, ManaData data) {
        ManaCap cap = CapabilityRegistry.getMana(player);
        if (cap == null) {
            return;
        }
        push(player, data, cap);
        if (api.isOn(CLIENT_SYNC)) {
            cap.syncToClient(player);
        }
    }

    @Override
    public void forget(ServerPlayer player) {
        mirrored.remove(player.getUUID());
    }

    private void push(ServerPlayer player, ManaData data, ManaCap cap) {
        int max = (int) Math.min(Integer.MAX_VALUE, Math.floor(data.maxMana()));
        if (cap.getMaxMana() != max) {
            cap.setMaxMana(max);
        }
        if (Math.abs(cap.getCurrentMana() - data.mana()) > EPSILON) {
            cap.setMana(data.mana());
        }
        mirrored.put(player.getUUID(), cap.getCurrentMana());
    }
}
