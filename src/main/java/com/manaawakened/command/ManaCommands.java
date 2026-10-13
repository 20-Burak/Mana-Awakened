package com.manaawakened.command;

import com.manaawakened.compat.ExternalCostLine;
import com.manaawakened.compat.MagicAdapters;
import com.manaawakened.core.CircleTier;
import com.manaawakened.core.ManaData;
import com.manaawakened.core.Refinement;
import com.manaawakened.core.Refinements;
import com.manaawakened.core.VesselTier;
import com.manaawakened.network.ModNetwork;
import com.manaawakened.passive.Passive;
import com.manaawakened.passive.PassiveData;
import com.manaawakened.spell.Sigil;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.registry.AttachmentRegistries;
import com.manaawakened.registry.ModRegistries;
import com.manaawakened.server.BreakthroughHandler;
import com.manaawakened.server.CultivationRules;
import com.manaawakened.server.MeditationHandler;
import com.manaawakened.server.RefinementHandler;
import com.manaawakened.server.TrialHandler;
import com.manaawakened.server.VesselStrainHandler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /mana} shows your own status (any player).
 * Operators (level 2) get {@code info}, {@code set}, {@code fill}, {@code heal}, {@code reset},
 * {@code refine give|remove} and {@code trial start|stop} for testing and pack tuning.
 */
public final class ManaCommands {
    private static final int OP_LEVEL = 2;
    private static final List<String> REFINEMENT_IDS = refinementIds();
    private static final SuggestionProvider<CommandSourceStack> REFINEMENT_SUGGESTIONS =
            (context, builder) -> SharedSuggestionProvider.suggest(REFINEMENT_IDS, builder);

    private ManaCommands() {
    }

