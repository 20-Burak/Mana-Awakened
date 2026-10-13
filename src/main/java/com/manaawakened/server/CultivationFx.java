package com.manaawakened.server;

import com.manaawakened.core.Refinement;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import org.joml.Vector3f;

/**
 * Server-side sounds, particles and titles. Everything is sent through vanilla packets, so other
 * players nearby see and hear it too. The custom 3D magic circles arrive with the Phase 8 renderer.
 */
public final class CultivationFx {
    public static final DustParticleOptions BLOOD = new DustParticleOptions(new Vector3f(0.55F, 0.02F, 0.03F), 1.4F);
    private static final double TAU = Math.PI * 2.0;
    /** One tinted dust per {@link Refinement.Kind}, built once so the ritual allocates nothing per tick. */
    private static final DustParticleOptions[] KIND_DUST = kindDust();

    private CultivationFx() {
    }

    private static DustParticleOptions[] kindDust() {
        Refinement.Kind[] kinds = Refinement.Kind.values();
        DustParticleOptions[] dust = new DustParticleOptions[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            int rgb = kinds[i].rgb();
            dust[i] = new DustParticleOptions(new Vector3f(((rgb >> 16) & 0xFF) / 255.0F, ((rgb >> 8) & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F), 1.2F);
        }
        return dust;
    }

    public static void sound(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS, volume, pitch);
    }

