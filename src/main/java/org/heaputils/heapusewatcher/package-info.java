/*
 * package-info.java
 * Written by Gil Tene of Azul Systems, and released to the public domain,
 * as explained at http://creativecommons.org/publicdomain/zero/1.0/
 */

/**
 * <h3>HeapUseWatcher: A simple tracker of non-ephemeral heap use and live set levels</h3>
 * A tracker of non-ephemeral heap use and live set levels which may be useful
 * for making application logic decisions that react to heap occupancy (in e.g. choosing
 * when and how much application-managed in-memory cached contents to keep or evict).
 * <p>
 * This tracker leverages a trivially simple observation: In any collector, generational
 * or not, the stable set of actually live objects will eventually make it into the oldest
 * generation. We focus our reporting on the oldest generation, and completely ignore
 * all other use levels and activity.
 * </p><p>
 * While establishing the current use level (as opposed to the live set) in the oldest
 * generation is a relatively simple thing. Estimation of the live set would be much
 * more beneficial. Making logic choices based purely on the currently observed level
 * (which includes any promoted, now-dead objects that have not yet been identified
 * or collected) is usually "a bad idea", and can often lead to unwanted behavior,
 * as logic may (and will) conservatively react to perceived use levels that are not
 * real, and indicate a "full or nearly full" heap when plenty of (not yet reclaimed)
 * room remains available.
 * </p>
 * The HeapUseWatcher jar and class provides multiple forms of use:
 * <ul>
 * <li>
 * The runnable HeapUseWatcher class can be
 * launched and started as a thread, which will independently
 * maintain an updated model of the non-ephemeral heap use.
 * </li>
 * <li>
 * One can directly use HeapUseWatcher.NonEphemeralHeapUseModel
 * and call its updateModel() method periodically to keep the model
 * up to date.
 * </li><li>
 * The jar file can be used as a java agent, to add optional
 * reporting output to existing, unmodified applications.
 * </li>
 * </ul>
 *
 * <h3>Example programmatic use:</h3>
 * <h4>As a thread (doing it's own model updates in the background):</h4>
 * <pre>{@code
 * import org.heaputils.heapusewatcher.HeapUseWatcher;
 * ...
 *
 * watcher = new HeapUseWatcher();
 * watcher.start();
 * // Watcher now runs in the background and updates its live set model,
 * // as well as refresging max available, and current use on a regular
 * // basis (default every 1 sec).
 * ...
 * }</pre>
 *
 * At some point later, e.g. in some "every 2 minute polling point":
 * <pre>{@code
 * ...
 * long liveSet = watcher.getEstimatedLiveSet();
 * long currentUsed = watcher.getCurrentUsed();
 * long maxAvalaible = watcher.getMaxAvailable();
 *
 * // And, for example. act on these values:
 * if (((double) liveSet)/maxAvalaible > triggeringThreasholdFraction) {
 *     doWhatYouNeedToDo();
 * }
 * }</pre>
 *
 * <h4>As a class (nothing running independently), and regular model update calls are made by someone else:</h4>
 *
 * <pre>{@code
 * import org.heaputils.heapusewatcher.HeapUseWatcher;
 * ...
 *
 * model = new HeapUseWatcher.NonEphemeralHeapUseModel();
 * model.updateModel();
 * ...
 * }</pre>
 *
 * Somewhere in the program a periodic task needs to be regularly updating the model
 * (e.g. once per second or so is usually good enough):
 *
 * <pre>{@code
 * ...
 * model.updateModel();
 * }</pre>
 *
 * And wherever you need to observe the heap use:
 * <pre>{@code
 * ...
 * // At some point later, e.g. in some "every 2 minute polling point":
 * long liveSet = model.getEstimatedLiveSet();
 * long currentUsed = model.getCurrentUsed();
 * long maxAvalaible = model.getMaxAvailable();
 *
 * // And, for example. act on these values:
 * if (((double) liveSet)/maxAvalaible > triggeringThreasholdFraction) {
 *     doWhatYouNeedToDo();
 * }
 * }</pre>
 */

package org.heaputils.heapusewatcher;


