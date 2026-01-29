package com.example.demo.constraint;

import com.example.demo.entity.Order;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;

import java.time.temporal.ChronoUnit;

/**
 * 排班约束提供者类，用于定义和实现各种排班相关的约束条件
 * 该类实现了ConstraintProvider接口，用于提供各种硬约束、中约束和软约束
 */
public class OrderScheduleConstraintProvider implements ConstraintProvider {

  @Override
  public Constraint[] defineConstraints(ConstraintFactory constraintFactory) {
    return new Constraint[] {
        // 硬约束：必须满足的基本约束条件
        lineFunctionMatch(constraintFactory),      // 生产线功能匹配约束
        employeeSkillMatch(constraintFactory),     // 员工技能匹配约束
        overtimeMustFollowShiftEnd(constraintFactory), // 加班时间约束
        orderWithinWindow(constraintFactory),     // 订单时间窗口约束
        uniqueLinePerShift(constraintFactory),     // 生产线唯一性约束
        uniqueEmployeePerShift(constraintFactory), // 员工唯一性约束
        // 中约束（用较大权重的软约束来表达）
        minimizeOvertime(constraintFactory),       // 最小化加班约束
        // 软约束：优化目标，可以适当违反
        finishEarly(constraintFactory),            // 尽早完成约束
        balanceOrdersPerEmployee(constraintFactory), // 均衡分配订单给员工
        minimizeLineSwitchingPerEmployee(constraintFactory), // 最小化生产线切换
        minimizeIdleTimePerShift(constraintFactory), // 最小化空闲时间
        balanceOrdersPerLine(constraintFactory)    // 均衡分配订单给生产线
    };
  }

  // 硬约束：如果订单被视为加班且发生在班次结束之后，则加班必须在班次结束后立刻开始
  /**
   * 创建一个约束，确保加班必须在班次结束后开始，并且班次结束后与加班开始之间最多允许5分钟的间隔
   * @param constraintFactory 约束工厂，用于创建约束
   * @return 配置好的约束，用于验证加班时间规则
   */
  private Constraint overtimeMustFollowShiftEnd(ConstraintFactory constraintFactory) {
    // 允许的班次结束与加班开始之间的最小间隔时间（分钟）
    final int allowedGapMin = 5;
    // 创建约束，遍历所有订单
    return constraintFactory.forEach(Order.class)
        // 过滤条件：订单必须有员工、计划日期时间，且员工的班次不能为空
        .filter(o -> o.getEmployee() != null && o.getScheduledDateTime() != null && o.getEmployee().getShift() != null)
        // 应用复杂的过滤逻辑，检查加班时间是否符合规则
        .filter(o -> {
          try {
            // 获取订单的开始时间和结束时间（工作小时数至少为1分钟）
            java.time.LocalDateTime orderStart = o.getScheduledDateTime();
            java.time.LocalDateTime orderEnd = orderStart.plusMinutes(Math.max(1, o.getWorkHours()));

            // 获取班次的开始和结束时间
            java.time.LocalTime shiftStartTime = o.getEmployee().getShift().getStart().toLocalTime();
            java.time.LocalTime shiftEndTime = o.getEmployee().getShift().getEnd().toLocalTime();
            // 如果班次开始和结束时间相同，表示全天班次，在此不限制加班
            if (shiftStartTime.equals(shiftEndTime)) {
              return false; // full-day shift -> no overtime restriction here
            }

            java.util.function.Function<java.time.LocalDate, java.time.LocalDateTime[]> shiftWindowForDate = (date) -> {
              java.time.LocalDateTime s = java.time.LocalDateTime.of(date, shiftStartTime);
              java.time.LocalDateTime e = java.time.LocalDateTime.of(date, shiftEndTime);
              if (!e.isAfter(s)) {
                e = e.plusDays(1);
              }
              return new java.time.LocalDateTime[] { s, e };
            };

            // Determine if this order is actually overtime (i.e. not fully inside any shift window)
            java.time.LocalDate dStart = orderStart.toLocalDate();
            java.time.LocalDate dPrev = dStart.minusDays(1);

            java.time.LocalDateTime[] wCur = shiftWindowForDate.apply(dStart);
            java.time.LocalDateTime[] wPrev = shiftWindowForDate.apply(dPrev);

            boolean insideCur = (!orderStart.isBefore(wCur[0]) && !orderEnd.isAfter(wCur[1]));
            boolean insidePrev = (!orderStart.isBefore(wPrev[0]) && !orderEnd.isAfter(wPrev[1]));
            if (insideCur || insidePrev) {
              return false; // not overtime
            }

            // Find the most recent shift window end that is <= orderStart (prefer current day's end if applicable)
            java.time.LocalDateTime candidateEnd = null;
            if (!wPrev[1].isAfter(orderStart)) {
              candidateEnd = wPrev[1];
            }
            if (!wCur[1].isAfter(orderStart) && (candidateEnd == null || wCur[1].isAfter(candidateEnd))) {
              candidateEnd = wCur[1];
            }
            if (candidateEnd == null) {
              return false; // order not after any known shift end -> don't count as violation here
            }

            java.time.LocalDateTime latestAllowed = candidateEnd.plusMinutes(allowedGapMin);
            // Violation when order starts strictly after latestAllowed
            return orderStart.isAfter(latestAllowed);
          } catch (Exception ex) {
            return false;
          }
        })
        .penalize(HardMediumSoftScore.ONE_HARD)
        .asConstraint("Overtime must start within 15 minutes after shift end");
  }

