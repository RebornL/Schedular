package com.schedular.events;

import com.schedular.storage.Event;
import com.schedular.storage.EventDelivery;
import com.schedular.storage.EventDeliveryRepository;
import com.schedular.storage.EventRepository;
import com.schedular.storage.Subscription;
import com.schedular.storage.SubscriptionRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DeliverySweeper：周期扫 event_deliveries 中 status='PENDING' 且 next_retry_at 到期的行，
 * 重新入队推送（覆盖进程重启后内存重试丢失的恢复场景，docs/design/04）。
 * 与内存重试幂等共存：EventPusher.attempt 投递前重查投递行，已非 PENDING 即跳过。
 */
@Component
public class DeliverySweeper {

    private static final Logger log = LoggerFactory.getLogger(DeliverySweeper.class);

    private final EventDeliveryRepository deliveryRepository;
    private final EventRepository eventRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final EventPusher pusher;
    private final Clock clock;

    public DeliverySweeper(EventDeliveryRepository deliveryRepository,
                           EventRepository eventRepository,
                           SubscriptionRepository subscriptionRepository,
                           EventPusher pusher, Clock clock) {
        this.deliveryRepository = deliveryRepository;
        this.eventRepository = eventRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.pusher = pusher;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${schedular.push.sweeper-interval:30s}")
    public void sweep() {
        try {
            String now = OffsetDateTime.now(clock).toString();
            for (EventDelivery delivery : deliveryRepository.findDuePending(now)) {
                requeue(delivery);
            }
        } catch (RuntimeException e) {
            log.warn("DeliverySweeper 扫描失败", e);
        }
    }

    private void requeue(EventDelivery delivery) {
        try {
            Event event = eventRepository.findById(delivery.eventId()).orElse(null);
            Subscription subscription = subscriptionRepository
                    .findById((long) delivery.subscriptionId()).orElse(null);
            if (event == null || subscription == null || !subscription.enabled()) {
                return;
            }
            pusher.attempt(subscription, event);
        } catch (RuntimeException e) {
            log.warn("投递重新入队失败: event_id={}, subscription_id={}",
                    delivery.eventId(), delivery.subscriptionId(), e);
        }
    }
}
