#!/usr/bin/env python3
"""Validate and summarize Ghost Map runtime JSONL without joining it."""

from __future__ import annotations

import argparse
import collections
import json
import pathlib
import sys

SCHEMA = "ghostmap.runtime/v1"


def method_package(owner: str) -> str:
    package, separator, _ = owner.rpartition(".")
    return package if separator else "<default>"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("jsonl", type=pathlib.Path)
    parser.add_argument("--json-out", type=pathlib.Path)
    args = parser.parse_args()

    lines = [line for line in args.jsonl.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not lines:
        raise ValueError(f"no JSONL records in {args.jsonl}")
    records = [json.loads(line) for line in lines]
    for record in records:
        validate_record(record)

    final_records = [record for record in records if record["reason"] == "shutdown"]
    selected = final_records if final_records else [records[-1]]
    edge_hits: collections.Counter[tuple[tuple[str, str, str], tuple[str, str, str]]] = collections.Counter()
    edge_dispatches: dict[tuple[tuple[str, str, str], tuple[str, str, str]], set[str]] = collections.defaultdict(set)
    packages: set[str] = set()
    for record in selected:
        for edge in record["edges"]:
            caller = edge["caller"]
            callee = edge["callee"]
            key = (
                (caller["owner"], caller["name"], caller["descriptor"]),
                (callee["owner"], callee["name"], callee["descriptor"]),
            )
            edge_hits[key] += edge["hits"]
            edge_dispatches[key].add(edge["dispatch"])
            packages.add(method_package(caller["owner"]))
            packages.add(method_package(callee["owner"]))

    dispatch_pairs: collections.Counter[str] = collections.Counter()
    dispatch_hits: collections.Counter[str] = collections.Counter()
    for key, hits in edge_hits.items():
        kinds = edge_dispatches[key]
        dispatch = next(iter(kinds)) if len(kinds) == 1 else "UNKNOWN"
        dispatch_pairs[dispatch] += 1
        dispatch_hits[dispatch] += hits

    summary = {
        "schema": SCHEMA,
        "runId": records[-1]["runId"],
        "startedAt": min(record["startedAt"] for record in selected),
        "endedAt": max(record["endedAt"] for record in selected),
        "dumpRecords": len(records),
        "summarizedRecords": len(selected),
        "finalReason": records[-1]["reason"],
        "filter": records[-1]["filter"],
        "edgeLimit": records[-1]["edgeLimit"],
        "droppedEvents": sum(record["droppedEvents"] for record in selected),
        "distinctPairs": len(edge_hits),
        "hitTotal": sum(edge_hits.values()),
        "dispatchPairCounts": dict(sorted(dispatch_pairs.items())),
        "dispatchHitCounts": dict(sorted(dispatch_hits.items())),
        "packages": sorted(packages),
    }
    rendered = json.dumps(summary, indent=2, sort_keys=True)
    print(rendered)
    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(rendered + "\n", encoding="utf-8")
    return 0


def validate_record(record: dict[str, object]) -> None:
    required = {
        "schema",
        "runId",
        "startedAt",
        "endedAt",
        "reason",
        "filter",
        "edgeLimit",
        "droppedEvents",
        "edges",
    }
    missing = required.difference(record)
    if missing:
        raise ValueError(f"runtime record is missing fields: {sorted(missing)}")
    if record["schema"] != SCHEMA:
        raise ValueError(f"unsupported runtime schema: {record['schema']}")
    if not isinstance(record["filter"], dict):
        raise ValueError("filter must be an object")
    edges = record["edges"]
    if not isinstance(edges, list):
        raise ValueError("edges must be an array")
    for edge in edges:
        if not isinstance(edge, dict):
            raise ValueError("edge must be an object")
        if set(("caller", "callee", "hits", "dispatch")).difference(edge):
            raise ValueError("edge is missing caller, callee, hits, or dispatch")
        if not isinstance(edge["hits"], int) or edge["hits"] < 1:
            raise ValueError("edge hits must be a positive integer")
        for endpoint in ("caller", "callee"):
            method = edge[endpoint]
            if not isinstance(method, dict) or set(("owner", "name", "descriptor")).difference(method):
                raise ValueError(f"{endpoint} must contain owner, name, and descriptor")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"summarize-runtime: {error}", file=sys.stderr)
        raise SystemExit(2) from error
