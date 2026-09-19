#!/usr/bin/env python3
"""Register a user-supplied OpenAI Responses model before invoking BFCL CLI."""

from __future__ import annotations

import json
import os
import re
import runpy
import statistics

from bfcl_eval.constants.model_config import MODEL_CONFIG_MAPPING, ModelConfig
from bfcl_eval.model_handler.api_inference.openai_response import (
    OpenAIResponsesHandler,
)


STRICT_AGENT_POLICY_NAME = "strict-v1"
STRICT_AGENT_POLICY = """You are a stateful tool-using agent. Follow these rules:
1. When a supplied tool matches the request, use the tool instead of answering from your own calculation or assumptions. Treat the tool as authoritative even if the input appears inconsistent; let the tool validate it.
2. Preserve user-provided literal argument values, entity names, and operand order exactly. Do not expand, normalize, or annotate string values.
3. Never materialize an optional default in a tool call. If the user did not explicitly provide an optional argument, omit that argument even when its default is shown in the schema.
4. Resolve clear, unique antecedents from earlier turns. If a required target or argument is still missing or has multiple plausible interpretations after considering the conversation, make no tool calls for that request and ask for the missing value. Do not infer a target from a value mentioned only as a comparison or reference. After the user clarifies it, execute the full pending request.
5. Do not emulate a missing operation with different tools. Leave the request pending and explain the missing capability. If a later turn adds the needed tool or parameter, execute the full pending request with it.
6. For directional comparisons such as diff, keep the current, final, or primary target first and the previous or reference target second unless the user explicitly requests the reverse.
7. When the user requests multiple independent items, entities, or properties, issue a separate tool call for every requested item in the same response when possible. Never stop after only the first item.
8. Before returning a batch of tool calls, merge equivalent requests that resolve to the exact same function and arguments. Emit that identical call only once.
9. For each actionable turn, continue issuing necessary tool calls until the request is complete. Use natural language only after the required tool calls have finished."""
OPTIONAL_PARAMETER_INSTRUCTION = (
    "IMPORTANT: Omit this optional argument unless the user explicitly supplies its value."
)
DESCRIPTION_DEFAULT_PATTERN = re.compile(
    r"\bdefault(?:s\s+to|\s+is)?\s*[:=]?\s*"
    r"(?P<value>true|false|null|-?(?:\d+(?:\.\d*)?|\.\d+))\b",
    re.IGNORECASE,
)


def reinforce_optional_parameter_policy(tools: list[dict]) -> list[dict]:
    """Place the optional-argument rule next to the field the model is filling."""
    for tool in tools:
        function = tool.get("function", tool)
        parameters = function.get("parameters", {})
        required = set(parameters.get("required", []))
        for name, schema in parameters.get("properties", {}).items():
            if name in required or not isinstance(schema, dict):
                continue
            description = str(schema.get("description", "")).strip()
            if OPTIONAL_PARAMETER_INSTRUCTION not in description:
                schema["description"] = (
                    f"{description} {OPTIONAL_PARAMETER_INSTRUCTION}".strip()
                )
    return tools


def collect_optional_defaults(tools: list[dict]) -> dict[str, dict[str, object]]:
    defaults: dict[str, dict[str, object]] = {}
    for tool in tools:
        function = tool.get("function", tool)
        function_name = function.get("name")
        parameters = function.get("parameters", {})
        required = set(parameters.get("required", []))
        if not isinstance(function_name, str):
            continue
        for name, schema in parameters.get("properties", {}).items():
            if name in required or not isinstance(schema, dict):
                continue
            if "default" in schema:
                default = schema["default"]
            else:
                match = DESCRIPTION_DEFAULT_PATTERN.search(
                    str(schema.get("description", ""))
                )
                if match is None:
                    continue
                default = json.loads(match.group("value").lower())
            defaults.setdefault(function_name, {})[name] = default
    return defaults


def omit_redundant_defaults(
    model_responses: object, defaults: dict[str, dict[str, object]]
) -> object:
    if not isinstance(model_responses, list):
        return model_responses
    normalized: list[object] = []
    for invocation in model_responses:
        if not isinstance(invocation, dict) or len(invocation) != 1:
            normalized.append(invocation)
            continue
        function_name, raw_arguments = next(iter(invocation.items()))
        function_defaults = defaults.get(function_name, {})
        if not function_defaults or not isinstance(raw_arguments, str):
            normalized.append(invocation)
            continue
        try:
            arguments = json.loads(raw_arguments)
        except json.JSONDecodeError:
            normalized.append(invocation)
            continue
        if not isinstance(arguments, dict):
            normalized.append(invocation)
            continue
        for name, default in function_defaults.items():
            if arguments.get(name) == default:
                arguments.pop(name)
        normalized.append(
            {
                function_name: json.dumps(
                    arguments, ensure_ascii=False, separators=(",", ":")
                )
            }
        )
    return normalized


