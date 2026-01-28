package com.example.demo.controller;

import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.solver.ScoreAnalysisFetchPolicy;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverConfigOverride;
import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.api.solver.SolverStatus;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;

import com.example.demo.entity.Employee;
import com.example.demo.entity.Shift;
import com.example.demo.entity.OrderSchedule;
import com.example.demo.entity.Order;
import com.example.demo.entity.Line;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@RestController
@RequestMapping("/schedules")
public class SolverController {

    private static final int MAX_JOBS_CACHE_SIZE = 2;

    private final SolverManager<OrderSchedule, String> solverManager;
    private final SolutionManager<OrderSchedule, HardSoftScore> solutionManager;
    private final ConcurrentMap<String, Job> jobIdToJob = new ConcurrentHashMap<>();

    // @Autowired
    public SolverController(SolverManager<OrderSchedule, String> solverManager,
            SolutionManager<OrderSchedule, HardSoftScore> solutionManager) {
        this.solverManager = solverManager;
        this.solutionManager = solutionManager;
    }

    // --- 列出所有 jobId ---
    @GetMapping("/list")
    public Collection<String> list() {
        return jobIdToJob.keySet();
    }

    // --- 启动求解（POST）---
    @PostMapping("/solve")
    public String solve(@RequestBody(required = false) OrderSchedule problem) {
        // 如果前端不传 problem，则使用默认问题
        OrderSchedule inputProblem = (problem != null) ? problem : createDefaultProblem();

        String jobId = UUID.randomUUID().toString();
        jobIdToJob.put(jobId, Job.ofSchedule(inputProblem));

        SolverConfigOverride<OrderSchedule> withTerminationConfig = new SolverConfigOverride<OrderSchedule>()
                .withTerminationConfig(new TerminationConfig()
                        .withSpentLimit(Duration.ofSeconds(60))
                        .withBestScoreLimit("0hard/10soft"));

        solverManager.solveBuilder()
                .withProblemId(jobId)
                .withProblemFinder(id -> jobIdToJob.get(id).schedule())
                .withFirstInitializedSolutionEventConsumer(event -> {
                    log.info("+++++++++ First +++++++++");
                    log.info("Solving First. First score: {}", event.solution().getScore());
                    log.info("=========================");
                })
                .withBestSolutionEventConsumer(event -> {
                    log.info("+++++++++ Best +++++++++");
                    jobIdToJob.put(jobId, Job.ofSchedule(event.solution()));
                    log.info("Found better solution: {}", event.solution().getScore());
                    printSolution(event.solution());
                    log.info("=========================");
                })
                .withFinalBestSolutionEventConsumer(event -> {
                    log.info("+++++++++ Final +++++++++");
                    log.info("Solving finished. Final score: {}", event.solution().getScore());
                    printSolution(event.solution());
                    log.info("=========================");
                })
                .withExceptionHandler((id, exception) -> {
                    log.error("Failed solving jobId: {}", id, exception);
                    jobIdToJob.put(id, Job.ofException(exception));
                })
                .withConfigOverride(withTerminationConfig)
                .run();

        cleanJobs();
        return jobId;
    }

    // --- 获取当前方案（含中间结果）---
    @GetMapping(path = "{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public OrderSchedule getSchedule(@PathVariable("jobId") String jobId) {
        OrderSchedule schedule = getScheduleAndCheckForExceptions(jobId);
        SolverStatus solverStatus = solverManager.getSolverStatus(jobId);
        schedule.setSolverStatus(solverStatus); // 假设 ShiftSchedule 有 setSolverStatus
        return schedule;
    }

    // --- 仅获取状态 ---
    @GetMapping(path = "{jobId}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public OrderSchedule getStatus(@PathVariable("jobId") String jobId) {
        OrderSchedule schedule = getScheduleAndCheckForExceptions(jobId);
        SolverStatus solverStatus = solverManager.getSolverStatus(jobId);
        // 返回一个轻量对象（只含 score 和 status）
        OrderSchedule statusOnly = new OrderSchedule();
        statusOnly.setScore(schedule.getScore());
        statusOnly.setSolverStatus(solverStatus);
        return statusOnly;
    }

