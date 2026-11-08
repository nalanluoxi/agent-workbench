#!/usr/bin/env python3
"""Train a real PEFT LoRA adapter on chat and tool-call examples."""
from __future__ import annotations

import argparse
import hashlib
import inspect
import json
from pathlib import Path


def load_jsonl(path: Path) -> list[dict]:
    if not path.is_file():
        raise ValueError(f"数据文件不存在：{path}")
    items = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            item = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"{path}:{number} 不是合法 JSON") from exc
        if not isinstance(item, dict) or not isinstance(item.get("messages"), list) or not item["messages"]:
            raise ValueError(f"{path}:{number} 缺少 messages 数组")
        if not isinstance(item.get("target"), dict) or item["target"].get("role") != "assistant":
            raise ValueError(f"{path}:{number} 的 target 必须是 assistant 消息")
        if any(not isinstance(message, dict) or message.get("role") not in {"system", "user", "assistant", "tool"} for message in item["messages"]):
            raise ValueError(f"{path}:{number} 包含无效消息角色")
        if item.get("tools") is not None and not isinstance(item["tools"], list):
            raise ValueError(f"{path}:{number} 的 tools 必须是数组")
        items.append(item)
    if not items:
        raise ValueError(f"数据文件为空：{path}")
    return items


def fingerprint(item: dict) -> str:
    value = json.dumps(item["messages"], ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(value.encode()).hexdigest()


def validate_splits(train: list[dict], validation: list[dict]) -> None:
    overlap = {fingerprint(item) for item in train} & {fingerprint(item) for item in validation}
    if overlap:
        raise ValueError(f"训练集与验证集存在 {len(overlap)} 条相同输入，请先去重")


def normalize_target(target: dict) -> dict:
    value = dict(target)
    calls = []
    for call in value.get("tool_calls", []):
        function = dict(call.get("function") or {})
        if not function.get("name"):
            raise ValueError("工具调用缺少 function.name")
        arguments = function.get("arguments", {})
        if isinstance(arguments, str):
            arguments = json.loads(arguments)
        if not isinstance(arguments, dict):
            raise ValueError("工具调用 arguments 必须为对象")
        function["arguments"] = arguments
        calls.append({"type": "function", "function": function})
    if calls:
        value["tool_calls"] = calls
    return value


def tokenize_example(tokenizer, item: dict, max_length: int, train_all_tokens: bool) -> dict:
    messages = [*item["messages"], normalize_target(item["target"])]
    encoded = tokenizer.apply_chat_template(messages, tools=item.get("tools"), tokenize=True,
        add_generation_prompt=False, return_dict=True, return_assistant_tokens_mask=not train_all_tokens,
        truncation=False)
    ids = encoded["input_ids"]
    if len(ids) > max_length:
        raise ValueError("训练样本超过最大长度；请缩短样本或调整 --max-length，避免截断目标")
    if train_all_tokens:
        labels = list(ids)
    else:
        mask = encoded.get("assistant_masks") or encoded.get("assistant_tokens_mask")
        if not mask or len(mask) != len(ids) or not any(mask):
            raise ValueError("模型模板未返回有效的 assistant 掩码；请使用支持该能力的模板，或显式选择 --train-all-tokens")
        prefix = tokenizer.apply_chat_template(item["messages"], tools=item.get("tools"), tokenize=True,
            add_generation_prompt=True, return_dict=True, truncation=False)["input_ids"]
        if len(prefix) >= len(ids) or ids[:len(prefix)] != prefix:
            raise ValueError("无法可靠确定目标回复边界，请检查模型聊天模板")
        labels = [token if marked and i >= len(prefix) else -100 for i, (token, marked) in enumerate(zip(ids, mask))]
        if all(value == -100 for value in labels):
            raise ValueError("目标回复没有可训练的 assistant token，请检查模型聊天模板")
    return {"input_ids": ids, "attention_mask": encoded.get("attention_mask", [1] * len(ids)), "labels": labels}


def parse_args():
    parser = argparse.ArgumentParser(description="使用真实模型和数据训练 LoRA 适配器")
    parser.add_argument("--train", type=Path, required=True, help="训练集 JSONL")
    parser.add_argument("--validation", type=Path, required=True, help="独立验证集 JSONL")
    parser.add_argument("--model", help="本地或 Hugging Face 模型路径")
    parser.add_argument("--output", type=Path, help="适配器输出目录")
    parser.add_argument("--epochs", type=float, default=2.0)
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--accumulation", type=int, default=4)
    parser.add_argument("--max-length", type=int, default=2048)
    parser.add_argument("--learning-rate", type=float, default=2e-4)
    parser.add_argument("--rank", type=int, default=16)
    parser.add_argument("--train-all-tokens", action="store_true", help="明确允许对完整对话计算 loss")
    parser.add_argument("--validate-only", action="store_true", help="只检查数据结构与精确重复，不加载模型")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    train, validation = load_jsonl(args.train), load_jsonl(args.validation)
    validate_splits(train, validation)
    if args.validate_only:
        print(f"数据结构检查通过：训练 {len(train)} 条，验证 {len(validation)} 条；近重复仍需人工检查。")
        return
    if not args.model or not args.output:
        raise ValueError("训练时必须提供 --model 和 --output")
    if args.output.exists() and any(args.output.iterdir()):
        raise ValueError("输出目录非空，请选择新的目录，避免覆盖已有训练结果")
    if args.epochs <= 0 or args.batch_size < 1 or args.accumulation < 1 or args.max_length < 128 or args.rank < 1:
        raise ValueError("训练参数无效")
    try:
        import torch
        from datasets import Dataset
        from peft import LoraConfig, TaskType, get_peft_model
        from transformers import AutoModelForCausalLM, AutoTokenizer, DataCollatorForSeq2Seq, Trainer, TrainingArguments
    except ImportError as exc:
        raise SystemExit("缺少训练依赖，请在训练环境安装 training/requirements.txt") from exc

    tokenizer = AutoTokenizer.from_pretrained(args.model)
    if not tokenizer.chat_template:
        raise ValueError("所选模型没有可用 chat template，无法安全构造工具调用训练样本")
    if tokenizer.pad_token is None:
        if tokenizer.eos_token is None:
            raise ValueError("模型缺少 pad_token 和 eos_token")
        tokenizer.pad_token = tokenizer.eos_token
    train_data = Dataset.from_list([tokenize_example(tokenizer, item, args.max_length, args.train_all_tokens) for item in train])
    val_data = Dataset.from_list([tokenize_example(tokenizer, item, args.max_length, args.train_all_tokens) for item in validation])
    base = AutoModelForCausalLM.from_pretrained(args.model)
    model = get_peft_model(base, LoraConfig(task_type=TaskType.CAUSAL_LM, r=args.rank, lora_alpha=args.rank * 2,
                                            lora_dropout=0.05, target_modules="all-linear"))
    if sum(p.numel() for p in model.parameters() if p.requires_grad) == 0:
        raise ValueError("没有可训练的 LoRA 参数，请检查模型架构")
    options = dict(output_dir=str(args.output), num_train_epochs=args.epochs, per_device_train_batch_size=args.batch_size,
                   per_device_eval_batch_size=1, gradient_accumulation_steps=args.accumulation,
                   learning_rate=args.learning_rate, logging_steps=1, save_strategy="epoch", report_to="none",
                   bf16=torch.cuda.is_available() and torch.cuda.is_bf16_supported())
    options["eval_strategy" if "eval_strategy" in inspect.signature(TrainingArguments).parameters else "evaluation_strategy"] = "epoch"
    trainer = Trainer(model=model, args=TrainingArguments(**options), train_dataset=train_data, eval_dataset=val_data,
                      data_collator=DataCollatorForSeq2Seq(tokenizer=tokenizer, padding=True, label_pad_token_id=-100))
    trainer.train()
    trainer.save_model(str(args.output))
    tokenizer.save_pretrained(str(args.output))
    metadata = {"base_model": args.model, "train_count": len(train), "validation_count": len(validation),
                "train_sha256": hashlib.sha256(args.train.read_bytes()).hexdigest(),
                "validation_sha256": hashlib.sha256(args.validation.read_bytes()).hexdigest(),
                "rank": args.rank, "epochs": args.epochs, "assistant_only": not args.train_all_tokens}
    (args.output / "training-metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"训练完成，适配器保存在 {args.output}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, json.JSONDecodeError) as exc:
        raise SystemExit(str(exc)) from exc
