#!/usr/bin/env python3
"""Validate RAG evaluation datasets and their human-review evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any
from uuid import UUID


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8-sig") as handle:
        return json.load(handle)


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def normalized_evidence(value: Any, label: str) -> list[dict[str, str]]:
    if value is None:
        return []
    if not isinstance(value, list):
        raise ValueError(f"review.sourceEvidence.{label} 必须是带 path/sha256 的数组")
    normalized: list[dict[str, str]] = []
    for index, item in enumerate(value):
        if not isinstance(item, dict):
            raise ValueError(f"{label}[{index}] 必须是对象")
        path = str(item.get("path") or "").strip()
        digest = str(item.get("sha256") or "").strip().lower()
        if not path or len(digest) != 64:
            raise ValueError(f"{label}[{index}] 必须包含 path 和 64 位 sha256")
        normalized.append({"path": path, "sha256": digest})
    return normalized


def normalized_expected_concepts(case: dict[str, Any], label: str) -> list[list[str]]:
    raw_concepts = case.get("expectedConcepts")
    if raw_concepts is None:
        return []
    if not isinstance(raw_concepts, list) or not raw_concepts:
        raise ValueError(f"{label}.expectedConcepts 必须是非空数组")
    concepts: list[list[str]] = []
    for index, concept in enumerate(raw_concepts):
        if not isinstance(concept, dict):
            raise ValueError(f"{label}.expectedConcepts[{index}] 必须是带 anyOf 的对象")
        aliases = concept.get("anyOf")
        if not isinstance(aliases, list) or not aliases:
            raise ValueError(f"{label}.expectedConcepts[{index}].anyOf 必须是非空数组")
        normalized = [str(alias).strip() for alias in aliases if str(alias).strip()]
        if len(normalized) != len(aliases):
            raise ValueError(f"{label}.expectedConcepts[{index}].anyOf 只能包含非空字符串")
        concepts.append(normalized)
    return concepts


def validate_source_evidence(
    dataset: dict[str, Any], repository_root: Path
) -> dict[str, Path]:
    review = dataset.get("review") or {}
    evidence = review.get("sourceEvidence") or {}
    primary = normalized_evidence(evidence.get("primaryDocuments"), "primaryDocuments")
    supporting = normalized_evidence(evidence.get("supportingDocuments"), "supportingDocuments")
    if not primary:
        raise ValueError("REVIEWED 题集至少需要一份 primaryDocuments 复核证据")

    root = repository_root.resolve()
    seen: set[str] = set()
    resolved_by_path: dict[str, Path] = {}
    for item in [*primary, *supporting]:
        relative = Path(item["path"])
        if relative.is_absolute():
            raise ValueError(f"复核证据必须使用仓库相对路径: {relative}")
        resolved = (root / relative).resolve()
        if not resolved.is_relative_to(root):
            raise ValueError(f"复核证据越出仓库目录: {relative}")
        normalized_path = relative.as_posix().casefold()
        if normalized_path in seen:
            raise ValueError(f"复核证据路径重复: {relative}")
        seen.add(normalized_path)
        if not resolved.is_file():
            raise ValueError(f"复核证据文件不存在: {relative}")
        actual = file_sha256(resolved)
        if actual != item["sha256"]:
            raise ValueError(
                f"复核证据已变化: {relative}，expected={item['sha256']}，actual={actual}"
            )
        resolved_by_path[relative.as_posix()] = resolved
    return resolved_by_path


def validate_expected_evidence(
    dataset: dict[str, Any], evidence_files: dict[str, Path]
) -> None:
    for case in dataset["testCases"]:
        if case.get("expectedNoAnswer"):
            continue
        source_paths = [str(value) for value in case.get("sourcePaths", [])]
        if dataset.get("datasetRole") == "test" and not source_paths:
            raise ValueError(f"{case['id']} 缺少 sourcePaths")
        if not source_paths:
            continue
        unknown = [path for path in source_paths if path not in evidence_files]
        if unknown:
            raise ValueError(f"{case['id']} 引用了未纳入复核证据的 sourcePaths: {unknown}")
        primary_source = source_paths[0]
        source_text = evidence_files[primary_source].read_text(
            encoding="utf-8-sig", errors="replace"
        ).casefold()
        concepts = normalized_expected_concepts(case, str(case["id"]))
        if concepts:
            missing = [
                {"anyOf": aliases}
                for aliases in concepts
                if not any(alias.casefold() in source_text for alias in aliases)
            ]
        else:
            missing = [
                str(keyword)
                for keyword in case.get("expectedKeywords", [])
                if str(keyword).casefold() not in source_text
            ]
        if missing:
            raise ValueError(
                f"{case['id']} 的关键词不在主证据 {primary_source} 中: {missing}"
            )


def normalize_query(value: Any) -> str:
    return "".join(
        character.casefold() for character in str(value) if character.isalnum()
    )


def resolve_dataset_evidence(
    evidence: Any, label: str, repository_root: Path
) -> Path:
    if not isinstance(evidence, dict):
        raise ValueError(f"{label} 必须是带 path/sha256 的对象")
    relative = Path(str(evidence.get("path") or ""))
    digest = str(evidence.get("sha256") or "").strip().lower()
    if not str(relative).strip() or len(digest) != 64 or relative.is_absolute():
        raise ValueError(f"{label} 必须包含相对 path 和 64 位 sha256")
    root = repository_root.resolve()
    resolved = (root / relative).resolve()
    if not resolved.is_relative_to(root) or not resolved.is_file():
        raise ValueError(f"{label} 文件不存在或越出仓库: {relative}")
    actual = file_sha256(resolved)
    if actual != digest:
        raise ValueError(
            f"{label} 已变化: {relative}，expected={digest}，actual={actual}"
        )
    return resolved


def validate_development_dataset_evidence(
    dataset: dict[str, Any], repository_root: Path
) -> None:
    review = dataset.get("review") or {}
    evidence = review.get("developmentDatasetEvidence")
    prior_evidence = review.get("priorDatasetEvidence")
    if evidence is None and prior_evidence is None:
        return
    if prior_evidence is not None and not isinstance(prior_evidence, list):
        raise ValueError("review.priorDatasetEvidence 必须是带 path/sha256 的数组")

    evidence_items: list[tuple[str, Any]] = []
    if evidence is not None:
        evidence_items.append(("developmentDatasetEvidence", evidence))
    for index, item in enumerate(prior_evidence or []):
        evidence_items.append((f"priorDatasetEvidence[{index}]", item))

    pinned_queries: list[tuple[str, str, str]] = []
    seen_paths: set[Path] = set()
    for label, item in evidence_items:
        resolved = resolve_dataset_evidence(item, label, repository_root)
        if resolved in seen_paths:
            raise ValueError(f"题集证据路径重复: {resolved}")
        seen_paths.add(resolved)
        pinned_dataset = load_json(resolved)
        for case in pinned_dataset.get("testCases", []):
            query = str(case.get("query") or "").strip()
            if query:
                pinned_queries.append(
                    (str(case.get("id") or "unknown"), query, normalize_query(query))
                )

    pinned_normalized = {normalized for _, _, normalized in pinned_queries}
    duplicates = [
        str(case.get("id"))
        for case in dataset.get("testCases", [])
        if normalize_query(case.get("query")) in pinned_normalized
    ]
    if duplicates:
        raise ValueError("测试集与历史题集存在完全重复问题: " + ", ".join(duplicates[:10]))

    threshold_value = review.get("nearDuplicateThreshold")
    if threshold_value is None:
        return
    try:
        threshold = float(threshold_value)
    except (TypeError, ValueError) as error:
        raise ValueError("review.nearDuplicateThreshold 必须是 0.5 到 1 之间的数字") from error
    if not 0.5 <= threshold < 1:
        raise ValueError("review.nearDuplicateThreshold 必须是 0.5 到 1 之间的数字")

    near_duplicates: list[str] = []
    for case in dataset.get("testCases", []):
        current = normalize_query(case.get("query"))
        if not current:
            continue
        for prior_id, _, prior in pinned_queries:
            similarity = SequenceMatcher(None, current, prior).ratio()
            if similarity >= threshold:
                near_duplicates.append(
                    f"{case.get('id')}~{prior_id}({similarity:.3f})"
                )
    if near_duplicates:
        raise ValueError(
            "测试集与历史题集存在近重复问题: " + ", ".join(near_duplicates[:10])
        )


def validate_cases(dataset: dict[str, Any]) -> tuple[int, int]:
    cases = dataset.get("testCases")
    if not isinstance(cases, list) or not cases:
        raise ValueError("RAG 题集至少需要一个 testCase")
    defaults = dataset.get("answerEvaluationDefaults") or {}
    default_recall = defaults.get("minimumKeywordRecall")
    if default_recall is not None:
        validate_keyword_recall(default_recall, "answerEvaluationDefaults.minimumKeywordRecall")
    seen_ids: set[str] = set()
    seen_queries: set[str] = set()
    no_answer_count = 0
    benchmark_cases = bool(dataset.get("benchmark"))
    for index, case in enumerate(cases):
        if not isinstance(case, dict):
            raise ValueError(f"testCases[{index}] 必须是对象")
        case_id = str(case.get("id") or "").strip()
        query = str(case.get("query") or "").strip()
        if not query or (not benchmark_cases and not case_id):
            raise ValueError(f"testCases[{index}] 缺少稳定 id 或 query")
        if case_id and case_id in seen_ids:
            raise ValueError(f"testCase id 重复: {case_id}")
        if query.casefold() in seen_queries:
            raise ValueError(f"testCase query 重复: {query}")
        if case_id:
            seen_ids.add(case_id)
        seen_queries.add(query.casefold())
        expected_no_answer = bool(case.get("expectedNoAnswer"))
        no_answer_count += int(expected_no_answer)
        if case.get("minimumKeywordRecall") is not None:
            validate_keyword_recall(
                case["minimumKeywordRecall"],
                f"{case_id or f'testCases[{index}]'}.minimumKeywordRecall",
            )
        concepts = normalized_expected_concepts(
            case, case_id or f"testCases[{index}]"
        )
        expectations = [
            case.get("expectedDocumentId"),
            case.get("expectedDocumentIds"),
            case.get("expectedSourceKey"),
            case.get("expectedSourceKeys"),
            case.get("expectedKeyword"),
            case.get("expectedKeywords"),
            concepts,
        ]
        if not expected_no_answer and not any(expectations):
            raise ValueError(f"{case_id} 缺少检索真值或预期关键词")
    return len(cases), no_answer_count


def validate_keyword_recall(value: Any, label: str) -> None:
    if isinstance(value, bool):
        raise ValueError(f"{label} 必须是大于 0 且不超过 1 的数字")
    try:
        threshold = float(value)
    except (TypeError, ValueError) as error:
        raise ValueError(f"{label} 必须是大于 0 且不超过 1 的数字") from error
    if not 0 < threshold <= 1:
        raise ValueError(f"{label} 必须是大于 0 且不超过 1 的数字")


def validate_knowledge_base_binding(dataset: dict[str, Any]) -> None:
    binding = str(dataset.get("knowledgeBaseBinding") or "dataset")
    dataset_kb_id = dataset.get("kbId")
    if binding == "runtimeParameter":
        if dataset_kb_id not in (None, ""):
            raise ValueError("knowledgeBaseBinding=runtimeParameter 时 kbId 必须为空")
        return
    if binding != "dataset":
        raise ValueError("knowledgeBaseBinding 只能是 dataset 或 runtimeParameter")
    try:
        UUID(str(dataset_kb_id or ""))
    except ValueError as error:
        raise ValueError("冻结测试集必须绑定有效 kbId，或使用 runtimeParameter") from error


def validate_dataset(
    dataset: dict[str, Any],
    repository_root: Path,
    *,
    dataset_path: Path | None = None,
    allow_draft: bool = False,
    require_frozen_test: bool = False,
    allow_exposed_test_regression: bool = False,
) -> None:
    total, no_answer_count = validate_cases(dataset)
    review_summary = (dataset.get("review") or {}).get("reviewSummary") or {}
    expected_summary = {
        "answerableCases": total - no_answer_count,
        "noAnswerCases": no_answer_count,
        "expectedKeywordCount": sum(
            len(case.get("expectedKeywords") or [])
            for case in dataset.get("testCases", [])
        ),
        "expectedConceptCount": sum(
            len(normalized_expected_concepts(case, str(case.get("id") or "testCase")))
            or len(case.get("expectedKeywords") or [])
            for case in dataset.get("testCases", [])
        ),
    }
    for field, actual in expected_summary.items():
        if field in review_summary and review_summary[field] != actual:
            raise ValueError(
                f"reviewSummary.{field}={review_summary[field]}，实际应为 {actual}"
            )
    status = str(dataset.get("annotationStatus") or "DRAFT")
    if allow_exposed_test_regression:
        if (
            status != "REVIEWED"
            or dataset.get("datasetRole") != "test"
            or dataset.get("frozen") is not True
        ):
            raise ValueError(
                "已暴露题集回归模式只接受 REVIEWED、datasetRole=test、frozen=true 的题集"
            )
    else:
        validate_development_dataset_evidence(dataset, repository_root)
    if status != "REVIEWED" and not allow_draft:
        raise ValueError(
            f"annotationStatus={status}；正式评测只接受 REVIEWED，试跑需显式允许 DRAFT"
        )
    if dataset.get("benchmark"):
        if status == "REVIEWED":
            if dataset_path is None:
                raise ValueError("公开基准校验需要 dataset_path")
            manifest_path = dataset_path.resolve().parent / "manifest.json"
            if not manifest_path.is_file():
                raise ValueError(f"公开基准缺少 manifest.json: {manifest_path}")
            manifest = load_json(manifest_path)
            expected_fingerprint = (dataset.get("benchmark") or {}).get("corpusFingerprint")
            if not expected_fingerprint or manifest.get("corpusFingerprint") != expected_fingerprint:
                raise ValueError("公开基准的 corpusFingerprint 与 manifest 不一致")
    else:
        source_evidence = ((dataset.get("review") or {}).get("sourceEvidence") or {})
        has_draft_evidence = bool(source_evidence.get("primaryDocuments"))
        if status == "REVIEWED" or has_draft_evidence:
            evidence_files = validate_source_evidence(dataset, repository_root)
            validate_expected_evidence(dataset, evidence_files)

    if require_frozen_test:
        if status != "REVIEWED":
            raise ValueError("正式验收题集必须为 REVIEWED")
        if dataset.get("datasetRole") != "test" or dataset.get("frozen") is not True:
            raise ValueError("正式验收必须使用 datasetRole=test 且 frozen=true 的题集")
        validate_knowledge_base_binding(dataset)
        if not ((dataset.get("review") or {}).get("developmentDatasetEvidence")):
            raise ValueError("冻结测试集必须固定 developmentDatasetEvidence")
        if total < 50 or no_answer_count < 10:
            raise ValueError("冻结测试集至少需要 50 题，其中无答案题至少 10 题")
        missing_categories = [
            str(case.get("id"))
            for case in dataset["testCases"]
            if not str(case.get("category") or "").strip()
        ]
        if missing_categories:
            raise ValueError("冻结测试集每题必须标注 category: " + ", ".join(missing_categories[:5]))
        missing_difficulty = [
            str(case.get("id"))
            for case in dataset["testCases"]
            if not str(case.get("difficulty") or "").strip()
        ]
        if missing_difficulty:
            raise ValueError("冻结测试集每题必须标注 difficulty: " + ", ".join(missing_difficulty[:5]))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--repository-root", required=True, type=Path)
    parser.add_argument("--allow-draft", action="store_true")
    parser.add_argument("--require-frozen-test", action="store_true")
    parser.add_argument("--allow-exposed-test-regression", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    dataset = load_json(args.dataset)
    validate_dataset(
        dataset,
        args.repository_root,
        dataset_path=args.dataset,
        allow_draft=args.allow_draft,
        require_frozen_test=args.require_frozen_test,
        allow_exposed_test_regression=args.allow_exposed_test_regression,
    )
    print(
        "RAG dataset validation passed: "
        f"{dataset.get('datasetId')} ({len(dataset.get('testCases', []))} cases)"
    )


if __name__ == "__main__":
    main()
