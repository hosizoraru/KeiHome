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
