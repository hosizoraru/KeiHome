#!/usr/bin/env python3
"""Generate reviewed instruction contracts from external fixture oracles."""
import argparse
import importlib.util
import json
from pathlib import Path
import struct
spec=importlib.util.spec_from_file_location('fixture',Path(__file__).parents[1]/'icon-highlight/elf_fixture.py')
elf=importlib.util.module_from_spec(spec);spec.loader.exec_module(elf)
def mask(w,role=''):
    # Paint dispatch checks name native render classes. Their CIDs are linked
    # to the creation allocator by the resolver, never admitted as constants.
    if role=='StackPaint' and w&0xffe0001f==0xd2800011:return 0xffe0001f
    if role in ('RecentsSuperUpdate','RecentsRectUpdate') and w&0xffe0001f==0xd2800011:return 0xffe0001f
    if (role in ('RecentsStacked','RecentsTaskRadius') or role=='RecentsRadiusCaller' and w&31==3) and w&0xffe01fe0==0x1e601000:return 0xffe01fff
    if w&0xfc000000==0x94000000:return 0xfc000000
    if w&0xffc00000 in (0xf9400000,0xfd400000):return 0xffc003ff
    if w&0xffc00000==0x91400000 and w>>5&31==27:return 0xffc003ff
    return 0xffffffff

