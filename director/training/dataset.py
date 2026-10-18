"""Reproducible weak-policy data, never evidence of real player success.

From director/: python -m training.dataset --catalog ../pack/config/dead-earth/director/catalog.json
    --out ../work/director-training/data --seed 42

Families are assigned to splits below, before any random variation. Each base
case emits every cyclic permutation of a shuffled option list: the selected
event visits each index once. This balances indices *within option-list length*,
not the marginal histogram across lists of different lengths.
"""

import argparse
from collections import Counter, defaultdict
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import random
import re


POLICY_VERSION = 'de-tactics-heuristic-v1'
SPLITS = ('train', 'validation', 'test')
SEASONS = {'CALM', 'ASH_WINTER', 'HEAT'}
OUTCOMES = {'repelled', 'contained', 'avoided', 'engaged', 'inconclusive', 'uncertain', 'expired', 'cancelled'}
IDENTIFIER = re.compile(r'[a-z][a-z0-9_]{0,63}\Z')
HASH = re.compile(r'[0-9a-f]{64}\Z')
POLICY_DESCRIPTION = (
    'Weak synthetic preference, not measured game success: base option weight; '
    'same-event recent outcome penalties repelled=3, contained=1.5, engaged=.6, '
    'avoided=.6, inconclusive=.3, uncertain/expired/cancelled=0; '
    'two latest known events of one pure faction favor the other by 1.25 '
    '(mixed +.4); pressure<1 favors reconnaissance/lure/signal_investigation/passage '
    'by 2 and penalizes combined_pressure/pursuit_trap/resource_pressure/excavation_trap '
    'by 1.5; pressure>=1.8 favors weakness_probe/feint/area_denial by .75; '
    'matching winter seasonal_ambush or heat fire_decoy gets +1. '
    'Unknown history IDs are ignored. All tied maxima are acceptable; '
    'lexicographically first event is the canonical target, independent of option order.'
)


@dataclass(frozen=True)
class Family:
    name: str
    split: str
    season: str
    phase: int
    pressure: float
    focus: tuple[str, ...]
    recent: tuple[tuple[str, str], ...] = ()
    singleton: bool = False


# These are curated source situations, not random row-level split assignments.
FAMILIES = (
    Family('forest_opening', 'train', 'CALM', 0, .6, ('spore_lure', 'spore_fog', 'interrupted_harvest')),
    Family('reconnaissance_repelled', 'train', 'CALM', 1, 1.3, ('flank_rustle', 'spore_lure', 'relay_echo'),
           (('flank_rustle', 'repelled'), ('flank_rustle', 'repelled'))),
    Family('pressure_side_switch', 'train', 'CALM', 2, 1.5, ('forked_tracks', 'underground_pulse', 'defense_probe'),
           (('brood_silence', 'contained'), ('false_siege', 'engaged'))),
    Family('winter_probe', 'train', 'ASH_WINTER', 3, 1.5, ('cold_focus', 'migration')),
    Family('heat_diversion', 'train', 'HEAT', 4, 2.0, ('dry_thunder', 'spore_fog')),
    Family('pursuit_recovery', 'train', 'CALM', 5, .65, ('false_breather', 'interrupted_harvest', 'cross_front'),
           (('false_breather', 'repelled'),)),
    Family('incubation_patience', 'train', 'CALM', 2, 1.25, ('brood_silence', 'false_siege'),
           (('migration', 'inconclusive'),)),
    Family('single_allowed', 'train', 'HEAT', 1, 1.1, ('cross_front',),
           (('cross_front', 'repelled'),), True),
    Family('unknown_old_history', 'validation', 'CALM', 0, .9, ('spore_fog', 'spore_lure'),
           (('retired_event', 'repelled'),)),
    Family('winter_exhausted_probe', 'validation', 'ASH_WINTER', 3, 2.1, ('defense_probe', 'cold_focus', 'false_siege'),
           (('defense_probe', 'repelled'), ('defense_probe', 'repelled'))),
    Family('forest_pressure_reversal', 'validation', 'HEAT', 2, 1.4, ('dry_thunder', 'flank_rustle', 'forked_tracks'),
           (('spore_lure', 'engaged'), ('spore_fog', 'contained'))),
    Family('mixed_front_repelled', 'test', 'CALM', 5, 1.9, ('cross_front', 'defense_probe', 'relay_echo'),
           (('cross_front', 'repelled'), ('cross_front', 'repelled'))),
    Family('heat_soft_exploration', 'test', 'HEAT', 1, .8, ('dry_thunder', 'migration', 'false_breather'),
           (('interrupted_harvest', 'avoided'),)),
    Family('winter_single_survivor', 'test', 'ASH_WINTER', 4, 1.8, ('cold_focus',),
           (('cold_focus', 'repelled'),), True),
    Family('forest_only_recovery', 'test', 'ASH_WINTER', 0, .55, ('interrupted_harvest', 'spore_lure'),
           (('flank_rustle', 'repelled'), ('brood_silence', 'cancelled'))),
)


