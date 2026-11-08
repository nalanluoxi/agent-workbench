#!/usr/bin/env python3
"""A small, offline MCP STDIO server for trying the workbench."""
import json
import math
import sys


TOOLS = [
    {"name": "echo", "description": "原样返回传入的文本", "inputSchema": {"type": "object", "properties": {"message": {"type": "string"}}, "required": ["message"]}},
    {"name": "sum_numbers", "description": "计算一组数字的和", "inputSchema": {"type": "object", "properties": {"numbers": {"type": "array", "items": {"type": "number"}}}, "required": ["numbers"]}},
]


def handle(method, params):
    if method == "initialize":
        return {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}}, "serverInfo": {"name": "workbench-local-example", "version": "1.0"}}
    if method == "ping":
        return {}
    if method == "tools/list":
        return {"tools": TOOLS}
    if method == "tools/call":
        name, args = params.get("name"), params.get("arguments", {})
        if not isinstance(args, dict):
            raise ValueError("工具参数必须是对象")
        if name == "echo":
            message = args.get("message")
            if not isinstance(message, str):
                raise ValueError("message 必须是字符串")
            text = message
        elif name == "sum_numbers":
            numbers = args.get("numbers")
            if not isinstance(numbers, list) or len(numbers) > 100 or not all(valid_number(x) for x in numbers):
                raise ValueError("numbers 必须是最多 100 个有限数字")
            total = sum(numbers)
            if not valid_number(total):
                raise ValueError("求和结果超出范围")
            text = str(total)
        else:
            raise ValueError("工具不存在")
        return {"content": [{"type": "text", "text": text}]}
    raise ValueError("方法不支持")


def valid_number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    try:
        return math.isfinite(value)
    except OverflowError:
        return False


def main():
    for line in sys.stdin:
        request_id = None
        try:
            request = json.loads(line)
            request_id = request.get("id")
            if request_id is None:
                continue
            response = {"jsonrpc": "2.0", "id": request_id, "result": handle(request.get("method"), request.get("params") or {})}
        except (ValueError, TypeError, AttributeError) as exc:
            response = {"jsonrpc": "2.0", "id": request_id, "error": {"code": -32602, "message": str(exc)}}
        print(json.dumps(response, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
