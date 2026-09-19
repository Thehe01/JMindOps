#!/usr/bin/env python3
"""Run pinned BFCL generation/scoring with reproducibility metadata.

This runner intentionally delegates answer generation and scoring to the
official ``bfcl-eval`` package. It only validates the pinned release/subset,
builds an isolated run directory, and records provenance.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
from importlib import metadata, util
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
from typing import Any, Iterable
from urllib.parse import urlparse


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RELEASE_LOCK = REPOSITORY_ROOT / "docs" / "evaluation" / "bfcl-release.json"
DEFAULT_SUBSET = REPOSITORY_ROOT / "evaluation-data" / "bfcl-v4-core-v1.json"
DEFAULT_OUTPUT_ROOT = REPOSITORY_ROOT / "evaluation-results" / "bfcl"
DEFAULT_MODEL = "Qwen/Qwen3-1.7B-FC"
CUSTOM_API_ENTRYPOINT = REPOSITORY_ROOT / "scripts" / "bfcl-custom-api.py"
RELAY_CONFIG_FIELDS = ("RELAY_API_KEY", "RELAY_BASE_URL", "RELAY_CHAT_MODEL")
API_AGENT_POLICIES = ("strict-v1", "none")
API_REASONING_EFFORTS = ("provider-default", "low", "medium", "high", "xhigh")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"无法读取 JSON: {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ValueError(f"JSON 根节点必须是对象: {path}")
    return value


def read_relay_config(path: Path) -> dict[str, str]:
    """Read the three supported Relay settings without leaking credentials."""
    try:
        lines = path.read_text(encoding="utf-8-sig").splitlines()
    except OSError as exc:
        raise ValueError(f"无法读取 API 配置: {path}: {exc}") from exc

    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(lines, start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].lstrip()
        if "=" not in line:
            raise ValueError(f"API 配置不是合法 KEY=VALUE: {path}:{line_number}")
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        if key in RELAY_CONFIG_FIELDS:
            values[key] = value

    missing = [field for field in RELAY_CONFIG_FIELDS if not values.get(field)]
    if missing:
        raise ValueError("API 配置缺少字段: " + ", ".join(missing))

    base_url = values["RELAY_BASE_URL"].rstrip("/")
    parsed = urlparse(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ValueError("RELAY_BASE_URL 必须是 http(s) URL。")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("RELAY_BASE_URL 不能包含凭据、query 或 fragment。")
    if not base_url.endswith("/v1"):
        base_url += "/v1"
    values["RELAY_BASE_URL"] = base_url
    return values


def custom_registry_name(provider_model: str, requested_name: str | None) -> str:
    if requested_name:
        name = requested_name.strip()
        if not name.startswith("custom-"):
            raise ValueError("--api-registry-name 必须以 custom- 开头，避免覆盖官方模型。")
        if ".." in name or not re.fullmatch(r"[A-Za-z0-9._/-]+", name):
            raise ValueError("--api-registry-name 包含不安全字符。")
        return name

    safe_model = re.sub(r"[^A-Za-z0-9._-]+", "-", provider_model).strip("-.")
    if not safe_model:
        raise ValueError("RELAY_CHAT_MODEL 无法生成安全的 BFCL 注册名。")
    return f"custom-relay/{safe_model}-FC"


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def discover_bfcl_installation() -> tuple[str, Path]:
    try:
        installed_version = metadata.version("bfcl-eval")
    except metadata.PackageNotFoundError as exc:
        raise RuntimeError(
            "未安装 bfcl-eval。请先按 docs/evaluation/bfcl.md 创建独立环境。"
        ) from exc

    spec = util.find_spec("bfcl_eval")
    if spec is None or not spec.submodule_search_locations:
        raise RuntimeError("已发现 bfcl-eval 发行包，但无法定位 bfcl_eval 模块。")
    return installed_version, Path(next(iter(spec.submodule_search_locations))).resolve()


def validate_release(
    release_lock: dict[str, Any], installed_version: str, subset: dict[str, Any] | None
) -> None:
    expected_version = str(release_lock.get("version", ""))
    if installed_version != expected_version:
        raise ValueError(
            f"bfcl-eval 版本不一致: expected={expected_version}, actual={installed_version}"
        )
    if subset is not None and str(subset.get("bfclEvalVersion", "")) != expected_version:
        raise ValueError("BFCL 子集声明的 bfclEvalVersion 与 release lock 不一致。")


def iter_dataset_entries(path: Path) -> Iterable[dict[str, Any]]:
    text = path.read_text(encoding="utf-8-sig")
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        parsed = None
    if isinstance(parsed, list):
        for entry in parsed:
            if isinstance(entry, dict):
                yield entry
        return
    if isinstance(parsed, dict):
        yield parsed
        return

    for line_number, line in enumerate(text.splitlines(), start=1):
        if not line.strip():
            continue
        try:
            entry = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"官方数据不是合法 JSONL: {path}:{line_number}") from exc
        if isinstance(entry, dict):
            yield entry


def collect_inference_errors(run_root: Path, model: str) -> list[dict[str, str]]:
    model_result_root = run_root / "result" / model.replace("/", "_")
    if not model_result_root.is_dir():
        return []

    errors: list[dict[str, str]] = []
    for result_file in sorted(model_result_root.rglob("*_result.json")):
        for entry in iter_dataset_entries(result_file):
            result = entry.get("result")
            has_error = bool(entry.get("traceback")) or (
                isinstance(result, str)
                and result.casefold().startswith("error during inference")
            )
            if not has_error:
                continue
            errors.append(
                {
                    "caseId": str(entry.get("id", "unknown")),
                    "message": str(result)[:500],
                    "resultFile": str(result_file.relative_to(run_root)),
                }
            )
    return errors


def normalize_case_ids(subset: dict[str, Any]) -> dict[str, list[str]]:
    raw_case_ids = subset.get("caseIds")
    if not isinstance(raw_case_ids, dict) or not raw_case_ids:
        raise ValueError("BFCL 子集必须包含非空 caseIds 对象。")

    normalized: dict[str, list[str]] = {}
    all_ids: set[str] = set()
    for category, values in raw_case_ids.items():
        if not isinstance(category, str) or not category.strip():
            raise ValueError("BFCL category 必须是非空字符串。")
        if not isinstance(values, list) or not values:
            raise ValueError(f"BFCL category {category} 没有 case id。")
        clean_values: list[str] = []
        for value in values:
            case_id = str(value).strip()
            if not case_id:
                raise ValueError(f"BFCL category {category} 包含空 case id。")
            if case_id in all_ids:
                raise ValueError(f"BFCL case id 重复: {case_id}")
            all_ids.add(case_id)
            clean_values.append(case_id)
        normalized[category.strip()] = clean_values
    return normalized


def validate_official_ids(
    data_directory: Path, case_ids: dict[str, list[str]]
) -> list[dict[str, str]]:
    wanted = {case_id for values in case_ids.values() for case_id in values}
    found: dict[str, Path] = {}
    for data_file in sorted(data_directory.glob("BFCL_v*.json")):
        for entry in iter_dataset_entries(data_file):
            case_id = entry.get("id")
            if case_id in wanted:
                if case_id in found:
                    raise ValueError(f"官方 BFCL 数据中存在重复 id: {case_id}")
                found[str(case_id)] = data_file

    missing = sorted(wanted.difference(found))
    if missing:
        raise ValueError("固定子集包含官方包中不存在的 id: " + ", ".join(missing))

    referenced_files = sorted(set(found.values()), key=lambda value: value.name)
    return [
        {"file": path.name, "sha256": sha256_file(path)} for path in referenced_files
    ]


def parse_categories(value: str | None, available: Iterable[str]) -> list[str]:
    available_list = list(available)
    if value is None:
        return available_list
    categories = [item.strip() for item in value.split(",") if item.strip()]
    if not categories:
        raise ValueError("--categories 不能为空。")
    unknown = [item for item in categories if item not in available_list]
    if unknown:
        raise ValueError("子集不包含 category: " + ", ".join(unknown))
    return categories


def build_commands(
    python_executable: str,
    model: str,
    categories: list[str],
    partial: bool,
    mode: str,
    endpoint: str | None,
    backend: str,
    num_threads: int,
    temperature: float,
    include_input_log: bool,
    allow_overwrite: bool,
    custom_api: bool = False,
) -> list[list[str]]:
    category_argument = ",".join(categories)
    commands: list[list[str]] = []
    command_prefix = (
        [python_executable, str(CUSTOM_API_ENTRYPOINT)]
        if custom_api
        else [python_executable, "-m", "bfcl_eval"]
    )
    if mode in {"both", "generate"}:
        generate = [
            *command_prefix,
            "generate",
            "--model",
            model,
        ]
        if partial:
            generate.append("--run-ids")
        else:
            generate.extend(["--test-category", category_argument])
        if not custom_api:
            generate.extend(["--backend", backend])
        generate.extend(
            ["--num-threads", str(num_threads), "--temperature", str(temperature)]
        )
        if endpoint and not custom_api:
            generate.append("--skip-server-setup")
        if include_input_log:
            generate.append("--include-input-log")
        if allow_overwrite:
            generate.append("--allow-overwrite")
        commands.append(generate)

    if mode in {"both", "evaluate"}:
        evaluate = [
            *command_prefix,
            "evaluate",
            "--model",
            model,
            "--test-category",
            category_argument,
        ]
        if partial:
            evaluate.append("--partial-eval")
        commands.append(evaluate)
    return commands


def git_provenance(repository_root: Path) -> dict[str, Any]:
    def run_git(*args: str) -> str | None:
        completed = subprocess.run(
            ["git", *args],
            cwd=repository_root,
            text=True,
            capture_output=True,
            check=False,
        )
        return completed.stdout.strip() if completed.returncode == 0 else None

    head = run_git("rev-parse", "HEAD")
    status = run_git("status", "--porcelain")
    return {"head": head, "dirty": bool(status) if status is not None else None}


def format_command(command: list[str]) -> str:
    return " ".join(shlex.quote(part) for part in command)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run the pinned official BFCL generator and scorer."
    )
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--subset-manifest", type=Path, default=DEFAULT_SUBSET)
    parser.add_argument("--release-lock", type=Path, default=DEFAULT_RELEASE_LOCK)
    parser.add_argument("--categories", help="Comma-separated subset categories.")
    parser.add_argument("--full", action="store_true", help="Run complete categories, not fixed IDs.")
    parser.add_argument("--run-root", type=Path)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument("--endpoint", help="OpenAI-compatible /v1 base URL.")
    parser.add_argument(
        "--api-config",
        type=Path,
        help="KEY=VALUE file containing RELAY_API_KEY, RELAY_BASE_URL, and RELAY_CHAT_MODEL.",
    )
    parser.add_argument(
        "--api-registry-name",
        help="BFCL registry alias for a custom API model; defaults to custom-relay/<model>-FC.",
    )
    parser.add_argument(
        "--api-agent-policy",
        choices=API_AGENT_POLICIES,
        default="strict-v1",
        help="Custom API agent guardrail policy; use none for an ungoverned baseline.",
    )
    parser.add_argument(
        "--api-timeout-seconds",
        type=int,
        default=120,
        help="Per-request timeout for the custom API.",
    )
    parser.add_argument(
        "--api-max-retries",
        type=int,
        default=2,
        help="OpenAI SDK retry count for transient custom API failures.",
    )
    parser.add_argument(
        "--api-reasoning-effort",
        choices=API_REASONING_EFFORTS,
        default="high",
        help="Responses API reasoning effort for the custom API.",
    )
    parser.add_argument("--backend", choices=("vllm", "sglang"), default="vllm")
    parser.add_argument("--num-threads", type=int, default=1)
    parser.add_argument("--temperature", type=float, default=0.001)
    parser.add_argument("--mode", choices=("both", "generate", "evaluate"), default="both")
    parser.add_argument("--include-input-log", action="store_true")
    parser.add_argument("--allow-overwrite", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.num_threads < 1:
        raise ValueError("--num-threads 必须大于 0。")
    if not 0 <= args.temperature <= 2:
        raise ValueError("--temperature 必须位于 0..2。")
    if args.mode == "evaluate" and args.run_root is None:
        raise ValueError("--mode evaluate 必须通过 --run-root 指向已有生成结果。")
    if args.endpoint and args.api_config:
        raise ValueError("--endpoint 与 --api-config 不能同时使用。")
    if args.api_registry_name and not args.api_config:
        raise ValueError("--api-registry-name 只能与 --api-config 一起使用。")
    if args.api_agent_policy != "strict-v1" and not args.api_config:
        raise ValueError("非默认 --api-agent-policy 只能与 --api-config 一起使用。")
    if args.api_timeout_seconds < 1:
        raise ValueError("--api-timeout-seconds 必须大于 0。")
    if not 0 <= args.api_max_retries <= 5:
        raise ValueError("--api-max-retries 必须位于 0..5。")
    if not args.api_config and (
        args.api_timeout_seconds != 120
        or args.api_max_retries != 2
        or args.api_reasoning_effort != "high"
    ):
        raise ValueError("API 推理、超时与重试参数只能与 --api-config 一起使用。")

    release_lock_path = args.release_lock.resolve()
    subset_path = args.subset_manifest.resolve()
    release_lock = read_json(release_lock_path)
    subset = None if args.full else read_json(subset_path)
    installed_version, package_root = discover_bfcl_installation()
    validate_release(release_lock, installed_version, subset)

    relay_config = (
        read_relay_config(args.api_config.resolve()) if args.api_config else None
    )
    model = args.model
    if relay_config is not None:
        model = custom_registry_name(
            relay_config["RELAY_CHAT_MODEL"], args.api_registry_name
        )

    if args.full:
        if args.categories is None:
            raise ValueError("全量模式必须显式提供 --categories，避免误跑 Web/Memory 外部依赖。")
        categories = [item.strip() for item in args.categories.split(",") if item.strip()]
        if not categories:
            raise ValueError("--categories 不能为空。")
        case_ids = None
        official_data_files: list[dict[str, str]] = []
    else:
        assert subset is not None
        all_case_ids = normalize_case_ids(subset)
        categories = parse_categories(args.categories, all_case_ids.keys())
        case_ids = {category: all_case_ids[category] for category in categories}
        official_data_files = validate_official_ids(package_root / "data", case_ids)

    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    run_root = (
        args.run_root.resolve()
        if args.run_root is not None
        else (args.output_root.resolve() / f"bfcl-{timestamp}")
    )
    if args.mode != "evaluate" and run_root.exists() and not args.allow_overwrite:
        raise FileExistsError(f"运行目录已存在；请换目录或显式使用 --allow-overwrite: {run_root}")

    commands = build_commands(
        sys.executable,
        model,
        categories,
        partial=not args.full,
        mode=args.mode,
        endpoint=args.endpoint,
        backend=args.backend,
        num_threads=args.num_threads,
        temperature=args.temperature,
        include_input_log=args.include_input_log,
        allow_overwrite=args.allow_overwrite,
        custom_api=relay_config is not None,
    )
    manifest: dict[str, Any] = {
        "schemaVersion": 1,
        "status": "PLANNED" if args.dry_run else "RUNNING",
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "benchmark": {
            "name": release_lock.get("benchmarkVersion"),
            "distribution": release_lock.get("distribution"),
            "installedVersion": installed_version,
            "sourceRepository": release_lock.get("sourceRepository"),
            "sourceCommit": release_lock.get("sourceCommit"),
            "wheelSha256": release_lock.get("wheelSha256"),
        },
        "model": model,
        "providerModel": (
            relay_config["RELAY_CHAT_MODEL"] if relay_config is not None else None
        ),
        "categories": categories,
        "partialEvaluation": not args.full,
        "subset": None
        if args.full
        else {
            "datasetId": subset.get("datasetId") if subset else None,
            "manifestPath": str(subset_path),
            "manifestSha256": sha256_file(subset_path),
            "caseCount": sum(len(values) for values in (case_ids or {}).values()),
            "caseIds": case_ids,
            "officialDataFiles": official_data_files,
        },
        "endpoint": (
            relay_config["RELAY_BASE_URL"] if relay_config is not None else args.endpoint
        ),
        "backend": "openai-responses" if relay_config is not None else args.backend,
        "agentPolicy": args.api_agent_policy if relay_config is not None else None,
        "apiTimeoutSeconds": (
            args.api_timeout_seconds if relay_config is not None else None
        ),
        "apiMaxRetries": args.api_max_retries if relay_config is not None else None,
        "apiReasoningEffort": (
            args.api_reasoning_effort if relay_config is not None else None
        ),
        "numThreads": args.num_threads,
        "temperature": args.temperature,
        "commands": commands,
        "git": git_provenance(REPOSITORY_ROOT),
    }

    print(f"BFCL package: {installed_version} ({package_root})")
    print(f"Run root: {run_root}")
    for command in commands:
        print("Command:", format_command(command))
    if args.dry_run:
        print(json.dumps(manifest, ensure_ascii=False, indent=2))
        return 0

    run_root.mkdir(parents=True, exist_ok=args.allow_overwrite or args.mode == "evaluate")
    if case_ids is not None:
        write_json(run_root / "test_case_ids_to_generate.json", case_ids)
    manifest_path = run_root / "jmindops-run-manifest.json"
    write_json(manifest_path, manifest)

    environment = os.environ.copy()
    environment["BFCL_PROJECT_ROOT"] = str(run_root)
    if relay_config is not None:
        environment["OPENAI_API_KEY"] = relay_config["RELAY_API_KEY"]
        environment["OPENAI_BASE_URL"] = relay_config["RELAY_BASE_URL"]
        environment["BFCL_CUSTOM_REGISTRY_NAME"] = model
        environment["BFCL_CUSTOM_PROVIDER_MODEL"] = relay_config["RELAY_CHAT_MODEL"]
        environment["BFCL_CUSTOM_AGENT_POLICY"] = args.api_agent_policy
        environment["BFCL_CUSTOM_API_TIMEOUT_SECONDS"] = str(
            args.api_timeout_seconds
        )
        environment["BFCL_CUSTOM_API_MAX_RETRIES"] = str(args.api_max_retries)
        environment["BFCL_CUSTOM_REASONING_EFFORT"] = args.api_reasoning_effort
    elif args.endpoint:
        environment["REMOTE_OPENAI_BASE_URL"] = args.endpoint.rstrip("/")
        environment["REMOTE_OPENAI_API_KEY"] = environment.get(
            "BFCL_REMOTE_OPENAI_API_KEY",
            environment.get("REMOTE_OPENAI_API_KEY", "EMPTY"),
        )

    try:
        for command in commands:
            subprocess.run(command, env=environment, cwd=REPOSITORY_ROOT, check=True)
    except KeyboardInterrupt:
        manifest["status"] = "INTERRUPTED"
        manifest["finishedAt"] = datetime.now(timezone.utc).isoformat()
        write_json(manifest_path, manifest)
        raise
    except subprocess.CalledProcessError as exc:
        manifest["status"] = "FAILED"
        manifest["finishedAt"] = datetime.now(timezone.utc).isoformat()
        manifest["failedCommand"] = exc.cmd
        manifest["exitCode"] = exc.returncode
        write_json(manifest_path, manifest)
        raise

    inference_errors = collect_inference_errors(run_root, model)
    if inference_errors:
        manifest["status"] = "COMPLETED_WITH_ERRORS"
        manifest["finishedAt"] = datetime.now(timezone.utc).isoformat()
        manifest["inferenceErrors"] = inference_errors
        write_json(manifest_path, manifest)
        print(
            f"ERROR: BFCL completed with {len(inference_errors)} inference error(s): "
            + ", ".join(error["caseId"] for error in inference_errors),
            file=sys.stderr,
        )
        return 3

    manifest["status"] = "COMPLETED"
    manifest["finishedAt"] = datetime.now(timezone.utc).isoformat()
    write_json(manifest_path, manifest)
    print(f"BFCL evaluation completed: {run_root}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (FileNotFoundError, RuntimeError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(2) from error