def canonical_json(value):
    """The UTF-8 serialization used by all dataset hashes."""
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _digest(value):
    return _sha(canonical_json(value).encode('utf-8'))


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate JSON key: ' + key)
        result[key] = value
    return result


def strict_json(raw):
    def constant(value):
        raise ValueError('non-finite JSON constant: ' + value)
    return json.loads(raw, object_pairs_hook=_pairs, parse_constant=constant)


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _number(value, minimum, maximum):
    return type(value) in (int, float) and minimum <= value <= maximum and math.isfinite(value)


def _identifier(value):
    return type(value) is str and IDENTIFIER.fullmatch(value) is not None


def validate_context(context):
    """Reject non-public fields instead of silently dropping them."""
    _require(type(context) is dict and set(context) == {'season', 'pressure', 'parasite_phase', 'options', 'recent'}, 'invalid public context fields')
    _require(type(context['season']) is str and context['season'] in SEASONS, 'invalid season')
    _require(_number(context['pressure'], .05, 4), 'invalid pressure')
    _require(type(context['parasite_phase']) is int and 0 <= context['parasite_phase'] <= 5, 'invalid parasite phase')
    options = context['options']
    _require(type(options) is list and 1 <= len(options) <= 8, 'invalid option count')
    seen = set()
    for index, option in enumerate(options):
        _require(type(option) is dict and set(option) == {'option', 'event', 'title', 'tactic', 'faction', 'weight'}, 'invalid public option fields')
        _require(type(option['option']) is int and option['option'] == index, 'non-contiguous option index')
        _require(_identifier(option['event']) and option['event'] not in seen, 'invalid or duplicate event')
        seen.add(option['event'])
        _require(_identifier(option['tactic']), 'invalid tactic')
        title = option['title']
        _require(type(title) is str and 1 <= len(title) <= 256 and not any(ord(c) < 32 for c in title), 'invalid title')
        _require(type(option['faction']) is str and option['faction'] in {'forest', 'parasites', 'mixed'}, 'invalid faction')
        _require(_number(option['weight'], 0, 10000) and option['weight'] > 0, 'invalid weight')
        _require(context['parasite_phase'] != 0 or option['faction'] == 'forest', 'phase zero permits forest only')
    recent = context['recent']
    _require(type(recent) is list and len(recent) <= 6, 'invalid recent history')
    for history in recent:
        _require(type(history) is dict and set(history) == {'event', 'outcome'}, 'invalid public history fields')
        _require(_identifier(history['event']) and type(history['outcome']) is str and history['outcome'] in OUTCOMES, 'invalid history value')


def validate_row(row):
    """Validate one schema-1 curriculum row; return it without modifying it."""
    _require(type(row) is dict and set(row) == {'id', 'family', 'split', 'context', 'target', 'acceptable_options', 'provenance'}, 'invalid row fields')
    _require(type(row['id']) is str and 1 <= len(row['id']) <= 128 and re.fullmatch(r'[a-zA-Z0-9_-]+', row['id']), 'invalid row id')
    _require(_identifier(row['family']) and row['split'] in SPLITS, 'invalid family or split')
    validate_context(row['context'])
    count = len(row['context']['options'])
    target = row['target']
    _require(type(target) is dict and set(target) == {'option'} and type(target['option']) is int and 0 <= target['option'] < count, 'invalid target')
    acceptable = row['acceptable_options']
    _require(type(acceptable) is list and acceptable and all(type(i) is int and 0 <= i < count for i in acceptable), 'invalid acceptable labels')
    _require(len(set(acceptable)) == len(acceptable) and target['option'] in acceptable, 'invalid acceptable label set')
    provenance = row['provenance']
    required = {'kind', 'policy'}
    allowed = required | {'label_strength', 'rationale', 'catalog_sha256', 'context_sha256', 'permutation_group', 'variant', 'permutation'}
    _require(type(provenance) is dict and required <= set(provenance) <= allowed, 'invalid provenance fields')
    _require(provenance['kind'] == 'synthetic_policy' and type(provenance['policy']) is str and 1 <= len(provenance['policy']) <= 128, 'invalid synthetic policy provenance')
    if 'label_strength' in provenance:
        _require(provenance['label_strength'] == 'weak', 'synthetic labels must be weak')
    if 'rationale' in provenance:
        _require(type(provenance['rationale']) is str and 1 <= len(provenance['rationale']) <= 4096, 'invalid teacher rationale')
    for key in ('catalog_sha256', 'context_sha256', 'permutation_group'):
        if key in provenance:
            _require(type(provenance[key]) is str and HASH.fullmatch(provenance[key]), 'invalid ' + key)
    if 'context_sha256' in provenance:
        _require(provenance['context_sha256'] == _digest(row['context']), 'context hash mismatch')
    for key in ('variant', 'permutation'):
        if key in provenance:
            _require(type(provenance[key]) is int and provenance[key] >= 0, 'invalid ' + key)
    return row


