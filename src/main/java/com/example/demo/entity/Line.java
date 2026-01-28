package com.example.demo.entity;

import lombok.Data;
import java.util.List;

@Data
public class Line {
    // 生产线名称（固定）
    private String name;
    // 生产线功能列表，例如 "Cutting", "Assembly" 等
    private List<String> functions;

    public Line() {
    }

    public Line(String name, List<String> functions) {
        this.name = name;
        this.functions = functions;
    }
}
