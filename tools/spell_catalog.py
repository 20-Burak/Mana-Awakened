#!/usr/bin/env python3
"""The catalogue of Mana Awakened's sigils: the single source of truth for spell/Sigil.java (constants and the table of
retired ids), the sigil texts of the language files and the 16x16 sigil icons. No two fighting sigils share a kind and an
element (a copy that differs only in name and damage is not a spell), and every working spell (a "rite") has exactly one
sigil. Run `python3 tools/spell_catalog.py` to regenerate Sigil.java and the icons; lang_data.py imports `texts()`."""
import colorsys, hashlib, math, os, sys
from PIL import Image, ImageDraw

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), '..'))
SIGIL_JAVA = os.path.join(ROOT, 'src/main/java/com/manaawakened/spell/Sigil.java')
ICON_DIR = os.path.join(ROOT, 'src/main/resources/assets/mana_awakened/textures/gui/spell')

ELEMENTS = {
    # name: (rgb, EN noun, TR noun, rider EN, rider TR)
    'ARCANE': (0xB48CFF, 'arcane', 'arkana', '', ''),
    'FIRE':   (0xFF7A2E, 'fire', 'ateş', 'It sets foes alight.', 'Düşmanları tutuşturur.'),
    'FROST':  (0x9FE8FF, 'frost', 'buz', 'It chills and slows foes.', 'Düşmanları üşütür ve yavaşlatır.'),
    'STORM':  (0xFFF27A, 'lightning', 'şimşek', 'It stuns foes for a moment.', 'Düşmanları bir an sersemletir.'),
    'EARTH':  (0xC8A25A, 'earth', 'toprak', 'It slows foes and heaves them upward.', 'Düşmanları yavaşlatır ve havaya savurur.'),
    'WIND':   (0xB8FFE0, 'wind', 'rüzgâr', 'It flings foes into the air.', 'Düşmanları havaya fırlatır.'),
    'LIGHT':  (0xFFE9A0, 'light', 'ışık', 'It scorches the undead and makes foes glow.', 'Ölümsüzleri kavurur, düşmanları parlatır.'),
    'NATURE': (0x7CFF8F, 'nature', 'doğa', 'It poisons foes.', 'Düşmanları zehirler.'),
    'WATER':  (0x5CA8FF, 'water', 'su', 'It soaks foes, slows them and puts out fire.', 'Düşmanları ıslatır, yavaşlatır, ateşi söndürür.'),
    'SHADOW': (0x9A5CD6, 'shadow', 'gölge', 'It withers and weakens foes.', 'Düşmanları soldurur ve zayıflatır.'),
}
ENV = {  # elements whose spells can change the terrain
    'FIRE': ('start fires and melt snow and ice; a great fire fuses sand and stone into glass and magma and opens a pool of lava at its heart',
             'yangın çıkarır, kar ve buzu eritir; büyük ateş kumu ve taşı cama ve magmaya çevirir, merkezinde lav gölü açar'),
    'FROST': ('freeze water and lay snow; in great cold the ground itself turns to packed and blue ice and lava to obsidian',
              'suyu dondurur, kar serer; büyük soğukta zeminin kendisi sıkışık ve mavi buza, lav obsidyene döner'),
    'STORM': ('call real lightning that fuses sand to glass and can set things alight; from the third Circle it brings a real thunderstorm',
              'kumu cama çeviren, yangın çıkarabilen gerçek yıldırım çağırır; üçüncü Çemberden itibaren gerçek bir fırtına koparır'),
    'EARTH': ('crack stone, heave up spears and mounds of rock that crumble again, and break loose soil, sand and leaves',
              'taşı çatlatır, sonra çöken kaya mızrakları ve tümsekler yükseltir, gevşek toprağı, kumu ve yaprakları kırar'),
    'WIND': ('blow away plants, snow and fires and strip the leaves from the trees',
             'bitkileri, karı ve ateşi savurur, ağaçların yapraklarını soyar'),
    'NATURE': ('green the bare ground, make plants and crops grow and raise saplings into trees (its curses wither it all)',
               'çıplak toprağı yeşertir, bitkileri ve ekinleri büyütür, fidanları ağaca dönüştürür (laneti hepsini kurutur)'),
    'WATER': ('put out fires, quench lava into obsidian, water farmland and flood the land for a while; from the fourth Circle it brings rain',
              'ateşi söndürür, lavı obsidyene çevirir, tarlayı sular, araziyi bir süre sular altında bırakır; dördüncü Çemberden itibaren yağmur getirir'),
}
# kind: (cost, cooldown, power, range, radius) of a Circle-1 spell; damaging kinds use `power` per hit
KINDS = {
    'BEAM': (6, 10, 8.0, 28.0, 0.0), 'STRIKE': (14, 25, 12.0, 32.0, 0.0), 'WARD': (22, 160, 8.0, 0.0, 0.0),
    'RESTORE': (24, 80, 6.0, 0.0, 0.0), 'BLINK': (14, 40, 0.0, 10.0, 0.0), 'NOVA': (32, 60, 7.0, 26.0, 4.5),
    'METEOR': (70, 120, 20.0, 40.0, 5.5), 'PILLAR': (18, 45, 10.0, 30.0, 2.5), 'CONE': (12, 30, 9.0, 9.0, 4.0),
    'WAVE': (16, 40, 8.0, 16.0, 1.6), 'BARRAGE': (14, 24, 4.5, 26.0, 0.0), 'RAIN': (30, 90, 5.0, 30.0, 6.0),
    'VORTEX': (26, 100, 3.0, 24.0, 5.0), 'SANCTUARY': (28, 140, 4.0, 0.0, 5.0), 'BUFF': (16, 200, 3.0, 0.0, 0.0),
    'CURSE': (20, 90, 3.0, 24.0, 6.0), 'DASH': (12, 50, 9.0, 10.0, 1.5), 'UTILITY': (14, 100, 1.0, 0.0, 0.0),
}
KIND_EN = {
    'BEAM': 'A beam of {e} that strikes everything along its line.',
    'STRIKE': 'Calls a bolt of {e} down on the spot you look at.',
    'WARD': 'Wraps you and nearby allies in a ward of {e} that absorbs damage.',
    'RESTORE': 'A wave of {e} that mends you and nearby allies.',
    'BLINK': 'Step through a rift of {e} to where you look.',
    'NOVA': 'Hurls a ball of {e} that explodes where it lands, or at the end of its flight.',
    'METEOR': 'Drops a meteor of {e} on the spot you look at.',
    'PILLAR': 'A column of {e} erupts where you look and hurls foes upward.',
    'CONE': 'A cone of {e} sweeps the ground in front of you.',
    'WAVE': 'A shockwave of {e} rolls forward along the ground.',
    'BARRAGE': 'Launches a volley of {e} that seeks the foes in front of you.',
    'RAIN': 'A storm of {e} falls on an area for several seconds.',
    'VORTEX': 'A lingering whirl of {e} that drags in and wears down everything inside.',
    'SANCTUARY': 'A zone of {e} that mends the allies who stand in it.',
    'BUFF': 'Fills you and nearby allies with the power of {e}.',
    'CURSE': 'A curse of {e} that weakens every foe in an area.',
    'DASH': 'Rush forward wrapped in {e}, striking everything you pass.',
}
KIND_TR = {
    'BEAM': 'Önündeki her şeye {e} gücüyle çarpan bir ışın.',
    'STRIKE': 'Baktığın noktaya {e} gücüyle bir yıldırım indirir.',
    'WARD': 'Seni ve yakındaki dostları hasar emen bir {e} korumasına sarar.',
    'RESTORE': 'Seni ve yakındaki dostları iyileştiren bir {e} dalgası.',
    'BLINK': 'Bir {e} yarığından geçerek baktığın yere adım atarsın.',
    'NOVA': 'Önüne bir {e} topu fırlatır; çarptığı yerde ya da menzilin sonunda patlar.',
    'METEOR': 'Baktığın noktaya {e} gücüyle bir göktaşı düşürür.',
    'PILLAR': 'Baktığın yerde bir {e} sütunu yükselir ve düşmanları havaya savurur.',
    'CONE': 'Önündeki zemini bir {e} konisi süpürür.',
    'WAVE': 'Zemin boyunca ileri yuvarlanan bir {e} şok dalgası.',
    'BARRAGE': 'Önündeki düşmanları arayan bir {e} yağmuru fırlatır.',
    'RAIN': 'Bir bölgeye birkaç saniye boyunca {e} fırtınası yağar.',
    'VORTEX': 'İçindeki her şeyi çekip aşındıran kalıcı bir {e} girdabı.',
    'SANCTUARY': 'İçinde duran dostları iyileştiren bir {e} alanı.',
    'BUFF': 'Seni ve yakındaki dostları {e} gücüyle doldurur.',
    'CURSE': 'Bir bölgedeki her düşmanı zayıflatan bir {e} laneti.',
    'DASH': '{e} kuşanıp ileri atılırsın; geçtiğin her şeye çarparsın.',
}
DAMAGING = {'BEAM', 'STRIKE', 'NOVA', 'METEOR', 'PILLAR', 'CONE', 'WAVE', 'BARRAGE', 'RAIN', 'VORTEX', 'CURSE', 'DASH'}

