package logclient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class GoogleLogClientImpl implements LogClient {
    private final ConcurrentSkipListMap<Long, List<Process>> queue;
    private final Map<String, Process> map;
    private final Lock lock;
    private final BlockingQueue<CompletableFuture<String>> pendingPolls;
    private final ExecutorService[] taskScheduler;

    public GoogleLogClientImpl(int threads) {
        queue = new ConcurrentSkipListMap<>();
        map = new ConcurrentHashMap<>();
        lock = new ReentrantLock();
        pendingPolls = new LinkedBlockingQueue<>();
        taskScheduler = new ExecutorService[threads];
        for (int i = 0; i < taskScheduler.length; i++) {
            taskScheduler[i] = Executors.newSingleThreadExecutor();
        }
    }

    public void start(final String taskId, long timestamp) {
        taskScheduler[taskId.hashCode() % taskScheduler.length].execute(() -> {
            final Process task = new Process(taskId, timestamp);
            map.put(taskId, task);
            queue.putIfAbsent(timestamp, new CopyOnWriteArrayList<>());
            queue.get(timestamp).add(task);
        });
    }

    public void end(final String taskId) {
        taskScheduler[taskId.hashCode() % taskScheduler.length].execute(() -> {
            map.get(taskId).setEndTime(System.currentTimeMillis());
            lock.lock();
            try {
                String result;
                while (!pendingPolls.isEmpty() && (result = pollNow()) != null) {
                    pendingPolls.take().complete(result);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }
        });
    }

    public String poll() {
        final CompletableFuture<String> result = new CompletableFuture<>();
        lock.lock();
        try {
            try {
                String logStatement;
                if (!pendingPolls.isEmpty()) {
                    pendingPolls.offer(result);
                } else if ((logStatement = pollNow()) != null) {
                    return logStatement;
                } else {
                    pendingPolls.offer(result);
                }
            } finally {
                lock.unlock();
            }
            return result.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    private String pollNow() {
        if (!queue.isEmpty()) {
            for (final Process earliest : queue.firstEntry().getValue()) {
                if (earliest.getEndTime() != -1) {
                    queue.firstEntry().getValue().remove(earliest);
                    if (queue.firstEntry().getValue().isEmpty()) {
                        queue.pollFirstEntry();
                    }
                    map.remove(earliest.getId());
                    final var logStatement = "task " + earliest.getId() + " started at: " + earliest.getStartTime() + " and ended at: " + earliest.getEndTime();
                    System.out.println(logStatement);
                    return logStatement;
                }
            }
        }
        return null;
    }
}
