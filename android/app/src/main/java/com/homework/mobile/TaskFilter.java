package com.homework.mobile;

import java.time.Instant;
import java.util.Comparator;

enum TaskFilter {
    UPCOMING("未截止", "已同步任务中暂无未截止任务"),
    ALL("全部", "暂无已扫描任务"),
    UNKNOWN("未知截止", "暂无未知截止任务"),
    COMPLETED("已完成", "暂无已完成任务");

    static final Comparator<Store.Task> ORDER = Comparator
            .comparing((Store.Task task) -> task.dueInstant() == null)
            .thenComparing(Store.byDeadline());

    private final String label;
    private final String emptyMessage;

    TaskFilter(String label, String emptyMessage) {
        this.label = label;
        this.emptyMessage = emptyMessage;
    }

    String label() {
        return label;
    }

    String emptyMessage() {
        return emptyMessage;
    }

    boolean includes(boolean upcoming, boolean completed, Instant due) {
        return switch (this) {
            case UPCOMING -> upcoming;
            case ALL -> true;
            case UNKNOWN -> due == null && !completed;
            case COMPLETED -> completed;
        };
    }

    static boolean isUpcoming(boolean completed, Instant due, Instant now) {
        return due != null && due.isAfter(now) && !completed;
    }
}
