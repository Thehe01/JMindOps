import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "summarize-rag-evaluation-runs.py"
SPEC = importlib.util.spec_from_file_location("summarize_rag_evaluation_runs", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class RagRunSummaryTest(unittest.TestCase):
    def write_runs(self, root: Path, config_hash="config"):
        retrieval_paths = []
        answer_paths = []
        agent = {"id": "agent", "model": "model"}
        for index, latency in enumerate((10, 30, 20), 1):
            retrieval = {
                "run": {
                    "datasetSha256": "dataset", "gitCommit": "commit",
                    "workspaceFingerprint": "workspace",
                },
                "result": {
                    "knowledgeBaseSnapshot": "snapshot",
                    "retrievalConfigHash": config_hash,
                    "retrievalConfig": {"candidateTopK": 20},
                    "results": [{
                        "mode": "VECTOR", "status": "COMPLETED", "hitRate": 90,
                        "recallAtK": 80, "precisionAtK": 70, "mrr": 0.8,
                        "noAnswerAccuracy": 50, "p50LatencyMs": latency,
                        "p95LatencyMs": latency + 5,
                    }],
                },
            }
            answer = {
                "datasetSha256": "dataset",
                "scorer": {
                    "name": "rag-scorer",
                    "version": "2.0.0",
                    "sha256": "scorer-hash",
                    "conceptMatchingPolicy": "policy",
                },
                "agentSnapshot": agent,
                "git": {"workspaceFingerprint": "workspace"},
                "ragProvenance": {
                    "knowledgeBaseSnapshot": "snapshot",
                    "retrievalConfigHash": config_hash,
                    "retrievalConfig": {"candidateTopK": 20},
                },
                "metrics": {name: 1.0 for name in MODULE.ANSWER_METRICS},
            }
            retrieval_path = root / f"retrieval-{index}.json"
            answer_path = root / f"answer-{index}.json"
            retrieval_path.write_text(json.dumps(retrieval), encoding="utf-8")
            answer_path.write_text(json.dumps(answer), encoding="utf-8")
            retrieval_paths.append(retrieval_path)
            answer_paths.append(answer_path)
        return retrieval_paths, answer_paths

    def test_summarizes_three_compatible_runs_with_median_latency(self):
        with tempfile.TemporaryDirectory() as directory:
            retrieval, answer = self.write_runs(Path(directory))
            report = MODULE.summarize(retrieval, answer)
            self.assertEqual(3, report["runCount"])
            self.assertEqual(20, report["retrieval"]["VECTOR"]["p50LatencyMs"]["median"])
            self.assertTrue(report["retrieval"]["VECTOR"]["hitRate"]["allRunsEqual"])
            self.assertEqual("2.0.0", report["answerScorer"]["version"])

    def test_rejects_changed_knowledge_base_or_config(self):
        with tempfile.TemporaryDirectory() as directory:
            retrieval, answer = self.write_runs(Path(directory))
            payload = json.loads(retrieval[-1].read_text(encoding="utf-8"))
            payload["result"]["retrievalConfigHash"] = "changed"
            retrieval[-1].write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "retrievalConfigHash"):
                MODULE.summarize(retrieval, answer)

    def test_rejects_changed_answer_scorer(self):
        with tempfile.TemporaryDirectory() as directory:
            retrieval, answer = self.write_runs(Path(directory))
            payload = json.loads(answer[-1].read_text(encoding="utf-8"))
            payload["scorer"]["version"] = "changed"
            answer[-1].write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "answerScorer"):
                MODULE.summarize(retrieval, answer)


if __name__ == "__main__":
    unittest.main()
