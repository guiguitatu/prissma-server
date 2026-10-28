package br.pucpr.prissma_server.budget;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping
@Tag(name = "Budget", description = "Project budgets, budget items and expense management.")
@SecurityRequirement(name = "bearerAuth")
public class BudgetController {

    private final BudgetService service;

    public BudgetController(BudgetService service) {
        this.service = service;
    }

    private Long resolveUserId(Authentication auth) {
        Object principal = auth.getPrincipal();
        if (principal instanceof Long userId) {
            return userId;
        }
        if (principal instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(auth.getName());
    }

    @PostMapping("/projects/{projectId}/budget")
    @Operation(summary = "Create a project budget", description = "Creates the budget associated with a construction project.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Budget created"),
            @ApiResponse(responseCode = "400", description = "Invalid budget data or project already has a budget"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to manage the project budget"),
            @ApiResponse(responseCode = "404", description = "Project not found")
    })
    public ResponseEntity<ProjectBudgetResponse> createBudget(
            @PathVariable Long projectId,
            @Valid @RequestBody ProjectBudgetRequest request,
            Authentication auth) {
        ProjectBudgetResponse response = service.createBudget(projectId, request, resolveUserId(auth));
        return ResponseEntity.created(URI.create("/budgets/" + response.getId())).body(response);
    }

