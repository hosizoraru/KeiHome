// SPDX-License-Identifier: Apache-2.0
#include "monet_image_trace.h"
    .text
    .global test_original
test_original:
    brk #0
    .bss
    .p2align 6
    .global kh_image_trace_control
kh_image_trace_control: .skip 16
    .global kh_image_trace_records
kh_image_trace_records: .skip KH_IMAGE_TRACE_RECORD_SIZE * KH_IMAGE_TRACE_CAPACITY
    .global kh_image_trace_image_cid
kh_image_trace_image_cid: .skip 4
    .global kh_image_trace_dispose_selector
kh_image_trace_dispose_selector: .skip 4
    .p2align 3
    .global kh_image_trace_raw_update_return
kh_image_trace_raw_update_return: .skip 8
    .global kh_image_trace_dispose_original
kh_image_trace_dispose_original: .skip 8
    .global kh_image_trace_clone_original
kh_image_trace_clone_original: .skip 8
    .global kh_image_trace_build_original
kh_image_trace_build_original: .skip 8
    .section .note.GNU-stack,"",%progbits
