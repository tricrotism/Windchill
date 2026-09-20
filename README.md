# Windchill

A Paper/Folia plugin that reports **why the JVM could not make your plugins faster**, and manages an
AOT cache so it does not have to start from cold every boot.

A sampling profiler tells you a method is hot. Windchill reads the JIT compiler's own event stream
and tells you what the compiler decided about that method: that it refused to inline it and the exact
reason it gave, that it could not work out which implementation a call site reaches, that it threw
away the compiled version 4,000 times a minute, or that it gave up on the method permanently. Then it
attributes all of it to the plugin that owns the code.

Built on the JDK's public Flight Recorder API. No NMS, no server internals.

## What it finds

| Rule      | What it means                                                                                                                                   |
|-----------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| **JIT-1** | A hot method C2 refused to inline because of its size, with its real size against the live limit                                                |
| **JIT-2** | A hot virtual or interface call that compiled code still makes as a real call, located to the line                                              |
| **JIT-3** | A method deoptimising repeatedly, with the line, the instruction and the caller that triggered it                                               |
| **JIT-4** | A method HotSpot has permanently barred from C2. It runs as slower C1 code until restart. Only JVMCI compilers such as Graal report this to JFR |
| **JIT-5** | Hot code the optimising compiler is not keeping, with in-window evidence for why                                                                |
| **JIT-6** | A method compiled over and over inside one window                                                                                               |
| **JIT-7** | A method the compiler tried to compile and bailed out of, with HotSpot's reason                                                                 |
| **JIT-8** | A hot method running in the interpreter, usually one over 8000 bytes that HotSpot never compiles                                                |
| **VM-1**  | Code cache pressure per code heap, and how much machine code each plugin holds in it                                                            |
| **VM-2**  | A compiler queue backlog leaving code interpreted while it waits                                                                                |

Every finding carries the measurement that produced it and a specific change to make. Findings are
ranked by severity first and by measured hotness within it, so a compiler complaint about a method
nothing calls never outranks one about a method taking 4% of your samples.

Rules are deliberately conservative about what counts as evidence. A window only sees the
compilations that happen inside it, so "no compilation seen" is treated as the window missing it,
not as proof the method is interpreted. JIT-2 and JIT-8 do not have that limit: every sample records
whether its frame ran interpreted and which instruction it stopped on, so they work on a server that
has been up for days.

## The AOT cache

JDK 24 added an AOT cache and JDK 25 extended it to carry method profiles. Windchill walks an
operator through the lifecycle one step at a time and measures the result.

```
/windchill aot          what state this server is in, the one next thing to do, per plugin how
                        many classes the cache is actually serving, and plugins that ship the
                        same classes unrelocated
/windchill aot finish   end a training run and write the cache without stopping the server, then
                        say per plugin which classes the JVM left out and why
/windchill aot assemble build a cache from a recorded configuration
/windchill flags        every JVM flag Windchill can justify from what it measured
```

It does two separate jobs:

- **Cached classes** cut the parse and verify work at startup. This covers Paper's and every
  plugin's classes, even though they load through their own class loaders.
- **Recorded method profiles** let hot methods skip the profiling tiers on boot. On JDK 25 these are
  recorded only for classes on the JDK's built-in loaders, which on Paper means JDK methods. Paper and
  plugin methods are cached as classes but still warm up through the tiers. Measured with two
  identical hot classes: the one on the application loader had training data, the one in a
  `URLClassLoader` subclass had none.

Windchill measures both on your own server rather than asserting them, by recording one row per boot
and comparing medians. Measured on the test server in this repo:

|                                        | without a cache | with a cache  |
|----------------------------------------|-----------------|---------------|
| Time to plugin enable                  | 15,965 ms       | **11,736 ms** |
| Compilations during the startup window | 640             | **383**       |

The first row is mostly the class-loading saving. The second is the method-profile saving, from JDK
methods reaching the optimising compiler without climbing the tiers. Both need the automatic startup
capture to be on, since the comparison is only valid taken at the same point after every boot.

Things worth knowing, all measured on Temurin 25.0.3:

- **A stale plugin jar is safe, and degrades per class.** HotSpot checks each plugin class against
  its jar and loads from the jar when they disagree. Updating a plugin costs only the classes that
  changed: a rebuilt plugin on the test server still had 554 of its 760 classes served.
  `/windchill aot` reads the JVM's own class list and says how many of each plugin's classes the cache
  serves.
