#!/bin/zsh
set -euo pipefail

项目目录="${0:A:h}"
本机目录="${项目目录}/.local"
账号文件="${本机目录}/发件邮箱"
钥匙串服务="AgentWorkbench SMTP 授权码"

mkdir -p "${本机目录}"
chmod 700 "${本机目录}"

if [[ ! -f "${账号文件}" ]]; then
  read "发件邮箱?请输入完整 QQ 邮箱地址："
  if [[ ! "${发件邮箱}" =~ '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$' ]]; then
    print -u2 "邮箱格式不正确。"
    exit 1
  fi
  print -r -- "${发件邮箱}" > "${账号文件}"
  chmod 600 "${账号文件}"
fi

发件邮箱="$(tr -d '\r\n' < "${账号文件}")"
read -rs "授权码?请输入 ${发件邮箱} 的 SMTP 授权码（输入不会显示）："
print

if [[ -z "${授权码}" ]]; then
  print -u2 "授权码不能为空。"
  exit 1
fi

/usr/bin/security add-generic-password -U \
  -a "${发件邮箱}" \
  -s "${钥匙串服务}" \
  -w "${授权码}" >/dev/null
unset 授权码

print "发件邮箱已配置，授权码已保存到 macOS 钥匙串。"
print "现在可以双击“启动后端.command”启动服务。"
