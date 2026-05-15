# Supabase 配置（邮箱登录 + 云端日志）

## 1) 必要信息
- Supabase Project URL：`https://xxxx.supabase.co`
- Supabase anon key：Dashboard -> Project Settings -> API -> anon public key

将这两个值填入 Android 的 `BuildConfig`：
- [app/build.gradle.kts](file:///D:/pythonproject%202/Android%20APP/app/build.gradle.kts)：
  - `SUPABASE_URL`
  - `SUPABASE_ANON_KEY`

注意：只允许使用 `anon` 公钥，禁止把 service role key 放进 App。

## 2) Postgres 表：translation_logs

在 Supabase SQL Editor 执行：

```sql
create table if not exists public.translation_logs (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  timestamp_ms bigint not null,
  duration_ms bigint not null,
  input_chars integer not null,
  output_chars integer not null,
  unk_count integer not null,
  model_version text,
  input_text text,
  output_text text,
  created_at timestamptz not null default now()
);

create unique index if not exists translation_logs_user_ts_uq
on public.translation_logs(user_id, timestamp_ms);

alter table public.translation_logs enable row level security;

create policy "translation_logs_insert_own"
on public.translation_logs
for insert
with check (auth.uid() = user_id);

create policy "translation_logs_select_own"
on public.translation_logs
for select
using (auth.uid() = user_id);
```

## 2.5) Postgres 表：user_dicts（云端词典）

在 Supabase SQL Editor 执行：

```sql
create table if not exists public.user_dicts (
  user_id uuid primary key references auth.users(id) on delete cascade,
  content text not null,
  updated_at timestamptz not null default now()
);

alter table public.user_dicts enable row level security;

create policy "user_dicts_select_own"
on public.user_dicts
for select
using (auth.uid() = user_id);

create policy "user_dicts_insert_own"
on public.user_dicts
for insert
with check (auth.uid() = user_id);

create policy "user_dicts_update_own"
on public.user_dicts
for update
using (auth.uid() = user_id)
with check (auth.uid() = user_id);
```

## 3) 模型更新（推荐：Git + Google Drive）

由于 Supabase Storage 在免费额度下对单文件大小/总容量存在限制，模型包较大时建议：
- `model_bundle_*.zip` 上传到 Google Drive（公开下载链接）
- `latest.json` 放在 Git 仓库（raw 链接），App 读取该 JSON 获取版本与 zipUrl

App 侧需要配置：
- `MODEL_LATEST_JSON_URL`（建议放在用户级 gradle.properties，不提交到 Git）

`latest.json` 示例：

```json
{
  "modelVersion": "2026.05.14",
  "zipUrl": "https://drive.google.com/uc?export=download&id=FILE_ID",
  "zipSha256": "49cb1c5d7fc7cede0ec515b567718a572f581bca28b6152d591258e4a0628afc",
  "notes": "模型更新说明"
}
```

## 4) App 当前实现范围
- 已支持 Supabase 邮箱注册/登录（使用 anon key 调用 Auth API）
- 已保留离线账号作为备用模式
