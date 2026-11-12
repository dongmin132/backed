package dev.backed.close;

/** Runs the day close for the current business date. Implemented by the close module. */
public interface CloseRunner {

    /** @param actor who ran it: a user name, or a system actor from {@link dev.backed.config.Actors} */
    CloseOutcome runClose(String actor);
}
