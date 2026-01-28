package com.example.demo.entity;
import lombok.Data;
import java.util.Set;

@Data
public class Employee {

    private String name;
    private Set<String> skills;
    // 员工所属班次（固定引用）
    private Shift shift;

    public Employee(String name, Set<String> skills) {
        this.name = name;
        this.skills = skills;
        this.shift = null;
    }

    // Default constructor required for Jackson deserialization
    public Employee() {
    }

    public Employee(String name, Set<String> skills, Shift shift) {
        this.name = name;
        this.skills = skills;
        this.shift = shift;
    }

    @Override
    public String toString() {
        return name;
    }

}
