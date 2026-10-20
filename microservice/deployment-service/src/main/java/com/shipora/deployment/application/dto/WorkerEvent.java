package com.shipora.deployment.application.dto;

public record WorkerEvent(String level,String message) {
    @Override public String toString(){return "WorkerEvent[redacted]";}
}
