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
    if w&0xfc000000==0x94000000:return 0xfc000000
    if w&0xffc00000 in (0xf9400000,0xfd400000):return 0xffc003ff
    if w&0xffc00000==0x91400000 and w>>5&31==27:return 0xffc003ff
    return 0xffffffff

def main():
 p=argparse.ArgumentParser();p.add_argument('--fixtures',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 meta=json.loads(a.fixtures.read_text());groups={}
 for version,entry in meta.items():
  im=elf.Image(a.fixtures.parents[2]/'inputs'/'launchers'/version/'libapp.so')
  roles={'Phone':(int(entry['inner'],16),0x4c0),'Outer':(int(entry['outer'],16),0x400)}
  for role,name in [('Background','_HotseatWidgetState._buildBackground'),('CellWidth','GridController.dockCellWidth'),('MaxCount','DeviceConfig.hotSeatMaxCount'),('SizedBox','_CupertinoActivityIndicatorState.build'),('Positioned','_AnimatedPositionedState.build'),('Corner','DeviceConfig.screenCornerRadius='),('MaterialFactory','_DockBackgroundViewState._buildStrategy'),('Mode','BlurCapabilityCheckerImpl.computeMode'),('Elderly','LauncherModeController.isElderlyManMode'),('Sync','_HotseatWidgetState._syncRemoveAnimationState'),('Ink','HotseatLayerGetxController.backgroundLayout'),('CellHeight','GridController.dockCellHeight'),('NoWord','Utilities.isNoWordModel'),('StackPaint','RenderStack.paint'),('PushClipRect','PaintingContext.pushClipRect'),('StackCreate','Stack.createRenderObject')]:
   [(at,size)]=im.syms[name];roles[role]=(at,size)
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
   words=struct.unpack('<'+'I'*(size//4),im.read(at,size));pattern=tuple((w&mask(w,role),mask(w,role)) for w in words)
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
