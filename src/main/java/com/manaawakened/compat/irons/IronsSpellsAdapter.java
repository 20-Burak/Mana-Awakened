package com.manaawakened.compat.irons;

import com.manaawakened.ManaAwakened;
import com.manaawakened.compat.ApiProbe;
import com.manaawakened.compat.ApiProbe.Feature;
import com.manaawakened.compat.ApiProbe.Symbol;
import com.manaawakened.compat.MagicAdapter;
import com.manaawakened.compat.MagicAdapters;
import com.manaawakened.core.ExternalMagic;
import com.manaawakened.core.ManaData;
import com.manaawakened.registry.AttachmentRegistries;
import io.redspace.ironsspellbooks.api.events.ChangeManaEvent;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import io.redspace.ironsspellbooks.api.events.SpellPreCastEvent;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.network.SyncManaPacket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Iron's Spells 'n Spellbooks. Its {@code MagicData} becomes a mirror of the core:
 * <ul>
 *   <li>{@code max_mana} attribute = core capacity, {@code mana_regen} = 0 (transient modifiers);</li>
 *   <li>every {@link ChangeManaEvent} is turned into a core spend/gain and then overwritten with the core value; a spend costs the core
 *       its amount times the cost multiplier ({@link ExternalMagic}), which covers instant and continuous spells alike;</li>
 *   <li>a cast the core cannot pay at that price is refused before it starts ({@link SpellPreCastEvent}), with the red "not enough
 *       mana" line; the core then pays nothing;</li>
 *   <li>what Iron's is shown (1.0.2): between casts the core as a whole number, so Iron's own check (made first, in its own
 *       unscaled units) lets the cast reach ours; from the moment a cast is allowed until it ends, the whole units the core can pay
 *       at the multiplier ({@link MagicAdapters#mirrorUnits}). Iron's charges a cast without checking again, and a channelled
 *       (continuous) spell pays every 10 ticks while its mana is at least twice the cost and then once more before it stops: seen in
 *       those units, every payment is one the core can make in full, and a channel stops when the core cannot pay its next ticks
 *       (until 1.0.1 it ran on while Iron's saw the unscaled core, and the core paid what it had);</li>
 *   <li>{@code spell_power} gets the external damage multiplier of the caster's Circle and purity (a transient modifier);</li>
 *   <li>a transcendent core sets every spell's cost to 0.</li>
 * </ul>
 * Iron's own spell wheel, tooltips and inscription table compute a spell's cost on the client from the spell itself, so they keep
 * showing the unscaled cost. This class is only loaded when {@code irons_spellbooks} is present.
 *
 * <p>Big cores (audited for 1.0.1): Iron's keeps its mana as a {@code float}, which holds every whole number only up to 16,777,216,
 * but its {@code max_mana} attribute stops at 1,000,000 and {@code MagicData.setMana} clamps the mana to it. So however big the core,
 * Iron's sees at most 1,000,000 of it, and every spend it makes inside that is read exactly and charged to the core at the scaled price
 * (checked at a core of a trillion). Iron's own (hidden) mana bar shows at most that million, not the whole core.
 *
 * <p>Version tolerance (1.0.0): everything used is listed in {@link #FEATURES} and checked once when the adapter starts
 * ({@link ApiProbe}). Each event listener is its own small class, registered only when its feature is on, so a build that lacks one
 * part loses that part only: no refusal of unaffordable casts, no spell power bonus, no forced client sync. Without the regeneration
 * attribute, Iron's own regeneration is not credited to the core (the core value is pushed back every tick). Without the mana event
 * or the magic data the bridge cannot work, and Iron's keeps its own mana (one warning). Iron's builds 1.21-3.4.4 to 1.21.1-3.16.3
 * have every symbol with the same signature.
 */
public final class IronsSpellsAdapter implements MagicAdapter {
    public static final String MOD_ID = ExternalMagic.IRONS;
    private static final ResourceLocation MAX_MANA_MODIFIER = ManaAwakened.id("irons_max_mana");
    private static final ResourceLocation REGEN_MODIFIER = ManaAwakened.id("irons_mana_regen");
    /** The external damage multiplier as a {@code spell_power} modifier (amount = multiplier - 1, ADD_MULTIPLIED_TOTAL). */
    public static final ResourceLocation SPELL_POWER_MODIFIER = ManaAwakened.id("irons_spell_power");
    private static final double EPSILON = 1.0E-6;

    private static final String PKG = "io.redspace.ironsspellbooks.";
    private static final String HOLDER = "net.neoforged.neoforge.registries.DeferredHolder";
    public static final String CORE = "mana_mirror";
    public static final String REGEN_OFF = "regeneration_off";
    public static final String PRE_CAST = "refuse_unaffordable_casts";
    public static final String CAST = "cast_tracking";
    public static final String SPELL_POWER = "spell_power_bonus";
    public static final String CLIENT_SYNC = "client_sync";
    public static final String CASTING = "casting_state";
    /** What the bridge uses of Iron's, by feature; names separated by "|" would be tried in order (no rename is known). */
    public static final List<Feature> FEATURES = List.of(
            Feature.essential(CORE,
                    Symbol.type(PKG + "api.events.ChangeManaEvent"),
                    Symbol.method(PKG + "api.events.ChangeManaEvent", "getOldMana", "float"),
                    Symbol.method(PKG + "api.events.ChangeManaEvent", "getNewMana", "float"),
                    Symbol.method(PKG + "api.events.ChangeManaEvent", "setNewMana", "void", "float"),
                    Symbol.staticMethod(PKG + "api.magic.MagicData", "getPlayerMagicData", PKG + "api.magic.MagicData",
                            "net.minecraft.world.entity.LivingEntity"),
                    Symbol.method(PKG + "api.magic.MagicData", "getMana", "float"),
                    Symbol.method(PKG + "api.magic.MagicData", "setMana", "void", "float"),
                    Symbol.staticField(PKG + "api.registry.AttributeRegistry", "MAX_MANA", HOLDER)),
            Feature.optional(REGEN_OFF,
                    Symbol.staticField(PKG + "api.registry.AttributeRegistry", "MANA_REGEN", HOLDER)),
            Feature.optional(PRE_CAST,
                    Symbol.type(PKG + "api.events.SpellPreCastEvent"),
                    Symbol.method(PKG + "api.events.SpellPreCastEvent", "getCastSource", PKG + "api.spells.CastSource"),
                    Symbol.method(PKG + "api.spells.CastSource", "consumesMana", "boolean"),
                    Symbol.method(PKG + "api.events.SpellPreCastEvent", "getSpellId", "java.lang.String"),
                    Symbol.method(PKG + "api.events.SpellPreCastEvent", "getSpellLevel", "int"),
                    Symbol.staticMethod(PKG + "api.registry.SpellRegistry", "getSpell", PKG + "api.spells.AbstractSpell", "java.lang.String"),
                    Symbol.method(PKG + "api.spells.AbstractSpell", "getManaCost", "int", "int")),
            Feature.optional(CAST,
                    Symbol.type(PKG + "api.events.SpellOnCastEvent"),
                    Symbol.method(PKG + "api.events.SpellOnCastEvent", "setManaCost", "void", "int")),
            Feature.optional(SPELL_POWER,
                    Symbol.staticField(PKG + "api.registry.AttributeRegistry", "SPELL_POWER", HOLDER)),
            Feature.optional(CASTING,
                    Symbol.method(PKG + "api.magic.MagicData", "isCasting", "boolean")),
            Feature.optional(CLIENT_SYNC,
                    Symbol.staticField(PKG + "network.SyncManaPacket", "TYPE", "net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type"),
                    Symbol.constructor(PKG + "network.SyncManaPacket", PKG + "api.magic.MagicData")));

    private final ApiProbe.Result api;
    private final Map<UUID, Float> mirrored = new HashMap<>();
    /** The server tick of each player's last cast, to tell a cast's spend from a continuous spell's. */
    private final Map<UUID, long[]> lastCast = new HashMap<>();
    /** True while this adapter itself writes to Iron's data, so its own writes are not read back as spends. */
    private boolean pushing;

    public IronsSpellsAdapter() {
        api = ApiProbe.resolve(IronsSpellsAdapter.class.getClassLoader(), FEATURES);
        if (!api.usable()) {
            throw new ApiProbe.Unavailable(api.offFeatures());
        }
        NeoForge.EVENT_BUS.register(new ManaListener());
        if (api.isOn(PRE_CAST)) {
            NeoForge.EVENT_BUS.register(new PreCastListener());
        }
        if (api.isOn(CAST)) {
            NeoForge.EVENT_BUS.register(new CastListener());
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

    private static boolean enabled() {
        return MagicAdapters.isEnabled(MOD_ID);
    }

    /** Every change of Iron's mana: a spend or a gain of the core, then the core value written back. */
    private final class ManaListener {
        @SubscribeEvent
        public void onChangeMana(ChangeManaEvent event) {
            if (pushing || !enabled() || !(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
            float delta = event.getNewMana() - event.getOldMana();
            if (delta < 0.0F && !trimmedByIrons(event.getOldMana(), event.getNewMana(), player.getAttributeValue(AttributeRegistry.MAX_MANA))) {
                // a spend in the tick of a cast is that cast; any other (a continuous spell paying every few ticks) is a steady drain
                long[] cast = lastCast.get(player.getUUID());
                boolean casting = cast != null && cast[0] == player.server.getTickCount();
                MagicAdapters.reportSpent(player, data, MOD_ID, -delta, !casting);
            } else if (delta > 0.0F && api.isOn(REGEN_OFF)) {
                // without the regeneration attribute Iron's own regeneration would show up here: it is not credited
                MagicAdapters.reportGained(player, data, delta);
            }
            float mirror = mirror(data, MagicData.getPlayerMagicData(player));
            event.setNewMana(mirror);
            mirrored.put(player.getUUID(), mirror);
        }
    }

    /** Iron's checks its own (mirrored) mana against the unscaled cost; the core must be able to pay the scaled one. */
    private final class PreCastListener {
        @SubscribeEvent
        public void onPreCast(SpellPreCastEvent event) {
            if (!enabled() || !(event.getEntity() instanceof ServerPlayer player) || player.isCreative() || !event.getCastSource().consumesMana()) {
                return;
            }
            ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
            if (data.isTranscendent()) {
                return;
            }
            AbstractSpell spell = SpellRegistry.getSpell(event.getSpellId());
            double cost = MagicAdapters.scaledCost(data, MOD_ID, spell.getManaCost(event.getSpellLevel()));
            if (!ExternalMagic.affords(data.mana(), cost)) {
                event.setCanceled(true);
                MagicAdapters.refuse(player, cost);
                return;
            }
            // the cast goes ahead: from now until it ends Iron's sees what the core can pay, in its own units
            MagicData magic = MagicData.getPlayerMagicData(player);
            if (magic != null) {
                write(player, magic, (float) MagicAdapters.mirrorUnits(data, MOD_ID));
            }
        }
    }

    /** Marks the tick of a cast, and makes every cast of a transcendent core free. */
    private final class CastListener {
        @SubscribeEvent
        public void onSpellCast(SpellOnCastEvent event) {
            if (!enabled() || !(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            lastCast.computeIfAbsent(player.getUUID(), id -> new long[1])[0] = player.server.getTickCount();
            if (player.getData(AttachmentRegistries.MANA_DATA).isTranscendent()) {
                event.setManaCost(0);
            }
        }
    }

    /**
     * Whether a decrease of Iron's mana is Iron's own trim to its maximum rather than a spell. Iron's keeps its mana at most its
     * maximum cut to a whole number (its regeneration clamps to {@code (int) max_mana}), so with a fractional capacity such as 5,054.72
     * a full core is trimmed to 5,054 every few ticks. The rule: a decrease that ends at or above the whole-number maximum is a trim and
     * is never charged; so is one that starts above the maximum itself (the core shrank). Iron's costs are whole numbers of at least 1,
     * so a real cast from a full core always ends below the whole-number maximum and is charged exactly.
     */
    static boolean trimmedByIrons(float oldMana, float newMana, double maxMana) {
        return oldMana > maxMana + 0.001 || newMana >= Math.floor(maxMana) - 0.001;
    }

    @Override
    public void tick(ServerPlayer player, ManaData data) {
        // Every tick: while the core trains, capacity grows a little each tick and Iron's maximum must keep up; the Circle and
        // purity behind the spell power change only now and then, and the modifier is touched only when its value changes.
        applyAttributes(player, data);
        push(player, data);
    }

    @Override
    public void forceSync(ServerPlayer player, ManaData data) {
        applyAttributes(player, data);
        push(player, data);
        if (!api.isOn(CLIENT_SYNC)) {
            return;
        }
        MagicData magic = MagicData.getPlayerMagicData(player);
        if (magic != null && player.connection != null && player.connection.hasChannel(SyncManaPacket.TYPE)) {
            PacketDistributor.sendToPlayer(player, new SyncManaPacket(magic));
        }
    }

    @Override
    public void forget(ServerPlayer player) {
        mirrored.remove(player.getUUID());
        lastCast.remove(player.getUUID());
    }

    private void push(ServerPlayer player, ManaData data) {
        MagicData magic = MagicData.getPlayerMagicData(player);
        if (magic == null) {
            return;
        }
        float target = mirror(data, magic);
        Float last = mirrored.get(player.getUUID());
        if (last == null || Math.abs(magic.getMana() - target) > 0.001F) {
            write(player, magic, target);
        }
    }

    /** Writes Iron's mana without reading the write back as a spend. */
    private void write(ServerPlayer player, MagicData magic, float mana) {
        pushing = true;
        try {
            magic.setMana(mana);
        } finally {
            pushing = false;
        }
        mirrored.put(player.getUUID(), magic.getMana());
    }

    /**
     * What Iron's is shown: while it casts (or always, on a build whose casting state or pre-cast event this bridge cannot read) the
     * whole units the core can pay at the multiplier, otherwise the core as a whole number. Whole numbers keep every Iron's spend exact
     * in its float (its mana never goes above its max_mana attribute, at most 1,000,000).
     */
    private float mirror(ManaData data, MagicData magic) {
        boolean scaled = !api.isOn(PRE_CAST) || !api.isOn(CASTING) || (magic != null && magic.isCasting());
        return scaled ? (float) MagicAdapters.mirrorUnits(data, MOD_ID) : (float) Math.max(0.0, Math.floor(data.mana()));
    }

    private void applyAttributes(ServerPlayer player, ManaData data) {
        AttributeInstance max = player.getAttribute(AttributeRegistry.MAX_MANA);
        if (max != null) {
            double needed = data.maxMana() - max.getBaseValue();
            AttributeModifier existing = max.getModifier(MAX_MANA_MODIFIER);
            if (existing == null || existing.amount() != needed) {
                max.addOrUpdateTransientModifier(new AttributeModifier(MAX_MANA_MODIFIER, needed, AttributeModifier.Operation.ADD_VALUE));
            }
        }
        if (api.isOn(REGEN_OFF)) {
            AttributeInstance regen = player.getAttribute(AttributeRegistry.MANA_REGEN);
            if (regen != null && !regen.hasModifier(REGEN_MODIFIER)) {
                regen.addOrUpdateTransientModifier(new AttributeModifier(REGEN_MODIFIER, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
        }
        if (!api.isOn(SPELL_POWER)) {
            return;
        }
        AttributeInstance power = player.getAttribute(AttributeRegistry.SPELL_POWER);
        if (power != null) {
            double bonus = ExternalMagic.powerMultiplier(data) - 1.0;
            AttributeModifier existing = power.getModifier(SPELL_POWER_MODIFIER);
            if (bonus <= EPSILON) {
                if (existing != null) {
                    power.removeModifier(SPELL_POWER_MODIFIER);
                }
            } else if (existing == null || Math.abs(existing.amount() - bonus) > EPSILON) {
                power.addOrUpdateTransientModifier(new AttributeModifier(SPELL_POWER_MODIFIER, bonus, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
        }
    }
}
