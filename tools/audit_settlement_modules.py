#!/usr/bin/env python3
"""Trace actual checked-in NBT blocks and four transforms (standard library only).

Optional SVG contact sheet is a geometry diagnostic, not Minecraft screenshot QA.
"""
import argparse
import collections
import gzip
import io
import json
from pathlib import Path
import struct
import subprocess


def read_nbt(data):
    stream = io.BytesIO(gzip.decompress(data))
    def number(fmt): return struct.unpack('>'+fmt,stream.read(struct.calcsize(fmt)))[0]
    def string(): return stream.read(number('H')).decode('utf-8')
    def tag(kind):
        if kind == 1: return number('b')
        if kind == 3: return number('i')
        if kind == 8: return string()
        if kind == 9:
            child,count=number('B'),number('i')
            return [tag(child) for _ in range(count)]
        if kind == 10:
            value={}
            while True:
                child=number('B')
                if child == 0: return value
                key=string(); value[key]=tag(child)
        raise ValueError(f'Unsupported authoring tag: {kind}')
    assert number('B') == 10
    string()
    return tag(10)


def transformed(pos,size,rotation):
    x,y,z=pos; width,_,depth=size
    return ((x,y,z),(depth-1-z,y,x),(width-1-x,y,depth-1-z),(z,y,width-1-x))[rotation]


def audit(nbt):
    size=nbt['size']; palette=nbt['palette']; blocks={tuple(b['pos']):palette[b['state']] for b in nbt['blocks']}
    assert len(blocks)==len(nbt['blocks']), 'Duplicate native positions'
    solid={p for p,b in blocks.items() if b['Name']!='minecraft:air'}
    pending=collections.deque([(0,0,0)]); reached=set()
    while pending:
        x,y,z=pending.popleft()
        if (x,y,z) not in solid or (x,y,z) in reached: continue
        reached.add((x,y,z))
        pending.extend([(x+1,y,z),(x-1,y,z),(x,y+1,z),(x,y-1,z),(x,y,z+1),(x,y,z-1)])
    rotations=[]
    for rotation in range(4):
        positions={transformed(p,size,rotation) for p in blocks}
        bounds=[min(p[i] for p in positions) for i in range(3)]+[max(p[i] for p in positions) for i in range(3)]
        expected=(size if rotation%2==0 else [size[2],size[1],size[0]])
        assert bounds == [0,0,0]+[s-1 for s in expected] and len(positions)==len(blocks)
        rotations.append({'degrees':rotation*90,'bounds':bounds})
    wooden_layers=collections.Counter(p[1] for p,b in blocks.items() if any(t in b['Name'] for t in ('planks','stairs','slab')) and p[1]>0)
    return {'size':size,'blocks':len(blocks),'non_air':len(solid),'detached_count':len(solid-reached),
            'detached_examples':sorted(solid-reached)[:12], 'wooden_layer_counts':dict(sorted(wooden_layers.items())),
            'rotations':rotations}, blocks


def contact_sheet(modules,path):
    # Orthographic native block positions, with approximate vanilla colors and stair/slab silhouettes.
    colors={'stone':('#99988c','#77776d','#b3b2a3'),'cobble':('#96958a','#707069','#aead9f'),
            'oak':('#aa844e','#86663f','#c29a60'),'spruce':('#79603e','#5b492f','#92774f'),
            'dark_oak':('#55412b','#3c2d1d','#72563a'),'glass':('#9cafb0','#708e92','#bed7d7'),
            'blue':('#445e8c','#304368','#6b86b1'),'default':('#97856a','#776850','#b6a183')}
    svg=['<svg xmlns="http://www.w3.org/2000/svg" width="1440" height="850" viewBox="0 0 1440 850">',
         '<rect width="1440" height="850" fill="#f0eadc"/>',
         '<text x="24" y="27" font-size="18" font-family="sans-serif">Native NBT geometry audit — diagnostic only; Minecraft screenshots still required</text>']
    for index,(name,blocks) in enumerate(modules.items()):
        ox=180+(index%4)*360; oy=330+(index//4)*390
        svg.append(f'<text x="{ox-150}" y="{oy-266}" font-size="16" font-family="sans-serif">{name}</text>')
        def project(x,y,z): return (ox+(x-z)*10,oy+(x+z)*5-y*12)
        for (x,y,z),state in sorted(blocks.items(),key=lambda pair:(sum((pair[0][0],pair[0][2])),pair[0][1])):
            block=state['Name'];props=state.get('Properties',{})
            if block.endswith(':air'): continue
            key=next((key for key in ('dark_oak','spruce','glass','blue','cobble','stone','oak') if key in block),'default')
            cubes=[(0,0,0,1,1,1)]
            if 'slab' in block: cubes=[(0,0.5 if props.get('type')=='top' else 0,0,1,1 if props.get('type')=='top' else 0.5,1)]
            elif 'stairs' in block:
                face=props.get('facing'); upper={'east':(.5,.5,0,1,1,1),'west':(0,.5,0,.5,1,1),'south':(0,.5,.5,1,1,1),'north':(0,.5,0,1,1,.5)}[face]
                cubes=[(0,0,0,1,.5,1),upper]
            elif 'fence' in block or 'wall' in block and 'banner' not in block and 'sign' not in block: cubes=[(.3,0,.3,.7,1,.7)]
            elif 'pane' in block: cubes=[(.42,0,0,.58,1,1)] if props.get('north')=='true' else [(0,0,.42,1,1,.58)]
            elif 'lantern' in block or 'flower_pot' in block: cubes=[(.3,0,.3,.7,.6,.7)]
            for x0,y0,z0,x1,y1,z1 in cubes:
                def polygon(points,color):
                    pts=' '.join(','.join(map(str,project(x+px,y+py,z+pz))) for px,py,pz in points)
                    svg.append(f'<polygon points="{pts}" fill="{color}" stroke="#453e32" stroke-width=".35"/>')
                a,b,c=colors[key]
                polygon([(x0,y0,z1),(x1,y0,z1),(x1,y1,z1),(x0,y1,z1)],a)
                polygon([(x1,y0,z0),(x1,y0,z1),(x1,y1,z1),(x1,y1,z0)],b)
                polygon([(x0,y1,z0),(x1,y1,z0),(x1,y1,z1),(x0,y1,z1)],c)
    svg.append('</svg>');path.parent.mkdir(parents=True,exist_ok=True);path.write_text('\n'.join(svg),encoding='utf-8')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compare-head',action='store_true')
    parser.add_argument('--output',type=Path,default=Path('build/qa/template-audit.json'))
    parser.add_argument('--svg',type=Path)
    args=parser.parse_args();root=Path(__file__).resolve().parents[1]
    results={};modules={}
    for path in sorted((root/'src/main/resources/data/livingkingdoms/structure/allied/plains').glob('*.nbt')):
        result,blocks=audit(read_nbt(path.read_bytes()));modules[path.stem]=blocks
        if args.compare_head:
            before=subprocess.run(['git','show','HEAD:'+path.relative_to(root).as_posix()],cwd=root,capture_output=True,check=True).stdout
            result['head']=audit(read_nbt(before))[0]
        results[path.stem]=result
        print(f"{path.stem}: {result['size']}, detached={result['detached_count']}, four transforms verified"+
              (f", HEAD detached={result['head']['detached_count']}" if args.compare_head else ''))
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(results,indent=2)+'\n',encoding='utf-8')
    if args.svg: contact_sheet(modules,args.svg)
    if any(result['detached_count'] for result in results.values()): raise SystemExit('Detached blocks remain')


if __name__=='__main__': main()
