package dev.phpruntime.host;

/** An HTTP front end in front of {@link PhpDispatcher}. */
public interface PhpServer extends AutoCloseable {

    void start() throws Exception;

    int port();

    @Override
    void close();
}
