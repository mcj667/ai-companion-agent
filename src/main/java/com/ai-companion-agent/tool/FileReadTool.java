package com.example.aiagent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class FileReadTool {

    @Tool(description = "读取指定的 TXT 文件内容，用于分析用户提供的聊天记录或文本资料")
    public String readFile(String filePath) {

        try {
            Path path = Path.of(filePath);

            if (!Files.isRegularFile(path)) {
                return "读取失败：文件不存在或不是普通文件";
            }

            if (!path.getFileName().toString()
                    .toLowerCase()
                    .endsWith(".txt")) {
                return "读取失败：目前只支持 TXT 文件";
            }

            long fileSize = Files.size(path);

            if (fileSize > 1024 * 1024) {
                return "读取失败：文件不能超过 1 MB";
            }

            return Files.readString(path, StandardCharsets.UTF_8);

        } catch (IOException | SecurityException e) {
            return "读取文件失败，请检查文件是否存在及访问权限";
        } catch (IllegalArgumentException e) {
            return "读取失败：文件路径无效";
        }
    }
}