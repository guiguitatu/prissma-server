package br.pucpr.prissma_server.users;

import br.pucpr.prissma_server.notifications.NotificationResponse;

import java.util.List;

public record UserResponse(Long id,
                           String name,
                           String email,
                           Role role,
                           List<NotificationResponse> notifications) {

    public static UserResponse from(User user) {
        return from(user, List.of());
    }

    public static UserResponse from(User user, List<NotificationResponse> notifications) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                notifications == null ? List.of() : List.copyOf(notifications));
    }
}