const STATUS = {
  QUEUED: ['排队中', 'warn'], RUNNING: ['运行中', 'info'], WAITING_CONFIRMATION: ['等待确认', 'warn'],
  COMPLETED: ['已完成', 'ok'], FAILED: ['失败', 'bad'], CANCELLED: ['已取消', 'muted'], CANCELED: ['已取消', 'muted'],
  CANCEL_REQUESTED: ['正在取消', 'warn'], INTERRUPTED: ['已中断', 'bad'], SKIPPED: ['已跳过', 'muted'],
  PENDING: ['待处理', 'warn'], APPROVED: ['已批准', 'ok'], REJECTED: ['已拒绝', 'bad'], EXPIRED: ['已过期', 'bad'],
  EXECUTING: ['执行中', 'info'], SUCCEEDED: ['执行成功', 'ok'], EXECUTION_FAILED: ['执行失败', 'bad'],
  UNKNOWN: ['结果未知', 'warn'], ACTIVE: ['生效中', 'ok'], STALE: ['待重建', 'warn'],
  DELETED: ['已删除', 'bad'], DONE: ['已完成', 'ok'], DRYRUN: ['仅演练', 'info'],
  CHAT: ['对话模式', 'info'], BUILD: ['执行模式', 'info'], CHAT_ONLY: ['普通对话', 'muted'],
  MYSQL_READ: ['MySQL 只读', 'info'], MYSQL_MUTATE: ['MySQL 变更', 'warn'], REDIS_READ: ['Redis 只读', 'info'],
  REDIS_MUTATE: ['Redis 变更', 'warn'], SKILL_GUIDANCE: ['Skill 引导', 'info'], MCP_CALL: ['MCP 调用', 'info'],
  SUMMARY: ['会话摘要', 'ok'], L0: ['原始证据', 'info'], L1: ['原子记忆', 'info'], L2: ['场景记忆', 'info'], L3: ['用户画像', 'info'],
  EMBEDDING: ['向量检索', 'ok'], KEYWORD: ['关键词检索', 'warn'], NONE: ['未召回', 'muted'],
  CACHE_HIT: ['缓存命中', 'ok'], TIMEOUT: ['检索超时', 'warn'], MODEL_UNAVAILABLE: ['模型不可用', 'bad'],
  SESSION_CONTEXT: ['会话上下文', 'info']
};

export function statusMeta(code) {
  const raw = String(code ?? '').trim();
  const normalized = raw.toUpperCase();
  const found = STATUS[normalized];
  return found
    ? { raw, normalized, label: found[0], tone: found[1], known: true }
    : { raw, normalized, label: raw ? '未知状态' : '未提供状态', tone: 'muted', known: false };
}

export function statusText(code) {
  const meta = statusMeta(code);
  return meta.raw ? `${meta.label} · ${meta.raw}` : meta.label;
}
