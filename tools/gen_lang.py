#!/usr/bin/env python3
"""Merges lang_data.py into the language files: removes keys of the retired spell system, adds the new ones."""
import json, os
from collections import OrderedDict
import lang_data as L
import rite_texts as R
from spell_catalog import C as CATALOGUE

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), '..'))
LANG = os.path.join(ROOT, 'src/main/resources/assets/mana_awakened/lang')
REMOVE_PREFIXES = ('block.mana_awakened.arcane_study_desk', 'container.mana_awakened.study_desk', 'item.mana_awakened.theory_sheet', 'spell.mana_awakened.',
                   'mana_awakened.desk.', 'advancements.mana_awakened.study_desk', 'mana_awakened.configuration.studyDesk',
                   'mana_awakened.configuration.theoryManaFraction', 'mana_awakened.wheel.forget', 'mana_awakened.spell.already_known', 'mana_awakened.spell.learned',
                   'mana_awakened.altar.workshop.create', 'mana_awakened.configuration.coreTrainingRate', 'mana_awakened.configuration.foundationBonus',
                   'mana_awakened.configuration.plainMeditationTraining', 'mana_awakened.configuration.meditationPurityPerSecond',
                   'mana_awakened.configuration.passiveImpurityPerFill', 'mana_awakened.configuration.purityRetainedOnBreakthrough',
                   'mana_awakened.configuration.minSuccessChance', 'mana_awakened.configuration.maxSuccessChance',
                   'mana_awakened.refinement.effect.success', 'mana_awakened.command.set.purity',
                   'mana_awakened.configuration.heartScale', 'mana_awakened.configuration.heartOffset',
                   'mana_awakened.configuration.meditationMovementPenalty')


def build(path, static, index, glyphs):
    data = json.load(open(path, encoding='utf-8'), object_pairs_hook=OrderedDict)
    for key in list(data):
        if key.startswith(REMOVE_PREFIXES) or key.startswith(('sigil.mana_awakened.', 'rite.mana_awakened.', 'mana_awakened.rite.', 'mana_awakened.dimension.')):
            del data[key]
    data.update(static)
    for i, name in enumerate(glyphs):
        data['mana_awakened.glyph.%d' % i] = name
    for pid, texts in L.PASSIVES.items():
        data['passive.mana_awakened.%s' % pid] = texts[index]
        data['passive.mana_awakened.%s.desc' % pid] = texts[index + 1]
    for sid, texts in L.SIGILS.items():
        data['sigil.mana_awakened.%s' % sid] = texts[index]
        data['sigil.mana_awakened.%s.desc' % sid] = texts[index + 1]
    lang = 0 if index == 0 else 1
    for sigil in CATALOGUE:
        if sigil['kind'] != 'UTILITY':
            continue
        rid = sigil['id']
        data['rite.mana_awakened.%s' % rid] = sigil['en'] if lang == 0 else sigil['tr']
        if rid in R.CHOICES:
            heading_en, heading_tr, options = R.CHOICES[rid]
            data['rite.mana_awakened.%s.choice' % rid] = heading_en if lang == 0 else heading_tr
            for cid, en, tr in options:
                data['rite.mana_awakened.%s.%s' % (rid, cid)] = en if lang == 0 else tr
    for key, texts in R.FAIL.items():
        data['mana_awakened.rite.fail.%s' % key] = texts[lang]
    for key, texts in R.MESSAGES.items():
        data['mana_awakened.rite.%s' % key] = texts[lang]
    for key, texts in R.DIMENSIONS.items():
        data['mana_awakened.dimension.%s' % key] = texts[lang]
    for key, texts in R.UI.items():
        data[key] = texts[lang]
    for sid, en, tr in R.STRUCTURE_NAMES:
        data['mana_awakened.structure.minecraft.%s' % sid] = en if lang == 0 else tr
    for gid, en, tr in R.STRUCTURE_GROUPS:
        data['mana_awakened.target.structure_group.minecraft.%s' % gid] = en if lang == 0 else tr
    for kind, en, tr in R.ORE_GROUPS:
        data['mana_awakened.target.ore_group.c.ores/%s' % kind] = en if lang == 0 else tr
    for key, en, tr in R.TARGET_UI:
        data[key] = en if lang == 0 else tr
    for key, en, tr in R.QUICK_UI:
        data[key] = en if lang == 0 else tr
    for mid, texts in L.MODIFIERS.items():
        data['modifier.mana_awakened.%s' % mid] = texts[index]
        data['modifier.mana_awakened.%s.desc' % mid] = texts[index + 1]
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write('\n')
    return data


en = build(os.path.join(LANG, 'en_us.json'), L.EN, 0, L.GLYPHS_EN)
tr = build(os.path.join(LANG, 'tr_tr.json'), L.TR, 2, L.GLYPHS_TR)
print('en', len(en), 'tr', len(tr))
missing = [k for k in en if k not in tr]
print('missing in tr:', missing)
