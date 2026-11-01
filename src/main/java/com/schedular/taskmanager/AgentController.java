package com.schedular.taskmanager;

import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/agents/**，仅 ADMIN（ApiKeyFilter 门槛）。 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentRegistryService agentRegistryService;

    public AgentController(AgentRegistryService agentRegistryService) {
        this.agentRegistryService = agentRegistryService;
    }

    @PostMapping
    public AgentView create(@RequestBody AgentRequest request) {
        return agentRegistryService.create(request);
    }

    @GetMapping
    public List<AgentView> list() {
        return agentRegistryService.list();
    }

    @GetMapping("/{agentId}")
    public AgentView get(@PathVariable String agentId) {
        return agentRegistryService.get(agentId);
    }

    @PutMapping("/{agentId}")
    public AgentView update(@PathVariable String agentId, @RequestBody AgentRequest request) {
        return agentRegistryService.update(agentId, request);
    }

    @DeleteMapping("/{agentId}")
    public AgentView disable(@PathVariable String agentId) {
        return agentRegistryService.disable(agentId);
    }
}
