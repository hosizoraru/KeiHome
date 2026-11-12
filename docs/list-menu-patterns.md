# List filtering and sorting

Application and resource lists follow the Apps page: standalone search, separate
OS4 filter/sort icons in the top bar, explicit current selections, and a tinted
icon while nondefault choices are active. Localized menu labels reuse the
existing browser terms where their meaning matches.

| List | Filters | Sorting |
| --- | --- | --- |
| Apps | Existing scope, origin, coverage and capability choices | Existing name, package, update and coverage choices |
| Apple mapping browser | Existing mapping/search filters | Existing name/package choices |
| Preserved icons | Saved/all and application origin | Name, package, installed/updated time and reverse |
| Installed/imported icon packs | Current/other and installed-app matches | Existing name/update/coverage choices; current stays first |
| Perfect resources | All/downloaded/not downloaded | Recommended, name, download state, known size and reverse |
| Perfect preview | Existing coverage/capability filters, installed selection where available | Source order, name/package and reverse |

Recommended Perfect resource order retains HyperOS4, HyperOSClassic, HyperOS3
and Hyper ahead of the default style. Unknown sizes remain last in either sort
direction. Reordering retains selected identities; changing a resource filter
removes only selections hidden by that filter.

Filter reset leaves query and sort choices intact. Stable identity keys keep
rows and selection from following a position when the order changes. Sorting
uses already loaded metadata and a remembered locale collator, with no package
queries or resource reads in comparison callbacks.

Settings forms and navigation hubs keep their deliberate order. Changelogs stay
in version order; online Apple results keep service relevance order.

Focused checks:

```sh
python3 scripts/checks.py list-menus --run
python3 scripts/checks.py ui-preserved-icons --serial emulator-5580 --run
python3 scripts/checks.py icon-preview-device --serial emulator-5580 --run
```
