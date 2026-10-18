package com.shipora.git.application.port.in;

import com.shipora.git.application.port.out.GitStore.WebhookDelivery;

public interface WebhookUseCases {
    boolean receive(WebhookDelivery delivery);
}
