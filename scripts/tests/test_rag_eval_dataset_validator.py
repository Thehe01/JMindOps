import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "validate-rag-eval-dataset.py"
SPEC = importlib.util.spec_from_file_location("validate_rag_eval_dataset", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class RagEvalDatasetValidatorTest(unittest.TestCase):
    def reviewed_dataset(self, digest):
        return {
            "datasetId": "rag-test",
            "datasetRole": "development",
            "frozen": False,
            "annotationStatus": "REVIEWED",
            "review": {
                "sourceEvidence": {
                    "primaryDocuments": [{"path": "source.md", "sha256": digest}],
                    "supportingDocuments": [],
                }
            },
            "testCases": [
                {
                    "id": "positive", "query": "what", "expectedKeywords": ["evidence"],
                    "sourcePaths": ["source.md"],
                },
                {"id": "negative", "query": "missing", "expectedNoAnswer": True},
            ],
        }

    def test_accepts_matching_review_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.md"
            source.write_text("evidence", encoding="utf-8")
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            MODULE.validate_dataset(self.reviewed_dataset(digest), root)

    def test_rejects_stale_review_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "source.md").write_text("changed", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "复核证据已变化"):
                MODULE.validate_dataset(self.reviewed_dataset("0" * 64), root)

    def test_exposed_test_regression_keeps_frozen_dataset_but_allows_development_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.md"
            source.write_text("evidence", encoding="utf-8")
            development = root / "development.json"
            development.write_text('{"testCases": []}', encoding="utf-8")
            dataset = self.reviewed_dataset(hashlib.sha256(source.read_bytes()).hexdigest())
            dataset["datasetRole"] = "test"
            dataset["frozen"] = True
            dataset["review"]["developmentDatasetEvidence"] = {
                "path": "development.json",
                "sha256": "0" * 64,
            }

            MODULE.validate_dataset(
                dataset, root, allow_exposed_test_regression=True
            )

    def test_exposed_test_regression_rejects_mutable_development_dataset(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.md"
            source.write_text("evidence", encoding="utf-8")
            dataset = self.reviewed_dataset(hashlib.sha256(source.read_bytes()).hexdigest())

            with self.assertRaisesRegex(ValueError, "只接受 REVIEWED"):
                MODULE.validate_dataset(
                    dataset, root, allow_exposed_test_regression=True
                )

    def test_draft_with_evidence_still_rejects_stale_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "source.md").write_text("changed", encoding="utf-8")
            dataset = self.reviewed_dataset("0" * 64)
            dataset["annotationStatus"] = "DRAFT"
            with self.assertRaisesRegex(ValueError, "复核证据已变化"):
                MODULE.validate_dataset(dataset, root, allow_draft=True)

    def test_keywords_must_exist_in_first_source_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = root / "first.md"
            second = root / "second.md"
            first.write_text("unrelated", encoding="utf-8")
            second.write_text("evidence", encoding="utf-8")
            dataset = self.reviewed_dataset(hashlib.sha256(first.read_bytes()).hexdigest())
            dataset["review"]["sourceEvidence"]["primaryDocuments"][0]["path"] = "first.md"
            dataset["review"]["sourceEvidence"]["supportingDocuments"] = [
                {"path": "second.md", "sha256": hashlib.sha256(second.read_bytes()).hexdigest()}
            ]
            dataset["testCases"][0]["sourcePaths"] = ["first.md", "second.md"]
            with self.assertRaisesRegex(ValueError, "主证据 first.md"):
                MODULE.validate_dataset(dataset, root)

    def test_rejects_keyword_recall_threshold_outside_unit_interval(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.md"
            source.write_text("evidence", encoding="utf-8")
            dataset = self.reviewed_dataset(hashlib.sha256(source.read_bytes()).hexdigest())
            dataset["testCases"][0]["minimumKeywordRecall"] = 1.01
            with self.assertRaisesRegex(ValueError, "minimumKeywordRecall"):
                MODULE.validate_dataset(dataset, root)

    def test_accepts_concept_when_any_alias_exists_in_primary_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.md"
            source.write_text("系统记录全部外部工具", encoding="utf-8")
            dataset = self.reviewed_dataset(hashlib.sha256(source.read_bytes()).hexdigest())
            dataset["testCases"][0].pop("expectedKeywords")
            dataset["testCases"][0]["expectedConcepts"] = [
                {"anyOf": ["所有外部工具", "全部外部工具"]}
            ]

            MODULE.validate_dataset(dataset, root)

    def test_rejects_malformed_expected_concept_alias_group(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.md"
            source.write_text("evidence", encoding="utf-8")
            dataset = self.reviewed_dataset(hashlib.sha256(source.read_bytes()).hexdigest())
            dataset["testCases"][0]["expectedConcepts"] = [{"anyOf": []}]

            with self.assertRaisesRegex(ValueError, "expectedConcepts"):
                MODULE.validate_dataset(dataset, root)

    def test_accepts_runtime_knowledge_base_binding_without_dataset_uuid(self):
        MODULE.validate_knowledge_base_binding(
            {"knowledgeBaseBinding": "runtimeParameter", "kbId": None}
        )

    def test_rejects_placeholder_dataset_knowledge_base_id(self):
        with self.assertRaisesRegex(ValueError, "有效 kbId"):
            MODULE.validate_knowledge_base_binding(
                {"knowledgeBaseBinding": "dataset", "kbId": "REPLACE_WITH_UUID"}
            )

    def test_requires_explicit_frozen_test_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.md"
            source.write_text("evidence", encoding="utf-8")
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            with self.assertRaisesRegex(ValueError, "datasetRole=test"):
                MODULE.validate_dataset(
                    self.reviewed_dataset(digest), root, require_frozen_test=True
                )

    def test_rejects_exact_query_reused_from_pinned_development_set(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.md"
            source.write_text("evidence", encoding="utf-8")
            development = root / "development.json"
            development.write_text(
                json.dumps({"testCases": [{"id": "dev", "query": "What?"}]}),
                encoding="utf-8",
            )
            dataset = self.reviewed_dataset(hashlib.sha256(source.read_bytes()).hexdigest())
            dataset["review"]["developmentDatasetEvidence"] = {
                "path": "development.json",
                "sha256": hashlib.sha256(development.read_bytes()).hexdigest(),
            }
            with self.assertRaisesRegex(ValueError, "完全重复问题"):
                MODULE.validate_dataset(dataset, root)

    def test_rejects_exact_query_reused_from_pinned_prior_test_set(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.md"
            source.write_text("evidence", encoding="utf-8")
            prior = root / "prior.json"
            prior.write_text(
                json.dumps({"testCases": [{"id": "old-test", "query": "What?"}]}),
                encoding="utf-8",
            )
            dataset = self.reviewed_dataset(hashlib.sha256(source.read_bytes()).hexdigest())
            dataset["review"]["priorDatasetEvidence"] = [
                {
                    "path": "prior.json",
                    "sha256": hashlib.sha256(prior.read_bytes()).hexdigest(),
                }
            ]
            with self.assertRaisesRegex(ValueError, "历史题集存在完全重复问题"):
                MODULE.validate_dataset(dataset, root)

    def test_rejects_near_duplicate_from_pinned_dataset_when_threshold_is_set(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.md"
            source.write_text("evidence", encoding="utf-8")
            prior = root / "prior.json"
            prior.write_text(
                json.dumps(
                    {
                        "testCases": [
                            {"id": "old-test", "query": "系统如何处理任务恢复扫描？"}
                        ]
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            dataset = self.reviewed_dataset(hashlib.sha256(source.read_bytes()).hexdigest())
            dataset["testCases"][0]["query"] = "系统怎样处理任务恢复扫描？"
            dataset["review"]["priorDatasetEvidence"] = [
                {
                    "path": "prior.json",
                    "sha256": hashlib.sha256(prior.read_bytes()).hexdigest(),
                }
            ]
            dataset["review"]["nearDuplicateThreshold"] = 0.8
            with self.assertRaisesRegex(ValueError, "历史题集存在近重复问题"):
                MODULE.validate_dataset(dataset, root)

    def test_rejects_stale_review_summary_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.md"
            source.write_text("evidence", encoding="utf-8")
            dataset = self.reviewed_dataset(hashlib.sha256(source.read_bytes()).hexdigest())
            dataset["review"]["reviewSummary"] = {"answerableCases": 999}
            with self.assertRaisesRegex(ValueError, "answerableCases"):
                MODULE.validate_dataset(dataset, root)

    def test_local_gold_is_explicitly_a_development_set(self):
        dataset = MODULE.load_json(MODULE_PATH.parents[1] / "evaluation-data" / "rag-gold-v1.json")
        self.assertEqual("development", dataset.get("datasetRole"))
        self.assertFalse(dataset.get("frozen"))
        answerable_cases = [
            case for case in dataset["testCases"] if not case.get("expectedNoAnswer")
        ]
        self.assertTrue(answerable_cases)
        self.assertTrue(all(case.get("sourcePaths") for case in answerable_cases))
        self.assertTrue(all(case.get("expectedConcepts") for case in answerable_cases))
        self.assertTrue(all("expectedKeywords" not in case for case in answerable_cases))
        self.assertFalse(
            any(
                unsupported in case["query"]
                for case in answerable_cases
                for unsupported in ("HNSW", "IVFFlat")
            )
        )

    def test_prepared_t2ranking_uses_its_manifest_as_review_evidence(self):
        dataset_path = (
            MODULE_PATH.parents[1]
            / "evaluation-data/prepared/t2ranking-mteb-q30-c500-seed2026-v2/evaluation.ready.json"
        )
        dataset = MODULE.load_json(dataset_path)
        MODULE.validate_dataset(
            dataset, MODULE_PATH.parents[1], dataset_path=dataset_path
        )


if __name__ == "__main__":
    unittest.main()
