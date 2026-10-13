package com.manaawakened.network;

import com.manaawakened.compat.MagicAdapters;
import com.manaawakened.config.ManaConfig;
import com.manaawakened.core.ManaData;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.server.BreakthroughHandler;
import com.manaawakened.server.CultivationRules;
import com.manaawakened.passive.Passive;
import com.manaawakened.passive.PassiveData;
import com.manaawakened.server.MeditationHandler;
import com.manaawakened.spell.AltarService;
import com.manaawakened.spell.FieldTargets;
import com.manaawakened.spell.Sigil;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellCaster;
import com.manaawakened.spell.SpellStats;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Payload registration and send helpers. Handlers run on the main thread (NeoForge default),
 * and none of them touch client-only classes, so this class is safe to load on a dedicated server.
 */
public final class ModNetwork {
    /** Bump whenever a payload layout changes; mismatched client/server versions are refused at login. */
    public static final String PROTOCOL_VERSION = "23";
    /** Routine syncs are never closer than this many ticks: at most 5 a second for any continuous change. */
    public static final int MIN_ROUTINE_SYNC_TICKS = 4;
    /** A routine sync refreshes the other magic mods' own displays when the mana moved at least this much since they were last told ... */
    public static final double EXTERNAL_SYNC_MANA_STEP = 1.0;
    /** ... or, for any smaller difference, after this many ticks. */
    public static final int EXTERNAL_SYNC_MAX_TICKS = 20;

