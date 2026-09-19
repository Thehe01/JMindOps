#!/usr/bin/env python3
"""Score end-to-end JMindOps RAG answers with deterministic assertions."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import unicodedata
from pathlib import Path
from typing import Any


SCORER_NAME = "jmindops-rag-answer-deterministic"
SCORER_VERSION = "2.1.0"
MAX_ANCHOR_FILLER_CHARS = 24
CITATION_PATTERN = re.compile(r"\[Source\s+(\d+)(?:\s*\|[^\]]+)?\]", re.IGNORECASE)
SOURCE_HEADER_PATTERN = re.compile(
    r"\[Source\s+(\d+)\s*\|\s*documentId=[^\]\s]+\]", re.IGNORECASE
)
SEMANTIC_UNIT_SPLIT_PATTERN = re.compile(r"(?:\r?\n+|[。！？!?]+)")
ASCII_ANCHOR_PATTERN = re.compile(r"[a-z][a-z0-9_./:-]*|\d+", re.IGNORECASE)
CJK_RUN_PATTERN = re.compile(r"[\u3400-\u9fff]+")
CJK_ANCHOR_PATTERN = re.compile(r"^[\u3400-\u9fff]+$")
NEGATION_PATTERN = re.compile(r"没有|无法|不能|不会|不得|禁止|不应|未|无|不")
CONCEPT_CONNECTOR_PATTERN = re.compile(
    r"并且|同时|分别|以及|配置为|标记为|负责|设置|使用|采用|改为|变为|转为|"
    r"通过|进行|只能从|从|被|为|由|把|与|和|及|并|后|时|可|会|的"
)


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8-sig") as handle:
        return json.load(handle)


def ratio(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 4) if denominator else 0.0


def average(values: list[float]) -> float:
    return round(sum(values) / len(values), 4) if values else 0.0


def percentile(values: list[int], quantile: float) -> int:
    if not values:
        return 0
    ordered = sorted(values)
    rank = max(1, math.ceil(len(ordered) * quantile))
    return ordered[rank - 1]


def canonical_sha256(value: Any) -> str:
    payload = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def unique_by_id(items: list[dict[str, Any]], key: str, label: str) -> dict[str, dict[str, Any]]:
    indexed: dict[str, dict[str, Any]] = {}
    for item in items:
        item_id = item.get(key)
        if not isinstance(item_id, str) or not item_id.strip():
            raise ValueError(f"{label} has an empty {key}")
        if item_id in indexed:
            raise ValueError(f"duplicate {label} {key}: {item_id}")
        indexed[item_id] = item
    return indexed


def normalized_strings(value: Any) -> list[str]:
    if value is None:
        return []
    values = value if isinstance(value, list) else [value]
    return [str(item).strip() for item in values if str(item).strip()]


def normalize_match_text(value: str) -> str:
    """Normalize presentation differences without changing domain identifiers."""
    normalized = unicodedata.normalize("NFKC", str(value)).casefold()
    normalized = normalized.replace("->", " ").replace("→", " ")
    normalized = normalized.replace("`", "").replace("*", "")
    return NEGATION_PATTERN.sub(" neg ", normalized)


def compact_match_text(value: str) -> str:
    return re.sub(r"[^\w\u3400-\u9fff]+", "", value, flags=re.UNICODE)


def semantic_units(content: str) -> list[str]:
    normalized = normalize_match_text(content)
    units = [
        compact_match_text(unit)
        for unit in SEMANTIC_UNIT_SPLIT_PATTERN.split(normalized)
        if compact_match_text(unit)
    ]
    return units or [compact_match_text(normalized)]


def compact_subsequence_matches(pattern: str, content: str) -> bool:
    """Allow short filler words while keeping anchors close and ordered."""
    if not pattern:
        return True
    if pattern in content:
        return True
    if len(pattern) <= 2:
        return False
    # Keep matching inside one semantic unit, but allow an answer to explain an
    # anchor with bounded modifiers such as the concrete retrieval stages.
    filler_budget = min(MAX_ANCHOR_FILLER_CHARS, max(8, len(pattern) * 2))
    max_span = len(pattern) + filler_budget
    first = pattern[0]
    for start, character in enumerate(content):
        if character != first:
            continue
        pattern_index = 1
        cursor = start + 1
        while cursor < len(content) and pattern_index < len(pattern):
            if content[cursor] == pattern[pattern_index]:
                pattern_index += 1
            cursor += 1
            if cursor - start > max_span:
                break
        if pattern_index == len(pattern) and cursor - start <= max_span:
            return True
    return False


def alias_anchors(alias: str) -> list[str]:
    normalized = normalize_match_text(alias)
    anchors: list[str] = []
    for token in ASCII_ANCHOR_PATTERN.findall(normalized):
        compact = compact_match_text(token)
        if compact and compact not in anchors:
            anchors.append(compact)
    cjk_only = ASCII_ANCHOR_PATTERN.sub(" ", normalized)
    for run in CJK_RUN_PATTERN.findall(cjk_only):
        for fragment in CONCEPT_CONNECTOR_PATTERN.split(run):
            compact = compact_match_text(fragment)
            if len(compact) >= 2 and compact not in anchors:
                anchors.append(compact)
    return anchors


def alias_matches(content: str, alias: str) -> bool:
    normalized_alias = compact_match_text(normalize_match_text(alias))
    if not normalized_alias:
        return False
    units = semantic_units(content)
    if any(normalized_alias in unit for unit in units):
        return True
    anchors = alias_anchors(alias)
    if not anchors:
        return False
    return any(
        all(
            compact_subsequence_matches(
                anchor,
                "".join(CJK_RUN_PATTERN.findall(unit))
                if CJK_ANCHOR_PATTERN.fullmatch(anchor)
                else unit,
            )
            for anchor in anchors
        )
        for unit in units
    )


def expected_concepts(case: dict[str, Any]) -> list[list[str]]:
    """Return answer concepts as alias groups; one alias match satisfies one concept."""
    raw_concepts = case.get("expectedConcepts")
    if raw_concepts is None:
        keywords = normalized_strings(
            case.get("expectedAnswerKeywords", case.get("expectedKeywords"))
        )
        return [[keyword] for keyword in keywords]

    concepts: list[list[str]] = []
    for raw_concept in raw_concepts if isinstance(raw_concepts, list) else [raw_concepts]:
        aliases = normalized_strings(
            raw_concept.get("anyOf") if isinstance(raw_concept, dict) else raw_concept
        )
        if aliases:
            concepts.append(aliases)
    return concepts


def matching_concept_aliases(content: str, concepts: list[list[str]]) -> list[str]:
    matched: list[str] = []
    for aliases in concepts:
        matched_alias = next(
            (alias for alias in aliases if alias_matches(content, alias)),
            None,
        )
        if matched_alias is not None:
            matched.append(matched_alias)
    return matched


def matching_concepts(content: str, concepts: list[list[str]]) -> list[list[str]]:
    return [aliases for aliases in concepts if any(alias_matches(content, alias) for alias in aliases)]


def contains(content: str, expected: str) -> bool:
    return expected.casefold() in content.casefold()


def source_contents(contexts: Any) -> dict[int, str]:
    indexed: dict[int, list[str]] = {}
    for raw_context in normalized_strings(contexts):
        matches = list(SOURCE_HEADER_PATTERN.finditer(raw_context))
        for offset, match in enumerate(matches):
            end = matches[offset + 1].start() if offset + 1 < len(matches) else len(raw_context)
            content = raw_context[match.end():end].strip()
            indexed.setdefault(int(match.group(1)), []).append(content)
    return {index: "\n".join(contents) for index, contents in indexed.items()}


def setting(case: dict[str, Any], defaults: dict[str, Any], name: str, fallback: Any) -> Any:
    return case[name] if name in case else defaults.get(name, fallback)


def score(
    dataset: dict[str, Any],
    observed: dict[str, Any],
    dataset_hash: str | None = None,
) -> dict[str, Any]:
    if observed.get("datasetId") != dataset.get("datasetId"):
        raise ValueError("datasetId does not match")
    expected_hash = observed.get("datasetSha256")
    actual_hash = dataset_hash or canonical_sha256(dataset)
    if expected_hash and expected_hash != actual_hash:
        raise ValueError("datasetSha256 does not match")

    cases = list(dataset.get("testCases", []))
    cases_by_id = unique_by_id(cases, "id", "dataset case")
    results = list(observed.get("results", []))
    observed_by_id = unique_by_id(results, "caseId", "observed result")
    unknown_ids = sorted(set(observed_by_id) - set(cases_by_id))
    if unknown_ids:
        raise ValueError("observed result contains unknown caseId: " + ", ".join(unknown_ids))

    defaults = dataset.get("answerEvaluationDefaults") or {}
    counters = {
        "evaluated": 0,
        "passed": 0,
        "terminal": 0,
        "route": 0,
        "tool": 0,
        "answerable": 0,
        "keyword_pass": 0,
        "citation_required": 0,
        "citation_present": 0,
        "citation_valid": 0,
        "citation_evidence_pass": 0,
        "no_answer": 0,
        "refusal": 0,
    }
    keyword_recalls: list[float] = []
    citation_evidence_coverages: list[float] = []
    latencies: list[int] = []
    tokens: list[float] = []
    details: list[dict[str, Any]] = []

    for case in cases:
        result = observed_by_id.get(case["id"])
        if result is None:
            details.append({"caseId": case["id"], "evaluated": False})
            continue

        answer = str((result.get("answer") or {}).get("content") or "")
        retrieval = result.get("retrieval") or {}
        source_indexes = {
            int(value) for value in retrieval.get("sourceIndexes", [])
            if isinstance(value, int) or str(value).isdigit()
        }
        citation_indexes = [int(value) for value in CITATION_PATTERN.findall(answer)]
        indexed_source_contents = source_contents(retrieval.get("knowledgeContexts", []))
        terminal_ok = result.get("terminalStatus") == "SUCCEEDED"
        expected_route = setting(case, defaults, "expectedRoute", "RAG")
        route_ok = not expected_route or result.get("route") == expected_route
        require_tool = bool(setting(case, defaults, "requireKnowledgeTool", True))
        knowledge_tool_calls = int(retrieval.get("knowledgeToolCalls") or 0)
        tool_ok = not require_tool or knowledge_tool_calls > 0
        answer_present = bool(answer.strip())
        expected_no_answer = bool(
            setting(case, defaults, "expectedNoAnswer", False)
        )

        checks: dict[str, bool] = {
            "terminalStatus": terminal_ok,
            "route": route_ok,
            "knowledgeTool": tool_ok,
            "answerPresent": answer_present,
        }
        keyword_recall: float | None = None
        refusal_ok: bool | None = None
        citation_present: bool | None = None
        citation_valid: bool | None = None
        citation_evidence_coverage: float | None = None
        citation_evidence_pass: bool | None = None
        matched_concept_aliases: list[str] = []
        evidence_matched_concept_aliases: list[str] = []

        if expected_no_answer:
            refusal_patterns = normalized_strings(
                setting(
                    case,
                    defaults,
                    "refusalMustContainAny",
                    [
                        "未找到", "没有找到", "知识库中没有", "无法从知识库",
                        "未提供", "未说明", "未提及", "无法确定",
                    ],
                )
            )
            refusal_ok = bool(refusal_patterns) and any(
                contains(answer, pattern) for pattern in refusal_patterns
            )
            forbidden_claims = normalized_strings(case.get("answerMustNotContain"))
            forbidden_ok = not any(contains(answer, item) for item in forbidden_claims)
            require_no_citation = bool(
                setting(case, defaults, "requireNoCitationForNoAnswer", True)
            )
            citation_free = not citation_indexes
            checks["groundedRefusal"] = refusal_ok
            checks["forbiddenClaims"] = forbidden_ok
            if require_no_citation:
                checks["noAnswerCitationFree"] = citation_free
            counters["no_answer"] += 1
            counters["refusal"] += int(
                refusal_ok and forbidden_ok and (citation_free or not require_no_citation)
            )
        else:
            concepts = expected_concepts(case)
            matched = matching_concepts(answer, concepts)
            matched_concept_aliases = matching_concept_aliases(answer, concepts)
            keyword_recall = ratio(len(matched), len(concepts)) if concepts else 1.0
            minimum_recall = float(
                setting(case, defaults, "minimumKeywordRecall", 1.0)
            )
            keyword_ok = keyword_recall >= minimum_recall
            require_citation = bool(setting(case, defaults, "requireCitation", True))
            citation_present = bool(citation_indexes)
            citation_valid = bool(citation_indexes) and all(
                index in source_indexes for index in citation_indexes
            )
            cited_evidence = "\n".join(
                indexed_source_contents.get(index, "")
                for index in dict.fromkeys(citation_indexes)
            )
            evidence_matched = matching_concepts(cited_evidence, concepts)
            evidence_matched_concept_aliases = matching_concept_aliases(
                cited_evidence, concepts
            )
            citation_evidence_coverage = (
                ratio(len(evidence_matched), len(concepts)) if concepts else 1.0
            )
            citation_evidence_pass = (
                citation_valid and citation_evidence_coverage >= minimum_recall
            )
            checks["answerKeywords"] = keyword_ok
            if case.get("expectedConcepts") is not None:
                checks["answerConcepts"] = keyword_ok
            if require_citation:
                checks["citationPresent"] = citation_present
                checks["citationIndexValid"] = citation_valid
                checks["citationEvidence"] = citation_evidence_pass
                counters["citation_required"] += 1
                counters["citation_present"] += int(citation_present)
                counters["citation_valid"] += int(citation_valid)
                counters["citation_evidence_pass"] += int(citation_evidence_pass)
                citation_evidence_coverages.append(citation_evidence_coverage)
            counters["answerable"] += 1
            counters["keyword_pass"] += int(keyword_ok)
            keyword_recalls.append(keyword_recall)

        passed = all(checks.values())
        counters["evaluated"] += 1
        counters["passed"] += int(passed)
        counters["terminal"] += int(terminal_ok)
        counters["route"] += int(route_ok)
        counters["tool"] += int(tool_ok)
        latency = int(result.get("latencyMs") or 0)
        token_count = float(result.get("totalTokens") or 0)
        latencies.append(max(latency, 0))
        tokens.append(max(token_count, 0))
        details.append({
            "caseId": case["id"],
            "evaluated": True,
            "expectedNoAnswer": expected_no_answer,
            "passed": passed,
            "checks": checks,
            "keywordRecall": keyword_recall,
            "conceptRecall": keyword_recall,
            "expectedConceptCount": len(expected_concepts(case)) if not expected_no_answer else 0,
            "matchedConceptAliases": matched_concept_aliases,
            "evidenceMatchedConceptAliases": evidence_matched_concept_aliases,
            "citationIndexes": citation_indexes,
            "availableSourceIndexes": sorted(source_indexes),
            "citationEvidenceCoverage": citation_evidence_coverage,
            "refusalPassed": refusal_ok,
            "latencyMs": latency,
            "totalTokens": int(token_count),
        })

    evaluated = counters["evaluated"]
    return {
        "schemaVersion": "1.1",
        "scorer": {
            "name": SCORER_NAME,
            "version": SCORER_VERSION,
            "sha256": file_sha256(Path(__file__).resolve()),
            "conceptMatchingPolicy": "nfkc-markdown-normalized-same-unit-bounded-anchor-v2.1",
        },
        "datasetId": dataset.get("datasetId"),
        "datasetSha256": actual_hash,
        "annotationStatus": dataset.get("annotationStatus"),
        "datasetRole": observed.get("datasetRole", dataset.get("datasetRole")),
        "frozen": observed.get("frozen", dataset.get("frozen")),
        "runId": observed.get("runId"),
        "agentId": observed.get("agentId"),
        "knowledgeBaseId": observed.get("knowledgeBaseId"),
        "agentSnapshot": observed.get("agentSnapshot"),
        "apiBaseUrl": observed.get("apiBaseUrl"),
        "ragProvenance": observed.get("ragProvenance"),
        "git": observed.get("git"),
        "coverage": {
            "evaluatedCases": evaluated,
            "totalCases": len(cases),
            "rate": ratio(evaluated, len(cases)),
        },
        "metrics": {
            "terminalSuccess": ratio(counters["terminal"], evaluated),
            "ragRouteAccuracy": ratio(counters["route"], evaluated),
            "knowledgeToolUsage": ratio(counters["tool"], evaluated),
            "answerKeywordRecall": average(keyword_recalls),
            "answerConceptRecall": average(keyword_recalls),
            "answerCompletenessPassRate": ratio(
                counters["keyword_pass"], counters["answerable"]
            ),
            "citationPresenceRate": ratio(
                counters["citation_present"], counters["citation_required"]
            ),
            "citationIndexValidityRate": ratio(
                counters["citation_valid"], counters["citation_required"]
            ),
            "citationValidityRate": ratio(
                counters["citation_valid"], counters["citation_required"]
            ),
            "citationEvidenceCoverage": average(citation_evidence_coverages),
            "citationEvidencePassRate": ratio(
                counters["citation_evidence_pass"], counters["citation_required"]
            ),
            "noAnswerAccuracy": ratio(counters["refusal"], counters["no_answer"]),
            "casePassRate": ratio(counters["passed"], evaluated),
            "averageTokens": round(average(tokens), 2),
            "p50LatencyMs": percentile(latencies, 0.50),
            "p95LatencyMs": percentile(latencies, 0.95),
        },
        "metricSupport": {
            "answerableCases": counters["answerable"],
            "citationRequiredCases": counters["citation_required"],
            "noAnswerCases": counters["no_answer"],
        },
        "details": details,
    }


def render_markdown(report: dict[str, Any]) -> str:
    metrics = report["metrics"]
    support = report["metricSupport"]
    coverage = report["coverage"]
    lines = [
        "# JMindOps RAG 端到端回答评测",
        "",
        f"- Dataset: `{report.get('datasetId')}` (`{report.get('annotationStatus')}`)",
        f"- Dataset role: `{report.get('datasetRole')}` (frozen={report.get('frozen')})",
        f"- Dataset SHA-256: `{report.get('datasetSha256')}`",
        f"- Scorer: `{report['scorer']['name']}@{report['scorer']['version']}`",
        f"- Scorer SHA-256: `{report['scorer']['sha256']}`",
        f"- Run ID: `{report.get('runId')}`",
        f"- Agent ID: `{report.get('agentId')}`",
        f"- Knowledge base ID: `{report.get('knowledgeBaseId')}`",
        f"- Coverage: {coverage['evaluatedCases']}/{coverage['totalCases']} ({coverage['rate']:.2%})",
        "",
        "| 指标 | 结果 | 支持题数 |",
        "| --- | ---: | ---: |",
        f"| 最终任务成功率 | {metrics['terminalSuccess']:.2%} | {coverage['evaluatedCases']} |",
        f"| RAG 路由准确率 | {metrics['ragRouteAccuracy']:.2%} | {coverage['evaluatedCases']} |",
        f"| KnowledgeTool 使用率 | {metrics['knowledgeToolUsage']:.2%} | {coverage['evaluatedCases']} |",
        f"| 答案关键词/概念平均召回 | {metrics['answerConceptRecall']:.2%} | {support['answerableCases']} |",
        f"| 答案完整性通过率 | {metrics['answerCompletenessPassRate']:.2%} | {support['answerableCases']} |",
        f"| 引用存在率 | {metrics['citationPresenceRate']:.2%} | {support['citationRequiredCases']} |",
        f"| 引用编号有效率（仅格式） | {metrics['citationIndexValidityRate']:.2%} | {support['citationRequiredCases']} |",
        f"| 引用证据关键词覆盖 | {metrics['citationEvidenceCoverage']:.2%} | {support['citationRequiredCases']} |",
        f"| 引用证据通过率 | {metrics['citationEvidencePassRate']:.2%} | {support['citationRequiredCases']} |",
        f"| 无答案拒答准确率 | {metrics['noAnswerAccuracy']:.2%} | {support['noAnswerCases']} |",
        f"| 整题通过率 | {metrics['casePassRate']:.2%} | {coverage['evaluatedCases']} |",
        f"| 平均 Token | {metrics['averageTokens']:.2f} | {coverage['evaluatedCases']} |",
        f"| P50 / P95 延迟 | {metrics['p50LatencyMs']} / {metrics['p95LatencyMs']} ms | {coverage['evaluatedCases']} |",
        "",
        "## 未通过样本",
        "",
    ]
    failures = [item for item in report["details"] if item.get("evaluated") and not item.get("passed")]
    if failures:
        for item in failures:
            failed_checks = [name for name, passed in item["checks"].items() if not passed]
            lines.append(f"- `{item['caseId']}`: {', '.join(failed_checks)}")
    else:
        lines.append("- 无")
    lines.extend([
        "",
        "> 引用证据指标只检查被引用上下文能否覆盖 Gold 关键词，不等同于 RAGAS Faithfulness、逐句蕴含或人工事实核验。",
        "",
    ])
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--observed", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--markdown", type=Path)
    args = parser.parse_args()

    report = score(
        load_json(args.dataset),
        load_json(args.observed),
        dataset_hash=file_sha256(args.dataset),
    )
    serialized = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized + "\n", encoding="utf-8")
    if args.markdown:
        args.markdown.parent.mkdir(parents=True, exist_ok=True)
        args.markdown.write_text(render_markdown(report), encoding="utf-8")
    print(serialized)


if __name__ == "__main__":
    main()
