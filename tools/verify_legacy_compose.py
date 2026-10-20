"""Bounded Docker Desktop E2E: admin/console, real mode acknowledgements and CPU inference.

Never starts/stops containers, resets worlds, admits a scenario, or exposes bridge secrets.
The only operator commands are list/deadearth status. Mode changes finish in single.
"""
import argparse
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid
from urllib.error import HTTPError
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler

ROOT = Path(__file__).resolve().parents[1]
SERVICES = {'minecraft', 'ollama', 'director', 'admin', 'playit'}
ORIGIN = 'http://127.0.0.1:8766'
GET_PATHS = {'/api/health', '/api/dashboard', '/api/console', '/api/ai-mode'}


class CheckFailure(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


# Executed only in the director container, on the private CPU backend. This
# program never constructs/calls a bridge client, host loop, command transport or journal.
INFERENCE_CHILD = r"""
from dataclasses import replace
import json, math, re, sqlite3, sys, time
from pathlib import Path
from dead_earth_director.host import load_config, build_model
from dead_earth_director.model import OllamaModel
from dead_earth_director.tactics import parse_selection

root=Path('/srv/dead-earth')
payload=json.load(sys.stdin)
settings,_=load_config(root/'director/director.example.toml')
if settings.url!='http://ollama:11434' or settings.critic is None or settings.critic.url!=settings.url:
    raise SystemExit('private_cpu_backend_required')
if max(settings.threads,settings.critic.threads)>4 or max(settings.context,settings.critic.context)>4096:
    raise SystemExit('bounded_cpu_settings_required')

def matches(mode,revision):
    database=(root/'director/runtime/ai-control.sqlite3').resolve().as_uri()+'?mode=ro'
    with sqlite3.connect(database,uri=True,timeout=2) as db:
        row=db.execute('SELECT mode,revision FROM desired WHERE id=1').fetchone()
    return row is not None and tuple(row)==(mode,revision)

def residents():
    result=OllamaModel(settings.critic)._request('/api/ps',deadline=time.monotonic()+5)
    if type(result) is not dict or type(result.get('models')) is not list:
        raise ValueError('invalid_resident_reply')
    rows=[]
    for row in result['models']:
        if row.get('digest')==settings.critic.digest:
            if row.get('size_vram') not in (None,0):raise ValueError('critic_gpu_resident')
            rows.append(dict(name=row.get('name',row.get('model')),digest=row['digest'],size_vram=row.get('size_vram')))
    return rows

try:
    action=payload['action']
    if action=='residents':
        result=dict(passed=True,action=action,critic_residents=residents())
    elif action=='warm_critic':
        if not matches('dual',payload['revision']):raise ValueError('mode_changed')
        model=OllamaModel(settings.critic)
        tags=model._request('/api/tags',deadline=time.monotonic()+5)
        if not any(row.get('name')==settings.critic.model and row.get('digest')==settings.critic.digest for row in tags.get('models',[])):
            raise ValueError('critic_revision_mismatch')
        # Empty request loads only the pinned critic; it does not generate text.
        reply=model._request('/api/generate',dict(model=settings.critic.model,stream=False,keep_alive=60,
            options=dict(num_gpu=0,num_thread=settings.critic.threads,num_ctx=settings.critic.context)),deadline=time.monotonic()+30)
        if reply.get('done') is not True:raise ValueError('critic_load_failed')
        loaded=residents()
        if not loaded:raise ValueError('critic_not_resident_after_load')
        result=dict(passed=True,action=action,load_only=True,num_gpu=0,critic_residents=loaded)
    elif action=='inference':
        mode=payload['mode'];revision=payload['revision']
        if mode not in ('single','dual') or not matches(mode,revision):raise ValueError('mode_changed')
        source=payload['context']
        catalog=json.loads((root/'config/dead-earth/director/catalog.json').read_text(encoding='utf-8'))
        events={row['id']:row for row in catalog['events']}
        options=[]
        for row in source['options']:
            if row['event'] not in events:continue
            event=events[row['event']]
            options.append(dict(option=len(options),event=event['id'],title=event['title'],
                tactic=event['tactic'],faction=event['faction'],weight=row['weight']))
        if not 1<=len(options)<=8:raise ValueError('frozen_options_not_in_current_catalog')
        context=dict(season=source['season'],pressure=source['pressure'],options=options,recent=[],
            parasite_system='srp',parasite_phase_maximum=10,parasite_phase=source['parasite_phase'])
        effective=replace(settings,ai_mode=mode)
        model=build_model(effective,'live',continue_dual=lambda:matches(mode,revision))
        choice,metrics=model.select(context)
        choice=parse_selection(json.dumps(choice),len(options))
        if mode=='dual':
            stages=metrics.get('stages',[])
            if not any(row.get('stage')=='proposal' and row.get('status')=='valid' for row in stages):raise ValueError('primary_stage_not_valid')
            if not any(row.get('stage')=='critique' and row.get('status')=='valid' for row in stages):raise ValueError('critic_stage_not_valid')
        if not matches(mode,revision):raise ValueError('mode_changed')
        result=dict(passed=True,action=action,mode=mode,context=context,choice=choice,
            selected_event=options[choice['option']]['event'],metrics=metrics,
            primary=dict(model=settings.model,digest=settings.digest),
            critic=dict(model=settings.critic.model,digest=settings.critic.digest) if mode=='dual' else None,
            cpu_request_options=dict(num_gpu=0,num_thread=settings.threads,num_ctx=settings.context,keep_alive=0),
            frozen_context=True,live_world_admission=False,manual_review_required=True)
    else:raise ValueError('unknown_probe_action')
except Exception as error:
    code=str(error)
    result=dict(passed=False,error=code if re.fullmatch('[a-z_]{1,64}',code) else type(error).__name__)
print(json.dumps(result,ensure_ascii=False,allow_nan=False))
sys.exit(0 if result['passed'] else 1)
"""


def docker_binary():
    found = shutil.which('docker')
    candidates = [found] if found else []
    candidates += [str(Path(os.environ.get('ProgramFiles', 'C:/Program Files')) / 'Docker/Docker/resources/bin/docker.exe'),
                   str(Path(os.environ.get('LOCALAPPDATA', 'C:/Users/Default/AppData/Local')) / 'Programs/DockerDesktop/resources/bin/docker.exe')]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return str(Path(candidate).resolve())
    raise CheckFailure('docker_cli_missing')


class Compose:
    def __init__(self, directory):
        self.directory = Path(directory).resolve(strict=True)
        if not (self.directory / '.env').is_file() or not (self.directory / 'compose.yaml').is_file():
            raise CheckFailure('compose_not_prepared')
        document = json.loads((self.directory / 'compose.yaml').read_text(encoding='utf-8'))
        if document.get('name') != 'dead-earth-srp' or set(document.get('services', {})) != SERVICES:
            raise CheckFailure('unexpected_compose_project')
        self.health_services = {name for name, value in document['services'].items() if value.get('healthcheck')}
        if not {'minecraft', 'ollama', 'director', 'admin'} <= self.health_services:
            raise CheckFailure('four_real_service_healthchecks_required')
        self.base = [docker_binary(), '--context', 'desktop-linux', 'compose', '--project-name', 'dead-earth-srp',
                     '--env-file', str(self.directory / '.env'), '-f', str(self.directory / 'compose.yaml')]
        self.environment = os.environ.copy()
        self.environment.pop('DOCKER_HOST', None)
        self.environment.pop('DOCKER_CONTEXT', None)

    def call(self, arguments, payload=None, timeout=25, allow_failed_json=False):
        # Caller supplies only fixed ps or exec director python probe actions.
        try:
            result = subprocess.run(self.base + arguments, cwd=self.directory, env=self.environment,
                                    input=None if payload is None else json.dumps(payload, ensure_ascii=False),
                                    text=True, encoding='utf-8', errors='strict', capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            raise CheckFailure('docker_probe_timeout') from None
        # Never print compose stderr: it can contain interpolated environment values.
        if result.returncode and not allow_failed_json:
            raise CheckFailure('docker_probe_failed')
        return result.stdout

    def status(self):
        source = self.call(['ps', '--format', 'json'])
        try:
            value = json.loads(source)
            rows = value if isinstance(value, list) else [value]
        except ValueError:
            rows = [json.loads(line) for line in source.splitlines() if line.strip()]
        if {row.get('Service') for row in rows} != SERVICES or len(rows) != 5:
            raise CheckFailure('five_owned_services_required')
        summary = []
        for row in rows:
            service = row['Service']
            if row.get('State') != 'running':
                raise CheckFailure('compose_service_not_running_' + service)
            health = row.get('Health') or None
            if service in self.health_services and health != 'healthy':
                raise CheckFailure('compose_service_not_healthy_' + service)
            for mapping in row.get('Publishers') or []:
                # Compose ps also includes Dockerfile EXPOSE as PublishedPort=0; it has no
                # host mapping. A private target with any real/unknown published port is refused.
                if (mapping.get('TargetPort') in (11434, 25575)
                        and not (type(mapping.get('PublishedPort')) is int
                                 and mapping['PublishedPort'] == 0)):
                    raise CheckFailure('private_model_or_rcon_port_published')
            summary.append(dict(service=service, state='running', health=health or 'not_configured'))
        return dict(services=summary, running=5,
                    healthy=sum(row['health'] == 'healthy' for row in summary),
                    playit_tunnel_connectivity_tested=False, private_backend_and_rcon=True)

    def model_probe(self, payload):
        text = self.call(['exec', '-T', 'director', 'python3', '-c', INFERENCE_CHILD], payload, timeout=115, allow_failed_json=True)
        try:
            result = json.loads(text)
        except ValueError:
            raise CheckFailure('invalid_private_probe_output') from None
        if result.get('passed') is not True:
            code=result.get('error','private_model_probe_not_passed')
            if type(code) is not str or not re.fullmatch(r'[A-Za-z_]{1,64}',code):
                code='private_model_probe_not_passed'
            raise CheckFailure('private_probe_' + code)
        return result


class Admin:
    def __init__(self):
        self.opener = build_opener(ProxyHandler({}), NoRedirect())

    def request(self, path, payload=None, nonce=None, console=False):
        if payload is None and path not in GET_PATHS:
            raise CheckFailure('unexpected_admin_get_route')
        if payload is not None and path not in {'/api/ai-mode', '/api/console/command'}:
            raise CheckFailure('unexpected_admin_post_route')
        headers = {'Accept': 'application/json'}
        body = None
        if payload is not None:
            headers.update({'Origin': ORIGIN, 'Content-Type': 'application/json',
                            'X-Dead-Earth-Console' if console else 'X-Dead-Earth-Operator': nonce})
            body = json.dumps(payload, allow_nan=False).encode('utf-8')
        try:
            with self.opener.open(Request(ORIGIN + path, data=body, headers=headers), timeout=20) as response:
                data = response.read(2 * 1024 * 1024 + 1)
                if response.status != 200 or len(data) > 2 * 1024 * 1024:
                    raise CheckFailure('admin_response_invalid')
            return json.loads(data)
        except HTTPError as error:
            raise CheckFailure('admin_http_' + str(error.code)) from None

    def mode(self):
        return self.request('/api/ai-mode')

    def change_mode(self, mode, expected_revision):
        current = self.mode()
        if current.get('revision') != expected_revision:
            raise CheckFailure('concurrent_operator_mode_change')
        reply = self.request('/api/ai-mode', dict(request_id=str(uuid.uuid4()), expected_revision=expected_revision,
                                                mode=mode), current.get('nonce'))
        if reply.get('desired_mode') != mode:
            raise CheckFailure('mode_request_not_saved')
        return reply['revision']

    def wait_mode(self, mode, revision, timeout):
        deadline = time.monotonic() + timeout
        while True:
            current = self.mode()
            if current.get('revision') != revision:
                raise CheckFailure('concurrent_operator_mode_change')
            if (current.get('state') == 'applied' and current.get('desired_mode') == mode
                    and current.get('active_mode') == mode and current.get('host_state') == 'online'
                    and current.get('host_run_mode') == 'live'
                    and (mode != 'single' or current.get('critic_memory') == 'released')):
                return {key: current.get(key) for key in ('desired_mode', 'active_mode', 'host_state',
                        'host_run_mode', 'state', 'critic_memory', 'revision', 'dual_available')}
            if time.monotonic() >= deadline:
                raise CheckFailure('host_mode_ack_timeout_' + mode)
            time.sleep(1)


def safe_context(path):
    source = json.loads(Path(path).read_text(encoding='utf-8'))['context']
    if source.get('parasite_system') != 'srp' or source.get('parasite_phase_maximum') != 10:
        raise CheckFailure('frozen_context_not_srp')
    phase = source.get('parasite_phase')
    if type(phase) is not int or not 0 <= phase <= 10 or source.get('season') not in ('CALM', 'HEAT', 'ASH_WINTER'):
        raise CheckFailure('invalid_frozen_context')
    pressure = source.get('pressure')
    if type(pressure) not in (int, float) or not math.isfinite(pressure) or not 0 <= pressure <= 4:
        raise CheckFailure('invalid_frozen_pressure')
    options = []
    for row in source.get('options', []):
        if not re.fullmatch(r'[a-z][a-z0-9_]{0,63}', row.get('event', '')):
            raise CheckFailure('invalid_frozen_event')
        weight = row.get('weight')
        if type(weight) not in (int, float) or not math.isfinite(weight) or not 0 <= weight <= 1000000:
            raise CheckFailure('invalid_frozen_weight')
        options.append(dict(event=row['event'], weight=weight))
    if not 1 <= len(options) <= 8:
        raise CheckFailure('invalid_frozen_options')
    # Do not forward historical audit records, zones, coordinates, budgets or console data.
    return dict(season=source['season'], pressure=pressure, parasite_phase=phase, options=options)


def progress(stage):
    print(json.dumps(dict(stage=stage), allow_nan=False), flush=True)


def run(args):
    compose, admin = Compose(args.settings_dir), Admin()
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    output = args.report or ROOT / 'work' / ('compose-e2e-' + stamp + '-' + uuid.uuid4().hex[:6]) / 'report.json'
    output = Path(output).absolute()
    if output.exists() or not output.resolve().is_relative_to((ROOT / 'work').resolve()):
        raise CheckFailure('choose_new_report_under_repository_work')
    output.parent.mkdir(parents=True, exist_ok=True)
    report = dict(schema=1, kind='compose_desktop_linux_e2e', passed=False, checks=[],
                  live_world_admission=False, world_mutating_commands=False, models_mocked=False,
                  manual_review_required=not args.skip_inference)
    owned_revision = None
    control_started = False
    def check(value, name):
        report['checks'].append(dict(name=name, passed=bool(value)))
        if not value:
            raise CheckFailure(name)
    try:
        progress('compose_services')
        report['compose_before'] = compose.status()
        check(True, 'five_running_and_all_configured_healthchecks_healthy')
        health = admin.request('/api/health')
        check(health.get('product') == 'dead-earth-admin-v1', 'admin_health')
        deadline = time.monotonic() + args.readiness_timeout
        while True:
            dashboard = admin.request('/api/dashboard')
            if dashboard.get('connection', {}).get('state') == 'online':
                break
            if time.monotonic() >= deadline:
                raise CheckFailure('admin_live_dashboard_timeout')
            time.sleep(1)
        check(dashboard.get('product') == 'dead-earth-admin-v1' and dashboard.get('snapshot', {}).get('parasiteSystem') == 'srp', 'admin_dashboard_live_srp')
        report['admin'] = dict(product=health['product'], connection='online',
                              online_players=dashboard['snapshot'].get('onlinePlayers'),
                              phase=dashboard['snapshot'].get('parasitePhase'))
        console = admin.request('/api/console')
        check(console.get('state') == 'ready', 'human_console_ready')
        command = 'list' if args.console_command == 'list' else 'deadearth status'
        receipt = admin.request('/api/console/command', dict(request_id=str(uuid.uuid4()), command=command),
                                console.get('nonce'), console=True)
        check(receipt.get('state') == 'completed' and type(receipt.get('output')) is str and bool(receipt['output'].strip()), 'human_console_read_only_command_completed')
        # Do not archive raw console history or a server status containing positions.
        report['console'] = dict(command=command, state=receipt['state'], nonempty_output=True,
                                 output_characters=len(receipt['output']))
        current = admin.mode()
        check(current.get('dual_available') is True, 'dual_mode_available')
        owned_revision = current.get('revision')
        control_started = True
        context = safe_context(args.frozen_context)
        progress('real_single_mode')
        owned_revision = admin.change_mode('single', owned_revision)
        report['single_ack'] = admin.wait_mode('single', owned_revision, args.mode_timeout)
        if not args.skip_inference:
            progress('real_single_frozen_inference')
            report['single_inference'] = compose.model_probe(dict(action='inference', mode='single',
                                                                revision=owned_revision, context=context))
            check(report['single_inference']['passed'], 'actual_primary_frozen_selection_valid')
        progress('real_dual_mode')
        owned_revision = admin.change_mode('dual', owned_revision)
        report['dual_ack'] = admin.wait_mode('dual', owned_revision, args.mode_timeout)
        if not args.skip_inference:
            progress('real_dual_frozen_inference')
            report['dual_inference'] = compose.model_probe(dict(action='inference', mode='dual',
                                                              revision=owned_revision, context=context))
            check(report['dual_inference']['passed'], 'actual_primary_and_critic_frozen_selection_valid')
        progress('critic_resident_then_single_release')
        report['critic_before_single'] = compose.model_probe(dict(action='warm_critic', revision=owned_revision))
        owned_revision = admin.change_mode('single', owned_revision)
        report['final_single_ack'] = admin.wait_mode('single', owned_revision, args.mode_timeout)
        report['critic_after_single'] = compose.model_probe(dict(action='residents'))
        check(not report['critic_after_single']['critic_residents'], 'previously_loaded_critic_confirmed_unloaded')
        report['compose_after'] = compose.status()
        check(True, 'five_services_still_running')
        report['passed'] = True
    except Exception as error:
        report['error'] = str(error) if isinstance(error, CheckFailure) else type(error).__name__
    finally:
        if control_started:
            try:
                current = admin.mode()
                if current.get('revision') != owned_revision:
                    report['cleanup'] = 'concurrent_operator_revision_preserved'
                elif current.get('desired_mode') != 'single':
                    owned_revision = admin.change_mode('single', owned_revision)
                    report['cleanup_single_ack'] = admin.wait_mode('single', owned_revision, args.mode_timeout)
                    report['cleanup'] = 'single_restored'
                else:
                    report['cleanup'] = 'single_retained'
            except Exception:
                report['cleanup'] = 'single_restore_not_confirmed'
            if report['cleanup'] not in ('single_restored', 'single_retained'):
                report['passed'] = False
        output.write_text(json.dumps(report, ensure_ascii=False, allow_nan=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(passed=report['passed'], report=str(output), error=report.get('error'),
                          healthy=report.get('compose_after', report.get('compose_before', {})).get('healthy'),
                          real_inference=not args.skip_inference, cleanup=report.get('cleanup')),
                     ensure_ascii=False, allow_nan=False), flush=True)
    return 0 if report['passed'] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--settings-dir', type=Path, default=ROOT / 'legacy/docker')
    parser.add_argument('--report', type=Path)
    parser.add_argument('--frozen-context', type=Path, default=ROOT / 'verification/srp-full-1.0.0/real-ai.json')
    parser.add_argument('--mode-timeout', type=int, default=120)
    parser.add_argument('--readiness-timeout', type=int, default=60)
    parser.add_argument('--console-command', choices=('list', 'status'), default='list')
    parser.add_argument('--skip-inference', action='store_true', help='Narrower smoke: still proves mode acknowledgements and loaded critic release')
    args = parser.parse_args()
    if not 5 <= args.mode_timeout <= 180 or not 5 <= args.readiness_timeout <= 120:
        parser.error('Timeouts outside bounded verification limits')
    try:
        return run(args)
    except Exception as error:
        code = str(error) if isinstance(error, CheckFailure) else type(error).__name__
        print(json.dumps(dict(passed=False, error=code)), flush=True)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
