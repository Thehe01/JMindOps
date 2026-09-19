#!/usr/bin/env python3
"""Build a reproducible, JMindOps-compatible subset from T2Ranking TSV files."""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import re
from collections import defaultdict
from pathlib import Path
from typing import Iterable


SCHEMA_VERSION = 1
MAX_JMINDOPS_QUERIES = 100
MAX_EXPECTED_VALUES = 20
MARKER_ANCHOR_INTERVAL = 800


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Convert T2Ranking collection/queries/qrels TSV files into Markdown "
            "corpus shards and a JMindOps retrieval-evaluation dataset."
        )
    )
    parser.add_argument("--data-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--split", default="dev")
    parser.add_argument("--collection", type=Path)
    parser.add_argument("--queries", type=Path)
    parser.add_argument("--qrels", type=Path)
    parser.add_argument("--query-count", type=int, default=100)
    parser.add_argument("--corpus-size", type=int, default=5000)
    parser.add_argument("--shards", type=int, default=5)
    parser.add_argument("--seed", type=int, default=2026)
    parser.add_argument("--relevance-threshold", type=int, default=1)
    parser.add_argument("--max-relevant-per-query", type=int, default=20)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument(
        "--modes",
        nargs="+",
        default=["VECTOR", "HYBRID_RRF"],
        choices=["VECTOR", "HYBRID_RRF", "HYBRID_RERANK"],
    )
    return parser.parse_args()


def resolve_input(
    data_dir: Path, explicit: Path | None, filename: str
) -> Path:
    candidates = []
    if explicit is not None:
        candidates.append(explicit)
    candidates.extend([data_dir / filename, data_dir / "data" / filename])
    for candidate in candidates:
        resolved = candidate.expanduser().resolve()
        if resolved.is_file():
            return resolved
    checked = ", ".join(str(path) for path in candidates)
    raise FileNotFoundError(f"Cannot find {filename}; checked: {checked}")


def load_queries(path: Path) -> dict[str, str]:
    queries: dict[str, str] = {}
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        for line_number, raw_line in enumerate(handle, 1):
            line = raw_line.rstrip("\r\n")
            if not line:
                continue
            parts = line.split("\t", 1)
            if len(parts) != 2:
                raise ValueError(
                    f"{path}:{line_number}: expected qid<TAB>query"
                )
            qid, query = parts[0].strip(), parts[1].strip()
            if not qid or not query:
                raise ValueError(f"{path}:{line_number}: empty qid or query")
            queries[qid] = query
    return queries


def parse_relevance(value: str, path: Path, line_number: int) -> int:
    try:
        return int(float(value))
    except ValueError as exc:
        raise ValueError(
            f"{path}:{line_number}: invalid relevance value {value!r}"
        ) from exc


def load_qrels(path: Path) -> dict[str, dict[str, int]]:
    """Read TREC 4-column qrels and the 2/3-column retrieval variants."""
    qrels: dict[str, dict[str, int]] = defaultdict(dict)
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        for line_number, raw_line in enumerate(handle, 1):
            parts = raw_line.strip().split()
            if not parts:
                continue
            if len(parts) >= 4:
                qid, pid, relevance_raw = parts[0], parts[2], parts[3]
            elif len(parts) == 3:
                qid, pid, relevance_raw = parts
            elif len(parts) == 2:
                qid, pid = parts
                relevance_raw = "1"
            else:
                raise ValueError(
                    f"{path}:{line_number}: expected 2, 3, or 4 qrels columns"
                )
            relevance = parse_relevance(relevance_raw, path, line_number)
            previous = qrels[qid].get(pid)
            if previous is None or relevance > previous:
                qrels[qid][pid] = relevance
    return dict(qrels)


def marker_for(pid: str) -> str:
    if re.fullmatch(r"[A-Za-z0-9_-]+", pid):
        return f"T2_PID_{pid}"
    digest = hashlib.sha256(pid.encode("utf-8")).hexdigest()[:16]
    return f"T2_PID_SHA256_{digest}"


