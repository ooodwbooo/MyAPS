package com.example.demo.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class Shift {

    private LocalDateTime start;
    private LocalDateTime end;
    // 班次名称（固定）
    private String name;

    public Shift() {
    }

    public Shift(LocalDateTime start, LocalDateTime end, String name) {
        this(start, end, name, null);
    }

    public Shift(LocalDateTime start, LocalDateTime end, String name, String unused) {
        this.start = start;
        this.end = end;
        this.name = name;
    }

    @Override
    public String toString() {
        return start + " - " + end;
    }

}
