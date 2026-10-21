package dev.deadearth.legacy;

import java.util.concurrent.*;
import java.util.function.Consumer;

/** Waits on the caller (RCON) thread; world work always belongs to the server queue. */
public final class ServerCommandDispatch {
  private ServerCommandDispatch() {}

  public static <T> T call(boolean owner, Consumer<Runnable> schedule, Callable<T> command) {
    FutureTask<T> task = new FutureTask<T>(command);
    if (owner) task.run();
    else schedule.accept(task);
    try {
      return task.get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Server command interrupted", interrupted);
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause();
      if (cause instanceof RuntimeException) throw (RuntimeException) cause;
      if (cause instanceof Error) throw (Error) cause;
      throw new IllegalStateException("Server command failed", cause);
    }
  }
}