# id, circle, kind, element, EN, TR, options
# options: r=radius, g=range, p=power factor, c=cost factor, i=innate modifiers, legacy=(ring,cost,cd,power,range,color,desc EN,desc TR)
C = []
def s(id, circle, kind, element, en, tr, **o):
    C.append(dict(id=id, circle=circle, kind=kind, element=element, en=en, tr=tr, **o))


def u(id, circle, element, rite, en, tr, en_desc, tr_desc, c=1.0, cd=100, g=0.0, r=0.0, p=1.0):
    """A working spell: its sigil is of the UTILITY kind and carries the rite. c = cost factor, cd = cooldown in ticks at Circle 1,
    g = range, r = natural radius, p = the rite's power (a count, a height, a damage...)."""
    C.append(dict(id=id, circle=circle, kind='UTILITY', element=element, en=en, tr=tr, rite=rite, en_desc=en_desc, tr_desc=tr_desc,
                  c=c, cd=cd, g=g, r=r, p=p))


# ---------------------------------------------------------------- fighting sigils (no two share a kind and an element)
# --- Circle 1
s('mana_bolt', 1, 'BEAM', 'ARCANE', 'Mana Bolt', 'Mana Oku', legacy=(5, 6.0, 10, 8.0, 28.0, 0xB48CFF), en_desc='A bolt of pure mana that strikes the first foe in your sight.', tr_desc='Görüş hattındaki ilk düşmana çarpan saf mana oku.')
s('ember', 1, 'BEAM', 'FIRE', 'Ember', 'Kor', legacy=(5, 7.0, 12, 5.0, 24.0, 0xFF7A2E), en_desc='A lance of fire that burns what it hits.', tr_desc='Çarptığını yakan bir ateş mızrağı.')
s('spark', 1, 'BEAM', 'STORM', 'Spark', 'Kıvılcım', i='chain:1')
s('stone_shard', 1, 'BARRAGE', 'EARTH', 'Stone Shard', 'Taş Kıymığı')
s('gust', 1, 'WAVE', 'WIND', 'Gust', 'Esinti')
s('vine_lash', 1, 'WAVE', 'NATURE', 'Vine Lash', 'Sarmaşık Kırbacı')
s('splash', 1, 'CONE', 'WATER', 'Splash', 'Su Sıçratma')
s('shadow_dart', 1, 'BARRAGE', 'SHADOW', 'Shadow Dart', 'Gölge Oku')
s('swift_step', 1, 'BUFF', 'WIND', 'Swift Step', 'Çevik Adım')
# --- Circle 2
s('ward', 2, 'WARD', 'ARCANE', 'Ward', 'Koruma Mührü', legacy=(6, 22.0, 160, 8.0, 0.0, 0x6FD6FF), en_desc='Wraps you in a shell of absorption.', tr_desc='Seni bir emilim kabuğuna sarar.')
s('restore', 2, 'RESTORE', 'LIGHT', 'Restore', 'Şifa', legacy=(6, 24.0, 80, 6.0, 0.0, 0x7CFF8F), en_desc='Mends you and nearby allies and cleanses poison.', tr_desc='Seni ve yakındaki dostları iyileştirir, zehri temizler.')
s('frost', 2, 'BEAM', 'FROST', 'Frost', 'Ayaz', legacy=(6, 7.0, 12, 5.0, 24.0, 0x9FE8FF), en_desc='A lance of ice that chills and slows.', tr_desc='Üşüten ve yavaşlatan bir buz mızrağı.')
s('flame_breath', 2, 'CONE', 'FIRE', 'Flame Breath', 'Alev Nefesi')
s('thunderclap', 2, 'NOVA', 'STORM', 'Thunderball', 'Şimşek Topu', r=4.0)
s('stone_spike', 2, 'PILLAR', 'EARTH', 'Stone Spike', 'Taş Diken')
s('tidal_wave', 2, 'WAVE', 'WATER', 'Tidal Wave', 'Med Dalgası')
s('poison_cloud', 2, 'VORTEX', 'NATURE', 'Poison Cloud', 'Zehir Bulutu')
s('lightning_reflex', 2, 'BUFF', 'STORM', 'Lightning Reflex', 'Şimşek Refleksi')
s('thorn_volley', 2, 'BARRAGE', 'NATURE', 'Thorn Volley', 'Diken Yağmuru')
# --- Circle 3
s('storm', 3, 'STRIKE', 'STORM', 'Storm', 'Fırtına', legacy=(7, 14.0, 25, 12.0, 32.0, 0xFFF27A), en_desc='Calls lightning down on the foe you look at.', tr_desc='Baktığın düşmanın üzerine yıldırım indirir.')
s('blink', 3, 'BLINK', 'ARCANE', 'Blink', 'Sıçrayış', legacy=(7, 14.0, 40, 0.0, 10.0, 0xC49CFF), en_desc='Step through the mana currents to where you look.', tr_desc='Mana akıntılarından geçerek baktığın yere adım atarsın.')
s('fireball_volley', 3, 'BARRAGE', 'FIRE', 'Fireball Volley', 'Ateş Topu Yağmuru')
s('ice_lances', 3, 'BARRAGE', 'FROST', 'Ice Lances', 'Buz Mızrakları')
s('radiant_pillar', 3, 'PILLAR', 'LIGHT', 'Radiant Pillar', 'Işık Sütunu')
s('gale_dash', 3, 'DASH', 'WIND', 'Gale Dash', 'Fırtına Atılışı')
s('hex_of_weakness', 3, 'CURSE', 'SHADOW', 'Hex of Weakness', 'Zayıflık Laneti')
s('sanctuary_bloom', 3, 'SANCTUARY', 'NATURE', 'Sanctuary Bloom', 'Şifa Çiçeği')
s('stone_rain', 3, 'RAIN', 'EARTH', 'Stone Rain', 'Taş Yağmuru')
s('frozen_ground', 3, 'VORTEX', 'FROST', 'Freezing Whirl', 'Dondurucu Girdap')
s('aqua_lance', 3, 'BEAM', 'WATER', 'Aqua Lance', 'Su Mızrağı')
# --- Circle 4
s('nova', 4, 'NOVA', 'LIGHT', 'Starburst', 'Yıldız Patlaması', legacy=(7, 32.0, 60, 7.0, 26.0, 0xFFC86B), en_desc='Hurls a star of mana that bursts where it lands, or at the end of its flight.', tr_desc='Çarptığı yerde ya da menzilin sonunda patlayan bir mana yıldızı fırlatır.')
s('inferno_pillar', 4, 'PILLAR', 'FIRE', 'Inferno Pillar', 'Cehennem Sütunu')
s('blizzard', 4, 'RAIN', 'FROST', 'Blizzard', 'Kar Fırtınası')
s('gravity_well', 4, 'VORTEX', 'SHADOW', 'Gravity Well', 'Yerçekimi Kuyusu')
s('earthquake', 4, 'WAVE', 'EARTH', 'Earthquake', 'Deprem', r=3.0)
s('wind_blades', 4, 'BARRAGE', 'WIND', 'Wind Blades', 'Rüzgâr Bıçakları')
s('vampiric_beam', 4, 'BEAM', 'SHADOW', 'Vampiric Beam', 'Vampir Işını', i='leech:5')
s('stone_aegis', 4, 'WARD', 'EARTH', 'Stone Aegis', 'Taş Kalkan')
s('rime_breath', 4, 'CONE', 'FROST', 'Rime Breath', 'Kırağı Nefesi')
# --- Circle 5
s('solar_lance', 5, 'BEAM', 'LIGHT', 'Solar Lance', 'Güneş Mızrağı', i='pierce:4')
s('magma_burst', 5, 'NOVA', 'FIRE', 'Magma Burst', 'Magma Patlaması', r=5.5)
s('glacier_spike', 5, 'PILLAR', 'FROST', 'Glacier Spike', 'Buzul Dikeni')
s('thunder_rain', 5, 'RAIN', 'STORM', 'Thunder Rain', 'Yıldırım Yağmuru')
s('healing_rain', 5, 'SANCTUARY', 'WATER', 'Healing Rain', 'Şifa Yağmuru')
s('shadow_step', 5, 'BLINK', 'SHADOW', 'Shadow Step', 'Gölge Adımı')
s('thunder_dash', 5, 'DASH', 'STORM', 'Thunder Dash', 'Yıldırım Atılışı')
s('spirit_ward', 5, 'WARD', 'LIGHT', 'Spirit Ward', 'Ruh Kalkanı')
s('riptide', 5, 'DASH', 'WATER', 'Riptide', 'Girdap Atılışı')
# --- Circle 6
s('meteor', 6, 'METEOR', 'FIRE', 'Meteor', 'Göktaşı', legacy=(8, 70.0, 120, 20.0, 40.0, 0xFF5A3C), en_desc='Drops a meteor on the spot you look at.', tr_desc='Baktığın noktaya bir göktaşı düşürür.')
s('comet_fall', 6, 'METEOR', 'FROST', 'Comet Fall', 'Kuyruklu Yıldız')
s('starfall', 6, 'RAIN', 'ARCANE', 'Starfall', 'Yıldız Düşüşü')
s('lava_flow', 6, 'WAVE', 'FIRE', 'Lava Flow', 'Lav Akıntısı', r=2.5)
s('soul_nova', 6, 'NOVA', 'SHADOW', 'Soul Bomb', 'Ruh Bombası', r=6.0)
s('typhoon', 6, 'VORTEX', 'WIND', 'Typhoon', 'Tayfun')
s('light_sanctuary', 6, 'SANCTUARY', 'LIGHT', 'Light Sanctuary', 'Işık Sığınağı')
s('withering_curse', 6, 'CURSE', 'NATURE', 'Withering Curse', 'Solma Laneti')
# --- Circle 7
s('absolute_zero', 7, 'NOVA', 'FROST', 'Absolute Zero', 'Mutlak Sıfır', r=7.0)
s('titans_might', 7, 'BUFF', 'EARTH', "Titan's Might", 'Titan Gücü')
s('hellfire_rain', 7, 'RAIN', 'FIRE', 'Hellfire Rain', 'Cehennem Yağmuru')
s('berserk_rite', 7, 'BUFF', 'FIRE', 'Berserk Rite', 'Çılgınlık Ritüeli')
s('tidal_pillar', 7, 'PILLAR', 'WATER', 'Tidal Pillar', 'Gelgit Sütunu')
# --- Circle 8
s('sunfall', 8, 'METEOR', 'LIGHT', 'Sunfall', 'Güneş Düşüşü')
s('dimensional_rend', 8, 'DASH', 'ARCANE', 'Dimensional Rend', 'Boyut Yarığı', g=16.0)
# --- Circle 9
s('arcane_cataclysm', 9, 'NOVA', 'ARCANE', 'Arcane Cataclysm', 'Arkana Felaketi', r=9.0)
s('gaias_embrace', 9, 'WARD', 'NATURE', "Gaia's Embrace", "Gaia'nın Kucağı")
s('rift_barrage', 9, 'BARRAGE', 'ARCANE', 'Rift Barrage', 'Yarık Yağmuru', i='multi:5')
s('celestial_meteor', 9, 'METEOR', 'ARCANE', 'Celestial Meteor', 'Göksel Meteor', i='multi:2')
# --- Circle 10
s('time_dilation', 10, 'VORTEX', 'ARCANE', 'Time Dilation', 'Zaman Genişlemesi', r=8.0)
s('transcendent_step', 10, 'BLINK', 'LIGHT', 'Transcendent Step', 'Aşkın Adım', g=24.0)
s('primordial_might', 10, 'BUFF', 'ARCANE', 'Primordial Might', 'İlkel Güç')
s('wrath_of_heaven', 10, 'STRIKE', 'LIGHT', 'Wrath of Heaven', 'Cennetin Gazabı', i='multi:5,chain:2')

