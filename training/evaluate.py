#!/usr/bin/env python3
"""Evaluate actual OpenAI-compatible model responses against a held-out JSONL set."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def score(expected: dict, message: dict) -> dict:
    calls = message.get("tool_calls") or []
    expected_name = expected.get("tool_name")
    actual_name = calls[0].get("function", {}).get("name") if calls else None
    chosen = actual_name == expected_name and (len(calls) <= 1)
    arguments_ok = None
    if expected_name is not None:
        arguments = calls[0].get("function", {}).get("arguments", "{}") if calls else "{}"
        try:
            actual_arguments = json.loads(arguments) if isinstance(arguments, str) else arguments
        except json.JSONDecodeError:
            actual_arguments = None
        arguments_ok = chosen and actual_arguments == expected.get("arguments", {})
    return {"tool_correct": chosen, "arguments_correct": arguments_ok, "unexpected_tool": expected_name is None and bool(calls)}


def main() -> None:
    parser = argparse.ArgumentParser(description="对同一独立测试集评估基座或微调模型")
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--base-url", required=True, help="OpenAI 兼容服务基础地址，如 http://127.0.0.1:8000/v1")
    parser.add_argument("--model", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists() and any(args.output.iterdir()):
        raise SystemExit("输出目录非空，请选择新的目录，避免覆盖已有评测结果")
    items = [json.loads(line) for line in args.data.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not items:
        raise SystemExit("测试集为空")
    rows = []
    for index, item in enumerate(items, 1):
        if not isinstance(item.get("messages"), list) or not isinstance(item.get("expected"), dict) or "tool_name" not in item["expected"]:
            raise SystemExit(f"第 {index} 条缺少 messages 或 expected.tool_name（无工具时为 null）")
        request_body = {"model": args.model, "messages": item["messages"], "stream": False}
        if item.get("tools"):
            request_body["tools"] = item["tools"]
        headers = {"Content-Type": "application/json"}
        if os.environ.get("WORKBENCH_EVAL_API_KEY"):
            headers["Authorization"] = "Bearer " + os.environ["WORKBENCH_EVAL_API_KEY"]
        request = Request(args.base_url.rstrip("/") + "/chat/completions", data=json.dumps(request_body).encode(), headers=headers, method="POST")
        try:
            with urlopen(request, timeout=60) as response:
                result = json.load(response)
            message = result["choices"][0]["message"]
            rows.append({"id": item.get("id", index), "ok": True, "scores": score(item["expected"], message), "response": message})
        except (HTTPError, URLError, TimeoutError, KeyError, IndexError, json.JSONDecodeError) as exc:
            rows.append({"id": item.get("id", index), "ok": False, "error": type(exc).__name__})
    success = [row for row in rows if row["ok"]]
    tool_cases = [row for row in success if row["scores"]["arguments_correct"] is not None]
    no_tool_cases = [row for row in success if row["scores"]["arguments_correct"] is None]
    summary = {"model": args.model, "total": len(rows), "completed": len(success), "failed": len(rows) - len(success),
               "tool_choice_accuracy": sum(row["scores"]["tool_correct"] for row in success) / len(success) if success else None,
               "arguments_exact_accuracy": sum(row["scores"]["arguments_correct"] for row in tool_cases) / len(tool_cases) if tool_cases else None,
               "no_tool_false_positive_rate": sum(row["scores"]["unexpected_tool"] for row in no_tool_cases) / len(no_tool_cases) if no_tool_cases else None}
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "评测明细.jsonl").write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    (args.output / "评测汇总.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
