"""Original medieval storage cabinet geometry and hand-authored pixel pattern, no external assets."""
import argparse, json, struct, zlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]/'src/main/resources'
def png():
    rows=[]
    for y in range(16):
        row=[]
        for x in range(16):
            grain=(x*7+y*3)%11
            color=(107+grain,71+grain,39+grain,255)
            if x in (0,15) or y in (0,15,7,8): color=(58,39,25,255)
            if x in (2,13): color=(100,106,105,255)
            if x in (2,13) and y in (2,5,10,13): color=(208,178,99,255)
            if x in (7,8) and y in (9,10,11): color=(224,188,85,255)
            row.extend(color)
        rows.append(b'\0'+bytes(row))
    def chunk(k,d): return struct.pack('>I',len(d))+k+d+struct.pack('>I',zlib.crc32(k+d))
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',16,16,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(b''.join(rows),9))+chunk(b'IEND',b'')
def assets():
    def cuboid(a,b): return {'from':a,'to':b,'faces':{f:{'texture':'#wood','uv':[0,0,16,16]} for f in ('north','south','east','west','up','down')}}
    prefix='assets/livingkingdoms/'
    values={prefix+'textures/block/kingdom_storage.png':png()}
    documents={prefix+'models/block/kingdom_storage.json':{'parent':'minecraft:block/block','textures':{'wood':'livingkingdoms:block/kingdom_storage','particle':'livingkingdoms:block/kingdom_storage'},'elements':[cuboid([1,1,1],[15,14,15]),cuboid([0,14,0],[16,16,16]),cuboid([0,0,0],[16,2,16]),cuboid([6,6,0],[10,10,1])]},
        prefix+'models/item/kingdom_storage.json':{'parent':'livingkingdoms:block/kingdom_storage'},
        prefix+'blockstates/kingdom_storage.json':{'variants':{f'facing={d}':{'model':'livingkingdoms:block/kingdom_storage','y':r} for d,r in [('north',0),('east',90),('south',180),('west',270)]}},
        prefix+'textures/block/kingdom_storage.png.mcmeta':{'texture':{'blur':False,'clamp':False}},
        'data/livingkingdoms/recipe/kingdom_storage.json':{'type':'minecraft:crafting_shaped','category':'building','pattern':['LIL','LCL','LLL'],'key':{'L':{'tag':'minecraft:planks'},'I':{'item':'minecraft:iron_ingot'},'C':{'item':'minecraft:chest'}},'result':{'id':'livingkingdoms:kingdom_storage','count':1}},
        'data/livingkingdoms/loot_table/blocks/kingdom_storage.json':{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'livingkingdoms:kingdom_storage'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]}}
    values.update({k:(json.dumps(v,indent=2)+'\n').encode() for k,v in documents.items()});return values
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args()
    for name,data in assets().items():
        path=ROOT/name
        if args.check: assert path.read_bytes().replace(b'\r\n',b'\n')==data if not name.endswith('.png') else path.read_bytes()==data,name
        else: path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    print('Storage assets verified' if args.check else 'Storage assets generated')
