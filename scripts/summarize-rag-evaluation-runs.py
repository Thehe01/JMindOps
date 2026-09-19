#!/usr/bin/env python3
"""Validate repeated RAG runs and summarize quality and latency."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from statistics import median
from typing import Any


QUALITY_METRICS = (
    "hitRate",
    "recallAtK",
    "precisionAtK",
    "mrr",
    "noAnswerAccuracy",
)
ANSWER_METRICS = (
    "casePassRate",
    "answerKeywordRecall",
    "answerCompletenessPassRate",
    "citationIndexValidityRate",
    "citationEvidenceCoverage",
    "citationEvidencePassRate",
    "noAnswerAccuracy",
    "averageTokens",
    "p50LatencyMs",
    "p95LatencyMs",
)


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8-sig") as handle:
        return json.load(handle)


def file_record(path: Path) -> dict[str, str]:
    return {
        "path": str(path.resolve()),
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    }


def stable_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def require_same(label: str, values: list[Any]) -> Any:
    if not values:
        raise ValueError(f"缺少 {label}")
    if any(value is None or value == "" for value in values):
        raise ValueError(f"重复运行缺少 {label}")
    normalized = [stable_json(value) for value in values]
    if len(set(normalized)) != 1:
        raise ValueError(f"重复运行的 {label} 不一致")
    return values[0]


def metric_summary(values: list[float]) -> dict[str, float | bool]:
    numeric = [float(value) for value in values]
    return {
        "median": median(numeric),
        "min": min(numeric),
        "max": max(numeric),
        "allRunsEqual": len(set(numeric)) == 1,
    }


def summarize(retrieval_paths: list[Path], answer_paths: list[Path]) -> dict[str, Any]:
    if len(retrieval_paths) < 3 or len(answer_paths) < 3:
        raise ValueError("正式汇总至少需要 3 次检索运行和 3 次回答运行")
    retrieval = [load_json(path) for path in retrieval_paths]
    answers = [load_json(path) for path in answer_paths]

    dataset_sha = require_same(
        "datasetSha256",
        [item.get("run", {}).get("datasetSha256") for item in retrieval]
        + [item.get("datasetSha256") for item in answers],
    )
    kb_snapshot = require_same(
        "knowledgeBaseSnapshot",
        [item.get("result", {}).get("knowledgeBaseSnapshot") for item in retrieval]
        + [item.get("ragProvenance", {}).get("knowledgeBaseSnapshot") for item in answers],
    )
    config_hash = require_same(
        "retrievalConfigHash",
        [item.get("result", {}).get("retrievalConfigHash") for item in retrieval]
        + [item.get("ragProvenance", {}).get("retrievalConfigHash") for item in answers],
    )
    retrieval_config = require_same(
        "retrievalConfig",
        [item.get("result", {}).get("retrievalConfig") for item in retrieval]
        + [item.get("ragProvenance", {}).get("retrievalConfig") for item in answers],
    )
    require_same("Git commit", [item.get("run", {}).get("gitCommit") for item in retrieval])
    workspace_fingerprint = require_same(
        "workspaceFingerprint",
        [item.get("run", {}).get("workspaceFingerprint") for item in retrieval]
        + [item.get("git", {}).get("workspaceFingerprint") for item in answers],
    )
    require_same("Agent snapshot", [item.get("agentSnapshot") for item in answers])
    answer_scorer = require_same(
        "answerScorer",
        [item.get("scorer") for item in answers],
    )

    by_mode: dict[str, list[dict[str, Any]]] = {}
    for artifact in retrieval:
        for mode in artifact.get("result", {}).get("results", []):
            if mode.get("status") != "COMPLETED":
                raise ValueError(f"检索模式未完成: {mode.get('mode')}={mode.get('status')}")
            by_mode.setdefault(str(mode.get("mode")), []).append(mode)
    expected_runs = len(retrieval)
    if any(len(runs) != expected_runs for runs in by_mode.values()):
        raise ValueError("重复运行的检索模式集合不一致")

    retrieval_summary: dict[str, Any] = {}
    for mode, runs in sorted(by_mode.items()):
        retrieval_summary[mode] = {
            metric: metric_summary([run[metric] for run in runs])
            for metric in (*QUALITY_METRICS, "p50LatencyMs", "p95LatencyMs")
        }

    answer_summary = {
        metric: metric_summary([item.get("metrics", {}).get(metric, 0.0) for item in answers])
        for metric in ANSWER_METRICS
    }
    return {
        "schemaVersion": "1.1",
        "runCount": len(retrieval),
        "datasetSha256": dataset_sha,
        "knowledgeBaseSnapshot": kb_snapshot,
        "retrievalConfigHash": config_hash,
        "retrievalConfig": retrieval_config,
        "workspaceFingerprint": workspace_fingerprint,
        "answerScorer": answer_scorer,
        "retrievalArtifacts": [file_record(path) for path in retrieval_paths],
        "answerArtifacts": [file_record(path) for path in answer_paths],
        "retrieval": retrieval_summary,
        "answer": answer_summary,
    }


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# JMindOps RAG 正式重复运行汇总",
        "",
        f"- 重复次数：{report['runCount']}",
        f"- Dataset SHA-256：`{report['datasetSha256']}`",
        f"- Knowledge-base snapshot：`{report['knowledgeBaseSnapshot']}`",
        f"- Retrieval config hash：`{report['retrievalConfigHash']}`",
        f"- Answer scorer：`{report['answerScorer']['name']}@{report['answerScorer']['version']}`",
        f"- Answer scorer SHA-256：`{report['answerScorer']['sha256']}`",
        "",
        "## 检索层",
        "",
        "| 模式 | HitRate 中位数 | Recall 中位数 | MRR 中位数 | No-answer 中位数 | P50 中位数(ms) | P95 中位数(ms) | 质量一致 |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
    ]
    for mode, metrics in report["retrieval"].items():
        quality_equal = all(metrics[name]["allRunsEqual"] for name in QUALITY_METRICS)
        lines.append(
            f"| {mode} | {metrics['hitRate']['median']:.2f} | "
            f"{metrics['recallAtK']['median']:.2f} | {metrics['mrr']['median']:.4f} | "
            f"{metrics['noAnswerAccuracy']['median']:.2f} | "
            f"{metrics['p50LatencyMs']['median']:.0f} | {metrics['p95LatencyMs']['median']:.0f} | "
            f"{'是' if quality_equal else '否'} |"
        )
    answer = report["answer"]
    lines.extend([
        "",
        "## 端到端回答层",
        "",
        "| 指标 | 中位数 | 最小值 | 最大值 |",
        "| --- | ---: | ---: | ---: |",
    ])
    for metric in ANSWER_METRICS:
        value = answer[metric]
        lines.append(f"| {metric} | {value['median']:.4f} | {value['min']:.4f} | {value['max']:.4f} |")
    return "\n".join(lines) + "\n"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--retrieval", required=True, type=Path, nargs="+")
    parser.add_argument("--answer", required=True, type=Path, nargs="+")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--markdown", required=True, type=Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    report = summarize(args.retrieval, args.answer)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.markdown.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    args.markdown.write_text(render_markdown(report), encoding="utf-8")
    print(f"RAG summary JSON: {args.output}")
    print(f"RAG summary Markdown: {args.markdown}")


if __name__ == "__main__":
    main()
