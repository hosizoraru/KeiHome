#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reject incomplete/cross-process evidence and keep identity conclusions bounded."""
import sys
sys.dont_write_bytecode = True
from report import parse, correlate

prefix = '10-31 01:12:48.000 123 124 I KeiHomeImageTrace: KHIMG '
def event(seq, kind, **changes):
    row = dict(seq=seq, kind=kind, thr='1000', caller='2000', state='0',
               first='3001', second='0', d1=0, d2=2, dispose_pc='0', backing='4001')
    row.update(changes)
    return prefix+' '.join(f'{k}={v}' for k, v in row.items())+'\n'
start = prefix+'armed capacity=8192 window_ms=120000\n'
end = prefix+'ended emitted=3 lost=0 pending=0 full=0 clock_ok=1\n'
body = event(1, 1)+event(2, 3, state='5001', d1=1)+event(3, 2, caller='207c', d1=1)
events, _, coverage = parse(start+body+end, 123)
assert coverage['complete_window']
hit = correlate(events, 0x207c)[0]
assert hit['raw_image_update'] and hit['previous_same_address_dispose']['seq'] == 1
assert hit['previous_same_address_build']['seq'] == 2
for log in [start+body, start+body+end.replace('lost=0', 'lost=1'), start+body+end.replace('pending=0', 'pending=1'),
            start+body+end.replace('full=0', 'full=1'), start+body+end.replace('clock_ok=1', 'clock_ok=0'),
            start+body+end.replace('emitted=3', 'emitted=4'), start+body+body+end,
            start+event(2, 2, d1=1)+end]:
    assert not parse(log, 123)[2]['complete_window']
events, _, coverage = parse(start+body+end+(start+body+end).replace('123 124', '999 998'), 123)
assert len(events) == 3 and coverage['complete_window']
events, _, _ = parse(start+event(1, 1)+event(2, 2, d1=1, thr='1001')+end, 123)
assert correlate(events, 0x207c)[0]['previous_same_address_dispose'] is None
print('11 capture/completeness/correlation cases passed')
