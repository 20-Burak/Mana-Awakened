package com.manaawakened.network;

import com.manaawakened.ManaAwakened;
import com.manaawakened.spell.Sigil;
import com.manaawakened.spell.SpellBook;
import com.manaawakened.spell.SpellRecipe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → owning client: unlocked sigils, spells, the selected spell and running cooldowns. */
public record SyncSpellsPayload(List<Sigil> unlocked, List<SpellRecipe> spells, int selected, Map<Integer, Integer> cooldowns) implements CustomPacketPayload {
    public static final Type<SyncSpellsPayload> TYPE = new Type<>(ManaAwakened.id("sync_spells"));
    public static final StreamCodec<FriendlyByteBuf, SyncSpellsPayload> STREAM_CODEC =
            CustomPacketPayload.codec(SyncSpellsPayload::write, SyncSpellsPayload::read);

    public static SyncSpellsPayload of(SpellBook book) {
        return new SyncSpellsPayload(new ArrayList<>(book.unlocked()), book.spellList(), book.selected(), new HashMap<>(book.cooldowns()));
    }

    private static SyncSpellsPayload read(FriendlyByteBuf buf) {
        int unlockedCount = Math.min(Sigil.VALUES.length, buf.readVarInt());
        List<Sigil> unlocked = new ArrayList<>();
        for (int i = 0; i < unlockedCount; i++) {
            Sigil sigil = Sigil.byOrdinal(buf.readVarInt());
            if (sigil != null) {
                unlocked.add(sigil);
            }
        }
        int spellCount = Math.min(SpellBook.MAX_SPELLS, buf.readVarInt());
        List<SpellRecipe> spells = new ArrayList<>();
        for (int i = 0; i < spellCount; i++) {
            SpellRecipe recipe = SpellRecipe.read(buf);
            if (recipe != null) {
                spells.add(recipe);
            }
        }
        int selected = buf.readVarInt() - 1;
        int cooldownCount = Math.min(SpellBook.MAX_SPELLS, buf.readVarInt());
        Map<Integer, Integer> cooldowns = new HashMap<>();
        for (int i = 0; i < cooldownCount; i++) {
            cooldowns.put(buf.readVarInt(), buf.readVarInt());
        }
        return new SyncSpellsPayload(unlocked, spells, selected, cooldowns);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(unlocked.size());
        for (Sigil sigil : unlocked) {
            buf.writeVarInt(sigil.ordinal());
        }
        int count = Math.min(SpellBook.MAX_SPELLS, spells.size());
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            spells.get(i).write(buf);
        }
        buf.writeVarInt(selected + 1);
        int cooldownCount = Math.min(SpellBook.MAX_SPELLS, cooldowns.size());
        buf.writeVarInt(cooldownCount);
        int written = 0;
        for (Map.Entry<Integer, Integer> entry : cooldowns.entrySet()) {
            if (written++ >= cooldownCount) {
                break;
            }
            buf.writeVarInt(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
    }

    public void applyTo(SpellBook book) {
        book.applySnapshot(unlocked, spells, selected, cooldowns);
    }

    @Override
    public Type<SyncSpellsPayload> type() {
        return TYPE;
    }
}
