"""One bounded proposal/critique/revision; no raw conversation is retained."""
from dataclasses import dataclass
import json
import math
import time
from .bridge import _json, BridgeError
from .model import ModelSettings, ModelError
from .tactics import parse_selection, selection_schema

REASONS = ('recent_failure', 'repetition', 'pressure', 'season', 'better_alternative')
ERRORS = {'model_timeout', 'model_unavailable', 'model_revision_mismatch', 'model_invalid_response',
          'model_invalid_context', 'council_deadline'}

class CouncilError(ModelError):
    def __init__(self, code, metrics):
        super().__init__(code)
        self.metrics = metrics


@dataclass(frozen=True)
class CouncilSettings(ModelSettings):
    ai_mode: str = 'single'
    critic: ModelSettings | None = None
    budget_seconds: float = 90
    margin_seconds: float = 5

    def __post_init__(self):
        super().__post_init__()
        if self.ai_mode not in ('single', 'dual'):
            raise ModelError('council_invalid_settings')
        for value, low, high in ((self.budget_seconds, 1, 90), (self.margin_seconds, 1, 30)):
            if type(value) not in (int, float) or not math.isfinite(value) or not low <= value <= high:
                raise ModelError('council_invalid_settings')
        if self.critic is not None and type(self.critic) is not ModelSettings:
            raise ModelError('council_invalid_settings')
        if self.ai_mode == 'dual' and (self.critic is None or self.critic.url != self.url
                or self.critic.model == self.model or self.critic.digest == self.digest
                or max(self.context, self.critic.context) > 4096 or max(self.threads, self.critic.threads) > 4):
            raise ModelError('council_invalid_settings')


def critique_schema(count):
    schema = selection_schema(count)
    schema['required'] = ['option', 'reason_codes']
    schema['properties']['reason_codes'] = dict(type='array', maxItems=3, uniqueItems=True,
                                               items=dict(type='string', enum=list(REASONS)))
    return schema


def parse_critique(raw, count):
    try:
        if type(raw) is not str or len(raw.encode('utf-8')) > 512:
            raise ValueError()
        value = _json(raw)
        if type(value) is not dict or set(value) != {'option', 'reason_codes'}:
            raise ValueError()
        parse_selection(json.dumps(dict(option=value['option'])), count)
        codes = value['reason_codes']
        if type(codes) is not list or len(codes) > 3 or any(type(code) is not str or code not in REASONS for code in codes) or len(set(codes)) != len(codes):
            raise ValueError()
        return value
    except (KeyError, ValueError, TypeError, UnicodeError, RecursionError, BridgeError):
        raise ModelError('model_invalid_response') from None


class CouncilModel:
    def __init__(self, primary, critic, settings, *, clock=None,continue_dual=None):
        if settings.ai_mode != 'dual' or primary.settings.url != critic.settings.url:
            raise ModelError('council_invalid_settings')
        self.primary, self.critic, self.settings = primary, critic, settings
        self.clock = clock or time.monotonic
        self.continue_dual=continue_dual or (lambda:True)

    def select(self, context, *, deadline=None):
        start = self.clock()
        if deadline is not None and (type(deadline) not in (int, float) or not math.isfinite(deadline)):
            raise ModelError('model_invalid_settings')
        end = min(start + self.settings.budget_seconds, deadline if deadline is not None else math.inf)
        options = context.get('options') if type(context) is dict else None
        if type(options) is not list or not 1 <= len(options) <= 8:
            raise ModelError('model_invalid_context')
        count, stages, revisions = len(options), [], 0

        def stage(name, model, call, parse):
            before = self.clock()
            item = dict(stage=name, model=model.settings.model, digest=model.settings.digest)
            try:
                if before >= end: raise ModelError('council_deadline')
                value, stats = call()
                value = parse(json.dumps(value, ensure_ascii=False, allow_nan=False), count)
                if self.clock() > end: raise ModelError('model_timeout')
                item.update(status='valid', option=value['option'], reason_codes=value.get('reason_codes', []))
                if type(stats) is dict:
                    for key in ('prompt_tokens', 'output_tokens'):
                        if type(stats.get(key)) is int and 0 <= stats[key] <= 1000000: item[key] = stats[key]
                return value
            except (ModelError, ValueError, TypeError) as error:
                code = str(error) if isinstance(error, ModelError) and str(error) in ERRORS else 'model_invalid_response' if not isinstance(error, ModelError) else 'model_error'
                item.update(status='error', error_code=code)
                raise ModelError(code) from None
            finally:
                item.update(elapsed_ms=max(0, round((self.clock() - before) * 1000)),
                            remaining_ms=max(0, round((end - self.clock()) * 1000)))
                stages.append(item)

        def skipped(name, model,reason='council_deadline'):
            stages.append(dict(stage=name, model=model.settings.model, digest=model.settings.digest,
                               status='skipped', error_code=reason, elapsed_ms=0,
                               remaining_ms=max(0, round((end - self.clock()) * 1000))))

        def summary(outcome, proposal=None, final=None):
            return dict(ai_mode='dual', elapsed_ms=max(0, round((self.clock() - start) * 1000)),
                        remaining_ms=max(0, round((end - self.clock()) * 1000)),
                        proposal_option=proposal['option'] if proposal else None,
                        final_option=final['option'] if final else None,
                        outcome=outcome, revision_count=revisions, stages=stages)

        try:
            proposal = stage('proposal', self.primary, lambda: self.primary.select(context, deadline=end), parse_selection)
        except ModelError as error:
            raise CouncilError(str(error), summary('primary_failed')) from None
        final, outcome = proposal, 'critic_agreed'
        if not self.continue_dual():
            skipped('critique',self.critic,'council_mode_changed');outcome='mode_changed'
        elif self.clock() >= end:
            skipped('critique', self.critic); outcome = 'proposal_deadline'
        else:
            try:
                critique = stage('critique', self.critic, lambda: self.critic.critique(context, proposal, deadline=end), parse_critique)
            except ModelError:
                outcome = 'critic_failed'
            else:
                if not self.continue_dual():
                    skipped('revision',self.primary,'council_mode_changed');outcome='mode_changed'
                elif critique['option'] != proposal['option']:
                    if self.clock() >= end:
                        skipped('revision', self.primary); outcome = 'revision_deadline'
                    else:
                        revisions = 1
                        try:
                            final = stage('revision', self.primary, lambda: self.primary.revise(context, proposal, critique, deadline=end), parse_selection)
                            outcome = 'revised'
                        except ModelError:
                            outcome = 'revision_failed'
        if not self.continue_dual():final,outcome=proposal,'mode_changed'
        return final, summary(outcome, proposal, final)