    public static void title(ServerPlayer player, Component title, Component subtitle) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(8, 50, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(title));
        player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
    }

    /** Three glyph motes circling the feet while meditating. */
    public static void meditationAura(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        double base = player.tickCount * 0.12;
        for (int i = 0; i < 3; i++) {
            double angle = base + i * (TAU / 3.0);
            level.sendParticles(ParticleTypes.ENCHANT,
                    player.getX() + Math.cos(angle) * 0.9, player.getY() + 0.15, player.getZ() + Math.sin(angle) * 0.9,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** Ambient mana drawn inward toward the heart while the core is compressed. */
    public static void chargeConverge(ServerPlayer player, double progress) {
        ServerLevel level = player.serverLevel();
        double cx = player.getX();
        double cy = player.getY() + player.getBbHeight() * 0.55;
        double cz = player.getZ();
        int motes = 4 + (int) Math.round(progress * 8.0);
        double radius = 2.4 - progress * 1.2;
        for (int i = 0; i < motes; i++) {
            double angle = player.getRandom().nextDouble() * TAU;
            double lift = (player.getRandom().nextDouble() - 0.5) * 1.6;
            // count = 0 makes the offset a velocity; enchant motes then fly from cx+offset back to cx.
            level.sendParticles(ParticleTypes.ENCHANT, cx, cy, cz, 0,
                    Math.cos(angle) * radius, lift, Math.sin(angle) * radius, 1.0);
        }
        if (progress > 0.66) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, cx, cy, cz, 3, 0.35, 0.45, 0.35, 0.05);
        }
    }

    public static void ascension(ServerPlayer player, int newCircle) {
        ServerLevel level = player.serverLevel();
        double cx = player.getX();
        double cy = player.getY() + 0.2;
        double cz = player.getZ();
        int rays = 40;
        for (int i = 0; i < rays; i++) {
            double angle = i * (TAU / rays);
            level.sendParticles(ParticleTypes.END_ROD, cx, cy, cz, 0, Math.cos(angle), 0.02, Math.sin(angle), 0.45);
        }
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, cx, cy + 1.0, cz, 60, 0.4, 0.8, 0.4, 0.35);
        level.sendParticles(ParticleTypes.FLASH, cx, cy + 1.0, cz, 1, 0.0, 0.0, 0.0, 0.0);
        sound(player, SoundEvents.TOTEM_USE, 0.9F, 1.15F);
        sound(player, SoundEvents.PLAYER_LEVELUP, 1.0F, 0.6F + newCircle * 0.06F);
    }

    public static void transcendence(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        level.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1.0, player.getZ(), 160, 0.35, 2.5, 0.35, 0.04);
        level.sendParticles(ParticleTypes.GLOW, player.getX(), player.getY() + 1.0, player.getZ(), 60, 1.5, 1.0, 1.5, 0.02);
        sound(player, SoundEvents.END_PORTAL_SPAWN, 1.0F, 1.2F);
        player.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    public static void backlash(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        double cy = player.getY() + player.getBbHeight() * 0.5;
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, player.getX(), cy, player.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, player.getX(), cy, player.getZ(), 40, 0.6, 0.6, 0.6, 0.18);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 1.0F, 1.3F);
    }

    public static void rupture(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        double cy = player.getY() + player.getBbHeight() * 0.6;
        level.sendParticles(BLOOD, player.getX(), cy, player.getZ(), 90, 0.45, 0.6, 0.45, 0.25);
        level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, player.getX(), cy, player.getZ(), 12, 0.4, 0.4, 0.4, 0.2);
        sound(player, SoundEvents.WARDEN_HEARTBEAT, 2.0F, 0.55F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 0.6F, 0.6F);
    }

    /** Flaming dragon blood spiralling up around the player during the absorption ritual. */
    public static void dragonVortex(ServerPlayer player, double progress) {
        ServerLevel level = player.serverLevel();
        double cx = player.getX();
        double cz = player.getZ();
        double base = player.tickCount * 0.35;
        int arms = 3;
        double radius = 1.6 - progress * 0.6;
        for (int arm = 0; arm < arms; arm++) {
            double angle = base + arm * (TAU / arms);
            double height = (player.tickCount % 40) / 40.0 * 2.6;
            double x = cx + Math.cos(angle) * radius;
            double z = cz + Math.sin(angle) * radius;
            level.sendParticles(ParticleTypes.FLAME, x, player.getY() + height, z, 1, 0.0, 0.0, 0.0, 0.0);
            level.sendParticles(BLOOD, x, player.getY() + 2.6 - height, z, 1, 0.02, 0.02, 0.02, 0.0);
        }
        if (progress > 0.5 && player.tickCount % 3 == 0) {
            level.sendParticles(ParticleTypes.DRAGON_BREATH, cx, player.getY() + 1.0, cz, 2, 0.5, 0.6, 0.5, 0.01);
        }
    }

    /** The heart is absorbed: a column of dragon fire and a burst of blood. */
    public static void dragonAscension(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        double cx = player.getX();
        double cy = player.getY();
        double cz = player.getZ();
        level.sendParticles(ParticleTypes.FLAME, cx, cy + 1.0, cz, 120, 0.3, 1.5, 0.3, 0.08);
        level.sendParticles(BLOOD, cx, cy + 1.0, cz, 80, 0.8, 0.8, 0.8, 0.1);
        level.sendParticles(ParticleTypes.DRAGON_BREATH, cx, cy + 1.0, cz, 40, 1.0, 1.0, 1.0, 0.05);
        level.sendParticles(ParticleTypes.FLASH, cx, cy + 1.0, cz, 1, 0.0, 0.0, 0.0, 0.0);
        sound(player, SoundEvents.ENDER_DRAGON_GROWL, 1.0F, 0.8F);
        sound(player, SoundEvents.TOTEM_USE, 0.8F, 0.7F);
    }

    public static void overheat(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        level.sendParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 1.0, player.getZ(), 20, 0.3, 0.5, 0.3, 0.03);
        level.sendParticles(ParticleTypes.FLAME, player.getX(), player.getY() + 1.0, player.getZ(), 16, 0.3, 0.5, 0.3, 0.05);
        sound(player, SoundEvents.FIRE_EXTINGUISH, 0.8F, 0.7F);
    }

    // ------------------------------------------------------------------ refinements

    /** The material starts to dissolve into the body. */
    public static void refinementStart(ServerPlayer player, Refinement.Kind kind) {
        switch (kind) {
            case CORE -> sound(player, SoundEvents.BEACON_ACTIVATE, 0.7F, 1.4F);
            case BODY -> sound(player, SoundEvents.SMITHING_TABLE_USE, 0.9F, 0.7F);
            case MIND -> sound(player, SoundEvents.ENCHANTMENT_TABLE_USE, 0.9F, 0.8F);
        }
    }

    /** Two ribbons of the refinement's colour climbing around the player while it is absorbed. */
    public static void refinementSwirl(ServerPlayer player, Refinement.Kind kind, double progress) {
        ServerLevel level = player.serverLevel();
        DustParticleOptions dust = KIND_DUST[kind.ordinal()];
        double base = player.tickCount * 0.3;
        double radius = 1.5 - progress * 0.7;
        for (int arm = 0; arm < 2; arm++) {
            double angle = base + arm * Math.PI;
            double height = ((player.tickCount + arm * 20) % 40) / 40.0 * 2.2;
            level.sendParticles(dust, player.getX() + Math.cos(angle) * radius, player.getY() + height, player.getZ() + Math.sin(angle) * radius,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
        if (progress > 0.5 && player.tickCount % 4 == 0) {
            level.sendParticles(ParticleTypes.ENCHANT, player.getX(), player.getY() + 1.0, player.getZ(), 2, 0.5, 0.6, 0.5, 0.05);
        }
    }

    /** The refinement settles into the body. */
    public static void refinementBurst(ServerPlayer player, Refinement.Kind kind) {
        ServerLevel level = player.serverLevel();
        level.sendParticles(KIND_DUST[kind.ordinal()], player.getX(), player.getY() + 1.0, player.getZ(), 70, 0.5, 0.9, 0.5, 0.05);
        level.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1.0, player.getZ(), 20, 0.3, 1.0, 0.3, 0.05);
        sound(player, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.0F, 1.2F);
        sound(player, SoundEvents.PLAYER_LEVELUP, 0.7F, 1.3F);
    }

    // ------------------------------------------------------------------ heavenly trial

    /** The heavens answer: a flash of lightning on the player. The bolt is purely visual, it neither hurts nor ignites. */
    public static void trialStart(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt != null) {
            bolt.moveTo(player.getX(), player.getY(), player.getZ());
            bolt.setVisualOnly(true);
            level.addFreshEntity(bolt);
        }
        sound(player, SoundEvents.WITHER_SPAWN, 0.6F, 1.5F);
    }

    /** A guardian is called down from the heavens. */
    public static void guardianArrival(ServerLevel level, Entity guardian) {
        double cy = guardian.getY() + guardian.getBbHeight() * 0.5;
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, guardian.getX(), cy, guardian.getZ(), 24, 0.4, 0.6, 0.4, 0.05);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, guardian.getX(), cy, guardian.getZ(), 16, 0.4, 0.6, 0.4, 0.2);
    }

    /** A guardian is dismissed (the trial ended around it). */
    public static void guardianDismissed(ServerLevel level, Entity guardian) {
        level.sendParticles(ParticleTypes.POOF, guardian.getX(), guardian.getY() + guardian.getBbHeight() * 0.5, guardian.getZ(), 12, 0.3, 0.5, 0.3, 0.02);
    }

    public static void trialVictory(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1.0, player.getZ(), 50, 0.5, 0.9, 0.5, 0.3);
        player.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    public static void trialFailure(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        level.sendParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 1.0, player.getZ(), 30, 0.5, 0.8, 0.5, 0.04);
        sound(player, SoundEvents.BEACON_DEACTIVATE, 1.0F, 0.6F);
    }
}
