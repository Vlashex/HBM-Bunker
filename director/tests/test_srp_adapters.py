"""Native SRP capabilities are opt-in; modern protocol bounds remain intact."""
import copy
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from dead_earth_director import admin, host
from dead_earth_director.admin_evolution import EvolutionError, evolution_dto
from dead_earth_director.admin_store import Telemetry
from dead_earth_director.model import ModelSettings, OllamaModel
from dead_earth_director.tactics import selection_prompt
from test_admin import snapshot
from test_admin_evolution import status, payload, CAMPAIGN, EPOCH, REQUEST
from test_cinematic import brief
from test_director import DIGEST


def native_brief(phase=10):
    value=brief()
    value.update(parasiteSystem='srp',parasitePhaseMaximum=10,parasitePhase=phase)
    value['goal']['options'][0]['faction']='siege'
    return value


def native_status(phase=10):
    value=status()
    value.update(parasiteSystem='srp',phaseMaximum=10,phase=phase,
                 unlockedEntities=['srparasites:sim_human'],migration='native_srp')
    value['history']=[dict(atTick=120,**{'from':9,'to':10},source='automatic',requestId=None)]
    return value


class NativeAdapterTests(unittest.TestCase):
    def context(self,value):
        return host.cinematic_context(value,SimpleNamespace(world_epoch=EPOCH))[1]

    def test_srp_phase_range_and_siege_are_projected_without_private_authority(self):
        for phase in range(11):
            with self.subTest(phase=phase):
                context=self.context(native_brief(phase))
                self.assertEqual(context['parasite_phase'],phase)
                self.assertEqual(context['parasite_system'],'srp')
                self.assertEqual(context['parasite_phase_maximum'],10)
                self.assertEqual(context['options'][0]['faction'],'siege')
                self.assertEqual(set(context),{'season','pressure','parasite_system',
                                             'parasite_phase_maximum','parasite_phase','options','recent'})
                self.assertNotIn('budget',json.dumps(context))

    def test_native_forest_and_out_of_range_or_spoofed_capabilities_are_rejected(self):
        invalid=[]
        forest=native_brief();forest['goal']['options'][0]['faction']='forest';invalid.append(forest)
        for changes in (dict(parasitePhase=-1),dict(parasitePhase=11),dict(parasitePhase=True),
                        dict(parasitePhase='10'),dict(parasitePhaseMaximum=5),
                        dict(parasitePhaseMaximum=True),dict(parasitePhaseMaximum=10.0),
                        dict(parasiteSystem='other')):
            value=native_brief();value.update(changes);invalid.append(value)
        missing_cap=native_brief();missing_cap.pop('parasitePhaseMaximum');invalid.append(missing_cap)
        unmarked=native_brief();unmarked.pop('parasiteSystem');invalid.append(unmarked)
        for value in invalid:
            with self.subTest(value=value),self.assertRaises(host.DirectorError):self.context(value)

    def test_modern_stays_phase_five_with_forest_and_without_siege(self):
        value=brief();value['parasitePhase']=5
        context=self.context(value)
        self.assertEqual(context['parasite_system'],'mutationcraft')
        self.assertEqual(context['parasite_phase_maximum'],5)
        self.assertEqual(context['options'][0]['faction'],'forest')
        for changes in (dict(parasitePhase=6),dict(parasitePhase=10),dict(parasitePhaseMaximum=10)):
            invalid=copy.deepcopy(value);invalid.update(changes)
            with self.subTest(changes=changes),self.assertRaises(host.DirectorError):self.context(invalid)
        value['goal']['options'][0]['faction']='siege'
        with self.assertRaises(host.DirectorError):self.context(value)

    def test_native_guidance_reaches_selection_critique_and_revision_only_for_srp(self):
        class CaptureModel(OllamaModel):
            def _generate(self,context,schema,prompt,parse,**kwargs):
                self.captured=prompt
                return {'option':0},{}
        model=CaptureModel(ModelSettings('http://127.0.0.1:11434','qwen3.5:4b',DIGEST))
        native=self.context(native_brief())
        for invoke in (lambda:model.select(native),
                       lambda:model.critique(native,{'option':0}),
                       lambda:model.revise(native,{'option':0},{'option':1,'reason_codes':[]})):
            invoke()
            self.assertIn('parasite_phase_maximum=10',model.captured)
            self.assertIn('siege',model.captured)
            self.assertIn('Леса и форм Mutationcraft в этой кампании нет',model.captured)
        model.select(self.context(brief()))
        self.assertEqual(model.captured,selection_prompt(2))
        self.assertNotIn('SRP',model.captured)

    def test_native_admin_status_and_history_preserve_explicit_maximum(self):
        value=native_status()
        self.assertEqual(evolution_dto(value),value)
        with tempfile.TemporaryDirectory() as tmp:
            service=admin.Evolution(Path(tmp),admin.OperatorConsole(Path(tmp)),reader=lambda _:json.dumps(value))
            result=service.snapshot()
            self.assertEqual(result,dict(state='ready',server=value))
        for changes in (dict(phase=11),dict(phase=True),dict(phaseMaximum=5),dict(phaseMaximum=True),
                        dict(phaseMaximum=10.0),dict(parasiteSystem='other')):
            invalid=copy.deepcopy(value);invalid.update(changes)
            with self.subTest(changes=changes),self.assertRaises(EvolutionError):evolution_dto(invalid)
        invalid=copy.deepcopy(value);invalid['history'][0]['to']=11
        with self.assertRaises(EvolutionError):evolution_dto(invalid)
        for key in ('parasiteSystem','phaseMaximum'):
            invalid=copy.deepcopy(value);invalid.pop(key)
            with self.subTest(missing=key),self.assertRaises(EvolutionError):evolution_dto(invalid)

    def test_modern_admin_phase_and_history_cannot_claim_native_range(self):
        self.assertEqual(evolution_dto(status()),status())
        for changes in (dict(phase=10),dict(phaseMaximum=10)):
            value=status();value.update(changes)
            with self.subTest(changes=changes),self.assertRaises(EvolutionError):evolution_dto(value)
        value=status();value['history'][0]['to']=10
        with self.assertRaises(EvolutionError):evolution_dto(value)

    def test_native_manual_shift_keeps_exact_scoped_command_and_durable_receipt(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);console=admin.OperatorConsole(root)
            service=admin.Evolution(root,console)
            response=json.dumps(dict(accepted=True,code='shifted',evolution=native_status(9)))
            with (patch('dead_earth_director.admin_console.settings',return_value=('configured',25575,'PRIVATE')),
                  patch('dead_earth_director.admin_console.rcon_command',return_value=response) as transport):
                first=service.shift(payload(delta=-1))
                self.assertEqual(first['state'],'completed')
                self.assertEqual(first['result']['evolution']['phaseMaximum'],10)
                self.assertEqual(service.shift(payload(delta=-1)),first)
                transport.assert_called_once_with(25575,'PRIVATE',
                    f'deadearth evolution shift {CAMPAIGN} {EPOCH} 7 {REQUEST} -1')

    def test_observer_retains_native_capabilities_for_admin_rendering(self):
        value=snapshot();value.update(parasiteSystem='srp',parasitePhaseMaximum=10,parasitePhase=10)
        with tempfile.TemporaryDirectory() as tmp:
            store=Telemetry(Path(tmp)/'observer.db')
            store.record(value,dict(operations=[]),now=1000)
            saved=store.view()['snapshot']
            self.assertEqual(saved['parasiteSystem'],'srp')
            self.assertEqual(saved['parasitePhaseMaximum'],10)
            self.assertEqual(saved['parasitePhase'],10)


