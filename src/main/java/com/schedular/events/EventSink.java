package com.schedular.events;

import com.schedular.storage.Event;

/**
 * 事件投递出口：EventService 持久化事件后调用；#7 票替换为订阅匹配与推送。
 */
public interface EventSink {

    void publish(Event event);
}
