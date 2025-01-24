package io.matrix;

public interface FailureEvent {
    boolean onFailure(Throwable res);
}
