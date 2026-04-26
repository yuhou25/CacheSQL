package com.browise.core.exception;

import java.lang.reflect.InvocationTargetException;

public class utilException extends Exception {
    private String message;
    private int errCode;

    /**
     * 构造带错误码的自定义异常
     * Construct a custom exception with error code
     */
    public utilException(String message, int errCode) {
        this.message = message;
        this.errCode = errCode;
    }

    /**
     * 从异常对象构造自定义异常，自动提取 InvocationTargetException 的目标异常信息
     * Construct from an exception, auto-extracting target exception from InvocationTargetException
     */
    public utilException(Exception ex, int errCode) {
        if (ex instanceof InvocationTargetException) {
            InvocationTargetException ext = (InvocationTargetException) ex;
            Throwable tmp1 = ext.getTargetException();
            if (tmp1 != null) {
                Throwable tmp2 = tmp1.getCause();
                if (tmp2 != null) {
                    this.message = tmp2.getMessage();
                }
            }
        } else {
            this.message = ex.getMessage();
        }
        this.errCode = errCode;
    }

    @Override
    public String getMessage() {
        return message;
    }

    public int getErrCode() {
        return errCode;
    }

    public String getErrorMessage() {
        return message;
    }
}
