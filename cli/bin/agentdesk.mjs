#!/usr/bin/env node
import { request, prompt, password, stdinJson, saveToken, clearToken, output, fail, uploadSkill, streamRun } from './common.mjs';

const argumentsList = process.argv.slice(2);
const [group, action, id, extra] = argumentsList;
const help = `ZWD-AWDP（ZWD Agent Workbench & Development Platform）CLI
  agentdesk register | login | logout | me
  agentdesk sessions list | create [名称] | get <ID> | rename <ID> | delete <ID>
  agentdesk chat <会话 ID> [chat|build]
  agentdesk models list | test [ID] | create | update <ID> | default <ID> | delete <ID>
  agentdesk tools list | enable <ID> | disable <ID>
  agentdesk stats [24h|7d|30d]
  agentdesk jobs show <ID> | events <ID> | definition <ID>
  agentdesk approvals show <ID> [mcp|skill] | approve <ID> [mcp|skill] | reject <ID> [mcp|skill]
  agentdesk mcp list | show <ID> | create | update <ID> | delete <ID> | refresh <ID> | tools <ID>
  agentdesk mcp enable-tool <服务 ID> <工具名> | disable-tool <服务 ID> <工具名> | call
  agentdesk skills list | show <ID> | scripts <ID> | run <ID> | install-local <目录> | install-zip <文件> | install-github | update <ID> | refresh <ID> | enable <ID> | disable <ID> | delete <ID>
  agentdesk memories list [层级] | show <ID> | create | update <ID> | delete <ID> | jobs | retry <运行 ID>
模型、MCP 的 create/update 与 MCP call 从标准输入读取 JSON，避免把密钥写入命令参数。
模型 create/update 前必须先执行 models test，并把返回的 testToken 放入保存参数。`;

