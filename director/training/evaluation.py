"""Offline agreement with weak synthetic labels, not gameplay win rate.

Predictions are {row_id: {"option": int}} or {row_id: raw_json_string}.
Missing, malformed and out-of-range predictions count against agreement.
"""

import argparse
from collections import defaultdict
import json
from pathlib import Path

from dead_earth_director.tactics import parse_selection

from .dataset import canonical_json, load_rows, strict_json, validate_rows


def _selection(prediction, count):
    try:
        parsed = strict_json(prediction) if type(prediction) is str else prediction
        # Dict predictions must also be JSON-serializable, including finite values.
        if type(prediction) is not str:
            json.dumps(parsed, allow_nan=False)
    except (ValueError, TypeError, OverflowError, RecursionError, UnicodeError):
        return False, None
    try:
        raw = prediction if type(prediction) is str else canonical_json(parsed)
        return True, parse_selection(raw, count)['option']
    except (ValueError, TypeError, RecursionError, UnicodeError):
        return True, None


def _metrics(items):
    total = len(items)
    valid = sum(item['option'] is not None for item in items)
    agreed = sum(item['agreed'] for item in items)
    syntactic = sum(item['json_valid'] for item in items)
    return dict(total=total, policy_agreement=agreed / total if total else None,
                agreed=agreed, json_valid_rate=syntactic / total if total else None,
                contract_valid_rate=valid / total if total else None, contract_valid=valid)


def score_rows(rows, predictions):
    """Return overall/per-family validity, policy and permutation metrics.

    Agreement denominator includes missing and invalid predictions. Permutation
    consistency also requires *every* member to be valid; singleton groups are
    excluded and reported separately. Stable wrong answers can be consistent,
    hence this metric is separate from policy agreement.
    """
    rows = list(rows)
    validate_rows(rows)
    if type(predictions) is not dict:
        raise ValueError('predictions must map row ids to objects or raw JSON')
    items, families, groups = [], defaultdict(list), defaultdict(list)
    repeat_total = repeat_selected = 0
    for row in rows:
        if row['id'] not in predictions:
            json_valid, option = False, None
        else:
            json_valid, option = _selection(predictions[row['id']], len(row['context']['options']))
        item = dict(json_valid=json_valid, option=option,
                    agreed=option is not None and option in row['acceptable_options'],
                    event=row['context']['options'][option]['event'] if option is not None else None)
        items.append(item)
        families[row['family']].append(item)
        group = row['provenance'].get('permutation_group')
        if group:
            groups[group].append(item)
        repelled = {h['event'] for h in row['context']['recent'] if h['outcome'] == 'repelled'}
        # Count only cases where repeating is avoidable within allowed options.
        if repelled and any(o['event'] in repelled for o in row['context']['options']) and any(o['event'] not in repelled for o in row['context']['options']):
            repeat_total += 1
            repeat_selected += item['event'] in repelled
    compared = [members for members in groups.values() if len(members) >= 2]
    consistent = sum(all(item['option'] is not None for item in members) and len({item['event'] for item in members}) == 1 for members in compared)
    report = _metrics(items)
    report.update(kind='synthetic_policy_evaluation',
                  limitation='Policy agreement with weak synthetic labels; not measured real-player success.',
                  per_family={family: _metrics(members) for family, members in sorted(families.items())},
                  permutation=dict(groups=len(compared), consistent_groups=consistent,
                                   singleton_groups=sum(len(members) == 1 for members in groups.values()),
                                   same_event_rate=consistent / len(compared) if compared else None),
                  repeat_after_repelled=dict(eligible_rows=repeat_total, selected_rows=repeat_selected,
                                             rate=repeat_selected / repeat_total if repeat_total else None),
                  missing_predictions=sum(row['id'] not in predictions for row in rows),
                  extra_predictions=len(set(predictions) - {row['id'] for row in rows}))
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', required=True, type=Path)
    parser.add_argument('--predictions', required=True, type=Path, help='JSON object keyed by row id')
    parser.add_argument('--out', type=Path)
    args = parser.parse_args(argv)
    predictions = strict_json(args.predictions.read_text(encoding='utf-8'))
    report = score_rows(load_rows(args.data), predictions)
    raw = canonical_json(report) + '\n'
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_bytes(raw.encode('utf-8'))
    print(raw, end='')


if __name__ == '__main__':
    main()
