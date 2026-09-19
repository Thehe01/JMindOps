#!/usr/bin/env python3
"""Recompute graded T2Ranking metrics from a JMindOps evaluation artifact."""

from __future__ import annotations

import argparse
import json
import math
import re
from pathlib import Path
from statistics import mean


MARKER_PATTERN = re.compile(r"\bT2_PID_(?:SHA256_)?[A-Za-z0-9_-]+\b")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--result", required=True, type=Path)
    parser.add_argument("--output-prefix", type=Path)
    return parser.parse_args()


def reciprocal_rank(grades: list[int]) -> float:
    for index, grade in enumerate(grades, 1):
        if grade > 0:
            return 1.0 / index
    return 0.0


def dcg(grades: list[int]) -> float:
    return sum(
        ((2**grade) - 1) / math.log2(index + 1)
        for index, grade in enumerate(grades, 1)
    )


def ndcg(grades: list[int], ideal_grades: list[int]) -> float:
    ideal = dcg(ideal_grades[: len(grades)])
    return dcg(grades) / ideal if ideal > 0 else 0.0


def extract_marker(source: str, marker_to_pid: dict[str, str]) -> str | None:
    matches = [
        marker
        for marker in dict.fromkeys(MARKER_PATTERN.findall(source))
        if marker in marker_to_pid
    ]
    if len(matches) > 1:
        raise ValueError(
            "A retrieved source contains multiple T2Ranking passage markers: "
            + ", ".join(matches[:5])
        )
    return matches[0] if matches else None


def get_results(artifact: dict) -> list[dict]:
    if isinstance(artifact.get("result"), dict):
        return artifact["result"].get("results", [])
    if isinstance(artifact.get("data"), dict):
        return artifact["data"].get("results", [])
    return artifact.get("results", [])


def score_artifact(manifest: dict, artifact: dict) -> dict:
    manifest_queries = {
        item["query"]: item for item in manifest.get("queries", [])
    }
    marker_to_pid = {
        item["marker"]: pid
        for pid, item in manifest.get("passages", {}).items()
    }
    if not manifest_queries or not marker_to_pid:
        raise ValueError("Manifest has no queries or passage markers")
    relevance_threshold = int(
        manifest.get("parameters", {}).get("relevanceThreshold", 1)
    )

    mode_summaries = []
    for result in get_results(artifact):
        if result.get("status") != "COMPLETED":
            mode_summaries.append(
                {
                    "mode": result.get("mode"),
                    "status": result.get("status", "FAILED"),
                    "error": result.get("error"),
                    "queries": [],
                }
            )
            continue

        query_scores = []
        for detail in result.get("details", []):
            query = detail.get("query")
            manifest_query = manifest_queries.get(query)
            if manifest_query is None:
                raise ValueError(
                    f"Evaluation query is absent from manifest: {query!r}"
                )
            grades_by_pid = {
                item["pid"]: int(item["grade"])
                for item in manifest_query.get("judged", [])
            }
            relevant_pids = {
                pid
                for pid, grade in grades_by_pid.items()
                if grade >= relevance_threshold
            }
            retrieved_pids = []
            grades = []
            unknown_sources = 0
            duplicate_sources = 0
            seen_pids: set[str] = set()
            for source in detail.get("retrievedSources", []):
                marker = extract_marker(source or "", marker_to_pid)
                if marker is None:
                    retrieved_pids.append(None)
                    grades.append(0)
                    unknown_sources += 1
                    continue
                pid = marker_to_pid[marker]
                retrieved_pids.append(pid)
                if pid in seen_pids:
                    grades.append(0)
                    duplicate_sources += 1
                    continue
                seen_pids.add(pid)
                raw_grade = grades_by_pid.get(pid, 0)
                grades.append(
                    raw_grade if raw_grade >= relevance_threshold else 0
                )

            ideal_grades = sorted(
                (
                    grade
                    for grade in grades_by_pid.values()
                    if grade >= relevance_threshold
                ),
                reverse=True,
            )
            relevant_retrieved = {
                pid for pid in retrieved_pids if pid in relevant_pids
            }
            recall = (
                len(relevant_retrieved) / len(relevant_pids)
                if relevant_pids
                else 0.0
            )
            precision = (
                sum(1 for grade in grades if grade > 0) / len(grades)
                if grades
                else 0.0
            )
            query_scores.append(
                {
                    "qid": manifest_query["qid"],
                    "query": query,
                    "retrievedPids": retrieved_pids,
                    "retrievedGrades": grades,
                    "reciprocalRank": reciprocal_rank(grades),
                    "nDCG": ndcg(grades, ideal_grades),
                    "recall": recall,
                    "precision": precision,
                    "unknownSources": unknown_sources,
                    "duplicateSources": duplicate_sources,
                }
            )

        mode_summaries.append(
            {
                "mode": result.get("mode"),
                "status": "COMPLETED",
                "queryCount": len(query_scores),
                "mrr": mean(
                    item["reciprocalRank"] for item in query_scores
                )
                if query_scores
                else 0.0,
                "nDCG": mean(item["nDCG"] for item in query_scores)
                if query_scores
                else 0.0,
                "recall": mean(item["recall"] for item in query_scores)
                if query_scores
                else 0.0,
                "precision": mean(
                    item["precision"] for item in query_scores
                )
                if query_scores
                else 0.0,
                "unknownSourceCount": sum(
                    item["unknownSources"] for item in query_scores
                ),
                "duplicateSourceCount": sum(
                    item["duplicateSources"] for item in query_scores
                ),
                "queries": query_scores,
            }
        )

    return {
        "schemaVersion": 1,
        "benchmark": "T2Ranking-derived JMindOps subset",
        "corpusFingerprint": manifest.get("corpusFingerprint"),
        "sourceEvaluation": artifact.get("run", {}),
        "modes": mode_summaries,
    }


