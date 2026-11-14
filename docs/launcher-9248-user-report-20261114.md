# Launcher 9248: public 1.9.0 capability-unavailable report

## Report and interpretation

Inputs: `LSPosed_20261114_094554.zip`, `LSPosed_20261114_094604.zip`,
and the user's status screenshot. The archives contain overlapping historical
entries, not two independent reproductions.

The screenshot identifies:

- Public build `109000394`, `1.9.0+g70ea216.b0394`.
- Home 9248, Rust 3.9.0, Flutter 3.38.3.
- Launcher PID `32556`, with `native Monet capability unavailable`.
- A loaded system scope reporting the same build.

In the supplied verbose log
`verbose_2026-11-14T07:47:10.535581.log`, line 19972 records
`08:55:35.647` loading `os.kei.hyper.home`'s
`libkeihome_hyos_lsp.so` into Launcher PID `32556`. Thus the evidence does not
show an entirely missing module injection.

`NativeMonetClient` emits the reported diagnostic after authenticating the
current target and reading a greeting without the required Monet capability
bit. This differs from an absent socket, a timeout or an expired report.
The screenshot's "35 minutes ago" belongs to the icon-refresh receipt; it does
not independently measure the duration of native initialization.

The supplied logs do not retain the native resolver rejection for this
occurrence. The attribution below combines the observed public build with the
matching source and retained 9248 regression, rather than claiming that the
user log contains the rejection.

## Existing fix and current verification

The selection-pattern header in public source `70ea216` is identical to the
one immediately before fix `e1a3d45`. The retained pre-fix 9248 host check
rejects its native image with:

> missing/ambiguous selection instruction/call graph

Fix `e1a3d454a131bfd19bea138eae8bd6d4eac373f5` adds the 9248 selection and
image-recovery graph contracts. `afc4d7c` covers its Dock geometry;
`81ccbbc` retains the permanent regression fixtures. These changes already
precede current Release `109000495`.

| Payload | Packaged ARM64 native SHA-256 |
| --- | --- |
| Public `109000394` | `e886da0b96e881c24e0e42e70acb9e7e5928bc28268df23ac9317eb850f0f2ec` |
| Current `109000495` | `e37d921c3fa20af6da2a507127f7c12452b8a4f94e516c6486c83e8753714b8a` |

The current packaged hash matches the native payload validated during the
original 9248 work. That evidence includes exact fixture checks and a live
9248 AVD run installing Monet, labels and Dock. See
[`launcher-9248-compatibility.md`](launcher-9248-compatibility.md).

For this report, the current production selection resolver was checked again
against the retained 9248 and 9239 ELF/debug pairs. Both passed the focused
host harness and independent ABI audit. This harness runs entirely on the
host; it does not connect to or change a device.

The report strongly matches the missing 9248 support in the public native
payload. It does not establish that every APK called "9248" contains the same
native image: the reporter's actual `libapp.so` was not supplied or pulled.
Upgrade acceptance on that user's device remains pending.

## Activation guidance and copy correction

An APK update or API 102 Java handoff does not replace the native image
already mapped in a running Launcher. A user upgrading from the old payload
must let Launcher start a new process once. A device restart is an alternative;
readiness must then be checked after unlocking and entering HOME.

The current capability-missing note still advised waiting for automatic hot
reload. This report corrects the English, Simplified Chinese and Japanese
copy: after an upgrade, restart Launcher once or restart the device and unlock
to HOME; if capability remains unavailable, collect LSPosed logs. This is
guidance, not an automatic restart action.

When following up, verify the new running module build, a new Launcher PID or
start identity, and the resident native Build ID/capability greeting. If it
still fails, preserve the native initialization logs and obtain the actual
running Launcher ELF identity before adding another compatibility profile.

## Retained evidence

`.artifacts/research/launcher-9248-user-logs-20261114/` retains archive hashes,
deduplicated filtered log entries, the status screenshot, `assessment.json`,
and `current-selection-check/report.json`. Private user logs are not checked
into Git.
