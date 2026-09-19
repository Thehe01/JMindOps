from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "summarize-rag-regression-suite.py"
SPEC = importlib.util.spec_from_file_location("rag_regression_suite_summary", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value), encoding="utf-8")


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def test_weighted_content_rate_excludes_execution_and_judge_failures(tmp_path: Path) -> None:
    entries = []
    fixed_judge = {
        "judgeName": "fixed",
        "judgeVersion": "1",
        "judgeScriptSha256": "a" * 64,
        "judgePromptSha256": "b" * 64,
        "judgeModel": "gpt-5.5",
        "judgeApiOrigin": "example.test",
        "temperature": 0,
        "maxAttemptsPerCase": 1,
        "maxContextChars": 16000,
        "maxCompletionTokens": 1200,
        "reasoningEffort": "low",
    }
    for label in ("V1", "V2"):
        folder = tmp_path / label.lower()
        folder.mkdir()
        dataset_path = folder / "dataset.json"
        write_json(
            dataset_path,
            {"datasetId": label, "testCases": [{"id": f"{label}-{i}"} for i in range(50)]},
        )
        dataset_sha = file_sha256(dataset_path)
        retrieval_path = folder / "retrieval.json"
        write_json(
            retrieval_path,
            {
                "run": {
                    "datasetSha256": dataset_sha,
                    "runType": "REGRESSION",
                    "exposedTestRegression": True,
                },
                "result": {
                    "knowledgeBaseSnapshot": "snapshot",
                    "retrievalConfigHash": "config-hash",
                    "retrievalConfig": {"topK": 3},
                    "results": [],
                },
            },
        )
        observed_path = folder / "observed.json"
        write_json(
            observed_path,
            {
                "datasetSha256": dataset_sha,
                "runType": "REGRESSION",
                "exposedTestRegression": True,
                "agentId": "agent",
                "agentSnapshot": {"model": "deepseek-chat"},
                "ragProvenance": {
                    "knowledgeBaseSnapshot": "snapshot",
                    "retrievalConfigHash": "config-hash",
                    "retrievalConfig": {"topK": 3},
                },
                "git": {"workspaceFingerprint": "workspace"},
            },
        )
        deterministic_path = folder / "deterministic.json"
        write_json(deterministic_path, {"datasetSha256": dataset_sha})
        outcomes = ["PASSED"] * (48 if label == "V1" else 45)
        outcomes += ["HARD_GATE_FAILED"] if label == "V1" else ["SEMANTIC_QUALITY_FAILED"] * 3
        outcomes += ["JUDGE_ERROR"] if label == "V1" else ["GENERATION_FAILURE", "INFRASTRUCTURE_FAILURE"]
        judge_path = folder / "judge.json"
        write_json(
            judge_path,
            {
                "provenance": {**fixed_judge, "datasetSha256": dataset_sha},
                "metrics": {},
                "metricSupport": {},
                "details": [
                    {
                        "caseId": f"{label}-{index}",
                        "evaluated": True,
                        "outcome": outcome,
                        "passed": outcome == "PASSED",
                    }
                    for index, outcome in enumerate(outcomes)
                ],
            },
        )
        manifest_path = folder / "manifest.json"
        write_json(
            manifest_path,
            {
                "runType": "REGRESSION",
                "exposedTestRegression": True,
                "datasetSha256": dataset_sha,
            },
        )
        entries.append(
            {
                "label": label,
                "datasetPath": str(dataset_path),
                "datasetSha256": dataset_sha,
                "retrievalJson": str(retrieval_path),
                "observedJson": str(observed_path),
                "deterministicJson": str(deterministic_path),
                "judgeJson": str(judge_path),
                "manifestPath": str(manifest_path),
            }
        )

    suite_path = tmp_path / "suite.json"
    write_json(
        suite_path,
        {
            "runId": "test",
            "runType": "REGRESSION",
            "exposedTestRegression": True,
            "answerModel": "gpt-5.6-terra",
            "jarSha256": "c" * 64,
            "knowledgeBaseId": "kb",
            "datasets": entries,
        },
    )

    report = MODULE.summarize(suite_path)

    assert report["combined"] == {
        "totalCases": 100,
        "contentEligibleCases": 97,
        "contentPassedCases": 93,
        "contentFailedCases": 4,
        "weightedContentPassRate": 0.9588,
        "rawPassRate": 0.93,
        "excludedFailureCount": 3,
    }
    assert report["sharedEvidence"]["answerModel"] == "gpt-5.6-terra"
    assert report["sharedEvidence"]["answerClientKey"] == "deepseek-chat"