def deduplicate_identical_tool_calls(model_responses: object) -> object:
    """Keep the first occurrence of semantically identical calls in one batch."""
    if not isinstance(model_responses, list):
        return model_responses
    normalized: list[object] = []
    seen: set[tuple[str, str]] = set()
    for invocation in model_responses:
        if not isinstance(invocation, dict) or len(invocation) != 1:
            normalized.append(invocation)
            continue
        function_name, raw_arguments = next(iter(invocation.items()))
        if not isinstance(function_name, str) or not isinstance(raw_arguments, str):
            normalized.append(invocation)
            continue
        try:
            arguments = json.loads(raw_arguments)
        except json.JSONDecodeError:
            normalized.append(invocation)
            continue
        fingerprint = (
            function_name,
            json.dumps(
                arguments,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ),
        )
        if fingerprint in seen:
            continue
        seen.add(fingerprint)
        normalized.append(invocation)
    return normalized


def sample_stdev(values) -> float:
    materialized = list(values)
    if len(materialized) < 2:
        return 0.0
    return statistics.stdev(materialized)


class _StatisticsCompat:
    def __getattr__(self, name):
        return getattr(statistics, name)

    @staticmethod
    def stdev(values) -> float:
        return sample_stdev(values)


def install_bfcl_single_sample_compat() -> None:
    from bfcl_eval.eval_checker import eval_runner_helper

    eval_runner_helper.statistics = _StatisticsCompat()


class JMindOpsResponsesHandler(OpenAIResponsesHandler):
    """OpenAI Responses adapter with benchmark-independent agent guardrails."""

    def _build_client_kwargs(self):
        kwargs = super()._build_client_kwargs()
        kwargs["timeout"] = float(
            os.environ.get("BFCL_CUSTOM_API_TIMEOUT_SECONDS", "120")
        )
        kwargs["max_retries"] = int(
            os.environ.get("BFCL_CUSTOM_API_MAX_RETRIES", "2")
        )
        return kwargs

    def generate_with_backoff(self, **kwargs):
        reasoning_effort = os.environ.get(
            "BFCL_CUSTOM_REASONING_EFFORT", "high"
        ).strip()
        if reasoning_effort != "provider-default" and "reasoning" in kwargs:
            kwargs["reasoning"] = {
                **kwargs["reasoning"],
                "effort": reasoning_effort,
            }
        return super().generate_with_backoff(**kwargs)

    def _pre_query_processing_FC(self, inference_data: dict, test_entry: dict) -> dict:
        inference_data = super()._pre_query_processing_FC(inference_data, test_entry)
        policy = os.environ.get(
            "BFCL_CUSTOM_AGENT_POLICY", STRICT_AGENT_POLICY_NAME
        ).strip()
        if policy == STRICT_AGENT_POLICY_NAME:
            inference_data["message"].append(
                {"role": "developer", "content": STRICT_AGENT_POLICY}
            )
        elif policy != "none":
            raise RuntimeError(f"Unsupported BFCL custom agent policy: {policy}")
        return inference_data

    def _compile_tools(self, inference_data: dict, test_entry: dict) -> dict:
        inference_data = super()._compile_tools(inference_data, test_entry)
        inference_data["tools"] = reinforce_optional_parameter_policy(
            inference_data["tools"]
        )
        self._optional_defaults = collect_optional_defaults(inference_data["tools"])
        return inference_data

    def _parse_query_response_FC(self, api_response) -> dict:
        model_response_data = super()._parse_query_response_FC(api_response)
        normalized_responses = omit_redundant_defaults(
            model_response_data["model_responses"],
            getattr(self, "_optional_defaults", {}),
        )
        model_response_data["model_responses"] = deduplicate_identical_tool_calls(
            normalized_responses
        )
        return model_response_data

    def decode_ast(self, result, language, has_tool_call_tag):
        if self.is_fc_model and isinstance(result, str):
            return []
        return super().decode_ast(result, language, has_tool_call_tag)

    def decode_execute(self, result, has_tool_call_tag):
        if self.is_fc_model and isinstance(result, str):
            return []
        return super().decode_execute(result, has_tool_call_tag)


def register_custom_model() -> None:
    registry_name = os.environ.get("BFCL_CUSTOM_REGISTRY_NAME", "").strip()
    provider_model = os.environ.get("BFCL_CUSTOM_PROVIDER_MODEL", "").strip()
    provider_url = os.environ.get("OPENAI_BASE_URL", "").strip()
    if not registry_name or not provider_model or not provider_url:
        raise RuntimeError(
            "Custom BFCL API registration requires BFCL_CUSTOM_REGISTRY_NAME, "
            "BFCL_CUSTOM_PROVIDER_MODEL, and OPENAI_BASE_URL."
        )

    MODEL_CONFIG_MAPPING[registry_name] = ModelConfig(
        model_name=provider_model,
        display_name=f"{provider_model} via custom OpenAI-compatible API (FC)",
        url=provider_url,
        org="Custom API",
        license="Proprietary",
        model_handler=JMindOpsResponsesHandler,
        input_price=None,
        output_price=None,
        is_fc_model=True,
        underscore_to_dot=True,
    )


if __name__ == "__main__":
    install_bfcl_single_sample_compat()
    register_custom_model()
    runpy.run_module("bfcl_eval", run_name="__main__")
