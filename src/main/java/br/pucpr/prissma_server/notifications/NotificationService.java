package br.pucpr.prissma_server.notifications;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;


@Service
public class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void notifyUser(Long userId, NotificationType type, String title, String message) {
        if (userId == null) {
            return;
        }
        repository.save(build(userId, type, title, message, Instant.now()));
    }

    @Transactional
    public void notifyUsers(Collection<Long> userIds, NotificationType type, String title, String message) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        List<Notification> batch = new ArrayList<>();
        for (Long userId : new LinkedHashSet<>(userIds)) {
            if (userId != null) {
                batch.add(build(userId, type, title, message, now));
            }
        }
        if (!batch.isEmpty()) {
            repository.saveAll(batch);
        }
    }

    @Transactional
    public void notifyUsersExcept(Collection<Long> userIds,
                                  Long excludedUserId,
                                  NotificationType type,
                                  String title,
                                  String message) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        List<Long> recipients = userIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> !id.equals(excludedUserId))
                .toList();
        notifyUsers(recipients, type, title, message);
    }

    // ---------- leitura ----------

    @Transactional(readOnly = true)
    public List<NotificationResponse> listForUser(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User id is required");
        }
        return repository.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(NotificationResponse::from)
                .toList();
    }

    @Transactional
    public NotificationResponse markAsRead(Long notificationId, Long actorUserId) {
        if (actorUserId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User id is required");
        }
        Notification notification = repository.findByIdAndUserId(notificationId, actorUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found"));

        if (!notification.isRead()) {
            notification.setRead(true);
            repository.save(notification);
        }
        return NotificationResponse.from(notification);
    }

    private Notification build(Long userId, NotificationType type, String title, String message, Instant createdAt) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setRead(false);
        notification.setCreatedAt(createdAt);
        return notification;
    }
}

