PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'USER' CHECK(role IN ('USER','ADMIN')), created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS email_codes (
  email TEXT PRIMARY KEY, code_hash TEXT NOT NULL, expires_at INTEGER NOT NULL,
  sent_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS auth_tokens (
  token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id),
  kind TEXT NOT NULL CHECK(kind IN ('USER','AGENT')), expires_at INTEGER NOT NULL, created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tokens_user ON auth_tokens(user_id);
CREATE TABLE IF NOT EXISTS model_configs (
  id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), kind TEXT NOT NULL CHECK(kind IN ('CHAT','EMBEDDING')),
  name TEXT NOT NULL, provider TEXT NOT NULL, base_url TEXT NOT NULL, model_name TEXT NOT NULL,
  api_key_cipher TEXT, enabled INTEGER NOT NULL DEFAULT 1, version INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_models_user ON model_configs(user_id,kind);
CREATE TABLE IF NOT EXISTS model_test_tokens (
  token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), config_digest TEXT NOT NULL,
  expires_at INTEGER NOT NULL, created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_model_tests_user ON model_test_tokens(user_id,expires_at);
CREATE TABLE IF NOT EXISTS user_settings (
  user_id TEXT PRIMARY KEY REFERENCES users(id), default_chat_model TEXT, default_embedding_model TEXT
);
CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), title TEXT NOT NULL,
  title_locked INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id,updated_at DESC);
CREATE TABLE IF NOT EXISTS messages (
  id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), session_id TEXT NOT NULL REFERENCES sessions(id),
  role TEXT NOT NULL, content TEXT NOT NULL, mode TEXT NOT NULL DEFAULT 'CHAT' CHECK(mode IN ('CHAT','BUILD')), created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(user_id,session_id,created_at);
CREATE TABLE IF NOT EXISTS runs (
  id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), session_id TEXT NOT NULL REFERENCES sessions(id),
  status TEXT NOT NULL, mode TEXT NOT NULL DEFAULT 'CHAT' CHECK(mode IN ('CHAT','BUILD')),
  model_id TEXT, model_version INTEGER, error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_runs_user ON runs(user_id,session_id,created_at);
