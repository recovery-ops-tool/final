package com.recoverpro.server.lucien.tool;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Discovers all LucienTool beans; routes by stable tool name. */
@Component
public class ToolRegistry {

    private final Map<String, LucienTool> tools;

    public ToolRegistry(List<LucienTool> toolList) {
        this.tools = toolList.stream()
                .collect(Collectors.toMap(LucienTool::name, Function.identity()));
    }

    public Optional<LucienTool> resolve(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Collection<LucienTool> all() {
        return tools.values();
    }

    /** Builds the tool schema block injected into the system prompt each turn. */
    public String buildSchemaBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== AVAILABLE TOOLS ===\n");
        sb.append("Most messages (greetings, general questions, advice) need NO tool at all — ")
          .append("just answer directly in plain text with no <tool_call> block.\n");
        sb.append("Only emit a tool call when you need information or an action this list actually provides.\n");
        sb.append("To call a tool, output a <tool_call> block in this format at the end of your message (you may still output regular conversational text/responses before the block):\n");
        sb.append("<tool_call>\n{\"name\": \"tool_name\", \"args\": {\"key\": \"value\"}}\n</tool_call>\n\n");
        sb.append("Example — user says \"Hi\": respond in plain text, e.g. ")
          .append("\"Hi! How can I help with your cases today?\" — do not call a tool.\n\n");
        sb.append("TOOLS:\n[\n");
        boolean first = true;
        for (LucienTool tool : tools.values()) {
            if (!first) sb.append(",\n");
            sb.append("  {\n");
            sb.append("    \"name\": \"").append(tool.name()).append("\",\n");
            sb.append("    \"description\": \"").append(tool.description().replace("\"", "'")).append("\",\n");
            sb.append("    \"write\": ").append(tool.isWriteOperation()).append(",\n");
            sb.append("    \"parameters\": ").append(tool.parametersSchema()).append("\n");
            sb.append("  }");
            first = false;
        }
        sb.append("\n]\n=== END TOOLS ===\n");
        return sb.toString();
    }
}
