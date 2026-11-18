# Icon pack operation progress

The root navigation overlay hosts a centered MIUIX Glass dialog at both window
sizes. It uses the opaque Glass surface to keep stage text readable over moving
lists and covers the adaptive rail as well as the content pane.

| Operation | Progress milestones |
| --- | --- |
| Import from file | Read 0%, verify 45%, refresh catalog 95%, imported 100% |
| Generate Apple pack | Inventory 0–4%, metadata 5–24%, artwork 25–87%, packaging 88–95%, generated 100% |
| Generate and import | Inventory 0–4%, light 5–32%, dark 33–55%, pairing 56–63%, adaptations 64–69%, assembly 70–74%, mask 75–79%, Monet 80–84%, custom icons 85–88%, packaging 89–91% |
| Generate Apple pack before import | Apple generation 0–39%, system preparation 40–91% |
| System handoff | Import 94%, automatic application 97%, successful result 100% |

Each stage has a fixed share of the overall percentage. The dialog also shows
the current stage's processed and total units whenever they are known.
Application matching, artwork, pairing, adaptations, archive rewrites, masks,
and Monet use item counts. Local reads, raw native module copies, and system
packaging use KiB. For stored archive entries, packaging counts both the CRC
scan and the write. A document with an unknown size shows the amount read so
far. Validation and system handoff display their current status while they wait
for results. The percentages are work milestones, and durations vary by pack.

A successful import without automatic application shows “Icon pack imported”.
Automatic application shows “Icon pack applied” when System Themes returns
success.

Preparation supports cancellation. After submission, the background action
hides the dialog for the current import and leaves the system request running.
The existing catalog remains the place to inspect confirmed, failed and
uncertain import states.

## HyperOS theme application

The HyperOS dock opens apps with freeform activity options. On Xiaomi devices
that advertise freeform window support, KeiHome requests the same window mode
for a separate task. A private, resizable activity opens the existing Theme
details screen for a result inside that task. KeiHome's main task keeps its
current size. Automatic application still uses the native single-use
authorization and result. Manual application uses the same window and the OEM
controls. Devices without freeform support and launches rejected by Android
use the existing Theme details handoff.

On HyperOS Phone with launcher 9259, the Perfect Icons pack was updated and
applied in the separate freeform task, followed by 100% on the original
KeiHome page. The large HyperOS Monet pack exposed 13,452 candidates and
reported changing counts during the 80% stage. Its existing generation memory
limit stopped that run before handoff.
