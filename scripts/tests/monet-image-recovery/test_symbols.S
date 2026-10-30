// SPDX-License-Identifier: Apache-2.0
#include "monet_image_recovery.h"
    .text
    .global test_original
test_original:
    brk #0
    .bss
    .p2align 6
    .global kh_image_recovery_config
kh_image_recovery_config: .skip KH_IR_CONFIG_SIZE
    .global kh_image_recovery_counts
kh_image_recovery_counts: .skip 8
    .p2align 3
    .global kh_image_recovery_original
kh_image_recovery_original: .skip 8
    .section .note.GNU-stack,"",%progbits
