#!/usr/bin/env python3
"""The texts of the working spells ("rites"): the heading and the options of every choice, the messages a cast shows and the
reasons a cast can be refused. Merged into the language files by gen_lang.py; the names of the rites are the names of their sigils."""

# rite id -> (EN heading, TR heading, [(choice id, EN, TR), ...]); the choice ids are the ones in spell/Rite.java (the ores, structures and worlds
# have no fixed list since 3.5.0: their names come from the game, or from STRUCTURE_NAMES, STRUCTURE_GROUPS and ORE_GROUPS below)
PLACES = [('spawn', 'World spawn', 'Dünya doğuş noktası'), ('bed', 'Your bed', 'Yatağın'), ('death', 'Last death', 'Son ölüm yeri')]
ANCHORS = [('1', 'Anchor I', 'Çıpa I'), ('2', 'Anchor II', 'Çıpa II'), ('3', 'Anchor III', 'Çıpa III')]
MINING = [('pickaxe', 'Pickaxe', 'Kazma'), ('silk', 'Silk touch', 'İpek dokunuş'), ('fortune', 'Fortune', 'Servet')]

CHOICES = {
    'wayfinder': ('Destination', 'Hedef', PLACES),
    'homeward': ('Destination', 'Hedef', [PLACES[1], PLACES[0], PLACES[2]]),
    'anchor_set': ('Anchor', 'Çıpa', ANCHORS),
    'anchor_return': ('Anchor', 'Çıpa', ANCHORS),
    'structure_compass': ('Structure', 'Yapı', []),
    'rift_walk': ('Structure', 'Yapı', []),
    'dimension_gate': ('World', 'Dünya', []),
    'ore_sense': ('Ore', 'Cevher', []),
    'seer_sight': ('Creatures', 'Yaratıklar', [('hostile', 'Hostile', 'Düşmanlar'), ('animals', 'Animals', 'Hayvanlar'), ('players', 'Players', 'Oyuncular'),
                                              ('all', 'Everything', 'Her şey')]),
    'earthbreaker': ('Mode', 'Kip', MINING + [('shatter', 'Shatter (no drops)', 'Parçala (eşya yok)')]),
    'tunnel_bore': ('Width', 'Genişlik', [('narrow', 'Narrow', 'Dar'), ('wide', 'Wide', 'Geniş')]),
    'vein_reaver': ('Mode', 'Kip', MINING),
    'lumberjack': ('After felling', 'Kestikten sonra', [('fell', 'Just fell', 'Sadece devir'), ('replant', 'Fell and replant', 'Devir ve fidan dik')]),
    'forge_touch': ('Smelts', 'Eritilen', [('held', 'What I hold', 'Elimdekini'), ('nearby', 'What lies about', 'Etrafta yatanları')]),
    'dayspring': ('Hour', 'Saat', [('sunrise', 'Sunrise', 'Şafak'), ('noon', 'Noon', 'Öğle')]),
    'nightfall': ('Hour', 'Saat', [('sunset', 'Sunset', 'Gün batımı'), ('midnight', 'Midnight', 'Gece yarısı')]),
    'weather_weave': ('Weather', 'Hava', [('clear', 'Clear sky', 'Açık hava'), ('rain', 'Rain', 'Yağmur')]),
}

DIMENSIONS = {
    'minecraft.overworld': ('Overworld', 'Overworld'), 'minecraft.the_nether': ('The Nether', 'Nether'), 'minecraft.the_end': ('The End', 'End'),
}