- **The server jar is not safe to change.** Classes from the jar on the application class path (the
  Paperclip launcher) are not checked on 25.0.3: a class changed after training ran its old cached
  version with no warning (JDK-8377932). Later builds refuse the whole cache instead. Re-train after
  every Paper or Java update. `/windchill aot` warns when the server jar is newer than the cache.
- **Train with the flags you run with.** A cache trained on G1 is refused outright under ZGC, with a
  heap moved across about 32 GB, with `UseCompactObjectHeaders` flipped, or with `--add-opens` that
  differ from training. A refused cache also turns off the JDK's own class sharing, so the server
  boots slower than with no cache flag at all. Windchill warns about this at startup and keeps those
  boots out of its startup comparison.
- **The training run costs memory at exit.** With `-XX:AOTCacheOutput` the JVM builds the cache in a
  second JVM given the same heap flags, so the machine briefly needs room for two heaps. The two-step
  `-XX:AOTMode=record` plus `/windchill aot assemble` avoids that. Killing the JVM while it builds
  leaves an empty cache file.
- **Use `-XX:AOTMode=auto`, not `on`.** With `on` a cache the JVM refuses stops the server starting,
  so an unattended Java or Paper update keeps it down.
- **Directory classpath entries are never cached.** HotSpot skips them with "Unsupported location".
  Only jars are cached.
- **Two plugins shading the same library without relocating it get one cached copy between them.**
  The other copy is skipped as "Duplicated unregistered class", and classes built on it follow. It is
  also the classic way plugins break each other, so `/windchill aot` lists it either way.

## Timing without an agent

`/windchill time [seconds]` gives exact call counts and per-call wall time for the methods the last
capture flagged, using JDK 25's `jdk.MethodTiming` (JEP 520). JFR adds the timing code itself and
removes it when the window closes, so no agent and no start flags are needed.

It refuses to run alongside a capture, because the added code makes the timed methods bigger and
changes the inlining decisions a capture measures. It also refuses during an AOT training run: HotSpot
leaves any class JFR rewrote out of the cache that run writes.

## The optional agent

Windchill works without it. Attaching adds **exact attribution**, read from live class loaders rather
than by scanning plugin jars, which also covers classes generated at runtime.

The agent reads and never rewrites. It injects no bytecode and does not touch the bootstrap loader,
so it leaves an AOT cache in use alone. Earlier versions counted invocations by injecting a counter
into flagged methods. That was removed: `/windchill time` gives the same counts plus per-call time
without an agent, and publishing the counter to the bootstrap loader stopped the JVM serving
application class loader classes from the cache for the rest of the run.

## Commands

All require `windchill.use` (default: op).

| Command                                   | Description                                                 |
|-------------------------------------------|-------------------------------------------------------------|
| `/windchill`                              | State, and a summary of the last capture                    |
| `/windchill capture [seconds] [--inline]` | Open a capture window                                       |
| `/windchill stop`                         | Close the window early and report                           |
| `/windchill report`                       | Findings from the last capture                              |
| `/windchill report write`                 | Full findings with evidence, to a file                      |
| `/windchill plugins`                      | Plugins ranked by what was found against them               |
| `/windchill flags`                        | Recommended JVM flags, AOT and JIT                          |
| `/windchill time [seconds]` / `time stop` | Exact calls and per-call time for flagged methods, no agent |
| `/windchill aot`                          | Cache state, the next step, and classes served per plugin   |
| `/windchill aot finish`                   | End a training run and write its output without stopping    |
| `/windchill aot assemble`                 | Build a cache from a recorded configuration                 |
| `/windchill agent`                        | Attach the optional agent                                   |
| `/windchill rules`                        | The detectors Windchill runs                                |
| `/windchill reload`                       | Re-read config.yml                                          |

`inline` records every inlining decision the JIT makes. It is the highest-volume event the JVM emits
and it is what produces JIT-1, so ask for it on a short window.

## JIT flags

`/windchill flags` only recommends a flag a measurement justifies, reads its current value from the
running JVM, and says what it costs. Targeted flags are preferred over global ones. Every cost below
was measured on Temurin 25.0.3 or read from the JDK 25 source.

- `-XX:CompileCommand=inline,<Class>::<method>` for a hot callee C2 refused as slightly too big,
  instead of raising `FreqInlineSize` for every method in the JVM. It forces the inline into every
  caller in C1 and C2, including cold ones. A misspelled command stops the JVM from starting, and a
  shell silently drops the `$` of a nested class unless the argument is single-quoted.
- `-XX:ReservedCodeCacheSize` when the code cache filled or is close to it, sized at twice the
  measured peak and never above 2048m, where the JVM refuses to start. A full cache stops all new
  compilation, and while its code is still in use nothing is freed, so it can last until restart.
  Committed memory follows use and is never returned. With `-XX:+UseLargePages` on Linux the whole
  reservation is committed at startup.