    /** "all" first, then every refinement id: what {@code /mana refine give|remove} offers. */
    private static List<String> refinementIds() {
        List<String> ids = new ArrayList<>();
        ids.add("all");
        for (Refinement refinement : Refinement.VALUES) {
            ids.add(refinement.id());
        }
        return List.copyOf(ids);
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("mana")
                .executes(ctx -> showStatus(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                .then(Commands.literal("info")
                        .requires(source -> source.hasPermission(OP_LEVEL))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(ctx -> showStatus(ctx.getSource(), EntityArgument.getPlayer(ctx, "target")))))
                .then(Commands.literal("set")
                        .requires(source -> source.hasPermission(OP_LEVEL))
                        .then(intSetter("circle", 0, CircleTier.MAX_CIRCLE, (data, value) -> {
                            data.setCircle(value);
                            // A set Circle gets the capacity of the shortest route into it.
                            data.setCoreCapacity(CultivationRules.minimumEntryCapacity(value));
                        }))
                        .then(doubleSetter("capacity", 1.0, 1.0E15, ManaData::setCoreCapacity))
                        .then(intSetter("vessel", 0, VesselTier.maxId(), ManaData::setVesselTier))
                        .then(doubleSetter("mana", 0.0, Double.MAX_VALUE, ManaData::setMana))
                        .then(doubleSetter("heat", 0.0, ManaData.MAX_PERCENT, ManaData::setBodyHeat)))
                .then(Commands.literal("fill")
                        .requires(source -> source.hasPermission(OP_LEVEL))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> apply(ctx, "fill", data -> data.setMana(data.maxMana())))))
                .then(Commands.literal("heal")
                        .requires(source -> source.hasPermission(OP_LEVEL))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> applyToPlayers(ctx, "heal", (player, data) -> {
                                    data.setFractureTicks(0);
                                    data.setBodyHeat(0.0);
                                    player.removeEffect(ModRegistries.CORE_FRACTURE);
                                    player.removeEffect(ModRegistries.HEMORRHAGE);
                                }))))
                .then(Commands.literal("reset")
                        .requires(source -> source.hasPermission(OP_LEVEL))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> applyToPlayers(ctx, "reset", (player, data) -> {
                                    data.resetAll();
                                    MeditationHandler.release(player);
                                    TrialHandler.abort(player);
                                    player.removeEffect(ModRegistries.CORE_FRACTURE);
                                    player.removeEffect(ModRegistries.HEMORRHAGE);
                                }))))
                .then(Commands.literal("refine")
                        .requires(source -> source.hasPermission(OP_LEVEL))
                        .then(Commands.literal("give")
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .then(Commands.argument("refinement", StringArgumentType.word())
                                                .suggests(REFINEMENT_SUGGESTIONS)
                                                .executes(ctx -> refine(ctx, true)))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .then(Commands.argument("refinement", StringArgumentType.word())
                                                .suggests(REFINEMENT_SUGGESTIONS)
                                                .executes(ctx -> refine(ctx, false))))))
                .then(sigilNode())
                .then(cheatNode())
                .then(passiveNode())
                .then(Commands.literal("trial")
                        .requires(source -> source.hasPermission(OP_LEVEL))
                        .then(Commands.literal("start")
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> trial(ctx, true))))
                        .then(Commands.literal("stop")
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> trial(ctx, false))))));
    }

    private static final SuggestionProvider<CommandSourceStack> SIGIL_SUGGESTIONS = (context, builder) -> {
        List<String> ids = new ArrayList<>();
        ids.add("all");
        for (Sigil sigil : Sigil.VALUES) {
            ids.add(sigil.id());
        }
        return SharedSuggestionProvider.suggest(ids, builder);
    };
    private static final SuggestionProvider<CommandSourceStack> PASSIVE_SUGGESTIONS = (context, builder) -> {
        List<String> ids = new ArrayList<>();
        ids.add("all");
        for (Passive passive : Passive.VALUES) {
            ids.add(passive.id());
        }
        return SharedSuggestionProvider.suggest(ids, builder);
    };

    /** {@code /mana sigil give <targets> <sigil|all>} and {@code /mana sigil forget <targets>}: no puzzle needed. */
    private static LiteralArgumentBuilder<CommandSourceStack> sigilNode() {
        return Commands.literal("sigil")
                .requires(source -> source.hasPermission(OP_LEVEL))
                .then(Commands.literal("give")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("sigil", StringArgumentType.word())
                                        .suggests(SIGIL_SUGGESTIONS)
                                        .executes(ManaCommands::giveSigil))))
                .then(Commands.literal("forget")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> {
                                    Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
                                    for (ServerPlayer player : targets) {
                                        SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
                                        book.forgetAll();
                                        ModNetwork.syncSpells(player, book);
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.translatable("mana_awakened.command.sigil.forget", targets.size()), true);
                                    return targets.size();
                                })));
    }

    /**
     * Quick helpers for testing: {@code /mana cheat all|unlock|spells|modifiers <level>|cooldowns <targets>} ({@code modifiers} sets every
     * modifier but Range).
     * {@code all} = Circle 10, full core, every sigil, passive and refinement, a spell of every sigil that fits, mana filled.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> cheatNode() {
        return Commands.literal("cheat")
                .requires(source -> source.hasPermission(OP_LEVEL))
                .then(Commands.literal("all")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> cheat(ctx, "all", 0))))
                .then(Commands.literal("unlock")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> cheat(ctx, "unlock", 0))))
                .then(Commands.literal("spells")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> cheat(ctx, "spells", 0))))
                .then(Commands.literal("modifiers")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("level", IntegerArgumentType.integer(0, com.manaawakened.spell.Modifier.MAX_LEVEL))
                                        .executes(ctx -> cheat(ctx, "modifiers", IntegerArgumentType.getInteger(ctx, "level"))))))
                .then(Commands.literal("cooldowns")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> cheat(ctx, "cooldowns", 0))));
    }

    private static int cheat(CommandContext<CommandSourceStack> ctx, String what, int level) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        boolean all = what.equals("all");
        for (ServerPlayer player : targets) {
            ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
            SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
            if (all) {
                data.setCircle(CircleTier.MAX_CIRCLE);
                data.setCoreCapacity(CultivationRules.minimumEntryCapacity(CircleTier.MAX_CIRCLE));
                data.setVesselTier(VesselTier.maxId());
            }
            if (all || what.equals("unlock")) {
                for (Sigil sigil : Sigil.VALUES) {
                    book.unlock(sigil);
                }
                PassiveData passives = player.getData(AttachmentRegistries.PASSIVES);
                for (Passive passive : Passive.VALUES) {
                    passives.learn(passive);
                }
                ModNetwork.syncPassives(player, passives);
                for (Refinement refinement : Refinement.VALUES) {
                    RefinementHandler.grant(player, data, refinement);
                }
            }
            if (all || what.equals("spells")) {
                for (Sigil sigil : Sigil.VALUES) {
                    boolean has = false;
                    for (com.manaawakened.spell.SpellRecipe recipe : book.spells()) {
                        has |= recipe.sigil() == sigil;
                    }
                    if (!has && book.isUnlocked(sigil) && book.canCreate()) {
                        book.create(sigil, null);
                    }
                }
            }
            if (what.equals("modifiers")) {
                for (com.manaawakened.spell.SpellRecipe recipe : book.spells()) {
                    for (com.manaawakened.spell.Modifier modifier : com.manaawakened.spell.Modifier.VALUES) {
                        // Range stays as it was (1.0.0): a maxed range threw the aim point far away and made testing awkward.
                        if (modifier != com.manaawakened.spell.Modifier.RANGE && (level == 0 || data.circle() >= modifier.minCircle())) {
                            recipe.setLevel(modifier, Math.min(level, modifier.cap(data.circle())));
                        }
                    }
                }
            }
            if (all || what.equals("cooldowns")) {
                for (com.manaawakened.spell.SpellRecipe recipe : book.spells()) {
                    book.setCooldown(recipe.id(), 0);
                }
            }
            book.markDirty();
            ModNetwork.syncSpells(player, book);
            CultivationRules.normalizeCapacity(data);
            VesselStrainHandler.applyVesselAttributes(player, data);
            if (all) {
                data.setMana(data.maxMana());
            }
            ModNetwork.syncNow(player, data);
        }
        int count = targets.size();
        ctx.getSource().sendSuccess(() -> Component.translatable("mana_awakened.command.cheat." + what, count), true);
        return count;
    }

    private static int giveSigil(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "sigil").toLowerCase(Locale.ROOT);
        boolean all = id.equals("all");
        Sigil chosen = all ? null : Sigil.byId(id);
        if (!all && chosen == null) {
            ctx.getSource().sendFailure(Component.translatable("mana_awakened.command.sigil.unknown", id));
            return 0;
        }
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        // How many players each outcome happened to (one sigil), so the message says what really happened.
        int[] given = new int[SpellBook.Given.values().length];
        for (ServerPlayer player : targets) {
            SpellBook book = player.getData(AttachmentRegistries.SPELL_BOOK);
            for (Sigil sigil : Sigil.VALUES) {
                if (all || sigil == chosen) {
                    given[book.give(sigil).ordinal()]++;
                }
            }
            ModNetwork.syncSpells(player, book);
        }
        if (chosen == null) {
            Component everything = Component.translatable("mana_awakened.command.sigil.everything");
            ctx.getSource().sendSuccess(() -> Component.translatable("mana_awakened.command.sigil.give", everything, targets.size()), true);
            return targets.size();
        }
        Component label = chosen.displayName();
        reportGiven(ctx, "mana_awakened.command.sigil.give", label, given[SpellBook.Given.UNLOCKED.ordinal()]);
        reportGiven(ctx, "mana_awakened.command.sigil.spell_made", label, given[SpellBook.Given.SPELL_MADE.ordinal()]);
        reportGiven(ctx, "mana_awakened.command.sigil.already", label, given[SpellBook.Given.ALREADY.ordinal()]);
        return targets.size();
    }

    private static void reportGiven(CommandContext<CommandSourceStack> ctx, String key, Component label, int players) {
        if (players > 0) {
            ctx.getSource().sendSuccess(() -> Component.translatable(key, label, players), true);
        }
    }

    /** {@code /mana passive give <targets> <passive|all>} and {@code /mana passive forget <targets>}: no scroll needed. */
    private static LiteralArgumentBuilder<CommandSourceStack> passiveNode() {
        return Commands.literal("passive")
                .requires(source -> source.hasPermission(OP_LEVEL))
                .then(Commands.literal("give")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("passive", StringArgumentType.word())
                                        .suggests(PASSIVE_SUGGESTIONS)
                                        .executes(ManaCommands::givePassive))))
                .then(Commands.literal("forget")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> {
                                    Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
                                    for (ServerPlayer player : targets) {
                                        PassiveData data = player.getData(AttachmentRegistries.PASSIVES);
                                        data.forgetAll();
                                        ModNetwork.syncPassives(player, data);
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.translatable("mana_awakened.command.passive.forget", targets.size()), true);
                                    return targets.size();
                                })));
    }

    private static int givePassive(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "passive").toLowerCase(Locale.ROOT);
        boolean all = id.equals("all");
        Passive chosen = all ? null : Passive.byId(id);
        if (!all && chosen == null) {
            ctx.getSource().sendFailure(Component.translatable("mana_awakened.command.passive.unknown", id));
            return 0;
        }
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        for (ServerPlayer player : targets) {
            PassiveData data = player.getData(AttachmentRegistries.PASSIVES);
            for (Passive passive : Passive.VALUES) {
                if (all || passive == chosen) {
                    data.learn(passive);
                }
            }
            ModNetwork.syncPassives(player, data);
        }
        Component label = chosen == null ? Component.translatable("mana_awakened.command.passive.everything") : chosen.displayName();
        ctx.getSource().sendSuccess(() -> Component.translatable("mana_awakened.command.passive.give", label, targets.size()), true);
        return targets.size();
    }

    private interface IntSetter {
        void set(ManaData data, int value);
    }

    private interface DoubleSetter {
        void set(ManaData data, double value);
    }

    private interface DataAction {
        void apply(ManaData data);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> intSetter(String name, int min, int max, IntSetter setter) {
        return Commands.literal(name)
                .then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("value", IntegerArgumentType.integer(min, max))
                                .executes(ctx -> {
                                    int value = IntegerArgumentType.getInteger(ctx, "value");
                                    return apply(ctx, "set." + name, data -> setter.set(data, value));
                                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> doubleSetter(String name, double min, double max, DoubleSetter setter) {
        return Commands.literal(name)
                .then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(min, max))
                                .executes(ctx -> {
                                    double value = DoubleArgumentType.getDouble(ctx, "value");
                                    return apply(ctx, "set." + name, data -> setter.set(data, value));
                                })));
    }

    private static int apply(CommandContext<CommandSourceStack> ctx, String action, DataAction change) throws CommandSyntaxException {
        return applyToPlayers(ctx, action, (player, data) -> change.apply(data));
    }

    private static int applyToPlayers(CommandContext<CommandSourceStack> ctx, String action,
                                      BiConsumer<ServerPlayer, ManaData> change) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        for (ServerPlayer player : targets) {
            ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
            change.accept(player, data);
            CultivationRules.normalizeCapacity(data);
            VesselStrainHandler.applyVesselAttributes(player, data);
            ModNetwork.syncNow(player, data);
        }
        int count = targets.size();
        ctx.getSource().sendSuccess(() -> Component.translatable("mana_awakened.command." + action, count), true);
        return count;
    }

    /** {@code /mana refine give|remove <targets> <refinement|all>}: quiet, no ritual, no material consumed. */
    private static int refine(CommandContext<CommandSourceStack> ctx, boolean give) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "refinement").toLowerCase(Locale.ROOT);
        boolean all = id.equals("all");
        Refinement chosen = all ? null : Refinement.byId(id);
        if (!all && chosen == null) {
            ctx.getSource().sendFailure(Component.translatable("mana_awakened.command.refine.unknown", id));
            return 0;
        }
        Component label = chosen == null ? Component.translatable("mana_awakened.command.refine.everything") : chosen.displayName();
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        for (ServerPlayer player : targets) {
            ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
            for (Refinement refinement : Refinement.VALUES) {
                if (all || refinement == chosen) {
                    if (give) {
                        RefinementHandler.grant(player, data, refinement);
                    } else {
                        data.revoke(refinement);
                    }
                }
            }
            CultivationRules.normalizeCapacity(data);
            VesselStrainHandler.applyVesselAttributes(player, data);
            ModNetwork.syncNow(player, data);
        }
        int count = targets.size();
        ctx.getSource().sendSuccess(() -> Component.translatable(give ? "mana_awakened.command.refine.give" : "mana_awakened.command.refine.remove",
                label, count), true);
        return count;
    }

    /** {@code /mana trial start|stop <targets>}: start the trial for the target's next Circle at once, or end a running one. */
    private static int trial(CommandContext<CommandSourceStack> ctx, boolean start) throws CommandSyntaxException {
        int done = 0;
        for (ServerPlayer player : EntityArgument.getPlayers(ctx, "targets")) {
            ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
            if (start) {
                if (data.isTranscendent() || TrialHandler.isActive(player)) {
                    ctx.getSource().sendFailure(Component.translatable("mana_awakened.command.trial.cannot", player.getDisplayName()));
                    continue;
                }
                BreakthroughHandler.cancel(player, data, null);
                if (TrialHandler.startForced(player, data.circle() + 1)) {
                    done++;
                } else {
                    ctx.getSource().sendFailure(Component.translatable("mana_awakened.command.trial.failed", player.getDisplayName()));
                }
            } else if (TrialHandler.isActive(player)) {
                TrialHandler.abort(player);
                done++;
            }
            ModNetwork.syncNow(player, data);
        }
        int count = done;
        if (count == 0) {
            // Starting has already said why it failed for each target.
            if (!start) {
                ctx.getSource().sendFailure(Component.translatable("mana_awakened.command.trial.none"));
            }
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(start ? "mana_awakened.command.trial.start" : "mana_awakened.command.trial.stop", count), true);
        return done;
    }

    private static int showStatus(CommandSourceStack source, ServerPlayer player) {
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);
        CultivationRules.normalizeCapacity(data);

        source.sendSuccess(() -> Component.translatable("mana_awakened.status.header", player.getDisplayName())
                .withStyle(ChatFormatting.DARK_AQUA), false);
        source.sendSuccess(() -> Component.translatable("mana_awakened.status.circle", CircleTier.label(data.circle()),
                data.vessel().displayName().withStyle(ChatFormatting.WHITE)), false);
        source.sendSuccess(() -> Component.translatable("mana_awakened.status.mana", manaText(data)), false);
        // Purity is derived (refinements and perfect clears), so it is shown here but cannot be set by a command.
        source.sendSuccess(() -> Component.translatable("mana_awakened.status.purity_heat", number(data.purity()), number(ManaData.MAX_PURITY),
                number(data.refinementPurity()), Integer.bitCount(data.perfectClears()), percent(data.bodyHeat())), false);
        source.sendSuccess(() -> breakthroughLine(player, data), false);
        if (TrialHandler.isActive(player)) {
            source.sendSuccess(() -> Component.translatable("mana_awakened.status.trial_active", TrialHandler.guardiansLeft(player))
                    .withStyle(ChatFormatting.LIGHT_PURPLE), false);
        }
        source.sendSuccess(() -> refinementLine(data), false);
        source.sendSuccess(() -> Component.translatable("mana_awakened.status.growth",
                String.format(Locale.ROOT, "%.3f", CultivationRules.trainingRate(data)), String.format(Locale.ROOT, "%.2f", CultivationRules.growthMultiplier(data)))
                .withStyle(ChatFormatting.AQUA), false);
        source.sendSuccess(() -> Component.translatable("mana_awakened.status.foundation",
                String.format(Locale.ROOT, "%.0f", data.foundation() * 100.0), String.format(Locale.ROOT, "%.0f", CultivationRules.leavingFullness(data) * 100.0),
                String.format(Locale.ROOT, "%,d", (long) Math.floor(CultivationRules.ceiling(data))))
                .withStyle(ChatFormatting.AQUA), false);
        if (Refinements.health(data) > 0.0 || Refinements.armor(data) > 0.0 || Refinements.toughness(data) > 0.0) {
            source.sendSuccess(() -> Component.translatable("mana_awakened.status.tempering",
                    number(Refinements.health(data)), number(Refinements.armor(data)), number(Refinements.toughness(data)))
                    .withStyle(ChatFormatting.GOLD), false);
        }
        source.sendSuccess(() -> data.isFractured()
                ? Component.translatable("mana_awakened.status.core_fractured", clock(data.fractureTicks())).withStyle(ChatFormatting.RED)
                : Component.translatable("mana_awakened.status.core_stable").withStyle(ChatFormatting.GREEN), false);
        if (MagicAdapters.isEnabled()) {
            String mods = String.join(", ", MagicAdapters.sharing());
            source.sendSuccess(() -> Component.translatable("mana_awakened.status.unified", mods).withStyle(ChatFormatting.DARK_PURPLE), false);
        }
        Component externalCost = ExternalCostLine.line(data);
        if (externalCost != null) {
            source.sendSuccess(() -> externalCost.copy().withStyle(ChatFormatting.DARK_PURPLE), false);
        }
        return 1;
    }

    private static MutableComponent refinementLine(ManaData data) {
        MutableComponent names = Component.empty();
        int count = 0;
        for (Refinement refinement : Refinement.VALUES) {
            if (data.has(refinement)) {
                if (count > 0) {
                    names.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
                }
                names.append(refinement.displayName().withStyle(refinement.kind().color()));
                count++;
            }
        }
        if (count == 0) {
            names = Component.translatable("mana_awakened.status.refinements_none").withStyle(ChatFormatting.DARK_GRAY);
        }
        return Component.translatable("mana_awakened.status.refinements", count, Refinement.VALUES.length, names).withStyle(ChatFormatting.GRAY);
    }

    private static MutableComponent breakthroughLine(ServerPlayer player, ManaData data) {
        if (data.isTranscendent()) {
            return Component.translatable("mana_awakened.status.breakthrough_max").withStyle(ChatFormatting.YELLOW);
        }
        int target = data.circle() + 1;
        if (!CultivationRules.vesselCanHold(data, target)) {
            return Component.translatable("mana_awakened.status.breakthrough_vessel", CircleTier.roman(target)).withStyle(ChatFormatting.DARK_RED);
        }
        String chance = String.format(Locale.ROOT, "%.0f%%", CultivationRules.successChance(data) * 100.0);
        CultivationRules.Readiness readiness = CultivationRules.readiness(data);
        if (readiness == CultivationRules.Readiness.READY) {
            String key = TrialHandler.isRequired(player, data) ? "mana_awakened.status.breakthrough_ready_trial" : "mana_awakened.status.breakthrough_ready";
            return Component.translatable(key, chance).withStyle(ChatFormatting.GOLD);
        }
        return Component.translatable("mana_awakened.status.breakthrough_waiting", readiness.message(), chance).withStyle(ChatFormatting.GRAY);
    }

    private static String manaText(ManaData data) {
        if (data.isTranscendent()) {
            return "∞";
        }
        return String.format(Locale.ROOT, "%,d / %,d (%.0f%%)", (long) Math.floor(data.mana()), (long) Math.floor(data.maxMana()),
                data.manaFraction() * 100.0);
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.format(Locale.ROOT, "%.0f", value) : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String clock(int ticks) {
        int seconds = (ticks + 19) / 20;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
