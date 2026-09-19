import importlib.util
import json
from http.client import RemoteDisconnected
import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "judge-rag-answer-eval.py"
SPEC = importlib.util.spec_from_file_location("judge_rag_answer_eval", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class RagAnswerSemanticJudgeTest(unittest.TestCase):
    def setUp(self):
        self.dataset = {
            "datasetId": "rag-test",
            "annotationStatus": "REVIEWED",
            "answerEvaluationDefaults": {"minimumKeywordRecall": 1.0},
            "testCases": [
                {
                    "id": "answerable",
                    "query": "如何切换？",
                    "expectedConcepts": [
                        {"anyOf": ["切换后端不修改主链路"]},
                        {"anyOf": ["失败保留 RRF 排序"]},
                    ],
                },
                {"id": "missing", "query": "不存在什么？", "expectedNoAnswer": True},
                {"id": "failed", "query": "为什么失败？", "expectedConcepts": [{"anyOf": ["原因"]}]},
            ],
        }
        self.observed = {
            "datasetId": "rag-test",
            "datasetSha256": "hash",
            "runId": "run-1",
            "results": [
                {
                    "caseId": "answerable",
                    "terminalStatus": "SUCCEEDED",
                    "answer": {"content": "可替换供应方且主检索流程不变；异常时沿用已有融合次序。[Source 1]"},
                    "retrieval": {
                        "knowledgeToolCalls": 1,
                        "sourceIndexes": [1],
                        "knowledgeContexts": ["[Source 1 | documentId=d1]\n相关证据"],
                    },
                },
                {
                    "caseId": "missing",
                    "terminalStatus": "SUCCEEDED",
                    "answer": {"content": "知识库未说明该事实。"},
                    "retrieval": {"knowledgeToolCalls": 1, "sourceIndexes": [], "knowledgeContexts": []},
                },
                {
                    "caseId": "failed",
                    "terminalStatus": "FAILED",
                    "terminalError": "HTTP 524 from upstream gateway",
                },
            ],
        }
        self.deterministic = {
            "datasetId": "rag-test",
            "datasetSha256": "hash",
            "runId": "run-1",
            "scorer": {"name": "deterministic", "version": "2"},
            "details": [
                {
                    "caseId": "answerable",
                    "evaluated": True,
                    "passed": False,
                    "checks": {
                        "terminalStatus": True,
                        "route": True,
                        "knowledgeTool": True,
                        "answerPresent": True,
                        "answerConcepts": False,
                        "citationPresent": True,
                        "citationIndexValid": True,
                        "citationEvidence": False,
                    },
                },
                {
                    "caseId": "missing",
                    "evaluated": True,
                    "passed": True,
                    "checks": {
                        "terminalStatus": True,
                        "route": True,
                        "knowledgeTool": True,
                        "answerPresent": True,
                        "groundedRefusal": True,
                        "forbiddenClaims": True,
                        "noAnswerCitationFree": True,
                    },
                },
                {
                    "caseId": "failed",
                    "evaluated": True,
                    "passed": False,
                    "checks": {"terminalStatus": False, "route": True},
                },
            ],
        }

    def judge_records(self):
        return {
            "answerable": {
                "status": "SUCCESS",
                "latencyMs": 10,
                "result": {
                    "concepts": [
                        {"index": 1, "covered": True, "reason": "同义表达"},
                        {"index": 2, "covered": True, "reason": "同义表达"},
                    ],
                    "faithfulness": {"passed": True, "unsupportedClaims": []},
                    "relevance": {"passed": True, "reason": "直接回答"},
                },
            },
            "missing": {
                "status": "SUCCESS",
                "latencyMs": 8,
                "result": {
                    "concepts": [],
                    "faithfulness": {"passed": True, "unsupportedClaims": []},
                    "relevance": {"passed": True, "reason": "完整拒答"},
                },
            },
        }

    def test_parse_strict_response_and_markdown_fence(self):
        raw = """```json
        {"concepts":[{"index":1,"covered":true,"reason":"ok"}],
        "faithfulness":{"passed":true,"unsupportedClaims":[]},
        "relevance":{"passed":true,"reason":"ok"}}
        ```"""
        result = MODULE.parse_judge_response(raw, 1)
        self.assertTrue(result["concepts"][0]["covered"])

    def test_parse_accepts_only_final_json_after_provider_prose(self):
        raw = "provider preamble\n" + json.dumps({
            "concepts": [{"index": 1, "covered": True, "reason": "ok"}],
            "faithfulness": {"passed": True, "unsupportedClaims": []},
            "relevance": {"passed": True, "reason": "ok"},
        })
        result = MODULE.parse_judge_response(raw, 1)
        self.assertTrue(result["faithfulness"]["passed"])

    def test_parse_rejects_duplicate_or_missing_concepts(self):
        raw = json.dumps({
            "concepts": [
                {"index": 1, "covered": True},
                {"index": 1, "covered": True},
            ],
            "faithfulness": {"passed": True, "unsupportedClaims": []},
            "relevance": {"passed": True},
        })
        with self.assertRaisesRegex(ValueError, "重复"):
            MODULE.parse_judge_response(raw, 2)

    def test_parse_accepts_no_answer_response_without_concepts(self):
        raw = json.dumps({
            "concepts": [],
            "faithfulness": {"passed": True, "unsupportedClaims": []},
            "relevance": {"passed": True, "reason": "完整拒答"},
        })
        result = MODULE.parse_judge_response(raw, 0)
        self.assertEqual([], result["concepts"])

    def test_semantic_judge_can_pass_deterministic_lexical_miss(self):
        report = MODULE.build_report(
            self.dataset, self.observed, self.deterministic, self.judge_records(), {}
        )
        detail = next(item for item in report["details"] if item["caseId"] == "answerable")
        self.assertTrue(detail["passed"])
        self.assertEqual("PASSED", detail["outcome"])
        self.assertEqual(1.0, report["metrics"]["semanticConceptRecall"])

    def test_no_answer_is_semantically_judged_and_infrastructure_is_separate(self):
        report = MODULE.build_report(
            self.dataset, self.observed, self.deterministic, self.judge_records(), {}
        )
        missing = next(item for item in report["details"] if item["caseId"] == "missing")
        failed = next(item for item in report["details"] if item["caseId"] == "failed")
        self.assertEqual("SUCCESS", missing["judgeStatus"])
        self.assertTrue(missing["faithfulnessPassed"])
        self.assertEqual("INFRASTRUCTURE_FAILURE", failed["outcome"])
        self.assertEqual(1, report["metricSupport"]["infrastructureFailures"])
        self.assertEqual(0, report["metricSupport"]["generationFailures"])
        self.assertAlmostEqual(2 / 3, report["metrics"]["combinedCasePassRate"], places=4)

    def test_no_answer_refusal_with_invented_fact_fails(self):
        records = self.judge_records()
        records["missing"]["result"] = {
            "concepts": [],
            "faithfulness": {
                "passed": False,
                "unsupportedClaims": ["声称 TTL 为 60 秒"],
            },
            "relevance": {"passed": True, "reason": "包含拒答"},
        }
        report = MODULE.build_report(
            self.dataset, self.observed, self.deterministic, records, {}
        )
        missing = next(item for item in report["details"] if item["caseId"] == "missing")
        self.assertFalse(missing["passed"])
        self.assertEqual("NO_ANSWER_SEMANTIC_FAILED", missing["outcome"])
        self.assertEqual(0.0, report["metrics"]["noAnswerAccuracy"])

    def test_build_judge_input_contains_only_cited_sources(self):
        case = self.dataset["testCases"][0]
        observed = self.observed["results"][0]
        observed["retrieval"]["knowledgeContexts"][0] += "\n[Source 2 | documentId=d2]\n不能发送"
        payload = MODULE.build_judge_input(case, observed, 1000)
        self.assertFalse(payload["expectedNoAnswer"])
        self.assertEqual([1], [item["sourceIndex"] for item in payload["citedEvidence"]])
        self.assertNotIn("不能发送", payload["citedEvidence"][0]["content"])

    def test_build_judge_input_marks_no_answer_contract(self):
        case = self.dataset["testCases"][1]
        observed = self.observed["results"][1]
        payload = MODULE.build_judge_input(case, observed, 1000)
        self.assertTrue(payload["expectedNoAnswer"])
        self.assertEqual([], payload["acceptedReferencePhrases"])
        self.assertEqual([], payload["citedEvidence"])

    def test_relay_config_normalizes_url_and_selects_explicit_judge_model(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".api_key"
            path.write_text(
                "RELAY_API_KEY=secret\n"
                "RELAY_BASE_URL=https://relay.example.test/\n"
                "RELAY_CHAT_MODEL=answer-model\n"
                "RELAY_JUDGE_MODEL=judge-model\n",
                encoding="utf-8",
            )
            config = MODULE.read_relay_config(path)
        self.assertEqual("https://relay.example.test/v1", config["RELAY_BASE_URL"])
        self.assertEqual("judge-model", config["JUDGE_MODEL"])

    def test_remote_disconnect_is_reported_as_per_case_network_error(self):
        config = {
            "RELAY_BASE_URL": "https://relay.example.test/v1",
            "RELAY_API_KEY": "secret",
            "JUDGE_MODEL": "judge-model",
        }
        judge_input = {
            "question": "q",
            "acceptedReferencePhrases": [{"index": 1, "anyOf": ["a"]}],
            "answer": "a [Source 1]",
            "citedEvidence": [{"sourceIndex": 1, "content": "a"}],
        }
        with patch.object(MODULE, "urlopen", side_effect=RemoteDisconnected("closed")):
            with self.assertRaisesRegex(RuntimeError, "RemoteDisconnected"):
                MODULE.call_judge(config, judge_input, 10)

    def test_non_json_response_reports_only_safe_structure_metadata(self):
        response = unittest.mock.MagicMock()
        response.__enter__.return_value.read.return_value = json.dumps({
            "choices": [{
                "message": {"role": "assistant", "content": "", "reasoning_content": "hidden"},
                "finish_reason": "length",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 0, "secret": "ignored"},
        }).encode("utf-8")
        config = {
            "RELAY_BASE_URL": "https://relay.example.test/v1",
            "RELAY_API_KEY": "secret",
            "JUDGE_MODEL": "judge-model",
        }
        judge_input = {
            "question": "q",
            "acceptedReferencePhrases": [{"index": 1, "anyOf": ["a"]}],
            "answer": "a",
            "citedEvidence": [],
        }
        with patch.object(MODULE, "urlopen", return_value=response):
            with self.assertRaisesRegex(
                ValueError, "finishReason=length.*contentType=str.*contentChars=0"
            ) as raised:
                MODULE.call_judge(config, judge_input, 10)
        self.assertNotIn("hidden", str(raised.exception))
        self.assertNotIn("secret", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
