"""Own compact sieve recipes/resources. Generates data only; never launches Minecraft."""
from pathlib import Path
import json
ROOT=Path(__file__).resolve().parents[1]
def generate():
    native=[('cobblestone','minecraft:cobblestone',0,1),('dirt','minecraft:dirt',0,4),('gravel','minecraft:gravel',0,2),('sand','minecraft:sand',0,3),('dust','exnihilocreatio:block_dust',0,0),('nether_gravel','exnihilocreatio:block_netherrack_crushed',0,6),('ender_gravel','exnihilocreatio:block_endstone_crushed',0,7),('soul_sand','minecraft:soul_sand',0,8)]
    extra=[('red_sand','minecraft:sand',1),('crushed_andesite','exnihilocreatio:block_andesite_crushed',0),('crushed_diorite','exnihilocreatio:block_diorite_crushed',0),('crushed_granite','exnihilocreatio:block_granite_crushed',0),('skystone','exnihilocreatio:block_skystone_crushed',0)]
    extra += [(name+'_leaves','minecraft:leaves' if i<4 else 'minecraft:leaves2',i if i<4 else i-4) for i,name in enumerate(['oak','spruce','birch','jungle','acacia','dark_oak'])]
    rows=[]
    for name,raw,meta,first in native+[(a,b,c,None) for a,b,c in extra]:
        rows.append({'material':name,'raw_id':raw,'raw_meta':meta,'native_level1_id':'excompressum:compressed_block' if first is not None else None,'native_level1_meta':first,'levels':[{'id':'dead_earth:compressed_'+name+'_'+str(power),'meta':0,'compression_power':power,'raw_equivalents':9**power} for power in range(2 if first is not None else 1,5)]})
    for row in rows:
        row['currently_siftable']=row['material'] not in {'cobblestone','crushed_andesite','crushed_granite'}
        if row['native_level1_id'] is None:
            row['own_level1_id']='dead_earth:compressed_'+row['material']+'_1';row['own_level1_meta']=0
    def dump(relative,obj):
        p=ROOT/relative;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(obj,indent=2)+'\n',encoding='utf-8')
    dump('legacy/sieving/materials.json',{'schema':1,'materials':rows})
    dump('legacy/pack/config/dead-earth/sieving.json',{'schema':1,'nativeDrawsPerServerTick':8,'materials':{r['material']:r['raw_id']+('#'+str(r['raw_meta']) if r['raw_meta'] else '') for r in rows},'ownFirstCompression':[r['material'] for r in rows if r['native_level1_id'] is None]})
    dump('legacy/sieving/heavy-entries.json',[{'name':r['levels'][0]['id'],'metadata':'0','type':'generate','sourceName':r['raw_id'],'sourceMetadata':str(r['raw_meta']),'sourceCount':9} for r in rows if r['native_level1_id'] is None and r['currently_siftable']])
    lines=['// Compact III/IV/V = 81/729/6561 actual native raw draws.','// Packed frames retain BlockEntityTag; only empty untagged frames can be upgraded.','import crafttweaker.item.IItemStack;','']
    lines.append('recipes.remove(<excompressum:heavy_sieve:*>);')
    lines.append('recipes.addShaped("de_heavy_frame_9", <excompressum:heavy_sieve:0>, [[<exnihilocreatio:block_sieve:*>,<exnihilocreatio:block_sieve:*>,<exnihilocreatio:block_sieve:*>],[<exnihilocreatio:block_sieve:*>,<exnihilocreatio:block_sieve:*>,<exnihilocreatio:block_sieve:*>],[<exnihilocreatio:block_sieve:*>,<exnihilocreatio:block_sieve:*>,<exnihilocreatio:block_sieve:*>]]);')
    for level in range(3,6):
        previous='<excompressum:heavy_sieve:*>' if level==3 else '<dead_earth:compact_sieve_v'+str(level-1)+'>.only(function(stack) { return !stack.hasTag; })'
        lines.append('recipes.addShaped("de_compact_sieve_'+str(level)+'", <dead_earth:compact_sieve_v'+str(level)+'>, [['+','.join([previous]*3)+'],['+','.join([previous]*3)+'],['+','.join([previous]*3)+']]);')
        for grade,meta in [('string',1),('flint',2),('iron',3),('diamond',4)]:
            prev='<exnihilocreatio:item_mesh:'+str(meta)+'>' if level==3 else '<dead_earth:compact_mesh_'+grade+'_v'+str(level-1)+'>'
            lines.append('recipes.addShaped("de_compact_mesh_'+grade+'_'+str(level)+'", <dead_earth:compact_mesh_'+grade+'_v'+str(level)+'>, [['+','.join([prev]*3)+'],['+','.join([prev]*3)+'],['+','.join([prev]*3)+']]);')
    for r in rows:
        prev='<'+r['native_level1_id']+':'+str(r['native_level1_meta'])+'>' if r['native_level1_id'] else '<'+r['raw_id']+':'+str(r['raw_meta'])+'>'
        for item in r['levels']:
            target='<'+item['id']+'>';key=r['material']+'_'+str(item['compression_power'])
            lines.append('recipes.addShaped("de_compress_'+key+'", '+target+', [['+','.join([prev]*3)+'],['+','.join([prev]*3)+'],['+','.join([prev]*3)+']]);')
            lines.append('recipes.addShapeless("de_decompress_'+key+'", '+prev+' * 9, ['+target+']);');prev=target
    p=ROOT/'legacy/pack/scripts/dead_earth_sieving.zs';p.write_text('\n'.join(lines)+'\n',encoding='utf-8')
    assets='legacy/coordinator/src/main/resources/assets/dead_earth/'
    def block_asset(name,model):
        dump(assets+'blockstates/'+name+'.json',{'variants':{'normal':{'model':'dead_earth:'+name}}});dump(assets+'models/block/'+name+'.json',model);dump(assets+'models/item/'+name+'.json',{'parent':'dead_earth:block/'+name})
    langs=[]
    for level in range(3,6):
        name='compact_sieve_v'+str(level);metal={3:'iron',4:'gold',5:'diamond'}[level]
        block_asset(name,{'parent':'block/cube','textures':{'particle':'blocks/'+metal+'_block','down':'blocks/'+metal+'_block','up':'excompressum:blocks/iron_mesh','north':'blocks/dispenser_front_horizontal','south':'blocks/'+metal+'_block','east':'blocks/'+metal+'_block','west':'blocks/'+metal+'_block'}});langs.append(('tile.dead_earth.'+name+'.name','Compact Sieve '+str(level)+' ('+str(9**(level-1))+')'))
        for grade in ['string','flint','iron','diamond']:
            name='compact_mesh_'+grade+'_v'+str(level);dump(assets+'models/item/'+name+'.json',{'parent':'item/generated','textures':{'layer0':'excompressum:blocks/'+grade+'_mesh'}});langs.append(('item.dead_earth.'+name+'.name',grade.title()+' Compact Mesh '+str(level)))
    tex={'red_sand':'blocks/red_sand','crushed_andesite':'blocks/stone_andesite','crushed_diorite':'blocks/stone_diorite','crushed_granite':'blocks/stone_granite','skystone':'appliedenergistics2:blocks/sky_stone_block'}
    for r in rows:
        for item in r['levels']:
            name=item['id'].split(':')[1];power=item['compression_power'];material=r['material'];texture='excompressum:blocks/compressed_'+material if r['native_level1_id'] else tex.get(material,'blocks/leaves_'+('big_oak' if material=='dark_oak_leaves' else material.replace('_leaves','')))
            metal={1:'stone',2:'iron_block',3:'gold_block',4:'diamond_block'}[power];elements=[{'from':[0,0,0],'to':[16,16,16],'faces':{side:{'texture':'#all','cullface':side} for side in ['down','up','north','south','west','east']}}]
            for side,lo,hi in [('north',[6,6,-.01],[10,10,0]),('south',[6,6,16],[10,10,16.01]),('west',[-.01,6,6],[0,10,10]),('east',[16,6,6],[16.01,10,10]),('up',[6,16,6],[10,16.01,10]),('down',[6,-.01,6],[10,0,10])]:elements.append({'from':lo,'to':hi,'faces':{side:{'texture':'#badge'}}})
            block_asset(name,{'textures':{'particle':texture,'all':texture,'badge':'blocks/'+metal},'elements':elements});langs.append(('tile.dead_earth.'+name+'.name',material.replace('_',' ').title()+' x'+str(9**power)))
    directory=ROOT/'legacy/sieving/lang';directory.mkdir(parents=True,exist_ok=True)
    (directory/'en_us.lang').write_text('\n'.join(k+'='+v for k,v in langs)+'\n',encoding='utf-8')
    (directory/'ru_ru.lang').write_text('\n'.join(k+'='+v.replace('Compact Sieve','Компактное сито').replace('Compact Mesh','Компактная сетка') for k,v in langs)+'\n',encoding='utf-8')
    print('Generated',len(rows),'raw materials;',sum(len(r['levels']) for r in rows),'placeable compressed blocks; 3 compact frames/12 meshes.')
if __name__=='__main__':generate()
