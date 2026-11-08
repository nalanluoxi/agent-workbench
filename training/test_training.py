import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).parent


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / f"{name}.py")
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


class TrainingDataTest(unittest.TestCase):
    def test_examples_validate_and_splits_do_not_overlap(self):
        train = module("train_lora")
        first = train.load_jsonl(ROOT / "examples/训练示例.jsonl")
        second = train.load_jsonl(ROOT / "examples/验证示例.jsonl")
        train.validate_splits(first, second)
        with self.assertRaises(ValueError):
            train.validate_splits(first, first)

    def test_tool_scores_use_actual_response(self):
        evaluate = module("evaluate")
        expected = {"tool_name": "database_operation", "arguments": {"command": "GET"}}
        reply = {"tool_calls": [{"function": {"name": "database_operation", "arguments": '{"command":"GET"}'}}]}
        self.assertTrue(evaluate.score(expected, reply)["arguments_correct"])
        self.assertTrue(evaluate.score({"tool_name": None}, reply)["unexpected_tool"])

    def test_only_target_tokens_are_trained_and_oversize_is_rejected(self):
        train = module("train_lora")
        class Tokenizer:
            def apply_chat_template(self, messages, **options):
                if options["add_generation_prompt"]:
                    return {"input_ids": [1, 2, 3, 4]}
                return {"input_ids": [1, 2, 3, 4, 5, 6], "assistant_masks": [0, 1, 1, 0, 1, 1]}
        item = {"messages": [{"role": "user", "content": "你好"}], "target": {"role": "assistant", "content": "回答"}}
        encoded = train.tokenize_example(Tokenizer(), item, 8, False)
        self.assertEqual(encoded["labels"], [-100, -100, -100, -100, 5, 6])
        with self.assertRaisesRegex(ValueError, "超过最大长度"):
            train.tokenize_example(Tokenizer(), item, 5, False)


if __name__ == "__main__":
    unittest.main()
