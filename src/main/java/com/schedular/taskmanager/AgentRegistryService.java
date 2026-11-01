package com.schedular.taskmanager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schedular.common.ApiException;
import com.schedular.storage.Agent;
import com.schedular.storage.AgentRepository;
import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Agent 注册中心：注册、查询、更新、停用（enabled=0，无物理删除）。 */
@Service
public class AgentRegistryService {

    private static final String TRIGGER_TYPE_HTTP_API = "HTTP_API";

    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AgentRegistryService(AgentRepository agentRepository, ObjectMapper objectMapper, Clock clock) {
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public AgentView create(AgentRequest request) {
        String agentId = request.agentId();
        if (agentId == null || agentId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_ID", "agent_id 必填");
        }
        validate(request);
        if (agentRepository.existsById(agentId)) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_ALREADY_EXISTS",
                    "agent_id 已存在: " + agentId);
        }
        String now = nowIso();
        Agent agent = new Agent(agentId, request.agentVersion(), request.triggerType(),
                request.endpointUrl(), request.resultMode(), toJson(request.authHeaders()),
                true, now, now);
        agentRepository.insert(agent);
        return AgentView.from(agent);
    }

    public AgentView get(String agentId) {
        return AgentView.from(find(agentId));
    }

    public List<AgentView> list() {
        return agentRepository.findAll().stream().map(AgentView::from).toList();
    }

    public AgentView update(String agentId, AgentRequest request) {
        Agent existing = find(agentId);
        validate(request);
        Agent updated = new Agent(agentId, request.agentVersion(), request.triggerType(),
                request.endpointUrl(), request.resultMode(), toJson(request.authHeaders()),
                existing.enabled(), existing.createdAt(), nowIso());
        agentRepository.update(updated);
        return AgentView.from(updated);
    }

    public AgentView disable(String agentId) {
        Agent existing = find(agentId);
        agentRepository.disable(agentId, nowIso());
        return new AgentView(existing.agentId(), existing.agentVersion(), existing.triggerType(),
                existing.endpointUrl(), existing.resultMode(), false,
                existing.createdAt(), nowIso());
    }

    private Agent find(String agentId) {
        return agentRepository.findById(agentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND",
                        "agent 不存在: " + agentId));
    }

    private void validate(AgentRequest request) {
        if (!TRIGGER_TYPE_HTTP_API.equals(request.triggerType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TRIGGER_TYPE",
                    "trigger_type 当前仅支持 HTTP_API");
        }
        if (!"SYNC".equals(request.resultMode()) && !"EVENT_CALLBACK".equals(request.resultMode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESULT_MODE",
                    "result_mode 仅支持 SYNC / EVENT_CALLBACK");
        }
        validateEndpointUrl(request.endpointUrl());
    }

    private void validateEndpointUrl(String endpointUrl) {
        URI uri;
        try {
            uri = URI.create(endpointUrl == null ? "" : endpointUrl.trim());
        } catch (IllegalArgumentException e) {
            throw invalidEndpointUrl();
        }
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())
                || uri.getHost() == null || uri.getHost().isBlank()) {
            throw invalidEndpointUrl();
        }
    }

    private static ApiException invalidEndpointUrl() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ENDPOINT_URL",
                "endpoint_url 必须是合法的 http(s) URL");
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AUTH_HEADERS",
                    "auth_headers 序列化失败");
        }
    }

    private String nowIso() {
        return OffsetDateTime.now(clock).toString();
    }
}
