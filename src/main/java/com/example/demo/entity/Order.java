package com.example.demo.entity;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@PlanningEntity
public class Order {
    private String productName;
    private int quantity;
    // 以分钟为单位的预计工时（已从小时改为分钟）
    private int workHours;
    private LocalDate earliestDate;
    private LocalDate latestDate;
    // 需求员工技能
    private String requiredSkill;
    // 需求生产线功能
    private String requiredLineFunction;

    // 动态规划字段：分配的员工、生产线和规划生产日期时间（可能为 null）
    @PlanningVariable(valueRangeProviderRefs = {"employees"})
    private Employee employee;

    @PlanningVariable(valueRangeProviderRefs = {"lines"})
    private Line line;

    @PlanningVariable(valueRangeProviderRefs = {"dateTimes"})
    private LocalDateTime scheduledDateTime;

    private boolean pinned = false;

    @PlanningPin
    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public Order() {
    }

    public Order(String productName, int quantity, int workHours, LocalDate earliestDate, LocalDate latestDate,
                 String requiredSkill, String requiredLineFunction) {
        this.productName = productName;
        this.quantity = quantity;
        this.workHours = workHours;
        this.earliestDate = earliestDate;
        this.latestDate = latestDate;
        this.requiredSkill = requiredSkill;
        this.requiredLineFunction = requiredLineFunction;
    }
}
