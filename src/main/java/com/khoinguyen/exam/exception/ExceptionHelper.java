package com.khoinguyen.exam.exception;

public class ExceptionHelper {

    public static String getTrace(Throwable ex) {
        StackTraceElement[] st = ex.getStackTrace();
        if (st != null && st.length > 0) {
            StackTraceElement e = st[0];
            return e.getClassName() + ":" + e.getLineNumber();
            // hoặc:
            // return String.format("%s:%d", e.getClassName(), e.getLineNumber());
        }
        return null;
    }
}