    // --- 终止求解 ---
    @DeleteMapping(path = "{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public OrderSchedule terminateSolving(@PathVariable("jobId") String jobId) {
        solverManager.terminateEarly(jobId);
        return getSchedule(jobId);
    }

    @PutMapping(path = "analyze", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScoreAnalysis<HardSoftScore> analyze(@RequestBody OrderSchedule problem,
            @RequestParam(required = false) ScoreAnalysisFetchPolicy fetchPolicy) {
        return fetchPolicy == null ? solutionManager.analyze(problem) : solutionManager.analyze(problem, fetchPolicy);
    }

    // --- 辅助方法 ---

    private OrderSchedule getScheduleAndCheckForExceptions(String jobId) {
        Job job = jobIdToJob.get(jobId);
        if (job == null) {
            throw new RuntimeException("Job not found: " + jobId); // 可封装为自定义异常
        }
        if (job.exception() != null) {
            throw new RuntimeException("Job failed: " + job.exception().getMessage(), job.exception());
        }
        return job.schedule();
    }

    private void cleanJobs() {
        if (jobIdToJob.size() <= MAX_JOBS_CACHE_SIZE) {
            return;
        }
        // 找出已完成的任务（NOT_SOLVING）并按时间排序
        var completedJobs = jobIdToJob.entrySet().stream()
                .filter(entry -> {
                    SolverStatus status = solverManager.getSolverStatus(entry.getKey());
                    return status == SolverStatus.NOT_SOLVING;
                })
                .sorted((e1, e2) -> e1.getValue().createdAt().compareTo(e2.getValue().createdAt()))
                .toList();

        int toRemove = completedJobs.size() - MAX_JOBS_CACHE_SIZE;
        if (toRemove > 0) {
            for (int i = 0; i < toRemove; i++) {
                String jobId = completedJobs.get(i).getKey();
                jobIdToJob.remove(jobId);
                log.debug("Cleaned up old job: {}", jobId);
            }
        }
    }

    // --- 默认问题（用于测试）---
    private OrderSchedule createDefaultProblem() {
        // 构建示例数据：固定班次、员工（绑定班次）、生产线、订单（规划的字段为 null）
        LocalDate day1 = LocalDate.of(2030, 4, 1);
        LocalDate day2 = LocalDate.of(2030, 4, 2);
        LocalDate day3 = LocalDate.of(2030, 4, 3);

        // 三班倒：早班 / 中班 / 夜班（夜班跨到次日）
        Shift s1 = new Shift(day1.atTime(6, 0), day1.atTime(14, 0), "Morning");
        Shift s2 = new Shift(day1.atTime(14, 0), day1.atTime(22, 0), "Evening");
        Shift s3 = new Shift(day1.atTime(22, 0), day2.atTime(6, 0), "Night");

        // List<Shift> shifts = List.of(s1, s2, s3);

        // 员工绑定到三班（每班两人作为示例）
        Employee e1 = new Employee("Ann", Set.of("Assembly", "Welding"), s1);
        Employee e2 = new Employee("Bob", Set.of("Assembly", "Cutting"), s1);
        Employee e3 = new Employee("Carl", Set.of("Assembly"), s2);
        Employee e4 = new Employee("Dana", Set.of("Assembly", "Welding"), s2);
        Employee e5 = new Employee("Eve", Set.of("Assembly", "Welding", "Cutting"), s3);
        Employee e6 = new Employee("Fay", Set.of("Assembly"), s3);

        List<Employee> employees = List.of(e1, e2, e3, e4, e5, e6);

        // 多条生产线，不同功能
        Line line1 = new Line("L1", List.of("Cutting", "Assembly", "Welding"));
        Line line2 = new Line("L2", List.of("Cutting", "Assembly", "Welding"));
        Line line3 = new Line("L3", List.of("Cutting", "Assembly", "Welding"));
        List<Line> lines = List.of(line1, line2, line3);

        // dateTimes 值域：从 day1 到 day3（含）按 TimeGrain 生成每个 15 分钟时间槽（与班次无关）
        List<java.time.LocalDateTime> dateTimes = new java.util.ArrayList<>();
        java.time.LocalDateTime slot = day1.atStartOfDay();
        java.time.LocalDateTime endSlot = day3.atTime(23, 45); // 最后一个 15 分钟槽为 23:45
        while (!slot.isAfter(endSlot)) {
            dateTimes.add(slot);
            slot = slot.plusMinutes(com.example.demo.entity.TimeGrain.GRAIN_LENGTH_IN_MINUTES);
        }

        // 扩充示例订单：workHours 以分钟为单位
        Order o1 = new Order("Widget-A", 100, 120, day1, day2, "Welding", "Cutting"); // 2 小时
        Order o2 = new Order("Widget-B", 50, 60, day1, day3, "Assembly", "Assembly"); // 1 小时
        Order o3 = new Order("Widget-C", 80, 30, day1, day2, "Welding", "Welding"); // 0.5 小时
        Order o4 = new Order("Widget-D", 20, 45, day2, day3, "Cutting", "Cutting");
        Order o5 = new Order("Widget-E", 200, 180, day1, day3, "Welding", "Welding"); // 3 小时
        Order o6 = new Order("Widget-F", 10, 15, day2, day2, "Assembly", "Assembly");
        Order o7 = new Order("Widget-G", 60, 90, day3, day3, "Cutting", "Cutting");
        Order o8 = new Order("Widget-H", 40, 30, day1, day3, "Assembly", "Assembly");
        Order o9 = new Order("Widget-I", 25, 20, day2, day3, "Welding", "Cutting");
        Order o10 = new Order("Widget-J", 15, 10, day1, day1, "Assembly", "Assembly");
        Order o11 = new Order("Widget-K", 70, 60, day3, day3, "Welding", "Welding");
        Order o12 = new Order("Widget-L", 5, 15, day1, day3, "Cutting", "Cutting");

        List<Order> ordersTmp = List.of(o1, o2, o3, o4, o5, o6, o7, o8, o9, o10, o11, o12);
        List<Order> orders = new ArrayList<>();
        orders.addAll(ordersTmp);

        for (int i = 0; i < 70; i++) {
            orders.add(new Order("Order-" + (i + 1), 10, 45 + (i % 5) * 15,
                    day1.plusDays(i % 3), day3, "Assembly", "Assembly"));
        }

        OrderSchedule problem = new OrderSchedule(employees, lines, dateTimes, orders);
        return problem;
    }

    // --- 内部记录类 ---
    private record Job(OrderSchedule schedule, LocalDateTime createdAt, Throwable exception) {
        static Job ofSchedule(OrderSchedule schedule) {
            return new Job(schedule, LocalDateTime.now(), null);
        }

        static Job ofException(Throwable error) {
            return new Job(null, LocalDateTime.now(), error);
        }
    }

    private void printSolution(OrderSchedule solution) {
        log.info("订单分配结果");
        if (solution.getOrders() == null) {
            return;
        }
        for (Order order : solution.getOrders()) {
            String emp = order.getEmployee() != null ? order.getEmployee().getName() : "<unassigned>";
            String line = order.getLine() != null ? order.getLine().getName() : "<unassigned>";
            String dt = order.getScheduledDateTime() != null ? order.getScheduledDateTime().toString() : "<unassigned>";
            log.info("  {} -> {} @ {} on {}", order.getProductName(), emp, line, dt);
        }
    }
}