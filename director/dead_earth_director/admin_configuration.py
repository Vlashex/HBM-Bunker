"""Bounded human configuration control. No paths or commands come from HTTP."""
from contextlib import contextmanager
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sqlite3
import threading
import time

from .admin_console import RconFailure, rcon_command, settings
from .admin_evolution import canonical_uuid, native_json

MAX_BODY = 262144
ID = re.compile(r'[A-Za-z][A-Za-z0-9_-]{0,63}\Z')
KEY = re.compile(r'[A-Za-z][A-Za-z0-9_.]{0,95}\Z')
HASH = re.compile(r'[0-9a-f]{64}\Z')
CODE = re.compile(r'[a-z][a-z0-9_]{0,63}\Z')


class ConfigurationError(ValueError):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def require(condition, code='unsupported'):
    if not condition:
        raise ConfigurationError(code)


def ident(value):
    return type(value) is str and ID.fullmatch(value) is not None


def revision(value):
    return type(value) is str and HASH.fullmatch(value) is not None


def number(value):
    return type(value) in (int, float) and -1e100 <= value <= 1e100 and math.isfinite(value)


def field_key(value):
    return type(value) is str and KEY.fullmatch(value) is not None


def tree(value, depth=0):
    require(depth <= 16, 'invalid_configuration_request')
    if type(value) is dict:
        require(len(value) <= 1024 and all(type(k) is str and len(k) <= 256 for k in value), 'invalid_configuration_request')
        for item in value.values():
            tree(item, depth + 1)
    elif type(value) is list:
        require(len(value) <= 2048, 'invalid_configuration_request')
        for item in value:
            tree(item, depth + 1)
    else:
        require(value is None or type(value) is bool or number(value)
                or type(value) is str and len(value) <= 65536, 'invalid_configuration_request')


def encode(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(',', ':')).encode('utf-8')


def configuration_dto(value):
    require(type(value) is dict and type(value.get('schema')) is int and value['schema'] == 1
            and value.get('status') == 'ready' and revision(value.get('revision'))
            and canonical_uuid(value.get('worldEpoch')) and canonical_uuid(value.get('campaignId')))
    sections = value.get('sections')
    require(type(sections) is list and len(sections) <= 32)
    result = []; section_ids = set(); total = 0
    for section in sections:
        require(type(section) is dict and ident(section.get('id')) and section['id'] not in section_ids)
        section_ids.add(section['id'])
        require(type(section.get('label')) is str and len(section['label']) <= 256)
        fields = section.get('fields'); require(type(fields) is list)
        total += len(fields); require(total <= 1024)
        rows = []; keys = set()
        for field in fields:
            require(type(field) is dict and field_key(field.get('key')) and field['key'] not in keys)
            keys.add(field['key'])
            require(field.get('type') in ('boolean', 'integer', 'number', 'string', 'json')
                    and field.get('policy') in ('hot', 'restart') and 'value' in field)
            require(all(type(field.get(k)) is str and len(field[k]) <= limit for k, limit in (('label', 256), ('help', 2048))))
            kind, v = field['type'], field['value']
            require((kind == 'boolean' and type(v) is bool) or (kind == 'integer' and type(v) is int and -2**63 <= v < 2**63)
                    or (kind == 'number' and number(v)) or (kind == 'string' and type(v) is str and len(v) <= 65536) or kind == 'json')
            tree(v)
            row = {k: field[k] for k in ('key', 'label', 'type', 'value', 'policy', 'help')}
            for key in ('min', 'max', 'step'):
                if key in field:
                    require(number(field[key]) and (key != 'step' or field[key] > 0)); row[key] = field[key]
            if 'min' in row and 'max' in row:
                require(row['min'] <= row['max'])
            if 'choices' in field:
                require(type(field['choices']) is list and len(field['choices']) <= 256)
                tree(field['choices']); row['choices'] = field['choices']
            rows.append(row)
        result.append(dict(id=section['id'], label=section['label'], fields=rows))
    return dict(revision=value['revision'], worldEpoch=value['worldEpoch'], campaignId=value['campaignId'], sections=result)


def result_dto(value, request_id):
    require(type(value) is dict and type(value.get('schema')) is int and value['schema'] == 1
            and type(value.get('accepted')) is bool and value.get('requestId') == request_id
            and value.get('state') in ('validated', 'applied', 'restart_pending', 'rejected')
            and revision(value.get('revision')))
    require(value['accepted'] == (value['state'] != 'rejected'))
    errors = value.get('errors'); require(type(errors) is list and len(errors) <= 256)
    rows = []
    for error in errors:
        require(type(error) is dict and all(type(error.get(k)) is str and len(error[k]) <= maximum
                for k, maximum in (('section', 64), ('key', 96), ('code', 64), ('message', 512))))
        require(CODE.fullmatch(error['code']) is not None)
        rows.append({k: error[k] for k in ('section', 'key', 'code', 'message')})
    return dict(schema=1, accepted=value['accepted'], state=value['state'], requestId=request_id,
                revision=value['revision'], errors=rows)