def validate_rows(rows):
    """Also reject duplicate contexts/IDs and a source family crossing splits."""
    ids, contexts, families, groups = set(), set(), {}, {}
    for row in rows:
        validate_row(row)
        context_hash = _digest(row['context'])
        _require(row['id'] not in ids and context_hash not in contexts, 'duplicate row id or context')
        ids.add(row['id'])
        contexts.add(context_hash)
        _require(families.setdefault(row['family'], row['split']) == row['split'], 'source family crosses splits')
        group = row['provenance'].get('permutation_group')
        if group:
            source = (row['family'], row['split'])
            _require(groups.setdefault(group, source) == source, 'permutation group crosses source family or split')
    return rows


def load_rows(path):
    """Load strict UTF-8 JSONL; duplicate keys, blank rows and leaks are errors."""
    rows = []
    with Path(path).open(encoding='utf-8') as stream:
        for line_number, line in enumerate(stream, 1):
            try:
                rows.append(validate_row(strict_json(line)))
            except (ValueError, TypeError, KeyError) as error:
                raise ValueError(f'{path}:{line_number}: invalid curriculum row: {error}') from error
    return validate_rows(rows)


def _catalog(path):
    raw = Path(path).read_bytes()
    catalog = strict_json(raw.decode('utf-8'))
    _require(type(catalog) is dict and type(catalog.get('events')) is list and catalog['events'], 'invalid catalog events')
    events = catalog['events']
    seen = set()
    for event in events:
        _require(type(event) is dict and _identifier(event.get('id')) and event['id'] not in seen, 'invalid or duplicate catalog event')
        seen.add(event['id'])
        _require(type(event.get('seasons')) is list and event['seasons'] and set(event['seasons']) <= SEASONS, 'invalid catalog seasons')
        # Reuse the public option contract without exposing catalog graph internals.
        option = dict(option=0, event=event['id'], **{key: event[key] for key in ('title', 'tactic', 'faction', 'weight')})
        validate_context(dict(season=event['seasons'][0], pressure=1, parasite_phase=1, options=[option], recent=[]))
    return events, _sha(raw)


def label_context(context, catalog_events):
    """Return target/acceptable_options/rationale for the documented weak teacher.

    catalog_events is the catalog's public ``events`` list. History order is
    chronological; only the last two known events influence faction switching.
    """
    validate_context(context)
    catalog = {event['id']: event for event in catalog_events}
    known = [h for h in context['recent'] if h['event'] in catalog]
    factions = [catalog[h['event']]['faction'] for h in known[-2:]]
    switch_from = factions[0] if len(factions) == 2 and factions[0] == factions[1] and factions[0] in {'forest', 'parasites'} else None
    penalties = {'repelled': 3, 'contained': 1.5, 'engaged': .6, 'avoided': .6, 'inconclusive': .3}
    scores = []
    for option in context['options']:
        score = option['weight']
        for history in known:
            if history['event'] == option['event']:
                score -= penalties.get(history['outcome'], 0)
        if switch_from and option['faction'] != switch_from:
            score += .4 if option['faction'] == 'mixed' else 1.25
        if context['pressure'] < 1:
            if option['tactic'] in {'reconnaissance', 'lure', 'signal_investigation', 'passage'}:
                score += 2
            if option['tactic'] in {'combined_pressure', 'pursuit_trap', 'resource_pressure', 'excavation_trap'}:
                score -= 1.5
        if context['pressure'] >= 1.8 and option['tactic'] in {'weakness_probe', 'feint', 'area_denial'}:
            score += .75
        if (context['season'], option['tactic']) in {('ASH_WINTER', 'seasonal_ambush'), ('HEAT', 'fire_decoy')}:
            score += 1
        scores.append(round(score, 8))
    maximum = max(scores)
    acceptable = [index for index, score in enumerate(scores) if math.isclose(score, maximum, rel_tol=0, abs_tol=1e-8)]
    selected = min(acceptable, key=lambda index: context['options'][index]['event'])
    rationale = ('weak synthetic policy; repelled/other outcome penalties, faction diversity, pressure and season preferences; '
                 + '; '.join(f"{o['event']}={score:g}" for o, score in zip(context['options'], scores))
                 + '; canonical event=' + context['options'][selected]['event'])
    return dict(target={'option': selected}, acceptable_options=acceptable, rationale=rationale)


