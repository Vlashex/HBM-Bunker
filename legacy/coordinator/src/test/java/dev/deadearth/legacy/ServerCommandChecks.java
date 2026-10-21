package dev.deadearth.legacy;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real cross-thread dispatch regression, independent of Minecraft reflection. */
public final class ServerCommandChecks {
  private static int checks;

  private static void check(boolean ok) {
    checks++;
    if (!ok) throw new AssertionError("Command check " + checks);
  }

  public static void main(String[] args) throws Exception {
    Thread owner = Thread.currentThread();
    BlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();
    AtomicReference<String> result = new AtomicReference<String>();
    AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
    AtomicInteger calls = new AtomicInteger();
    Thread rcon =
        new Thread(
            () -> {
              try {
                result.set(
                    ServerCommandDispatch.call(
                        false,
                        queue::add,
                        () -> {
                          check(Thread.currentThread() == owner);
                          calls.incrementAndGet();
                          return "ready";
                        }));
              } catch (Throwable error) {
                failure.set(error);
              }
            });
    rcon.start();
    Runnable work = queue.poll(2, TimeUnit.SECONDS);
    check(work != null);
    check(calls.get() == 0 && rcon.isAlive());
    work.run();
    rcon.join(2000);
    check(
        !rcon.isAlive()
            && failure.get() == null
            && "ready".equals(result.get())
            && calls.get() == 1);
    String direct =
        ServerCommandDispatch.call(
            true,
            ignored -> {
              throw new AssertionError("Owner must not queue itself");
            },
            () -> "direct");
    check(direct.equals("direct"));
    RuntimeException expected = new IllegalArgumentException("invalid draft");
    try {
      ServerCommandDispatch.call(
          true,
          queue::add,
          () -> {
            throw expected;
          });
      throw new AssertionError();
    } catch (RuntimeException actual) {
      check(actual == expected);
    }
    Thread.currentThread().interrupt();
    try {
      ServerCommandDispatch.call(false, queue::add, () -> "later");
      throw new AssertionError();
    } catch (IllegalStateException interrupted) {
      check(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    System.out.println("ServerCommandChecks passed: " + checks);
  }
}
