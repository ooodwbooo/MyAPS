package com.example.demo.constraint;

import com.example.demo.entity.Order;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;

import java.time.temporal.ChronoUnit;

public class ShiftScheduleConstraintProvider implements ConstraintProvider {

  @Override
  public Constraint[] defineConstraints(ConstraintFactory constraintFactory) {
    return new Constraint[] {
        // 硬约束
        lineFunctionMatch(constraintFactory),
        employeeSkillMatch(constraintFactory),
        orderWithinWindow(constraintFactory),
        uniqueLinePerShift(constraintFactory),
        uniqueEmployeePerShift(constraintFactory),
        // 中约束（用较大权重的软约束来表达）
        minimizeOvertime(constraintFactory),
        // 软约束
        finishEarly(constraintFactory),
        balanceOrdersPerEmployee(constraintFactory),
        balanceOrdersPerLine(constraintFactory)
    };
  }

  // 生产线功能必须满足订单需求
  private Constraint lineFunctionMatch(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Order.class)
        .filter(o -> o.getLine() != null && o.getRequiredLineFunction() != null
            && o.getLine().getFunctions() != null
            && !o.getLine().getFunctions().contains(o.getRequiredLineFunction()))
        .penalize(HardSoftScore.ONE_HARD)
        .asConstraint("Line function must match order requirement");
  }

  // 员工技能必须满足订单需求
  private Constraint employeeSkillMatch(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Order.class)
        .filter(o -> o.getEmployee() != null && o.getRequiredSkill() != null
            && (o.getEmployee().getSkills() == null || !o.getEmployee().getSkills().contains(o.getRequiredSkill())))
        .penalize(HardSoftScore.ONE_HARD)
        .asConstraint("Employee must have required skill");
  }

  // 订单的生产时间必须在最早生产日期和最晚生产日期之间
  private Constraint orderWithinWindow(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Order.class)
        .filter(o -> o.getScheduledDateTime() != null && (o.getEarliestDate() != null && o.getLatestDate() != null)
            && (o.getScheduledDateTime().toLocalDate().isBefore(o.getEarliestDate())
                || o.getScheduledDateTime().toLocalDate().isAfter(o.getLatestDate())))
        .penalize(HardSoftScore.ONE_HARD)
        .asConstraint("Order must be scheduled within its allowed window");
  }

  // 同一时间段内，同一生产线只能被一个订单使用（基于 scheduledDateTime + workHours 的时间区间重叠检测）
  private Constraint uniqueLinePerShift(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Order.class)
        .join(Order.class, Joiners.equal(Order::getLine))
        .filter((o1, o2) -> {
          if (o1 == o2)
            return false;
          if (o1.getScheduledDateTime() == null || o2.getScheduledDateTime() == null)
            return false;
          if (o1.getLine() == null)
            return false;
          java.time.LocalDateTime s1 = o1.getScheduledDateTime();
          java.time.LocalDateTime s2 = o2.getScheduledDateTime();
          // enforce deterministic ordering to avoid double-counting: only check when
          // o1.start <= o2.start
          if (s1.isAfter(s2))
            return false;
          java.time.LocalDateTime e1 = s1.plusMinutes(Math.max(1, o1.getWorkHours()));
          java.time.LocalDateTime e2 = s2.plusMinutes(Math.max(1, o2.getWorkHours()));
          // overlap if s1 < e2 && s2 < e1 (this also catches equal starts)
          return s1.isBefore(e2) && s2.isBefore(e1);
        })
        .penalize(HardSoftScore.ONE_HARD)
        .asConstraint("Only one order per line per overlapping time");
  }

  // 同一时间段内，同一员工只能被分配给一个订单（基于 scheduledDateTime + workHours 的时间区间重叠检测）
  private Constraint uniqueEmployeePerShift(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Order.class)
        .join(Order.class, Joiners.equal(Order::getEmployee))
        .filter((o1, o2) -> {
          if (o1 == o2)
            return false;
          if (o1.getScheduledDateTime() == null || o2.getScheduledDateTime() == null)
            return false;
          if (o1.getEmployee() == null)
            return false;
          java.time.LocalDateTime s1 = o1.getScheduledDateTime();
          java.time.LocalDateTime s2 = o2.getScheduledDateTime();
          // enforce deterministic ordering to avoid double-counting: only check when
          // o1.start <= o2.start
          if (s1.isAfter(s2))
            return false;
          java.time.LocalDateTime e1 = s1.plusMinutes(Math.max(1, o1.getWorkHours()));
          java.time.LocalDateTime e2 = s2.plusMinutes(Math.max(1, o2.getWorkHours()));
          // overlap if s1 < e2 && s2 < e1
          return s1.isBefore(e2) && s2.isBefore(e1);
        })
        .penalize(HardSoftScore.ONE_HARD)
        .asConstraint("Only one order per employee per overlapping time");
  }

  // 中约束：尽量不加班（员工所属班次与订单班次不一致则视为加班）
  // 用较高权重的软约束表达（Timefold 支持 hard/soft 两层，我们把中约束映射为软约束权重较高）
  private Constraint minimizeOvertime(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Order.class)
        .filter(o -> o.getEmployee() != null && o.getScheduledDateTime() != null && o.getEmployee().getShift() != null)
        .filter(o -> {
          try {
            java.time.LocalDateTime orderStart = o.getScheduledDateTime();
            java.time.LocalDateTime orderEnd = orderStart.plusMinutes(Math.max(1, o.getWorkHours()));

            java.time.LocalTime shiftStartTime = o.getEmployee().getShift().getStart().toLocalTime();
            java.time.LocalTime shiftEndTime = o.getEmployee().getShift().getEnd().toLocalTime();
            if (shiftStartTime.equals(shiftEndTime)) {
              return false; // full-day shift -> not overtime
            }

            // Build a candidate shift window anchored to a given date
            java.util.function.Function<java.time.LocalDate, java.time.LocalDateTime[]> shiftWindowForDate = (date) -> {
              java.time.LocalDateTime s = java.time.LocalDateTime.of(date, shiftStartTime);
              java.time.LocalDateTime e = java.time.LocalDateTime.of(date, shiftEndTime);
              if (!e.isAfter(s)) {
                e = e.plusDays(1);
              }
              return new java.time.LocalDateTime[] { s, e };
            };

            // Check shift window anchored on order start date, previous date, and order end
            // date
            java.time.LocalDate dStart = orderStart.toLocalDate();
            java.time.LocalDate dPrev = dStart.minusDays(1);
            java.time.LocalDate dEnd = orderEnd.toLocalDate();

            java.time.LocalDateTime[] w1 = shiftWindowForDate.apply(dStart);
            java.time.LocalDateTime[] w2 = shiftWindowForDate.apply(dPrev);
            java.time.LocalDateTime[] w3 = shiftWindowForDate.apply(dEnd);

            boolean inside = (!orderStart.isBefore(w1[0]) && !orderEnd.isAfter(w1[1]))
                || (!orderStart.isBefore(w2[0]) && !orderEnd.isAfter(w2[1]))
                || (!orderStart.isBefore(w3[0]) && !orderEnd.isAfter(w3[1]));

            return !inside; // if not fully inside any shift window -> overtime
          } catch (Exception ex) {
            return false;
          }
        })
        .penalize(HardSoftScore.ofSoft(200))
        .asConstraint("Minimize overtime (scheduled outside employee's shift window)");
  }

  // 软约束：订单尽早完成（尽量把订单安排在更早的班次）
  private Constraint finishEarly(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Order.class)
        .filter(o -> o.getScheduledDateTime() != null && o.getEarliestDate() != null)
        .penalize(HardSoftScore.ONE_SOFT, o -> (int) Math.max(0,
            ChronoUnit.DAYS.between(o.getEarliestDate(), o.getScheduledDateTime().toLocalDate())))
        .asConstraint("Finish orders as early as possible");
  }

  // 软约束：尽量均衡分配订单给员工（相同员工之间的订单对会被惩罚）
  private Constraint balanceOrdersPerEmployee(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Order.class)
        .join(Order.class, Joiners.equal(Order::getEmployee))
        .filter((o1, o2) -> o1 != o2 && o1.getEmployee() != null)
        .penalize(HardSoftScore.ONE_SOFT)
        .asConstraint("Balance orders across employees");
  }

  // 软约束：尽量均衡分配订单给生产线
  private Constraint balanceOrdersPerLine(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Order.class)
        .join(Order.class, Joiners.equal(Order::getLine))
        .filter((o1, o2) -> o1 != o2 && o1.getLine() != null)
        .penalize(HardSoftScore.ONE_SOFT)
        .asConstraint("Balance orders across lines");
  }

}
