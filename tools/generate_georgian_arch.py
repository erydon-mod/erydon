#!/usr/bin/env python3
"""Derive the Georgian arch family from its preserved three-wide BB master.

The authored left side is mirrored across X. Each width and crown cell has
prederived geometry, so placement needs no whole-arch scaling at render time.
"""
from __future__ import annotations

import argparse
import copy
from collections import defaultdict
import hashlib
import math
from pathlib import Path

import generate_georgian_alcove as alcove
import generate_romanesque_arch as mesh
import model_z_fighting_safety as geometry

ROOT = Path(__file__).resolve().parents[1]
DESIGN = Path('authoring/arches/georgian')
MASTER = DESIGN/'arch_georgian_3wide.bbmodel'
ASSETS = Path('src/main/resources/assets/erydon')
RAW = ASSETS/'authoring_models/block/arch/georgian'
PARENTS = ASSETS/'models/block/arch/georgian'
JAMB = 2.10064


def crown_rows(width, height):
    return min(height, (width+2)//3)


def top_suffix(width, row, column, short=False):
    return f'width{width}_short_top_column{column}' if short else f'width{width}_top_row{row}_column{column}'


def suffixes():
    result = ['base_left','base_right','middle_left','middle_right']
    result += [top_suffix(w,r,c) for w in range(1,7) for r in range((w+2)//3) for c in range(w)]
    result += [top_suffix(w,0,c,True) for w in (4,5,6) for c in range(w)]
    return tuple(result+['icon'])


def raw_document(elements):
    result = mesh.raw_document(elements)
    result['credit'] = 'Derived from the editable Georgian arch three-wide design'
    return result


def source_rows(repo):
    project = alcove.load(repo/MASTER)
    document, owners, _ = alcove.native_to_raw(project)
    rows = defaultdict(list)
    for i,e in enumerate(document['elements']):
        role = next(name for name in owners[i] if '__' in name)
        rows[role].append(e)
    return rows, project


def primitive_mirror(element, width):
    """Keep the cuboid's two-unit band thickness for the mitre fitter."""
    result = copy.deepcopy(element)
    result['from'][0], result['to'][0] = width-element['to'][0], width-element['from'][0]
    rotation = result.get('rotation',{})
    if any(abs(rotation.get(a,0))>1e-9 for a in ('x','y')):
        raise ValueError('The Georgian crown uses Z-rotated cubes')
    if rotation:
        rotation['z'] = -rotation.get('z',0)
        rotation['origin'][0] = width-rotation['origin'][0]
    result['faces'] = {mesh.FACE_X.get(k,k):v for k,v in result['faces'].items()}
    return result


def explicit_solid(element):
    face = element['faces']['south']['erydon_vertices']
    element['_solid'] = {'axis':'z','polygon':mesh.hull([[p[0],p[1]] for p in face]),
                         'depth':[min(p[2] for f in element['faces'].values() for p in f['erydon_vertices']),
                                  max(p[2] for f in element['faces'].values() for p in f['erydon_vertices'])]}
    return element


def polygon_prism(name, polygon, z1, z2):
    """Extrude a fitted infill polygon without inventing internal seam faces."""
    if mesh.polygon_area(polygon)<1e-10:return []
    solid = {'axis':'z','polygon':mesh.hull(polygon),'depth':[z1,z2]}
    polygon = solid['polygon']
    result = []
    faces = [('south',[[p[0],p[1],z2] for p in polygon]),
             ('north',[[p[0],p[1],z1] for p in reversed(polygon)])]
    for a,b in zip(polygon,polygon[1:]+polygon[:1]):
        normal = [b[1]-a[1],a[0]-b[0]]
        face = ('east' if normal[0]>0 else 'west') if abs(normal[0])>abs(normal[1]) else ('up' if normal[1]>0 else 'down')
        faces.append((face,[[a[0],a[1],z1],[a[0],a[1],z2],[b[0],b[1],z2],[b[0],b[1],z1]]))
    for face,points in faces:
        pieces = [points] if len(points)<=4 else [[points[0],points[i],points[i+1]] for i in range(1,len(points)-1)]
        for p in pieces:
            if len(p)==3:p=p+[p[-1]]
            element = {'name':name,'faces':{face:{'texture':'#stone','erydon_vertices':p}},
                       'from':[min(v[a] for v in p) for a in range(3)],'to':[max(v[a] for v in p) for a in range(3)],'_solid':solid}
            for a in range(3):
                if element['to'][a]-element['from'][a]<1e-10:element['to'][a]+=1e-8
            result.append(element)
    return result


def crown_geometry(rows, width, short=False):
    count = 1 if short else (width+2)//3
    scale_y = .53125 if width==1 else 1 if width<=3 or short else width/3
    springing = 7.5 if width==1 else count*16-16*scale_y
    scale_x = (width*16-2*JAMB)/(48-2*JAMB)
    core = lambda p:(JAMB+(p[0]-JAMB)*scale_x,(p[1]-32)*scale_y+springing,p[2])
    left = rows['ROOF__top_left']
    ridge = [e for e in left if abs(e.get('rotation',{}).get('z',0))>1e-6
             and e['from'][2]<=-.4 and e['to'][2]>=16.4]
    ridge.sort(key=lambda e:abs(e['rotation']['z']),reverse=True)
    if len(ridge)!=7:
        raise ValueError(f'Expected seven full-depth left ridge sections, found {len(ridge)}')
    complete_ridge = ridge+[primitive_mirror(e,48) for e in reversed(ridge)]
    original_lines = alcove.ridge_lines(complete_ridge,lambda p:(p[0],p[1]-32,p[2]))
    middle = len(original_lines)//2
    original_peak = alcove.intersect(original_lines[middle-1][0],original_lines[middle-1][2],
                                     original_lines[middle][0],original_lines[middle][2])[1]
    if width==3:
        fitted = [mesh.translated(e,[0,-32,0]) for e in ridge]
        crest_shift = 0
    else:
        pieces,target_peak = alcove.mitre_band(complete_ridge,core,springing+1.5)
        fitted = [explicit_solid(e) for e in pieces[:7]]
        crest_shift = target_peak-original_peak
    fitted_by_name = {id(e):fitted[i] for i,e in enumerate(ridge)}
    infills = [e for e in left if id(e) not in fitted_by_name and e['name'] not in ('top','outer_jamb_left')]
    lintel_top = next(e['to'][1]-32+crest_shift for e in left if e['name']=='top' and e['to'][0]-e['from'][0]>=15.9)
    left_out = []
    for e in rows['SIDES__top_left']+left:
        if id(e) in fitted_by_name:
            output = fitted_by_name[id(e)]
        elif e['name']=='outer_jamb_left':
            # The plain outer wall starts at springing; capital bands keep
            # their physical height and width under every crown profile.
            if e in rows['SIDES__top_left']:
                output = mesh.baked(e,lambda p:(p[0],springing+(p[1]-32)*(lintel_top-springing)/16,p[2])) if width>=4 else mesh.baked(e,lambda p:(p[0],core(p)[1],p[2]))
            else:
                output = mesh.translated(e,[0,-32+springing,0])
        elif e['name']=='top' and e['to'][0]-e['from'][0]>=15.9:
            output = mesh.baked(e,lambda p:(p[0]*width/3,p[1]-32+crest_shift,p[2]))
        elif width>=4 and e in infills:
            # Affine copies of the old cuboid infill can protrude through a
            # fixed-thickness ridge. Fit the wall to its new upper envelope.
            continue
        else:
            output = mesh.translated(e,[0,-32,0]) if width==3 else mesh.baked(e,core)
        left_out.append(output)
    if width>=4:
        for i,band in enumerate(fitted):
            edge = band['faces']['up']['erydon_vertices']
            a,b = edge[0][:2],edge[2][:2]
            polygon = mesh.hull([a,b,[b[0],lintel_top],[a[0],lintel_top]])
            polygon = mesh.clip_polygon(mesh.clip_polygon(polygon,0,0,True),1,lintel_top,False)
            source = min(infills,key=lambda e:abs(abs(e.get('rotation',{}).get('z',0))-abs(ridge[i]['rotation']['z'])))
            left_out += polygon_prism(f'fitted_infill_{i:02d}',polygon,source['from'][2],source['to'][2])
    # Fluting fills any unused lower portion of a crown cell, exactly up to
    # its capital. It never inherits horizontal or depth scaling.
    if springing>1e-8:
        for e in rows['SIDES__middle_left']:
            output = copy.deepcopy(e)
            output['from'][1],output['to'][1] = 0,springing
            output.get('rotation',{}).pop('origin',None)
            left_out.append(output)
    center = mesh.source_center(rows['ROOF__top_center'])
    center_out = []
    for e in center:
        # Full-width slabs/lining grow across X. The crest/apex caps retain
        # their authored dimensions and follow the fitted curve's peak.
        original_width = e['_solid']['polygon']
        is_slab = max(p[0] for p in original_width)-min(p[0] for p in original_width)>=7.99
        transform = (lambda p:(p[0]*width/3,p[1]-32+crest_shift,p[2])) if is_slab else (
                    lambda p:(p[0]+(width-3)*8,p[1]-32+crest_shift,p[2]))
        center_out.append(mesh.baked(e,transform))
    return left_out+[mesh.x_mirror(e,width*16) for e in left_out]+center_out, {
        'crown_rows':count,'springing_units':springing,'curve_xy_scale':[scale_x,scale_y],
        'crest_shift_units':crest_shift,'ridge_segments':14}


def cell_slice(element, width, count, row, column):
    y = (count-1-row)*16
    bounds = [(0,-1000 if column==0 else column*16,1000 if column==width-1 else (column+1)*16),
              (1,-1000 if row==count-1 else y,1000 if row==0 else y+16)]
    solid = copy.deepcopy(mesh.volume(element))
    for axis,low,high in bounds:
        solid['polygon'] = mesh.clip_polygon(mesh.clip_polygon(solid['polygon'],axis,low,True),axis,high,False)
    if mesh.polygon_area(solid['polygon'])<1e-10:
        return []
    result = []
    for part in mesh.clipped_element_faces(element,bounds):
        part['_solid'] = solid
        result.append(mesh.translated(part,[-column*16,-y,0]))
    return result


def models_from_master(repo):
    rows,project = source_rows(repo)
    models = {}
    for part,row in (('base',0),('middle',1)):
        left = [mesh.translated(e,[0,-row*16,0]) for e in rows[f'SIDES__{part}_left']]
        models[part+'_left'] = raw_document(left)
        models[part+'_right'] = raw_document([mesh.x_mirror(e,16) for e in left])
    profiles = {}
    for width in range(1,7):
        for short in (False,True) if width>=4 else (False,):
            whole,profile = crown_geometry(rows,width,short)
            count = profile['crown_rows']
            profiles[f'{width}'+('_short' if short else '')] = profile
            for row in range(count):
                for column in range(width):
                    elements = [p for e in whole for p in cell_slice(e,width,count,row,column)]
                    models[top_suffix(width,row,column,short)] = raw_document(elements)
    return models,profiles,project


def component_selection(width,height,row,column):
    count = crown_rows(width,height)
    if row>=height-count:
        return (top_suffix(width,height-1-row,column,height==1 and width>=4),)
    sides = ['left'] if column==0 else []
    if column==width-1:sides.append('right')
    return tuple(('base_' if row==0 else 'middle_')+side for side in sides)


def assembly(models,width,height=None):
    height = height or (width+2)//3+2
    result = raw_document([])
    result['groups'] = []
    components = []
    for row in range(height):
        group = {'name':f'ROW_{row}','origin':[width*8,row*16+8,8],'children':[]}
        for column in range(width):
            cell = {'name':f'CELL_{column}','origin':[column*16+8,row*16+8,8],'children':[]}
            for suffix in component_selection(width,height,row,column):
                start = len(result['elements'])
                result['elements'] += [mesh.translated(mesh.identified(e,suffix,i),[column*16,row*16,0])
                                       for i,e in enumerate(models[suffix]['elements'])]
                size = len(result['elements'])-start
                cell['children'].append({'name':suffix,'origin':cell['origin'],'children':list(range(start,start+size))})
                components.append({'suffix':suffix,'source':(RAW/f'arch_georgian_{suffix}.json').as_posix(),
                    'translation':[column*16,row*16,0],'runtime_y_rotation':0,
                    'element_start':start,'element_count':size,'cell':[column,row,0]})
            group['children'].append(cell)
        result['groups'].append(group)
    result['__erydon_authoring'] = {'schema':1,'width_blocks':width,'height_blocks':height,
        'crown_rows':crown_rows(width,height),'front':'+Z; runtime NORTH applies Y180',
        'mirror_policy':'Authoritative LEFT; pure X mirror on RIGHT','components':components}
    return result


def separate(models):
    graph = defaultdict(set)
    observations = 0
    verifying = False
    def scan(document,label,suffix=None):
        nonlocal observations
        report = geometry.scan_model_document(mesh.clean(document),relative_path=label,source_sha256='',
                                             raw_authoring=True,enable_raw_transforms=True)
        if report['unsupported']:
            raise ValueError(f'Unsupported geometry in {label}: {report["unsupported"][:3]}')
        for finding in report['findings']:
            if finding['orientation']!='same':continue
            # Include the icon's near tier after miniature projection.
            if finding['plane_quality']!='exact' and suffix!='icon':continue
            nodes = []
            for end in ('a','b'):
                record = finding[end]
                nodes.append((suffix,record['element'],record['face']) if suffix else
                             tuple(document['elements'][record['element']]['_nodes'][record['face']]))
            a,b = nodes
            if verifying:
                raise ValueError(f'Face overlap left in {label}: {a}, {b}')
            if a==b:
                raise ValueError(f'Repeated source face overlaps itself in {label}: {a}')
            graph[a].add(b);graph[b].add(a);observations+=1
    layouts = [(w,h) for w in range(1,7) for h in range(1,6)]
    def inspect_all():
        for suffix,doc in models.items():scan(doc,suffix,suffix)
        for width,height in layouts:scan(assembly(models,width,height),f'cluster/{width}/{height}')
    inspect_all()
    colors = {}
    for node in sorted(graph,key=lambda n:(-len(graph[n]),n)):
        occupied = {colors[n] for n in graph[node] if n in colors}
        colors[node] = next(i for i in range(len(occupied)+1) if i not in occupied)
    # Select a microscopic step whose final emitted planes do not coincide
    # with another authored plane. Every candidate gets a complete rescan.
    last_error = None
    for step in (.000375,.000431,.000527,.000613):
        for doc in models.values():
            for e in doc['elements']:
                for face in e['faces'].values():face.pop('erydon_normal_offset',None)
        max_offset = max(colors.values(),default=0)*step
        if max_offset>=.05:raise ValueError('Microscopic face separation limit exceeded')
        for (suffix,index,face),color in colors.items():
            if color:models[suffix]['elements'][index]['faces'][face]['erydon_normal_offset']=round(color*step,8)
        verifying = True
        try:
            inspect_all()
            break
        except ValueError as error:
            last_error = error
    else:
        raise last_error
    return {'standalone_models_checked':len(models),'cluster_layouts_checked':len(layouts),
        'observed_overlap_pairs':observations,'unique_overlap_edges':sum(map(len,graph.values()))//2,
        'coplanar_faces_separated':sum(c>0 for c in colors.values()),'max_normal_offset':max_offset,
        'normal_offset_step':step,'remaining_exact_same_plane_overlaps':0,'remaining_icon_near_same_plane_overlaps':0}


def generate(repo):
    models,profiles,project = models_from_master(repo)
    references = {width:assembly(models,width) for width in range(1,7)}
    models['icon'] = raw_document([mesh.baked(e,lambda p:(p[0]/3,p[1]/3,p[2]/3+16/3))
                                  for e in references[3]['elements']])
    if set(models)!=set(suffixes()):raise ValueError('Georgian suffix contract mismatch')
    collisions = {suffix:mesh.collision_boxes(doc['elements']) for suffix,doc in models.items() if suffix!='icon'}
    separation = separate(models)
    output = {RAW/f'arch_georgian_{suffix}.json':alcove.encoded(mesh.clean(doc)) for suffix,doc in models.items()}
    for suffix in models:
        anchor = {'parent':'minecraft:block/block','textures':{'particle':'#stone'},'elements':[]}
        if suffix=='icon':anchor['display']=alcove.load(repo/ASSETS/'models/block/arch/modern/arch_modern_icon.json')['display']
        output[PARENTS/f'arch_georgian_{suffix}.json'] = alcove.encoded(anchor)
    modern_ids = sorted(p.stem for p in (repo/ASSETS/'blockstates').glob('*_arch_modern.json'))
    if len(modern_ids)!=162:raise ValueError(f'Expected 162 Modern finish references, found {len(modern_ids)}')
    for modern_id in modern_ids:
        block_id = modern_id.replace('_arch_modern','_arch_georgian')
        aged = '_aged_' in modern_id
        resource_id = modern_id.replace('_aged_','_',1) if aged else modern_id
        source = alcove.load(repo/ASSETS/'models/block/arch/modern'/(f'{resource_id}_side_large'+('_aged' if aged else '')+'.json'))
        for suffix in models:
            child = {'parent':'erydon:block/arch/georgian/arch_georgian_'+suffix,'textures':copy.deepcopy(source['textures'])}
            output[PARENTS/f'{block_id}_{suffix}.json'] = alcove.encoded(child)
    for width,doc in references.items():output[DESIGN/f'arch_georgian_current_{width}wide.json']=alcove.encoded(mesh.clean(doc))
    for width in (4,5,6):
        output[DESIGN/f'arch_georgian_current_{width}wide_short.json']=alcove.encoded(mesh.clean(assembly(models,width,1)))
    output[ASSETS/'interaction_shapes/arch_georgian.json']=alcove.encoded({'schema':1,'units_per_block':16,'components':collisions})
    output[DESIGN/'derivation_manifest.json']=alcove.encoded({'schema':1,'master':MASTER.as_posix(),
        'master_sha256':hashlib.sha256((repo/MASTER).read_bytes()).hexdigest(),'master_cubes':len(project['elements']),
        'mirror_policy':'Authoritative LEFT; pure X mirror on RIGHT','material_finish_variants':len(modern_ids),
        'component_templates':len(models),'fixed_jamb_inner_edge_units':JAMB,'collision_grid_units':.25,
        'collision_quantization':'Bounds rounded outward on all axes','profiles':profiles,'face_separation':separation,
        'assemblies':{str(w):{'height_blocks':d['__erydon_authoring']['height_blocks'],'elements':len(d['elements'])} for w,d in references.items()}})
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check',action='store_true')
    args = parser.parse_args()
    output = generate(ROOT)
    changed = [p for p,b in output.items() if not (ROOT/p).is_file() or (ROOT/p).read_bytes()!=b]
    if args.check:
        if changed:
            print(f'Georgian arch outputs need regeneration: {len(changed)} files')
            for p in changed[:12]:print(p)
            return 1
        print(f'Georgian arch outputs match the edited BB ({len(output)} files).')
        return 0
    for path,body in output.items():
        (ROOT/path).parent.mkdir(parents=True,exist_ok=True)
        (ROOT/path).write_bytes(body)
    print(f'Generated Georgian arches: {len(changed)} changed, {len(output)} outputs; master BB untouched.')
    return 0


if __name__=='__main__':raise SystemExit(main())
