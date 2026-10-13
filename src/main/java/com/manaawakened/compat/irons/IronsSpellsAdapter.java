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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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
 *   <li>a cast the core cannot pay at that price is refused before it starts ({@link SpellPreCastEvent});</li>
 *   <li>{@code spell_power} gets the external damage multiplier of the caster's Circle and purity (a transient modifier);</li>
 *   <li>a transcendent core sets every spell's cost to 0.</li>
 * </ul>
 * Iron's own spell wheel, tooltips and inscription table compute a spell's cost on the client from the spell itself, so they keep
 * showing the unscaled cost. This class is only loaded when {@code irons_spellbooks} is present.
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
            float mirror = (float) data.mana();
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
            double cost = spell.getManaCost(event.getSpellLevel()) * ExternalMagic.costMultiplier(data, MOD_ID);
            if (data.mana() + EPSILON < cost) {
                event.setCanceled(true);
                player.displayClientMessage(Component.translatable("mana_awakened.integration.not_enough",
                        String.format(Locale.ROOT, "%,d", (long) Math.ceil(cost))).withStyle(ChatFormatting.RED), true);
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
        float target = (float) data.mana();
        Float last = mirrored.get(player.getUUID());
        if (last == null || Math.abs(magic.getMana() - target) > 0.001F) {
            pushing = true;
            try {
                magic.setMana(target);
            } finally {
                pushing = false;
            }
            mirrored.put(player.getUUID(), magic.getMana());
        }
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
