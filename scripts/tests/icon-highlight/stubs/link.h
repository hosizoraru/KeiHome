// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <stddef.h>
#include <stdint.h>

#define PT_LOAD 1
#define PF_X 1
#define PF_R 4
struct TestProgramHeader {
    uint32_t p_type, p_flags;
    uint64_t p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align;
};
struct dl_phdr_info {
    uintptr_t dlpi_addr;
    const char* dlpi_name;
    const TestProgramHeader* dlpi_phdr;
    uint16_t dlpi_phnum;
};