    private ModNetwork() {
    }

    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(SyncManaPayload.TYPE, SyncManaPayload.STREAM_CODEC, ModNetwork::handleSync);
        registrar.playToServer(ToggleMeditationPayload.TYPE, ToggleMeditationPayload.STREAM_CODEC, ModNetwork::handleToggleMeditation);
        registrar.playToServer(BreakthroughChargePayload.TYPE, BreakthroughChargePayload.STREAM_CODEC, ModNetwork::handleBreakthroughCharge);
        registrar.playToClient(SyncSpellsPayload.TYPE, SyncSpellsPayload.STREAM_CODEC, ModNetwork::handleSyncSpells);
        registrar.playToServer(SpellSelectPayload.TYPE, SpellSelectPayload.STREAM_CODEC, ModNetwork::handleSpellSelect);
        registrar.playToServer(SpellEditPayload.TYPE, SpellEditPayload.STREAM_CODEC, ModNetwork::handleSpellEdit);
        registrar.playToServer(AltarRequestPayload.TYPE, AltarRequestPayload.STREAM_CODEC, ModNetwork::handleAltarRequest);
        registrar.playToServer(AltarSolvePayload.TYPE, AltarSolvePayload.STREAM_CODEC, ModNetwork::handleAltarSolve);
        registrar.playToClient(AltarTrialPayload.TYPE, AltarTrialPayload.STREAM_CODEC, ModNetwork::handleAltarTrial);
        registrar.playToClient(SpellCircleFxPayload.TYPE, SpellCircleFxPayload.STREAM_CODEC, ModNetwork::handleCircleFx);
        registrar.playToClient(SpellFxPayload.TYPE, SpellFxPayload.STREAM_CODEC, ModNetwork::handleSpellFx);
        registrar.playToClient(SyncPassivesPayload.TYPE, SyncPassivesPayload.STREAM_CODEC, ModNetwork::handleSyncPassives);
        registrar.playToServer(SelectPassivePayload.TYPE, SelectPassivePayload.STREAM_CODEC, ModNetwork::handleSelectPassive);
        registrar.playToServer(CondenserSettingsPayload.TYPE, CondenserSettingsPayload.STREAM_CODEC, ModNetwork::handleCondenserSettings);
        registrar.playToClient(CondenserStatePayload.TYPE, CondenserStatePayload.STREAM_CODEC, ModNetwork::handleCondenserState);
        registrar.playToClient(CircleRevealPayload.TYPE, CircleRevealPayload.STREAM_CODEC, ModNetwork::handleCircleReveal);
        registrar.playToClient(SyncAuraPayload.TYPE, SyncAuraPayload.STREAM_CODEC, ModNetwork::handleSyncAura);
        registrar.playToClient(ManaSpentPayload.TYPE, ManaSpentPayload.STREAM_CODEC, ModNetwork::handleManaSpent);
        registrar.playToClient(RiteTargetsPayload.TYPE, RiteTargetsPayload.STREAM_CODEC, ModNetwork::handleRiteTargets);
        registrar.playToServer(SpellTargetsPayload.TYPE, SpellTargetsPayload.STREAM_CODEC, ModNetwork::handleSpellTargets);
        registrar.playToServer(FieldTargetsPayload.TYPE, FieldTargetsPayload.STREAM_CODEC, ModNetwork::handleFieldTargets);
        registrar.playToClient(FieldTargetsResultPayload.TYPE, FieldTargetsResultPayload.STREAM_CODEC, ModNetwork::handleFieldTargetsResult);
    }

    // ------------------------------------------------------------------ handlers

    private static void handleSync(SyncManaPayload payload, IPayloadContext context) {
        Player player = context.player();
        if (player != null) {
            payload.applyTo(player.getData(AttachmentRegistries.MANA_DATA));
        }
    }

    /** Another player's aura. The owner's own copy is authoritative through {@link SyncManaPayload}, so it is skipped. */
    private static void handleSyncAura(SyncAuraPayload payload, IPayloadContext context) {
        Player self = context.player();
        if (self == null) {
            return;
        }
        if (self.level().getEntity(payload.entityId()) instanceof Player target && target != self) {
            payload.applyTo(target.getData(AttachmentRegistries.MANA_DATA));
        }
    }

    private static void handleToggleMeditation(ToggleMeditationPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.isAlive()) {
            MeditationHandler.requestState(player, player.getData(AttachmentRegistries.MANA_DATA), payload.meditate(), payload.moved());
        }
    }

    private static void handleBreakthroughCharge(BreakthroughChargePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.isAlive()) {
            BreakthroughHandler.requestCharge(player, player.getData(AttachmentRegistries.MANA_DATA), payload.charging());
        }
    }

    private static void handleSyncSpells(SyncSpellsPayload payload, IPayloadContext context) {
        Player player = context.player();
        if (player != null) {
            payload.applyTo(player.getData(AttachmentRegistries.SPELL_BOOK));
        }
    }

    private static void handleSpellSelect(SpellSelectPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !player.isAlive()) {
            return;
        }
        if (payload.cast()) {
            SpellCaster.cast(player, payload.spell(), payload.colorSpell(), payload.colors());
        } else {
            SpellCaster.select(player, payload.spell());
        }
    }

    private static void handleSpellEdit(SpellEditPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.isAlive()) {
            AltarService.edit(player, payload.op(), payload.spell(), payload.arg(), payload.value(), payload.text());
        }
    }

    private static void handleSpellTargets(SpellTargetsPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.isAlive()) {
            AltarService.setTargets(player, payload.spell(), payload.targets());
        }
    }

    /** A target edit away from the altar: no living check here, {@link FieldTargets#apply} refuses (and says so) itself. */
    private static void handleFieldTargets(FieldTargetsPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            FieldTargets.apply(player, payload.spell(), payload.targets());
        }
    }

    private static void handleFieldTargetsResult(FieldTargetsResultPayload payload, IPayloadContext context) {
        com.manaawakened.client.ClientScreens.onFieldTargets(payload);
    }

    private static void handleRiteTargets(RiteTargetsPayload payload, IPayloadContext context) {
        com.manaawakened.client.ClientScreens.onRiteTargets(payload);
    }

    private static void handleAltarRequest(AltarRequestPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.isAlive()) {
            AltarService.requestTrial(player, payload.sigil());
        }
    }

    private static void handleAltarSolve(AltarSolvePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.isAlive()) {
            AltarService.submit(player, payload.sigil(), payload.arrangement());
        }
    }

    private static void handleCondenserSettings(CondenserSettingsPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.isAlive()) {
            com.manaawakened.block.ManaCondenserBlockEntity.handleSettings(player, payload);
        }
    }

    private static void handleCircleReveal(CircleRevealPayload payload, IPayloadContext context) {
        com.manaawakened.client.ClientScreens.onCircleReveal(payload);
    }

    private static void handleCondenserState(CondenserStatePayload payload, IPayloadContext context) {
        com.manaawakened.client.ClientScreens.onCondenserState(payload);
    }

    private static void handleAltarTrial(AltarTrialPayload payload, IPayloadContext context) {
        com.manaawakened.client.ClientScreens.onAltarTrial(payload);
    }

    private static void handleCircleFx(SpellCircleFxPayload payload, IPayloadContext context) {
        com.manaawakened.client.ClientScreens.onCircleFx(payload);
    }

    private static void handleManaSpent(ManaSpentPayload payload, IPayloadContext context) {
        com.manaawakened.client.ClientScreens.onManaSpent(payload);
    }

    private static void handleSpellFx(SpellFxPayload payload, IPayloadContext context) {
        com.manaawakened.client.ClientScreens.onSpellFx(payload);
    }

    private static void handleSyncPassives(SyncPassivesPayload payload, IPayloadContext context) {
        Player player = context.player();
        if (player != null) {
            payload.applyTo(player.getData(AttachmentRegistries.PASSIVES));
        }
    }

    private static void handleSelectPassive(SelectPassivePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !player.isAlive()) {
            return;
        }
        PassiveData data = player.getData(AttachmentRegistries.PASSIVES);
        ManaData mana = player.getData(AttachmentRegistries.MANA_DATA);
        Passive passive = payload.passive() >= 0 && payload.passive() < Passive.VALUES.length ? Passive.VALUES[payload.passive()] : null;
        if (payload.circle() >= 1 && payload.circle() <= mana.circle()) {
            if (payload.slot() == SelectPassivePayload.FIRST) {
                data.choose(payload.circle(), passive);
            } else if (payload.slot() == SelectPassivePayload.SECOND) {
                // Only a Circle the server saw cleared perfectly has a second pick.
                data.chooseSecond(payload.circle(), passive, mana.hasSecondPick(payload.circle()));
            }
        }
        syncPassives(player, data);
    }

    // ------------------------------------------------------------------ sending

    public static void syncSpells(ServerPlayer player, SpellBook book) {
        if (canReceive(player)) {
            PacketDistributor.sendToPlayer(player, SyncSpellsPayload.of(book));
        }
        book.markSynced();
    }

    public static void syncPassives(ServerPlayer player, PassiveData data) {
        if (canReceive(player)) {
            PacketDistributor.sendToPlayer(player, SyncPassivesPayload.of(data));
        }
        data.markSynced();
    }

    /** What the core of {@code player} paid this tick, for the spend feedback on the mana HUD. */
    public static void sendManaSpent(ServerPlayer player, ManaSpentPayload payload) {
        if (canReceive(player)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /** What the working spells of this server can be aimed at, for the Altar of {@code player}. */
    public static void sendRiteTargets(ServerPlayer player, com.manaawakened.spell.RiteTargets.Catalogue catalogue) {
        if (canReceive(player)) {
            PacketDistributor.sendToPlayer(player, RiteTargetsPayload.of(catalogue));
        }
    }

    /** The answer to a target edit away from the altar ({@link FieldTargets}). */
    public static void sendFieldTargetsResult(ServerPlayer player, FieldTargetsResultPayload payload) {
        if (canReceive(player)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    public static void sendAltarTrial(ServerPlayer player, AltarTrialPayload payload) {
        if (canReceive(player)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /**
     * The choreography of a cast (3.8.0) for the caster and everyone who can see them: one payload with the kind, the target the
     * server decided, the seed and the phase times of {@code stats.timeline()}. The client draws the circles, the manifesting
     * spell, its travel and the impact from it, and plays the caster's gesture for exactly as long.
     */
    public static void sendCast(ServerPlayer caster, Sigil sigil, SpellStats stats, @Nullable Vec3 target, long seed) {
        if (caster instanceof FakePlayer || caster.connection == null) {
            return;
        }
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(caster, castPayload(caster, sigil, stats, target, seed));
    }

    /** The payload {@link #sendCast} sends (also what the tests read). */
    public static SpellCircleFxPayload castPayload(ServerPlayer caster, Sigil sigil, SpellStats stats, @Nullable Vec3 target, long seed) {
        int rgb = sigil.color();
        float scale = (float) (1.0 + 0.08 * Math.min(10, caster.getData(AttachmentRegistries.MANA_DATA).circle()));
        int stack = 1 + (sigil.minCircle() >= 4 ? 1 : 0) + (sigil.minCircle() >= 8 ? 1 : 0);
        Sigil.Kind kind = sigil.kind();
        int mode;
        float size;
        if (sigil.aroundCaster()) {
            mode = SpellCircleFxPayload.MODE_SELF;
            size = kind == Sigil.Kind.NOVA || kind == Sigil.Kind.SANCTUARY ? (float) Math.max(2.0, stats.radius() * 0.9) : scale * 1.4F;
        } else {
            mode = SpellCircleFxPayload.MODE_FRONT;
            size = scale;
        }
        com.manaawakened.spell.SpellTimelines.Timeline timeline = stats.timeline();
        return new SpellCircleFxPayload(kind.ordinal(), mode, caster.getId(), rgb, sigil.variant(), size, stack,
                target != null, target != null ? target.x : 0.0, target != null ? target.y : 0.0, target != null ? target.z : 0.0, seed,
                timeline.summon(), timeline.manifest(), timeline.travel(), timeline.impact(), timeline.aftermath(),
                stats.beams(), stats.echoes(), (float) stats.radius(), stats.tier());
    }


    /** Sends the full state right now, the other magic mods' displays included (discrete events: login, breakthrough, toggles, casts). */
    public static void syncNow(ServerPlayer player, ManaData data) {
        send(player, data, true);
    }

    /**
     * Throttled routine update for continuous changes such as regeneration and passive upkeep: only when something changed, and
     * never closer than {@link #MIN_ROUTINE_SYNC_TICKS} (at most 5 a second, whatever {@code syncIntervalTicks} says; the client
     * still has the right value within 250 ms). The other magic mods' own displays follow only when the mana moved enough
     * ({@link ManaData#isExternalSyncDue}); their server-side copies are kept in step every tick by the adapters anyway.
     */
    public static void syncIfDue(ServerPlayer player, ManaData data) {
        if (data.isSyncDue(Math.max(MIN_ROUTINE_SYNC_TICKS, ManaConfig.SERVER.syncIntervalTicks.get()))) {
            send(player, data, false);
        }
    }

    private static void send(ServerPlayer player, ManaData data, boolean discrete) {
        CultivationRules.normalizeCapacity(data);
        if (discrete || data.isExternalSyncDue(EXTERNAL_SYNC_MANA_STEP, EXTERNAL_SYNC_MAX_TICKS)) {
            MagicAdapters.forceSync(player, data);
            data.markExternalSynced();
        }
        if (canReceive(player)) {
            PacketDistributor.sendToPlayer(player, SyncManaPayload.of(data));
        }
        data.markSynced();
    }

    /**
     * Tells everyone who can see this player what their aura looks like, when it changed or a running
     * clock needs a correction. The owner is excluded: they already get the full {@link SyncManaPayload}.
     */
    public static void syncAuraIfDue(ServerPlayer player, ManaData data) {
        if (!data.isAuraSyncDue(ManaConfig.SERVER.auraCorrectionTicks.get())) {
            return;
        }
        if (!(player instanceof FakePlayer) && player.level() instanceof net.minecraft.server.level.ServerLevel) {
            PacketDistributor.sendToPlayersTrackingEntity(player, SyncAuraPayload.of(player, data));
        }
        data.markAuraSynced();
    }

    /** One tracker just started seeing {@code target}: give it the current aura right away. */
    public static void syncAuraTo(ServerPlayer tracker, ServerPlayer target) {
        if (tracker != target && canReceive(tracker)) {
            PacketDistributor.sendToPlayer(tracker, SyncAuraPayload.of(target, target.getData(AttachmentRegistries.MANA_DATA)));
        }
    }

    /**
     * Fake players (Create deployers, Mekanism miners...) and game-test mock players have no real
     * client; sending them a payload would throw, so only players that negotiated our channel receive one.
     */
    private static boolean canReceive(ServerPlayer player) {
        return !(player instanceof FakePlayer)
                && player.connection != null
                && player.connection.hasChannel(SyncManaPayload.TYPE);
    }
}
