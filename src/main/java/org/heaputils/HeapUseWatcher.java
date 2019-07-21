package org.heaputils;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 *
 * A simple tracker of the non-ephemeral heap use and live set levels, which may be useful
 * for making application logic decisions that react to heap occupancy (in e.g. choosing
 * when and how much application-managed in-memory cached contents to keep or evict).
 *
 * This tracker leverages a trivially simple observation: In any collector, generational
 * or not, the stable set of actually live objects will eventually make it into the oldest
 * generation. We focus our reporting on the oldest generation, and completely ignore
 * all other use levels and activity.
 *
 * Reasoning: Any memory use in other, younger generations, regardless of their shapes and
 * parts, is temporary, and any non-ephemeral objects that reside there will eventually get
 * promoted to the oldest generation and be counted. While it is possible to temporarily
 * miss such objects temporarily by ignoring the younger generations, their impact on the
 * oldest generation will eventually show up.
 *
 * Ignoring the promotion of yet-to-be-promoted objects (when considering the oldest
 * generation use against the maximum capacity of that same oldest generation) is logically
 * equivalent to ignoring the allocations of yet-to-be-instantiated objects in the heap
 * as a whole. It is simply an admission that we cannot tell what the future might hold,
 * so we don't count it until it happens.
 *
 * Establishing the current use level (as opposed to the live set) in the oldest
 * generation is a relatively simple thing. But for many purposes, estimation of the
 * live set would be more useful generally. Making logic choices purely on the
 * currently observed level (which includes any promoted, now-dead objects that have
 * not yet been identified or collected) can often lead to unwanted behavior, as
 * logic may conservatively react to perceived use levels that are not real, and
 * indicate a "full or nearly full" heap when plenty of (not yet reclaimed) room
 * remains available.
 *
 * Live set estimation:
 * Due to the current limitations of the spec'ed memory management beans available in Java
 * SE (up to and including Java SE 13), there is no current way to establish an estimate
 * of the "live set" in the oldest generation without use of platform-specific (non-spec'ed)
 * MXBeans, which provide access to information about use levels before and after collection
 * events. While using such MXBeans does provide additional and better insight into live-set
 * levels, a portable approximation of the same can be achieved by watching the current use
 * levels in the oldest generation, and reporting the most recent local minima observed as
 * live set estimations [A local minimum in this context is the lowest level observed between
 * two other, higher levels, with some simple filtering applied to avoid noise].
 *
 * This class provides multiple forms of use:
 *
 * A. The runnable HeapUseWatcher class can be
 * launched and started as a thread, which will independently
 * maintain an updated model of the non-ephemeral heap use.
 *
 * B. One can directly use HeapUseWatcher.NonEphemeralHeapUseModel
 * and call its updateModel() method periodically to keep the model
 * up to date.
 *
 * C. The class can be used as java agent, and add optional
 * reporting output to existing, unmodified applications.
 */

public class HeapUseWatcher extends Thread {

    /**
     * A model of the stable (non-ephemeral, non-temporary) use of the heap.
     *
     * This model tracks the current use, maximum allowed, and estimated live
     * set in the heap, on the assumption tha the oldest generation in the heap
     * eventually accumulates all non-ephemeral objects, and that it's use and
     * estimated live may be potentially useful in making program logic choices.
     *
     * Current usage and maximum capacity are reported directly from the
     * associated stats oldest generation. The estimated live set level is
     * is modeled by looking for the most recent established local minimum
     * in the usage level of the oldest generation. I.e. a usage level
     * that was lower than prior usage levels AND lower than later usage
     * levels.
     *
     * This model requires regular periodic calls to the updateModel()
     * method in order to work. The model update intervals should be
     * chosen to be short enough to be a fraction of the typical
     * start-to-start interval between oldest-generation collections.
     * In most systems, an update interval of 1 second or less would
     * comfortably suffice.
     */
    public static class NonEphemeralHeapUseModel {

        // Currently known Oldest-generation names for OpenJDK variants and Zing:
        static private final List<String> oldGenNames = Arrays.asList(
                "G1 Old Gen",           // OpenJDK G1GC: -XX:+UseG1GC
                "PS Old Gen",           // OpenJDK ParallelGC: -XX:+ParallelGC
                "CMS Old Gen",          // OpenJDK ConcMarkSweepGC: -XX:+ConcMarkSweepGC
                "Tenured Gen",          // OpenJDK SerialGC: -XX:+UseSerialGC
                "GenPauseless Old Gen", // Zing C4/GPGC (No options needed)
                "Shenandoah",           // Shenandoah: -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoah
                "ZHeap"                 // ZGC: -XX:+UnlockExperimentalVMOptions -XX:+UseZGC
        );

        static private final double MB = 1024.0 * 1024.0;

        /**
         * Creates a model.
         */
        public NonEphemeralHeapUseModel() {
            this(10 * MB);
        }

        /**
         * Creates a model
         * @param noiseFilteringLevelInMB the noise filtering level to use in estimating live set
         */
        public NonEphemeralHeapUseModel(double noiseFilteringLevelInMB) {
            this.noiseFilteringLevelInBytes = (long) (noiseFilteringLevelInMB * MB);
            List<MemoryPoolMXBean> poolBeans = ManagementFactory.getMemoryPoolMXBeans();

            oldGenBean = poolBeans.stream().
                    filter((x) ->oldGenNames.contains(x.getName())).
                    findFirst().
                    orElse(null);

            if(oldGenBean == null) {
                StringBuilder errorMessage = new StringBuilder(
                        "No recognized OldGen pool name found. Pool names found include:\n");
                for (MemoryPoolMXBean b : poolBeans) {
                    errorMessage.append(b.getName()).append("\n");
                }
                throw new IllegalStateException(errorMessage.toString());
            }
        }

        private long noiseFilteringLevelInBytes;

        private MemoryPoolMXBean oldGenBean;

        private long maxAllowed;
        private long currentUsed;

        private long localMinimum = 0;

        /**
         * @return an estimate of the non-ephemeral live set, in bytes.
         */
        public long getEstimatedLiveSet() {
            return localMinimum;
        }

        /**
         * @return the current use level of the oldest generation, in bytes.
         */
        public long getCurrentUsed() {
            return currentUsed;
        }

        /**
         * @return the maximum allowed use of the oldest generation, in bytes.
         */
        public long getMaxAllowed() {
            return maxAllowed;
        }


        private long previousValue = 0;
        private long previousDelta = 0;

        /**
         * Update the model.
         * This model requires regular periodic callsto the updateModel()
         * method in order to work. The model update intervals should be
         * chosen to be short enough to be a fraction of the typical
         * start-to-start interval between oldest-generation collections.
         * In most systems, an update interval of 1 second or less would
         * comfortably suffice.
         */
        public void updateModel() {
            MemoryUsage currentOldGenUsage = oldGenBean.getUsage();
            maxAllowed = currentOldGenUsage.getMax();
            currentUsed = currentOldGenUsage.getUsed();

            long delta = currentUsed - previousValue;

            if (Math.abs(delta) > noiseFilteringLevelInBytes) {
                if (delta > 0) {
                    if (previousDelta < 0) {
                        localMinimum = previousValue;
                    }
                }
                previousValue = currentUsed;
                previousDelta = (delta != 0) ? delta : previousDelta;
            }

        }
    }

    private final NonEphemeralHeapUseModel nonEphemeralHeapUseModel;

    public long getEstimatedLiveSet() {
        return nonEphemeralHeapUseModel.getEstimatedLiveSet();
    }

    public long getCurrentUsed() {
        return nonEphemeralHeapUseModel.getCurrentUsed();
    }

    public long getMaxAllowed() {
        return nonEphemeralHeapUseModel.getMaxAllowed();
    }

    private final HeapUseWatcherConfiguration config;

    private final PrintStream log;

    private class HeapUseWatcherConfiguration {
        double noiseFilteringLevelInMB = 10.0;
        double pollingIntervalMsec = 1000.0;
        double reportingIntervalMsec = 0.0;
        boolean verbose = false;

        String logFileName;

        boolean error = false;
        String errorMessage = "";

        private HeapUseWatcherConfiguration(final String[] args) {
            try {
                for (int i = 0; i < args.length; ++i) {
                    if (args[i].equals("-v")) {
                        verbose = true;
                    } else if (args[i].equals("-i")) {
                        pollingIntervalMsec = Double.parseDouble(args[++i]);   // lgtm [java/index-out-of-bounds]
                    } else if (args[i].equals("-r")) {
                        reportingIntervalMsec = Double.parseDouble(args[++i]);   // lgtm [java/index-out-of-bounds]
                    } else if (args[i].equals("-e")) {
                        noiseFilteringLevelInMB = Double.parseDouble(args[++i]);   // lgtm [java/index-out-of-bounds]
                    } else if (args[i].equals("-l")) {
                        logFileName = args[++i];                            // lgtm [java/index-out-of-bounds]
                    } else {
                        throw new Exception("Invalid args: " + args[i]);
                    }
                }

                try {
                    //Try to make a model just to catch failure and report error during config construction:
                    NonEphemeralHeapUseModel model = new NonEphemeralHeapUseModel(noiseFilteringLevelInMB);
                } catch (IllegalStateException ex) {
                    error = true;
                    errorMessage = ex.getMessage();
                }
            } catch (Exception e) {
                error = true;
                errorMessage = "Error: launched with the following args:\n";

                for (String arg : args) {
                    errorMessage += arg + " ";
                }
                errorMessage += "\nWhich was parsed as an error, indicated by the following exception:\n" + e;

                System.err.println(errorMessage);

                String validArgs =
                        "\"[-v] [-i pollingIntervalMsec] [-r reportingIntervalMsec] " +
                " [-e noiseFilteringLevelInMB] [-l logFileName]\"\n";

                System.err.println("valid arguments = " + validArgs);

                System.err.println(
                        " [-h]                          help\n" +
                                " [-v]                          verbose\n" +
                                " [-i pollingIntervalMsec]      Polling interval for HeapWatcher [default 1000 msec]\n" +
                                " [-i reportingIntervalMsec]    Reporting interval [default 0, for no reporting]\n" +
                                " [-e noiseFilteringLevelInMB]  The level of noise filtering to apply in determining \n" +
                                " [-l logFileName]              File to direct logging to (default none, output to stdout)\n" +
                                "\n");
            }
        }
    }

    public HeapUseWatcher(final String[] args) throws FileNotFoundException {
        this.setName("HeapUseWatcher");
        config = new HeapUseWatcherConfiguration(args);
        nonEphemeralHeapUseModel = new NonEphemeralHeapUseModel(config.noiseFilteringLevelInMB);
        log = (config.logFileName != null) ?
                new PrintStream(new FileOutputStream(config.logFileName), false) :
                System.out;
        this.setDaemon(true);
    }

    public HeapUseWatcher() throws FileNotFoundException {
        this(new String[] {});
    }



    private volatile boolean doRun = true;

    public void terminate() {
        doRun = false;
    }

    static private final double GB = 1024.0 * 1024.0 * 1024.0;

    public void run() {
        final long pollingIntervalNsec = (long) (config.pollingIntervalMsec * 1000L * 1000L);
        try {

            long nextReportingTime = 0;

            while (doRun) {
                if (pollingIntervalNsec != 0) {
                    TimeUnit.NANOSECONDS.sleep(pollingIntervalNsec);
                }

                nonEphemeralHeapUseModel.updateModel();

                long now = System.currentTimeMillis();
                if ((config.reportingIntervalMsec != 0) && (now >= nextReportingTime)) {
                    log.printf("CurrentUsed = %.3fGB, MaxAllowed = %.3fGB, EstimatedLiveSet = %.3fGB\n",
                            getCurrentUsed()/GB, getMaxAllowed()/GB, getEstimatedLiveSet()/GB);
                    nextReportingTime = now + (long)config.reportingIntervalMsec;
                }
            }
        } catch (InterruptedException e) {
            if (config.verbose) {
                System.err.println("# HeapUseWatcher interrupted/terminating...");
            }
        }
    }

    private static HeapUseWatcher commonMain(final String[] args, boolean exitOnError) {
        HeapUseWatcher heapUseMeter = null;
        try {
            heapUseMeter = new HeapUseWatcher(args);

            if (heapUseMeter.config.error) {
                if (exitOnError) {
                    System.exit(1);
                } else {
                    throw new RuntimeException("Error: " + heapUseMeter.config.errorMessage);
                }
            }

            if (heapUseMeter.config.verbose) {
                heapUseMeter.log.print("# Executing: HeapUseWatcher");
                for (String arg : args) {
                    heapUseMeter.log.print(" " + arg);
                }
                heapUseMeter.log.println();
                MemoryUsage oldGenUsage = heapUseMeter.nonEphemeralHeapUseModel.oldGenBean.getUsage();
                heapUseMeter.log.printf("Oldest heap generation (Used/Max): %.3fGB/%.3fGB  [pool name: %s]\n",
                        oldGenUsage.getUsed()/GB, oldGenUsage.getMax()/GB,
                        heapUseMeter.nonEphemeralHeapUseModel.oldGenBean.getName());
            }

            heapUseMeter.start();

        } catch (FileNotFoundException e) {
            System.err.println("HeapUseWatcher: Failed to open log file.");
        }
        return heapUseMeter;
    }

    public static void agentmain(String argsString, java.lang.instrument.Instrumentation inst) {
        final String[] args = ((argsString != null) && !argsString.equals("")) ? argsString.split("[ ,;]+") : new String[0];
        commonMain(args, false);
    }

    public static void premain(String argsString, java.lang.instrument.Instrumentation inst) {
        final String[] args = ((argsString != null) && !argsString.equals("")) ? argsString.split("[ ,;]+") : new String[0];
        commonMain(args, true);
    }

    public static void main(final String[] args)  {
        final HeapUseWatcher heapUseWatcher = commonMain(args, true);

        if (heapUseWatcher != null) {
            // The HeapUseWatcher thread, on it's own, will not keep the JVM from exiting.
            // If nothing else is running (i.e. we we are the main class), then keep main thread from
            // exiting until the HeapUseWatcher thread does...
            try {
                heapUseWatcher.join();
            } catch (InterruptedException e) {
                if (heapUseWatcher.config.verbose) {
                    heapUseWatcher.log.println("# HeapUseWatcher main() interrupted");
                }
            }
        }
    }
}
