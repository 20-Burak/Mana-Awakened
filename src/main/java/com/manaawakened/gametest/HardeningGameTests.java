package com.manaawakened.gametest;

import com.manaawakened.ManaAwakened;
import com.manaawakened.client.fx.CameraNear;
import com.manaawakened.compat.AdapterSet;
import com.manaawakened.compat.ApiProbe;
import com.manaawakened.compat.ApiProbe.Feature;
import com.manaawakened.compat.ApiProbe.Symbol;
import com.manaawakened.compat.MagicAdapter;
import com.manaawakened.compat.MagicAdapters;
import com.manaawakened.core.ExternalMagic;
import com.manaawakened.core.ManaData;
import com.manaawakened.registry.AttachmentRegistries;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforgespi.language.IModInfo;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.Restriction;
import org.apache.maven.artifact.versioning.VersionRange;

/**
 * 1.0.0 before the first upload: the optional dependencies never stop the game from starting (open at the top), a broken or partial
 * integration switches off only itself, the config switches per mod, untested versions are tried with one log line, and a blast's
 * fireball is capped by its distance from the camera.
 */
@GameTestHolder(ManaAwakened.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HardeningGameTests {
    private static final String EMPTY = "empty";

    private HardeningGameTests() {
    }

    // ------------------------------------------------------------------ A1: the fireball cap

    @GameTest(template = EMPTY)
    public static void aBlastFireballIsCappedByItsDistanceFromTheCamera(GameTestHelper helper) {
        helper.assertTrue(CameraNear.ballRadius(100.0F, 3.8F) == 3.8F, "far away the ball keeps its size");
        float atDash = CameraNear.ballRadius(4.0F, 3.8F);
        helper.assertTrue(Math.abs(atDash - 0.35F * 4.0F) < 1.0E-5F, "a Circle-10 Dash blast 4 blocks from the camera is 1.4 blocks, not 3.8: " + atDash);
        helper.assertTrue(CameraNear.fireball(4.0F, atDash) == 1.0F, "and drawn whole: the camera is well outside it");
        helper.assertTrue(CameraNear.ballRadius(0.0F, 5.0F) == 0.0F && CameraNear.ballRadius(2.0F, -1.0F) == 0.0F, "nothing at the camera, nothing for nonsense");
        // the shock rings: as they were from far away, never under a camera close to the blast
        helper.assertTrue(CameraNear.ringRadius(30.0F, 11.0F) == 11.0F, "a meteor's 11-block ring seen from 30 blocks is drawn whole");
        float ring = CameraNear.ringRadius(4.0F, 4.06F);
        helper.assertTrue(Math.abs(ring - 0.65F * 4.0F) < 1.0E-5F, "a Dash ring 4 blocks from the camera stops at 2.6 blocks, short of it: " + ring);
        for (float distance = 0.0F; distance <= 60.0F; distance += 0.25F) {
            helper.assertTrue(CameraNear.ringRadius(distance, 50.0F) <= (1.0F - CameraNear.CAP) * distance + 1.0E-4F, "a ring never reaches the camera at " + distance);
        }
        for (float distance = 0.0F; distance <= 60.0F; distance += 0.25F) {
            for (float radius = 0.0F; radius <= 20.0F; radius += 0.5F) {
                float ball = CameraNear.ballRadius(distance, radius);
                helper.assertTrue(ball <= radius + 1.0E-5F && ball <= CameraNear.CAP * distance + 1.0E-5F,
                        "the ball is never bigger than itself or than " + CameraNear.CAP + " x the distance: " + distance + " / " + radius + " -> " + ball);
            }
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ B: the optional dependency ranges

    /** The mods.toml this build ships, as NeoForge read it: every optional dependency is open at the top, and starts where we tested. */
    @GameTest(template = EMPTY)
    public static void everyOptionalDependencyIsOpenAtTheTop(GameTestHelper helper) {
        IModInfo info = ModList.get().getModContainerById(ManaAwakened.MOD_ID).orElseThrow().getModInfo();
        Map<String, String> expectedLower = new HashMap<>();
        for (Map.Entry<String, String> tested : MagicAdapters.TESTED.entrySet()) {
            expectedLower.put(tested.getKey(), lowerBound(range(tested.getValue())));
        }
        expectedLower.put("curios", "9.0.5+1.21.0"); // what Iron's Spells itself declares
        int optional = 0;
        for (IModInfo.ModVersion dependency : info.getDependencies()) {
            if (dependency.getType() != IModInfo.DependencyType.OPTIONAL) {
                continue;
            }
            optional++;
            VersionRange range = dependency.getVersionRange();
            List<Restriction> restrictions = range.getRestrictions();
            Restriction last = restrictions.get(restrictions.size() - 1);
            helper.assertTrue(last.getUpperBound() == null, dependency.getModId() + " must be open at the top, is " + range);
            String lower = lowerBound(range);
            helper.assertTrue(expectedLower.containsKey(dependency.getModId()), "an optional dependency nobody measured: " + dependency.getModId());
            helper.assertTrue(expectedLower.get(dependency.getModId()).equals(lower),
                    dependency.getModId() + " starts at the oldest build that passed, " + expectedLower.get(dependency.getModId()) + ", not " + lower);
            helper.assertTrue(dependency.getOrdering() == IModInfo.Ordering.AFTER, dependency.getModId() + " loads before us");
        }
        helper.assertTrue(optional == expectedLower.size(), "Iron's, Ars, Mahou and Curios are optional, found " + optional);
        helper.succeed();
    }

    private static VersionRange range(String spec) {
        try {
            return VersionRange.createFromVersionSpec(spec);
        } catch (org.apache.maven.artifact.versioning.InvalidVersionSpecificationException invalid) {
            throw new IllegalArgumentException(spec, invalid);
        }
    }

    private static String lowerBound(VersionRange range) {
        ArtifactVersion lower = range.getRestrictions().get(0).getLowerBound();
        return lower == null ? "" : lower.toString();
    }

    // ------------------------------------------------------------------ C5: untested versions are tried

    @GameTest(template = EMPTY)
    public static void anUntestedVersionIsTriedWithOneNote(GameTestHelper helper) {
        helper.assertTrue(MagicAdapters.untestedNote(ExternalMagic.IRONS, "1.21.1-3.16.3") == null, "ours is tested");
        helper.assertTrue(MagicAdapters.untestedNote(ExternalMagic.IRONS, "1.21-3.4.4") == null, "the oldest is tested");
        helper.assertTrue(MagicAdapters.untestedNote(ExternalMagic.IRONS, "1.21.1-3.12.2") == null, "one in between counts as tested");
        helper.assertTrue(MagicAdapters.untestedNote(ExternalMagic.ARS, "5.8.2") == null && MagicAdapters.untestedNote(ExternalMagic.MAHOU, "1.21.1-v1.36.28") == null,
                "the ends of the other ranges are tested");
        String newer = MagicAdapters.untestedNote(ExternalMagic.IRONS, "1.21.1-3.17.0");
        helper.assertTrue(newer != null && newer.contains("irons_spellbooks 1.21.1-3.17.0") && newer.contains("untested") && newer.contains("trying anyway"),
                "a newer Iron's is tried, with a note: " + newer);
        helper.assertTrue(MagicAdapters.untestedNote(ExternalMagic.ARS, "5.14.0") != null && MagicAdapters.untestedNote(ExternalMagic.ARS, "5.0.8") != null,
                "Ars outside 5.8.2..5.13.3 gets the note");
        helper.assertTrue(MagicAdapters.untestedNote(ExternalMagic.MAHOU, "1.21.1-v1.36.30") != null, "a newer Mahou gets the note");
        helper.assertTrue(MagicAdapters.untestedNote("some_other_mod", "1.0") == null, "a mod without a tested range: nothing");
        helper.succeed();
    }

    // ------------------------------------------------------------------ C2: the switches per mod

    @GameTest(template = EMPTY)
    public static void eachMagicModHasItsOwnSwitchUnderTheGlobalOne(GameTestHelper helper) {
        helper.assertTrue(MagicAdapters.allows(ExternalMagic.IRONS, true, true, true, true), "everything on: Iron's shares");
        helper.assertTrue(!MagicAdapters.allows(ExternalMagic.IRONS, true, false, true, true), "unifyIrons off: Iron's does not");
        helper.assertTrue(MagicAdapters.allows(ExternalMagic.ARS, true, false, true, true) && MagicAdapters.allows(ExternalMagic.MAHOU, true, false, true, true),
                "Iron's off leaves Ars and Mahou on");
        helper.assertTrue(!MagicAdapters.allows(ExternalMagic.ARS, true, true, false, true) && !MagicAdapters.allows(ExternalMagic.MAHOU, true, true, true, false),
                "Ars and Mahou have their own switches");
        for (String mod : new String[] {ExternalMagic.IRONS, ExternalMagic.ARS, ExternalMagic.MAHOU}) {
            helper.assertTrue(!MagicAdapters.allows(mod, false, true, true, true), "unifyMagicMods off turns " + mod + " off whatever its own switch says");
        }
        // The live config: defaults are all on, so every installed mod shares.
        for (MagicAdapter adapter : MagicAdapters.active()) {
            helper.assertTrue(MagicAdapters.sharing().contains(adapter.modId()), adapter.modId() + " shares by default");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ C1: a broken adapter switches off only itself

    /** A stand-in adapter: counts its ticks, and throws on them when asked to. */
    private static final class FakeAdapter implements MagicAdapter {
        private final String modId;
        private final boolean throwsOnTick;
        private final List<String> off;
        private int ticks;

        FakeAdapter(String modId, boolean throwsOnTick, List<String> off) {
            this.modId = modId;
            this.throwsOnTick = throwsOnTick;
            this.off = off;
        }

        @Override
        public String modId() {
            return modId;
        }

        @Override
        public void tick(ServerPlayer player, ManaData data) {
            ticks++;
            if (throwsOnTick) {
                throw new IllegalStateException("broken on purpose");
            }
        }

        @Override
        public void forceSync(ServerPlayer player, ManaData data) {
            tick(player, data);
        }

        @Override
        public void forget(ServerPlayer player) {
            ticks = 0;
        }

        @Override
        public List<String> offFeatures() {
            return off;
        }
    }

    @GameTest(template = EMPTY)
    public static void aBrokenAdapterSwitchesOffOnlyItself(GameTestHelper helper) {
        List<String> warnings = new ArrayList<>();
        AdapterSet set = new AdapterSet(warnings::add);
        ServerPlayer player = TestPlayers.mock(helper);
        ManaData data = player.getData(AttachmentRegistries.MANA_DATA);

        // cannot start: the installed build lacks an essential part
        boolean started = set.attach("fake_unavailable", "9.9", () -> {
            throw new ApiProbe.Unavailable(List.of("mana_mirror (missing Fake.getMana())"));
        });
        helper.assertTrue(!started && warnings.size() == 1, "an adapter that cannot start is not added, with one warning: " + warnings);
        helper.assertTrue(warnings.get(0).contains("fake_unavailable 9.9") && warnings.get(0).contains("is off") && warnings.get(0).contains("Fake.getMana()"),
                "the warning names the mod, its version and what is missing: " + warnings.get(0));
        // a class of the mod that is gone
        helper.assertTrue(!set.attach("fake_linkage", "1.2", () -> {
            throw new NoClassDefFoundError("fake/Missing");
        }) && warnings.size() == 2 && warnings.get(1).contains("fake_linkage 1.2"), "a missing class switches that mod off with one warning");

        FakeAdapter ok = new FakeAdapter("fake_ok", false, List.of());
        FakeAdapter broken = new FakeAdapter("fake_broken", true, List.of());
        FakeAdapter partial = new FakeAdapter("fake_partial", false, List.of("client_sync (missing Fake.sync())"));
        helper.assertTrue(set.attach("fake_ok", "1.0", () -> ok) && set.attach("fake_broken", "2.0", () -> broken), "both start");
        helper.assertTrue(warnings.size() == 2, "a whole adapter starts without a warning");
        helper.assertTrue(set.attach("fake_partial", "3.0", () -> partial) && warnings.size() == 3 && warnings.get(2).contains("fake_partial 3.0")
                && warnings.get(2).contains("client_sync"), "a partial adapter runs, with one warning listing what is off: " + warnings);

        set.forEach(adapter -> adapter.tick(player, data));
        helper.assertTrue(!set.has("fake_broken") && set.has("fake_ok") && set.has("fake_partial"), "the one that threw is off, the others run");
        helper.assertTrue(warnings.size() == 4 && warnings.get(3).contains("fake_broken 2.0") && warnings.get(3).contains("broken on purpose"),
                "one warning for it, naming the mod and its version: " + warnings);
        set.forEach(adapter -> adapter.tick(player, data));
        set.forEach(adapter -> adapter.tick(player, data));
        helper.assertTrue(ok.ticks == 3 && partial.ticks == 3 && broken.ticks == 1, "the others kept ticking, the broken one was never called again: "
                + ok.ticks + " / " + partial.ticks + " / " + broken.ticks);
        helper.assertTrue(warnings.size() == 4, "no more warnings");
        helper.succeed();
    }

    // ------------------------------------------------------------------ C4: a missing symbol degrades only its feature

    /** A public field for the probe to find. */
    public static final class Probed {
        public int value = 7;
    }

    @GameTest(template = EMPTY)
    public static void aMissingSymbolDegradesOnlyItsFeature(GameTestHelper helper) {
        Symbol length = Symbol.method("java.lang.String", "sizeOf|length", "int");
        Symbol field = Symbol.field(Probed.class.getName(), "value", "int");
        Symbol constructor = Symbol.constructor("java.lang.StringBuilder", "java.lang.String");
        List<Feature> features = List.of(
                Feature.essential("core", Symbol.type("java.lang.String"), length, Symbol.staticField("java.lang.Integer", "MAX_VALUE", "int"), field),
                Feature.optional("extra", Symbol.method("java.lang.String", "noSuchMethod", "int"), constructor),
                Feature.optional("statics", Symbol.staticMethod("java.lang.String", "valueOf", "java.lang.String", "int")));
        ApiProbe.Result result = ApiProbe.resolve(HardeningGameTests.class.getClassLoader(), features);
        helper.assertTrue(result.usable() && result.isOn("core") && result.isOn("statics"), "the essential part and the other optional part are on");
        helper.assertTrue(!result.isOn("extra"), "a feature with a missing method is off");
        helper.assertTrue(result.offFeatures().size() == 1 && result.offFeatures().get(0).contains("String.noSuchMethod()"),
                "and only that one, named with what it misses: " + result.offFeatures());
        try {
            MethodHandle handle = result.handle(length);
            helper.assertTrue(handle != null && (int) handle.invoke("abcd") == 4, "a renamed member is found by its next name, and the handle is kept");
            helper.assertTrue((int) result.handle(field).invoke(new Probed()) == 7, "a field's getter is kept");
            helper.assertTrue(result.handle(constructor) != null, "a constructor in a feature that is off still resolved on its own");
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
        ApiProbe.Result broken = ApiProbe.resolve(HardeningGameTests.class.getClassLoader(),
                List.of(Feature.essential("core", Symbol.type("com.example.NotThere")), Feature.optional("extra", length)));
        helper.assertTrue(!broken.usable() && broken.isOn("extra"), "a missing essential class makes the whole adapter unusable, nothing else");
        helper.succeed();
    }

    /**
     * With the magic mods installed, every part of every adapter works with the installed builds (the mutation check of the task:
     * one resolved handle nulled makes this fail while the adapter stays on). Without them there is nothing to check.
     */
    @GameTest(template = EMPTY)
    public static void theInstalledMagicModsResolveEveryFeature(GameTestHelper helper) {
        for (String mod : new String[] {ExternalMagic.IRONS, ExternalMagic.ARS, ExternalMagic.MAHOU}) {
            boolean running = MagicAdapters.active().stream().anyMatch(adapter -> adapter.modId().equals(mod));
            helper.assertTrue(running == ModList.get().isLoaded(mod), mod + ": installed " + ModList.get().isLoaded(mod) + " but adapter running " + running);
        }
        for (MagicAdapter adapter : MagicAdapters.active()) {
            helper.assertTrue(adapter.offFeatures().isEmpty(), adapter.modId() + " runs with parts off: " + adapter.offFeatures());
        }
        helper.succeed();
    }
}
