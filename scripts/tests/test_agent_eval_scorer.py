import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "score-agent-eval.py"
SPEC = importlib.util.spec_from_file_location("score_agent_eval", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class AgentEvalScorerTest(unittest.TestCase):
    def test_scores_route_tools_approval_and_efficiency(self):
        dataset = {
            "datasetId": "test",
            "annotationStatus": "DRAFT",
            "cases": [{
                "id": "case-1",
                "category": "governance",
                "expected": {
                    "route": "MCP",
                    "requiredTools": ["writeFile"],
                    "forbiddenTools": ["deleteFile"],
                    "approvalRequiredTools": ["writeFile"],
                    "expectedApprovalState": "WAITING_APPROVAL",
                    "maxSteps": 2,
                    "terminalStatus": "SUCCEEDED",
                },
            }],
        }
        observed = {
            "datasetId": "test",
            "runId": "run-1",
            "results": [{
                "caseId": "case-1",
                "route": "MCP",
                "terminalStatus": "SUCCEEDED",
                "steps": [{
                    "stepNo": 1,
                    "toolInvocations": [{"toolName": "writeFile", "status": "WAITING_APPROVAL"}],
                }],
            }],
        }

        report = MODULE.score(dataset, observed)

        self.assertEqual(1.0, report["metrics"]["casePassRate"])
        self.assertTrue(report["details"][0]["checks"]["approval"])

    def test_missing_results_reduce_coverage_but_are_not_silently_scored(self):
        dataset = {
            "datasetId": "test",
            "cases": [
                {"id": "one", "category": "chat", "expected": {"route": "CHAT"}},
                {"id": "two", "category": "chat", "expected": {"route": "CHAT"}},
            ],
        }
        observed = {
            "datasetId": "test",
            "results": [{"caseId": "one", "route": "CHAT", "terminalStatus": "SUCCEEDED", "steps": []}],
        }

        report = MODULE.score(dataset, observed)

        self.assertEqual(0.5, report["coverage"]["rate"])
        self.assertFalse(report["details"][1]["evaluated"])

    def test_rejects_cross_dataset_results(self):
        with self.assertRaisesRegex(ValueError, "datasetId"):
            MODULE.score({"datasetId": "a", "cases": []}, {"datasetId": "b", "results": []})

    def test_rejects_wrong_order_and_duplicate_tool_calls(self):
        dataset = {
            "datasetId": "test",
            "cases": [{
                "id": "ordered",
                "category": "multi_step",
                "expected": {
                    "route": "MCP",
                    "requiredTools": ["listFiles", "readFile"],
                    "expectedToolSequence": ["listFiles", "readFile"],
                    "maxToolCalls": 2,
                },
            }],
        }
        observed = {
            "datasetId": "test",
            "results": [{
                "caseId": "ordered",
                "route": "MCP",
                "terminalStatus": "SUCCEEDED",
                "steps": [{
                    "toolInvocations": [
                        {"toolName": "readFile"},
                        {"toolName": "listFiles"},
                        {"toolName": "readFile"},
                    ],
                }],
            }],
        }

        report = MODULE.score(dataset, observed)

        self.assertEqual(0.0, report["metrics"]["casePassRate"])
        self.assertEqual(0.0, report["metrics"]["toolSequenceAccuracy"])
        self.assertEqual(0.0, report["metrics"]["toolCountCompliance"])
        self.assertFalse(report["details"][0]["checks"]["toolSequence"])
        self.assertFalse(report["details"][0]["checks"]["toolCount"])

    def test_reviewed_dataset_configures_exact_tool_constraints(self):
        dataset_path = SCRIPT.parents[1] / "evaluation-data" / "agent-eval-v1.json"
        dataset = MODULE.load_json(dataset_path)

        self.assertEqual("REVIEWED", dataset["annotationStatus"])
        self.assertEqual("1.1", dataset["schemaVersion"])
        self.assertEqual(20, len(dataset["cases"]))
        for case in dataset["cases"]:
            expected = case["expected"]
            self.assertIsInstance(expected.get("expectedToolSequence"), list)
            self.assertIsInstance(expected.get("maxToolCalls"), int)

    def test_approval_metric_uses_only_approval_cases_as_denominator(self):
        dataset = {
            "datasetId": "test",
            "cases": [
                {"id": "chat", "category": "chat", "expected": {"route": "CHAT"}},
                {
                    "id": "write",
                    "category": "governance",
                    "expected": {
                        "route": "MCP",
                        "approvalRequiredTools": ["writeFile"],
                        "expectedApprovalState": "WAITING_APPROVAL",
                    },
                },
            ],
        }
        observed = {
            "datasetId": "test",
            "results": [
                {"caseId": "chat", "route": "CHAT", "terminalStatus": "SUCCEEDED", "steps": []},
                {
                    "caseId": "write",
                    "route": "MCP",
                    "terminalStatus": "SUCCEEDED",
                    "steps": [{"toolInvocations": [{"toolName": "writeFile", "status": "FAILED"}]}],
                },
            ],
        }

        report = MODULE.score(dataset, observed)

        self.assertEqual(1, report["metricSupport"]["approvalCases"])
        self.assertEqual(0.0, report["metrics"]["approvalCompliance"])

    def test_argument_hash_and_answer_assertions_are_supported(self):
        dataset = {
            "datasetId": "test",
            "cases": [{
                "id": "case",
                "category": "tool_selection",
                "expected": {
                    "route": "MCP",
                    "expectedArgumentHashes": ["expected-hash"],
                    "requireFinalAnswer": True,
                },
            }],
        }
        observed = {
            "datasetId": "test",
            "results": [{
                "caseId": "case",
                "route": "MCP",
                "terminalStatus": "SUCCEEDED",
                "answerAssertions": {"passed": True},
                "steps": [{"toolInvocations": [{"toolName": "readFile", "argumentsHash": "expected-hash"}]}],
            }],
        }

        report = MODULE.score(dataset, observed)

        self.assertEqual(1.0, report["metrics"]["argumentAccuracy"])
        self.assertEqual(1.0, report["metrics"]["finalAnswerCompliance"])
        self.assertEqual(1.0, report["metrics"]["casePassRate"])

    def test_unsupported_optional_metrics_are_reported_as_not_applicable(self):
        dataset = {
            "datasetId": "test",
            "cases": [{"id": "chat", "category": "chat", "expected": {"route": "CHAT"}}],
        }
        observed = {
            "datasetId": "test",
            "results": [{
                "caseId": "chat",
                "route": "CHAT",
                "terminalStatus": "SUCCEEDED",
                "steps": [],
            }],
        }

        report = MODULE.score(dataset, observed)

        self.assertIsNone(report["metrics"]["argumentAccuracy"])
        self.assertIsNone(report["metrics"]["approvalCompliance"])
        self.assertEqual(0, report["metricSupport"]["argumentCases"])
        self.assertEqual(0, report["metricSupport"]["approvalCases"])

    def test_rejects_duplicate_observed_case_ids(self):
        dataset = {
            "datasetId": "test",
            "cases": [{"id": "one", "category": "chat", "expected": {}}],
        }
        observed = {
            "datasetId": "test",
            "results": [{"caseId": "one"}, {"caseId": "one"}],
        }

        with self.assertRaisesRegex(ValueError, "duplicate observed result"):
            MODULE.score(dataset, observed)

    def test_rejects_dataset_hash_mismatch(self):
        dataset = {
            "datasetId": "test",
            "cases": [{"id": "one", "category": "chat", "expected": {}}],
        }
        observed = {
            "datasetId": "test",
            "datasetSha256": "0" * 64,
            "results": [],
        }

        with self.assertRaisesRegex(ValueError, "datasetSha256"):
            MODULE.score(dataset, observed)


if __name__ == "__main__":
    unittest.main()
