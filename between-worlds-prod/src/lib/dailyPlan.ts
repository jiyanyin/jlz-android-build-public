export const TASK_CATEGORIES = [
  "考试/学习",
  "工作",
  "生活",
  "身体与休息",
  "杂事",
  "想做但非必须",
] as const;

export type DailyTaskStatus = "todo" | "in_progress" | "done" | "postponed" | "incomplete";
export type DailyTaskPriority = "high" | "normal" | "low";
export type DailyTask = {
  task_id: string;
  date: string;
  title: string;
  description?: string;
  category: string;
  priority: DailyTaskPriority;
  status: DailyTaskStatus;
  must_do: boolean;
  user_pinned: boolean;
  created_by: string;
  owner: string;
  estimated_minutes: number;
  due_at: string;
  next_action: string;
  source: string;
  sort_order: number;
  created_at: string;
  updated_at: string;
};
export type DailyPlan = {
  version: string;
  space_id: string;
  date: string;
  tasks: DailyTask[];
  current_task_id: string;
  current_step: null | {
    task_id: string;
    title: string;
    next_action: string;
    estimated_minutes: number;
  };
  updated_at: string;
};

const asObj = (value: unknown): Record<string, unknown> =>
  value && typeof value === "object" ? value as Record<string, unknown> : {};
const str = (value: unknown) => typeof value === "string" ? value : typeof value === "number" ? String(value) : "";
const num = (value: unknown, fallback = 0) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
};
const bool = (value: unknown) => value === true || value === 1 || value === "1" || value === "true";
const status = (value: unknown): DailyTaskStatus =>
  ["todo", "in_progress", "done", "postponed", "incomplete"].includes(str(value))
    ? str(value) as DailyTaskStatus : "todo";
const priority = (value: unknown): DailyTaskPriority =>
  ["high", "normal", "low"].includes(str(value))
    ? str(value) as DailyTaskPriority : "normal";

export const emptyDailyPlan = (date: string): DailyPlan => ({
  version: "daily-plan-1",
  space_id: "world-between-primary",
  date,
  tasks: [],
  current_task_id: "",
  current_step: null,
  updated_at: new Date().toISOString(),
});

export const normalizeDailyPlan = (payload: unknown, fallbackDate: string): DailyPlan => {
  const root = asObj(payload);
  const rawPlan = asObj(root.plan ?? root.daily_plan ?? payload);
  const planDate = str(rawPlan.date) || fallbackDate;
  const rawTasks = Array.isArray(rawPlan.tasks) ? rawPlan.tasks : [];
  const tasks: DailyTask[] = rawTasks.map((raw) => {
    const task = asObj(raw);
    return {
      task_id: str(task.task_id ?? task.id),
      date: str(task.date) || planDate,
      title: str(task.title),
      category: str(task.category) || "杂事",
      priority: priority(task.priority),
      status: status(task.status),
      must_do: bool(task.must_do),
      user_pinned: bool(task.user_pinned),
      created_by: str(task.created_by),
      owner: str(task.owner) || "user",
      estimated_minutes: Math.max(0, Math.min(1440, num(task.estimated_minutes))),
      due_at: str(task.due_at),
      description: str(task.description),
      next_action: str(task.next_action),
      source: str(task.source),
      sort_order: Math.max(0, num(task.sort_order, 100)),
      created_at: str(task.created_at),
      updated_at: str(task.updated_at),
    };
  }).filter((task) => task.task_id && task.title);
  const currentRaw = asObj(rawPlan.current_step);
  return {
    version: str(rawPlan.version) || "daily-plan-1",
    space_id: str(rawPlan.space_id) || "world-between-primary",
    date: planDate,
    tasks,
    current_task_id: str(rawPlan.current_task_id),
    current_step: str(currentRaw.task_id) ? {
      task_id: str(currentRaw.task_id),
      title: str(currentRaw.title),
      next_action: str(currentRaw.next_action),
      estimated_minutes: Math.max(0, num(currentRaw.estimated_minutes)),
    } : null,
    updated_at: str(rawPlan.updated_at) || new Date().toISOString(),
  };
};

const taskSort = (a: DailyTask, b: DailyTask) => {
  const statusRank: Record<DailyTaskStatus, number> = {
    in_progress: 0, todo: 1, incomplete: 2, postponed: 3, done: 4,
  };
  const priorityRank: Record<DailyTaskPriority, number> = { high: 0, normal: 1, low: 2 };
  return statusRank[a.status] - statusRank[b.status]
    || Number(b.user_pinned) - Number(a.user_pinned)
    || Number(b.must_do) - Number(a.must_do)
    || a.sort_order - b.sort_order
    || priorityRank[a.priority] - priorityRank[b.priority]
    || a.task_id.localeCompare(b.task_id);
};

const withDerivedCurrent = (plan: DailyPlan): DailyPlan => {
  const tasks = [...plan.tasks].sort(taskSort);
  const current = tasks.find((task) => task.status === "in_progress")
    ?? tasks.find((task) => task.status === "todo" || task.status === "incomplete")
    ?? null;
  return {
    ...plan,
    tasks,
    current_task_id: current?.task_id ?? "",
    current_step: current ? {
      task_id: current.task_id,
      title: current.title,
      next_action: current.next_action || current.title,
      estimated_minutes: current.estimated_minutes,
    } : null,
    updated_at: new Date().toISOString(),
  };
};

export const optimisticTaskMutation = (
  current: DailyPlan | null,
  date: string,
  action: string,
  payload: Partial<DailyTask> & { task_id?: string },
): DailyPlan => {
  const plan = current?.date === date ? current : emptyDailyPlan(date);
  const existing = payload.task_id
    ? plan.tasks.find((task) => task.task_id === payload.task_id)
    : undefined;
  if (action === "delete" && payload.task_id) {
    return withDerivedCurrent({ ...plan, tasks: plan.tasks.filter((task) => task.task_id !== payload.task_id) });
  }
  if (!existing && (!payload.task_id || !payload.title)) return plan;

  const base: DailyTask = existing ?? {
    task_id: payload.task_id!,
    date,
    title: payload.title!,
    category: "杂事",
    priority: "normal",
    status: "todo",
    must_do: false,
    user_pinned: false,
    created_by: "user",
    owner: "user",
    estimated_minutes: 0,
    due_at: "",
    next_action: "",
    source: "world_between_web",
    sort_order: 100,
    created_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  };
  const task: DailyTask = { ...base, ...payload, date, updated_at: new Date().toISOString() };
  if (action === "complete") task.status = "done";
  if (action === "start") task.status = "in_progress";
  if (action === "reopen") task.status = "todo";
  if (action === "postpone") task.status = "postponed";
  if (action === "incomplete") task.status = "incomplete";
  if (action === "pin") task.user_pinned = true;
  if (action === "unpin") task.user_pinned = false;
  const tasks = [...plan.tasks.filter((item) => item.task_id !== task.task_id), task];
  return withDerivedCurrent({ ...plan, tasks });
};

export const planSections = (plan: DailyPlan | null) => {
  const tasks = plan?.tasks ?? [];
  const active = tasks.filter((task) => !["done", "postponed"].includes(task.status));
  return {
    main: active.slice(0, 3),
    other: active.slice(3),
    completed: tasks.filter((task) => task.status === "done"),
    postponed: tasks.filter((task) => task.status === "postponed"),
  };
};
