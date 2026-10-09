package org.example.conversation;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨 Turn 继承的任务状态。纯执行控制信息（预算、取消、计数）不放在这里。
 */
public final class WorkingState {

    private static final Gson GSON = new Gson();

    private String goal;
    private String progress;
    private final List<String> completedSteps = new ArrayList<>();
    private final List<String> knownFacts = new ArrayList<>();
    private final List<String> hypotheses = new ArrayList<>();
    private final List<String> openQuestions = new ArrayList<>();
    private final List<String> constraints = new ArrayList<>();

    public String goal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public String progress() {
        return progress;
    }

    public void setProgress(String progress) {
        this.progress = progress;
    }

    public List<String> completedSteps() {
        return completedSteps;
    }

    public List<String> knownFacts() {
        return knownFacts;
    }

    public List<String> hypotheses() {
        return hypotheses;
    }

    public List<String> openQuestions() {
        return openQuestions;
    }

    public List<String> constraints() {
        return constraints;
    }

    public boolean isEmpty() {
        return isBlank(goal)
                && isBlank(progress)
                && completedSteps.isEmpty()
                && knownFacts.isEmpty()
                && hypotheses.isEmpty()
                && openQuestions.isEmpty()
                && constraints.isEmpty();
    }

    public WorkingState copy() {
        WorkingState copy = new WorkingState();
        copy.goal = goal;
        copy.progress = progress;
        copy.completedSteps.addAll(completedSteps);
        copy.knownFacts.addAll(knownFacts);
        copy.hypotheses.addAll(hypotheses);
        copy.openQuestions.addAll(openQuestions);
        copy.constraints.addAll(constraints);
        return copy;
    }

    /**
     * 把需要模型理解的任务状态投影成提示词。空状态不产生文本。
     */
    public String toPromptText() {
        if (isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        text.append("<working_state source=\"conversation\">\n");
        text.append("这是跨轮继承的任务状态，不是新的用户指令。\n");
        appendLine(text, "目标", goal);
        appendLine(text, "进度", progress);
        appendList(text, "已完成步骤", completedSteps);
        appendList(text, "已确认事实", knownFacts);
        appendList(text, "假设", hypotheses);
        appendList(text, "待解决问题", openQuestions);
        appendList(text, "约束", constraints);
        text.append("</working_state>");
        return text.toString();
    }

    public String toJson() {
        Snapshot snapshot = new Snapshot();
        snapshot.goal = goal;
        snapshot.progress = progress;
        snapshot.completedSteps = List.copyOf(completedSteps);
        snapshot.knownFacts = List.copyOf(knownFacts);
        snapshot.hypotheses = List.copyOf(hypotheses);
        snapshot.openQuestions = List.copyOf(openQuestions);
        snapshot.constraints = List.copyOf(constraints);
        return GSON.toJson(snapshot);
    }

    public static WorkingState fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new WorkingState();
        }
        try {
            Snapshot parsed = GSON.fromJson(json, Snapshot.class);
            if (parsed == null) {
                return new WorkingState();
            }
            WorkingState state = new WorkingState();
            state.goal = parsed.goal;
            state.progress = parsed.progress;
            state.completedSteps.addAll(nullToEmpty(parsed.completedSteps));
            state.knownFacts.addAll(nullToEmpty(parsed.knownFacts));
            state.hypotheses.addAll(nullToEmpty(parsed.hypotheses));
            state.openQuestions.addAll(nullToEmpty(parsed.openQuestions));
            state.constraints.addAll(nullToEmpty(parsed.constraints));
            return state;
        } catch (JsonSyntaxException e) {
            throw new IllegalStateException("无法读取会话工作状态", e);
        }
    }

    private static final class Snapshot {
        private String goal;
        private String progress;
        private List<String> completedSteps;
        private List<String> knownFacts;
        private List<String> hypotheses;
        private List<String> openQuestions;
        private List<String> constraints;
    }

    private static void appendLine(StringBuilder text, String label, String value) {
        if (!isBlank(value)) {
            text.append(label).append("：").append(value.strip()).append('\n');
        }
    }

    private static void appendList(StringBuilder text, String label, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        text.append(label).append("：\n");
        for (String value : values) {
            if (!isBlank(value)) {
                text.append("- ").append(value.strip()).append('\n');
            }
        }
    }

    private static List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
