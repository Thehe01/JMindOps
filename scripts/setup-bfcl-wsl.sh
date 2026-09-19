#!/usr/bin/env bash
set -euo pipefail

bfcl_version="2026.3.23"
bfcl_wheel="bfcl_eval-2026.3.23-py3-none-any.whl"
bfcl_sha256="3bb6dfa5f0c68ad403c9ec50b00db2bb3b4cc9b38ab1ff33f48fe30d853d3a0a"
base_python="${BFCL_BASE_PYTHON:-python3.11}"
venv_path="${BFCL_VENV_PATH:-.venv-bfcl-wsl}"

if ! command -v "$base_python" >/dev/null 2>&1 && [[ ! -x "$base_python" ]]; then
  echo "BFCL_BASE_PYTHON 不可用: $base_python" >&2
  echo "请指定 Python 3.10-3.12，例如 BFCL_BASE_PYTHON=/path/to/python3.11" >&2
  exit 2
fi

python_version="$("$base_python" -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')"
case "$python_version" in
  3.10|3.11|3.12) ;;
  *)
    echo "BFCL 当前依赖 numpy==1.26.4；请使用 Python 3.10-3.12，当前为 $python_version。" >&2
    exit 2
    ;;
esac

"$base_python" -m venv "$venv_path"
bfcl_python="$venv_path/bin/python"
archive_dir="$(mktemp -d)"

cleanup() {
  case "$archive_dir" in
    /tmp/*) rm -rf -- "$archive_dir" ;;
  esac
}
trap cleanup EXIT

# The runner talks to an external vLLM endpoint and only needs Torch for
# upstream imports/tokenizer helpers. Preinstall a CPU build so pip does not
# pull a second multi-gigabyte CUDA runtime into this evaluation environment.
if ! "$bfcl_python" -c 'import torch' >/dev/null 2>&1; then
  "$bfcl_python" -m pip install --index-url https://download.pytorch.org/whl/cpu "torch==2.8.0"
fi

"$bfcl_python" -m pip download --no-deps --dest "$archive_dir" "bfcl-eval==$bfcl_version"
downloaded_wheel="$archive_dir/$bfcl_wheel"
if [[ ! -f "$downloaded_wheel" ]]; then
  echo "未找到预期 wheel: $bfcl_wheel" >&2
  exit 3
fi
printf '%s  %s\n' "$bfcl_sha256" "$downloaded_wheel" | sha256sum --check --status
"$bfcl_python" -m pip install "$downloaded_wheel"
# bfcl-eval imports qwen-agent while constructing the model registry;
# qwen-agent currently imports soundfile without declaring it transitively.
"$bfcl_python" -m pip install "soundfile==0.13.1"

installed_version="$("$bfcl_python" -c 'from importlib.metadata import version; print(version("bfcl-eval"))')"
if [[ "$installed_version" != "$bfcl_version" ]]; then
  echo "BFCL 版本校验失败: expected=$bfcl_version actual=$installed_version" >&2
  exit 4
fi

echo "BFCL $installed_version 已安装到 $venv_path"
echo "运行器: $bfcl_python scripts/run-bfcl-evaluation.py --dry-run"
