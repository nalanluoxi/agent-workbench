#!/bin/zsh
set -euo pipefail

项目目录="${0:A:h}"
账号文件="${项目目录}/.local/发件邮箱"
钥匙串服务="AgentWorkbench SMTP 授权码"

if [[ ! -f "${账号文件}" ]]; then
  print -u2 "尚未配置发件邮箱，请先双击“配置发件邮箱.command”。"
  exit 1
fi

发件邮箱="$(tr -d '\r\n' < "${账号文件}")"
授权码="$(/usr/bin/security find-generic-password \
  -a "${发件邮箱}" \
  -s "${钥匙串服务}" \
  -w 2>/dev/null || true)"

if [[ -z "${授权码}" ]]; then
  print -u2 "钥匙串中没有找到 SMTP 授权码，请先双击“配置发件邮箱.command”。"
  exit 1
fi

export WORKBENCH_SMTP_HOST="smtp.qq.com"
export WORKBENCH_SMTP_PORT="465"
export WORKBENCH_SMTP_USER="${发件邮箱}"
export WORKBENCH_SMTP_PASSWORD="${授权码}"
unset 授权码

if ! command -v redis-cli >/dev/null 2>&1; then
  print -u2 "AgentDesk 记忆系统强制依赖 Redis，但本机未找到 redis-cli。"
  exit 1
fi
if ! redis-cli -h "${WORKBENCH_MEMORY_REDIS_HOST:-127.0.0.1}" -p "${WORKBENCH_MEMORY_REDIS_PORT:-6379}" ping 2>/dev/null | grep -q '^PONG$'; then
  print -u2 "AgentDesk 记忆系统强制依赖 Redis，请先启动 ${WORKBENCH_MEMORY_REDIS_HOST:-127.0.0.1}:${WORKBENCH_MEMORY_REDIS_PORT:-6379}。"
  exit 1
fi

export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export PATH="${JAVA_HOME}/bin:${PATH}"

cd "${项目目录}/backend"
exec mvn spring-boot:run