CREATE TABLE IF NOT EXISTS run_events (
  run_id TEXT NOT NULL REFERENCES runs(id), seq INTEGER NOT NULL, type TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL,
  PRIMARY KEY(run_id,seq)
);
CREATE TABLE IF NOT EXISTS agent_delegations (
  token_hash TEXT PRIMARY KEY REFERENCES auth_tokens(token_hash) ON DELETE CASCADE,
  run_id TEXT NOT NULL REFERENCES runs(id), created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS route_decisions (
  run_id TEXT PRIMARY KEY REFERENCES runs(id) ON DELETE CASCADE, user_id TEXT NOT NULL REFERENCES users(id),
  mode TEXT NOT NULL CHECK(mode IN ('CHAT','BUILD')), intent TEXT NOT NULL, candidates_json TEXT NOT NULL,
  decision_source TEXT NOT NULL DEFAULT 'RULE', created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_route_decisions_user ON route_decisions(user_id,created_at DESC);
CREATE TABLE IF NOT EXISTS run_contexts (
  run_id TEXT PRIMARY KEY REFERENCES runs(id), base_url TEXT NOT NULL, model_name TEXT NOT NULL,
  api_key_cipher TEXT, messages_json TEXT NOT NULL, pending_job_id TEXT, pending_tool_call_id TEXT, pending_calls_json TEXT,
  skills_json TEXT NOT NULL DEFAULT '[]', source_message_id TEXT, mcp_tools_json TEXT NOT NULL DEFAULT '[]'
);
CREATE TABLE IF NOT EXISTS db_connections (
  id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), type TEXT NOT NULL CHECK(type IN ('MYSQL','REDIS')),
  name TEXT NOT NULL, host TEXT NOT NULL, port INTEGER NOT NULL, database_name TEXT NOT NULL,
  username TEXT, password_cipher TEXT, version INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_connections_user ON db_connections(user_id,type);
CREATE TABLE IF NOT EXISTS workflow_jobs (
  id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), run_id TEXT, connection_id TEXT NOT NULL,
  workflow_version INTEGER NOT NULL DEFAULT 1, workflow_snapshot TEXT, stage TEXT NOT NULL, status TEXT NOT NULL,
  result_json TEXT, error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_jobs_user ON workflow_jobs(user_id,created_at DESC);
CREATE TABLE IF NOT EXISTS db_actions (
  id TEXT PRIMARY KEY, job_id TEXT NOT NULL REFERENCES workflow_jobs(id), user_id TEXT NOT NULL REFERENCES users(id),
  connection_id TEXT NOT NULL, connection_version INTEGER NOT NULL, operation_json TEXT NOT NULL, operation_digest TEXT NOT NULL,
  status TEXT NOT NULL, expires_at INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, error TEXT
);
CREATE INDEX IF NOT EXISTS idx_actions_user_status ON db_actions(user_id,status,created_at DESC);
CREATE TABLE IF NOT EXISTS workflow_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT, job_id TEXT NOT NULL REFERENCES workflow_jobs(id),
  stage TEXT NOT NULL, status TEXT NOT NULL, message TEXT NOT NULL, created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_workflow_events_job ON workflow_events(job_id,id);
CREATE TABLE IF NOT EXISTS skills (
  id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), name TEXT NOT NULL, description TEXT NOT NULL,
  content TEXT NOT NULL, source_type TEXT NOT NULL, source_ref TEXT NOT NULL, package_dir TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1, version INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_skills_user ON skills(user_id,name);
CREATE TABLE IF NOT EXISTS builtin_skill_settings (
  user_id TEXT NOT NULL REFERENCES users(id), skill_id TEXT NOT NULL, enabled INTEGER NOT NULL,
  PRIMARY KEY(user_id,skill_id)
);
CREATE TABLE IF NOT EXISTS builtin_tool_settings (
  user_id TEXT NOT NULL REFERENCES users(id), tool_id TEXT NOT NULL CHECK(tool_id IN ('builtin-mysql','builtin-redis')),
  enabled INTEGER NOT NULL DEFAULT 1, updated_at INTEGER NOT NULL,
  PRIMARY KEY(user_id,tool_id)
);
CREATE TABLE IF NOT EXISTS memories (
  id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), layer TEXT NOT NULL CHECK(layer IN ('L1','L2','L3')),
  content TEXT NOT NULL, source_session_id TEXT, source_message_id TEXT, fingerprint TEXT NOT NULL,
  manual INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'ACTIVE', version INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_memories_user ON memories(user_id,layer,status,updated_at DESC);
CREATE TABLE IF NOT EXISTS memory_dependencies (
  user_id TEXT NOT NULL REFERENCES users(id), parent_id TEXT NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
  child_id TEXT NOT NULL REFERENCES memories(id) ON DELETE CASCADE, PRIMARY KEY(parent_id,child_id)
);
CREATE TABLE IF NOT EXISTS memory_suppressions (
  user_id TEXT NOT NULL REFERENCES users(id), source_message_id TEXT NOT NULL, fingerprint TEXT NOT NULL,
  PRIMARY KEY(user_id,source_message_id,fingerprint)
);
CREATE TABLE IF NOT EXISTS memory_source_suppressions (
  user_id TEXT NOT NULL REFERENCES users(id), source_message_id TEXT NOT NULL,
  PRIMARY KEY(user_id,source_message_id)
);
CREATE TABLE IF NOT EXISTS memory_embeddings (
  memory_id TEXT NOT NULL REFERENCES memories(id) ON DELETE CASCADE, model_id TEXT NOT NULL, model_version INTEGER NOT NULL,
  memory_version INTEGER NOT NULL, dimensions INTEGER NOT NULL, vector_json TEXT NOT NULL,
  PRIMARY KEY(memory_id,model_id,model_version)
);
CREATE TABLE IF NOT EXISTS memory_jobs (
  run_id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), session_id TEXT NOT NULL, source_message_id TEXT NOT NULL,
  status TEXT NOT NULL, error TEXT, attempts INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS mcp_servers (
  id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), name TEXT NOT NULL,
  transport TEXT NOT NULL CHECK(transport IN ('HTTP','STDIO')), url TEXT, command TEXT, args_json TEXT, bundle_hash TEXT,
  bearer_cipher TEXT, enabled INTEGER NOT NULL DEFAULT 1, version INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_mcp_servers_user ON mcp_servers(user_id,name);
CREATE TABLE IF NOT EXISTS mcp_tools (
  server_id TEXT NOT NULL REFERENCES mcp_servers(id) ON DELETE CASCADE, user_id TEXT NOT NULL REFERENCES users(id),
  name TEXT NOT NULL, description TEXT NOT NULL, input_schema TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1,
  read_only INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(server_id,name)
);
CREATE TABLE IF NOT EXISTS mcp_actions (
  id TEXT PRIMARY KEY, job_id TEXT NOT NULL REFERENCES workflow_jobs(id), user_id TEXT NOT NULL REFERENCES users(id),
  server_id TEXT NOT NULL, server_version INTEGER NOT NULL, operation_json TEXT NOT NULL, operation_digest TEXT NOT NULL,
  status TEXT NOT NULL, expires_at INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, error TEXT
);
CREATE INDEX IF NOT EXISTS idx_mcp_actions_user ON mcp_actions(user_id,status,created_at DESC);
CREATE TABLE IF NOT EXISTS skill_script_actions (
  id TEXT PRIMARY KEY, job_id TEXT NOT NULL REFERENCES workflow_jobs(id), user_id TEXT NOT NULL REFERENCES users(id),
  skill_id TEXT NOT NULL, skill_version INTEGER NOT NULL, operation_json TEXT NOT NULL, operation_digest TEXT NOT NULL,
  status TEXT NOT NULL, expires_at INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, error TEXT
);
CREATE INDEX IF NOT EXISTS idx_skill_script_actions_user ON skill_script_actions(user_id,status,created_at DESC);
