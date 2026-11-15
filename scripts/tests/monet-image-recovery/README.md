# Monet image recovery validation

The resolver authenticates Image clone/dispose, RawImage ownership and the render
setter/dispose contract before publishing primitive code addresses and offsets.
Host mutation checks do not replace actual Dart GC or device rendering acceptance.

The guarded-clone prefix accepts closure-pool relocations only as the complete
`ADD PP page; ADD low offset; LDP entry/context; BLR entry` sequence. Register
allocation, page/low shift modes, pair width and call register remain exact.

An external `B` from this prefix is normalized only after its declared target's
32-byte return-helper prefix is authenticated. The generator rejects PC-relative
instructions in that target prefix; its Thread fields and return instructions
remain exact. Tests move this helper, reject changed target bytes and reject
malformed closure calls.

Production contracts are generated from the reviewed inputs through 9248.
9258 belongs to the runtime test inventory, independently of contract generation.
The shared runner verifies byte-identical regeneration before the requested suite:

```sh
NDK_DIR=/opt/homebrew/share/android-ndk /path/to/python \
  scripts/tests/monet-selection/run_matrix.py \
  --fixture-root /external/launchers --suite image \
  --output /tmp/image-recovery-matrix
```

For an immutable unstripped shipping payload, use
`scripts/tests/monet-selection/run_shipping.py` with a completed, passing
matrix. This tests the actual compiled bridge instructions rather than
rebuilding substitutes.
