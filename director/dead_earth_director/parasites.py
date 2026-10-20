"""Read-only parasite capability bounds shared by native and modern adapters."""
import re


def capabilities(value, maximum_field='parasitePhaseMaximum'):
    """A larger phase range requires the explicit SRP capability pair."""
    system=value.get('parasiteSystem')
    if system is None:
        maximum=5
        name='mutationcraft'
    elif system=='srp':
        maximum=10
        name='srp'
    else:
        raise ValueError('invalid_parasite_capabilities')
    declared=value.get(maximum_field,5)
    if type(declared) is not int or declared!=maximum:
        raise ValueError('invalid_parasite_capabilities')
    return name,maximum


def factions(system):
    return {'parasites','siege','mixed'} if system=='srp' else {'forest','parasites','mixed'}


def catalog_system(catalog):
    """Keep the exact catalog schema; infer SRP only from unambiguous wave entities."""
    waves=catalog.get('waves') if type(catalog) is dict else None
    if type(waves) is not dict or not waves:return 'mutationcraft'
    entities=[]
    for wave in waves.values():
        if type(wave) is not dict or type(wave.get('entities')) is not list:return 'mutationcraft'
        if not wave['entities'] or any(type(entity) is not str or not re.fullmatch(r'[a-z0-9_.-]+:[a-z0-9_./-]+',entity) for entity in wave['entities']):return 'mutationcraft'
        entities.extend(wave['entities'])
    namespaces={entity.partition(':')[0] for entity in entities}
    if 'srparasites' not in namespaces or any(name=='mutationcraft' or name.startswith('overgrown') for name in namespaces):
        return 'mutationcraft'
    return 'srp'
