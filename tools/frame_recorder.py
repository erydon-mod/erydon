"""Queue and inspect the optional client frame recorder; never control the game."""
import argparse
import csv
import json
import math
import os
from pathlib import Path
import re
import statistics
import tempfile


ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,47}\Z")


def check_id(value):
    if not ID.fullmatch(value) or re.fullmatch(r"CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]", value, re.I):
        raise ValueError("ID must be 1-48 letters, digits, underscores or hyphens; Windows device names are reserved")
    return value


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def queue(instance, identifier, warmup=5, duration=60, timeout=300, gpu_stages=False):
    identifier = check_id(identifier)
    if not instance.is_dir():
        raise ValueError("Instance directory does not exist")
    if not 2 <= warmup <= 60 or not 1 <= duration <= 120 or not duration + warmup + 5 <= timeout <= 600:
        raise ValueError("Use warmup 2-60s, capture 1-120s and timeout warmup+capture+5 through 600s")
    root = instance / "erydon-frame-recorder"
    request = root / "requests" / (identifier + ".json")
    if (root / "results" / identifier).exists() or request.exists():
        raise ValueError("This ID already exists; use a new ID")
    request.parent.mkdir(parents=True, exist_ok=True)
    payload = dict(id=identifier, warmupSeconds=warmup, captureSeconds=duration, timeoutSeconds=timeout)
    if gpu_stages:
        payload["gpuStages"] = True
    descriptor, temporary = tempfile.mkstemp(prefix=".request-", suffix=".tmp", dir=request.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(payload, stream, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        # Publish a complete file atomically without replacing a concurrent request.
        os.link(temporary, request)
    finally:
        Path(temporary).unlink(missing_ok=True)
    return {"queued": str(request), "id": identifier,
            "note": "The loaded companion waits for a focused, stable world. It never opens or focuses Minecraft."}


def inspect(instance, identifier=None):
    root = instance / "erydon-frame-recorder"
    heartbeat = root / "heartbeat.json"
    result = {"heartbeat": read_json(heartbeat) if heartbeat.is_file() else None,
              "pending": sorted(p.stem for p in (root / "requests").glob("*.json"))}
    if heartbeat.is_file():
        import time
        result["heartbeatAgeSeconds"] = round(max(0, time.time() - heartbeat.stat().st_mtime), 1)
    if identifier:
        status = root / "results" / check_id(identifier) / "status.json"
        result["capture"] = read_json(status) if status.is_file() else None
    return result


def summarize(directory):
    status = read_json(directory / "status.json")
    if status.get("state") != "COMPLETE":
        raise ValueError("Only COMPLETE captures can be summarized: " + str(status.get("state")))
    with (directory / "frames.csv").open(encoding="utf-8", newline="") as stream:
        rows = list(csv.DictReader(stream))
    values = [float(row["frame_ms"]) for row in rows]
    if not values or any(not math.isfinite(v) or v <= 0 for v in values):
        raise ValueError("Capture contains empty, nonfinite or nonpositive frame intervals")
    indexes = [int(row["frame_index"]) for row in rows]
    if indexes != list(range(indexes[0], indexes[0] + len(indexes))) or indexes[0] not in (0, 1):
        raise ValueError("Frame indices are not contiguous")
    if status.get("intervals") != len(values):
        raise ValueError("Status and CSV interval counts differ")
    ordered = sorted(values)
    tail = ordered[-max(1, math.ceil(len(values) * .01)):]
    return {"id": status.get("id"), "intervals": len(values),
            "seconds": sum(values) / 1000,
            "averageFps": 1000 / statistics.mean(values),
            "medianFrameMs": statistics.median(values),
            "p99FrameMs": ordered[math.ceil(len(values) * .99) - 1],
            "slowestOnePercentMeanFps": 1000 / statistics.mean(tail),
            "longestFrameMs": max(values),
            "measurement": "Completed-frame wall time, including limiter and stalls; not GPU-busy or display-scanout time"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    request = sub.add_parser("request")
    request.add_argument("--instance", type=Path, required=True)
    request.add_argument("--id", required=True)
    request.add_argument("--warmup", type=int, default=5)
    request.add_argument("--seconds", type=int, default=60)
    request.add_argument("--timeout", type=int, default=300)
    request.add_argument("--gpu-stages", action="store_true",
                         help="Opt in to sparse GPU stage timestamps; records a separate diagnostic file")
    status = sub.add_parser("status")
    status.add_argument("--instance", type=Path, required=True)
    status.add_argument("--id")
    summary = sub.add_parser("summary")
    summary.add_argument("result", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "request":
            result = queue(args.instance, args.id, args.warmup, args.seconds, args.timeout, args.gpu_stages)
        elif args.command == "status":
            result = inspect(args.instance, args.id)
        else:
            result = summarize(args.result)
    except (OSError, ValueError, KeyError) as failure:
        parser.exit(1, str(failure) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