# ---------------------------------------------------------------- working spells ("rites"), one sigil each
# --- Circle 1
u('wayfinder', 1, 'WIND', 'WAYFINDER', 'Wayfinder', 'Yol Bulucu',
  'A spirit of wind runs ahead along the ground toward the place you choose (the world spawn, your bed or where you last died) and tells you the bearing.',
  'Seçtiğin yere (dünya doğuş noktası, yatağın ya da son ölüm yerin) doğru yerde koşan bir rüzgâr ruhu salar ve yönü söyler.',
  c=0.25, cd=60, g=40.0)
u('magnet', 1, 'ARCANE', 'MAGNET', 'Mana Magnet', 'Mana Mıknatısı',
  'Everything that lies about - items and orbs of experience - is pulled to you in a stream of mana.',
  'Etrafta yatan her şey - eşyalar ve deneyim küreleri - bir mana akışıyla sana çekilir.',
  c=0.25, cd=80, r=8.0)
# --- Circle 2
u('wisp_light', 2, 'LIGHT', 'WISP_LIGHT', 'Wisp Light', 'Ruh Işıkları',
  'Wisps fly from your hand to the darkest places around and settle there, glowing, so nothing can spawn in the dark any more. They fade after a while.',
  'Elinden çıkan ruh ışıkları çevredeki en karanlık yerlere uçup orada parlayarak yerleşir; karanlıkta artık hiçbir şey doğamaz. Bir süre sonra sönerler.',
  c=0.45, cd=200, r=8.0, p=1.0)
u('forge_touch', 2, 'FIRE', 'FORGE_TOUCH', 'Forge Touch', 'Demirci Dokunuşu',
  'Smelts what you hold, or every smeltable item lying about, in a breath of fire: ore to ingots, sand to glass, raw meat to meals. You keep the experience.',
  'Elindekini ya da etrafta yatan eritilebilir her eşyayı bir ateş soluğuyla eritir: cevher külçeye, kum cama, çiğ et yemeğe döner. Deneyim de senindir.',
  c=0.45, cd=100, r=4.0)