- `-XX:CICompilerCount` only when the C2 queue stays deep across a window that is not the startup
  capture, on 8 or more cores, and with the code cache not tight. It names the smallest count that
  adds one C2 thread (C1 takes a third of the count). The count is a ceiling: threads start only
  when the queue is deep.

Deliberately not recommended:

- `-XX:-DontCompileHugeMethods`. It has no per-method form, so it applies to every method over
  8000 bytes of bytecode. Measured, it took a hot 8944-byte method from 34% of samples to 4%, and
  one such C2 compile took 30 to 300 ms. HotSpot inlines nothing into a method that size, so the
  compiled result is still slower than a split method. The 32 methods over the limit in Paper and
  its libraries are all one-shot bootstrap code. JIT-8 says to split the method and names the flag
  as a stopgap.
- `-XX:PerMethodRecompilationCutoff`. Reaching it moves a method from C2 to C1 code, not to the
  interpreter, and C2 stops speculating on a trap long before the cutoff. Raising it only adds
  compile and throw-away cycles for a method whose assumption keeps breaking.

## Requirements

- Paper or Folia. Nothing here touches NMS.
- **Java 25 or newer.** The plugin is compiled for 25, reads class files with `java.lang.classfile`
  and times methods with JEP 520. `/windchill aot finish` needs 25.0.3 or later. Everything in this
  repo was verified on Temurin 25.0.3.
- The optional agent needs the server started with:
  ```
  -Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading
  ```
  Without them the attach fails cleanly and everything else keeps working. `EnableDynamicAgentLoading`
  is already on by default in JDK 25, so setting it only silences the warning that a future release
  will turn it off. `allowAttachSelf` lets a JVM attach to itself and opens nothing to other
  processes. `-XX:+DisableAttachMechanism` is the setting that closes attach to other local processes.

## Cost

Nothing runs continuously. A capture window is opened explicitly, is bounded by
`capture.max-window-seconds`, and always carries an auto-stop that fires even if nobody closes it. A
profiler left running is the class of bug this plugin exists to report.

Every aggregation is capped by `limits.*`. Past a cap, further entries are counted and discarded
rather than stored, and the report says how many were dropped.

## Configuration

See `config.yml`. Every key is documented inline, and Windchill merges new keys from the bundled
template on each startup while keeping your values.

The thresholds that decide what counts as hot are the ones worth tuning. Defaults suit a busy server;
an idle test box needs them lower before anything is reported at all.

## When to capture

This matters more than any setting, so it is worth stating plainly.

A window only observes compilations, inlining decisions and deoptimisations that happen **inside it**.
Code compiled before the capture opened is silent for the rest of the JVM's life. A plugin that has
been running for an hour is fully compiled and will produce no inlining evidence at all, no matter
how hot it is.

- **Inlining analysis (JIT-1) only works shortly after a restart**, because that is when
  compilation happens. Windchill takes that capture **automatically on every boot**, so you do not
  have to be at the console for it. The report lands in `reports/`.
- **Everything else works any time.** Dispatch, interpretation, deoptimisation, recompilation and code
  cache findings (JIT-2, JIT-3, JIT-4, JIT-6, JIT-8, VM-1, VM-2) are steady-state signals that keep
  firing for as long as the problem exists.

When a plugin ran during a window but compiled nothing, Windchill says so by name rather than
reporting an empty result, because an empty result reads as a clean bill of health and is not one.

The startup capture is `capture.on-startup` in `config.yml`. It is bounded and auto-stopping like any
other window.

## Limitations

- Attribution by jar scan credits a plugin with libraries it shades under their own package, which is
  usually what you want. The agent resolves ownership by class loader instead.
- On an idle server, the percentages are dominated by the server's own tick-loop idling, because that
  is genuinely what is running. They become meaningful under real load.
- On JDK 25, a compiled method without a loop never showed up in samples as a frame of its own. Its
  time landed on the call instruction in its caller. JIT-1 and JIT-2 measure at that call
  instruction for this reason, which is also where the fix goes.
- `/windchill aot` pauses the server briefly while the JVM lists its classes: about 1 ms for 1,900
  classes in testing.

## Building

```
./gradlew build
```

To exercise the AOT lifecycle against the dev server:

```
./gradlew runServer -PwindchillAot=record    # writes a cache on clean shutdown
./gradlew runServer -PwindchillAot=use       # consumes it
```

## License

Not yet chosen.
