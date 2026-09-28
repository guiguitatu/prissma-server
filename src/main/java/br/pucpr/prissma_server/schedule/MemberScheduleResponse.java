package br.pucpr.prissma_server.schedule;

import java.math.BigDecimal;
import java.util.List;

public record MemberScheduleResponse(
        Long userId,
        String userName,
        String roleInProject,
        String userResponsibility,
        BigDecimal totalAllocatedHours,
        boolean hasOverlap,
        List<DayScheduleResponse> days
) {
}
