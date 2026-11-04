// SPDX-License-Identifier: Apache-2.0
#include "dock_glass_bridge.S"
    .section .rodata.dock_glass_bridge,"a",%progbits
#include "dock_glass_modes.inc"
    .global kh_dock_payload_end
    .hidden kh_dock_payload_end
kh_dock_payload_end:
