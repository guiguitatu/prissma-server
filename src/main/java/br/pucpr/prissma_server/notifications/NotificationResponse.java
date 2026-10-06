package br.pucpr.prissma_server.notifications;

import java.time.Instant;

public record NotificationResponse(Long id,
                                   NotificationType type,
                                   String title,
                                   String message,
                                   boolean read,
                                   Instant createdAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getMessage(),
                notification.isRead(),
                notification.getCreatedAt());
    }
}

