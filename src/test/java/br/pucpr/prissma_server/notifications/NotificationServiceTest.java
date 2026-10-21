package br.pucpr.prissma_server.notifications;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository repository;
    @InjectMocks private NotificationService service;

    @Test
    void notifyUserIgnoresNullAndPersistsCompleteNotification() {
        service.notifyUser(null, NotificationType.TASK_ASSIGNED, "x", "y");
        verifyNoInteractions(repository);

        service.notifyUser(7L, NotificationType.TASK_ASSIGNED, "Nova", "Mensagem");
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(captor.capture());
        assertEquals(7L, captor.getValue().getUserId());
        assertEquals(NotificationType.TASK_ASSIGNED, captor.getValue().getType());
        assertFalse(captor.getValue().isRead());
        assertNotNull(captor.getValue().getCreatedAt());
    }

    @Test
    void notifyUsersDeduplicatesRecipientsAndExcludesActor() {
        service.notifyUsers(List.of(1L, 1L, null, 2L), NotificationType.INVITE_ACCEPTED, "t", "m");
        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertEquals(List.of(1L, 2L), captor.getValue().stream().map(Notification::getUserId).toList());

        reset(repository);
        service.notifyUsersExcept(List.of(1L, 2L, 3L), 2L, NotificationType.INVITE_ACCEPTED, "t", "m");
        verify(repository).saveAll(any());
    }

    @Test
    void listAndMarkAsReadValidateOwnershipAndAreIdempotent() {
        Notification notification = new Notification();
        notification.setId(8L);
        notification.setUserId(4L);
        notification.setType(NotificationType.TASK_ASSIGNED);
        notification.setTitle("t");
        notification.setMessage("m");
        notification.setCreatedAt(Instant.now());
        when(repository.findByUserIdOrderByCreatedAtDescIdDesc(4L)).thenReturn(List.of(notification));
        when(repository.findByIdAndUserId(8L, 4L)).thenReturn(Optional.of(notification));

        assertEquals(1, service.listForUser(4L).size());
        assertTrue(service.markAsRead(8L, 4L).read());
        service.markAsRead(8L, 4L);
        verify(repository, times(1)).save(notification);

        ResponseStatusException missingUser = assertThrows(ResponseStatusException.class, () -> service.listForUser(null));
        assertEquals("User id is required", missingUser.getReason());
        when(repository.findByIdAndUserId(99L, 4L)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.markAsRead(99L, 4L));
    }
}
