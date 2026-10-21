package br.pucpr.prissma_server;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifica o contrato dos modelos simples que transportam o estado da API. */
class ModelAccessorsTest {

    private static final List<String> MODELS = List.of(
            "br.pucpr.prissma_server.users.UserAddress",
            "br.pucpr.prissma_server.users.User",
            "br.pucpr.prissma_server.auth.PasswordResetToken",
            "br.pucpr.prissma_server.notifications.Notification",
            "br.pucpr.prissma_server.attachments.Attachment",
            "br.pucpr.prissma_server.projects.ConstructionProject",
            "br.pucpr.prissma_server.projects.ConstructionProjectMember",
            "br.pucpr.prissma_server.stage.Stage",
            "br.pucpr.prissma_server.stage.StageRequest",
            "br.pucpr.prissma_server.stage.StageResponse",
            "br.pucpr.prissma_server.task.Task",
            "br.pucpr.prissma_server.task.TaskResponse",
            "br.pucpr.prissma_server.budget.ProjectBudget",
            "br.pucpr.prissma_server.budget.BudgetItem",
            "br.pucpr.prissma_server.budget.Expense",
            "br.pucpr.prissma_server.budget.ProjectBudgetResponse",
            "br.pucpr.prissma_server.budget.BudgetItemResponse",
            "br.pucpr.prissma_server.budget.ExpenseResponse",
            "br.pucpr.prissma_server.diary.DiaryEntry",
            "br.pucpr.prissma_server.schedule.ScheduleAllocation",
            "br.pucpr.prissma_server.schedule.ScheduleMember",
            "br.pucpr.prissma_server.workspaces.Workspace",
            "br.pucpr.prissma_server.workspaces.WorkspaceMember",
            "br.pucpr.prissma_server.workspaces.MemberInvite",
            "br.pucpr.prissma_server.design.DesignProposal",
            "br.pucpr.prissma_server.design.DesignSubmission",
            "br.pucpr.prissma_server.design.DesignApproval",
            "br.pucpr.prissma_server.design.EnvironmentPreview"
    );

    @Test
    void mutableModelsExposeConsistentPublicAccessors() throws Exception {
        for (String name : MODELS) {
            Class<?> type = Class.forName(name);
            Object instance = type.getDeclaredConstructor().newInstance();
            for (Method setter : type.getMethods()) {
                if (!setter.getName().startsWith("set") || setter.getParameterCount() != 1) continue;
                Object value = valueFor(setter.getParameterTypes()[0]);
                setter.invoke(instance, value);
                String suffix = setter.getName().substring(3);
                Method getter = findGetter(type, suffix);
                if (getter != null) assertEquals(value, getter.invoke(instance), name + ": " + suffix);
            }
        }
    }

    private Method findGetter(Class<?> type, String suffix) {
        try { return type.getMethod("get" + suffix); }
        catch (NoSuchMethodException ignored) {
            try { return type.getMethod("is" + suffix); }
            catch (NoSuchMethodException absent) { return null; }
        }
    }

    private Object valueFor(Class<?> type) {
        if (type == String.class) return "value";
        if (type == Long.class || type == long.class) return 1L;
        if (type == Integer.class || type == int.class) return 1;
        if (type == Boolean.class || type == boolean.class) return true;
        if (type == BigDecimal.class) return BigDecimal.ONE;
        if (type == LocalDate.class) return LocalDate.of(2026, 1, 1);
        if (type == Instant.class) return Instant.parse("2026-01-01T00:00:00Z");
        if (List.class.isAssignableFrom(type)) return List.of();
        if (Map.class.isAssignableFrom(type)) return Map.of();
        if (type.isEnum()) return type.getEnumConstants()[0];
        try { return type.getDeclaredConstructor().newInstance(); }
        catch (ReflectiveOperationException ignored) { return null; }
    }
}
