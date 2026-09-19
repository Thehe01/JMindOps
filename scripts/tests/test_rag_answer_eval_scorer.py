import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "score-rag-answer-eval.py"
SPEC = importlib.util.spec_from_file_location("score_rag_answer_eval", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class RagAnswerEvalScorerTest(unittest.TestCase):
    def dataset(self):
        return {
            "datasetId": "rag-answer-test",
            "annotationStatus": "REVIEWED",
            "answerEvaluationDefaults": {
                "minimumKeywordRecall": 0.5,
                "requireCitation": True,
                "requireKnowledgeTool": True,
            },
            "testCases": [
                {
                    "id": "answerable-1",
                    "query": "what",
                    "expectedKeywords": ["Redis", "Lua"],
                },
                {
                    "id": "no-answer-1",
                    "query": "missing",
                    "expectedNoAnswer": True,
                },
            ],
        }

    def observed(self, dataset):
        return {
            "datasetId": dataset["datasetId"],
            "datasetSha256": MODULE.canonical_sha256(dataset),
            "results": [
                {
                    "caseId": "answerable-1",
                    "terminalStatus": "SUCCEEDED",
                    "route": "RAG",
                    "latencyMs": 120,
                    "totalTokens": 30,
                    "answer": {"content": "Redis 使用锁。[Source 1]"},
                    "retrieval": {
                        "knowledgeToolCalls": 1,
                        "sourceIndexes": [1, 2],
                        "knowledgeContexts": [
                            "[Source 1 | documentId=doc-1]\nRedis 使用分布式锁。\n"
                            "[Source 2 | documentId=doc-2]\nLua 用于原子操作。"
                        ],
                    },
                },
                {
                    "caseId": "no-answer-1",
                    "terminalStatus": "SUCCEEDED",
                    "route": "RAG",
                    "latencyMs": 300,
                    "totalTokens": 50,
                    "answer": {"content": "知识库中没有相关信息。"},
                    "retrieval": {"knowledgeToolCalls": 1, "sourceIndexes": []},
                },
            ],
        }

    def test_scores_answer_citation_refusal_latency_and_tokens(self):
        dataset = self.dataset()
        report = MODULE.score(dataset, self.observed(dataset))

        self.assertEqual(1.0, report["metrics"]["answerCompletenessPassRate"])
        self.assertEqual(0.5, report["metrics"]["answerKeywordRecall"])
        self.assertEqual(1.0, report["metrics"]["citationValidityRate"])
        self.assertEqual(0.5, report["metrics"]["citationEvidenceCoverage"])
        self.assertEqual(1.0, report["metrics"]["citationEvidencePassRate"])
        self.assertEqual(1.0, report["metrics"]["noAnswerAccuracy"])
        self.assertEqual(1.0, report["metrics"]["casePassRate"])
        self.assertEqual(120, report["metrics"]["p50LatencyMs"])
        self.assertEqual(300, report["metrics"]["p95LatencyMs"])
        self.assertEqual(40.0, report["metrics"]["averageTokens"])

    def test_rejects_invalid_citation_index(self):
        dataset = self.dataset()
        observed = self.observed(dataset)
        observed["results"][0]["answer"]["content"] = "Redis [Source 9]"

        report = MODULE.score(dataset, observed)

        self.assertEqual(0.0, report["metrics"]["citationValidityRate"])
        self.assertFalse(report["details"][0]["passed"])

    def test_rejects_format_valid_citation_without_expected_evidence(self):
        dataset = self.dataset()
        observed = self.observed(dataset)
        observed["results"][0]["retrieval"]["knowledgeContexts"] = [
            "[Source 1 | documentId=doc-1]\nUnrelated material."
        ]

        report = MODULE.score(dataset, observed)

        self.assertEqual(1.0, report["metrics"]["citationIndexValidityRate"])
        self.assertEqual(0.0, report["metrics"]["citationEvidencePassRate"])
        self.assertFalse(report["details"][0]["checks"]["citationEvidence"])

    def test_rejects_dataset_hash_mismatch(self):
        dataset = self.dataset()
        observed = self.observed(dataset)
        observed["datasetSha256"] = "0" * 64

        with self.assertRaisesRegex(ValueError, "datasetSha256"):
            MODULE.score(dataset, observed)

    def test_no_answer_with_citation_is_not_counted_as_grounded_refusal(self):
        dataset = self.dataset()
        observed = self.observed(dataset)
        observed["results"][1]["answer"]["content"] = "知识库中没有相关信息。[Source 1]"

        report = MODULE.score(dataset, observed)

        self.assertEqual(0.0, report["metrics"]["noAnswerAccuracy"])
        self.assertFalse(report["details"][1]["checks"]["noAnswerCitationFree"])

    def test_accepts_common_grounded_refusal_wording(self):
        dataset = self.dataset()
        observed = self.observed(dataset)
        observed["results"][1]["answer"]["content"] = "现有资料未提供相关信息。"

        report = MODULE.score(dataset, observed)

        self.assertEqual(1.0, report["metrics"]["noAnswerAccuracy"])

    def test_point_66_accepts_two_of_three_expected_keywords(self):
        dataset = self.dataset()
        dataset["answerEvaluationDefaults"]["minimumKeywordRecall"] = 0.66
        dataset["testCases"][0]["expectedKeywords"] = ["Redis", "Lua", "watchdog"]
        observed = self.observed(dataset)
        observed["datasetSha256"] = MODULE.canonical_sha256(dataset)
        observed["results"][0]["answer"]["content"] = "Redis 通过 Lua 完成操作。[Source 1][Source 2]"

        report = MODULE.score(dataset, observed)

        self.assertTrue(report["details"][0]["checks"]["answerKeywords"])
        self.assertTrue(report["details"][0]["checks"]["citationEvidence"])

    def test_case_threshold_can_require_all_enumerated_keywords(self):
        dataset = self.dataset()
        dataset["testCases"][0]["minimumKeywordRecall"] = 1.0
        observed = self.observed(dataset)
        observed["datasetSha256"] = MODULE.canonical_sha256(dataset)

        report = MODULE.score(dataset, observed)

        self.assertEqual(0.5, report["details"][0]["keywordRecall"])
        self.assertFalse(report["details"][0]["checks"]["answerKeywords"])
        self.assertFalse(report["details"][0]["checks"]["citationEvidence"])

    def test_concept_aliases_accept_semantically_equivalent_wording(self):
        dataset = self.dataset()
        dataset["testCases"][0].pop("expectedKeywords")
        dataset["testCases"][0]["expectedConcepts"] = [
            {"anyOf": ["全部外部工具", "所有外部工具"]},
            {"anyOf": ["原始的 Prompt", "原始 Prompt"]},
        ]
        observed = self.observed(dataset)
        observed["datasetSha256"] = MODULE.canonical_sha256(dataset)
        observed["results"][0]["answer"]["content"] = (
            "系统会记录所有外部工具和原始 Prompt。[Source 1]"
        )
        observed["results"][0]["retrieval"]["knowledgeContexts"] = [
            "[Source 1 | documentId=doc-1]\n记录全部外部工具与原始的 Prompt。"
        ]

        report = MODULE.score(dataset, observed)

        self.assertEqual(1.0, report["details"][0]["conceptRecall"])
        self.assertEqual(1.0, report["metrics"]["answerConceptRecall"])
        self.assertEqual(2, report["details"][0]["expectedConceptCount"])
        self.assertTrue(report["details"][0]["checks"]["answerConcepts"])
        self.assertTrue(report["details"][0]["checks"]["citationEvidence"])

    def test_concept_matching_ignores_markdown_and_short_filler_words(self):
        dataset = self.dataset()
        dataset["answerEvaluationDefaults"]["minimumKeywordRecall"] = 1.0
        dataset["testCases"][0].pop("expectedKeywords")
        dataset["testCases"][0]["expectedConcepts"] = [
            {"anyOf": ["心跳超时后标记 FAILED"]},
            {"anyOf": ["Token 无法恢复"]},
        ]
        observed = self.observed(dataset)
        observed["datasetSha256"] = MODULE.canonical_sha256(dataset)
        observed["results"][0]["answer"]["content"] = (
            "系统在运行心跳超时检测后，将任务标记为可重试的 `FAILED`；"
            "尚未持久化的 **Token** 不能恢复。[Source 1]"
        )
        observed["results"][0]["retrieval"]["knowledgeContexts"] = [
            "[Source 1 | documentId=doc-1]\n"
            "运行心跳超时后，任务会被标记为 FAILED；未持久化 Token 无法恢复。"
        ]

        report = MODULE.score(dataset, observed)

        self.assertEqual("2.1.0", report["scorer"]["version"])
        self.assertEqual(1.0, report["details"][0]["conceptRecall"])
        self.assertTrue(report["details"][0]["checks"]["citationEvidence"])

    def test_concept_matching_accepts_compound_assertion_with_close_anchors(self):
        dataset = self.dataset()
        dataset["answerEvaluationDefaults"]["minimumKeywordRecall"] = 1.0
        dataset["testCases"][0].pop("expectedKeywords")
        dataset["testCases"][0]["expectedConcepts"] = [
            {"anyOf": ["事务回滚并释放预占"]},
            {"anyOf": ["无任务可恢复"]},
        ]
        observed = self.observed(dataset)
        observed["datasetSha256"] = MODULE.canonical_sha256(dataset)
        answer = "事务会回滚，同时释放 Redis 预占；数据库没有任务记录可供恢复。[Source 1]"
        observed["results"][0]["answer"]["content"] = answer
        observed["results"][0]["retrieval"]["knowledgeContexts"] = [
            f"[Source 1 | documentId=doc-1]\n{answer}"
        ]

        report = MODULE.score(dataset, observed)

        self.assertEqual(1.0, report["details"][0]["conceptRecall"])
        self.assertEqual(
            ["事务回滚并释放预占", "无任务可恢复"],
            report["details"][0]["matchedConceptAliases"],
        )

    def test_concept_matching_allows_bounded_explanation_inside_one_unit(self):
        dataset = self.dataset()
        dataset["answerEvaluationDefaults"]["minimumKeywordRecall"] = 1.0
        dataset["testCases"][0].pop("expectedKeywords")
        dataset["testCases"][0]["expectedConcepts"] = [
            {"anyOf": ["切换后端不修改检索主链路"]},
            {"anyOf": ["调用失败时保留确定性的 RRF 排序"]},
        ]
        observed = self.observed(dataset)
        observed["datasetSha256"] = MODULE.canonical_sha256(dataset)
        answer = (
            "切换后端或服务不会修改向量召回、BM25 召回和 RRF 融合组成的检索主链路；"
            "线上调用失败时，系统保留前置检索阶段产生的确定性 RRF 排序结果。[Source 1]"
        )
        observed["results"][0]["answer"]["content"] = answer
        observed["results"][0]["retrieval"]["knowledgeContexts"] = [
            f"[Source 1 | documentId=doc-1]\n{answer}"
        ]

        report = MODULE.score(dataset, observed)

        self.assertEqual(1.0, report["details"][0]["conceptRecall"])
        self.assertTrue(report["details"][0]["checks"]["citationEvidence"])

    def test_concept_matching_requires_anchors_in_same_semantic_unit(self):
        dataset = self.dataset()
        dataset["testCases"][0].pop("expectedKeywords")
        dataset["testCases"][0]["expectedConcepts"] = [
            {"anyOf": ["事务回滚并释放预占"]},
        ]
        observed = self.observed(dataset)
        observed["datasetSha256"] = MODULE.canonical_sha256(dataset)
        observed["results"][0]["answer"]["content"] = (
            "事务回滚发生在请求阶段。[Source 1]\n"
            "另一个无关场景会释放预占。[Source 1]"
        )

        report = MODULE.score(dataset, observed)

        self.assertEqual(0.0, report["details"][0]["conceptRecall"])

    def test_concept_matching_preserves_negation(self):
        dataset = self.dataset()
        dataset["testCases"][0].pop("expectedKeywords")
        dataset["testCases"][0]["expectedConcepts"] = [
            {"anyOf": ["终态不能重新进入 RUNNING"]},
        ]
        observed = self.observed(dataset)
        observed["datasetSha256"] = MODULE.canonical_sha256(dataset)
        observed["results"][0]["answer"]["content"] = (
            "终态可以重新进入 `RUNNING`。[Source 1]"
        )

        report = MODULE.score(dataset, observed)

        self.assertEqual(0.0, report["details"][0]["conceptRecall"])

    def test_missing_case_reduces_coverage(self):
        dataset = self.dataset()
        observed = self.observed(dataset)
        observed["results"] = observed["results"][:1]

        report = MODULE.score(dataset, observed)

        self.assertEqual(0.5, report["coverage"]["rate"])
        self.assertFalse(report["details"][1]["evaluated"])

    def test_local_gold_has_stable_ids_and_no_answer_cases(self):
        dataset_path = MODULE_PATH.parents[1] / "evaluation-data" / "rag-gold-v1.json"
        dataset = MODULE.load_json(dataset_path)
        case_ids = [case.get("id") for case in dataset["testCases"]]

        self.assertEqual("jmindops-rag-gold-v1", dataset["datasetId"])
        self.assertEqual(len(case_ids), len(set(case_ids)))
        self.assertNotIn(None, case_ids)
        self.assertGreaterEqual(
            sum(bool(case.get("expectedNoAnswer")) for case in dataset["testCases"]),
            5,
        )


if __name__ == "__main__":
    unittest.main()
