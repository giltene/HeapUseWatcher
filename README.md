HeapUseWatcher
===========
[![Javadocs](http://www.javadoc.io/badge/org.heaputils/HeapUseTracker.svg)](http://www.javadoc.io/doc/org.heaputils/HeapUseTracker)
[![Total alerts](https://img.shields.io/lgtm/alerts/g/giltene/HeapUseWatcher.svg?logo=lgtm&logoWidth=18)](https://lgtm.com/projects/g/giltene/HeapUseWatcher/alerts/)
[![Language grade: Java](https://img.shields.io/lgtm/grade/java/g/giltene/HeapUseWatcher.svg?logo=lgtm&logoWidth=18)](https://lgtm.com/projects/g/giltene/HeapUseWatcher/context:java)


A simple tracker of non-ephemeral heap use and live set levels, which may be useful
for making application logic decisions that react to heap occupancy (in e.g. choosing
when and how much application-managed in-memory cached contents to keep or evict).

This tracker leverages a trivially simple observation: In any collector, generational
or not, the stable set of actually live objects will eventually make it into the oldest
generation. We focus our reporting on the oldest generation, and completely ignore
all other use levels and activity.

Reasoning: Any memory use in other, younger generations, regardless of their shapes and
parts, is temporary, and any non-ephemeral objects that reside there will eventually get
promoted to the oldest generation and be counted. While it is possible to temporarily
miss such objects by ignoring the younger generations, their impact on the
oldest generation will eventually show up.

Ignoring the promotion of yet-to-be-promoted objects (when considering the oldest
generation use against the maximum capacity of that same oldest generation) is logically
equivalent to ignoring the allocations of yet-to-be-instantiated objects in the heap
as a whole. It is simply an admission that we cannot tell what the future might hold,
so we don't count it until it happens.

Establishing the current use level (as opposed to the live set) in the oldest
generation is a relatively simple thing. But for many purposes, estimation of the
live set would be much more useful. Making logic choices based purely on the
currently observed level (which includes any promoted, now-dead objects that have
not yet been identified or collected) is usually "a bad idea", and can often lead
to unwanted behavior, as logic may (and will) conservatively react to perceived
use levels that are not real, and indicate a "full or nearly full" heap when
plenty of (not yet reclaimed) room remains available.

Live set estimation:
Due to the current limitations of the spec'ed memory management bean APIs available
in Java SE (up to and including Java SE 13), there is no current way to use those
APIs to directly establish the "live set" in the oldest generation in a reliable
manner, with logic that actually works across the various collectors out there and
their possible configurations. Even when using platform-specific (non-spec'ed)
MXBeans, the data being reported does not directly indicate the "live set" (e.g.
G1GC after-collection usage is reported after every incremental mixed collection
step, and not for the Oldgen as a whole, so it usually does not represent the
live set).

A portable, collector-independent approximation of the live set can be achieved
by watching the current use levels in the oldest generation, and reporting the
most recent local minima observed as live set estimations [A local minimum in
this context is the lowest level observed between two other, higher levels, with
some simple filtering applied to avoid noise]. That's basically the simple thing
that HeapUseWatcher does.

The HeapUseWatcher jar and class supports multiple forms of use:

- The runnable HeapUseWatcher class can be
launched and started as a thread, which will independently
maintain an updated model of the non-ephemeral heap use.

- One can directly use HeapUseWatcher.NonEphemeralHeapUseModel
and call its updateModel() method periodically to keep the model
up to date.

- The jar file can be used as a java agent, to add optional
reporting output to existing, unmodified applications.

Example of use as a java agent:
----
Here is a simple example of using HeapUseWatcher as a java agent (here, it is
used to monitor heap use and live set in a 
[HeapFragger](https://github.com/giltene/HeapFragger) run. HeapFragger
is a simple active excercizer of the heap and presents a nice movingt
arget for a HeapUseWatcher agent to track and report on):

````
% java -Xmx1500m -XX:+UseG1GC -javaagent:HeapUseWatcher.jar="-r 1000" -jar HeapFragger.jar -a 3000 -s 500
````


Example programmatic use:
-----

#### As a thread (doing it's own model updates in the background):
````
import org.heaputils.heapusewatcher.HeapUseWatcher;
...
````

````

watcher = new HeapUseWatcher();
watcher.start();
// Watcher now runs in the background and updates its live set model,
// as well as refresging max available, and current use on a regular
// basis (default every 1 sec).
...
````

````
...
// At some point later, e.g. in some "every 2 minute polling point":
long liveSet = watcher.getEstimatedLiveSet();
long currentUsed = watcher.getCurrentUsed();
long maxAvalaible = watcher.getMaxAvailable();

// And, for example. act on these values:
if (((double) liveSet)/maxAvalaible > triggeringThreasholdFraction) {
    doWhatYouNeedToDo();
}
````

#### As a class (nothing running independently), and regular model update calls are made by someone else:

````
import org.heaputils.heapusewatcher.HeapUseWatcher;
...
````

````

model = new HeapUseWatcher.NonEphemeralHeapUseModel();
model.updateModel();
...
````

Somewhere in the program a periodic task needs to be regularly updating the model
(e.g. once per second or so is usually good enough):

````
...
model.updateModel();
````

And wherever you need to observe the heap use:
````
...
// At some point later, e.g. in some "every 2 minute polling point":
long liveSet = model.getEstimatedLiveSet();
long currentUsed = model.getCurrentUsed();
long maxAvalaible = model.getMaxAvailable();

// And, for example. act on these values:
if (((double) liveSet)/maxAvalaible > triggeringThreasholdFraction) {
    doWhatYouNeedToDo();
}
````