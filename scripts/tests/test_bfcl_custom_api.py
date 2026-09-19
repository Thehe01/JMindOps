import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "bfcl-custom-api.py"
BFCL_AVAILABLE = importlib.util.find_spec("bfcl_eval") is not None


@unittest.skipUnless(BFCL_AVAILABLE, "bfcl-eval is installed only in the BFCL environment")
class BfclCustomApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("bfcl_custom_api", SCRIPT)
        cls.module = importlib.util.module_from_spec(spec)
        assert spec.loader is not None
        spec.loader.exec_module(cls.module)

    def new_handler(self):
        handler = object.__new__(self.module.JMindOpsResponsesHandler)
        handler.is_fc_model = True
        return handler

    def test_strict_policy_is_injected_as_developer_message(self):
        handler = self.new_handler()
        test_entry = {"question": [[{"role": "user", "content": "Do it."}]]}

        with patch.dict(os.environ, {"BFCL_CUSTOM_AGENT_POLICY": "strict-v1"}):
            inference_data = handler._pre_query_processing_FC({}, test_entry)

        self.assertEqual("developer", inference_data["message"][0]["role"])
        self.assertIn("Preserve user-provided literal", inference_data["message"][0]["content"])
        self.assertIn("Never materialize an optional default", inference_data["message"][0]["content"])
        self.assertIn("make no tool calls", inference_data["message"][0]["content"])
        self.assertIn("clear, unique antecedents", inference_data["message"][0]["content"])
        self.assertIn("multiple independent items", inference_data["message"][0]["content"])
        self.assertIn("exact same function and arguments", inference_data["message"][0]["content"])

    def test_none_policy_keeps_official_empty_initial_history(self):
        handler = self.new_handler()
        test_entry = {"question": [[{"role": "user", "content": "Do it."}]]}

        with patch.dict(os.environ, {"BFCL_CUSTOM_AGENT_POLICY": "none"}):
            inference_data = handler._pre_query_processing_FC({}, test_entry)

        self.assertEqual([], inference_data["message"])

    def test_natural_language_completion_decodes_as_no_tool_call(self):
        handler = self.new_handler()

        self.assertEqual([], handler.decode_execute("Completed.", False))
        self.assertEqual([], handler.decode_ast("Completed.", "Python", False))

    def test_client_timeout_and_retry_are_configurable(self):
        handler = self.new_handler()

        with patch.dict(
            os.environ,
            {
                "BFCL_CUSTOM_API_TIMEOUT_SECONDS": "45",
                "BFCL_CUSTOM_API_MAX_RETRIES": "1",
            },
            clear=False,
        ):
            kwargs = handler._build_client_kwargs()

        self.assertEqual(45.0, kwargs["timeout"])
        self.assertEqual(1, kwargs["max_retries"])

    def test_high_reasoning_effort_is_added_to_reasoning_requests(self):
        handler = self.new_handler()
        captured = {}

        def fake_generate(**kwargs):
            captured.update(kwargs)
            return "response", 1.0

        with (
            patch.dict(
                os.environ,
                {"BFCL_CUSTOM_REASONING_EFFORT": "high"},
                clear=False,
            ),
            patch.object(
                self.module.OpenAIResponsesHandler,
                "generate_with_backoff",
                side_effect=fake_generate,
            ),
        ):
            response = handler.generate_with_backoff(
                model="example", reasoning={"summary": "auto"}
            )

        self.assertEqual(("response", 1.0), response)
        self.assertEqual("high", captured["reasoning"]["effort"])

    def test_optional_parameter_instruction_is_added_at_field_level(self):
        tools = [
            {
                "type": "function",
                "name": "calculate",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "value": {"type": "integer"},
                        "rounding": {
                            "type": "integer",
                            "description": "Default 0",
                        },
                    },
                    "required": ["value"],
                },
            }
        ]

        result = self.module.reinforce_optional_parameter_policy(tools)

        self.assertNotIn(
            "Omit this optional argument",
            result[0]["parameters"]["properties"]["value"].get("description", ""),
        )
        self.assertIn(
            "Omit this optional argument",
            result[0]["parameters"]["properties"]["rounding"]["description"],
        )

    def test_schema_defaults_are_omitted_from_normalized_tool_calls(self):
        tools = [
            {
                "name": "calculate",
                "parameters": {
                    "properties": {
                        "value": {"type": "integer"},
                        "rounding": {
                            "type": "integer",
                            "description": "Optional. Default 0.1",
                        },
                    },
                    "required": ["value"],
                },
            }
        ]
        defaults = self.module.collect_optional_defaults(tools)

        normalized = self.module.omit_redundant_defaults(
            [{"calculate": '{"value":5,"rounding":0.1}'}], defaults
        )

        self.assertEqual([{"calculate": '{"value":5}'}], normalized)

    def test_identical_tool_calls_are_deduplicated(self):
        responses = [
            {"circle_calculate_area": '{"radius":5}'},
            {"circle_calculate_circumference": '{"diameter":10}'},
            {"circle_calculate_circumference": '{"diameter":10}'},
        ]

        normalized = self.module.deduplicate_identical_tool_calls(responses)

        self.assertEqual(responses[:2], normalized)

    def test_equivalent_json_argument_order_is_deduplicated(self):
        responses = [
            {"calculate": '{"left":1,"right":2}'},
            {"calculate": '{"right":2,"left":1}'},
        ]

        normalized = self.module.deduplicate_identical_tool_calls(responses)

        self.assertEqual([responses[0]], normalized)

    def test_distinct_parallel_tool_calls_are_preserved(self):
        responses = [
            {"calculate": '{"value":1}'},
            {"calculate": '{"value":2}'},
            {"calculate_other": '{"value":1}'},
        ]

        normalized = self.module.deduplicate_identical_tool_calls(responses)

        self.assertEqual(responses, normalized)

    def test_malformed_tool_arguments_are_not_deduplicated(self):
        responses = [
            {"calculate": "not-json"},
            {"calculate": "not-json"},
        ]

        normalized = self.module.deduplicate_identical_tool_calls(responses)

        self.assertEqual(responses, normalized)

    def test_single_sample_standard_deviation_is_zero(self):
        self.assertEqual(0.0, self.module.sample_stdev([4.2]))
        self.assertAlmostEqual(2**0.5, self.module.sample_stdev([1.0, 3.0]))


if __name__ == "__main__":
    unittest.main()
