package br.pucpr.prissma_server.schedule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DayScheduleResponse(
        LocalDate date,
        BigDecimal allocatedHours,
        boolean allocated,
        boolean overlapped,
        List<ScheduledTaskResponse> tasks
) {
}
