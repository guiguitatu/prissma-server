package br.pucpr.prissma_server.schedule;

import java.time.LocalDate;
import java.util.List;

public record TeamScheduleResponse(
        Long constructionProjectId,
        ScheduleView view,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate previousDate,
        LocalDate nextDate,
        List<LocalDate> days,
        List<MemberScheduleResponse> members
) {
}
