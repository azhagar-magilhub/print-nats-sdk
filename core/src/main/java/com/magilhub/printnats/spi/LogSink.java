package com.magilhub.printnats.spi;

/**
 * Host-provided log writer. {@code fileName} is the legacy log-file prefix ("print_", "natsStatus_", …)
 * so hosts can keep writing the same {@code <prefix><YYYYMMDD>.txt} files support already collects.
 */
public interface LogSink {
    LogSink NONE = new LogSink() {
        @Override
        public void append(String fileName, String content) {
        }
    };

    void append(String fileName, String content);
}
