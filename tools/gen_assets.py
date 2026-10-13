#!/usr/bin/env python3
"""Generates the data-driven resources of Mana Awakened 2.x: block/item models, loot, tags, advancements, recipes
(including one scroll recipe per passive, read from Passive.java) and the procedural textures.
Run from anywhere; it writes into src/main/resources. Safe to re-run."""
import json, os, random, re, shutil
from PIL import Image, ImageDraw

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), '..'))
RES = os.path.join(ROOT, 'src/main/resources')
ASSETS = os.path.join(RES, 'assets/mana_awakened')
DATA = os.path.join(RES, 'data/mana_awakened')
PASSIVE_JAVA = os.path.join(ROOT, 'src/main/java/com/manaawakened/passive/Passive.java')


def put(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write('\n')


def clamp(v):
    return max(0, min(255, int(v)))


def noisy(base, amount, rnd):
    d = rnd.randint(-amount, amount)
    return tuple(clamp(c + d) for c in base) + (255,)


# ------------------------------------------------------------------ textures

def save(img, rel):
    path = os.path.join(ASSETS, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


N_, E_, S_, W_ = 1, 2, 4, 8


def plate_frame(mask, base, rim, inlay, soft, glow, seed=11):
    rnd = random.Random(seed)
    img = Image.new('RGBA', (16, 16))
    px = img.load()
    for x in range(16):
        for y in range(16):
            px[x, y] = noisy(base, 5, rnd)
    # the sides that face the outside: rim on the edge row, glowing inlay on the one inside it (rims first so that the
    # inlay lines run on to the very edge and meet the next plate's)
    for i in range(16):
        if not mask & N_:
            px[i, 0] = noisy(rim, 3, rnd)
        if not mask & S_:
            px[i, 15] = noisy(rim, 3, rnd)
        if not mask & W_:
            px[0, i] = noisy(rim, 3, rnd)
        if not mask & E_:
            px[15, i] = noisy(rim, 3, rnd)
    for i in range(16):
        if not mask & N_:
            px[i, 1] = noisy(inlay, 4, rnd)
        if not mask & S_:
            px[i, 14] = noisy(inlay, 4, rnd)
        if not mask & W_:
            px[1, i] = noisy(inlay, 4, rnd)
        if not mask & E_:
            px[14, i] = noisy(inlay, 4, rnd)
    # a small rune in the middle of every plate, dim so that a whole floor reads as one pattern
    for (x, y) in [(7, 5), (8, 5), (5, 7), (5, 8), (10, 7), (10, 8), (7, 10), (8, 10)]:
        px[x, y] = soft
    for (x, y) in [(7, 7), (8, 7), (7, 8), (8, 8)]:
        px[x, y] = glow
    return img


def scaled(color, k):
    return tuple(clamp(c * k) for c in color) + (255,)


def rune_plate():
    """The plate comes in 16 looks, one per combination of joined sides (N=1, E=2, S=4, W=8): a side that touches another
    plate or the core has no border, so neighbouring plates melt into one inlaid floor. Only the sides that face the outside
    get a dark rim and a thin glowing inlay line. Every look also exists "formed" (the plate belongs to a completed ring of a
    Meditation Circle): golden inlay and rune, pulsing through four animation frames."""
    for mask in range(16):
        img = plate_frame(mask, (60, 70, 116), (28, 34, 66), (84, 178, 236), (92, 168, 226, 255), (140, 228, 255, 255))
        save(img, 'textures/block/rune_plate_top_%d.png' % mask)
        strip = Image.new('RGBA', (16, 64))
        for frame, k in enumerate((0.85, 1.0, 1.25, 1.0)):
            strip.paste(plate_frame(mask, (46, 50, 92), (74, 52, 24), scaled((236, 186, 84), k)[:3], scaled((232, 184, 92), k), scaled((255, 238, 176), k), 31), (0, 16 * frame))
        save(strip, 'textures/block/rune_plate_top_f%d.png' % mask)
        put(os.path.join(ASSETS, 'textures/block/rune_plate_top_f%d.png.mcmeta' % mask), {'animation': {'frametime': 7, 'interpolate': True}})
    rnd = random.Random(12)
    side = Image.new('RGBA', (16, 16))
    sp = side.load()
    for x in range(16):
        for y in range(16):
            sp[x, y] = noisy((44, 52, 92), 5, rnd)
    save(side, 'textures/block/rune_plate_side.png')
    side_f = Image.new('RGBA', (16, 16))
    sf = side_f.load()
    rnd = random.Random(13)
    for x in range(16):
        for y in range(16):
            sf[x, y] = noisy((50, 54, 96), 5, rnd)
    for x in range(16):
        sf[x, 0] = noisy((196, 150, 70), 3, rnd)
    save(side_f, 'textures/block/rune_plate_side_formed.png')


def meditation_core():
    rnd = random.Random(21)
    top = Image.new('RGBA', (16, 16))
    px = top.load()
    for x in range(16):
        for y in range(16):
            d = max(abs(x - 7.5), abs(y - 7.5))
            ring = int(d) % 3
            base = [(74, 44, 130), (112, 74, 188), (60, 34, 110)][ring]
            px[x, y] = noisy(base, 6, rnd)
    for (x, y) in [(7, 7), (8, 7), (7, 8), (8, 8)]:
        px[x, y] = (255, 226, 130, 255)
    for (x, y) in [(7, 5), (8, 5), (7, 10), (8, 10), (5, 7), (5, 8), (10, 7), (10, 8)]:
        px[x, y] = (236, 190, 90, 255)
    save(top, 'textures/block/meditation_core_top.png')
    side = Image.new('RGBA', (16, 16))
    sp = side.load()
    for x in range(16):
        for y in range(16):
            facet = (x // 4 + (y // 8)) % 2
            base = (72, 42, 128) if facet else (56, 32, 104)
            sp[x, y] = noisy(base, 5, rnd)
    for (x, y) in [(6, 6), (7, 6), (8, 6), (9, 6), (7, 7), (8, 7), (7, 8), (8, 8), (6, 9), (9, 9)]:
        sp[x, y] = (200, 160, 255, 255)
    save(side, 'textures/block/meditation_core_side.png')
    # the awakened core: gold and white, pulsing
    top_strip = Image.new('RGBA', (16, 64))
    side_strip = Image.new('RGBA', (16, 64))
    for frame, k in enumerate((0.85, 1.0, 1.25, 1.0)):
        rnd = random.Random(41)
        t = Image.new('RGBA', (16, 16))
        tp = t.load()
        for x in range(16):
            for y in range(16):
                d = max(abs(x - 7.5), abs(y - 7.5))
                ring = int(d) % 3
                base = [(96, 62, 150), (150, 108, 220), (80, 52, 130)][ring]
                tp[x, y] = noisy(base, 6, rnd)
        for i in range(2, 14):
            tp[i, 2] = scaled((255, 214, 120), k)
            tp[i, 13] = scaled((255, 214, 120), k)
            tp[2, i] = scaled((255, 214, 120), k)
            tp[13, i] = scaled((255, 214, 120), k)
        for (x, y) in [(7, 7), (8, 7), (7, 8), (8, 8)]:
            tp[x, y] = scaled((255, 250, 220), k)
        for (x, y) in [(7, 5), (8, 5), (7, 10), (8, 10), (5, 7), (5, 8), (10, 7), (10, 8)]:
            tp[x, y] = scaled((255, 226, 130), k)
        top_strip.paste(t, (0, 16 * frame))
        sd = Image.new('RGBA', (16, 16))
        sp2 = sd.load()
        for x in range(16):
            for y in range(16):
                facet = (x // 4 + (y // 8)) % 2
                base = (92, 56, 150) if facet else (72, 44, 124)
                sp2[x, y] = noisy(base, 5, rnd)
        for (x, y) in [(6, 6), (7, 6), (8, 6), (9, 6), (7, 7), (8, 7), (7, 8), (8, 8), (6, 9), (9, 9)]:
            sp2[x, y] = scaled((255, 226, 150), k)
        side_strip.paste(sd, (0, 16 * frame))
    save(top_strip, 'textures/block/meditation_core_top_active.png')
    save(side_strip, 'textures/block/meditation_core_side_active.png')
    for name in ('meditation_core_top_active', 'meditation_core_side_active'):
        put(os.path.join(ASSETS, 'textures/block/%s.png.mcmeta' % name), {'animation': {'frametime': 7, 'interpolate': True}})


def mana_condenser():
    for lit in (False, True):
        rnd = random.Random(31)
        side = Image.new('RGBA', (16, 16))
        sp = side.load()
        for x in range(16):
            for y in range(16):
                edge = x in (0, 15) or y in (0, 15)
                sp[x, y] = noisy((78, 86, 100) if edge else (112, 122, 138), 5, rnd)
        strip = (90, 235, 255, 255) if lit else (28, 70, 88, 255)
        mid = (170, 250, 255, 255) if lit else (40, 96, 118, 255)
        for x in range(3, 13):
            for y in (6, 7, 8, 9):
                sp[x, y] = strip
        for x in range(4, 12):
            sp[x, 7] = mid
            sp[x, 8] = mid
        for (x, y) in [(2, 2), (13, 2), (2, 13), (13, 13)]:
            sp[x, y] = (60, 66, 78, 255)
        save(side, 'textures/block/mana_condenser_side%s.png' % ('_lit' if lit else ''))
        top = Image.new('RGBA', (16, 16))
        tp = top.load()
        for x in range(16):
            for y in range(16):
                edge = x in (0, 15) or y in (0, 15)
                tp[x, y] = noisy((70, 78, 92) if edge else (100, 110, 126), 5, rnd)
        coil = (80, 220, 245, 255) if lit else (30, 80, 100, 255)
        for r, color in ((5, coil), (3, (150, 245, 255, 255) if lit else (44, 100, 122, 255))):
            for x in range(16):
                for y in range(16):
                    d = max(abs(x - 7.5), abs(y - 7.5))
                    if abs(d - r) < 0.6:
                        tp[x, y] = color
        for (x, y) in [(7, 7), (8, 7), (7, 8), (8, 8)]:
            tp[x, y] = (230, 255, 255, 255) if lit else (60, 120, 140, 255)
        save(top, 'textures/block/mana_condenser_top%s.png' % ('_lit' if lit else ''))
    bottom = Image.new('RGBA', (16, 16), (60, 66, 78, 255))
    save(bottom, 'textures/block/mana_condenser_bottom.png')


def items():
    # Passive scroll: a rolled parchment with a violet seal.
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for i in range(11):
        x, y = 2 + i, 13 - i
        d.rectangle([x - 1, y - 2, x + 1, y], fill=(232, 214, 168, 255))
        d.point((x - 1, y - 2), fill=(196, 170, 120, 255))
    d.rectangle([1, 12, 4, 15], fill=(186, 150, 96, 255))
    d.rectangle([11, 0, 14, 3], fill=(186, 150, 96, 255))
    d.ellipse([6, 6, 9, 9], fill=(150, 90, 230, 255))
    d.point((7, 7), fill=(235, 215, 255, 255))
    save(img, 'textures/item/passive_scroll.png')
    # Codex: a purple book with a gold star.
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([2, 1, 13, 14], fill=(86, 52, 150, 255))
    d.rectangle([2, 1, 3, 14], fill=(58, 34, 104, 255))
    d.rectangle([13, 2, 13, 13], fill=(238, 228, 200, 255))
    d.rectangle([4, 1, 13, 1], fill=(120, 80, 190, 255))
    for (x, y) in [(8, 4), (8, 5), (8, 6), (7, 5), (9, 5), (6, 6), (10, 6), (7, 7), (9, 7), (6, 8), (10, 8)]:
        d.point((x, y), fill=(255, 216, 102, 255))
    d.point((8, 7), fill=(255, 216, 102, 255))
    save(img, 'textures/item/codex.png')


def sigil_icons():
    spell = os.path.join(ASSETS, 'textures/gui/spell')
    for new, old in (('ember', 'flame_lance'), ('ward', 'arcane_barrier'), ('restore', 'healing_light')):
        src = os.path.join(spell, old + '.png')
        if os.path.exists(src):
            shutil.copyfile(src, os.path.join(spell, new + '.png'))
    def canvas():
        img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
        return img, ImageDraw.Draw(img)
    # Frost: a six-armed snowflake.
    img, d = canvas()
    c = (170, 235, 255, 255)
    d.line([7, 1, 7, 14], fill=c)
    d.line([8, 1, 8, 14], fill=c)
    d.line([1, 7, 14, 7], fill=c)
    d.line([1, 8, 14, 8], fill=c)
    d.line([3, 3, 12, 12], fill=(120, 200, 245, 255))
    d.line([12, 3, 3, 12], fill=(120, 200, 245, 255))
    d.rectangle([6, 6, 9, 9], fill=(235, 252, 255, 255))
    save(img, 'textures/gui/spell/frost.png')
    # Storm: a lightning bolt.
    img, d = canvas()
    yellow = (255, 242, 122, 255)
    d.polygon([(9, 0), (3, 8), (7, 8), (5, 15), (13, 6), (9, 6), (11, 0)], fill=yellow)
    d.line([9, 1, 5, 7], fill=(255, 255, 220, 255))
    save(img, 'textures/gui/spell/storm.png')
    # Nova: a starburst.
    img, d = canvas()
    o = (255, 200, 107, 255)
    for angle in range(0, 360, 45):
        import math
        x = 7.5 + math.cos(math.radians(angle)) * 7
        y = 7.5 + math.sin(math.radians(angle)) * 7
        d.line([7.5, 7.5, x, y], fill=o)
    d.ellipse([4, 4, 11, 11], fill=(255, 150, 60, 255))
    d.ellipse([6, 6, 9, 9], fill=(255, 245, 200, 255))
    save(img, 'textures/gui/spell/nova.png')
    # Meteor: a rock with a tail.
    img, d = canvas()
    d.polygon([(0, 0), (9, 4), (4, 9)], fill=(255, 120, 60, 200))
    d.polygon([(2, 1), (7, 4), (4, 7)], fill=(255, 200, 100, 220))
    d.ellipse([7, 7, 14, 14], fill=(120, 60, 40, 255))
    d.ellipse([8, 8, 12, 12], fill=(200, 90, 50, 255))
    d.point((9, 9), fill=(255, 200, 120, 255))
    save(img, 'textures/gui/spell/meteor.png')
    for old in ('arcane_barrier', 'healing_light', 'flame_lance', 'augment_amplify', 'augment_efficient', 'augment_extend', 'augment_pierce',
                'augment_quicken', 'effect_blink', 'effect_damage', 'effect_frost', 'effect_heal', 'effect_ignite', 'effect_knockback',
                'effect_leech', 'effect_shield', 'form_bolt', 'form_burst', 'form_self'):
        path = os.path.join(spell, old + '.png')
        if os.path.exists(path):
            os.remove(path)


def altar_textures():
    block = os.path.join(ASSETS, 'textures/block')
    for new, old in (('altar_top', 'study_desk_top'), ('altar_side', 'study_desk_side'), ('altar_leg', 'study_desk_leg')):
        src = os.path.join(block, old + '.png')
        if os.path.exists(src):
            shutil.move(src, os.path.join(block, new + '.png'))
    old_model = os.path.join(ASSETS, 'models/block/arcane_study_desk.json')
    if os.path.exists(old_model):
        text = open(old_model, encoding='utf-8').read()
        text = text.replace('study_desk_side', 'altar_side').replace('study_desk_top', 'altar_top').replace('study_desk_leg', 'altar_leg')
        os.makedirs(os.path.join(ASSETS, 'models/block'), exist_ok=True)
        with open(os.path.join(ASSETS, 'models/block/inscription_altar.json'), 'w', encoding='utf-8') as f:
            f.write(text)
        os.remove(old_model)


# ------------------------------------------------------------------ models, states, loot, tags

def blocks_and_items():
    def cube_all(name, texture):
        put(os.path.join(ASSETS, 'models/block/%s.json' % name), {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'mana_awakened:block/%s' % texture}})

    for mask in range(16):
        put(os.path.join(ASSETS, 'models/block/rune_plate_%d.json' % mask), {'parent': 'minecraft:block/cube_bottom_top', 'textures': {
            'top': 'mana_awakened:block/rune_plate_top_%d' % mask, 'side': 'mana_awakened:block/rune_plate_side', 'bottom': 'mana_awakened:block/rune_plate_side'}})
    put(os.path.join(ASSETS, 'models/block/meditation_core.json'), {'parent': 'minecraft:block/cube_bottom_top', 'textures': {
        'top': 'mana_awakened:block/meditation_core_top', 'side': 'mana_awakened:block/meditation_core_side', 'bottom': 'mana_awakened:block/meditation_core_side'}})

    def glowing_cube(top, side, bottom, side_glow):
        light = {'neoforge_data': {'block_light': 15, 'sky_light': 15}}

        def face(texture, cull, glow):
            data = {'texture': texture, 'cullface': cull}
            if glow:
                data.update(light)
            return data
        return {'textures': {'particle': side, 'top': top, 'side': side, 'bottom': bottom}, 'elements': [{
            'from': [0, 0, 0], 'to': [16, 16, 16],
            'faces': {'up': face('#top', 'up', True), 'down': face('#bottom', 'down', False),
                      'north': face('#side', 'north', side_glow), 'south': face('#side', 'south', side_glow),
                      'west': face('#side', 'west', side_glow), 'east': face('#side', 'east', side_glow)}}]}
    put(os.path.join(ASSETS, 'models/block/meditation_core_active.json'), glowing_cube(
        'mana_awakened:block/meditation_core_top_active', 'mana_awakened:block/meditation_core_side_active', 'mana_awakened:block/meditation_core_side_active', True))
    for mask in range(16):
        put(os.path.join(ASSETS, 'models/block/rune_plate_f%d.json' % mask), glowing_cube(
            'mana_awakened:block/rune_plate_top_f%d' % mask, 'mana_awakened:block/rune_plate_side_formed', 'mana_awakened:block/rune_plate_side_formed', False))
    for lit in ('', '_lit'):
        put(os.path.join(ASSETS, 'models/block/mana_condenser%s.json' % lit), {'parent': 'minecraft:block/cube_bottom_top', 'textures': {
            'top': 'mana_awakened:block/mana_condenser_top%s' % lit, 'side': 'mana_awakened:block/mana_condenser_side%s' % lit,
            'bottom': 'mana_awakened:block/mana_condenser_bottom'}})
    plate_states = {}
    for mask in range(16):
        joins = tuple(str(bool(mask & bit)).lower() for bit in (E_, N_, S_, W_))
        plate_states['east=%s,formed=false,north=%s,south=%s,west=%s' % (joins[0], joins[1], joins[2], joins[3])] = {'model': 'mana_awakened:block/rune_plate_%d' % mask}
        plate_states['east=%s,formed=true,north=%s,south=%s,west=%s' % (joins[0], joins[1], joins[2], joins[3])] = {'model': 'mana_awakened:block/rune_plate_f%d' % mask}
    put(os.path.join(ASSETS, 'blockstates/rune_plate.json'), {'variants': plate_states})
    core_states = {'tier=0': {'model': 'mana_awakened:block/meditation_core'}}
    for tier in range(1, 6):
        core_states['tier=%d' % tier] = {'model': 'mana_awakened:block/meditation_core_active'}
    put(os.path.join(ASSETS, 'blockstates/meditation_core.json'), {'variants': core_states})
    put(os.path.join(ASSETS, 'blockstates/mana_condenser.json'), {'variants': {
        'lit=false': {'model': 'mana_awakened:block/mana_condenser'}, 'lit=true': {'model': 'mana_awakened:block/mana_condenser_lit'}}})
    put(os.path.join(ASSETS, 'blockstates/inscription_altar.json'), {'variants': {
        'facing=north': {'model': 'mana_awakened:block/inscription_altar'},
        'facing=east': {'model': 'mana_awakened:block/inscription_altar', 'y': 90},
        'facing=south': {'model': 'mana_awakened:block/inscription_altar', 'y': 180},
        'facing=west': {'model': 'mana_awakened:block/inscription_altar', 'y': 270}}})
    for block in ('rune_plate', 'meditation_core', 'mana_condenser', 'inscription_altar'):
        put(os.path.join(ASSETS, 'models/item/%s.json' % block), {'parent': 'mana_awakened:block/%s' % ('rune_plate_0' if block == 'rune_plate' else block)})
    for item in ('passive_scroll', 'codex'):
        put(os.path.join(ASSETS, 'models/item/%s.json' % item), {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'mana_awakened:item/%s' % item}})
    for block in ('rune_plate', 'meditation_core', 'mana_condenser', 'inscription_altar'):
        put(os.path.join(DATA, 'loot_table/blocks/%s.json' % block), {
            'type': 'minecraft:block',
            'pools': [{'rolls': 1.0, 'bonus_rolls': 0.0, 'conditions': [{'condition': 'minecraft:survives_explosion'}],
                       'entries': [{'type': 'minecraft:item', 'name': 'mana_awakened:%s' % block}]}],
            'random_sequence': 'mana_awakened:blocks/%s' % block})
    put(os.path.join(RES, 'data/minecraft/tags/block/mineable/pickaxe.json'), {'values': ['mana_awakened:rune_plate', 'mana_awakened:meditation_core', 'mana_awakened:mana_condenser']})
    put(os.path.join(RES, 'data/minecraft/tags/block/mineable/axe.json'), {'values': ['mana_awakened:inscription_altar']})
    put(os.path.join(DATA, 'tags/worldgen/biome/mana_rich.json'), {'values': [
        'minecraft:cherry_grove', 'minecraft:meadow', 'minecraft:flower_forest', 'minecraft:lush_caves', 'minecraft:grove', 'minecraft:snowy_slopes',
        'minecraft:jagged_peaks', 'minecraft:frozen_peaks', 'minecraft:stony_peaks', 'minecraft:dripstone_caves', 'minecraft:old_growth_birch_forest']})
    put(os.path.join(DATA, 'tags/worldgen/biome/mana_dense.json'), {'values': [
        'minecraft:mushroom_fields', 'minecraft:deep_dark', '#minecraft:is_end']})


def advancements():
    def adv(name, icon, frame, parent, announce=True):
        put(os.path.join(DATA, 'advancement/%s.json' % name), {
            'display': {'icon': {'id': icon}, 'title': {'translate': 'advancements.mana_awakened.%s.title' % name},
                        'description': {'translate': 'advancements.mana_awakened.%s.description' % name},
                        'frame': frame, 'show_toast': True, 'announce_to_chat': announce, 'hidden': False},
            'criteria': {'reached': {'trigger': 'minecraft:impossible'}},
            'parent': 'mana_awakened:%s' % parent})
    adv('first_spell', 'mana_awakened:inscription_altar', 'goal', 'first_meditation')
    adv('first_passive', 'mana_awakened:passive_scroll', 'goal', 'first_meditation')
    adv('meditation_circle', 'mana_awakened:meditation_core', 'goal', 'first_meditation')


# ------------------------------------------------------------------ recipes

def recipes():
    out = os.path.join(DATA, 'recipe')
    os.makedirs(out, exist_ok=True)
    put(os.path.join(out, 'rune_plate.json'), {
        'type': 'minecraft:crafting_shaped', 'category': 'building',
        'pattern': ['SSS', 'SAS', 'SSS'],
        'key': {'S': {'item': 'minecraft:stone_bricks'}, 'A': {'item': 'minecraft:amethyst_shard'}},
        'result': {'id': 'mana_awakened:rune_plate', 'count': 8}})
    put(os.path.join(out, 'meditation_core.json'), {
        'type': 'minecraft:crafting_shaped', 'category': 'building',
        'pattern': ['RAR', 'ADA', 'RAR'],
        'key': {'R': {'item': 'mana_awakened:rune_plate'}, 'A': {'item': 'minecraft:amethyst_shard'}, 'D': {'item': 'minecraft:diamond'}},
        'result': {'id': 'mana_awakened:meditation_core', 'count': 1}})
    put(os.path.join(out, 'mana_condenser.json'), {
        'type': 'minecraft:crafting_shaped', 'category': 'building',
        'pattern': ['IRI', 'AMA', 'IRI'],
        'key': {'I': {'item': 'minecraft:iron_ingot'}, 'R': {'item': 'minecraft:redstone_block'}, 'A': {'item': 'minecraft:amethyst_shard'}, 'M': {'item': 'mana_awakened:meditation_core'}},
        'result': {'id': 'mana_awakened:mana_condenser', 'count': 1}})
    put(os.path.join(out, 'inscription_altar.json'), {
        'type': 'minecraft:crafting_shapeless', 'category': 'misc',
        'ingredients': [{'item': 'minecraft:lectern'}, {'item': 'minecraft:amethyst_shard'}, {'item': 'minecraft:amethyst_shard'}, {'item': 'minecraft:lapis_lazuli'}],
        'result': {'id': 'mana_awakened:inscription_altar', 'count': 1}})
    put(os.path.join(out, 'codex.json'), {
        'type': 'minecraft:crafting_shapeless', 'category': 'misc',
        'ingredients': [{'item': 'minecraft:book'}, {'item': 'minecraft:amethyst_shard'}],
        'result': {'id': 'mana_awakened:codex', 'count': 1}})
    shutil.rmtree(os.path.join(out, 'passive_scroll'), ignore_errors=True)
    catalysts = ['minecraft:amethyst_shard', 'minecraft:diamond', 'minecraft:ender_eye', 'minecraft:diamond_block', 'minecraft:dragon_breath']
    pattern = re.compile(r'^\s*[A-Z_]+\("([a-z_]+)",\s*(\d+),\s*Kind\.[A-Z_]+,\s*[-0-9.]+,\s*[-0-9.]+,\s*"([a-z_:]+)"\),?;?\s*$')
    count = 0
    for line in open(PASSIVE_JAVA, encoding='utf-8'):
        match = pattern.match(line)
        if not match:
            continue
        pid, circle, ingredient = match.group(1), int(match.group(2)), match.group(3)
        catalyst = catalysts[(circle - 1) // 2]
        assert ingredient != catalyst and ingredient != 'minecraft:paper', pid
        put(os.path.join(out, 'passive_scroll/%s.json' % pid), {
            'type': 'minecraft:crafting_shapeless', 'category': 'misc',
            'ingredients': [{'item': 'minecraft:paper'}, {'item': ingredient}, {'item': catalyst}],
            'result': {'id': 'mana_awakened:passive_scroll', 'count': 1, 'components': {'mana_awakened:passive': pid}}})
        count += 1
    assert count == 50, count
    print('passive recipes:', count)


if __name__ == '__main__':
    rune_plate()
    meditation_core()
    mana_condenser()
    items()
    sigil_icons()
    altar_textures()
    blocks_and_items()
    advancements()
    recipes()
    print('assets generated')
