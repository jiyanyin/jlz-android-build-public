import assert from "node:assert/strict";
import test from "node:test";
import {
  emptyDailyPlan,
  normalizeDailyPlan,
  optimisticTaskMutation,
  planSections,
  taskDetailText,
} from "../src/lib/dailyPlan.ts";

test("normalizes Runtime DailyPlan payload", () => {
  const plan = normalizeDailyPlan({
    plan: {
      date: "2026-10-03",
      tasks: [{
        task_id: "a",
        title: "图推复盘",
        category: "考试/学习",
        priority: "high",
        status: "todo",
        user_pinned: true,
        must_do: true,
      }],
      current_task_id: "a",
      current_step: { task_id: "a", title: "图推复盘", next_action: "先做第一题" },
    },
  }, "2026-10-03");
  assert.equal(plan.tasks[0].task_id, "a");
  assert.equal(plan.tasks[0].date, "2026-10-03");
  assert.equal(plan.current_step?.next_action, "先做第一题");
  assert.equal(plan.tasks[0].user_pinned, true);
});

test("optimistic mutation derives current first step", () => {
  const plan = optimisticTaskMutation(emptyDailyPlan("2026-10-03"), "2026-10-03", "upsert", {
    task_id: "a",
    title: "申论",
    category: "考试/学习",
    next_action: "先读材料",
    status: "todo",
  });
  assert.equal(plan.current_task_id, "a");
  assert.equal(plan.current_step?.next_action, "先读材料");
});

test("start outranks todo and complete removes from active section", () => {
  let plan = emptyDailyPlan("2026-10-03");
  plan = optimisticTaskMutation(plan, plan.date, "upsert", { task_id: "a", title: "A", status: "todo" });
  plan = optimisticTaskMutation(plan, plan.date, "upsert", { task_id: "b", title: "B", status: "todo" });
  plan = optimisticTaskMutation(plan, plan.date, "start", { task_id: "b" });
  assert.equal(plan.current_task_id, "b");
  plan = optimisticTaskMutation(plan, plan.date, "complete", { task_id: "b" });
  assert.equal(plan.current_task_id, "a");
  assert.equal(planSections(plan).completed[0].task_id, "b");
});

test("home exposes at most three main open tasks", () => {
  let plan = emptyDailyPlan("2026-10-03");
  for (let i = 0; i < 5; i += 1) {
    plan = optimisticTaskMutation(plan, plan.date, "upsert", {
      task_id: String(i),
      title: `任务${i}`,
      sort_order: i,
    });
  }
  const sections = planSections(plan);
  assert.equal(sections.main.length, 3);
  assert.equal(sections.other.length, 2);
});

test("tracks completion timestamps without guessing dates for legacy tasks", () => {
  const date = "2026-10-10";
  let plan = emptyDailyPlan(date);
  plan = optimisticTaskMutation(plan,date,"upsert",{task_id:"a",title:"学习"});
  assert.equal(plan.tasks[0].completed_at, "");
  plan = optimisticTaskMutation(plan,date,"complete",{task_id:"a"});
  const stamp = plan.tasks[0].completed_at;
  assert.ok(stamp && !Number.isNaN(Date.parse(stamp)));
  plan = optimisticTaskMutation(plan,date,"upsert",{task_id:"a",description:"已整理"});
  assert.equal(plan.tasks[0].completed_at,stamp);
  plan = optimisticTaskMutation(plan,date,"reopen",{task_id:"a"});
  assert.equal(plan.tasks[0].completed_at, "");

  const old = normalizeDailyPlan({plan:{date,tasks:[{
    task_id:"legacy",date,title:"旧已完成事项",status:"done",
    created_at:"2026-09-29T12:00:00+08:00"
  }]}},date);
  assert.equal(old.tasks[0].completed_at, "");
});

test("legacy next action appears in the single concrete content field without duplicate text", () => {
  assert.equal(taskDetailText({ description: "", next_action: "先打开伴读" }), "先打开伴读");
  assert.equal(taskDetailText({ description: "整理材料", next_action: "先列清单" }), "整理材料\n先列清单");
  assert.equal(taskDetailText({ description: "整理材料：先列清单", next_action: "先列清单" }), "整理材料：先列清单");
  assert.equal(taskDetailText({ description: "详细内容", next_action: "" }), "详细内容");
  assert.equal(taskDetailText(null), "");
});
