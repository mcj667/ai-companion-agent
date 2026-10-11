package com.aicompanion.agent;

import java.util.List;

// Java Record，自动生成getter、toString
public record LoveReport(
        String title,
        List<String> suggestions
) {}