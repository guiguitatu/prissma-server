package br.pucpr.prissma_server.budget;

import br.pucpr.prissma_server.projects.ConstructionProject;
import br.pucpr.prissma_server.projects.ConstructionProjectRepository;
import br.pucpr.prissma_server.projects.ProjectPermission;
import br.pucpr.prissma_server.projects.ProjectPermissionService;
import br.pucpr.prissma_server.stage.Stage;
import br.pucpr.prissma_server.stage.StageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BudgetServiceTest {
    @Mock private ProjectBudgetRepository budgetRepository;
    @Mock private BudgetItemRepository itemRepository;
    @Mock private ExpenseRepository expenseRepository;
    @Mock private ConstructionProjectRepository projectRepository;
    @Mock private StageRepository stageRepository;
    @Mock private ProjectPermissionService permissionService;
    @InjectMocks private BudgetService service;

    private ConstructionProject project;
    private ProjectBudget budget;
    private BudgetItem item;
    private Expense expense;

    private static void id(Object target, long value) {
        try {
            Field field = target.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    @BeforeEach
    void setUp() {
        project = new ConstructionProject(); project.setId(1L);
        budget = new ProjectBudget(); id(budget, 2L); budget.setConstructionProject(project);
        budget.setDescription("Obra"); budget.setPlannedTotal(new BigDecimal("1000"));
        item = new BudgetItem(); id(item, 3L); item.setProjectBudget(budget);
        item.setCategory("Material"); item.setDescription("Cimento"); item.setPlannedAmount(new BigDecimal("300"));
        expense = new Expense(); id(expense, 4L); expense.setBudgetItem(item);
        expense.setDescription("Compra"); expense.setAmount(new BigDecimal("50")); expense.setSpentAt(LocalDate.of(2026, 1, 1));
        when(budgetRepository.findById(2L)).thenReturn(Optional.of(budget));
        when(itemRepository.findById(3L)).thenReturn(Optional.of(item));
        when(expenseRepository.findById(4L)).thenReturn(Optional.of(expense));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(itemRepository.findByProjectBudgetIdOrderByIdAsc(2L)).thenReturn(List.of(item));
        when(expenseRepository.sumAmountByBudgetItemId(3L)).thenReturn(BigDecimal.ZERO);
        when(expenseRepository.sumAmountByProjectBudgetId(2L)).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void managesBudgetAndItemsThroughTheirLifecycle() {
        when(budgetRepository.existsByConstructionProjectId(1L)).thenReturn(false);
        when(budgetRepository.save(any(ProjectBudget.class))).thenAnswer(i -> i.getArgument(0));
        ProjectBudgetRequest budgetRequest = new ProjectBudgetRequest("Nova", new BigDecimal("1000"));
        assertEquals(new BigDecimal("1000"), service.createBudget(1L, budgetRequest, 9L).getPlannedTotal());

        when(budgetRepository.findByConstructionProjectId(1L)).thenReturn(Optional.of(budget));
        assertNotNull(service.getByProject(1L, 9L));
        assertNotNull(service.get(2L, 9L));
        ProjectBudgetRequest patch = new ProjectBudgetRequest("Atualizada", new BigDecimal("1200"));
        assertEquals("Atualizada", service.updateBudget(2L, patch, 9L).getDescription());
        service.deleteBudget(2L, 9L);

        when(itemRepository.save(any(BudgetItem.class))).thenAnswer(i -> { id(i.getArgument(0), 3L); return i.getArgument(0); });
        BudgetItemRequest itemRequest = new BudgetItemRequest("Mão de obra", "Pedreiro", new BigDecimal("200"));
        assertNotNull(service.createItem(2L, itemRequest, 9L));
        assertEquals(1, service.listItems(2L, 9L).size());
        assertNotNull(service.getItem(3L, 9L));
        assertNotNull(service.updateItem(3L, itemRequest, 9L));
        service.deleteItem(3L, 9L);
        verify(permissionService, atLeastOnce()).requirePermission(1L, 9L, ProjectPermission.MANAGE_BUDGET);
    }

    @Test
    void managesExpensesAndCalculatesExceededFlags() {
        Stage stage = new Stage(); stage.setId(5L); stage.setConstructionProject(project);
        when(stageRepository.findById(5L)).thenReturn(Optional.of(stage));
        when(expenseRepository.save(any(Expense.class))).thenAnswer(i -> { id(i.getArgument(0), 4L); return i.getArgument(0); });
        when(expenseRepository.sumAmountByBudgetItemId(3L)).thenReturn(new BigDecimal("350"));
        when(expenseRepository.sumAmountByProjectBudgetId(2L)).thenReturn(new BigDecimal("1100"));
        ExpenseRequest request = new ExpenseRequest();
        request.setStageId(5L); request.setDescription("Areia"); request.setAmount(new BigDecimal("50"));
        request.setSupplier("Loja"); request.setReceiptUrl("nota"); request.setSpentAt(LocalDate.of(2026, 1, 2));

        assertTrue(service.createExpense(3L, request, 9L).isBudgetExceeded());
        when(expenseRepository.findByBudgetItemIdOrderBySpentAtDesc(3L)).thenReturn(List.of(expense));
        when(expenseRepository.findByProjectBudgetId(2L)).thenReturn(List.of(expense));
        assertEquals(1, service.listExpensesByItem(3L, 9L).size());
        assertEquals(1, service.listExpensesByBudget(2L, 9L).size());
        assertEquals("Areia", service.updateExpense(4L, request, 9L).getDescription());
        service.deleteExpense(4L, 9L);
        verify(expenseRepository).delete(expense);
    }

    @Test
    void returnsMeaningfulErrorsForMissingResourcesAndForeignStage() {
        when(budgetRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.get(99L, 9L));
        when(itemRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.getItem(99L, 9L));
        when(expenseRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.updateExpense(99L, new ExpenseRequest(), 9L));

        Stage other = new Stage(); other.setId(6L);
        ConstructionProject otherProject = new ConstructionProject(); otherProject.setId(99L);
        other.setConstructionProject(otherProject);
        when(stageRepository.findById(6L)).thenReturn(Optional.of(other));
        ExpenseRequest request = new ExpenseRequest(); request.setStageId(6L);
        assertThrows(ResponseStatusException.class, () -> service.createExpense(3L, request, 9L));
    }
}