# --- Circle 3
u('harvest', 3, 'NATURE', 'HARVEST', 'Harvest Rite', 'Hasat Ritüeli',
  'Every ripe crop around you is reaped in a wave of green light and sown again, so the field keeps growing.',
  'Çevrendeki her olgun ekin yeşil bir ışık dalgasıyla biçilir ve yeniden ekilir; tarla büyümeye devam eder.',
  c=0.55, cd=100, r=6.0)
u('ore_sense', 3, 'EARTH', 'ORE_SENSE', 'Ore Sense', 'Cevher Sezgisi',
  'A pulse runs through the ground and the ore of the kind you choose within reach glows through the rock for a while.',
  'Zeminden bir nabız geçer; seçtiğin türden cevherler menzil içinde kayanın ardından parlar.',
  c=0.7, cd=200, g=30.0)
# --- Circle 4
u('earthbreaker', 4, 'EARTH', 'EARTHBREAKER', 'Earthbreaker', 'Toprak Kıran',
  'Breaks the block you look at, and its neighbours, like a perfect pickaxe; the drops fly to you. Choose plain, silk touch, fortune or shatter (a great blast that leaves no drops).',
  'Baktığın bloğu ve komşularını kusursuz bir kazma gibi kırar; düşenler sana uçar. Düz, ipek dokunuş, servet ya da parçalama (eşya bırakmayan büyük bir patlama) seç.',
  c=0.55, cd=20, g=8.0, r=1.5, p=1.0)
u('lumberjack', 4, 'NATURE', 'LUMBERJACK', 'Lumberjack Spirit', 'Oduncu Ruhu',
  'Fells the whole tree you look at in one swing, branches and all, and the logs come to you. It can plant a sapling in its place.',
  'Baktığın ağacı dallarıyla birlikte tek vuruşta devirir; kütükler sana gelir. İstersen yerine bir fidan dikebilir.',
  c=0.6, cd=60, g=10.0, p=1.0)
u('anchor_set', 4, 'ARCANE', 'ANCHOR_SET', 'Set Anchor', 'Çıpa Bırak',
  'Leaves one of three anchors where you stand. It remembers the place and the world; Recall Anchor brings you back to it.',
  'Durduğun yere üç çıpadan birini bırakır. Çıpa yeri ve dünyayı hatırlar; Çıpaya Dön seni oraya geri getirir.',
  c=0.35, cd=100)
# --- Circle 5
u('homeward', 5, 'LIGHT', 'HOMEWARD', 'Homeward', 'Eve Dönüş',
  'Stand still for a few seconds while the world folds around you, and be carried to your bed, to the world spawn or to the place you last died. Moving or being hurt breaks the spell.',
  'Birkaç saniye kıpırdamadan dur; dünya çevrende kıvrılır ve seni yatağına, dünya doğuş noktasına ya da son ölüm yerine taşır. Kıpırdamak ya da hasar almak büyüyü bozar.',
  c=1.1, cd=600)
u('anchor_return', 5, 'ARCANE', 'ANCHOR_RETURN', 'Recall Anchor', 'Çıpaya Dön',
  'Tears the space open and returns you to one of your three anchors, in whichever world it lies.',
  'Uzayı yırtıp seni üç çıpandan birine götürür; çıpa hangi dünyada olursa olsun.',
  c=1.3, cd=600)
u('ice_path', 5, 'FROST', 'ICE_PATH', 'Ice Path', 'Buz Yolu',
  'A wave of cold runs ahead of you: water turns to ice and lava to obsidian, a path across the lake. After a while it thaws again.',
  'Önünde bir soğuk dalgası koşar: su buza, lav obsidyene döner; gölün üstünde bir yol açılır. Bir süre sonra yeniden çözülür.',
  c=0.8, cd=120, g=16.0, r=3.0)
u('hissing_charge', 5, 'EARTH', 'HISSING_CHARGE', 'Hissing Charge', 'Tıslayan Yük',
  'A charge flies to where you look, lies there hissing, swells and flashes like a creeper - and bursts. Foes are hurt and flung back; with destruction switched on it blasts a crater.',
  'Baktığın yere bir yük uçar, orada tıslayıp şişer ve bir creeper gibi parlar - sonra patlar. Düşmanlar yaralanır ve savrulur; yıkım açıkken krater de açar.',
  c=0.9, cd=100, g=28.0, r=3.5, p=8.0)
# --- Circle 6
u('tunnel_bore', 6, 'EARTH', 'TUNNEL_BORE', 'Tunnel Bore', 'Tünel Açıcı',
  'A drill of mana bores a straight tunnel ahead of you, narrow or wide, and the ore comes along. It stops before water or lava.',
  'Bir mana matkabı önünde düz bir tünel açar, dar ya da geniş; cevherler de sana gelir. Su ya da lavdan önce durur.',
  c=1.1, cd=200, g=24.0, r=1.0)
u('rampart', 6, 'EARTH', 'RAMPART', 'Stone Rampart', 'Taş Sur',
  'A battlemented wall of stone bricks grows out of the ground in front of you, from its middle outward. It crumbles again after a while.',
  'Önünde, ortasından kenarlarına doğru, siperlikli bir taş duvar yerden büyür. Bir süre sonra yeniden çöker.',
  c=0.9, cd=200, r=5.0)
u('spring', 6, 'WATER', 'SPRING', 'Spring', 'Pınar',
  'A geyser breaks out of the ground where you look and leaves a spring of clean water. It waters the fields, puts out fires and soothes whoever stands in it.',
  'Baktığın yerde bir gayzer patlar ve temiz bir su pınarı bırakır. Tarlaları sular, yangınları söndürür, içinde duranları dinlendirir.',
  c=0.7, cd=300, g=24.0, r=2.5)
# --- Circle 7
u('updraft', 7, 'WIND', 'UPDRAFT', 'Updraft', 'Yükselen Hava',
  'A column of rising air lifts everything in it for some seconds. Players are held up gently, so the way down is soft.',
  'Yükselen bir hava sütunu içindeki her şeyi birkaç saniye boyunca havaya kaldırır. Oyuncular yumuşakça tutulur; iniş de yumuşak olur.',
  c=0.75, cd=160, g=24.0, r=2.5, p=1.0)
u('seer_sight', 7, 'SHADOW', 'SEER_SIGHT', "Seer's Sight", 'Kâhin Gözü',
  'Opens the eye of a seer: the creatures of the kind you choose glow through walls and rock for a while.',
  'Bir kâhinin gözünü açar: seçtiğin türden yaratıklar bir süre duvarların ve kayaların ardından parlar.',
  c=0.8, cd=240, g=32.0)
u('vein_reaver', 7, 'EARTH', 'VEIN_REAVER', 'Vein Reaver', 'Damar Sökücü',
  'Tears out the whole vein of ore you look at, block by block, following it through the rock. The ore comes to you.',
  'Baktığın cevher damarının tamamını kayanın içinde izleyerek blok blok söker. Cevherler sana gelir.',
  c=1.1, cd=80, g=8.0, p=1.5)
u('starlight_bridge', 7, 'LIGHT', 'STARLIGHT_BRIDGE', 'Starlight Bridge', 'Yıldız Işığı Köprüsü',
  'A bridge of glowing glass is laid out ahead of you over the gaps, a row at a time, and fades from behind. Starlight also holds you up should it fade under your feet.',
  'Önündeki boşlukların üstüne, sıra sıra, parlayan camdan bir köprü serilir ve arkadan silinir. Ayağının altında silinirse yıldız ışığı seni havada tutar.',
  c=1.1, cd=240, g=20.0)
