#!/usr/bin/env python3
"""Validate and summarize a sequential V1 + V2 RAG regression suite."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


CONTENT_OUTCOMES = {
    "PASSED",
    "HARD_GATE_FAILED",
    "SEMANTIC_QUALITY_FAILED",
    "NO_ANSWER_SEMANTIC_FAILED",
}
EXCLUDED_OUTCOMES = {
    "INFRASTRUCTURE_FAILURE",
    "GENERATION_FAILURE",
    "JUDGE_ERROR",
    "JUDGE_NOT_RUN",
    "NOT_EVALUATED",
}


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8-sig") as handle:
        return json.load(handle)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ratio(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 4) if denominator else 0.0


def require_equal(label: str, values: list[Any]) -> Any:
    if not values or any(value is None or value == "" for value in values):
        raise ValueError(f"缺少共享证据: {label}")
    normalized = [json.dumps(value, ensure_ascii=False, sort_keys=True) for value in values]
    if len(set(normalized)) != 1:
        raise ValueError(f"V1/V2 的 {label} 不一致")
    return values[0]


def file_record(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise ValueError(f"产物不存在: {path}")
    return {"path": str(path.resolve()), "sha256": sha256(path)}


def retrieval_summary(artifact: dict[str, Any]) -> dict[str, Any]:
    results = artifact.get("result", {}).get("results", [])
    summary: dict[str, Any] = {}
    for item in results:
        mode = str(item.get("mode") or "")
        if not mode:
            continue
        summary[mode] = {
            "status": item.get("status"),
            "hitRate": item.get("hitRate"),
            "recallAtK": item.get("recallAtK"),
            "precisionAtK": item.get("precisionAtK"),
            "mrr": item.get("mrr"),
            "noAnswerAccuracy": item.get("noAnswerAccuracy"),
            "p50LatencyMs": item.get("p50LatencyMs"),
            "p95LatencyMs": item.get("p95LatencyMs"),
        }
    return summary


def semantic_summary(judge: dict[str, Any]) -> dict[str, Any]:
    details = [item for item in judge.get("details", []) if item.get("evaluated")]
    outcomes = Counter(str(item.get("outcome") or "UNKNOWN") for item in details)
    content = [item for item in details if item.get("outcome") in CONTENT_OUTCOMES]
    excluded = [item for item in details if item.get("outcome") in EXCLUDED_OUTCOMES]
    unknown = [
        item
        for item in details
        if item.get("outcome") not in CONTENT_OUTCOMES | EXCLUDED_OUTCOMES
    ]
    if unknown:
        raise ValueError(
            "存在未分类 Judge outcome: "
            + ", ".join(sorted({str(item.get('outcome')) for item in unknown}))
        )
    passed = sum(bool(item.get("passed")) for item in content)
    failed = len(content) - passed
    exclusion_ids = {
        outcome: [
            str(item.get("caseId"))
            for item in excluded
            if item.get("outcome") == outcome
        ]
        for outcome in sorted(EXCLUDED_OUTCOMES)
        if outcomes.get(outcome)
    }
    failure_ids = {
        outcome: [
            str(item.get("caseId"))
            for item in content
            if item.get("outcome") == outcome
        ]
        for outcome in sorted(CONTENT_OUTCOMES - {"PASSED"})
        if outcomes.get(outcome)
    }
    return {
        "totalCases": len(judge.get("details", [])),
        "evaluatedCases": len(details),
        "contentEligibleCases": len(content),
        "contentPassedCases": passed,
        "contentFailedCases": failed,
        "contentPassRate": ratio(passed, len(content)),
        "rawPassRate": ratio(passed, len(details)),
        "excludedFailureCount": len(excluded),
        "excludedFailures": exclusion_ids,
        "contentFailures": failure_ids,
        "outcomeCounts": dict(sorted(outcomes.items())),
        "judgeMetrics": judge.get("metrics", {}),
        "metricSupport": judge.get("metricSupport", {}),
    }


def summarize(suite_manifest_path: Path) -> dict[str, Any]:
    suite = load_json(suite_manifest_path)
    if suite.get("runType") != "REGRESSION" or suite.get("exposedTestRegression") is not True:
        raise ValueError("联合任务必须标记 REGRESSION / exposedTestRegression=true")
    entries = suite.get("datasets") or []
    if [str(item.get("label")) for item in entries] != ["V1", "V2"]:
        raise ValueError("联合任务必须严格按 V1、V2 顺序包含两个题集")

    dataset_summaries: dict[str, Any] = {}
    retrieval_artifacts: list[dict[str, Any]] = []
    observed_artifacts: list[dict[str, Any]] = []
    judge_artifacts: list[dict[str, Any]] = []
    manifest_records: list[dict[str, str]] = []

    for entry in entries:
        label = str(entry["label"])
        dataset_path = Path(entry["datasetPath"])
        retrieval_path = Path(entry["retrievalJson"])
        observed_path = Path(entry["observedJson"])
        deterministic_path = Path(entry["deterministicJson"])
        judge_path = Path(entry["judgeJson"])
        child_manifest_path = Path(entry["manifestPath"])
        dataset = load_json(dataset_path)
        retrieval = load_json(retrieval_path)
        observed = load_json(observed_path)
        deterministic = load_json(deterministic_path)
        judge = load_json(judge_path)
        child_manifest = load_json(child_manifest_path)

        actual_dataset_sha = sha256(dataset_path)
        if actual_dataset_sha != entry.get("datasetSha256"):
            raise ValueError(f"{label} Dataset SHA 与 suite manifest 不一致")
        if child_manifest.get("runType") != "REGRESSION" or child_manifest.get("exposedTestRegression") is not True:
            raise ValueError(f"{label} manifest 未标记暴露题集回归")
        if child_manifest.get("datasetSha256") != actual_dataset_sha:
            raise ValueError(f"{label} child manifest Dataset SHA 不一致")
        if len(dataset.get("testCases", [])) != 50:
            raise ValueError(f"{label} 必须恰好包含 50 题")
        if retrieval.get("run", {}).get("datasetSha256") != actual_dataset_sha:
            raise ValueError(f"{label} 检索产物 Dataset SHA 不一致")
        if retrieval.get("run", {}).get("runType") != "REGRESSION" or retrieval.get("run", {}).get("exposedTestRegression") is not True:
            raise ValueError(f"{label} 检索产物未标记暴露题集回归")
        if observed.get("datasetSha256") != actual_dataset_sha:
            raise ValueError(f"{label} observed Dataset SHA 不一致")
        if observed.get("runType") != "REGRESSION" or observed.get("exposedTestRegression") is not True:
            raise ValueError(f"{label} observed 未标记暴露题集回归")
        if deterministic.get("datasetSha256") != actual_dataset_sha:
            raise ValueError(f"{label} 确定性报告 Dataset SHA 不一致")
        if judge.get("provenance", {}).get("datasetSha256") != actual_dataset_sha:
            raise ValueError(f"{label} Judge Dataset SHA 不一致")

        retrieval_artifacts.append(retrieval)
        observed_artifacts.append(observed)
        judge_artifacts.append(judge)
        manifest_records.append(file_record(child_manifest_path))
        dataset_summaries[label] = {
            "datasetId": dataset.get("datasetId"),
            "datasetSha256": actual_dataset_sha,
            "caseCount": len(dataset.get("testCases", [])),
            "manifest": file_record(child_manifest_path),
            "retrievalArtifact": file_record(retrieval_path),
            "observedArtifact": file_record(observed_path),
            "deterministicArtifact": file_record(deterministic_path),
            "judgeArtifact": file_record(judge_path),
            "retrieval": retrieval_summary(retrieval),
            "semantic": semantic_summary(judge),
        }

    kb_snapshot = require_equal(
        "knowledgeBaseSnapshot",
        [item.get("result", {}).get("knowledgeBaseSnapshot") for item in retrieval_artifacts]
        + [item.get("ragProvenance", {}).get("knowledgeBaseSnapshot") for item in observed_artifacts],
    )
    retrieval_config_hash = require_equal(
        "retrievalConfigHash",
        [item.get("result", {}).get("retrievalConfigHash") for item in retrieval_artifacts]
        + [item.get("ragProvenance", {}).get("retrievalConfigHash") for item in observed_artifacts],
    )
    retrieval_config = require_equal(
        "retrievalConfig",
        [item.get("result", {}).get("retrievalConfig") for item in retrieval_artifacts]
        + [item.get("ragProvenance", {}).get("retrievalConfig") for item in observed_artifacts],
    )
    agent_id = require_equal("agentId", [item.get("agentId") for item in observed_artifacts])
    answer_client_key = require_equal(
        "answerClientKey",
        [item.get("agentSnapshot", {}).get("model") for item in observed_artifacts],
    )
    answer_model = suite.get("answerModel")
    if not answer_model:
        raise ValueError("suite manifest 缺少实际回答模型")
    workspace_fingerprint = require_equal(
        "workspaceFingerprint",
        [item.get("git", {}).get("workspaceFingerprint") for item in observed_artifacts],
    )
    judge_identity = require_equal(
        "fixedJudge",
        [
            {
                key: item.get("provenance", {}).get(key)
                for key in (
                    "judgeName",
                    "judgeVersion",
                    "judgeScriptSha256",
                    "judgePromptSha256",
                    "judgeModel",
                    "judgeApiOrigin",
                    "temperature",
                    "maxAttemptsPerCase",
                    "maxContextChars",
                    "maxCompletionTokens",
                    "reasoningEffort",
                )
            }
            for item in judge_artifacts
        ],
    )
    if judge_identity.get("maxAttemptsPerCase") != 1:
        raise ValueError("固定 Judge 必须关闭自动重试")

    semantic_values = [item["semantic"] for item in dataset_summaries.values()]
    total_cases = sum(int(item["totalCases"]) for item in semantic_values)
    eligible = sum(int(item["contentEligibleCases"]) for item in semantic_values)
    passed = sum(int(item["contentPassedCases"]) for item in semantic_values)
    failed = sum(int(item["contentFailedCases"]) for item in semantic_values)
    excluded = sum(int(item["excludedFailureCount"]) for item in semantic_values)
    combined = {
        "totalCases": total_cases,
        "contentEligibleCases": eligible,
        "contentPassedCases": passed,
        "contentFailedCases": failed,
        "weightedContentPassRate": ratio(passed, eligible),
        "rawPassRate": ratio(passed, total_cases),
        "excludedFailureCount": excluded,
    }
    if total_cases != 100:
        raise ValueError(f"联合题数必须为 100，实际 {total_cases}")
    if passed + failed + excluded != total_cases:
        raise ValueError("联合分母无法守恒")

    return {
        "schemaVersion": "1.0",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "runId": suite.get("runId"),
        "runType": "REGRESSION",
        "exposedTestRegression": True,
        "executionOrder": ["V1", "V2"],
        "sequential": True,
        "sharedEvidence": {
            "jarSha256": suite.get("jarSha256"),
            "answerModel": answer_model,
            "answerClientKey": answer_client_key,
            "agentId": agent_id,
            "knowledgeBaseId": suite.get("knowledgeBaseId"),
            "knowledgeBaseSnapshot": kb_snapshot,
            "retrievalConfigHash": retrieval_config_hash,
            "retrievalConfig": retrieval_config,
            "workspaceFingerprint": workspace_fingerprint,
            "fixedJudge": judge_identity,
        },
        "datasets": dataset_summaries,
        "combined": combined,
        "denominatorPolicy": {
            "contentQualityIncludedOutcomes": sorted(CONTENT_OUTCOMES),
            "excludedOutcomes": sorted(EXCLUDED_OUTCOMES),
            "description": "网络、服务端、生成执行和 Judge 调用失败单独报告，不进入内容质量分母。",
        },
        "suiteManifest": {"path": str(suite_manifest_path.resolve())},
        "childManifests": manifest_records,
    }


def percent(value: Any) -> str:
    return f"{float(value or 0):.2%}"


def render_markdown(report: dict[str, Any]) -> str:
    shared = report["sharedEvidence"]
    lines = [
        "# JMindOps RAG V1 + V2 联合全量回归",
        "",
        f"- Run ID：`{report['runId']}`",
        "- 运行类型：`REGRESSION / exposedTestRegression=true`",
        "- 执行方式：V1 → V2 串行；共 100 题",
        f"- JAR SHA-256：`{shared['jarSha256']}`",
        f"- 回答模型：`{shared['answerModel']}`",
        f"- 固定 Judge：`{shared['fixedJudge']['judgeModel']}`，`maxAttemptsPerCase=1`",
        f"- Knowledge-base snapshot：`{shared['knowledgeBaseSnapshot']}`",
        f"- Retrieval config hash：`{shared['retrievalConfigHash']}`",
        "",
        "## 三组核心数字",
        "",
        "| 范围 | 总题数 | 内容质量分母 | 内容通过 | 内容失败 | 排除的网络/服务/裁判失败 | 内容通过率 | 原始通过率 |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for label in ("V1", "V2"):
        semantic = report["datasets"][label]["semantic"]
        lines.append(
            f"| {label} | {semantic['totalCases']} | {semantic['contentEligibleCases']} | "
            f"{semantic['contentPassedCases']} | {semantic['contentFailedCases']} | "
            f"{semantic['excludedFailureCount']} | {percent(semantic['contentPassRate'])} | "
            f"{percent(semantic['rawPassRate'])} |"
        )
    combined = report["combined"]
    lines.append(
        f"| V1+V2 加权 | {combined['totalCases']} | {combined['contentEligibleCases']} | "
        f"{combined['contentPassedCases']} | {combined['contentFailedCases']} | "
        f"{combined['excludedFailureCount']} | {percent(combined['weightedContentPassRate'])} | "
        f"{percent(combined['rawPassRate'])} |"
    )

    for label in ("V1", "V2"):
        dataset = report["datasets"][label]
        semantic = dataset["semantic"]
        metrics = semantic["judgeMetrics"]
        lines.extend(
            [
                "",
                f"## {label}",
                "",
                f"- Dataset SHA-256：`{dataset['datasetSha256']}`",
                f"- Manifest：`{dataset['manifest']['path']}`",
                f"- 语义概念召回：{percent(metrics.get('semanticConceptRecall'))}",
                f"- 完整性：{percent(metrics.get('semanticCompletenessPassRate'))}",
                f"- 忠实度：{percent(metrics.get('faithfulnessPassRate'))}",
                f"- 回答相关性：{percent(metrics.get('answerRelevancePassRate'))}",
                f"- 无答案准确率：{percent(metrics.get('noAnswerAccuracy'))}",
                "",
                "### 检索层",
                "",
                "| 模式 | HitRate | Recall@K | MRR | No-answer | P50(ms) | P95(ms) |",
                "| --- | ---: | ---: | ---: | ---: | ---: | ---: |",
            ]
        )
        for mode in ("VECTOR", "HYBRID_RRF", "HYBRID_RERANK"):
            item = dataset["retrieval"].get(mode, {})
            lines.append(
                f"| {mode} | {float(item.get('hitRate') or 0):.2f}% | "
                f"{float(item.get('recallAtK') or 0):.2f}% | {float(item.get('mrr') or 0):.4f} | "
                f"{float(item.get('noAnswerAccuracy') or 0):.2f}% | "
                f"{int(item.get('p50LatencyMs') or 0)} | {int(item.get('p95LatencyMs') or 0)} |"
            )
        lines.extend(["", "### 失败拆分", ""])
        if semantic["contentFailures"]:
            for outcome, ids in semantic["contentFailures"].items():
                lines.append(f"- 内容 `{outcome}`：{', '.join(f'`{item}`' for item in ids)}")
        else:
            lines.append("- 内容失败：无")
        if semantic["excludedFailures"]:
            for outcome, ids in semantic["excludedFailures"].items():
                lines.append(f"- 分母外 `{outcome}`：{', '.join(f'`{item}`' for item in ids)}")
        else:
            lines.append("- 分母外网络/服务/裁判失败：无")

    lines.extend(
        [
            "",
            "## 口径说明",
            "",
            "- 加权通过率按 V1、V2 实际内容质量分母相加后计算，不是两个百分比的简单平均。",
            "- 网络、服务端、生成执行和 Judge 调用失败单独列出，不进入内容质量分母；原始通过率仍以全部 100 题为分母。",
            "- V1、V2 均为已曝光题集，本结果只表示历史全量回归，不是新的盲测成绩。",
            "",
        ]
    )
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite-manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--markdown", required=True, type=Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    report = summarize(args.suite_manifest)
    args.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    args.markdown.write_text(render_markdown(report), encoding="utf-8")
    print(f"Joint summary JSON: {args.output}")
    print(f"Joint summary Markdown: {args.markdown}")


if __name__ == "__main__":
    main()
