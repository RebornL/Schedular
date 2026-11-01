package com.schedular.taskmanager;

import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/subscriptions/**：CALLER / ADMIN 均可用，租户按 TenantContext 过滤。 */
@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PostMapping
    public SubscriptionView create(@RequestBody SubscriptionRequest request) {
        return subscriptionService.create(request);
    }

    @GetMapping
    public List<SubscriptionView> list() {
        return subscriptionService.list();
    }

    @DeleteMapping("/{id}")
    public SubscriptionView disable(@PathVariable long id) {
        return subscriptionService.disable(id);
    }
}
