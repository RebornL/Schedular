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

    @GetMapping
    public List<TaskView> list(@RequestParam(required = false) String status) {
        return taskService.list(status);
    }

    @GetMapping("/{taskId}")
    public TaskView get(@PathVariable String taskId) {
        return taskService.get(taskId);
    }

    @PutMapping("/{taskId}")
    public TaskView.Created update(@PathVariable String taskId,
                                   @RequestBody TaskCreateRequest request) {
        return taskService.update(taskId, request);
    }

    @PostMapping("/{taskId}/pause")
    public TaskView pause(@PathVariable String taskId) {
        return taskService.pause(taskId);
    }

    @PostMapping("/{taskId}/resume")
    public TaskView resume(@PathVariable String taskId) {
        return taskService.resume(taskId);
    }

    @DeleteMapping("/{taskId}")
    public TaskView stop(@PathVariable String taskId) {
        return taskService.stop(taskId);
    }
}
