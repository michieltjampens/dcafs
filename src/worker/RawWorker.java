package worker;

import das.Paths;
import io.forward.steps.AbstractStep;
import io.forward.LinkedStepsFab;
import org.tinylog.Logger;
import util.data.vals.Rtvals;
import util.tools.TimeTools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.stream.IntStream;

public class RawWorker {
    static int WORKERS = 8;

    int MINIMUM_SIZE = 50000;
    final BlockingQueue<String> queue = new LinkedBlockingQueue<>(); // max size

    ThreadPoolExecutor executor = new ThreadPoolExecutor(3,
            Math.min(WORKERS + 1, Runtime.getRuntime().availableProcessors()), // max allowed threads
            30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(WORKERS + 1));

    private ArrayList<Path> files;
    private AbstractStep[] cleanup;
    private AbstractStep[] storage;
    private ConcurrentHashMap<Long, String> map = new ConcurrentHashMap<>(250000);

    private int stageOne = 0;
    private long totalLines = 0;
    private long startTime = 0;
    private long totalSort = 0;
    private long linesToday = 0;
    private long workerStart = 0;
    private long sortedLines = 0;
    private long readingTime = 0;
    private long waitingTime = 0;

    public RawWorker(Rtvals rtvals) {
        var dig = Paths.digInSettings("rawworker");

        if (dig.isInvalid()) {
            Logger.info("No rawworker node in settings file.");
            return;
        }
        var opt = dig.attr("filepath", Paths.storage());
        if (opt.isEmpty()) {
            Logger.error("No valid path defined");
        } else {
            cleanup = getStageInstances(rtvals, 1);
            storage = getStageInstances(rtvals, 2);
            files = getFiles(opt.get());
        }
    }

    public void start(boolean first) {
        if (first)
            startTime = Instant.now().toEpochMilli();
        executor.submit(this::fileReader);
    }

    private void fileReader() {
        long lineNumber = 0;
        if (files.isEmpty()) {
            var total = Instant.now().toEpochMilli() - startTime;
            Logger.info("Finished processing " + totalLines + " lines from files in " + TimeTools.convertPeriodToString(total, TimeUnit.MILLISECONDS));
            Logger.info("Time spent reading: " + TimeTools.convertPeriodToString(readingTime, TimeUnit.MILLISECONDS)
                    + " and waiting " + TimeTools.convertPeriodToString(waitingTime, TimeUnit.MILLISECONDS));
            return;
        }

        // Start the workers
        IntStream.range(0, WORKERS).forEach(index -> executor.submit(() -> process(index)));
        if (workerStart == 0)
            workerStart = Instant.now().toEpochMilli();

        var date = files.get(0).getFileName().toString().substring(0, 10);
        Logger.info("Started reading: " + date);

        while (!files.isEmpty() && files.get(0).getFileName().toString().substring(0, 10).equals(date)) {
            try {
                var t = Instant.now().toEpochMilli();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(Files.newInputStream(files.remove(0)), StandardCharsets.ISO_8859_1), 64 * 1024)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lineNumber++;
                        queue.put(lineNumber + "|" + line);
                    }
                } catch (IOException e) {
                    Logger.error(e);
                }
                readingTime += Instant.now().toEpochMilli() - t;

                t = Instant.now().toEpochMilli();
                synchronized (queue) {
                    while (queue.size() > MINIMUM_SIZE) {
                        queue.wait(10);  // Wait with a timeout or indefinitely if needed
                    }
                }
                waitingTime += Instant.now().toEpochMilli() - t;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Logger.info("Total lines this day: " + lineNumber);
        linesToday = lineNumber;
        totalLines += lineNumber;
    }

    private AbstractStep[] getStageInstances(Rtvals rtvals, int stage) {
        var instances = new AbstractStep[WORKERS];
        for (int a = 0; a < WORKERS; a++) {
            var dig = Paths.digInSettings("rawworker");

            if (!dig.hasPeek("stage" + stage)) {
                Logger.error("Missing stage " + stage);
                return new AbstractStep[0];
            }

            dig.digDown("stage" + stage);
            if (!dig.hasAttr("id")) {
                dig.currentTrusted().setAttribute("id", "rawworker_stage" + stage);
            }
            var steps = LinkedStepsFab.buildLink(dig, rtvals, "\t");
            if (steps.length == 0) {
                Logger.error("No valid steps in the rawworker");
                return new AbstractStep[0];
            }
            if (stage == 1)
                steps[0].removeStore(); // We don't want to write to stores yet...
            instances[a] = steps[0];
        }
        return instances;
    }

    public ArrayList<Path> getFiles(Path folder) {
        var files = new ArrayList<Path>();

        if (!folder.isAbsolute()) {
            folder = Paths.storage().resolve(folder);
        }
        if (Files.isDirectory(folder)) {
            try (var str = Files.list(folder)) {
                str.forEach(files::add);
            } catch (IOException e) {
                Logger.error("Error when trying to read " + folder, e.getMessage());
            }
        } else {
            files.add(folder);
        }
        return files;
    }

    public void process(int instance) {
        var steps = cleanup[instance];
        while (true) {
            try {
                String line = queue.poll(1, TimeUnit.SECONDS);  // Blocks if the queue is empty
                if (line == null) {
                    stageOne++;
                    if (stageOne == WORKERS - 1) {
                        stageOne = 0;
                        executor.submit(this::sort);
                        if (files.isEmpty()) {
                            var total = Instant.now().toEpochMilli() - workerStart;
                            Logger.info("Total time stage 1: " + TimeTools.convertPeriodToString(total, TimeUnit.MILLISECONDS));
                        }
                    }
                    break;
                }
                // Split the data in the id and the original data
                var parts = line.split("\\|", 2);
                var result = steps.takeStep(parts[1], null);
                if (!result.isEmpty())
                    map.put(Long.parseLong(parts[0]), result);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void sort() {

        var time = Instant.now().toEpochMilli();
        var list = new ArrayList<String>(map.size());
        for (long a = 0; a < linesToday + 10; a++) {
            var x = map.get(a);
            if (x != null) {
                list.add(x);
            }
        }

        map.clear(); // Clears  the map but retains capacity and thus memory usage
        totalSort += Instant.now().toEpochMilli() - time;
        sortedLines += list.size();
        if (files.isEmpty()) {
            Logger.info("Total time spent sorting " + sortedLines + " lines: " + TimeTools.convertPeriodToString(totalSort, TimeUnit.MILLISECONDS));
            // No longer need to data, so free memory
            map = new ConcurrentHashMap<>(1);
        }
        // Start on stage 2...
        executor.submit(() -> doStage2(list));
    }

    public void doStage2(ArrayList<String> data) {
        for (String line : data) {
            storage[0].takeStep(line, null);
        }
        data.clear();
        start(false);
    }
}
