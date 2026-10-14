package com.manaawakened.registry;

import com.manaawakened.ManaAwakened;
import com.manaawakened.effect.CoreFractureEffect;
import com.manaawakened.effect.HemorrhageEffect;
import com.manaawakened.block.InscriptionAltarBlock;
import com.manaawakened.block.ManaCondenserBlock;
import com.manaawakened.block.ManaCondenserBlockEntity;
import com.manaawakened.block.MeditationCoreBlock;
import com.manaawakened.block.RunePlateBlock;
import com.manaawakened.item.CodexItem;
import com.manaawakened.entity.FloatingDragonHeart;
import com.manaawakened.item.DragonHeartItem;
import com.manaawakened.item.PassiveScrollItem;
import com.manaawakened.passive.Passive;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Central place that hooks every deferred register of the mod onto the mod event bus.
 */
public final class ModRegistries {
    public static final DeferredRegister<MobEffect> MOB_EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, ManaAwakened.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ManaAwakened.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ManaAwakened.MOD_ID);
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ManaAwakened.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ManaAwakened.MOD_ID);
    public static final DeferredRegister.DataComponents DATA_COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, ManaAwakened.MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, ManaAwakened.MOD_ID);

    /** Visual marker for a fractured core. The real state lives in {@link com.manaawakened.core.ManaData}. */
    public static final DeferredHolder<MobEffect, CoreFractureEffect> CORE_FRACTURE = MOB_EFFECTS.register("core_fracture", CoreFractureEffect::new);

    /** "Massive bleeding" after a vessel rupture: no healing, dripping blood. */
    public static final DeferredHolder<MobEffect, HemorrhageEffect> HEMORRHAGE = MOB_EFFECTS.register("hemorrhage", HemorrhageEffect::new);

    /** Dropped by dragons; absorbed through meditation to reforge the body. */
    public static final DeferredItem<DragonHeartItem> PRIMORDIAL_DRAGON_HEART = ITEMS.register("primordial_dragon_heart", DragonHeartItem::new);
    /** The heart a slain Ender Dragon leaves floating above its exit portal (1.0.3, {@link FloatingDragonHeart}). */
    public static final DeferredHolder<EntityType<?>, EntityType<FloatingDragonHeart>> FLOATING_DRAGON_HEART = ENTITY_TYPES.register("floating_dragon_heart",
            () -> EntityType.Builder.<FloatingDragonHeart>of(FloatingDragonHeart::new, MobCategory.MISC).sized(0.5F, 0.5F).fireImmune()
                    .clientTrackingRange(10).updateInterval(20).build("floating_dragon_heart"));

    /**
     * The sculk-veined channels of a Warden: a body-refinement material (item tag {@code mana_awakened:refinement/warden_meridians}).
     * Dropped by entities in {@link #WARDEN_MERIDIAN_BEARERS}.
     */
    public static final DeferredItem<Item> WARDEN_MERIDIAN = ITEMS.registerSimpleItem("warden_meridian", new Item.Properties()
            .stacksTo(16).rarity(Rarity.EPIC).fireResistant().component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true));

    /** The sleepless eye of an Elder Guardian (item tag {@code mana_awakened:refinement/abyssal_eye}). Dropped by {@link #ABYSSAL_EYE_BEARERS}. */
    public static final DeferredItem<Item> ABYSSAL_EYE = ITEMS.registerSimpleItem("abyssal_eye", new Item.Properties()
            .stacksTo(16).rarity(Rarity.RARE).fireResistant().component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true));

    /** Which passive a Passive Scroll teaches (a passive id). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> PASSIVE_COMPONENT =
            DATA_COMPONENTS.registerComponentType("passive", builder -> builder
                    .persistent(com.mojang.serialization.Codec.STRING)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8));

    public static final DeferredItem<PassiveScrollItem> PASSIVE_SCROLL = ITEMS.register("passive_scroll", PassiveScrollItem::new);

    /** The in-game guide: cultivation, refinements and where to find their materials, circles, spells, passives. */
    public static final DeferredItem<CodexItem> CODEX = ITEMS.register("codex", CodexItem::new);

    public static final DeferredBlock<InscriptionAltarBlock> INSCRIPTION_ALTAR = BLOCKS.registerBlock("inscription_altar", InscriptionAltarBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(2.5F).sound(SoundType.WOOD).noOcclusion());
    public static final DeferredItem<BlockItem> INSCRIPTION_ALTAR_ITEM = ITEMS.registerSimpleBlockItem("inscription_altar", INSCRIPTION_ALTAR);

    public static final DeferredBlock<RunePlateBlock> RUNE_PLATE = BLOCKS.registerBlock("rune_plate", RunePlateBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).strength(1.5F, 6.0F).sound(SoundType.AMETHYST)
                    .lightLevel(state -> state.getValue(RunePlateBlock.FORMED) ? 9 : 0).requiresCorrectToolForDrops());
    public static final DeferredItem<BlockItem> RUNE_PLATE_ITEM = ITEMS.registerSimpleBlockItem("rune_plate", RUNE_PLATE);

    public static final DeferredBlock<MeditationCoreBlock> MEDITATION_CORE = BLOCKS.registerBlock("meditation_core", MeditationCoreBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(3.0F, 8.0F).sound(SoundType.AMETHYST).lightLevel(state -> 8 + 2 * Math.min(3, state.getValue(MeditationCoreBlock.TIER))).requiresCorrectToolForDrops());
    public static final DeferredItem<BlockItem> MEDITATION_CORE_ITEM = ITEMS.registerSimpleBlockItem("meditation_core", MEDITATION_CORE);

    public static final DeferredBlock<ManaCondenserBlock> MANA_CONDENSER = BLOCKS.registerBlock("mana_condenser", ManaCondenserBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_CYAN).strength(3.5F, 8.0F).sound(SoundType.METAL)
                    .lightLevel(state -> state.getValue(ManaCondenserBlock.LIT) ? 10 : 0).requiresCorrectToolForDrops());
    public static final DeferredItem<BlockItem> MANA_CONDENSER_ITEM = ITEMS.registerSimpleBlockItem("mana_condenser", MANA_CONDENSER);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ManaCondenserBlockEntity>> MANA_CONDENSER_BE =
            BLOCK_ENTITIES.register("mana_condenser", () -> BlockEntityType.Builder.of(ManaCondenserBlockEntity::new, MANA_CONDENSER.get()).build(null));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.mana_awakened"))
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> PRIMORDIAL_DRAGON_HEART.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(PRIMORDIAL_DRAGON_HEART.get());
                output.accept(WARDEN_MERIDIAN.get());
                output.accept(ABYSSAL_EYE.get());
                output.accept(CODEX.get());
                output.accept(INSCRIPTION_ALTAR_ITEM.get());
                output.accept(MEDITATION_CORE_ITEM.get());
                output.accept(RUNE_PLATE_ITEM.get());
                output.accept(MANA_CONDENSER_ITEM.get());
                for (Passive passive : Passive.VALUES) {
                    output.accept(PassiveScrollItem.of(passive));
                }
            })
            .build());

    /** Entities that drop a Primordial Dragon Heart: {@code data/mana_awakened/tags/entity_type/dragon_heart_bearers.json}. */
    public static final TagKey<EntityType<?>> DRAGON_HEART_BEARERS = TagKey.create(Registries.ENTITY_TYPE, ManaAwakened.id("dragon_heart_bearers"));

    /** Entities that drop Warden Meridians: {@code data/mana_awakened/tags/entity_type/warden_meridian_bearers.json}. */
    public static final TagKey<EntityType<?>> WARDEN_MERIDIAN_BEARERS = TagKey.create(Registries.ENTITY_TYPE, ManaAwakened.id("warden_meridian_bearers"));

    /** Entities that drop an Abyssal Eye: {@code data/mana_awakened/tags/entity_type/abyssal_eye_bearers.json}. */
    public static final TagKey<EntityType<?>> ABYSSAL_EYE_BEARERS = TagKey.create(Registries.ENTITY_TYPE, ManaAwakened.id("abyssal_eye_bearers"));

    private ModRegistries() {
    }

    public static void register(IEventBus modBus) {
        MOB_EFFECTS.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        CREATIVE_TABS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        DATA_COMPONENTS.register(modBus);
        ENTITY_TYPES.register(modBus);
        AttachmentRegistries.ATTACHMENT_TYPES.register(modBus);
    }
}