  // 硬约束：生产线功能必须满足订单需求
  private Constraint lineFunctionMatch(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Order.class)
        .filter(o -> o.getLine() != null && o.getRequiredLineFunction() != null
            && o.getLine().getFunctions() != null
            && !o.getLine().getFunctions().contains(o.getRequiredLineFunction()))
        .penalize(HardMediumSoftScore.ONE_HARD)
        .asConstraint("Line function must match order requirement");
  }

  // 硬约束：员工技能必须满足订单需求
  private Constraint employeeSkillMatch(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Order.class)
        .filter(o -> o.getEmployee() != null && o.getRequiredSkill() != null
            && (o.getEmployee().getSkills() == null || !o.getEmployee().getSkills().contains(o.getRequiredSkill())))
        .penalize(HardMediumSoftScore.ONE_HARD)
        .asConstraint("Employee must have required skill");
  }

  // 硬约束：订单的生产时间必须在最早生产日期和最晚生产日期之间
  private Constraint orderWithinWindow(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Order.class)
        .filter(o -> o.getScheduledDateTime() != null && (o.getEarliestDate() != null && o.getLatestDate() != null)
            && (o.getScheduledDateTime().toLocalDate().isBefore(o.getEarliestDate())
                || o.getScheduledDateTime().toLocalDate().isAfter(o.getLatestDate())))
        .penalize(HardMediumSoftScore.ONE_HARD)
        .asConstraint("Order must be scheduled within its allowed window");
  }

  // 硬约束：同一时间段内，同一生产线只能被一个订单使用（基于 scheduledDateTime + workHours 的时间区间重叠检测）
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
        .penalize(HardMediumSoftScore.ONE_HARD)
        .asConstraint("Only one order per line per overlapping time");
  }

  // 硬约束：同一时间段内，同一员工只能被分配给一个订单（基于 scheduledDateTime + workHours 的时间区间重叠检测）
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
        .penalize(HardMediumSoftScore.ONE_HARD)
        .asConstraint("Only one order per employee per overlapping time");
  }

  // 中约束：尽量不加班（员工所属班次与订单班次不一致则视为加班）
  private Constraint minimizeOvertime(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Order.class)
        .filter(o -> o.getEmployee() != null && o.getScheduledDateTime() != null && o.getEmployee().getShift() != null)
        .filter(o -> {
          // keep only orders that are at least partially outside any shift window
          try {
            java.time.LocalDateTime orderStart = o.getScheduledDateTime();
            java.time.LocalDateTime orderEnd = orderStart.plusMinutes(Math.max(1, o.getWorkHours()));

            java.time.LocalTime shiftStartTime = o.getEmployee().getShift().getStart().toLocalTime();
            java.time.LocalTime shiftEndTime = o.getEmployee().getShift().getEnd().toLocalTime();
            if (shiftStartTime.equals(shiftEndTime)) {
              return false; // full-day shift -> not overtime
            }

            java.util.function.Function<java.time.LocalDate, java.time.LocalDateTime[]> shiftWindowForDate = (date) -> {
              java.time.LocalDateTime s = java.time.LocalDateTime.of(date, shiftStartTime);
              java.time.LocalDateTime e = java.time.LocalDateTime.of(date, shiftEndTime);
              if (!e.isAfter(s)) {
                e = e.plusDays(1);
              }
              return new java.time.LocalDateTime[] { s, e };
            };

            java.time.LocalDate dStart = orderStart.toLocalDate();
            java.time.LocalDate dPrev = dStart.minusDays(1);
            java.time.LocalDate dEnd = orderEnd.toLocalDate();

            java.time.LocalDateTime[] w1 = shiftWindowForDate.apply(dStart);
            java.time.LocalDateTime[] w2 = shiftWindowForDate.apply(dPrev);
            java.time.LocalDateTime[] w3 = shiftWindowForDate.apply(dEnd);

            // compute total overlap minutes between order interval and any shift window
            long overlap = 0L;
            java.time.LocalDateTime[][] windows = new java.time.LocalDateTime[][] { w1, w2, w3 };
            for (java.time.LocalDateTime[] w : windows) {
              java.time.LocalDateTime a = orderStart.isAfter(w[0]) ? orderStart : w[0];
              java.time.LocalDateTime b = orderEnd.isBefore(w[1]) ? orderEnd : w[1];
              if (b.isAfter(a)) {
                overlap += java.time.temporal.ChronoUnit.MINUTES.between(a, b);
              }
            }
            long orderDuration = java.time.temporal.ChronoUnit.MINUTES.between(orderStart, orderEnd);
            long overtime = Math.max(0L, orderDuration - Math.min(orderDuration, overlap));
            return overtime > 0L;
          } catch (Exception ex) {
            return false;
          }
        })
        .penalize(HardMediumSoftScore.ONE_MEDIUM, o -> {
          try {
            java.time.LocalDateTime orderStart = o.getScheduledDateTime();
            java.time.LocalDateTime orderEnd = orderStart.plusMinutes(Math.max(1, o.getWorkHours()));
            java.time.LocalTime shiftStartTime = o.getEmployee().getShift().getStart().toLocalTime();
            java.time.LocalTime shiftEndTime = o.getEmployee().getShift().getEnd().toLocalTime();
            java.util.function.Function<java.time.LocalDate, java.time.LocalDateTime[]> shiftWindowForDate = (date) -> {
              java.time.LocalDateTime s = java.time.LocalDateTime.of(date, shiftStartTime);
              java.time.LocalDateTime e = java.time.LocalDateTime.of(date, shiftEndTime);
              if (!e.isAfter(s)) e = e.plusDays(1);
              return new java.time.LocalDateTime[] { s, e };
            };
            java.time.LocalDate dStart = orderStart.toLocalDate();
            java.time.LocalDate dPrev = dStart.minusDays(1);
            java.time.LocalDate dEnd = orderEnd.toLocalDate();
            java.time.LocalDateTime[] w1 = shiftWindowForDate.apply(dStart);
            java.time.LocalDateTime[] w2 = shiftWindowForDate.apply(dPrev);
            java.time.LocalDateTime[] w3 = shiftWindowForDate.apply(dEnd);
            long overlap = 0L;
            java.time.LocalDateTime[][] windows = new java.time.LocalDateTime[][] { w1, w2, w3 };
            for (java.time.LocalDateTime[] w : windows) {
              java.time.LocalDateTime a = orderStart.isAfter(w[0]) ? orderStart : w[0];
              java.time.LocalDateTime b = orderEnd.isBefore(w[1]) ? orderEnd : w[1];
              if (b.isAfter(a)) {
                overlap += java.time.temporal.ChronoUnit.MINUTES.between(a, b);
              }
            }
            long orderDuration = java.time.temporal.ChronoUnit.MINUTES.between(orderStart, orderEnd);
            long overtime = Math.max(0L, orderDuration - Math.min(orderDuration, overlap));
            return (int) Math.min(Integer.MAX_VALUE, overtime);
          } catch (Exception ex) {
            return 0;
          }
        })
        .asConstraint("Minimize overtime (minutes outside employee's shift window)");
  }

  // 软约束：订单尽早完成（尽量把订单安排在更早的班次）
  private Constraint finishEarly(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Order.class)
        .filter(o -> o.getScheduledDateTime() != null && o.getEarliestDate() != null)
        .penalize(HardMediumSoftScore.ofSoft(20), o -> (int) Math.max(0,
            ChronoUnit.DAYS.between(o.getEarliestDate(), o.getScheduledDateTime().toLocalDate())))
        .asConstraint("Finish orders as early as possible");
  }

  // 软约束：尽量均衡分配订单给员工（相同员工之间的订单对会被惩罚）
  private Constraint balanceOrdersPerEmployee(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Order.class)
        .join(Order.class, Joiners.equal(Order::getEmployee))
        .filter((o1, o2) -> o1 != o2 && o1.getEmployee() != null)
        .penalize(HardMediumSoftScore.ONE_SOFT)
        .asConstraint("Balance orders across employees");
  }

  // 软约束：同一员工在同一天尽量不要更换生产线（若当天使用了 N 条不同产线，则惩罚 N-1 次）
  private Constraint minimizeLineSwitchingPerEmployee(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Order.class)
        .filter(o -> o.getEmployee() != null && o.getScheduledDateTime() != null)
        .groupBy(o -> o.getEmployee(), o -> o.getScheduledDateTime().toLocalDate(),
            ConstraintCollectors.toSet(Order::getLine))
        .filter((employee, date, lineSet) -> lineSet != null && lineSet.size() > 1)
        .penalize(HardMediumSoftScore.ofSoft(50), (employee, date, lineSet) -> lineSet.size() - 1)
        .asConstraint("Minimize employee switching lines per day");
  }

  // 软约束：尽量均衡分配订单给生产线
  private Constraint balanceOrdersPerLine(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Order.class)
        .join(Order.class, Joiners.equal(Order::getLine))
        .filter((o1, o2) -> o1 != o2 && o1.getLine() != null)
        .penalize(HardMediumSoftScore.ONE_SOFT)
        .asConstraint("Balance orders across lines");
  }
  // 软约束：尽量减少员工在班次中的空闲时间（靠近班次开始的空闲会被加重惩罚）
  private Constraint minimizeIdleTimePerShift(ConstraintFactory constraintFactory) {
    return constraintFactory.forEach(Order.class)
        .filter(o -> o.getEmployee() != null && o.getScheduledDateTime() != null && o.getEmployee().getShift() != null)
        .groupBy(o -> o.getEmployee(), o -> o.getScheduledDateTime().toLocalDate(),
            ConstraintCollectors.toList(o -> o))
        .penalize(HardMediumSoftScore.ofSoft(5), (employee, date, orders) -> {
          try {
            java.time.LocalTime shiftStartTime = employee.getShift().getStart().toLocalTime();
            java.time.LocalTime shiftEndTime = employee.getShift().getEnd().toLocalTime();
            java.time.LocalDateTime shiftStart = java.time.LocalDateTime.of(date, shiftStartTime);
            java.time.LocalDateTime shiftEnd = java.time.LocalDateTime.of(date, shiftEndTime);
            if (!shiftEnd.isAfter(shiftStart)) shiftEnd = shiftEnd.plusDays(1);
            long shiftDurationMin = java.time.temporal.ChronoUnit.MINUTES.between(shiftStart, shiftEnd);
            if (shiftDurationMin <= 0) return 0;

            // build list of intervals within the shift that are occupied by orders
            java.util.List<java.time.LocalDateTime[]> occupied = new java.util.ArrayList<>();
            for (Order o : orders) {
              java.time.LocalDateTime oStart = o.getScheduledDateTime();
              java.time.LocalDateTime oEnd = oStart.plusMinutes(Math.max(1, o.getWorkHours()));
              // clip to shift window
              java.time.LocalDateTime a = oStart.isAfter(shiftStart) ? oStart : shiftStart;
              java.time.LocalDateTime b = oEnd.isBefore(shiftEnd) ? oEnd : shiftEnd;
              if (b.isAfter(a)) occupied.add(new java.time.LocalDateTime[] { a, b });
            }
            // merge occupied intervals
            occupied.sort((x, y) -> x[0].compareTo(y[0]));
            java.util.List<java.time.LocalDateTime[]> merged = new java.util.ArrayList<>();
            for (java.time.LocalDateTime[] iv : occupied) {
              if (merged.isEmpty()) {
                merged.add(iv);
              } else {
                java.time.LocalDateTime[] last = merged.get(merged.size() - 1);
                if (!iv[0].isAfter(last[1])) { // overlap or touch
                  if (iv[1].isAfter(last[1])) last[1] = iv[1];
                } else {
                  merged.add(iv);
                }
              }
            }

            // compute weighted idle: for each gap between shiftStart -> merged..., merged -> shiftEnd
            double weightedPenalty = 0.0;
            java.time.LocalDateTime prev = shiftStart;
            for (java.time.LocalDateTime[] iv : merged) {
              if (iv[0].isAfter(prev)) {
                long gapMin = java.time.temporal.ChronoUnit.MINUTES.between(prev, iv[0]);
                // midpoint position in shift [0..1]
                double midOffset = java.time.temporal.ChronoUnit.MINUTES.between(shiftStart, prev) + gapMin / 2.0;
                double pos = Math.max(0.0, Math.min(1.0, midOffset / (double) shiftDurationMin));
                double weight = 1.0 - pos; // closer to start => weight near 1; closer to end => near 0
                weightedPenalty += gapMin * weight;
              }
              // advance prev to end of occupied
              prev = iv[1].isAfter(prev) ? iv[1] : prev;
            }
            // final gap
            if (shiftEnd.isAfter(prev)) {
              long gapMin = java.time.temporal.ChronoUnit.MINUTES.between(prev, shiftEnd);
              double midOffset = java.time.temporal.ChronoUnit.MINUTES.between(shiftStart, prev) + gapMin / 2.0;
              double pos = Math.max(0.0, Math.min(1.0, midOffset / (double) shiftDurationMin));
              double weight = 1.0 - pos;
              weightedPenalty += gapMin * weight;
            }

            int pen = (int) Math.round(weightedPenalty);
            return Math.max(0, pen);
          } catch (Exception ex) {
            return 0;
          }
        })
        .asConstraint("Minimize idle minutes within employee shift (weighted toward shift start)");
  }
}
