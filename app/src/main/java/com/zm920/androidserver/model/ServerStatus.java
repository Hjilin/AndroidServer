package com.zm920.androidserver.model;

public enum ServerStatus {
    STOPPED("已停止"),
    RUNNING("运行中"),
    ERROR("错误"),
    STARTING("启动中");

    private final String label;

    ServerStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
