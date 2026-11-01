package _1ms.playtime.Handlers;

/** A failed storage operation, distinct from a player with no saved record. */
public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
