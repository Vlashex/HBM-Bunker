"""Own compact sieve recipes/resources. Generates data only; never launches Minecraft."""
from pathlib import Path
import json
ROOT=Path(__file__).resolve().parents[1]
def merge_lang(path, entries):
    """Replace this generator's keys once, retaining unrelated translations/comments."""
    values=dict(entries);seen=set();lines=[]
    for line in path.read_text(encoding='utf-8').splitlines() if path.exists() else []:
        key=line.split('=',1)[0] if '=' in line else None
        if key in values:
            if key not in seen:lines.append(key+'='+values[key]);seen.add(key)
        else:lines.append(line)
    lines.extend(key+'='+value for key,value in entries if key not in seen)
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text('\n'.join(lines)+'\n',encoding='utf-8',newline='\n')

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
        p=ROOT/relative;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(obj,indent=2)+'\n',encoding='utf-8',newline='\n')
    dump('legacy/sieving/materials.json',{'schema':1,'materials':rows})
    dump('legacy/pack/config/dead-earth/sieving.json',{'schema':1,'nativeDrawsPerServerTick':256,'materials':{r['material']:r['raw_id']+('#'+str(r['raw_meta']) if r['raw_meta'] else '') for r in rows},'ownFirstCompression':[r['material'] for r in rows if r['native_level1_id'] is None]})
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
    p=ROOT/'legacy/pack/scripts/dead_earth_sieving.zs';p.write_text('\n'.join(lines)+'\n',encoding='utf-8',newline='\n')
    assets='legacy/coordinator/src/main/resources/assets/dead_earth/'
    def block_asset(name,model):
        dump(assets+'blockstates/'+name+'.json',{'variants':{'normal':{'model':'dead_earth:'+name}}});dump(assets+'models/block/'+name+'.json',model);dump(assets+'models/item/'+name+'.json',{'parent':'dead_earth:block/'+name})
    langs=[]
    for level in range(3,6):
        name='compact_sieve_v'+str(level)
        # Inherit the actual native heavy sieve: four legs, wooden rim, open sides.
        # Its mesh plane is at y=8.5 inside the rim, never a solid cube top.
        textures={'particle':'minecraft:blocks/log_oak','texture':'minecraft:blocks/log_oak'}
        display={'gui':{'rotation':[30,225,0],'translation':[0,0,0],'scale':[0.625,0.625,0.625]},'ground':{'translation':[0,3,0],'scale':[0.25,0.25,0.25]},'fixed':{'scale':[0.5,0.5,0.5]},'thirdperson_righthand':{'rotation':[75,45,0],'translation':[0,2.5,0],'scale':[0.375,0.375,0.375]},'firstperson_righthand':{'rotation':[0,45,0],'scale':[0.4,0.4,0.4]},'firstperson_lefthand':{'rotation':[0,225,0],'scale':[0.4,0.4,0.4]}}
        block_asset(name,{'parent':'excompressum:block/heavy_sieve','textures':textures,'display':display})
        multipart=[{'when':{'mesh':'0'},'apply':{'model':'dead_earth:'+name}}]
        for mesh,grade in enumerate(['string','flint','iron','diamond'],1):
            variant=name+'_'+grade
            dump(assets+'models/block/'+variant+'.json',{'parent':'excompressum:block/heavy_sieve_with_mesh','textures':dict(textures,iron_mesh='excompressum:blocks/'+grade+'_mesh')})
            multipart.append({'when':{'mesh':str(mesh)},'apply':{'model':'dead_earth:'+variant}})
        # Independently select the wooden frame/mesh and the shrinking material layer.
        # 76 shared content models cover all 3 tiers, rather than duplicating every state.
        for index,row in enumerate(rows):
            for fill in range(1,5):
                multipart.append({'when':{'material':str(index),'fill':str(fill)},'apply':{'model':'dead_earth:compact_sieve_content_'+row['material']+'_'+str(fill)}})
        dump(assets+'blockstates/'+name+'.json',{'multipart':multipart})
        langs.append(('tile.dead_earth.'+name+'.name','Compact Sieve '+str(level)+' ('+str(9**(level-1))+')'))
        for grade in ['string','flint','iron','diamond']:
            mesh_name='compact_mesh_'+grade+'_v'+str(level)
            # Ex Nihilo's item parents are real thin lattices with proper inventory transforms.
            dump(assets+'models/item/'+mesh_name+'.json',{'parent':'exnihilocreatio:item/item_mesh_'+grade})
            langs.append(('item.dead_earth.'+mesh_name+'.name',grade.title()+' Compact Mesh '+str(level)))
    tex={'red_sand':'minecraft:blocks/red_sand','crushed_andesite':'exnihilocreatio:blocks/crushed_andesite','crushed_diorite':'exnihilocreatio:blocks/crushed_diorite','crushed_granite':'exnihilocreatio:blocks/crushed_granite','skystone':'exnihilocreatio:blocks/blockskystonecrushed'}
    raw_tex=dict(tex,cobblestone='minecraft:blocks/cobblestone',dirt='minecraft:blocks/dirt',gravel='minecraft:blocks/gravel',sand='minecraft:blocks/sand',dust='exnihilocreatio:blocks/blockdust',nether_gravel='exnihilocreatio:blocks/blocknetherrackcrushed',ender_gravel='exnihilocreatio:blocks/blockendstonecrushed',soul_sand='minecraft:blocks/soul_sand')
    for row in rows:
        material=row['material']
        texture=raw_tex.get(material,'minecraft:blocks/leaves_'+('big_oak' if material=='dark_oak_leaves' else material.replace('_leaves','')))
        for fill in range(1,5):
            # The native mesh is at y=8.5 and the rim ends at y=12. Each working
            # click visibly lowers the material; fill=0 selects no content model.
            model={'parent':'minecraft:block/block','textures':{'particle':texture,'all':texture},'elements':[{'from':[1,8.55,1],'to':[15,round(8.55+fill*.8,2),15],'faces':{side:{'texture':'#all','tintindex':1} for side in ['down','up','north','south','west','east']}}]}
            dump(assets+'models/block/compact_sieve_content_'+material+'_'+str(fill)+'.json',model)
    for r in rows:
        for item in r['levels']:
            name=item['id'].split(':')[1];power=item['compression_power'];material=r['material'];texture='excompressum:blocks/compressed_'+material if r['native_level1_id'] else tex.get(material,'minecraft:blocks/leaves_'+('big_oak' if material=='dark_oak_leaves' else material.replace('_leaves','')))
            # Keep the exact native material pixels. SieveVisuals multiplies tint 0 by
            # compression depth in both world and inventory (including native leaf tint).
            elements=[{'from':[0,0,0],'to':[16,16,16],'faces':{side:{'texture':'#all','cullface':side,'tintindex':0} for side in ['down','up','north','south','west','east']}}]
            block_asset(name,{'parent':'minecraft:block/block','textures':{'particle':texture,'all':texture},'elements':elements});langs.append(('tile.dead_earth.'+name+'.name',material.replace('_',' ').title()+' x'+str(9**power)))
    tooltip_en=[('tooltip.dead_earth.sieving.frame_mesh','Mesh: Compact Mesh %s (any grade)'),('tooltip.dead_earth.sieving.frame_input','Input: compression %s (%s raw blocks)'),('tooltip.dead_earth.sieving.frame_use','Right-click: install mesh, load input, collect output'),('tooltip.dead_earth.sieving.frame_strokes','Sieves automatically while matching mesh and input are loaded'),('tooltip.dead_earth.sieving.frame_remove','Sneak + empty-hand right-click: remove mesh/input'),('tooltip.dead_earth.sieving.mesh_frame','Fits Compact Sieve %s'),('tooltip.dead_earth.sieving.native_mesh','Fits native sieves; compact frames need a matching Compact Mesh'),('tooltip.dead_earth.sieving.compression','Compression %s: %s raw blocks')]
    tooltip_ru=[('tooltip.dead_earth.sieving.frame_mesh','Сетка: компактная сетка %s (любой материал)'),('tooltip.dead_earth.sieving.frame_input','Сырьё: сжатие %s (%s исходных блоков)'),('tooltip.dead_earth.sieving.frame_use','ПКМ: установить сетку, загрузить сырьё, забрать результат'),('tooltip.dead_earth.sieving.frame_strokes','Автоматически просеивает сырьё с установленной сеткой'),('tooltip.dead_earth.sieving.frame_remove','Shift + ПКМ пустой рукой: забрать сетку/сырьё'),('tooltip.dead_earth.sieving.mesh_frame','Для компактного сита %s'),('tooltip.dead_earth.sieving.native_mesh','Для обычных сит; компактному ситу нужна компактная сетка того же уровня'),('tooltip.dead_earth.sieving.compression','Сжатие %s: %s исходных блоков')]
    roman={1:'I',2:'II',3:'III',4:'IV',5:'V'}
    russian_materials={'cobblestone':'Сжатый булыжник','dirt':'Сжатая земля','gravel':'Сжатый гравий','sand':'Сжатый песок','dust':'Сжатая пыль','nether_gravel':'Сжатый незерский гравий','ender_gravel':'Сжатый эндерский гравий','soul_sand':'Сжатый песок душ','red_sand':'Сжатый красный песок','crushed_andesite':'Сжатый дроблёный андезит','crushed_diorite':'Сжатый дроблёный диорит','crushed_granite':'Сжатый дроблёный гранит','skystone':'Сжатый дроблёный небесный камень','oak_leaves':'Сжатая дубовая листва','spruce_leaves':'Сжатая еловая листва','birch_leaves':'Сжатая берёзовая листва','jungle_leaves':'Сжатая тропическая листва','acacia_leaves':'Сжатая акациевая листва','dark_oak_leaves':'Сжатая листва тёмного дуба'}
    russian_grades={'string':'Нитяная','flint':'Кремнёвая','iron':'Железная','diamond':'Алмазная'}
    russian_names={}
    for level in range(3,6):
        russian_names['tile.dead_earth.compact_sieve_v'+str(level)+'.name']='Сито '+roman[level]
        for grade,adjective in russian_grades.items():
            russian_names['item.dead_earth.compact_mesh_'+grade+'_v'+str(level)+'.name']=adjective+' сетка '+roman[level]
    for row in rows:
        for item in row['levels']:
            russian_names['tile.dead_earth.'+item['id'].split(':')[1]+'.name']=russian_materials[row['material']]+' ×'+str(item['raw_equivalents'])
    assert set(russian_names)=={key for key,value in langs}
    directory=ROOT/'legacy/sieving/lang';directory.mkdir(parents=True,exist_ok=True)
    for language,entries in [('en_us',langs+tooltip_en),('ru_ru',[(k,russian_names[k]) for k,v in langs]+tooltip_ru)]:
        (directory/(language+'.lang')).write_text('\n'.join(k+'='+v for k,v in entries)+'\n',encoding='utf-8',newline='\n')
        merge_lang(ROOT/assets/'lang'/(language+'.lang'),entries)
    print('Generated',len(rows),'raw materials;',sum(len(r['levels']) for r in rows),'placeable compressed blocks; 3 compact frames/12 meshes.')
if __name__=='__main__':generate()
