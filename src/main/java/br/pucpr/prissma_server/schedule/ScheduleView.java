package br.pucpr.prissma_server.schedule;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/** Modo da tela: semana de segunda a domingo ou mês inteiro. */
public enum ScheduleView {
    WEEK,
    MONTH;

    public LocalDate startOf(LocalDate reference) {
        return this == WEEK
                ? reference.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                : reference.withDayOfMonth(1);
    }

    public LocalDate endOf(LocalDate reference) {
        return this == WEEK
                ? reference.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                : reference.with(TemporalAdjusters.lastDayOfMonth());
    }

    /** Data de referência do período anterior, para o front navegar sem calcular datas. */
    public LocalDate previousOf(LocalDate startDate) {
        return this == WEEK ? startDate.minusWeeks(1) : startDate.minusMonths(1);
    }

    /** Data de referência do próximo período. */
    public LocalDate nextOf(LocalDate startDate) {
        return this == WEEK ? startDate.plusWeeks(1) : startDate.plusMonths(1);
    }

    public static ScheduleView fromString(String value) {
        if (value == null || value.isBlank()) {
            return WEEK;
        }
        try {
            return ScheduleView.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "View must be WEEK or MONTH");
        }
    }
}
