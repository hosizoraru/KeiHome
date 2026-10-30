#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Symbolize one PID's KHIMG threadtime log; report correlations, never a fix verdict."""
import argparse
import bisect
import json
from pathlib import Path
import re
import sys
sys.dont_write_bytecode = True
from generate_contracts import Fixture
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN

LINE = re.compile(r'^\S+\s+\S+\s+(\d+)\s+\d+\s+[VDIWEF]\s+KeiHomeImageTrace\s*:\s*KHIMG\s+(.*)$')


def parse(log, pid):
    events, endings, installed = [], [], []
    arms = 0
    for line in log.splitlines():
        match = LINE.match(line)
        if not match or int(match[1]) != pid: continue
        text = match[2]
        words = dict(re.findall(r'(\w+)=([^\s]+)', text))
        if text.startswith('armed '): arms += 1
        elif text.startswith('ended '): endings.append(words)
        elif text.startswith('installed '): installed.append(words)
        elif text.startswith('seq='):
            decimal = {'seq', 'kind', 'd1', 'd2'}
            required = decimal | {'thr', 'caller', 'state', 'first', 'second', 'dispose_pc', 'backing'}
            if not required <= words.keys(): raise ValueError('truncated KHIMG event')
            event = {k: int(words[k], 10 if k in decimal else 16) for k in required}
            for k in ['caller_abs', 'dispose_abs']: event[k] = int(words.get(k, '0'), 16)
            events.append(event)
    if not events: raise ValueError('no KHIMG events for the requested PID/threadtime format')
    sequence_ok = [e['seq'] for e in events] == list(range(1, len(events)+1))
    complete = arms == 1 and len(endings) == 1 and sequence_ok
    if complete:
        end = endings[0]
        complete = (end.get('lost') == '0' and end.get('pending') == '0' and end.get('full') == '0'
                    and end.get('clock_ok') == '1' and int(end.get('emitted', '-1')) == len(events))
    return events, installed, {'complete_window': complete, 'sequence_ok': sequence_ok,
        'arms': arms, 'endings': endings,
        'limitation': 'GC can move/reuse scalar addresses. These are temporal address correlations, not persistent object IDs.'}


def correlate(events, raw_return):
    disposals, builds, failures = {}, {}, []
    for event in events:
        key = (event['thr'], event['first'])
        if event['kind'] == 1 and event['first']:
            disposals[key] = event
        elif event['kind'] == 3:
            for slot in ['first', 'second']:
                if event[slot]: builds[(event['thr'], event[slot])] = event
        elif event['kind'] == 2 and event['d1'] == 1:
            failures.append({'clone': event, 'raw_image_update': event['caller'] == raw_return,
                'previous_same_address_dispose': disposals.get(key),
                'previous_same_address_build': builds.get(key)})
    return failures


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--library', type=Path, required=True)
    p.add_argument('--log', type=Path, required=True)
    p.add_argument('--pid', type=int, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    fixture = Fixture(a.library)
    events, installed, coverage = parse(a.log.read_text(errors='replace'), a.pid)
    names = {pc: name for name, spans in fixture.symbols.items() for pc, _ in spans if name}
    sizes = {pc: size for spans in fixture.symbols.values() for pc, size in spans}
    ordered = sorted(names)
    def symbol(pc):
        i = bisect.bisect_right(ordered, pc)-1
        if i >= 0 and pc < ordered[i]+sizes[ordered[i]]:
            return f'{names[ordered[i]]}+0x{pc-ordered[i]:x}'
        return f'unnamed RVA 0x{pc:x}'
    roles = {'dispose': 'Image.dispose', 'clone': 'Image.clone', 'build': '_MonochromeIconWidgetState.build',
             'clear': 'MonochromeIconCache.clear', 'put': 'MonochromeIconCache.put', 'raw_update': 'RawImage.updateRenderObject'}
    if len(installed) != 1 or any(int(installed[0].get(k, '-1'), 16) != fixture.role(v)[0] for k, v in roles.items()):
        raise ValueError('missing/ambiguous installed metadata or wrong library for this capture')
    for event in events:
        event['caller_symbol'] = symbol(event['caller']-4) if event['caller'] else 'outside libapp'
    dispatches = []
    cs = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    for pc in sorted({e['dispose_pc'] for e in events if e['dispose_pc']}):
        dispatches.append({'rva': hex(pc), 'symbol': symbol(pc),
            'first_instructions': [i.mnemonic+' '+i.op_str for i in cs.disasm(fixture.read(pc, 16), pc)]})
    failures = correlate(events, fixture.role('RawImage.updateRenderObject')[0]+0x7c)
    report = {'pid': a.pid, 'library': str(fixture.path), 'coverage': coverage,
        'events': len(events), 'observed_state_dispose_targets': dispatches,
        'dispose_targets_outside_library': sorted({hex(e['dispose_abs']) for e in events
            if e['dispose_abs'] and not e['dispose_pc']}),
        'disposed_clone_correlations': failures,
        'verdict': 'Diagnostic evidence only. Missing correlation does not rule out cache ownership, GC relocation, eviction, or an event outside the capture.'}
    a.output.write_text(json.dumps(report, indent=2)+'\n')
    print(f'{len(events)} events, {len(failures)} disposed clone inputs, complete_window={coverage["complete_window"]}')


if __name__ == '__main__': main()
