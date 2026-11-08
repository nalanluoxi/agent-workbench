import { mkdir, readFile, writeFile, chmod, rm } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { createInterface } from 'node:readline/promises';

const configDir = process.env.WORKBENCH_CLI_HOME || join(homedir(), '.agent-workbench');
const tokenFile = join(configDir, 'token');
const base = (process.env.WORKBENCH_URL || 'http://127.0.0.1:8765').replace(/\/$/, '');

export async function token() {
  try { return (await readFile(tokenFile, 'utf8')).trim(); }
  catch { throw new Error('请先使用 agentdesk login 登录'); }
}

export async function saveToken(value) {
  await mkdir(configDir, { recursive: true, mode: 0o700 });
  await chmod(configDir, 0o700);
  await writeFile(tokenFile, value, { mode: 0o600 });
  await chmod(tokenFile, 0o600);
}

export async function clearToken() { await rm(tokenFile, { force: true }); }

export async function request(path, { method = 'GET', body, auth = true, delegatedToken } = {}) {
  const headers = {};
  if (auth) headers.Authorization = `Bearer ${delegatedToken || await token()}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let response;
  try { response = await fetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30000) }); }
  catch { throw new Error('无法连接本机服务，请检查服务是否启动'); }
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || `请求失败：HTTP ${response.status}`);
  return data;
}

export async function prompt(label) {
  if (!process.stdin.isTTY) throw new Error('该操作需要交互式终端');
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  try { return (await rl.question(label)).trim(); } finally { rl.close(); }
}

export async function password(label) {
  if (!process.stdin.isTTY || !process.stdin.setRawMode) throw new Error('输入密码需要交互式终端');
  process.stdout.write(label);
  process.stdin.setEncoding('utf8');
  process.stdin.setRawMode(true);
  process.stdin.resume();
  return await new Promise((resolve, reject) => {
    let text = '';
    function cleanup() { process.stdin.off('data', onData); process.stdin.setRawMode(false); process.stdin.pause(); process.stdout.write('\n'); }
    function onData(chunk) {
      for (const char of chunk) {
        if (char === '\u0003') { cleanup(); reject(new Error('已取消')); return; }
        if (char === '\r' || char === '\n') { cleanup(); resolve(text); return; }
        if (char === '\u007f' || char === '\b') text = Array.from(text).slice(0, -1).join('');
        else if (char >= ' ') text += char;
      }
    }
    process.stdin.on('data', onData);
  });
}

export async function stdinJson() {
  if (process.stdin.isTTY) throw new Error('请通过标准输入提供 JSON 参数');
  let raw = '';
  for await (const chunk of process.stdin) {
    raw += chunk;
    if (raw.length > 1024 * 1024) throw new Error('输入过大');
  }
  try { return JSON.parse(raw); } catch { throw new Error('标准输入必须是有效的 JSON'); }
}

export async function uploadSkill(path, subpath = '') {
  const bytes = await readFile(path);
  if (bytes.length > 20 * 1024 * 1024) throw new Error('ZIP 文件不能超过 20 MB');
  const form = new FormData();
  form.append('file', new Blob([bytes], { type: 'application/zip' }), path.split('/').pop());
  form.append('subpath', subpath);
  const headers = { Authorization: `Bearer ${await token()}` };
  const response = await fetch(base + '/api/skills/install/zip', { method: 'POST', headers, body: form, signal: AbortSignal.timeout(60000) });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || `请求失败：HTTP ${response.status}`);
  return data;
}

export async function streamRun(runId, after, onEvent) {
  const response = await fetch(`${base}/api/runs/${encodeURIComponent(runId)}/stream?after=${after}`, { headers: { Authorization: `Bearer ${await token()}` }, signal: AbortSignal.timeout(120000) });
  if (!response.ok || !response.body) throw new Error('无法接收运行事件');
  const decoder = new TextDecoder(); let buffer = '';
  for await (const chunk of response.body) {
    buffer += decoder.decode(chunk, { stream: true }).replace(/\r\n/g, '\n');
    let index;
    while ((index = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, index); buffer = buffer.slice(index + 2);
      let id = 0, type = 'message'; const data = [];
      for (const line of block.split('\n')) {
        if (line.startsWith('id:')) id = Number(line.slice(3).trim()) || 0;
        else if (line.startsWith('event:')) type = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
      }
      if (data.length) { let payload; try { payload = JSON.parse(data.join('\n')); } catch { payload = { text: data.join('\n') }; } await onEvent({ id, type, payload }); }
    }
  }
}

export function output(data) { process.stdout.write(JSON.stringify(data, null, 2) + '\n'); }
export function fail(error) { process.stderr.write(`错误：${error.message}\n`); process.exitCode = 1; }