u('structure_compass', 7, 'LIGHT', 'STRUCTURE_COMPASS', 'Structure Compass', 'Yapı Pusulası',
  'Finds the nearest structure of the kind you choose - a village, a mansion, a stronghold, a fortress... - and keeps pointing the way to it for a while.',
  'Seçtiğin türden en yakın yapıyı - köy, malikâne, kale, hisar... - bulur ve bir süre boyunca ona giden yolu gösterir.',
  c=0.9, cd=400, g=32.0)
# --- Circle 8
u('weather_weave', 8, 'WATER', 'WEATHER_WEAVE', 'Weather Weave', 'Hava Dokuması',
  'The clouds gather and rain falls, or they are blown apart and the sun comes out. It changes the weather of the whole world.',
  'Bulutlar toplanıp yağmur yağar ya da dağılıp güneş açar. Tüm dünyanın havasını değiştirir.',
  c=1.0, cd=600)
u('shadow_veil', 8, 'SHADOW', 'SHADOW_VEIL', 'Shadow Veil', 'Gölge Perdesi',
  'You melt into shadow: invisible, and every creature that hunts you loses the trail - again and again while the veil lasts.',
  'Gölgeye karışırsın: görünmez olursun ve seni avlayan her yaratık izi kaybeder - perde sürdükçe tekrar tekrar.',
  c=1.0, cd=400)
u('chrono_step', 8, 'ARCANE', 'CHRONO_STEP', 'Chrono Step', 'Zaman Adımı',
  'Steps back through the last seconds of time: you return to where you stood a moment ago, with the health you had then.',
  'Zamanın son birkaç saniyesinde geri adım atarsın: bir an önce durduğun yere, o zamanki canınla dönersin.',
  c=1.4, cd=400)
u('terraform', 8, 'EARTH', 'TERRAFORM', 'Terraform', 'Arazi Düzleyici',
  'Cuts the hills down and fills the hollows up to the level of your feet, in a wide circle, and lays the natural ground back on top. The stone you cut away comes to you.',
  'Geniş bir daire içinde tepeleri keser, çukurları ayaklarının hizasına kadar doldurur ve üstüne doğal toprağı serer. Kestiğin taşlar sana gelir.',
  c=1.5, cd=300, r=6.0, p=1.0)
u('rift_walk', 8, 'SHADOW', 'RIFT_WALK', 'Rift Walk', 'Yarık Yürüyüşü',
  'Finds the nearest structure of the kind you choose and steps through a rift straight to it, in the world you stand in.',
  'Seçtiğin türden en yakın yapıyı bulur ve bir yarıktan geçerek, içinde bulunduğun dünyada, doğrudan ona adım atarsın.',
  c=2.2, cd=1200, g=48.0)
# --- Circle 9
u('dayspring', 9, 'LIGHT', 'DAYSPRING', 'Dayspring', 'Şafak',
  'The sun races across the sky to the dawn or to high noon. It changes the time of the whole world.',
  'Güneş gökyüzünde şafağa ya da öğle vaktine doğru koşar. Tüm dünyanın saatini değiştirir.',
  c=1.5, cd=1200)
u('nightfall', 9, 'SHADOW', 'NIGHTFALL', 'Nightfall', 'Gece Çökmesi',
  'The sky darkens and the stars fall into place: the clock runs on to the sunset or to midnight. It changes the time of the whole world.',
  'Gökyüzü kararır, yıldızlar yerine oturur: saat gün batımına ya da gece yarısına doğru akar. Tüm dünyanın saatini değiştirir.',
  c=1.5, cd=1200)
u('volcano', 9, 'FIRE', 'VOLCANO', 'Volcano', 'Yanardağ',
  'A volcano heaves out of the ground where you look, with a crater of magma at its top. It erupts: smoke, sparks and burning bombs fall on your enemies. Later the mountain sinks again.',
  'Baktığın yerde yerden bir yanardağ yükselir, tepesinde bir magma krateri vardır. Patlar: duman, kıvılcım ve yanan bombalar düşmanlarının üzerine yağar. Sonra dağ yeniden çöker.',
  c=2.6, cd=1800, g=32.0, r=7.0, p=10.0)
u('rewind', 9, 'ARCANE', 'REWIND', 'Rewind', 'Geri Sarma',
  'The clock turns back around you: blocks that spells and explosions tore out or changed in the last minute return to their places - a creeper crater closes, a burnt field is green again.',
  'Saat çevrende geri döner: son dakikada büyülerin ve patlamaların söktüğü ya da değiştirdiği bloklar yerine döner - creeper çukuru kapanır, yanan tarla yeniden yeşerir.',
  c=1.8, cd=600, r=12.0)
u('summon_tempest', 9, 'STORM', 'SUMMON_TEMPEST', 'Summon Tempest', 'Fırtına Çağır',
  'A real thunderstorm breaks over you. For some seconds lightning falls on your enemies - and on the land around. It changes the weather of the whole world.',
  'Üstünde gerçek bir gök gürültülü fırtına kopar. Birkaç saniye boyunca yıldırımlar düşmanlarına - ve çevredeki araziye - düşer. Tüm dünyanın havasını değiştirir.',
  c=2.2, cd=2400, r=12.0, p=14.0)
# --- Circle 10
u('second_life', 10, 'LIGHT', 'SECOND_LIFE', 'Second Life', 'İkinci Can',
  'For a while, the next blow that would kill you does not: you are left standing, half healed, and the ward is spent.',
  'Bir süre boyunca seni öldürecek bir sonraki darbe öldürmez: ayakta kalırsın, canın yarıya dolar ve koruma tükenir.',
  c=2.4, cd=3600)
u('eternal_winter', 10, 'FROST', 'ETERNAL_WINTER', 'Eternal Winter', 'Sonsuz Kış',
  'Winter falls on the land around you: lakes freeze, snow settles and the ground turns to ice, and a blizzard slows and freezes your enemies. The Rewind rite can set the land back.',
  'Çevrendeki topraklara kış çöker: göller donar, kar yağar, zemin buza döner; bir tipi düşmanlarını yavaşlatıp dondurur. Geri Sarma toprağı eski haline döndürebilir.',
  c=3.2, cd=3600, r=12.0)
u('dimension_gate', 10, 'ARCANE', 'DIMENSION_GATE', 'Dimension Gate', 'Boyut Geçidi',
  'Opens a shimmering gate where you look. Whoever steps through arrives in the world you chose, at the matching place. It closes after a while.',
  'Baktığın yerde parıldayan bir geçit açar. İçinden geçen, seçtiğin dünyada, karşılık gelen yere varır. Bir süre sonra kapanır.',
  c=4.5, cd=2400, g=12.0, r=0.0)

# ---------------------------------------------------------------- ids of earlier versions that were copies of a sigil that stayed
RETIRED = {
    'genesis_beam': 'mana_bolt', 'chain_lightning': 'spark', 'void_lance': 'vampiric_beam', 'judgment_beam': 'solar_lance',
    'boulder_volley': 'stone_shard', 'thousand_blades': 'wind_blades', 'soothing_light': 'restore', 'cleansing_light': 'restore',
    'rebirth': 'second_life', 'hoarfrost': 'absolute_zero', 'supernova': 'magma_burst', 'dragon_breath': 'flame_breath',
    'ragnarok': 'meteor', 'volcanic_eruption': 'volcano', 'tsunami': 'tidal_wave', 'aurora_veil': 'spirit_ward',
    'seraphic_pillar': 'radiant_pillar', 'absolute_judgment': 'radiant_pillar', 'divine_sanctuary': 'light_sanctuary',
    'phase_shift': 'blink', 'shattering_quake': 'stone_spike', 'world_breaker': 'earthquake', 'world_tree_blessing': 'sanctuary_bloom',
    'plague_storm': 'poison_cloud', 'storm_sovereign': 'lightning_reflex', 'chain_tempest': 'storm', 'skybreaker': 'storm',
    'doom_curse': 'hex_of_weakness', 'event_horizon': 'gravity_well', 'abyssal_maw': 'gravity_well', 'void_collapse': 'soul_nova',
    'tempest_of_ages': 'thunder_rain',
}

