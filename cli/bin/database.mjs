import { request, stdinJson, output, fail } from './common.mjs';

export async function databaseCli(type) {
  const values = process.argv.slice(2);
  const [first, second] = values;
  const toolId = type === 'MYSQL' ? 'builtin-mysql' : 'builtin-redis';
  if (first === '--delegated') {
    try {
      const envelope = await stdinJson();
      if (!envelope.token || !envelope.runId || !envelope.toolId || !envelope.command || !envelope.args) throw new Error('委托参数不完整');
      output(await request('/api/database/operations', { method: 'POST', delegatedToken: envelope.token,
        body: { connectionId: envelope.toolId, command: envelope.command, args: envelope.args, runId: envelope.runId } }));
    } catch (error) { fail(error); }
    return;
  }
  const command = first;
  if (!command || command === '--help' || second) {
    console.log(`${type} 固定工具：<操作>，通过标准输入提供 JSON 参数。无需连接 ID；数据变更须在工作台或 agentdesk approvals 中由用户确认。`);
    return;
  }
  try {
    const args = await stdinJson();
    output(await request('/api/database/operations', { method: 'POST', body: { connectionId: toolId, command, args } }));
  } catch (error) { fail(error); }
}
