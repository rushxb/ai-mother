package com.rush.rushaicodemother.service.dependency;

import java.util.Objects;

/** 项目依赖安装结果。 */
public record DependencyInstallResult(Status status, String output, String errorDetail) {

    public DependencyInstallResult {
        Objects.requireNonNull(status, "安装状态不能为空");
        output = output == null ? "" : output;
        if (status == Status.SUCCESS) {
            errorDetail = null;
        } else if (errorDetail == null || errorDetail.isBlank()) {
            errorDetail = "依赖安装失败";
        }
    }

    public static DependencyInstallResult success(String output) {
        return new DependencyInstallResult(Status.SUCCESS, output, null);
    }

    public static DependencyInstallResult failed(Status status, String output, String errorDetail) {
        if (status == Status.SUCCESS) {
            throw new IllegalArgumentException("失败结果不能使用 SUCCESS 状态");
        }
        return new DependencyInstallResult(status, output, errorDetail);
    }

    public boolean success() {
        return status == Status.SUCCESS;
    }

    public boolean terminal() {
        return status == Status.CANCELLED || status == Status.INTERRUPTED;
    }

    public enum Status {
        SUCCESS,
        FAILED,
        TIMED_OUT,
        IDLE_TIMED_OUT,
        CANCELLED,
        INTERRUPTED,
        INTEGRITY_FAILED,
        INVALID_PROJECT
    }
}
