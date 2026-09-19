from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace


SCRIPTS_DIR = Path(__file__).resolve().parents[1]


def load_module(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS_DIR / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


prepare = load_module("prepare_t2ranking", "prepare-t2ranking.py")
score = load_module("score_t2ranking", "score-t2ranking.py")


class T2RankingAdapterTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.data = self.root / "source" / "data"
        self.data.mkdir(parents=True)
        (self.data / "queries.dev.tsv").write_text(
            "q1\t第一个问题\nq2\t第二个问题\nq3\t第三个问题\n",
            encoding="utf-8",
        )
        (self.data / "qrels.dev.tsv").write_text(
            "\n".join(
                [
                    "q1 0 p1 3",
                    "q1 0 p2 1",
                    "q1 0 p5 0",
                    "q2 0 p3 2",
                    "q2 0 p4 0",
                    "q3 0 p6 1",
                    "q3 0 p7 0",
                ]
            )
            + "\n",
            encoding="utf-8",
        )
        (self.data / "collection.tsv").write_text(
            "".join(
                f"p{index}\t这是第 {index} 个测试段落。\n"
                for index in range(1, 13)
            ),
            encoding="utf-8",
        )

    def tearDown(self):
        self.temp.cleanup()

    def args(self, output: Path):
        return SimpleNamespace(
            data_dir=self.root / "source",
            output_dir=output,
            split="dev",
            collection=None,
            queries=None,
            qrels=None,
            query_count=2,
            corpus_size=8,
            shards=2,
            seed=42,
            relevance_threshold=1,
            max_relevant_per_query=20,
            top_k=5,
            modes=["VECTOR", "HYBRID_RRF"],
        )

    def test_prepares_deterministic_jmindops_dataset_and_shards(self):
        first = prepare.prepare_dataset(self.args(self.root / "out-a"))
        second = prepare.prepare_dataset(self.args(self.root / "out-b"))

        first_dataset = json.loads(
            first["dataset"].read_text(encoding="utf-8")
        )
        second_dataset = json.loads(
            second["dataset"].read_text(encoding="utf-8")
        )
        self.assertEqual(first_dataset, second_dataset)
        self.assertEqual("REVIEWED", first_dataset["annotationStatus"])
        self.assertEqual("benchmark", first_dataset["datasetRole"])
        self.assertTrue(first_dataset["frozen"])
        self.assertEqual(2, len(first_dataset["testCases"]))
        self.assertEqual(
            first_dataset["benchmark"]["corpusFingerprint"],
            second_dataset["benchmark"]["corpusFingerprint"],
        )

        first_shards = sorted(first["corpus_dir"].glob("*.md"))
        second_shards = sorted(second["corpus_dir"].glob("*.md"))
        self.assertEqual(2, len(first_shards))
        self.assertEqual(
            [path.read_text(encoding="utf-8") for path in first_shards],
            [path.read_text(encoding="utf-8") for path in second_shards],
        )
        corpus_text = "\n".join(
            path.read_text(encoding="utf-8") for path in first_shards
        )
        for test_case in first_dataset["testCases"]:
            for marker in test_case["expectedKeywords"]:
                self.assertIn(marker, corpus_text)

    def test_scores_graded_relevance_from_retrieved_markers(self):
        outputs = prepare.prepare_dataset(self.args(self.root / "out-score"))
        manifest = json.loads(
            outputs["manifest"].read_text(encoding="utf-8")
        )
        query = manifest["queries"][0]
        relevant_pid = query["relevant"][0]["pid"]
        other_pid = next(
            pid
            for pid in manifest["passages"]
            if pid
            not in {item["pid"] for item in query["relevant"]}
        )
        relevant_marker = manifest["passages"][relevant_pid]["marker"]
        other_marker = manifest["passages"][other_pid]["marker"]
        artifact = {
            "run": {"topK": 2},
            "result": {
                "results": [
                    {
                        "mode": "VECTOR",
                        "status": "COMPLETED",
                        "details": [
                            {
                                "query": query["query"],
                                "retrievedSources": [
                                    f"# {other_marker}\n{other_marker}",
                                    f"# {relevant_marker}\n{relevant_marker}",
                                ],
                            }
                        ],
                    }
                ]
            },
        }

        scored = score.score_artifact(manifest, artifact)
        mode = scored["modes"][0]
        self.assertEqual(0.5, mode["mrr"])
        self.assertGreater(mode["nDCG"], 0.0)
        self.assertLess(mode["nDCG"], 1.0)
        self.assertEqual(0, mode["unknownSourceCount"])

    def test_marker_matching_does_not_confuse_prefix_ids(self):
        mapping = {"T2_PID_1": "1", "T2_PID_10": "10"}
        self.assertEqual(
            "T2_PID_10",
            score.extract_marker("passage T2_PID_10 content", mapping),
        )

    def test_long_passages_repeat_markers_for_downstream_chunks(self):
        passage = "长文档" * 1000
        anchored = prepare.add_marker_anchors(passage, "T2_PID_long")
        self.assertGreater(anchored.count("T2_PID_long"), 1)
        for window_start in range(0, len(anchored), 1000):
            window = anchored[window_start : window_start + 2000]
            if len(window) >= 1000:
                self.assertIn("T2_PID_long", window)

    def test_duplicate_passage_chunks_do_not_inflate_relevance(self):
        manifest = {
            "parameters": {"relevanceThreshold": 1},
            "corpusFingerprint": "fixture",
            "passages": {"p1": {"marker": "T2_PID_p1"}},
            "queries": [
                {
                    "qid": "q1",
                    "query": "query",
                    "relevant": [
                        {"pid": "p1", "marker": "T2_PID_p1", "grade": 1}
                    ],
                    "judged": [{"pid": "p1", "grade": 1}],
                }
            ],
        }
        artifact = {
            "results": [
                {
                    "mode": "VECTOR",
                    "status": "COMPLETED",
                    "details": [
                        {
                            "query": "query",
                            "retrievedSources": ["T2_PID_p1", "T2_PID_p1"],
                        }
                    ],
                }
            ]
        }

        mode = score.score_artifact(manifest, artifact)["modes"][0]
        self.assertEqual(0.5, mode["precision"])
        self.assertEqual(1, mode["duplicateSourceCount"])

    def test_scoring_uses_full_judged_qrels_when_api_labels_are_capped(self):
        manifest = {
            "parameters": {"relevanceThreshold": 1},
            "corpusFingerprint": "fixture",
            "passages": {
                "p1": {"marker": "T2_PID_p1"},
                "p2": {"marker": "T2_PID_p2"},
            },
            "queries": [
                {
                    "qid": "q1",
                    "query": "query",
                    "relevant": [
                        {"pid": "p1", "marker": "T2_PID_p1", "grade": 3}
                    ],
                    "judged": [
                        {"pid": "p1", "grade": 3},
                        {"pid": "p2", "grade": 1},
                    ],
                }
            ],
        }
        artifact = {
            "results": [
                {
                    "mode": "VECTOR",
                    "status": "COMPLETED",
                    "details": [
                        {
                            "query": "query",
                            "retrievedSources": ["T2_PID_p2"],
                        }
                    ],
                }
            ]
        }

        scored = score.score_artifact(manifest, artifact)
        self.assertEqual(0.5, scored["modes"][0]["recall"])
        self.assertEqual(1.0, scored["modes"][0]["precision"])


if __name__ == "__main__":
    unittest.main()