# (EN, TR)
FAIL = {
    'unknown': ('Nothing happens.', 'Hiçbir şey olmadı.'),
    'disabled_dig': ('This server does not let spells break blocks.', 'Bu sunucu büyülerin blok kırmasına izin vermiyor.'),
    'disabled_build': ('This server does not let spells change the terrain.', 'Bu sunucu büyülerin araziyi değiştirmesine izin vermiyor.'),
    'disabled_sky': ('This server does not let spells change the weather or the time.', 'Bu sunucu büyülerin havayı ve zamanı değiştirmesine izin vermiyor.'),
    'disabled_travel': ('This server does not let spells teleport you.', 'Bu sunucu büyülerin seni ışınlamasına izin vermiyor.'),
    'other_world': ('That place lies in another world.', 'Orası başka bir dünyada.'),
    'no_bed': ('You have no bed or respawn anchor.', 'Yatağın ya da yeniden doğuş çıpan yok.'),
    'no_death': ('You have not died yet.', 'Henüz ölmedin.'),
    'no_safe_spot': ('There is no safe place to arrive at.', 'Varılacak güvenli bir yer yok.'),
    'no_anchor': ('That anchor has not been set yet.', 'O çıpa henüz bırakılmadı.'),
    'no_structure': ('No structure of that kind within reach.', 'Menzilde o türden bir yapı yok.'),
    'no_world': ('That world is not there.', 'O dünya yok.'),
    'same_world': ('You are already in that world.', 'Zaten o dünyadasın.'),
    'no_history': ('There is no moment to step back to yet.', 'Geri dönülecek bir an henüz yok.'),
    'no_ore': ('No ore of that kind within reach.', 'Menzilde o türden cevher yok.'),
    'no_creatures': ('No creature of that kind within reach.', 'Menzilde o türden yaratık yok.'),
    'no_target': ('Look at a block.', 'Bir bloğa bak.'),
    'blocked': ('There is no room, or it is not yours to change.', 'Yer yok ya da orayı değiştirme iznin yok.'),
    'nothing_found': ('There is nothing to work on.', 'Üzerinde çalışılacak bir şey yok.'),
    'no_tree': ('Look at a living tree.', 'Yaşayan bir ağaca bak.'),
    'no_crops': ('No ripe crops within reach.', 'Menzilde olgun ekin yok.'),
    'nothing_to_smelt': ('Nothing to smelt.', 'Eritilecek bir şey yok.'),
    'no_sky': ('This world has no sky.', 'Bu dünyanın gökyüzü yok.'),
    'too_hot': ('The water would boil away in this heat.', 'Su bu sıcakta buharlaşırdı.'),
    'nothing_to_rewind': ('Nothing has changed here lately.', 'Burada son zamanlarda bir şey değişmedi.'),
    'no_dark': ('There is no dark place around.', 'Çevrede karanlık bir yer yok.'),
    'missing_target': ('Nothing to aim at: %s is not on this server.', 'Hedef yok: %s bu sunucuda bulunmuyor.'),
}

# (EN, TR)
MESSAGES = {
    'bearing': ('%1$s lies %2$s blocks to the %3$s.', '%1$s: %3$s yönünde, %2$s blok uzakta.'),
    'compass.n': ('north', 'kuzey'), 'compass.ne': ('north-east', 'kuzeydoğu'), 'compass.e': ('east', 'doğu'), 'compass.se': ('south-east', 'güneydoğu'),
    'compass.s': ('south', 'güney'), 'compass.sw': ('south-west', 'güneybatı'), 'compass.w': ('west', 'batı'), 'compass.nw': ('north-west', 'kuzeybatı'),
    'compass_bar': ('%1$s   %3$s   %2$s blocks', '%1$s   %3$s   %2$s blok'),
    'compass_here': ('You have reached: %s', 'Vardın: %s'),
    'anchor_set': ('Anchor %1$s set at %2$s, %3$s, %4$s.', 'Çıpa %1$s şuraya bırakıldı: %2$s, %3$s, %4$s.'),
    'rift': ('You step through the rift: %s', 'Yarıktan geçiyorsun: %s'),
    'gate': ('A gate opens to: %s', 'Bir geçit açıldı: %s'),
    'harvest': ('%s crops harvested.', '%s ekin biçildi.'),
    'ore_found': ('%1$s ore blocks found; the nearest is %2$s blocks away.', '%1$s cevher bloğu bulundu; en yakını %2$s blok uzakta.'),
    'seer_found': ('%s creatures glow.', '%s yaratık parlıyor.'),
    'vein': ('A vein of %s blocks.', '%s bloktan oluşan bir damar.'),
    'smelted': ('%s items smelted.', '%s eşya eritildi.'),
    'tunnel_stopped': ('The tunnel stops before the water.', 'Tünel suya varmadan durdu.'),
    'interrupted': ('The spell is broken.', 'Büyü bozuldu.'),
    'rewound': ('%s blocks restored.', '%s blok yerine döndü.'),
    'wisps': ('%s wisps light the dark.', '%s ruh ışığı karanlığı aydınlatıyor.'),
}

# keys of the user interface that belong to the working spells
UI = {
    'mana_awakened.altar.workshop.working': ('Working spells', 'İşlevsel büyüler'),
    'mana_awakened.altar.stat.area': ('Area %s', 'Alan %s'),
    'mana_awakened.altar.stat.rite_power': ('Strength %s', 'Güç %s'),
    'mana_awakened.altar.stat.rite_gate.dig': ('Needs: spells may destroy blocks', 'Gerekir: büyüler blok yıkabilsin'),
    'mana_awakened.altar.stat.rite_gate.build': ('Needs: spells may change the terrain', 'Gerekir: büyüler araziyi değiştirebilsin'),
    'mana_awakened.altar.stat.rite_gate.sky': ('Needs: spells may change weather and time', 'Gerekir: büyüler hava ve zamanı değiştirebilsin'),
    'mana_awakened.altar.stat.rite_gate.travel': ('Needs: spells may teleport', 'Gerekir: büyüler ışınlayabilsin'),
}

