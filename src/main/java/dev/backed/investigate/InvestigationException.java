package dev.backed.investigate;

/** A request to investigate, approve or dismiss that was refused; the message is shown to the user. */
public class InvestigationException extends RuntimeException {

    public InvestigationException(String message) {
        super(message);
    }

    public InvestigationException(String message, Throwable cause) {
        super(message, cause);
    }
}
