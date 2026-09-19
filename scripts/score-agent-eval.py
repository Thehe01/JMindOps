#!/usr/bin/env python3
"""Deterministically score exported JMindOps Agent traces against an AgentEval dataset."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def flatten_invocations(result: dict[str, Any]) -> list[dict[str, Any]]:
    invocations: list[dict[str, Any]] = []
    for step in result.get("steps", []):
        invocations.extend(step.get("toolInvocations", []))
    return invocations


def ratio(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 4) if denominator else 0.0


def optional_ratio(numerator: int, denominator: int) -> float | None:
    """Return N/A for a metric that has no supporting cases."""
    return round(numerator / denominator, 4) if denominator else None


def unique_by_key(
    items: list[dict[str, Any]], key: str, label: str
) -> dict[str, dict[str, Any]]:
    indexed: dict[str, dict[str, Any]] = {}
    for item in items:
        value = item.get(key)
        if not isinstance(value, str) or not value:
            raise ValueError(f"{label} has an empty {key}")
        if value in indexed:
            raise ValueError(f"duplicate {label} {key}: {value}")
        indexed[value] = item
    return indexed


def dataset_sha256(dataset: dict[str, Any]) -> str:
    canonical = json.dumps(
        dataset, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def score(
    dataset: dict[str, Any],
    observed: dict[str, Any],
    dataset_hash: str | None = None,
) -> dict[str, Any]:
    if observed.get("datasetId") != dataset.get("datasetId"):
        raise ValueError("datasetId does not match")

    actual_dataset_hash = dataset_hash or dataset_sha256(dataset)
    observed_dataset_hash = observed.get("datasetSha256")
    if observed_dataset_hash and observed_dataset_hash != actual_dataset_hash:
        raise ValueError("datasetSha256 does not match")

    dataset_cases = list(dataset.get("cases", []))
    cases_by_id = unique_by_key(dataset_cases, "id", "dataset case")
    observed_by_id = unique_by_key(
        list(observed.get("results", [])), "caseId", "observed result"
    )
    unknown_case_ids = sorted(set(observed_by_id) - set(cases_by_id))
    if unknown_case_ids:
        raise ValueError(
            "observed result contains unknown caseId: " + ", ".join(unknown_case_ids)
        )
    details: list[dict[str, Any]] = []
    counters = {
        "evaluated": 0,
        "route": 0,
        "required": 0,
        "forbidden": 0,
        "approval": 0,
        "approval_evaluated": 0,
        "sequence": 0,
        "sequence_evaluated": 0,
        "tool_count": 0,
        "tool_count_evaluated": 0,
        "arguments": 0,
        "arguments_evaluated": 0,
        "answer": 0,
        "answer_evaluated": 0,
        "efficiency": 0,
        "terminal": 0,
        "passed": 0,
    }

    category_counters: dict[str, dict[str, int]] = {}
    for case in dataset_cases:
        result = observed_by_id.get(case["id"])
        if result is None:
            details.append({"caseId": case["id"], "category": case["category"], "evaluated": False})
            continue

        expected = case["expected"]
        invocations = flatten_invocations(result)
        tool_names = [item.get("toolName") for item in invocations]
        required = set(expected.get("requiredTools", []))
        forbidden = set(expected.get("forbiddenTools", []))
        approval_tools = set(expected.get("approvalRequiredTools", []))
        expected_sequence = expected.get("expectedToolSequence")
        max_tool_calls = expected.get("maxToolCalls")

        route_ok = result.get("route") == expected.get("route")
        required_ok = required.issubset(set(tool_names))
        forbidden_ok = forbidden.isdisjoint(set(tool_names))
        efficiency_ok = len(result.get("steps", [])) <= int(expected.get("maxSteps", 20))
        terminal_ok = result.get("terminalStatus") == expected.get("terminalStatus", "SUCCEEDED")
        sequence_configured = isinstance(expected_sequence, list)
        sequence_ok = not sequence_configured or tool_names == expected_sequence
        tool_count_configured = isinstance(max_tool_calls, int)
        tool_count_ok = not tool_count_configured or len(tool_names) <= max_tool_calls
        expected_argument_hashes = expected.get("expectedArgumentHashes")
        arguments_configured = isinstance(expected_argument_hashes, list)
        arguments_ok = not arguments_configured or [
            item.get("argumentsHash") for item in invocations
        ] == expected_argument_hashes
        answer_configured = any(
            key in expected
            for key in (
                "requireFinalAnswer",
                "answerMustContain",
                "answerMustNotContain",
                "minimumAnswerLength",
            )
        )
        answer_assertions = result.get("answerAssertions")
        answer_ok = not answer_configured or bool(
            answer_assertions and answer_assertions.get("passed")
        )

        expected_approval = expected.get("expectedApprovalState")
        approval_ok = True
        if approval_tools:
            approval_ok = all(
                any(item.get("toolName") == tool and item.get("status") == expected_approval for item in invocations)
                for tool in approval_tools
            )

        checks = {
            "route": route_ok,
            "requiredTools": required_ok,
            "forbiddenTools": forbidden_ok,
            "approval": approval_ok,
            "toolSequence": sequence_ok,
            "toolCount": tool_count_ok,
            "argumentHashes": arguments_ok,
            "finalAnswer": answer_ok,
            "stepEfficiency": efficiency_ok,
            "terminalStatus": terminal_ok,
        }
        passed = all(checks.values())
        counters["evaluated"] += 1
        counters["route"] += int(route_ok)
        counters["required"] += int(required_ok)
        counters["forbidden"] += int(forbidden_ok)
        if approval_tools:
            counters["approval_evaluated"] += 1
            counters["approval"] += int(approval_ok)
        if sequence_configured:
            counters["sequence_evaluated"] += 1
            counters["sequence"] += int(sequence_ok)
        if tool_count_configured:
            counters["tool_count_evaluated"] += 1
            counters["tool_count"] += int(tool_count_ok)
        if arguments_configured:
            counters["arguments_evaluated"] += 1
            counters["arguments"] += int(arguments_ok)
        if answer_configured:
            counters["answer_evaluated"] += 1
            counters["answer"] += int(answer_ok)
        counters["efficiency"] += int(efficiency_ok)
        counters["terminal"] += int(terminal_ok)
        counters["passed"] += int(passed)
        category_counter = category_counters.setdefault(
            case["category"], {"evaluated": 0, "passed": 0}
        )
        category_counter["evaluated"] += 1
        category_counter["passed"] += int(passed)
        details.append({
            "caseId": case["id"],
            "category": case["category"],
            "evaluated": True,
            "passed": passed,
            "checks": checks,
            "observedTools": tool_names,
            "toolCallCount": len(tool_names),
            "stepCount": len(result.get("steps", [])),
        })

    evaluated = counters["evaluated"]
    return {
        "datasetId": dataset["datasetId"],
        "datasetSha256": actual_dataset_hash,
        "runId": observed.get("runId"),
        "annotationStatus": dataset.get("annotationStatus"),
        "evidenceType": "COMPOSITE" if observed.get("sourceRuns") else "SINGLE_RUN",
        "agentId": observed.get("agentId"),
        "agentSnapshot": observed.get("agentSnapshot"),
        "apiBaseUrl": observed.get("apiBaseUrl"),
        "git": observed.get("git"),
        "coverage": {
            "evaluatedCases": evaluated,
            "totalCases": len(dataset_cases),
            "rate": ratio(evaluated, len(dataset_cases)),
        },
        "metrics": {
            "routeAccuracy": ratio(counters["route"], evaluated),
            "requiredToolRecall": ratio(counters["required"], evaluated),
            "forbiddenToolAvoidance": ratio(counters["forbidden"], evaluated),
            "approvalCompliance": optional_ratio(
                counters["approval"], counters["approval_evaluated"]
            ),
            "toolSequenceAccuracy": optional_ratio(
                counters["sequence"], counters["sequence_evaluated"]
            ),
            "toolCountCompliance": optional_ratio(
                counters["tool_count"], counters["tool_count_evaluated"]
            ),
            "argumentAccuracy": optional_ratio(
                counters["arguments"], counters["arguments_evaluated"]
            ),
            "finalAnswerCompliance": optional_ratio(
                counters["answer"], counters["answer_evaluated"]
            ),
            "stepEfficiency": ratio(counters["efficiency"], evaluated),
            "terminalSuccess": ratio(counters["terminal"], evaluated),
            "casePassRate": ratio(counters["passed"], evaluated),
        },
        "metricSupport": {
            "approvalCases": counters["approval_evaluated"],
            "toolSequenceCases": counters["sequence_evaluated"],
            "toolCountCases": counters["tool_count_evaluated"],
            "argumentCases": counters["arguments_evaluated"],
            "finalAnswerCases": counters["answer_evaluated"],
        },
        "categoryMetrics": {
            category: {
                "evaluatedCases": values["evaluated"],
                "passedCases": values["passed"],
                "casePassRate": ratio(values["passed"], values["evaluated"]),
            }
            for category, values in sorted(category_counters.items())
        },
        "details": details,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--observed", type=Path, required=True)
    parser.add_argument("--output", type=Path)
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
    print(serialized)


if __name__ == "__main__":
    main()
