package io.mindspice.logging;

public interface MintLogger {


    void log(Class<?> clazz, LogLevel logLevel, String message);

}
