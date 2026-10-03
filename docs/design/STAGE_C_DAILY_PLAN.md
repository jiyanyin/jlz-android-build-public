# Stage C · DailyPlan Task System

Status: implementation branch `feature/world-between-control-v1-20261003`.

## Goal

Give World Between a durable daily task layer before Study Session, App Hub and the later soft-gate policy stages.

The official ChatGPT remains the decision brain. The app is the visible control surface. Stage C supplies the shared task contract both sides will use later.

## DailyTask contract

Every task supports:

- `task_id`
- `date`
- `title`
- `category`
- `priority`
- `status`
- `must_do`
- `user_pinned`
- `created_by`
- `owner`
- `estimated_minutes`
- `due_at`
- `next_action`
- `source`
- `sort_order`
- `created_at`
- `updated_at`

The plan-level `date` is authoritative; tasks belong to that plan date rather than duplicating a mutable task date internally.

Default categories:

1. 考试/学习
2. 工作
3. 生活
4. 身体与休息
5. 杂事
6. 想做但非必须

Statuses:

- `todo`
- `in_progress`
- `done`
- `postponed`
- `incomplete`

Priorities:

- `high`
- `normal`
- `low`

## user_pinned hard rule

`user_pinned=true` means the user explicitly said the task must stay in today's plan.

A companion/official-GPT mutation may:

- change the next action;
- change time/estimate;
- start or complete it;
- postpone it;
- mark it incomplete;
- split it by adding additional tasks.

It may **not**:

- silently delete it;
- silently clear its user pin.

A companion bulk replacement that omits an existing pinned task automatically carries that task forward and returns its ID in `protected_task_ids`.

The user-facing Web route may delete or unpin a pinned task because that is an explicit direct user action.

## Runtime APIs

Low-privilege Web:

- `GET /api/web/daily-plan?space_id=...&date=YYYY-MM-DD`
- `POST /api/web/daily-plan/task`

Authenticated companion / MCP:

- `GET /api/daily-plan?space_id=...&date=YYYY-MM-DD`
- `POST /api/daily-plan/task`
- `POST /api/daily-plan` for bulk replacement

Official GPT MCP tools:

- `read_daily_plan`
- `mutate_daily_plan_task`
- `replace_daily_plan`

## Persistence

Runtime persists the daily-plan dictionary through the canonical durable store under `daily_plans` and also keeps the established local JSON fallback for non-durable development/test runs.

## Home UI

Home shows:

1. one **当前第一步** derived from the in-progress task, otherwise the highest-ranked open task;
2. at most **3 main open tasks**;
3. additional open tasks folded;
4. completed tasks folded;
5. postponed tasks folded.

A task can be created or edited from the task sheet. The sheet exposes:

- title;
- next action;
- category;
- priority;
- estimated minutes;
- desired due time;
- “今天必须做 · 固定”.

Quick actions on Home support start, complete and reopen.

## Sorting

Active work is ordered before completed/postponed work. Within that:

1. in-progress;
2. todo;
3. incomplete;
4. postponed;
5. done.

Then user-pinned and must-do tasks rise before ordinary tasks, followed by `sort_order` and priority.

## Synchronization

- Local task edits are optimistic.
- They use the existing Web outbox and stable retry mechanism.
- Runtime is pulled after connection and again while the app is active.
- Runtime remains authoritative after queued writes flush.
- The app refreshes the current local-date plan rather than assuming the server's UTC date.

## Stage C acceptance

1. DailyPlan survives Runtime restart/persistence.
2. Web can create, read, update and delete tasks.
3. Home shows current first step and at most three main tasks.
4. Additional and completed tasks remain available without crowding Home.
5. User-pinned tasks cannot be silently removed by companion mutation or bulk replacement.
6. Official GPT has MCP read/mutate/replace tools for later hourly and 01:00 policy tasks.
7. Runtime Python + MCP regression suites pass.
8. DailyPlan Web unit tests pass.
9. TypeScript/Vite production build passes.
10. Android regression build remains green.
11. No production merge or latest APK publication occurs during Stage C.
