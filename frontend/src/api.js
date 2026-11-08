const TOKEN_KEY = 'workbench-session';

export function getToken() { return sessionStorage.getItem(TOKEN_KEY) || ''; }
export function setToken(value) { if (value) sessionStorage.setItem(TOKEN_KEY, value); else sessionStorage.removeItem(TOKEN_KEY); }

export async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (getToken()) headers.Authorization = `Bearer ${getToken()}`;
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  let response;
  try { response = await fetch(path, { ...options, headers, body: options.body === undefined ? undefined : JSON.stringify(options.body) }); }
  catch { throw new Error('无法连接本机服务，请检查服务是否启动'); }
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || `请求失败（${response.status}）`);
  return data;
}

export async function upload(path, form) {
  let response;
  try { response = await fetch(path, { method: 'POST', headers: { Authorization: `Bearer ${getToken()}` }, body: form }); }
  catch { throw new Error('无法连接本机服务，请检查服务是否启动'); }
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || `请求失败（${response.status}）`);
  return data;
}

export async function streamRun(id, after, onEvent, signal) {
  const response = await fetch(`/api/runs/${encodeURIComponent(id)}/stream?after=${after}`, {
    headers: { Authorization: `Bearer ${getToken()}` }, signal,
  });
  if (!response.ok || !response.body) throw new Error('无法接收运行事件');
  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += value.replace(/\r\n/g, '\n');
    let end;
    while ((end = buffer.indexOf('\n\n')) !== -1) {
      const block = buffer.slice(0, end); buffer = buffer.slice(end + 2);
      let id = '', type = 'message'; const data = [];
      for (const line of block.split('\n')) {
        if (line.startsWith('id:')) id = line.slice(3).trim();
        else if (line.startsWith('event:')) type = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
      }
      if (data.length) {
        let payload; try { payload = JSON.parse(data.join('\n')); } catch { payload = { text: data.join('\n') }; }
        onEvent({ id: Number(id) || 0, type, payload });
      }
    }
  }
}