function needId() { if (!id) throw new Error('请提供资源 ID'); return encodeURIComponent(id); }
async function main() {
  if (!group || group === 'help' || group === '--help') { console.log(help); return; }
  if (group === 'register') {
    const email = await prompt('邮箱：');
    const sent = await request('/api/auth/email-codes', { method: 'POST', body: { email }, auth: false });
    output(sent);
    const code = await prompt('邮件验证码：');
    const pass = await password('密码：');
    const repeated = await password('再次输入密码：');
    if (pass !== repeated) throw new Error('两次密码不一致，请重新注册');
    output(await request('/api/auth/register', { method: 'POST', body: { email, code, password: pass }, auth: false }));
    return;
  }
  if (group === 'login') {
    const email = await prompt('邮箱：');
    const pass = await password('密码：');
    const response = await request('/api/auth/login', { method: 'POST', body: { email, password: pass }, auth: false });
    await saveToken(response.token);
    output({ email: response.email, expiresAt: response.expiresAt, message: '登录成功' });
    return;
  }
  if (group === 'logout') { await request('/api/auth/logout', { method: 'POST' }); await clearToken(); output({ message: '已退出登录' }); return; }
  if (group === 'me') { output(await request('/api/auth/me')); return; }
  if (group === 'chat') {
    if (!action) throw new Error('请提供会话 ID');
    const message = await prompt('消息：');
    if (!message) throw new Error('消息不能为空');
    const selectedMode = id && id.toLowerCase() === 'build' ? 'BUILD' : 'CHAT';
    const run = await request(`/api/sessions/${encodeURIComponent(action)}/runs`, { method: 'POST', body: { message, mode: selectedMode } });
    let after = 0, done = false;
    while (!done) {
      await streamRun(run.id, after, async event => {
        after = Math.max(after, event.id);
        if (event.type === 'text_delta') process.stdout.write(event.payload.text || '');
        if (event.type === 'confirmation_required') {
          process.stdout.write('\n需要确认工具操作：\n'); output(event.payload.action);
          const choice = await prompt('请输入“确认执行”或“拒绝”：');
          if (choice === '确认执行' || choice === '拒绝') { const kind = event.payload.action.kind === 'MCP' ? 'mcp-actions' : event.payload.action.kind === 'SKILL_SCRIPT' ? 'skill-script-actions' : 'actions'; output(await request(`/api/${kind}/${event.payload.action.id}/${choice === '确认执行' ? 'approve' : 'reject'}`, { method: 'POST' })); }
          else process.stdout.write(`未确认；可稍后使用 approvals 命令处理${event.payload.action.kind === 'MCP' ? '（末尾加 mcp）' : event.payload.action.kind === 'SKILL_SCRIPT' ? '（末尾加 skill）' : ''}。\n`);
        }
        if (['run_completed','run_failed','run_interrupted','run_cancelled'].includes(event.type)) { process.stdout.write(`\n${event.payload.message || event.type}\n`); done = true; }
      });
      const state = await request(`/api/runs/${run.id}`);
      if (!['running','queued','waiting_confirmation','cancel_requested'].includes(state.status)) done = true;
      if (state.status === 'waiting_confirmation') { process.stdout.write(`运行等待确认：${run.id}\n`); break; }
    }
    return;
  }
  if (group === 'sessions') {
    if (action === 'list') output(await request('/api/sessions'));
    else if (action === 'create') output(await request('/api/sessions', { method: 'POST', body: { title: id || '新会话' } }));
    else if (action === 'get') output(await request(`/api/sessions/${needId()}`));
    else if (action === 'rename') output(await request(`/api/sessions/${needId()}`, { method: 'PATCH', body: { title: await prompt('新会话名称：') } }));
    else if (action === 'delete') output(await request(`/api/sessions/${needId()}`, { method: 'DELETE' }));
    else throw new Error('不支持的会话命令');
    return;
  }
  if (group === 'models') {
    const resource = '/api/models';
    if (action === 'list') output(await request(resource));
    else if (action === 'test') output(await request(`${resource}/test`, { method: 'POST', body: { modelId: id || null, input: await stdinJson() } }));
    else if (action === 'create') output(await request(resource, { method: 'POST', body: await stdinJson() }));
    else if (action === 'update') output(await request(`${resource}/${needId()}`, { method: 'PUT', body: await stdinJson() }));
    else if (action === 'delete') output(await request(`${resource}/${needId()}`, { method: 'DELETE' }));
    else if (action === 'default') output(await request(`${resource}/${needId()}/default`, { method: 'POST' }));
    else throw new Error('不支持的资源命令');
    return;
  }
  if (group === 'tools') {
    if (action === 'list') output(await request('/api/tools'));
    else if (action === 'enable' || action === 'disable') output(await request(`/api/tools/${needId()}/enabled`, { method: 'POST', body: { enabled: action === 'enable' } }));
    else throw new Error('不支持的工具命令');
    return;
  }
  if (group === 'stats') { output(await request(`/api/stats/dashboard?period=${encodeURIComponent(action || '7d')}&scope=me`)); return; }
  if (group === 'jobs') {
    if (action === 'show') output(await request(`/api/workflow-jobs/${needId()}`));
    else if (action === 'events') output(await request(`/api/workflow-jobs/${needId()}/events`));
    else if (action === 'definition') output(await request(`/api/workflow-jobs/${needId()}/definition`));
    else throw new Error('不支持的任务命令');
    return;
  }
  if (group === 'mcp') {
    const resource = '/api/mcp-servers';
    if (action === 'list') output(await request(resource));
    else if (action === 'show') output(await request(`${resource}/${needId()}`));
    else if (action === 'create') output(await request(resource, { method: 'POST', body: await stdinJson() }));
    else if (action === 'update') output(await request(`${resource}/${needId()}`, { method: 'PUT', body: await stdinJson() }));
    else if (action === 'delete') output(await request(`${resource}/${needId()}`, { method: 'DELETE' }));
    else if (action === 'refresh') output(await request(`${resource}/${needId()}/refresh`, { method: 'POST' }));
    else if (action === 'tools') output(await request(`${resource}/${needId()}/tools`));
    else if (action === 'enable-tool' || action === 'disable-tool') {
      if (!extra) throw new Error('请提供工具名');
      output(await request(`${resource}/${needId()}/tools/${encodeURIComponent(extra)}/enabled`, { method: 'POST', body: { enabled: action === 'enable-tool' } }));
    } else if (action === 'call') output(await request('/api/mcp-calls', { method: 'POST', body: await stdinJson() }));
    else throw new Error('不支持的 MCP 命令');
    return;
  }
  if (group === 'skills') {
    if (action === 'list') output(await request('/api/skills'));
    else if (action === 'show') output(await request(`/api/skills/${needId()}`));
    else if (action === 'scripts') output(await request(`/api/skills/${needId()}/scripts`));
    else if (action === 'run') { needId(); const input = await stdinJson(); output(await request('/api/skill-script-runs', { method: 'POST', body: { ...input, skillId: id } })); }
    else if (action === 'install-local') output(await request('/api/skills/install/local', { method: 'POST', body: { path: id } }));
    else if (action === 'install-zip') { if (!id) throw new Error('请提供 ZIP 路径'); output(await uploadSkill(id)); }
    else if (action === 'install-github') output(await request('/api/skills/install/github', { method: 'POST', body: await stdinJson() }));
    else if (action === 'enable' || action === 'disable') output(await request(`/api/skills/${needId()}/enabled`, { method: 'POST', body: { enabled: action === 'enable' } }));
    else if (action === 'update') output(await request(`/api/skills/${needId()}`, { method: 'PUT', body: await stdinJson() }));
    else if (action === 'refresh') output(await request(`/api/skills/${needId()}/refresh`, { method: 'POST' }));
    else if (action === 'delete') output(await request(`/api/skills/${needId()}`, { method: 'DELETE' }));
    else throw new Error('不支持的技能命令');
    return;
  }
  if (group === 'memories') {
    if (action === 'list') output(await request(`/api/memories${id ? '?layer=' + encodeURIComponent(id) : ''}`));
    else if (action === 'show') output(await request(`/api/memories/${needId()}`));
    else if (action === 'create') output(await request('/api/memories', { method: 'POST', body: await stdinJson() }));
    else if (action === 'update') output(await request(`/api/memories/${needId()}`, { method: 'PUT', body: await stdinJson() }));
    else if (action === 'delete') { if (await prompt('删除记忆将清理依赖的场景和画像，输入“确认删除”：') !== '确认删除') throw new Error('未确认删除'); output(await request(`/api/memories/${needId()}`, { method: 'DELETE' })); }
    else if (action === 'jobs') output(await request('/api/memory-jobs'));
    else if (action === 'retry') output(await request(`/api/memory-jobs/${needId()}/retry`, { method: 'POST' }));
    else throw new Error('不支持的记忆命令');
    return;
  }
  if (group === 'approvals') {
    const actionId = needId();
    if (extra && !['mcp', 'skill'].includes(extra)) throw new Error('动作类型只能省略或填写 mcp、skill');
    const resource = extra === 'mcp' ? 'mcp-actions' : extra === 'skill' ? 'skill-script-actions' : 'actions';
    if (action === 'show') { output(await request(`/api/${resource}/${actionId}`)); return; }
    if (action !== 'approve' && action !== 'reject') throw new Error('不支持的确认命令');
    output(await request(`/api/${resource}/${actionId}`));
    const answer = await prompt(`请输入“${action === 'approve' ? '确认执行' : '确认拒绝'}”：`);
    if (answer !== (action === 'approve' ? '确认执行' : '确认拒绝')) throw new Error('未确认，操作未提交');
    output(await request(`/api/${resource}/${actionId}/${action}`, { method: 'POST' }));
    return;
  }
  throw new Error('不支持的命令，请运行 agentdesk help');
}
main().catch(fail);