def select_query_ids(
    queries: dict[str, str],
    qrels: dict[str, dict[str, int]],
    query_count: int,
    relevance_threshold: int,
    seed: int,
) -> list[str]:
    if not 1 <= query_count <= MAX_JMINDOPS_QUERIES:
        raise ValueError(
            f"query_count must be between 1 and {MAX_JMINDOPS_QUERIES}"
        )
    candidates: list[str] = []
    seen_query_texts: set[str] = set()
    for qid in sorted(qrels):
        query = queries.get(qid)
        if query is None or query in seen_query_texts:
            continue
        if any(
            relevance >= relevance_threshold
            for relevance in qrels[qid].values()
        ):
            candidates.append(qid)
            seen_query_texts.add(query)
    if len(candidates) < query_count:
        raise ValueError(
            f"Only {len(candidates)} unique queries have relevant qrels; "
            f"cannot select {query_count}"
        )
    random.Random(seed).shuffle(candidates)
    return candidates[:query_count]


def iter_collection(path: Path) -> Iterable[tuple[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        for line_number, raw_line in enumerate(handle, 1):
            line = raw_line.rstrip("\r\n")
            if not line:
                continue
            parts = line.split("\t", 1)
            if len(parts) != 2:
                raise ValueError(
                    f"{path}:{line_number}: expected pid<TAB>passage"
                )
            pid, passage = parts[0].strip(), parts[1].strip()
            if not pid or not passage:
                continue
            yield pid, passage


def sample_collection(
    path: Path,
    required_pids: set[str],
    corpus_size: int,
    seed: int,
) -> list[tuple[str, str]]:
    if corpus_size < 1:
        raise ValueError("corpus_size must be positive")
    target_size = max(corpus_size, len(required_pids))
    random_target = target_size - len(required_pids)
    required_found: dict[str, str] = {}
    reservoir: list[tuple[str, str]] = []
    eligible_seen = 0
    rng = random.Random(seed ^ 0x5EED5EED)

    for pid, passage in iter_collection(path):
        if pid in required_pids:
            required_found[pid] = passage
            continue
        if random_target == 0:
            continue
        eligible_seen += 1
        if len(reservoir) < random_target:
            reservoir.append((pid, passage))
            continue
        replacement = rng.randrange(eligible_seen)
        if replacement < random_target:
            reservoir[replacement] = (pid, passage)

    missing = sorted(required_pids - required_found.keys())
    if missing:
        preview = ", ".join(missing[:10])
        raise ValueError(
            f"{len(missing)} qrels passage ids are absent from collection; "
            f"first values: {preview}"
        )
    passages = list(required_found.items()) + reservoir
    if len(passages) < target_size:
        raise ValueError(
            f"Collection contains only {len(passages)} usable passages, "
            f"but {target_size} were requested"
        )
    rng.shuffle(passages)
    return passages


def corpus_fingerprint(passages: list[tuple[str, str]]) -> str:
    digest = hashlib.sha256()
    for pid, passage in sorted(passages):
        digest.update(pid.encode("utf-8"))
        digest.update(b"\0")
        digest.update(passage.encode("utf-8"))
        digest.update(b"\0")
    return digest.hexdigest()


def escape_markdown_passage(passage: str) -> str:
    normalized = passage.replace("\r\n", "\n").replace("\r", "\n").strip()
    return "\n".join(
        f"\\{line}" if line.startswith("#") else line
        for line in normalized.splitlines()
    )


def add_marker_anchors(passage: str, marker: str) -> str:
    """Keep the passage id visible after JMindOps splits long sections."""
    if len(passage) <= MARKER_ANCHOR_INTERVAL:
        return passage
    segments = [
        passage[index : index + MARKER_ANCHOR_INTERVAL]
        for index in range(0, len(passage), MARKER_ANCHOR_INTERVAL)
    ]
    return (f"\n\n{marker}\n\n").join(segments)


def write_corpus_shards(
    output_dir: Path,
    split: str,
    passages: list[tuple[str, str]],
    shard_count: int,
) -> tuple[list[dict[str, object]], dict[str, dict[str, str]]]:
    if shard_count < 1:
        raise ValueError("shards must be positive")
    actual_shards = min(shard_count, len(passages))
    buckets: list[list[tuple[str, str]]] = [
        [] for _ in range(actual_shards)
    ]
    for index, passage in enumerate(passages):
        buckets[index % actual_shards].append(passage)

    corpus_dir = output_dir / "corpus"
    corpus_dir.mkdir()
    shard_manifest: list[dict[str, object]] = []
    passage_manifest: dict[str, dict[str, str]] = {}
    for index, bucket in enumerate(buckets, 1):
        filename = f"t2ranking-{split}-{index:03d}.md"
        path = corpus_dir / filename
        lines = [
            f"# T2Ranking {split} corpus shard {index}",
            "",
            (
                "Generated by scripts/prepare-t2ranking.py. "
                "Do not edit passage markers."
            ),
            "",
        ]
        for pid, passage in bucket:
            marker = marker_for(pid)
            anchored_passage = add_marker_anchors(
                escape_markdown_passage(passage), marker
            )
            lines.extend(
                [
                    f"## T2Ranking passage {marker}",
                    "",
                    marker,
                    "",
                    anchored_passage,
                    "",
                ]
            )
            passage_manifest[pid] = {
                "marker": marker,
                "shard": filename,
            }
        path.write_text("\n".join(lines), encoding="utf-8", newline="\n")
        shard_manifest.append(
            {
                "filename": filename,
                "passageCount": len(bucket),
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            }
        )
    return shard_manifest, passage_manifest


def build_query_records(
    selected_qids: list[str],
    queries: dict[str, str],
    qrels: dict[str, dict[str, int]],
    relevance_threshold: int,
    max_relevant_per_query: int,
) -> tuple[list[dict[str, object]], list[dict[str, object]], int]:
    if not 1 <= max_relevant_per_query <= MAX_EXPECTED_VALUES:
        raise ValueError(
            "max_relevant_per_query must be between 1 and "
            f"{MAX_EXPECTED_VALUES}"
        )
    test_cases: list[dict[str, object]] = []
    query_manifest: list[dict[str, object]] = []
    truncated_relevant = 0

    for qid in selected_qids:
        judged = sorted(
            qrels[qid].items(), key=lambda item: (-item[1], item[0])
        )
        relevant_all = [
            (pid, grade)
            for pid, grade in judged
            if grade >= relevance_threshold
        ]
        relevant = relevant_all[:max_relevant_per_query]
        truncated_relevant += len(relevant_all) - len(relevant)
        test_cases.append(
            {
                "id": f"t2ranking-{qid}",
                "query": queries[qid],
                "expectedKeywords": [
                    marker_for(pid) for pid, _ in relevant
                ],
            }
        )
        query_manifest.append(
            {
                "qid": qid,
                "query": queries[qid],
                "relevant": [
                    {
                        "pid": pid,
                        "marker": marker_for(pid),
                        "grade": grade,
                    }
                    for pid, grade in relevant
                ],
                "judged": [
                    {"pid": pid, "grade": grade} for pid, grade in judged
                ],
                "relevantTruncated": len(relevant_all) - len(relevant),
            }
        )
    return test_cases, query_manifest, truncated_relevant


def prepare_dataset(args: argparse.Namespace) -> dict[str, Path]:
    data_dir = args.data_dir.expanduser().resolve()
    output_dir = args.output_dir.expanduser().resolve()
    if output_dir.exists():
        raise FileExistsError(
            f"Output directory already exists: {output_dir}. "
            "Use a new directory to preserve benchmark provenance."
        )
    output_dir.mkdir(parents=True)

    collection_path = resolve_input(
        data_dir, args.collection, "collection.tsv"
    )
    queries_path = resolve_input(
        data_dir, args.queries, f"queries.{args.split}.tsv"
    )
    qrels_path = resolve_input(
        data_dir, args.qrels, f"qrels.{args.split}.tsv"
    )

    queries = load_queries(queries_path)
    qrels = load_qrels(qrels_path)
    selected_qids = select_query_ids(
        queries,
        qrels,
        args.query_count,
        args.relevance_threshold,
        args.seed,
    )
    required_pids = {
        pid for qid in selected_qids for pid in qrels[qid].keys()
    }
    passages = sample_collection(
        collection_path, required_pids, args.corpus_size, args.seed
    )
    shard_manifest, passage_manifest = write_corpus_shards(
        output_dir, args.split, passages, args.shards
    )
    test_cases, query_manifest, truncated_relevant = build_query_records(
        selected_qids,
        queries,
        qrels,
        args.relevance_threshold,
        args.max_relevant_per_query,
    )

    evaluation_dataset = {
        "name": (
            f"T2Ranking {args.split} sampled benchmark "
            f"(q={len(selected_qids)}, corpus={len(passages)})"
        ),
        "description": (
            "A reproducible T2Ranking-derived subset for JMindOps. "
            "Labels come from the official qrels; scores are not directly "
            "comparable with the official full-corpus leaderboard."
        ),
        "annotationStatus": "REVIEWED",
        "annotationSource": "T2Ranking official qrels",
        "datasetRole": "benchmark",
        "frozen": True,
        "kbId": "REPLACE_WITH_T2RANKING_KB_ID",
        "topK": args.top_k,
        "modes": list(args.modes),
        "benchmark": {
            "name": "T2Ranking",
            "split": args.split,
            "seed": args.seed,
            "queryCount": len(selected_qids),
            "corpusSize": len(passages),
            "relevanceThreshold": args.relevance_threshold,
            "corpusFingerprint": corpus_fingerprint(passages),
        },
        "testCases": test_cases,
    }
    dataset_path = output_dir / "evaluation.dataset.json"
    dataset_path.write_text(
        json.dumps(evaluation_dataset, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )

    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "benchmark": "T2Ranking",
        "source": {
            "repository": "https://github.com/THUIR/T2Ranking",
            "collection": str(collection_path),
            "queries": str(queries_path),
            "qrels": str(qrels_path),
            "split": args.split,
        },
        "parameters": {
            "seed": args.seed,
            "queryCount": args.query_count,
            "requestedCorpusSize": args.corpus_size,
            "actualCorpusSize": len(passages),
            "shards": len(shard_manifest),
            "relevanceThreshold": args.relevance_threshold,
            "maxRelevantPerQuery": args.max_relevant_per_query,
            "topK": args.top_k,
            "modes": list(args.modes),
        },
        "counts": {
            "selectedQueries": len(selected_qids),
            "requiredJudgedPassages": len(required_pids),
            "selectedPassages": len(passages),
            "truncatedRelevantLabels": truncated_relevant,
        },
        "corpusFingerprint": corpus_fingerprint(passages),
        "shards": shard_manifest,
        "passages": passage_manifest,
        "queries": query_manifest,
    }
    manifest_path = output_dir / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    return {
        "output_dir": output_dir,
        "dataset": dataset_path,
        "manifest": manifest_path,
        "corpus_dir": output_dir / "corpus",
    }


def main() -> int:
    args = parse_args()
    outputs = prepare_dataset(args)
    print("T2Ranking subset prepared")
    print(f"Dataset: {outputs['dataset']}")
    print(f"Manifest: {outputs['manifest']}")
    print(f"Corpus: {outputs['corpus_dir']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
