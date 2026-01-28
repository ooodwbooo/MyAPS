package com.example.demo.entity;

import java.util.List;
import java.time.LocalDateTime;

import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolverStatus;
import lombok.Data;

@Data
@PlanningSolution
public class OrderSchedule {
    @PlanningId
    private String id;

    private SolverStatus solverStatus;

    @ValueRangeProvider(id = "employees")
    private List<Employee> employees;

    @ValueRangeProvider(id = "lines")
    private List<Line> lines;

    @ValueRangeProvider(id = "dateTimes")
    private List<LocalDateTime> dateTimes;

    @PlanningEntityCollectionProperty
    private List<Order> orders;

    @PlanningScore
    private HardSoftScore score;

    public OrderSchedule() {
    }

    public OrderSchedule(List<Employee> employees) {
        this.employees = employees;
    }

    public OrderSchedule(List<Employee> employees, List<Line> lines, List<LocalDateTime> dateTimes, List<Order> orders) {
        this.employees = employees;
        this.lines = lines;
        this.dateTimes = dateTimes;
        this.orders = orders;
    }
}