# 3.5.0: the targets of the working spells come from the registries; these are the names of what vanilla brings
# (structure id, EN, TR) -> mana_awakened.structure.minecraft.<id>
STRUCTURE_NAMES = [
    ('village_plains', 'Plains Village', 'Ova Köyü'),
    ('village_desert', 'Desert Village', 'Çöl Köyü'),
    ('village_savanna', 'Savanna Village', 'Savan Köyü'),
    ('village_snowy', 'Snowy Village', 'Karlı Köy'),
    ('village_taiga', 'Taiga Village', 'Tayga Köyü'),
    ('pillager_outpost', 'Pillager Outpost', 'Yağmacı Karakolu'),
    ('mineshaft', 'Mineshaft', 'Terk Edilmiş Maden'),
    ('mineshaft_mesa', 'Badlands Mineshaft', 'Kanyon Madeni'),
    ('mansion', 'Woodland Mansion', 'Orman Malikânesi'),
    ('jungle_pyramid', 'Jungle Temple', 'Orman Tapınağı'),
    ('desert_pyramid', 'Desert Pyramid', 'Çöl Piramidi'),
    ('igloo', 'Igloo', 'İglo'),
    ('shipwreck', 'Shipwreck', 'Gemi Enkazı'),
    ('shipwreck_beached', 'Beached Shipwreck', 'Karaya Vurmuş Gemi Enkazı'),
    ('swamp_hut', 'Swamp Hut', 'Bataklık Kulübesi'),
    ('stronghold', 'Stronghold', 'Kale'),
    ('monument', 'Ocean Monument', 'Okyanus Anıtı'),
    ('ocean_ruin_cold', 'Cold Ocean Ruins', 'Soğuk Okyanus Harabesi'),
    ('ocean_ruin_warm', 'Warm Ocean Ruins', 'Ilık Okyanus Harabesi'),
    ('fortress', 'Nether Fortress', 'Nether Hisarı'),
    ('nether_fossil', 'Nether Fossil', 'Nether Fosili'),
    ('end_city', 'End City', 'End Şehri'),
    ('buried_treasure', 'Buried Treasure', 'Gömülü Hazine'),
    ('bastion_remnant', 'Bastion Remnant', 'Bastion Kalıntısı'),
    ('ruined_portal', 'Ruined Portal', 'Harap Portal'),
    ('ruined_portal_desert', 'Ruined Portal (desert)', 'Harap Portal (çöl)'),
    ('ruined_portal_jungle', 'Ruined Portal (jungle)', 'Harap Portal (orman)'),
    ('ruined_portal_swamp', 'Ruined Portal (swamp)', 'Harap Portal (bataklık)'),
    ('ruined_portal_mountain', 'Ruined Portal (mountain)', 'Harap Portal (dağ)'),
    ('ruined_portal_ocean', 'Ruined Portal (ocean)', 'Harap Portal (okyanus)'),
    ('ruined_portal_nether', 'Ruined Portal (Nether)', 'Harap Portal (Nether)'),
    ('ancient_city', 'Ancient City', 'Antik Şehir'),
    ('trail_ruins', 'Trail Ruins', 'İz Kalıntıları'),
    ('trial_chambers', 'Trial Chambers', 'Deneme Odaları'),
]
# (tag path, EN, TR) -> mana_awakened.target.structure_group.minecraft.<path>
STRUCTURE_GROUPS = [
    ('village', 'Any village', 'Herhangi bir köy'),
    ('mineshaft', 'Any mineshaft', 'Herhangi bir maden'),
    ('shipwreck', 'Any shipwreck', 'Herhangi bir gemi enkazı'),
    ('ruined_portal', 'Any ruined portal', 'Herhangi bir harap portal'),
    ('ocean_ruin', 'Any ocean ruins', 'Herhangi bir okyanus harabesi'),
    ('dolphin_located', 'Ocean ruins and shipwrecks', 'Okyanus harabeleri ve gemi enkazları'),
]
# (c:ores/<kind>, EN, TR) -> mana_awakened.target.ore_group.c.ores/<kind>
ORE_GROUPS = [
    ('coal', 'Any coal ore', 'Herhangi bir kömür cevheri'),
    ('copper', 'Any copper ore', 'Herhangi bir bakır cevheri'),
    ('iron', 'Any iron ore', 'Herhangi bir demir cevheri'),
    ('gold', 'Any gold ore', 'Herhangi bir altın cevheri'),
    ('redstone', 'Any redstone ore', 'Herhangi bir kızıltaş cevheri'),
    ('lapis', 'Any lapis lazuli ore', 'Herhangi bir lapis lazuli cevheri'),
    ('diamond', 'Any diamond ore', 'Herhangi bir elmas cevheri'),
    ('emerald', 'Any emerald ore', 'Herhangi bir zümrüt cevheri'),
    ('quartz', 'Any nether quartz ore', 'Herhangi bir Nether kuvarsı cevheri'),
    ('netherite_scrap', 'Any ancient debris', 'Herhangi bir kadim kalıntı'),
]
# the target picker of the altar and its refusals (key, EN, TR)
TARGET_UI = [
    ('mana_awakened.target.any_ore', 'Any ore', 'Herhangi bir cevher'),
    ('mana_awakened.target.missing', 'missing: %s', 'eksik: %s'),
    ('mana_awakened.target.choose', 'Choose…', 'Seç…'),
    ('mana_awakened.target.done', 'Done', 'Tamam'),
    ('mana_awakened.target.title', 'Targets: %s', 'Hedefler: %s'),
    ('mana_awakened.target.search', 'Search a name or an id', 'Ad ya da kimlik ara'),
    ('mana_awakened.target.tab.all', 'All', 'Tümü'),
    ('mana_awakened.target.none', 'Nothing matches.', 'Eşleşen bir şey yok.'),
    ('mana_awakened.target.remove', 'Remove', 'Kaldır'),
    ('mana_awakened.target.color.tip', 'Colour of %s: click to change it', '%s rengi: değiştirmek için tıkla'),
    ('mana_awakened.target.color.title', 'Colour: %s', 'Renk: %s'),
    ('mana_awakened.target.color.hex', 'Hex (#RRGGBB)', 'Hex (#RRGGBB)'),
    ('mana_awakened.target.color.reset', 'Default colour', 'Varsayılan renk'),
    ('mana_awakened.altar.targets.refused.too_many', 'At most %s targets.', 'En fazla %s hedef.'),
    ('mana_awakened.altar.targets.refused.too_few', 'This spell needs at least one target.', 'Bu büyünün en az bir hedefe ihtiyacı var.'),
    ('mana_awakened.altar.targets.refused.unknown', 'That target does not exist on this server.', 'Bu hedef bu sunucuda yok.'),
    ('mana_awakened.altar.targets.refused.none', 'This spell takes no target.', 'Bu büyü hedef almaz.'),
    ('mana_awakened.altar.workshop.create_refused', 'Your Circle is too low to make a spell of %1$s: it needs Circle %2$s.', '%1$s büyüsünü yapmak için Çemberin yetmiyor: %2$s. Çember ister.'),
]
# 3.6.0: the quick target menu in the field, Shift+wheel and the refusals of the field path (key, EN, TR)
QUICK_UI = [
    ('key.mana_awakened.quick_targets', 'Quick target menu', 'Hızlı hedef menüsü'),
    ('mana_awakened.quick.title', 'Quick targets', 'Hızlı hedefler'),
    ('mana_awakened.quick.heading', '%s · %s', '%s · %s'),
    ('mana_awakened.quick.recent', 'Recent', 'Son'),
    ('mana_awakened.quick.favourites', 'Favourites', 'Favoriler'),
    ('mana_awakened.quick.star', 'Add to favourites (Shift + wheel steps through them)', 'Favorilere ekle (Shift + tekerlek aralarında gezer)'),
    ('mana_awakened.quick.unstar', 'Remove from favourites', 'Favorilerden çıkar'),
    ('mana_awakened.quick.no_targets', 'This spell has no targets', 'Bu büyünün hedefi yok'),
    ('mana_awakened.quick.no_favourites', 'No favourites for this spell yet: star some in the quick target menu (%s).',
     'Bu büyü için henüz favori yok: hızlı hedef menüsünde (%s) yıldızla.'),
    ('mana_awakened.quick.cycled', 'Target: %s (%s/%s)', 'Hedef: %s (%s/%s)'),
    ('mana_awakened.target.field.refused.spell', 'That spell is not in your book.', 'Bu büyü kitabında yok.'),
    ('mana_awakened.target.field.refused.meditating', 'Not while meditating.', 'Meditasyon sırasında olmaz.'),
    ('mana_awakened.target.field.refused.unable', 'Targets cannot be changed while dead or spectating.', 'Ölüyken ya da izleyiciyken hedefler değiştirilemez.'),
    ('mana_awakened.target.field.refused.too_fast', 'Too many changes at once: wait a moment.', 'Aynı anda çok fazla değişiklik: biraz bekle.'),
    ('mana_awakened.configuration.quickTargets', 'Quick target menu', 'Hızlı hedef menüsü'),
    ('mana_awakened.configuration.quickTargetScroll', 'Wheel modifier for targets', 'Hedefler için tekerlek tuşu'),
    ('mana_awakened.configuration.scroll_modifier.shift', 'Shift', 'Shift'),
    ('mana_awakened.configuration.scroll_modifier.control', 'Ctrl', 'Ctrl'),
    ('mana_awakened.configuration.scroll_modifier.alt', 'Alt', 'Alt'),
    ('mana_awakened.configuration.scroll_modifier.off', 'Off', 'Kapalı'),
]
