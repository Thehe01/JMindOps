import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "run-bfcl-evaluation.py"
SPEC = importlib.util.spec_from_file_location("run_bfcl_evaluation", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class BfclRunnerTest(unittest.TestCase):
    def test_validates_fixed_ids_and_hashes_referenced_official_files(self):
        with tempfile.TemporaryDirectory() as directory:
            data_directory = Path(directory)
            data_file = data_directory / "BFCL_v4_simple_python.json"
            data_file.write_text(
                '\n'.join([
                    json.dumps({"id": "simple_python_0"}),
                    json.dumps({"id": "simple_python_1"}),
                ]),
                encoding="utf-8",
            )

            evidence = MODULE.validate_official_ids(
                data_directory,
                {"simple_python": ["simple_python_0", "simple_python_1"]},
            )

            self.assertEqual("BFCL_v4_simple_python.json", evidence[0]["file"])
            self.assertEqual(64, len(evidence[0]["sha256"]))

    def test_rejects_unknown_official_id(self):
        with tempfile.TemporaryDirectory() as directory:
            data_directory = Path(directory)
            (data_directory / "BFCL_v4_simple_python.json").write_text(
                json.dumps({"id": "simple_python_0"}) + "\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "不存在"):
                MODULE.validate_official_ids(
                    data_directory,
                    {"simple_python": ["simple_python_999"]},
                )

    def test_collects_inference_errors_from_generated_results(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root = Path(directory)
            result_directory = run_root / "result" / "custom-relay_model-FC"
            result_directory.mkdir(parents=True)
            (result_directory / "BFCL_v4_example_result.json").write_text(
                json.dumps({"id": "example_0", "result": []})
                + "\n"
                + json.dumps(
                    {
                        "id": "example_1",
                        "result": "Error during inference: Connection error.",
                        "traceback": "redacted stack",
                    }
                )
                + "\n",
                encoding="utf-8",
            )

            errors = MODULE.collect_inference_errors(
                run_root, "custom-relay/model-FC"
            )

            self.assertEqual(1, len(errors))
            self.assertEqual("example_1", errors[0]["caseId"])
            self.assertNotIn("traceback", errors[0])

    def test_partial_commands_use_official_run_ids_and_partial_eval(self):
        commands = MODULE.build_commands(
            "python",
            "Qwen/Qwen3-1.7B-FC",
            ["simple_python", "multiple"],
            partial=True,
            mode="both",
            endpoint="http://127.0.0.1:8002/v1",
            backend="vllm",
            num_threads=1,
            temperature=0.001,
            include_input_log=False,
            allow_overwrite=False,
        )

        self.assertIn("--run-ids", commands[0])
        self.assertIn("--skip-server-setup", commands[0])
        self.assertIn("--partial-eval", commands[1])
        self.assertEqual("simple_python,multiple", commands[1][-2])

    def test_reads_relay_config_and_normalizes_openai_base_url(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = Path(directory) / ".api_key"
            config_path.write_text(
                "RELAY_API_KEY=secret\n"
                "RELAY_BASE_URL=https://relay.example.test\n"
                "RELAY_CHAT_MODEL=example-model\n",
                encoding="utf-8",
            )

            config = MODULE.read_relay_config(config_path)

            self.assertEqual("secret", config["RELAY_API_KEY"])
            self.assertEqual("https://relay.example.test/v1", config["RELAY_BASE_URL"])
            self.assertEqual("example-model", config["RELAY_CHAT_MODEL"])

    def test_custom_api_commands_use_bootstrap_without_local_server_flags(self):
        commands = MODULE.build_commands(
            "python",
            "custom-relay/example-model-FC",
            ["simple_python"],
            partial=True,
            mode="both",
            endpoint=None,
            backend="vllm",
            num_threads=1,
            temperature=0.001,
            include_input_log=False,
            allow_overwrite=False,
            custom_api=True,
        )

        self.assertEqual(str(MODULE.CUSTOM_API_ENTRYPOINT), commands[0][1])
        self.assertNotIn("--backend", commands[0])
        self.assertNotIn("--skip-server-setup", commands[0])
        self.assertIn("--partial-eval", commands[1])

    def test_custom_registry_name_cannot_override_official_model(self):
        self.assertEqual(
            "custom-relay/vendor-model-FC",
            MODULE.custom_registry_name("vendor/model", None),
        )
        with self.assertRaisesRegex(ValueError, "custom-"):
            MODULE.custom_registry_name("vendor/model", "gpt-5.2-2025-12-11-FC")

    def test_api_agent_policy_is_explicit_and_defaults_to_strict(self):
        strict_args = MODULE.parse_args(["--api-config", "relay.env"])
        baseline_args = MODULE.parse_args(
            ["--api-config", "relay.env", "--api-agent-policy", "none"]
        )

        self.assertEqual("strict-v1", strict_args.api_agent_policy)
        self.assertEqual("none", baseline_args.api_agent_policy)
        self.assertEqual(120, strict_args.api_timeout_seconds)
        self.assertEqual(2, strict_args.api_max_retries)
        self.assertEqual("high", strict_args.api_reasoning_effort)

    def test_release_version_must_match_package_and_subset(self):
        lock = {"version": "2026.3.23"}
        subset = {"bfclEvalVersion": "2026.3.23"}

        MODULE.validate_release(lock, "2026.3.23", subset)
        with self.assertRaisesRegex(ValueError, "版本不一致"):
            MODULE.validate_release(lock, "2026.4.1", subset)


if __name__ == "__main__":
    unittest.main()
