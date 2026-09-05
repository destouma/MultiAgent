package com.multiagent.desktop.llm;

/** Mirrors shared/types.ts AppErrorCode. */
public enum ErrorCode {
    SERVER_UNREACHABLE,
    MODEL_NOT_LOADED,
    CONTEXT_EXCEEDED,
    UNSUPPORTED,
    CANCELLED,
    UNKNOWN
}
