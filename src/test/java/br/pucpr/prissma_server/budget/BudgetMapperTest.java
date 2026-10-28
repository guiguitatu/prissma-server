package br.pucpr.prissma_server.budget;

import br.pucpr.prissma_server.projects.ConstructionProject;
import br.pucpr.prissma_server.stage.Stage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BudgetMapperTest {

    private static void id(Object target, long value) {
        try {
            Field field = target.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void mapsBudgetItemAndExpenseWithCalculatedTotals() {
        ConstructionProject project = new ConstructionProject();
        project.setId(1L);
        ProjectBudgetRequest budgetRequest = new ProjectBudgetRequest("Reforma", new BigDecimal("1000.00"));
        ProjectBudget budget = BudgetMapper.toEntity(budgetRequest, project);
        id(budget, 2L);
        budget.setCreatedAt(Instant.now());
        budget.setUpdatedAt(Instant.now());

        BudgetItemRequest itemRequest = new BudgetItemRequest("Material", "Cimento", new BigDecimal("300.00"));
        BudgetItem item = BudgetMapper.toEntity(itemRequest, budget);
        id(item, 3L);
        BudgetItemResponse itemResponse = BudgetMapper.toResponse(item, new BigDecimal("350.00"));
        assertTrue(itemResponse.isExceeded());
        assertEquals(new BigDecimal("-50.00"), itemResponse.getRemaining());

        Stage stage = new Stage();
        stage.setId(4L);
        ExpenseRequest expenseRequest = new ExpenseRequest();
        expenseRequest.setStageId(4L);
        expenseRequest.setDescription("Nota");
        expenseRequest.setAmount(new BigDecimal("20.00"));
        expenseRequest.setSupplier("Loja");
        expenseRequest.setReceiptUrl("url");
        expenseRequest.setSpentAt(LocalDate.of(2026, 1, 2));
        Expense expense = BudgetMapper.toEntity(expenseRequest, item, stage);
        id(expense, 5L);
        expense.setCreatedAt(Instant.now());
        ExpenseResponse expenseResponse = BudgetMapper.toResponse(expense, true, false);
        assertEquals(4L, expenseResponse.getStageId());
        assertTrue(expenseResponse.isCategoryExceeded());

        ProjectBudgetResponse budgetResponse = BudgetMapper.toResponse(budget, List.of(itemResponse), new BigDecimal("200.00"));
        assertEquals(new BigDecimal("800.00"), budgetResponse.getRemaining());
        assertFalse(budgetResponse.isExceeded());
    }

    @Test
    void responseBeansRetainAllMutableValues() {
        ExpenseResponse expense = new ExpenseResponse();
        expense.setId(1L); expense.setBudgetItemId(2L); expense.setStageId(3L);
        expense.setDescription("d"); expense.setAmount(BigDecimal.ONE); expense.setSupplier("s");
        expense.setReceiptUrl("r"); expense.setSpentAt(LocalDate.now()); expense.setCreatedAt(Instant.now());
        expense.setCategoryExceeded(true); expense.setBudgetExceeded(true);
        assertEquals(1L, expense.getId());
        assertEquals(2L, expense.getBudgetItemId());
        assertEquals(3L, expense.getStageId());
        assertEquals("d", expense.getDescription());
        assertEquals(BigDecimal.ONE, expense.getAmount());
        assertEquals("s", expense.getSupplier());
        assertEquals("r", expense.getReceiptUrl());
        assertTrue(expense.isCategoryExceeded() && expense.isBudgetExceeded());
    }
}