def render_markdown(scored: dict) -> str:
    lines = [
        "# JMindOps T2Ranking 分级相关性评测",
        "",
        f"- Corpus fingerprint: {scored.get('corpusFingerprint')}",
        "",
        "| 模式 | 状态 | MRR | nDCG@K | Recall@K | Precision@K | 未识别来源 | 重复 passage |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for mode in scored.get("modes", []):
        if mode.get("status") != "COMPLETED":
            lines.append(
                f"| {mode.get('mode')} | {mode.get('status')} | - | - | - | - | - | - |"
            )
            continue
        lines.append(
            "| {mode} | COMPLETED | {mrr:.4f} | {ndcg:.4f} | "
            "{recall:.4f} | {precision:.4f} | {unknown} | {duplicate} |".format(
                mode=mode.get("mode"),
                mrr=mode["mrr"],
                ndcg=mode["nDCG"],
                recall=mode["recall"],
                precision=mode["precision"],
                unknown=mode["unknownSourceCount"],
                duplicate=mode["duplicateSourceCount"],
            )
        )
    lines.extend(
        [
            "",
            "> 这是固定抽样子集的分数，不等同于 T2Ranking 官方全量排行榜成绩。",
            "",
        ]
    )
    return "\n".join(lines)


def main() -> int:
    args = parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    artifact = json.loads(args.result.read_text(encoding="utf-8-sig"))
    scored = score_artifact(manifest, artifact)

    prefix = args.output_prefix
    if prefix is None:
        prefix = args.result.with_suffix("")
    json_path = Path(f"{prefix}-t2ranking.json")
    markdown_path = Path(f"{prefix}-t2ranking.md")
    json_path.write_text(
        json.dumps(scored, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    markdown_path.write_text(
        render_markdown(scored), encoding="utf-8", newline="\n"
    )
    print("T2Ranking scoring complete")
    print(f"JSON: {json_path}")
    print(f"Markdown: {markdown_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