class NativeAuthoringTests(unittest.TestCase):
    def native_catalog(self):
        from test_admin_authoring import CATALOG
        catalog=json.loads(CATALOG.read_text(encoding='utf-8'))
        for wave in catalog['waves'].values():wave['entities']=['srparasites:sim_human','minecraft:zombie']
        return catalog

    def test_native_catalog_allows_siege_but_rejects_forest_without_schema_changes(self):
        from dead_earth_director.admin_authoring import AuthoringError, validate_event
        from dead_earth_director.parasites import catalog_system
        from test_admin_authoring import event
        catalog=self.native_catalog()
        self.assertEqual(catalog_system(catalog),'srp')
        self.assertEqual(set(catalog),{'schema','rules','sounds','traces','waves','events'})
        for faction in ('parasites','siege','mixed'):
            value=event();value['faction']=faction
            self.assertEqual(validate_event(value,catalog),value)
        for faction in ('forest',{},[]):
            value=event();value['faction']=faction
            with self.subTest(faction=faction),self.assertRaises(AuthoringError) as rejected:validate_event(value,catalog)
            self.assertEqual(rejected.exception.path,'faction')

    def test_mixed_modern_and_ambiguous_wave_libraries_never_enable_siege(self):
        from dead_earth_director.admin_authoring import AuthoringError, validate_event
        from dead_earth_director.parasites import catalog_system
        from test_admin_authoring import event, CATALOG
        cases=[json.loads(CATALOG.read_text(encoding='utf-8'))]
        for entities in (['mutationcraft:assimilated_human'],['overgrown_vegetation:falcimnes'],
                         ['overgrown:creature'],[],[None],['srparasites'],['srparasites:'],['invalid entity']):
            catalog=self.native_catalog();catalog['waves']['ambiguous']={'entities':entities};cases.append(catalog)
        for malformed in ({},[],{'native':None},{'native':{'entities':'srparasites:sim_human'}},
                          {'vanilla':{'entities':['minecraft:zombie']}}):
            catalog=self.native_catalog();catalog['waves']=malformed;cases.append(catalog)
        for catalog in cases:
            with self.subTest(waves=catalog['waves']):
                self.assertEqual(catalog_system(catalog),'mutationcraft')
                value=event();value['faction']='siege'
                with self.assertRaises(AuthoringError):validate_event(value,catalog)

    def test_native_siege_draft_persists_and_forest_is_rejected_before_receipt(self):
        from dead_earth_director.admin_authoring import Authoring, AuthoringError
        from test_admin_authoring import Native, event
        import uuid
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);path=root/'config/dead-earth/director/catalog.json';path.parent.mkdir(parents=True)
            path.write_text(json.dumps(self.native_catalog()),encoding='utf-8')
            native=Native(root);service=Authoring(root,native,reader=native)
            value=event();value['faction']='siege'
            saved=service.action('save',dict(request_id=str(uuid.uuid4()),event=value,draft_revision=0))
            self.assertEqual(saved['draft']['event']['faction'],'siege')
            self.assertEqual(native.calls,[])
            bad=event();bad.update(id='custom_forest',faction='forest')
            with self.assertRaises(AuthoringError):
                service.action('save',dict(request_id=str(uuid.uuid4()),event=bad,draft_revision=0))
            with service.db() as db:
                self.assertEqual(db.execute('SELECT COUNT(*) FROM requests').fetchone()[0],1)
            self.assertEqual(service.snapshot()['state'],'ready')
            self.assertEqual(native.calls,['deadearth operator status'])