class Configuration:
    def __init__(self, root, console, reader=None):
        self.root = Path(root).resolve(); self.console = console
        self.reader = reader or self.read_command; self.lock = threading.Lock()
        self.database = self.root / 'director/runtime/configuration.sqlite3'
        self.safe_path(self.database); self.database.parent.mkdir(parents=True, exist_ok=True)
        with self.db() as db:
            db.execute('CREATE TABLE IF NOT EXISTS requests(id TEXT PRIMARY KEY,digest TEXT NOT NULL,result TEXT NOT NULL,at REAL NOT NULL)')

    def safe_path(self, path):
        require(path != self.root and path.is_relative_to(self.root), 'unsafe_path')
        for parent in (path, *path.parents):
            if parent == self.root:
                break
            require(not parent.is_symlink() and not (hasattr(parent, 'is_junction') and parent.is_junction()), 'unsafe_path')
        require(path.resolve().is_relative_to(self.root), 'unsafe_path')

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.database, timeout=2)
        try:
            with db:
                yield db
        finally:
            db.close()

    def read_command(self, command):
        state, port, password = settings(self.root)
        if state != 'configured':
            raise ConfigurationError('offline')
        return rcon_command(port, password, command)

    def snapshot(self):
        try:
            native = native_json(self.reader('deadearth config view'))
            if type(native.get('schema')) is int and native['schema'] == 1 and native.get('status') in ('unknown', 'stale', 'offline'):
                return dict(schema=1, state=native['status'], server=None)
            value = configuration_dto(native)
            return dict(schema=1, state='ready', server=value)
        except (RconFailure, OSError):
            return dict(schema=1, state='offline', server=None)
        except ConfigurationError as error:
            return dict(schema=1, state='offline' if error.code == 'offline' else 'unsupported', server=None)
        except ValueError:
            return dict(schema=1, state='unsupported', server=None)

    def validate_payload(self, p):
        require(type(p) is dict and set(p) == {'request_id', 'world_epoch', 'revision', 'mode', 'changes'}, 'invalid_configuration_request')
        require(canonical_uuid(p['request_id']) and canonical_uuid(p['world_epoch']) and revision(p['revision'])
                and p['mode'] in ('hot', 'restart'), 'invalid_configuration_request')
        changes = p['changes']
        require(type(changes) is dict and 1 <= len(changes) <= 32, 'invalid_configuration_request')
        count = 0
        for section, fields in changes.items():
            require(ident(section) and type(fields) is dict and len(fields) > 0 and all(field_key(k) for k in fields), 'invalid_configuration_request')
            count += len(fields); require(count <= 1024, 'invalid_configuration_request'); tree(fields)
        require(len(encode(p)) <= MAX_BODY, 'invalid_configuration_request')

    def record(self, request_id, result):
        with self.db() as db:
            db.execute('UPDATE requests SET result=? WHERE id=?', (encode(result).decode(), request_id))
        return result

    def apply(self, payload):
        self.validate_payload(payload)
        ident_ = payload['request_id']; digest = hashlib.sha256(encode(payload)).hexdigest()
        require(self.lock.acquire(blocking=False), 'busy')
        try:
            with self.db() as db:
                db.execute('BEGIN IMMEDIATE')
                prior = db.execute('SELECT digest,result FROM requests WHERE id=?', (ident_,)).fetchone()
                if prior:
                    require(prior[0] == digest, 'conflicting_request')
                    saved = json.loads(prior[1])
                    require(saved.get('error') != 'conflicting_request', 'conflicting_request')
                    return saved
                require(db.execute('SELECT COUNT(*) FROM requests').fetchone()[0] < 5000, 'journal_full')
                result = dict(schema=1, state='uncertain', request_id=ident_)
                db.execute('INSERT INTO requests VALUES(?,?,?,?)', (ident_, digest, encode(result).decode(), time.time()))
            dispatched = False
            preparation = 'staging_unavailable'
            try:
                path = self.root / 'config/dead-earth/config-staging' / (ident_ + '.json')
                self.safe_path(path); path.parent.mkdir(parents=True, exist_ok=True); self.safe_path(path)
                stage = dict(schema=1, requestId=ident_, worldEpoch=payload['world_epoch'], baseRevision=payload['revision'],
                             mode=payload['mode'], changes=payload['changes'])
                with path.open('xb') as output:
                    output.write(encode(stage)); output.flush(); os.fsync(output.fileno())
                suffix = '{} {} {}'.format(payload['world_epoch'], payload['revision'], ident_)
                preparation = 'validation_unavailable'
                validation = result_dto(native_json(self.reader('deadearth config validate ' + suffix)), ident_)
                if not validation['accepted']:
                    result.update(state='completed', result=validation)
                else:
                    require(validation['state'] == 'validated')
                    dispatched = True
                    receipt = self.console.execute(ident_, 'deadearth config apply ' + suffix)
                    result = self.console_result(ident_, receipt, payload['revision'])
            except ValueError as error:
                if str(error) == 'conflicting_request':
                    self.record(ident_, dict(schema=1,state='rejected',request_id=ident_,error='conflicting_request'))
                    raise ConfigurationError('conflicting_request') from None
                result = (dict(schema=1, state='uncertain', request_id=ident_) if dispatched
                          else self.rejection(ident_, payload['revision'], preparation))
            except (OSError, RconFailure):
                result = (dict(schema=1, state='uncertain', request_id=ident_) if dispatched
                          else self.rejection(ident_, payload['revision'], preparation))
            return self.record(ident_, result)
        finally:
            self.lock.release()

    def rejection(self, request_id, base_revision, code):
        messages = dict(busy='Консоль занята. Повторите применение позже.',
                        journal_full='Журнал команд заполнен. Настройки не применены.',
                        staging_unavailable='Не удалось подготовить изменения. Настройки не применены.',
                        validation_unavailable='Сервер не подтвердил проверку. Настройки не применены.')
        return dict(schema=1, state='completed', request_id=request_id,
            result=dict(schema=1, accepted=False, state='rejected', requestId=request_id, revision=base_revision,
                        errors=[dict(section='', key='', code=code, message=messages[code])]))

    def console_result(self, request_id, receipt, base_revision):
        result = dict(schema=1, state=receipt.get('state', 'uncertain'), request_id=request_id)
        if result['state'] in ('busy', 'journal_full'):
            # OperatorConsole returns these states before creating a receipt or
            # sending anything. They are definitive rejection, unlike a lost
            # reply after dispatch, and must leave the form editable.
            return self.rejection(request_id, base_revision, result['state'])
        if result['state'] == 'completed':
            try:
                require(not receipt.get('truncated'))
                native = result_dto(native_json(receipt.get('output')), request_id)
                require(native['state'] != 'validated')
                result['result'] = native
            except ValueError:
                result['state'] = 'uncertain'
        return result

    def receipt(self, request_id):
        require(canonical_uuid(request_id), 'invalid_query')
        with self.db() as db:
            prior = db.execute('SELECT result FROM requests WHERE id=?', (request_id,)).fetchone()
        result = json.loads(prior[0]) if prior else dict(schema=1, state='unknown', request_id=request_id)
        if prior and result['state'] in ('uncertain', 'unavailable'):
            # Reading a native receipt is safe after a lost reply. Never re-send apply.
            try:
                native = result_dto(native_json(self.reader('deadearth config receipt ' + request_id)), request_id)
                require(native['state'] != 'validated')
                require(not any(error['code'] == 'unknown_request' for error in native['errors']))
                result = self.record(request_id, dict(schema=1, state='completed', request_id=request_id, result=native))
            except (ValueError, OSError, RconFailure):
                pass
        return result

    def restart(self):
        path = self.root / 'runtime/compose/config-restart-state.json'
        try:
            self.safe_path(path)
            if not path.exists():
                return dict(schema=1, state='idle')
            require(path.stat().st_size <= 4096)
            value = native_json(path.read_text(encoding='utf-8'))
            require(type(value.get('schema')) is int and value['schema'] == 1
                    and value.get('state') in ('queued', 'stopping', 'starting', 'ready', 'failed')
                    and canonical_uuid(value.get('requestId')) and canonical_uuid(value.get('worldEpoch')) and revision(value.get('revision')))
            result = {k: value[k] for k in ('schema', 'state', 'requestId', 'worldEpoch', 'revision')}
            if type(value.get('rollback')) is bool:
                result['rollback'] = value['rollback']
            if type(value.get('code')) is str and CODE.fullmatch(value['code']):
                result['code'] = value['code']
            if result['state'] == 'ready':
                current = self.snapshot()
                if current['state'] != 'ready' or any(current['server'][k] != value[k] for k in ('worldEpoch', 'revision')):
                    result['state'] = 'starting'
            return result
        except (OSError, ValueError):
            return dict(schema=1, state='unknown')
