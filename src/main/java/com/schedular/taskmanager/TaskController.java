package com.schedular.taskmanager;

import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** /api/tasks：CALLER/ADMIN 均可访问，租户按 TenantContext 过滤。 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public TaskView.Created create(@RequestBody TaskCreateRequest request) {
        return taskService.create(request);
    }

    /** tenant 口径见 security/TenantScope：CALLER 恒按认证租户，ADMIN 必须显式传 tenant。 */
    @GetMapping
    public List<TaskView> list(@RequestParam(required = false) String tenant,
                               @RequestParam(required = false) String status) {
        return taskService.list(tenant, status);
    }

    @GetMapping("/{taskId}")
    public TaskView get(@PathVariable String taskId,
                        @RequestParam(required = false) String tenant) {
        return taskService.get(taskId, tenant);
    }

    @PutMapping("/{taskId}")
    public TaskView.Created update(@PathVariable String taskId,
                                   @RequestParam(required = false) String tenant,
                                   @RequestBody TaskCreateRequest request) {
        return taskService.update(taskId, tenant, request);
    }

    @PostMapping("/{taskId}/pause")
    public TaskView pause(@PathVariable String taskId,
                          @RequestParam(required = false) String tenant) {
        return taskService.pause(taskId, tenant);
    }

    @PostMapping("/{taskId}/resume")
    public TaskView resume(@PathVariable String taskId,
                           @RequestParam(required = false) String tenant) {
        return taskService.resume(taskId, tenant);
    }

    @DeleteMapping("/{taskId}")
    public TaskView stop(@PathVariable String taskId,
                         @RequestParam(required = false) String tenant) {
        return taskService.stop(taskId, tenant);
    }
}
