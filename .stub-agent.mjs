// 一次性验证用 stub Agent：POST 返回合法 AgentExecutionResult（协议三）
Bun.serve({
  port: 9999,
  async fetch(req) {
    if (req.method === 'POST') {
      const body = await req.json();
      const now = new Date().toISOString();
      return Response.json({
        execution_id: body.execution_id,
        task_id: body.task_id,
        status: 'SUCCEEDED',
        started_at: now,
        finished_at: now,
        summary: 'stub 同步成功',
        business_events: [
          { type: 'STUB_EVENT', severity: 'HIGH', title: 'stub 高危事件', content: {} }
        ],
        report: { ok: true }
      });
    }
    return new Response('ok');
  }
});
