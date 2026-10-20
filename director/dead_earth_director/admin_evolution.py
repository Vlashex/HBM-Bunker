"""Human operator evolution controls with fixed commands and durable console receipts."""
import math
from pathlib import Path
import re

from .admin_console import RconFailure, rcon_command, settings
from .bridge import BridgeError, _json, _uuid
from .parasites import capabilities

FIELDS=('status enabled campaignId worldEpoch revision configRevision phase name progress points '
        'phaseStartPoints nextPhasePoints rate paused migration unlockedEntities abilities history').split()
LONG_MAX=9223372036854775807


class EvolutionError(ValueError):
    def __init__(self,code):super().__init__(code);self.code=code


def require(condition,code='unsupported'):
    if not condition:raise EvolutionError(code)


def canonical_uuid(value):
    return _uuid(value) and value==value.lower()


def number(value,low=0,high=1e300):
    return type(value) in (int,float) and low<=value<=high and math.isfinite(value)


def native_json(raw):
    require(type(raw) is str)
    raw=re.sub(r'\u00a7[0-9A-FK-ORa-fk-or]','',raw).strip()
    if raw.startswith('[DeadEarth] '):raw=raw[len('[DeadEarth] '):]
    try:value=_json(raw)
    except (BridgeError,ValueError,TypeError,RecursionError):raise EvolutionError('unsupported') from None
    require(type(value) is dict)
    return value


def evolution_dto(value):
    require(type(value) is dict)
    # Default Gson omits nullable fields in the native command response.
    value=dict(value);value.setdefault('nextPhasePoints',None)
    require(all(key in value for key in FIELDS))
    try:parasite_system,phase_maximum=capabilities(value,'phaseMaximum')
    except ValueError:raise EvolutionError('unsupported') from None
    require(value['status'] in ('ready','paused','disabled','unsupported_save','configuration_error','unavailable'))
    require(type(value['enabled']) is bool and type(value['paused']) is bool)
    require(canonical_uuid(value['campaignId']) and canonical_uuid(value['worldEpoch']))
    require(type(value['revision']) is int and 0<=value['revision']<=LONG_MAX)
    require(type(value['configRevision']) is str and re.fullmatch(r'[0-9a-f]{64}',value['configRevision']))
    require(type(value['phase']) is int and 0<=value['phase']<=phase_maximum)
    require(type(value['name']) is str and len(value['name'])<=256)
    require(number(value['progress'],0,1))
    require(all(number(value[key]) for key in ('points','phaseStartPoints','rate')))
    require(value['nextPhasePoints'] is None or number(value['nextPhasePoints']))
    require(type(value['migration']) is str and len(value['migration'])<=128)
    for key in ('unlockedEntities','abilities'):
        require(type(value[key]) is list and len(value[key])<=512 and all(type(item) is str and len(item)<=256 for item in value[key]))
    require(type(value['history']) is list and len(value['history'])<=128)
    history=[]
    for row in value['history']:
        require(type(row) is dict and all(key in row for key in ('atTick','from','to','source')))
        row=dict(row);row.setdefault('requestId',None)
        require(type(row['atTick']) is int and 0<=row['atTick']<=LONG_MAX)
        require(all(type(row[key]) is int and 0<=row[key]<=phase_maximum for key in ('from','to')))
        require(type(row['source']) is str and len(row['source'])<=128)
        require(row['requestId'] is None or canonical_uuid(row['requestId']))
        history.append({key:row[key] for key in ('atTick','from','to','source','requestId')})
    result={key:value[key] for key in FIELDS};result['history']=history
    if parasite_system=='srp':result.update(parasiteSystem='srp',phaseMaximum=phase_maximum)
    elif 'phaseMaximum' in value:result['phaseMaximum']=phase_maximum
    return result


class Evolution:
    def __init__(self,root,console,reader=None):
        self.root=Path(root);self.console=console;self.reader=reader or self.read_command

    def read_command(self,command):
        state,port,password=settings(self.root)
        if state!='configured':raise EvolutionError('offline')
        return rcon_command(port,password,command)

    def snapshot(self):
        try:
            value=evolution_dto(native_json(self.reader('deadearth evolution status')))
            return dict(state='ready',server=value)
        except EvolutionError as error:return dict(state=error.code,server=None)
        except (RconFailure,OSError,ValueError):return dict(state='offline',server=None)

    def shift(self,payload):
        require(type(payload) is dict and set(payload)=={'request_id','campaign_id','world_epoch','revision','delta'},'invalid_evolution_request')
        require(all(canonical_uuid(payload[key]) for key in ('request_id','campaign_id','world_epoch')),'invalid_evolution_request')
        require(type(payload['revision']) is int and 0<=payload['revision']<=LONG_MAX,'invalid_evolution_request')
        require(type(payload['delta']) is int and payload['delta'] in (-1,1),'invalid_evolution_request')
        command='deadearth evolution shift {} {} {} {} {}'.format(payload['campaign_id'],payload['world_epoch'],payload['revision'],payload['request_id'],payload['delta'])
        # OperatorConsole commits its UUID receipt before dispatch and returns it
        # before any fresh server state check, including after an admin restart.
        try:receipt=self.console.execute(payload['request_id'],command)
        except ValueError as error:
            if str(error)=='conflicting_request':raise EvolutionError('conflicting_request') from None
            raise
        result=dict(state=receipt.get('state','uncertain'),request_id=payload['request_id'])
        if result['state']=='completed':
            try:
                require(not receipt.get('truncated'))
                value=native_json(receipt.get('output'))
                require(type(value.get('accepted')) is bool and type(value.get('code')) is str
                        and re.fullmatch(r'[a-z][a-z0-9_]{0,63}',value['code']))
                result['result']=dict(accepted=value['accepted'],code=value['code'],evolution=evolution_dto(value.get('evolution')))
            except EvolutionError:result.update(state='uncertain',code='unsupported_response')
        return result
