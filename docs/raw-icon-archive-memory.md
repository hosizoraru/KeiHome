# ZIP directory memory lifetime

The November 17 full import matrix exposed a Debug-host OOM while Zipflinger
allocated a 3,806,584-byte output central directory. The process had a 192 MiB
heap limit and about 2.5 MiB free at that point.

`RawIconArchive` previously retained its completed path-validation sets,
selected-entry index and source metadata while closing the output archive.
Release path sets after collision validation, and entry indexes after all raw
payloads and replacements have been written. Capture the selected-entry count
before clearing so output accounting remains unchanged.

This does not increase heap limits or relax path, CRC, expansion, entry-count
or output-size checks. Source metadata remains available until replacement
compression methods have been selected; failed output remains subject to the
existing staging cleanup.

The same initial matrix attempt also hit the separate free-space guard on
the 618 MiB iOS test source. Package/staging reclamation restored sufficient
free space before retry. Original imported sources and generated publications
were retained; no user archive was removed or offloaded.

JVM archive/security regressions and the actual native import matrix are
recorded under `.artifacts/research/monet-layer-geometry-20261117/`. This is a
memory-lifetime correction, not a claim of a controlled whole-app memory or
startup-speed improvement.