def generate_rows(catalog_path, seed=42, variants_per_family=12):
    """Generate all three splits from a catalog path, without writing files."""
    _require(type(seed) is int, 'seed must be an integer')
    _require(type(variants_per_family) is int and 1 <= variants_per_family <= 10000, 'invalid variants_per_family')
    events, catalog_hash = _catalog(catalog_path)
    rows = []
    for family in FAMILIES:
        eligible = sorted((e for e in events if family.season in e['seasons'] and (family.phase != 0 or e['faction'] == 'forest')), key=lambda e: e['id'])
        _require(eligible, 'catalog has no eligible events for ' + family.name)
        for variant in range(variants_per_family):
            rng = random.Random(int(_digest([seed, family.name, variant]), 16))
            by_id = {e['id']: e for e in eligible}
            focus = [by_id[event_id] for event_id in family.focus if event_id in by_id]
            if family.singleton:
                chosen = (focus or eligible)[:1]
            else:
                count = min(len(eligible), max(len(focus), 2 + variant % 7))
                others = [e for e in eligible if e not in focus]
                rng.shuffle(others)
                chosen = focus + others[:count - len(focus)]
            options = [dict(event=e['id'], **{key: e[key] for key in ('title', 'tactic', 'faction')},
                            weight=round(e['weight'] * rng.uniform(.85, 1.15), 4)) for e in chosen]
            rng.shuffle(options)
            pressure = round(max(.05, min(4, family.pressure + rng.uniform(-.1, .1))), 6)
            base = dict(season=family.season, pressure=pressure, parasite_phase=family.phase,
                        recent=[dict(event=event, outcome=outcome) for event, outcome in family.recent])
            group = _digest([seed, family.name, variant])
            for rotation in range(len(options)):
                ordered = options[rotation:] + options[:rotation]
                context = dict(base, options=[dict(option=index, **option) for index, option in enumerate(ordered)])
                labels = label_context(context, events)
                context_hash = _digest(context)
                rows.append(dict(id=_digest([family.name, family.split, context_hash]), family=family.name, split=family.split,
                                 context=context, target=labels['target'], acceptable_options=labels['acceptable_options'],
                                 provenance=dict(kind='synthetic_policy', policy=POLICY_VERSION, label_strength='weak',
                                                 rationale=labels['rationale'], catalog_sha256=catalog_hash, context_sha256=context_hash,
                                                 permutation_group=group, variant=variant, permutation=rotation)))
    random.Random(seed).shuffle(rows)
    return validate_rows(rows)


def write_dataset(catalog_path, out_dir, seed=42, variants_per_family=12):
    """Write deterministic JSONL splits and manifest; return the manifest dict."""
    rows = generate_rows(catalog_path, seed, variants_per_family)
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    manifest = dict(schema=1, kind='synthetic_policy', label_strength='weak', policy_version=POLICY_VERSION,
                    policy_description=POLICY_DESCRIPTION, seed=seed, variants_per_family=variants_per_family,
                    catalog_sha256=_sha(Path(catalog_path).read_bytes()), splits={},
                    family_splits={family.name: family.split for family in FAMILIES},
                    limitation='Synthetic weak-policy agreement is not real-player success or game win rate.')
    for split in SPLITS:
        members = [row for row in rows if row['split'] == split]
        payload = ''.join(canonical_json(row) + '\n' for row in members).encode('utf-8')
        (out / (split + '.jsonl')).write_bytes(payload)
        by_length = defaultdict(Counter)
        for row in members:
            by_length[str(len(row['context']['options']))][str(row['target']['option'])] += 1
        manifest['splits'][split] = dict(file=split + '.jsonl', sha256=_sha(payload), count=len(members),
                                        families=sorted({r['family'] for r in members}),
                                        target_indices=dict(sorted(Counter(str(r['target']['option']) for r in members).items())),
                                        target_indices_by_option_count={k: dict(sorted(v.items())) for k, v in sorted(by_length.items())},
                                        option_event_coverage=sorted({o['event'] for r in members for o in r['context']['options']}),
                                        target_event_coverage=sorted({r['context']['options'][r['target']['option']]['event'] for r in members}),
                                        context_set_sha256=_digest(sorted(r['provenance']['context_sha256'] for r in members)))
    (out / 'manifest.json').write_bytes((canonical_json(manifest) + '\n').encode('utf-8'))
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalog', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--seed', type=int, default=42)
    parser.add_argument('--variants-per-family', type=int, default=12)
    args = parser.parse_args(argv)
    manifest = write_dataset(args.catalog, args.out, args.seed, args.variants_per_family)
    print(canonical_json(dict(out=str(args.out), policy=POLICY_VERSION,
                              counts={split: details['count'] for split, details in manifest['splits'].items()})))


if __name__ == '__main__':
    main()