    @GetMapping("/projects/{projectId}/budget")
    @Operation(summary = "Get a project's budget", description = "Returns the budget associated with a construction project.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Budget returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the project budget"),
            @ApiResponse(responseCode = "404", description = "Project or budget not found")
    })
    public ResponseEntity<ProjectBudgetResponse> getProjectBudget(
            @PathVariable Long projectId,
            Authentication auth) {
        return ResponseEntity.ok(service.getByProject(projectId, resolveUserId(auth)));
    }

    @GetMapping("/budgets/{id}")
    @Operation(summary = "Get a budget", description = "Returns a budget by its identifier.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Budget returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the budget"),
            @ApiResponse(responseCode = "404", description = "Budget not found")
    })
    public ResponseEntity<ProjectBudgetResponse> getBudget(
            @PathVariable Long id,
            Authentication auth) {
        return ResponseEntity.ok(service.get(id, resolveUserId(auth)));
    }

    @PatchMapping("/budgets/{id}")
    @Operation(summary = "Update a budget", description = "Partially updates an existing project budget.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Budget updated"),
            @ApiResponse(responseCode = "400", description = "Invalid budget data"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to manage the budget"),
            @ApiResponse(responseCode = "404", description = "Budget not found")
    })
    public ResponseEntity<ProjectBudgetResponse> updateBudget(
            @PathVariable Long id,
            @RequestBody ProjectBudgetRequest request,
            Authentication auth) {
        return ResponseEntity.ok(service.updateBudget(id, request, resolveUserId(auth)));
    }

    @DeleteMapping("/budgets/{id}")
    @Operation(summary = "Delete a budget", description = "Deletes a project budget and its associated budget data according to service rules.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Budget deleted"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to manage the budget"),
            @ApiResponse(responseCode = "404", description = "Budget not found")
    })
    public ResponseEntity<Void> deleteBudget(
            @PathVariable Long id,
            Authentication auth) {
        service.deleteBudget(id, resolveUserId(auth));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/budgets/{budgetId}/items")
    @Operation(summary = "Create a budget item", description = "Adds an item to an existing project budget.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Budget item created"),
            @ApiResponse(responseCode = "400", description = "Invalid budget item data"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to manage the budget"),
            @ApiResponse(responseCode = "404", description = "Budget not found")
    })
    public ResponseEntity<BudgetItemResponse> createItem(
            @PathVariable Long budgetId,
            @Valid @RequestBody BudgetItemRequest request,
            Authentication auth) {
        BudgetItemResponse response = service.createItem(budgetId, request, resolveUserId(auth));
        return ResponseEntity.created(URI.create("/budget-items/" + response.getId())).body(response);
    }

    @GetMapping("/budgets/{budgetId}/items")
    @Operation(summary = "List budget items", description = "Returns all items belonging to a budget.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Budget items returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the budget"),
            @ApiResponse(responseCode = "404", description = "Budget not found")
    })
    public ResponseEntity<List<BudgetItemResponse>> listItems(
            @PathVariable Long budgetId,
            Authentication auth) {
        return ResponseEntity.ok(service.listItems(budgetId, resolveUserId(auth)));
    }

    @GetMapping("/budget-items/{id}")
    @Operation(summary = "Get a budget item", description = "Returns one budget item by its identifier.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Budget item returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the budget item"),
            @ApiResponse(responseCode = "404", description = "Budget item not found")
    })
    public ResponseEntity<BudgetItemResponse> getItem(
            @PathVariable Long id,
            Authentication auth) {
        return ResponseEntity.ok(service.getItem(id, resolveUserId(auth)));
    }

    @PatchMapping("/budget-items/{id}")
    @Operation(summary = "Update a budget item", description = "Partially updates an item in a project budget.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Budget item updated"),
            @ApiResponse(responseCode = "400", description = "Invalid budget item data"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to manage the budget"),
            @ApiResponse(responseCode = "404", description = "Budget item not found")
    })
    public ResponseEntity<BudgetItemResponse> updateItem(
            @PathVariable Long id,
            @RequestBody BudgetItemRequest request,
            Authentication auth) {
        return ResponseEntity.ok(service.updateItem(id, request, resolveUserId(auth)));
    }

    @DeleteMapping("/budget-items/{id}")
    @Operation(summary = "Delete a budget item", description = "Deletes an item from a project budget.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Budget item deleted"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to manage the budget"),
            @ApiResponse(responseCode = "404", description = "Budget item not found")
    })
    public ResponseEntity<Void> deleteItem(
            @PathVariable Long id,
            Authentication auth) {
        service.deleteItem(id, resolveUserId(auth));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/budget-items/{itemId}/expenses")
    @Operation(summary = "Create an expense", description = "Registers an expense against a budget item.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Expense created"),
            @ApiResponse(responseCode = "400", description = "Invalid expense data"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to manage the budget"),
            @ApiResponse(responseCode = "404", description = "Budget item not found")
    })
    public ResponseEntity<ExpenseResponse> createExpense(
            @PathVariable Long itemId,
            @Valid @RequestBody ExpenseRequest request,
            Authentication auth) {
        ExpenseResponse response = service.createExpense(itemId, request, resolveUserId(auth));
        return ResponseEntity.created(URI.create("/expenses/" + response.getId())).body(response);
    }

    @GetMapping("/budget-items/{itemId}/expenses")
    @Operation(summary = "List expenses for a budget item", description = "Returns all expenses registered for one budget item.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Expenses returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the budget"),
            @ApiResponse(responseCode = "404", description = "Budget item not found")
    })
    public ResponseEntity<List<ExpenseResponse>> listExpensesByItem(
            @PathVariable Long itemId,
            Authentication auth) {
        return ResponseEntity.ok(service.listExpensesByItem(itemId, resolveUserId(auth)));
    }

    @GetMapping("/budgets/{budgetId}/expenses")
    @Operation(summary = "List expenses for a budget", description = "Returns all expenses across the items of a project budget.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Expenses returned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User cannot access the budget"),
            @ApiResponse(responseCode = "404", description = "Budget not found")
    })
    public ResponseEntity<List<ExpenseResponse>> listExpensesByBudget(
            @PathVariable Long budgetId,
            Authentication auth) {
        return ResponseEntity.ok(service.listExpensesByBudget(budgetId, resolveUserId(auth)));
    }

    @PatchMapping("/expenses/{id}")
    @Operation(summary = "Update an expense", description = "Partially updates a registered expense.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Expense updated"),
            @ApiResponse(responseCode = "400", description = "Invalid expense data"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to manage the budget"),
            @ApiResponse(responseCode = "404", description = "Expense not found")
    })
    public ResponseEntity<ExpenseResponse> updateExpense(
            @PathVariable Long id,
            @RequestBody ExpenseRequest request,
            Authentication auth) {
        return ResponseEntity.ok(service.updateExpense(id, request, resolveUserId(auth)));
    }

    @DeleteMapping("/expenses/{id}")
    @Operation(summary = "Delete an expense", description = "Deletes a registered expense.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Expense deleted"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not allowed to manage the budget"),
            @ApiResponse(responseCode = "404", description = "Expense not found")
    })
    public ResponseEntity<Void> deleteExpense(
            @PathVariable Long id,
            Authentication auth) {
        service.deleteExpense(id, resolveUserId(auth));
        return ResponseEntity.noContent().build();
    }
}
