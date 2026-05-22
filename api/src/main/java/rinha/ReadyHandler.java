package rinha;

public final class ReadyHandler {

    static final byte[] LOADED_RESPONSE = (
        "HTTP/1.1 204 No Content\r\n" +
        "Content-Length: 0\r\n" +
        "Connection: close\r\n" +
        "\r\n"
    ).getBytes();

    static final byte[] NOT_LOADED_RESPONSE = (
        "HTTP/1.1 500 Internal Server Error\r\n" +
        "Content-Length: 0\r\n" +
        "Connection: close\r\n" +
        "\r\n"
    ).getBytes();

    private final KdTreeLoader loader;

    public ReadyHandler(KdTreeLoader loader) {
        this.loader = loader;
    }

    /** Returns the appropriate response bytes: 204 when loaded, 500 while loading. */
    public byte[] response() {
        return loader.isLoaded() ? LOADED_RESPONSE : NOT_LOADED_RESPONSE;
    }
}
