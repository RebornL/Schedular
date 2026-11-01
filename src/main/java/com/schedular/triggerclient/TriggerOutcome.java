package com.schedular.triggerclient;

/** 触发调用结果：ACK=受理回执有效；RESULT=同步结果；TIMEOUT=SYNC 执行超时终态；LINK_FAILURE=链路故障（可重试）。 */
public record TriggerOutcome(Type type, AgentExecutionResult result, String error) {

    public enum Type { ACK, RESULT, TIMEOUT, LINK_FAILURE }

    public static TriggerOutcome ack() {
        return new TriggerOutcome(Type.ACK, null, null);
    }

    public static TriggerOutcome result(AgentExecutionResult result) {
        return new TriggerOutcome(Type.RESULT, result, null);
    }

    public static TriggerOutcome timeout(String error) {
        return new TriggerOutcome(Type.TIMEOUT, null, error);
    }

    public static TriggerOutcome linkFailure(String error) {
        return new TriggerOutcome(Type.LINK_FAILURE, null, error);
    }
}
