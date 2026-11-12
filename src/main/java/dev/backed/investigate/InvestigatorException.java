package dev.backed.investigate;

public class InvestigatorException extends RuntimeException {

    public InvestigatorException(String message) {
        super(message);
    }

    public InvestigatorException(String message, Throwable cause) {
        super(message, cause);
    }
}
