package cn.managame.benchmark;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the benchmarks ask of the machine that the JDK does not tell: which of its cores are the fast ones, and the
 * CPU time every process has used. On Windows it asks the system, through the JDK's foreign function API; elsewhere
 * the cores are taken to be alike, and processes are read through {@link ProcessHandle}, which sees all of them on
 * Linux but, on Windows, none of the system's own.
 */
final class Machine {
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    private Machine() {}

    /** The machine's logical CPUs, whatever this process is confined to or told it has. */
    static int cpus() {
        long all = WINDOWS ? Windows.cores().values().stream().reduce(0L, (a, b) -> a | b) : 0;
        return all != 0 ? Long.bitCount(all) : Runtime.getRuntime().availableProcessors();
    }

    /**
     * Confines this process, and so every process it starts from now on, to the machine's fastest cores when it has
     * slower ones, and returns how many logical CPUs those are; 0 when nothing was done, the cores being alike. A
     * JVM started under it may still count every CPU of the machine, so it is told the number
     * ({@code -XX:ActiveProcessorCount}).
     *
     * @throws IllegalStateException when the machine has slower cores and the system refuses to keep off them
     */
    static int useFastestCores() {
        if (!WINDOWS) return 0;
        TreeMap<Integer, Long> cores = Windows.cores();
        if (cores.size() < 2) return 0;
        long fastest = cores.lastEntry().getValue();
        if (!Windows.confine(fastest)) throw new IllegalStateException("Cannot confine the benchmarks to the faster cores");
        return Long.bitCount(fastest);
    }

    /**
     * The CPU nanoseconds every process has used since it started, by pid. Read at both ends of an interval, it tells
     * what each used meanwhile; a process that started in between has only the later reading, one that ended only
     * the earlier.
     */
    static Map<Long, Long> cpuByProcess() {
        if (WINDOWS) return Windows.cpuByProcess();
        Map<Long, Long> cpu = new HashMap<>();
        ProcessHandle.allProcesses().forEach(process -> process.info().totalCpuDuration()
                .ifPresent(used -> cpu.put(process.pid(), used.toNanos())));
        return cpu;
    }

    /** The Windows calls, bound when first used, so that no other system looks for them. */
    private static final class Windows {
        private static final int RELATION_PROCESSOR_CORE = 0, SYSTEM_PROCESS_INFORMATION = 5;
        private static final int STATUS_INFO_LENGTH_MISMATCH = 0xC0000004;
        private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        private static final SymbolLookup NTDLL = SymbolLookup.libraryLookup("ntdll", Arena.global());
        private static final MethodHandle CORES = call(KERNEL32, "GetLogicalProcessorInformationEx", JAVA_INT, ADDRESS, ADDRESS);
        private static final MethodHandle CONFINE = call(KERNEL32, "SetProcessAffinityMask", ADDRESS, JAVA_LONG);
        private static final MethodHandle PROCESSES = call(NTDLL, "NtQuerySystemInformation", JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
        /** The pseudo handle that means the calling process. */
        private static final MemorySegment THIS_PROCESS = MemorySegment.ofAddress(-1);

        /** A function of {@code library} that returns an int, a BOOL or an NTSTATUS. */
        private static MethodHandle call(SymbolLookup library, String name, MemoryLayout... arguments) {
            return Linker.nativeLinker().downcallHandle(library.find(name).orElseThrow(), FunctionDescriptor.of(JAVA_INT, arguments));
        }

        /**
         * The logical CPUs of the cores of each efficiency class, as an affinity mask; a higher class is faster. One
         * class when the cores are alike. Empty on a machine of more than 64 logical CPUs, which Windows splits into
         * groups that one mask cannot name.
         */
        static TreeMap<Integer, Long> cores() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment length = arena.allocate(JAVA_INT);
                CORES.invoke(RELATION_PROCESSOR_CORE, MemorySegment.NULL, length); // fails, saying how much it needs
                MemorySegment cores = arena.allocate(length.get(JAVA_INT, 0), 8);
                if ((int) CORES.invoke(RELATION_PROCESSOR_CORE, cores, length) == 0)
                    throw new IllegalStateException("Cannot read the machine's cores");
                TreeMap<Integer, Long> byClass = new TreeMap<>();
                // SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX: its size at 4; of the core, EfficiencyClass at 9, and the
                // mask of its first GROUP_AFFINITY at 32 and its group at 40.
                for (long at = 0; at < length.get(JAVA_INT, 0); at += cores.get(JAVA_INT, at + 4)) {
                    if (cores.get(JAVA_SHORT, at + 40) != 0) return new TreeMap<>();
                    byClass.merge(cores.get(JAVA_BYTE, at + 9) & 0xFF, cores.get(JAVA_LONG, at + 32), (a, b) -> a | b);
                }
                return byClass;
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }

        static boolean confine(long mask) {
            try {
                return (int) CONFINE.invoke(THIS_PROCESS, mask) != 0;
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }

        /**
         * Every process's kernel and user time, the idle process left out. From the system's own list, which needs no
         * access to a process, so the system's services and other users' programs are in it; opening each process, as
         * {@link ProcessHandle} does, reads about half of them.
         */
        static Map<Long, Long> cpuByProcess() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment needed = arena.allocate(JAVA_INT);
                for (int size = 1 << 20; ; size = needed.get(JAVA_INT, 0) + (1 << 16)) {
                    MemorySegment processes = arena.allocate(size, 8);
                    int status = (int) PROCESSES.invoke(SYSTEM_PROCESS_INFORMATION, processes, size, needed);
                    if (status == STATUS_INFO_LENGTH_MISMATCH) continue;
                    if (status != 0) throw new IllegalStateException("Cannot read the processes: status 0x" + Integer.toHexString(status));
                    Map<Long, Long> cpu = new HashMap<>();
                    // SYSTEM_PROCESS_INFORMATION: the offset of the next at 0, UserTime at 40 and KernelTime at 48,
                    // in units of 100 ns, UniqueProcessId at 80.
                    for (long at = 0; ; at += processes.get(JAVA_INT, at)) {
                        long pid = processes.get(JAVA_LONG, at + 80);
                        if (pid != 0) cpu.put(pid, 100 * (processes.get(JAVA_LONG, at + 40) + processes.get(JAVA_LONG, at + 48)));
                        if (processes.get(JAVA_INT, at) == 0) return cpu;
                    }
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
