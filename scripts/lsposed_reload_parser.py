"""Pure parsing and inode/offset planning for LSPosed reload evidence."""
from datetime import datetime, timedelta
import re


HEADER = re.compile(
    r"^\[\s*(?P<time>\d{4}-\d\d-\d\dT[\d:.]+)\s+\d+:\s*"
    r"(?P<pid>\d+):\s*(?P<tid>\d+)\s+\w/(?P<tag>\S+)\s*\]\s*(?P<body>.*)$"
)
CONTEXT = re.compile(
    r"^\((?P<process>[^)]+)\)\[framework,LSPosedContext,[^]]+\]\s*(?P<message>.*)$"
)
OUTCOME = re.compile(
    r"^Auto hot reload (?P<result>succeeded|failed) for (?P<module>\S+) in "
    r"(?P<process>[^/\s]+)/(?P<pid>\d+)(?::\s*(?P<detail>.*))?$"
)
RECLAIM_REASON = r"umms_(?:selfcheck|pressure|launch)"
KILL = re.compile(
    r"^(?P<time>\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+"
    r"\d+\s+\d+\s+I ActivityManager:\s+Killing (?P<pid>\d+):"
    rf"(?P<process>[^/\s]+)/\S+ \(adj -?\d+\): (?P<reason>{RECLAIM_REASON})\b"
)
EVENT_KILL = re.compile(
    r"^(?P<time>\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+"
    r"\d+\s+\d+\s+I\s+am_kill\s*:\s*\[\d+,(?P<pid>\d+),"
    rf"(?P<process>[^,]+),-?\d+,(?P<reason>{RECLAIM_REASON}),[^\]]+\]"
)


def plan_log_reads(previous, current):
    """Return bounded byte ranges; follow a renamed inode rather than replay it."""
    old_inodes = {inode: size for inode, size in previous.values()}
    current_inodes = {inode for inode, _ in current.values()}
    issues = [
        f"log inode disappeared before its final size could be checked: {inode}"
        for inode in sorted(old_inodes.keys() - current_inodes)
    ]
    reads = []
    for path, (inode, size) in sorted(current.items()):
        offset = old_inodes.get(inode, 0)
        if size < offset:
            issues.append(f"log truncated: {path} inode={inode}")
            offset = 0
        if size > offset:
            reads.append({
                "path": path, "inode": inode, "offset": offset,
                "length": size - offset, "reset": size < old_inodes.get(inode, 0),
            })
    return reads, issues


def parse_reloads(text, module, *, capture_issues=(), system_log=""):
    """Keep failures and missing evidence distinct from observed successful pairs.

    LSPosed emits no authoritative target-count manifest in this log format.
    Even matched start/outcome pairs establish only the observed population.
    """
    parsed = []
    for line in text.splitlines():
        match = HEADER.match(line)
        if match:
            parsed.append((match["time"], line, match.groupdict()))
    parsed.sort(key=lambda row: row[0])
    contexts = {}
    targets = {}
    issues = list(capture_issues)

    def target(process, pid):
        key = process, int(pid)
        return targets.setdefault(key, {
            "process": process, "pid": int(pid), "starts": [], "outcomes": [],
            "status": "unknown", "detail": None, "failureReason": "unknown",
        })

    for timestamp, line, header in parsed:
        body = header["body"]
        if header["tag"] == "LSPosedFramework":
            context = CONTEXT.match(body)
            if not context:
                continue
            key = header["pid"], header["tid"], context["process"]
            message = context["message"]
            loading = re.match(r"Loading module (\S+) from ", message)
            if loading:
                contexts[key] = loading[1]
            elif message == "Start hot reload":
                if key not in contexts:
                    issues.append(f"unattributed start (missing module context): {line}")
                elif contexts[key] == module:
                    row = target(context["process"], header["pid"])
                    event = {"time": timestamp, "line": line}
                    if event not in row["starts"]:
                        row["starts"].append(event)
        elif header["tag"] == "LSPosedService":
            outcome = OUTCOME.match(body)
            if outcome and outcome["module"] == module:
                row = target(outcome["process"], outcome["pid"])
                detail = outcome["detail"]
                status = re.search(r"(?:^|,\s*)status=(\d+)", detail or "")
                event = {
                    "time": timestamp,
                    "result": "success" if outcome["result"] == "succeeded" else "failed",
                    "status": int(status[1]) if status else None,
                    "detail": detail, "line": line,
                }
                if event not in row["outcomes"]:
                    row["outcomes"].append(event)
            elif body.startswith("Auto hot reload ") and re.search(
                rf"\bfor {re.escape(module)}(?:\s|$)", body
            ):
                issues.append(f"unparsed module outcome: {line}")

    kills = []
    for line in system_log.splitlines():
        match = KILL.match(line) or EVENT_KILL.match(line)
        if match:
            kills.append((match, line))
    for row in targets.values():
        failures = [event for event in row["outcomes"] if event["result"] == "failed"]
        if failures:
            row["status"] = "failed"
            row["detail"] = failures[-1]["detail"]
            # A policy reclaim is not proof of a kernel OOM or memory exhaustion.
            for failure in failures:
                end = datetime.fromisoformat(failure["time"])
                begin = end - timedelta(seconds=5)
                starts = [datetime.fromisoformat(event["time"]) for event in row["starts"]
                          if event["time"] <= failure["time"]]
                if starts:
                    begin = max(begin, max(starts))
                for kill, line in kills:
                    when = datetime.fromisoformat(kill["time"])
                    if (int(kill["pid"]) != row["pid"] or kill["process"] != row["process"]
                            or not begin <= when <= end):
                        continue
                    row["failureReason"] = "system_reclaimed"
                    row["systemReclaimEvidence"] = {
                        "time": kill["time"], "pid": int(kill["pid"]),
                        "process": kill["process"], "reason": kill["reason"],
                        "line": line,
                    }
        elif len(row["outcomes"]) == len(row["starts"]) == 1:
            if row["starts"][0]["time"] <= row["outcomes"][0]["time"]:
                row["status"] = "success"
            else:
                issues.append(f"outcome precedes start: {row['process']}/{row['pid']}")
        if not row["starts"]:
            issues.append(f"missing start: {row['process']}/{row['pid']}")
        if not row["outcomes"]:
            issues.append(f"missing outcome: {row['process']}/{row['pid']}")
        if len(row["starts"]) > 1 or len(row["outcomes"]) > 1:
            issues.append(f"multiple reload attempts: {row['process']}/{row['pid']}")
    rows = sorted(targets.values(), key=lambda row: (row["process"], row["pid"]))
    counts = {state: sum(row["status"] == state for row in rows)
              for state in ("success", "failed", "unknown")}
    if not rows:
        issues.append("no module reload targets observed")
    issues = list(dict.fromkeys(issues))
    return {
        "schemaVersion": 1, "module": module,
        "populationCompleteness": "unknown",
        "populationDetail": "No authoritative target-count manifest in LSPosed logs.",
        "observedCompleteness": "unknown" if issues else "complete",
        "result": "failed" if counts["failed"] else (
            "unknown" if issues or counts["unknown"] else "observed_success"
        ),
        "counts": counts, "targets": rows, "issues": issues,
    }