def body_masks(words, role):
    result = [mask(w, role) for w in words]
    if role == 'Outer':
        for i in range(2, len(words)-2):
            if (words[i-2:i] == (0xf85ff004, 0xd34c7c84)
                and words[i] & 0xffe0001f == 0xd2800011
                and words[i+1:i+3] == (0xeb11009f, 0x540000c0)):
                result[i] = 0xffe0001f
    if role == 'ScrollBuild':
        for offset in (0x24, 0x4c):
            assert words[offset//4] & 0xff00001f == 0x54000000
            result[offset//4] = 0xff00001f
    for i in range(1, len(words) - 2):
        reg = words[i] & 31
        # Retain the complete PP-derived closure entry/context load and call.
        # Only its address's low immediate can move independently of the page.
        if (reg not in (27, 31) and
            words[i-1] & 0xffc003ff == 0x91400360 | reg and
            words[i] & 0xffc003ff == 0x91000000 | reg << 5 | reg and
            words[i+1] == 0xa940141e | reg << 5 and
            words[i+2] == 0xd63f03c0):
            result[i] = 0xffc003ff
    return result

def branch(im,pc,link=True):
 w=struct.unpack('<I',im.read(pc,4))[0];assert w&0xfc000000==(0x94000000 if link else 0x14000000)
 return pc+(((w&0x3ffffff)^0x2000000)-0x2000000)*4

def clip_identity_oracle(im,clip,super_create,rect_create,glass):
 """Independently bind caller allocation CIDs to named createRenderObject.

 The compressed snapshot dispatch is a generator-only oracle. Production uses
 mapped instruction contracts and linked fields/calls, never this inventory.
 """
 b=im.data
 def read(p,marker=128):
  v=0;shift=0
  while b[p]<128:
   v|=b[p]<<shift;p+=1;shift+=7;assert shift<=56
  return v|((b[p]-marker)<<shift),p+1
 start=next(a for a in range(0x350,0x10000,16) if b[a:a+4]==bytes.fromhex('f5f5dcdc'))
 end=start+struct.unpack_from('<Q',b,start+4)[0]+4
 header=b.index(b'\0',start+52)+1;p=header;head=[]
 for _ in range(5):v,p=read(p);head.append(v)
 ro=((end+63)&~63)+head[4]+16
 _,total,first,pad=struct.unpack_from('<IIII',b,ro);assert total-first==head[3] and pad==0
 ds=im.by_name['.dynsym'];strings=im.section('.dynstr');base=None
 for at in range(ds[4],ds[4]+ds[5],ds[9]):
  n,_,_,_,value,_=struct.unpack_from('<IBBHQQ',b,at)
  if strings[n:].split(b'\0',1)[0]==b'_kDartIsolateSnapshotInstructions':base=value
 assert base is not None
 pcs=[base+struct.unpack_from('<I',b,ro+16+i*8)[0] for i in range(total)]
 rev=[];p=end;expanded=0;candidates=[]
 while p>max(header,end-500000):
  stop=p;p-=1;assert b[p]>=128
  while p>header and b[p-1]<128:p-=1
  try:sv,q=read(p,192);uv,q=read(p)
  except AssertionError:break
  assert q==stop
  rev.append((p,stop,sv,uv))
  if len(rev)>=3:
   token=rev[-3][2];expanded+=token if 0<token<=63 else 1
   if uv==expanded and 77000<=uv<=500000 and rev[-2][3]<head[1]:candidates.append((uv,rev[-2][1]))
 tables=[]
 for length,p in candidates:
  recent=[None]*64;rix=0;table=[];value=None
  try:
   while len(table)<length:
    v,p=read(p,192)
    if v==0:value=0;table.append(value)
    elif v<0:
     assert -64<=v<=-1 and recent[~v] is not None
     value=recent[~v];table.append(value)
    elif v<=63:table.extend([value]*v)
    else:
     assert 1<=v-64<=total
     value=pcs[v-65];recent[rix]=value;rix=(rix+1)&63;table.append(value)
   assert len(table)==length and p==end;tables.append(table)
  except (AssertionError,IndexError):pass
 assert len(tables)==1;table=tables[0]
 [(mount,z)]=im.syms['RenderObjectElement.mount'];words=struct.unpack('<'+'I'*(z//4),im.read(mount,z))
 biases=[(w>>5)&65535 for i,w in enumerate(words[:-2]) if w&0xffe0001f==0xd2800011 and words[i+1]==0x8b11001e and words[i+2]==0xf87e7abe]
 cids=[]
 for pc in (super_create,rect_create):
  # The reviewed adaptive caller has two allocator calls. Identify each by
  # serialized dispatch, not by allocator address ordering or tag proximity.
  found=[]
  for i,w in enumerate(struct.unpack('<'+'I'*(clip[1]//4),im.read(clip[0],clip[1]))):
   if w&0xfc000000!=0x94000000:continue
   target=branch(im,clip[0]+i*4);a,c=struct.unpack('<II',im.read(target,8))
   if a&0xffe0001f!=0xd2800002 or c&0xffe0001f!=0xf2a00002:continue
   tag=((a>>5)&65535)|((c>>5)&65535)<<16;cid=tag>>12
   if any(4096+bias+cid<len(table) and table[4096+bias+cid]==pc for bias in biases):found.append(cid)
  assert len(found)==1,(hex(pc),found);cids.append(found[0])
 assert cids[0]!=cids[1]
 # The original Glass child is stateful. Decode the createState selector from the
 # StatefulElement constructor rather than reusing createRenderObject's bias.
 [(at,z)]=im.syms['StatefulWidget.createElement'];words=struct.unpack('<'+'I'*(z//4),im.read(at,z))
 calls=[at+i*4 for i,w in enumerate(words) if w&0xfc000000==0x94000000]
 ctor=branch(im,calls[-1]);words=struct.unpack('<64I',im.read(ctor,256))
 biases=[(w>>5)&65535 for i,w in enumerate(words[:-2]) if w&0xffe0001f==0xd2800011 and words[i+1]==0x8b11001e and words[i+2]==0xf87e7abe]
 a,c=struct.unpack('<II',im.read(branch(im,glass+0x138),8))
 assert a&0xffe0001f==0xd2800002 and c&0xffe0001f==0xf2a00002
 tag=((a>>5)&65535)|((c>>5)&65535)<<16;cid=tag>>12
 assert (tag>>8&15)*16==32
 assert any(4096+bias+cid<len(table) and table[4096+bias+cid]==im.syms['_DockGlassWidget.createState'][0][0] for bias in biases)
 cids.append(cid)
 key_caller=im.syms['IconCacheV2.desktopIconCacheKey'][0][0]
 a,c=struct.unpack('<II',im.read(branch(im,key_caller+0x88),8))
 assert a&0xffe0001f==0xd2800002 and c&0xffe0001f==0xf2a00002
 key_tag=((a>>5)&65535)|((c>>5)&65535)<<16
 assert key_tag&0xfff==0x11c
 assert table[4096+(key_tag>>12)]==im.syms['ValueKey.=='][0][0]
 assert next(i for i in range(4096,len(table)) if table[i]==im.syms['ValueKey.=='][0][0])==4096+(key_tag>>12)
 assert branch(im,key_caller+0x9c)==im.syms['ValueKey.toString'][0][0]
 cids.append(key_tag>>12)
 # Find the native search entry wrapper by its createState dispatch identity
 # and its two calls to the source-owned center resolver, not allocator order.
 unlock=im.syms['UnlockAnimWidget.createState'][0][0]
 center=im.syms['SearchBarGetXController.getCenterLocation'][0][0]
 item_pc,item_size=im.syms['_HotseatWidgetState._buildItems'][0]
 allocators=[]
 for i,w in enumerate(struct.unpack('<'+'I'*(item_size//4),im.read(item_pc,item_size))):
  if w&0xfc000000!=0x94000000:continue
  target=branch(im,item_pc+i*4);a,c=struct.unpack('<II',im.read(target,8))
  if a&0xffe0001f!=0xd2800002 or c&0xffe0001f!=0xf2a00002:continue
  tag=((a>>5)&65535)|((c>>5)&65535)<<16
  if any(4096+bias+(tag>>12)<len(table) and table[4096+bias+(tag>>12)]==unlock for bias in biases):
   allocators.append(target)
 assert len(allocators)==1
 callers=[]
 for lo,hi,ex in im.ranges:
  if not ex:continue
  for i,w in enumerate(struct.unpack('<'+'I'*((hi-lo)//4),im.read(lo,(hi-lo)//4*4))):
   at=lo+i*4
   if w&0xfc000000==0x94000000 and branch(im,at)==allocators[0] and at>=0xc4:
    first,second=struct.unpack('<I',im.read(at-0xc4,4))[0],struct.unpack('<I',im.read(at-0xa0,4))[0]
    if first&0xfc000000==second&0xfc000000==0x94000000 and branch(im,at-0xc4)==center and branch(im,at-0xa0)==center:callers.append(at)
 assert len(callers)==1,callers
 return cids,callers[0]

def main():
 p=argparse.ArgumentParser();p.add_argument('--fixtures',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
 p.add_argument('--launcher-root',type=Path,default=Path(__file__).resolve().parents[3]/'.artifacts/inputs/launchers');a=p.parse_args()
 meta=json.loads(a.fixtures.read_text());groups={}
 for version,entry in meta.items():
  im=elf.Image(a.launcher_root/version/'libapp.so')
  roles={'Phone':(int(entry['inner'],16),int(entry.get('phone_size','0x4c0'),16)),
         'Outer':(int(entry['outer'],16),0x400)}
  [(at,size)]=im.syms['HotseatLayerGetxController._getTotalHotSeatsMarginBottom'];roles['BottomMargin']=(at,size)
  for role,name in [('Background','_HotseatWidgetState._buildBackground'),('CellWidth','GridController.dockCellWidth'),('MaxCount','DeviceConfig.hotSeatMaxCount'),('SizedBox','_CupertinoActivityIndicatorState.build'),('Positioned','_AnimatedPositionedState.build'),('Corner','DeviceConfig.screenCornerRadius='),('MaterialFactory','_DockBackgroundViewState._buildStrategy'),('Mode','BlurCapabilityCheckerImpl.computeMode'),('Elderly','LauncherModeController.isElderlyManMode'),('Sync','_HotseatWidgetState._syncRemoveAnimationState'),('Ink','HotseatLayerGetxController.backgroundLayout'),('CellHeight','GridController.dockCellHeight'),('NoWord','Utilities.isNoWordModel'),('StackPaint','RenderStack.paint'),('PushClipRect','PaintingContext.pushClipRect'),('StackCreate','Stack.createRenderObject')]:
   [(at,size)]=im.syms[name];roles[role]=(at,size)
  for role,name in [('RecentsEnabled','RecentsSuperellipseConfig.isEnabled'),('RecentsEligibility','RecentsSuperellipseConfig._resolveEligibility'),('RecentsForRadius','RecentsSuperellipseConfig.isEnabledForRadius'),('RecentsFor','RecentsSuperellipseConfig.isEnabledFor'),('RecentsClip','_recentsAdaptiveClip'),('RecentsStacked','RecentUtils.getStackedCornerRadius'),('RecentsTaskRadius','RecentUtils.getTaskCornerRadius'),('RecentsRadiusCaller','_TaskCardFlightFrameState._updateFlightCornerRadius'),('RecentsSuperCreate','ClipRSuperellipse.createRenderObject'),('RecentsRectCreate','ClipRRect.createRenderObject'),('RecentsSuperUpdate','ClipRSuperellipse.updateRenderObject'),('RecentsRectUpdate','ClipRRect.updateRenderObject')]:
   [(at,size)]=im.syms[name];roles[role]=(at,size)
  [(at,size)]=[(x,z) for x,z in im.syms['Radius.*'] if z==0x44];roles['RecentsRadiusScale']=(at,size)
  for role,name in [('GlassDiagnosticInterpolate','_StringBase._interpolate'),('GlassDiagnosticLog','_LoggerImpl.logWithTag'),('GlassBuild','GlassOs4Background.build'),('GlassStatelessBuild','StatelessElement.build'),('GlassDockScope','DockScope.isInDockWindowOf'),('GlassChildCreate','_DockGlassWidget.createState'),('ValueKeyCaller','IconCacheV2.desktopIconCacheKey'),('ValueKeyEquals','ValueKey.=='),('ValueKeyString','ValueKey.toString'),('ValueKeyRuntimeType','Object._haveSameRuntimeType')]:
   [(at,size)]=im.syms[name];roles[role]=(at,size)
  key_alloc=branch(im,roles['ValueKeyCaller'][0]+0x88);key_generic=branch(im,key_alloc+8,False)
  words=struct.unpack('<64I',im.read(key_generic,256));roles['ValueKeyAllocator']=(key_generic,(words.index(0xd65f03c0)+1)*4)
  for role,name in [('RecentsRenderMount','RenderObjectElement.mount'),('RecentsStatefulCreate','StatefulWidget.createElement')]:
   [(at,size)]=im.syms[name];roles[role]=(at,size)
  at,size=roles['RecentsStatefulCreate'];calls=[at+i*4 for i,w in enumerate(struct.unpack('<'+'I'*(size//4),im.read(at,size))) if w&0xfc000000==0x94000000]
  ctor=branch(im,calls[-1]);words=struct.unpack('<256I',im.read(ctor,1024));roles['RecentsStatefulConstructor']=(ctor,(words.index(0xd65f03c0)+1)*4)
  for role in ('RecentsSuperCreate','RecentsRectCreate'):
   at,size=roles[role];calls=[at+i*4 for i,w in enumerate(struct.unpack('<'+'I'*(size//4),im.read(at,size))) if w&0xfc000000==0x94000000]
   ctor=branch(im,calls[-1]);words=struct.unpack('<128I',im.read(ctor,512));length=(words.index(0xd65f03c0)+1)*4
   roles[role.replace('Create','Constructor')]=(ctor,length)
  cids,unlock_call=clip_identity_oracle(im,roles['RecentsClip'],roles['RecentsSuperCreate'][0],roles['RecentsRectCreate'][0],roles['GlassBuild'][0])
  print(version,'clip / original Glass child / ValueKey CIDs',cids,'native search entry',hex(unlock_call))
  roles['UnlockSearch']=(unlock_call-0xc4,0x104)
  for role,name in [('UnlockCreate','UnlockAnimWidget.createState'),('UnlockLocation','_UnlockWidgetState._resolveCellLocationInfo'),('UnlockCenter','SearchBarGetXController.getCenterLocation')]:
   [(at,size)]=im.syms[name];roles[role]=(at,size)
  create,size=roles['UnlockCreate'];ctor=branch(im,create+0x24)
  words=struct.unpack('<512I',im.read(ctor,2048))
  roles['UnlockConstructor']=(ctor,(words.index(0xd65f03c0)+1)*4)
  # The unnamed RenderStack constructor is the final direct call from the
  # symbol-oracle creation method. Production follows this same call edge.
  create,size=roles['StackCreate'];assert size==0x94
  call=create+0x80;w=struct.unpack('<I',im.read(call,4))[0]
  assert w&0xfc000000==0x94000000
  target=call+(((w&0x3ffffff)^0x2000000)-0x2000000)*4
  roles['StackRenderConstructor']=(target,0x124)
  [(at,size)]=im.syms['ScrollableState.build'];assert size>=0xc4;roles['ScrollBuild']=(at,0xc4)
  if 'LauncherStateManager.isEffectiveNoWordModel' in im.syms:
   [(at,size)]=im.syms['LauncherStateManager.isEffectiveNoWordModel'];roles['NoWordEffective']=(at,size)
  for role,(at,size) in roles.items():
   words=struct.unpack('<'+'I'*(size//4),im.read(at,size));pattern=tuple((w&m,m) for w,m in zip(words,body_masks(words,role)))
   bucket=groups.setdefault(role,[])
   if pattern not in bucket:bucket.append(pattern)
 lines=['// SPDX-License-Identifier: Apache-2.0','// Reviewed mapped-code contracts. Symbols and addresses are generator oracles only.']
 for role,variants in groups.items():
  print(role,[len(v) for v in variants])
  for i,v in enumerate(variants):
   lines.append(f'static constexpr DockWord kDock{role}{i}[] = {{')
   lines.extend(f'    {{0x{w:08x}u, 0x{m:08x}u}},' for w,m in v);lines.append('};')
  lines.append(f'static constexpr DockPattern kDock{role}[] = {{')
  lines.extend(f'    {{kDock{role}{i}, sizeof(kDock{role}{i}) / sizeof(DockWord)}},' for i in range(len(variants)));lines.append('};')
 a.output.write_text('\n'.join(lines)+'\n')
if __name__=='__main__':main()
