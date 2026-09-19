#!/usr/bin/env python3
"""Validate JMindOps AgentEval datasets before expensive live execution."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any


ROUTES = {"CHAT", "RAG", "WEATHER", "MCP"}
TOOLS = {
    "KnowledgeTool",
    "weather",
    "databaseQuery",
    "readFile",
    "listFiles",
    "writeFile",
    "appendToFile",
    "deleteFile",
    "createDirectory",
    "sendEmail",
    "terminate",
}
EXPECTED_KEYS = {
    "route",
    "requiredTools",
    "forbiddenTools",
    "expectedToolSequence",
    "maxToolCalls",
    "maxSteps",
    "terminalStatus",
    "approvalRequiredTools",
    "expectedApprovalState",
    "expectedArgumentHashes",
    "requireFinalAnswer",
    "answerMustContain",
    "answerMustNotContain",
    "minimumAnswerLength",
}


def normalized_input(value: str) -> str:
    return re.sub(r"\s+", "", value).casefold()


def string_list(value: Any, field: str, case_id: str, errors: list[str]) -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        errors.append(f"{case_id}: {field} must be a string array")
        return []
    return value


def validate_dataset(path: Path) -> tuple[dict[str, Any], list[str], Counter[str]]:
    errors: list[str] = []
    try:
        dataset = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return {}, [f"{path}: invalid JSON: {exc}"], Counter()

    dataset_id = dataset.get("datasetId")
    if not isinstance(dataset_id, str) or not dataset_id:
        errors.append(f"{path}: datasetId is required")
    if dataset.get("annotationStatus") not in {"DRAFT", "REVIEWED"}:
        errors.append(f"{path}: annotationStatus must be DRAFT or REVIEWED")
    if dataset.get("split") == "frozen-test":
        frozen = dataset.get("frozen")
        reviewed = dataset.get("annotationStatus") == "REVIEWED"
        if reviewed != (frozen is True):
            errors.append(
                f"{path}: frozen-test must set frozen=true exactly when status is REVIEWED"
            )

    cases = dataset.get("cases")
    if not isinstance(cases, list) or not cases:
        return dataset, errors + [f"{path}: cases must be a non-empty array"], Counter()
    if dataset.get("expectedCaseCount") != len(cases):
        errors.append(
            f"{path}: expectedCaseCount={dataset.get('expectedCaseCount')} but found {len(cases)}"
        )

    seen_ids: set[str] = set()
    seen_inputs: set[str] = set()
    distribution: Counter[str] = Counter()
    for index, case in enumerate(cases, start=1):
        case_id = case.get("id")
        label = case_id if isinstance(case_id, str) and case_id else f"case#{index}"
        if not isinstance(case_id, str) or not case_id:
            errors.append(f"{path}: case#{index} has an empty id")
        elif case_id in seen_ids:
            errors.append(f"{path}: duplicate case id: {case_id}")
        else:
            seen_ids.add(case_id)

        category = case.get("category")
        capability = case.get("capability")
        if not isinstance(category, str) or not category:
            errors.append(f"{label}: category is required")
        else:
            distribution[category] += 1
        if not isinstance(capability, str) or not capability:
            errors.append(f"{label}: capability is required")

        input_text = case.get("input")
        if not isinstance(input_text, str) or not input_text.strip():
            errors.append(f"{label}: input is required")
        else:
            normalized = normalized_input(input_text)
            if normalized in seen_inputs:
                errors.append(f"{path}: duplicate normalized input at {label}")
            seen_inputs.add(normalized)

        expected = case.get("expected")
        if not isinstance(expected, dict):
            errors.append(f"{label}: expected must be an object")
            continue
        unknown_keys = sorted(set(expected) - EXPECTED_KEYS)
        if unknown_keys:
            errors.append(f"{label}: unknown expected keys: {', '.join(unknown_keys)}")
        route = expected.get("route")
        if route not in ROUTES:
            errors.append(f"{label}: unsupported route: {route}")

        required = string_list(expected.get("requiredTools"), "requiredTools", label, errors)
        forbidden = string_list(expected.get("forbiddenTools"), "forbiddenTools", label, errors)
        sequence = string_list(
            expected.get("expectedToolSequence"), "expectedToolSequence", label, errors
        )
        unknown_tools = sorted((set(required) | set(forbidden) | set(sequence)) - TOOLS)
        if unknown_tools:
            errors.append(f"{label}: unknown tools: {', '.join(unknown_tools)}")
        overlap = sorted(set(required) & set(forbidden))
        if overlap:
            errors.append(f"{label}: tools are both required and forbidden: {', '.join(overlap)}")
        if not set(required).issubset(set(sequence)):
            errors.append(f"{label}: requiredTools must be present in expectedToolSequence")
        max_calls = expected.get("maxToolCalls")
        if not isinstance(max_calls, int) or max_calls < len(sequence):
            errors.append(f"{label}: maxToolCalls must be an integer >= sequence length")
        max_steps = expected.get("maxSteps")
        if not isinstance(max_steps, int) or max_steps < 1:
            errors.append(f"{label}: maxSteps must be a positive integer")
        if expected.get("terminalStatus") != "SUCCEEDED":
            errors.append(f"{label}: terminalStatus must currently be SUCCEEDED")
        if route == "RAG" and "KnowledgeTool" not in required:
            errors.append(f"{label}: every RAG case must require KnowledgeTool")
        if route == "CHAT" and required:
            errors.append(f"{label}: CHAT cases cannot require tools")

        approval_tools = expected.get("approvalRequiredTools", [])
        approval_tools = string_list(approval_tools, "approvalRequiredTools", label, errors)
        if not set(approval_tools).issubset(set(required)):
            errors.append(f"{label}: approvalRequiredTools must be required")
        if approval_tools and expected.get("expectedApprovalState") != "WAITING_APPROVAL":
            errors.append(f"{label}: approval cases must expect WAITING_APPROVAL")

    return dataset, errors, distribution


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("datasets", nargs="+", type=Path)
    args = parser.parse_args()

    all_errors: list[str] = []
    normalized_across_files: dict[str, tuple[Path, str]] = {}
    summaries: list[tuple[Path, dict[str, Any], Counter[str]]] = []
    for path in args.datasets:
        dataset, errors, distribution = validate_dataset(path)
        all_errors.extend(errors)
        summaries.append((path, dataset, distribution))
        for case in dataset.get("cases", []):
            text = case.get("input")
            case_id = case.get("id")
            if not isinstance(text, str) or not text.strip():
                continue
            normalized = normalized_input(text)
            previous = normalized_across_files.get(normalized)
            if previous is not None:
                all_errors.append(
                    f"duplicate input across datasets: {previous[0]}:{previous[1]} and {path}:{case_id}"
                )
            else:
                normalized_across_files[normalized] = (path, str(case_id))

    if all_errors:
        for error in all_errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1

    for path, dataset, distribution in summaries:
        counts = ", ".join(f"{key}={value}" for key, value in sorted(distribution.items()))
        print(
            f"OK {path}: id={dataset.get('datasetId')} cases={len(dataset.get('cases', []))} "
            f"status={dataset.get('annotationStatus')} [{counts}]"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