DESIGNS = 14  # the casting-circle designs the client knows


def ring_size(c):
    return min(9, 4 + c['circle'] // 2 + 1)


def digest(text, n):
    return int(hashlib.md5(text.encode()).hexdigest(), 16) % n


def color_of(c):
    if 'legacy' in c:
        return c['legacy'][5]
    base = ELEMENTS[c['element']][0]
    r, g, b = (base >> 16) & 255, (base >> 8) & 255, base & 255
    h, l, sat = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
    shift = (digest(c['id'], 21) - 10) / 400.0
    l = min(0.85, max(0.45, l + (digest(c['id'] + 'l', 11) - 5) / 100.0))
    r, g, b = colorsys.hls_to_rgb((h + shift) % 1.0, l, sat)
    return (int(r * 255) << 16) | (int(g * 255) << 8) | int(b * 255)


def variant_of(c):
    # design 0..13 (by kind family, then rotated by the spell), star 0..3, satellites 0..3
    family = {'BEAM': 0, 'STRIKE': 2, 'WARD': 4, 'RESTORE': 6, 'BLINK': 8, 'NOVA': 10, 'METEOR': 12, 'PILLAR': 1, 'CONE': 3, 'WAVE': 5,
              'BARRAGE': 7, 'RAIN': 9, 'VORTEX': 11, 'SANCTUARY': 13, 'BUFF': 6, 'CURSE': 12, 'DASH': 0, 'UTILITY': 0}[c['kind']]
    design = (family + digest(c['id'], 3) * 4) % DESIGNS
    if c['kind'] == 'UTILITY':
        design = digest(c['id'], DESIGNS)
    star = digest(c['id'] + 'star', 4)
    sats = digest(c['id'] + 'sat', 4)
    return design + 16 * star + 64 * sats


def numbers(c):
    kind = c['kind']
    cost, cd, power, rng, radius = KINDS[kind]
    m = c['circle']
    if kind == 'UTILITY':
        # a working spell is priced like the fighting ones of its Circle: the cost factor says how much it is worth
        cost = round(cost * 1.7 ** (m - 1) * c['c'], 1)
        cd = int(round(c['cd'] * (1 + 0.06 * (m - 1))))
        return cost, cd, c['p'], c['g'], c['r'], ring_size(c)
    if 'legacy' in c:
        _, cost, cd, power, rng, _ = c['legacy']
        radius = 0.0
    else:
        cost = round(cost * 1.7 ** (m - 1) * c.get('c', 1.0), 1)
        cd = int(round(cd * (1 + 0.06 * (m - 1))))
        power = round(power * (1 + 0.30 * (m - 1)) * c.get('p', 1.0), 2)
        rng = round(c.get('g', rng * (1 + 0.02 * (m - 1))), 1)
        radius = c.get('r', radius)
    ring = c['legacy'][0] if 'legacy' in c else ring_size(c)
    return cost, cd, power, rng, radius, ring


def innate_of(c):
    text = c.get('i', '')
    if c['kind'] == 'BARRAGE' and 'multi' not in text:
        text = (text + ',' if text else '') + 'multi:2'
    return text


def java_constants():
    lines = []
    for c in C:
        cost, cd, power, rng, radius, ring = numbers(c)
        lines.append('    %s("%s", %d, Kind.%s, Element.%s, %s, %d, %s, %s, %s, %d, 0x%06X, %d, "%s", Rite.%s),' % (
            c['id'].upper(), c['id'], c['circle'], c['kind'], c['element'], num(cost), cd, num(power), num(rng), num(radius), ring,
            color_of(c), variant_of(c), innate_of(c), c.get('rite', 'NONE')))
    lines[-1] = lines[-1][:-1] + ';'
    return '\n'.join(lines)


def java_retired():
    entries = ',\n'.join('            java.util.Map.entry("%s", "%s")' % (old, new) for old, new in sorted(RETIRED.items()))
    return ('    static final java.util.Map<String, String> RETIRED = java.util.Map.ofEntries(\n' + entries + ');')


def num(v):
    return repr(round(float(v), 2))


def texts():
    out = {}
    for c in C:
        el = ELEMENTS[c['element']]
        if c['kind'] == 'UTILITY':
            out[c['id']] = (c['en'], c['en_desc'], c['tr'], c['tr_desc'])
            continue
        en_desc = c.get('en_desc') or KIND_EN[c['kind']].format(e=el[1])
        tr_desc = c.get('tr_desc') or KIND_TR[c['kind']].format(e=el[2])
        if 'legacy' not in c:
            if c['kind'] in DAMAGING and el[3]:
                en_desc += ' ' + el[3]
                tr_desc += ' ' + el[4]
        if c['element'] in ENV:
            en_desc += ' With "environment" switched on it can %s.' % ENV[c['element']][0]
            tr_desc += ' "Çevre" açıkken %s.' % ENV[c['element']][1]
        out[c['id']] = (c['en'], en_desc, c['tr'], tr_desc)
    return out


def extend_sigils(target):
    for key, value in texts().items():
        target[key] = value


# ------------------------------------------------------------------ icons

def shade(rgb, f):
    return tuple(max(0, min(255, int(v * f))) for v in rgb) + (255,)


def rite_glyph(d, rite, rgb, plate):
    """The picture of a working spell, 16x16, drawn over the round plate."""
    light = (255, 255, 255, 255)
    mid = shade(rgb, 1.0)
    soft = shade(rgb, 0.7)
    dark = shade(rgb, 0.16)
    if rite == 'WAYFINDER':
        d.polygon([(8, 1), (11, 8), (8, 7), (5, 8)], fill=light); d.polygon([(8, 14), (11, 8), (8, 9), (5, 8)], fill=mid); d.point((8, 8), fill=dark)
    elif rite == 'STRUCTURE_COMPASS':
        d.ellipse([2, 2, 13, 13], outline=mid); d.polygon([(11, 4), (9, 9), (4, 11), (6, 6)], fill=light); d.point((8, 8), fill=dark)
    elif rite == 'HOMEWARD':
        d.polygon([(2, 8), (8, 2), (13, 8)], fill=light); d.rectangle([4, 8, 11, 13], fill=mid); d.rectangle([7, 10, 8, 13], fill=dark)
    elif rite == 'ANCHOR_SET' or rite == 'ANCHOR_RETURN':
        d.ellipse([7, 1, 9, 3], outline=light); d.line([8, 3, 8, 12], fill=light); d.line([5, 5, 10, 5], fill=light)
        d.arc([3, 6, 12, 14], 20, 160, fill=mid); d.point((3, 9), fill=mid); d.point((12, 9), fill=mid)
        if rite == 'ANCHOR_RETURN':
            d.polygon([(11, 1), (14, 1), (14, 4)], fill=mid)
    elif rite == 'RIFT_WALK':
        d.line([6, 1, 4, 14], fill=mid); d.line([10, 1, 12, 14], fill=mid); d.line([2, 8, 11, 8], fill=light); d.polygon([(12, 8), (9, 5), (9, 11)], fill=light)
    elif rite == 'DIMENSION_GATE':
        d.ellipse([3, 1, 12, 14], fill=soft); d.ellipse([5, 3, 10, 12], fill=dark); d.point((8, 7), fill=light); d.point((7, 9), fill=mid)
        d.ellipse([3, 1, 12, 14], outline=light)
    elif rite == 'CHRONO_STEP':
        d.ellipse([3, 2, 13, 12], outline=light); d.line([8, 7, 8, 4], fill=light); d.line([8, 7, 10, 9], fill=mid)
        d.polygon([(1, 11), (5, 11), (3, 14)], fill=mid)
    elif rite == 'ORE_SENSE':
        d.polygon([(8, 2), (13, 6), (8, 14), (3, 6)], fill=mid); d.line([3, 6, 13, 6], fill=light); d.line([8, 2, 6, 6], fill=light); d.point((11, 2), fill=light); d.point((2, 12), fill=light)
    elif rite == 'SEER_SIGHT':
        d.ellipse([1, 4, 14, 11], fill=dark, outline=light); d.ellipse([6, 5, 9, 10], fill=mid); d.point((7, 7), fill=light)
    elif rite == 'EARTHBREAKER':
        d.line([8, 4, 5, 13], fill=mid); d.polygon([(2, 5), (8, 2), (14, 6), (11, 6), (8, 4), (5, 6)], fill=light)
    elif rite == 'TUNNEL_BORE':
        d.rectangle([2, 3, 13, 13], fill=soft); d.ellipse([4, 4, 11, 12], fill=dark); d.rectangle([4, 8, 11, 13], fill=dark); d.line([6, 9, 9, 9], fill=light); d.point((7, 6), fill=light)
    elif rite == 'VEIN_REAVER':
        d.line([2, 13, 5, 8], fill=mid); d.line([5, 8, 8, 10], fill=mid); d.line([8, 10, 11, 5], fill=mid); d.line([11, 5, 14, 3], fill=mid)
        for p in ((2, 13), (5, 8), (8, 10), (11, 5), (14, 3)):
            d.rectangle([p[0] - 1, p[1] - 1, p[0], p[1]], fill=light)
    elif rite == 'LUMBERJACK':
        d.line([4, 13, 10, 4], fill=mid); d.polygon([(8, 2), (13, 5), (12, 9), (9, 7)], fill=light)
    elif rite == 'HARVEST':
        d.line([8, 14, 8, 3], fill=mid)
        for y in (5, 8, 11):
            d.line([8, y, 5, y - 2], fill=light); d.line([8, y, 11, y - 2], fill=light)
    elif rite == 'MAGNET':
        d.arc([3, 1, 12, 13], 180, 360, fill=light); d.arc([4, 2, 11, 12], 180, 360, fill=light)
        d.rectangle([3, 7, 5, 13], fill=mid); d.rectangle([10, 7, 12, 13], fill=mid); d.rectangle([3, 11, 5, 13], fill=light); d.rectangle([10, 11, 12, 13], fill=light)
    elif rite == 'FORGE_TOUCH':
        d.polygon([(8, 1), (12, 7), (10, 13), (6, 13), (4, 7), (7, 6)], fill=mid); d.polygon([(8, 6), (10, 10), (8, 13), (6, 10)], fill=light)
    elif rite == 'TERRAFORM':
        d.polygon([(1, 13), (5, 6), (9, 13)], fill=mid); d.polygon([(6, 13), (11, 4), (15, 13)], fill=soft); d.line([1, 13, 14, 13], fill=light)
    elif rite == 'RAMPART':
        d.rectangle([2, 6, 13, 13], fill=mid)
        for x in (2, 6, 10):
            d.rectangle([x, 3, x + 2, 6], fill=mid)
        d.line([2, 9, 13, 9], fill=light); d.rectangle([7, 10, 8, 13], fill=dark)
    elif rite == 'STARLIGHT_BRIDGE':
        d.arc([1, 6, 14, 18], 200, 340, fill=light); d.line([1, 11, 14, 11], fill=mid); d.line([8, 2, 8, 6], fill=light); d.line([6, 4, 10, 4], fill=light); d.point((3, 3), fill=mid); d.point((12, 2), fill=mid)
    elif rite == 'ICE_PATH':
        for i, (x, y) in enumerate(((2, 11), (6, 8), (10, 5))):
            d.polygon([(x, y + 2), (x + 2, y), (x + 4, y + 2), (x + 2, y + 4)], fill=light if i == 1 else mid)
        d.point((13, 2), fill=light); d.point((2, 3), fill=mid)
    elif rite == 'WISP_LIGHT':
        d.ellipse([3, 3, 12, 12], outline=mid); d.ellipse([5, 5, 10, 10], fill=light)
        for p in ((7, 1), (7, 14), (1, 7), (14, 7)):
            d.point(p, fill=mid)
    elif rite == 'UPDRAFT':
        for y in (12, 8, 4):
            d.line([4, y, 8, y - 4], fill=light if y == 8 else mid); d.line([8, y - 4, 12, y], fill=light if y == 8 else mid)
    elif rite == 'SPRING':
        d.polygon([(8, 1), (12, 8), (4, 8)], fill=light); d.ellipse([4, 6, 12, 13], fill=light); d.ellipse([5, 7, 11, 12], fill=mid); d.point((6, 8), fill=light)
    elif rite == 'VOLCANO':
        d.polygon([(1, 14), (6, 6), (10, 6), (15, 14)], fill=soft); d.rectangle([6, 5, 10, 6], fill=mid)
        d.line([8, 5, 8, 1], fill=light); d.point((5, 2), fill=mid); d.point((11, 3), fill=mid); d.point((6, 4), fill=light)
    elif rite == 'HISSING_CHARGE':
        d.ellipse([3, 6, 12, 15], fill=soft); d.ellipse([3, 6, 12, 15], outline=light); d.line([10, 6, 12, 3], fill=mid); d.point((13, 2), fill=light); d.point((5, 9), fill=light)
    elif rite == 'REWIND':
        d.arc([2, 3, 13, 14], 60, 330, fill=light); d.arc([3, 4, 12, 13], 60, 330, fill=light); d.polygon([(10, 1), (14, 4), (9, 5)], fill=mid)
        d.line([8, 8, 8, 5], fill=mid); d.line([8, 8, 10, 9], fill=mid)
    elif rite == 'DAYSPRING':
        d.pieslice([3, 6, 12, 15], 180, 360, fill=light); d.line([1, 11, 14, 11], fill=mid)
        for p in ((7, 2, 7, 4), (2, 5, 4, 7), (12, 5, 10, 7)):
            d.line(p, fill=mid)
    elif rite == 'NIGHTFALL':
        d.ellipse([3, 2, 12, 13], fill=light); d.ellipse([6, 1, 15, 11], fill=shade(rgb, 0.16)); d.point((11, 12), fill=mid); d.point((13, 8), fill=mid); d.point((2, 13), fill=mid)
    elif rite == 'WEATHER_WEAVE':
        d.ellipse([2, 4, 8, 9], fill=light); d.ellipse([5, 2, 12, 9], fill=light); d.rectangle([4, 6, 12, 9], fill=light)
        for x in (4, 7, 10):
            d.line([x, 11, x - 1, 14], fill=mid)
    elif rite == 'SUMMON_TEMPEST':
        d.ellipse([2, 2, 8, 7], fill=soft); d.ellipse([5, 1, 12, 7], fill=soft); d.rectangle([4, 4, 12, 7], fill=soft)
        d.polygon([(9, 7), (6, 11), (8, 11), (7, 15), (11, 9), (9, 9), (10, 7)], fill=light)
    elif rite == 'ETERNAL_WINTER':
        d.line([8, 1, 8, 14], fill=light); d.line([2, 4, 13, 11], fill=light); d.line([2, 11, 13, 4], fill=light)
        for p in ((8, 1), (8, 14), (2, 4), (13, 11), (2, 11), (13, 4)):
            d.point(p, fill=mid)
    elif rite == 'SHADOW_VEIL':
        d.ellipse([3, 1, 12, 9], fill=soft); d.rectangle([3, 5, 12, 12], fill=soft)
        d.polygon([(3, 12), (5, 14), (7, 12), (9, 14), (12, 12)], fill=soft); d.point((6, 6), fill=light); d.point((9, 6), fill=light)
    elif rite == 'SECOND_LIFE':
        d.ellipse([2, 3, 8, 9], fill=mid); d.ellipse([7, 3, 13, 9], fill=mid); d.polygon([(2, 7), (13, 7), (8, 13)], fill=mid)
        d.point((5, 5), fill=light); d.line([7, 7, 7, 10], fill=light); d.line([6, 8, 8, 8], fill=light)
    else:
        raise SystemExit('no glyph for rite ' + rite)


def icon(c):
    base = ELEMENTS[c['element']][0]
    rgb = ((base >> 16) & 255, (base >> 8) & 255, base & 255)
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # a dark round plate with a rim in the element's colour
    d.ellipse([0, 0, 15, 15], fill=shade(rgb, 0.30))
    d.ellipse([1, 1, 14, 14], fill=shade(rgb, 0.16))
    light = (255, 255, 255, 255)
    mid = shade(rgb, 1.0)
    soft = shade(rgb, 0.7)
    k = c['kind']
    if k == 'UTILITY':
        rite_glyph(d, c['rite'], rgb, shade(rgb, 0.16))
        return img
    if k == 'BEAM':
        d.line([2, 12, 13, 3], fill=mid, width=2); d.line([2, 12, 13, 3], fill=light)
    elif k == 'STRIKE':
        d.polygon([(9, 2), (5, 8), (8, 8), (6, 13), (11, 7), (8, 7), (10, 2)], fill=light)
    elif k == 'WARD':
        d.polygon([(8, 2), (13, 4), (12, 10), (8, 14), (4, 10), (3, 4)], fill=mid); d.polygon([(8, 4), (11, 5), (10, 9), (8, 12), (6, 9), (5, 5)], fill=light)
    elif k == 'RESTORE':
        d.rectangle([7, 3, 8, 12], fill=light); d.rectangle([3, 7, 12, 8], fill=light)
    elif k == 'BLINK':
        d.ellipse([3, 3, 12, 12], outline=mid); d.ellipse([6, 6, 9, 9], fill=light); d.point((3, 8), fill=light); d.point((12, 7), fill=light)
    elif k == 'NOVA':
        for a in range(0, 360, 45):
            d.line([7.5, 7.5, 7.5 + math.cos(math.radians(a)) * 6, 7.5 + math.sin(math.radians(a)) * 6], fill=mid)
        d.ellipse([5, 5, 10, 10], fill=light)
    elif k == 'METEOR':
        d.polygon([(2, 2), (9, 6), (6, 9)], fill=soft); d.ellipse([7, 7, 13, 13], fill=light); d.ellipse([8, 8, 12, 12], fill=mid)
    elif k == 'PILLAR':
        d.rectangle([6, 2, 9, 13], fill=mid); d.rectangle([7, 2, 8, 13], fill=light); d.line([3, 13, 12, 13], fill=light)
    elif k == 'CONE':
        d.polygon([(2, 8), (13, 3), (13, 12)], fill=mid); d.polygon([(2, 8), (10, 6), (10, 10)], fill=light)
    elif k == 'WAVE':
        for y in (4, 8, 12):
            d.arc([2, y - 3, 14, y + 3], 200, 340, fill=light if y == 8 else mid)
    elif k == 'BARRAGE':
        for (x, y) in ((3, 9), (7, 5), (11, 9), (6, 11), (10, 3)):
            d.ellipse([x - 1, y - 1, x + 1, y + 1], fill=light)
        d.line([2, 12, 12, 12], fill=mid)
    elif k == 'RAIN':
        d.rectangle([3, 2, 12, 5], fill=soft)
        for x in (4, 7, 10):
            d.line([x, 7, x - 1, 13], fill=light)
    elif k == 'VORTEX':
        d.arc([2, 2, 13, 13], 20, 300, fill=light); d.arc([5, 5, 10, 10], 200, 500, fill=mid); d.point((7, 7), fill=light)
    elif k == 'SANCTUARY':
        d.ellipse([2, 5, 13, 12], outline=mid); d.rectangle([7, 2, 8, 8], fill=light); d.rectangle([5, 4, 10, 5], fill=light)
    elif k == 'BUFF':
        d.polygon([(8, 2), (10, 7), (14, 7), (11, 10), (12, 14), (8, 11), (4, 14), (5, 10), (2, 7), (6, 7)], fill=mid); d.rectangle([7, 7, 8, 8], fill=light)
    elif k == 'CURSE':
        d.ellipse([3, 3, 12, 11], fill=light); d.rectangle([5, 6, 6, 7], fill=shade(rgb, 0.2)); d.rectangle([9, 6, 10, 7], fill=shade(rgb, 0.2)); d.rectangle([6, 12, 9, 13], fill=mid)
    elif k == 'DASH':
        d.polygon([(2, 4), (10, 4), (14, 8), (10, 12), (2, 12), (5, 8)], fill=mid); d.line([6, 8, 12, 8], fill=light)
    # a few individual marks so that no two icons are alike
    h = digest(c['id'] + 'icon', 4096)
    spots = [(2, 2), (13, 2), (2, 13), (13, 13), (7, 1), (1, 7), (14, 8), (8, 14)]
    for j in range(3):
        x, y = spots[(h >> (3 * j)) % len(spots)]
        d.point((x, y), fill=light if j == 0 else mid)
    return img


def check():
    ids = [c['id'] for c in C]
    assert len(ids) == len(set(ids)), 'duplicate ids'
    pairs = {}
    for c in C:
        if c['kind'] == 'UTILITY':
            continue
        key = (c['kind'], c['element'])
        assert key not in pairs, 'copy: %s and %s are both %s/%s' % (pairs[key], c['id'], key[0], key[1])
        pairs[key] = c['id']
    rites = [c['rite'] for c in C if c['kind'] == 'UTILITY']
    assert len(rites) == len(set(rites)), 'a rite has two sigils'
    for n in range(1, 11):
        assert sum(1 for c in C if c['circle'] == n) >= 6, 'Circle %d is too thin' % n
    for old, new in RETIRED.items():
        assert new in ids, 'retired %s -> unknown %s' % (old, new)
        assert old not in ids, 'retired id %s is back in the catalogue' % old
    return ids


def write_all():
    ids = check()
    os.makedirs(ICON_DIR, exist_ok=True)
    for name in os.listdir(ICON_DIR):
        if name.endswith('.png') and name[:-4] not in ids:
            os.remove(os.path.join(ICON_DIR, name))  # the icon of a retired sigil
    for c in C:
        path = os.path.join(ICON_DIR, c['id'] + '.png')
        if 'legacy' in c and os.path.exists(path):
            continue  # the nine original icons are kept
        icon(c).save(path)
    src = open(SIGIL_JAVA, encoding='utf-8').read()
    a = src.index('    // BEGIN GENERATED')
    b = src.index('    // END GENERATED')
    src = src[:a] + '    // BEGIN GENERATED by tools/spell_catalog.py (edit the catalogue, not this block)\n' + java_constants() + '\n' + src[b:]
    a = src.index('    // BEGIN RETIRED')
    b = src.index('    // END RETIRED')
    src = src[:a] + '    // BEGIN RETIRED (generated by tools/spell_catalog.py)\n' + java_retired() + '\n' + src[b:]
    open(SIGIL_JAVA, 'w', encoding='utf-8').write(src)
    print('sigils:', len(C), 'per circle:', {n: sum(1 for c in C if c['circle'] == n) for n in range(1, 11)},
          'rites:', sum(1 for c in C if c['kind'] == 'UTILITY'))


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == 'check':
        print(len(check()), 'ok')
    else:
        write_all()
