import importlib.util
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "scripts" / "validate-agent-eval-datasets.py"
SPEC = importlib.util.spec_from_file_location("agent_eval_dataset_validator", MODULE_PATH)
VALIDATOR = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(VALIDATOR)


def test_v2_datasets_are_structurally_valid_and_have_declared_sizes():
    dev_path = ROOT / "evaluation-data" / "agent-eval-v2-dev.json"
    test_path = ROOT / "evaluation-data" / "agent-eval-v2-test.json"

    dev, dev_errors, dev_distribution = VALIDATOR.validate_dataset(dev_path)
    test, test_errors, test_distribution = VALIDATOR.validate_dataset(test_path)

    assert dev_errors == []
    assert test_errors == []
    assert len(dev["cases"]) == dev["expectedCaseCount"] == 40
    assert len(test["cases"]) == test["expectedCaseCount"] == 100
    assert sum(dev_distribution.values()) == 40
    assert sum(test_distribution.values()) == 100


def test_development_and_test_inputs_do_not_overlap():
    normalized = set()
    for filename in ("agent-eval-v2-dev.json", "agent-eval-v2-test.json"):
        dataset, errors, _ = VALIDATOR.validate_dataset(
            ROOT / "evaluation-data" / filename
        )
        assert errors == []
        for case in dataset["cases"]:
            value = VALIDATOR.normalized_input(case["input"])
            assert value not in normalized
            normalized.add(value)
