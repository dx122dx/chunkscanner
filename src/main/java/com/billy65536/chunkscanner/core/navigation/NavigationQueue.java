package com.billy65536.chunkscanner.core.navigation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import net.minecraft.client.MinecraftClient;

/**
 * 导航目标队列。
 *
 * <p>维护一个有序的导航目标列表，每个目标绑定一个到达判定条件。
 * 调用者通过 {@link #tick(MinecraftClient)} 推进队列，当当前目标的
 * 条件满足时自动弹出并返回 {@code true}（表示需要更新导航目标）。</p>
 *
 * <p>条目与条件成对存放在同一个节点中，因此<b>允许重复坐标入队</b>：
 * 相同坐标的多个目标各自持有独立条件，互不覆盖。</p>
 *
 * <p>此包不引用任何 Baritone 类型，职责边界清晰。</p>
 */
public final class NavigationQueue {

    /** 队列节点：目标 + 其到达条件。条件允许为 {@code null}（表示永不自动弹出）。 */
    private record Node(NavigationEntry entry, NavigationCondition condition) {}

    private final Deque<Node> nodes = new ArrayDeque<>();

    /**
     * 将目标及其到达条件入队。
     *
     * <p>重复坐标可安全入队，不会覆盖先前条目的条件。</p>
     */
    public void enqueue(NavigationEntry entry, NavigationCondition condition) {
        nodes.addLast(new Node(entry, condition));
    }

    /**
     * 查看队首目标（不移除）。
     */
    public NavigationEntry peek() {
        Node head = nodes.peekFirst();
        return head != null ? head.entry() : null;
    }

    /**
     * 获取队首目标对应的到达条件。
     */
    public NavigationCondition peekCondition() {
        Node head = nodes.peekFirst();
        return head != null ? head.condition() : null;
    }

    /**
     * 每 tick 调用，检查当前目标是否到达。
     * 若到达则弹出并返回 {@code true}，表示导航目标已变更。
     *
     * @return {@code true} 如果当前目标被弹出
     */
    public boolean tick(MinecraftClient client) {
        Node head = nodes.peekFirst();
        if (head == null) return false;
        NavigationCondition cond = head.condition();
        if (cond != null && cond.isSatisfied(client)) {
            nodes.pollFirst();
            return true;
        }
        return false;
    }

    /**
     * 获取所有队列条目的不可变快照（用于 GoalComposite 构建或 UI 渲染）。
     */
    public List<NavigationEntry> getEntries() {
        List<NavigationEntry> snapshot = new ArrayList<>(nodes.size());
        for (Node n : nodes) {
            snapshot.add(n.entry());
        }
        return Collections.unmodifiableList(snapshot);
    }

    /**
     * 清空队列。
     */
    public void clear() {
        nodes.clear();
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public int size() {
        return nodes.size();
    }
}
