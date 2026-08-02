package com.billy65536.chunkscanner.core.navigation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.MinecraftClient;

/**
 * 导航目标队列。
 *
 * <p>维护一个有序的导航目标列表，每个目标绑定一个到达判定条件。
 * 调用者通过 {@link #tick(MinecraftClient)} 推进队列，当当前目标的
 * 条件满足时自动弹出并返回 {@code true}（表示需要更新导航目标）。</p>
 *
 * <p>此包不引用任何 Baritone 类型，职责边界清晰。</p>
 */
public final class NavigationQueue {

    private final Deque<NavigationEntry> entries = new ArrayDeque<>();
    private final Map<NavigationEntry, NavigationCondition> conditionalEntries = new LinkedHashMap<>();

    /**
     * 将目标及其到达条件入队。
     */
    public void enqueue(NavigationEntry entry, NavigationCondition condition) {
        entries.addLast(entry);
        conditionalEntries.put(entry, condition);
    }

    /**
     * 查看队首目标（不移除）。
     */
    public NavigationEntry peek() {
        return entries.peekFirst();
    }

    /**
     * 获取队首目标对应的到达条件。
     */
    public NavigationCondition peekCondition() {
        NavigationEntry e = peek();
        return e != null ? conditionalEntries.get(e) : null;
    }

    /**
     * 每 tick 调用，检查当前目标是否到达。
     * 若到达则弹出并返回 {@code true}，表示导航目标已变更。
     *
     * @return {@code true} 如果当前目标被弹出
     */
    public boolean tick(MinecraftClient client) {
        NavigationEntry current = entries.peekFirst();
        if (current == null) return false;
        NavigationCondition cond = conditionalEntries.get(current);
        if (cond != null && cond.isSatisfied(client)) {
            entries.pollFirst();
            conditionalEntries.remove(current);
            return true;
        }
        return false;
    }

    /**
     * 获取所有队列条目的不可变快照（用于 GoalComposite 构建或 UI 渲染）。
     */
    public List<NavigationEntry> getEntries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /**
     * 清空队列。
     */
    public void clear() {
        entries.clear();
        conditionalEntries.clear();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }
}
