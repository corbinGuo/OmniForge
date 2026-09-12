package com.omniforge.tools.python;

/**
 * Python 执行结果。
 *
 * @param status     执行状态
 * @param output     标准输出内容
 * @param error      标准错误/失败原因
 * @param durationMs 执行耗时（毫秒）
 */
public record PythonResult(Status status, String output, String error, long durationMs) {

    /** 执行状态 */
    public enum Status {
        /** 正常完成 */
        SUCCESS,
        /** 执行超时（引擎尽力终止） */
        TIMEOUT,
        /** 执行异常 */
        ERROR
    }

    public PythonResult {
        output = output == null ? "" : output;
        error = error == null ? "" : error;
    }

    public static PythonResult success(String output, long durationMs) {
        return new PythonResult(Status.SUCCESS, output, "", durationMs);
    }

    public static PythonResult timeout(String error, long durationMs) {
        return new PythonResult(Status.TIMEOUT, "", error == null ? "python execution timed out" : error, durationMs);
    }

    public static PythonResult failure(String error, long durationMs) {
        return new PythonResult(Status.ERROR, "", error, durationMs);
    }
}
