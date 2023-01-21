package com.elominp.pod;

import com.sun.jna.Library;
import com.sun.jna.Native;

import javax.management.*;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public class Agent {
  private static final String DELAY_BETWEEN_MONITOR_CYCLES_VARIABLE_NAME = "DELAY_BETWEEN_MONITOR_CYCLES";
  private static final String TRIM_GLIBC_HEAP_VARIABLE_NAME = "TRIM_GLIBC_HEAP";
  private static final String DELAY_BETWEEN_TRIM_GLIBC_HEAP_CYCLES_VARIABLE_NAME = "DELAY_BETWEEN_TRIM_GLIBC_HEAP_CYCLES";
  private static final String CANARY_COMMAND_VARIABLE_NAME = "CANARY_COMMAND";
  private static final String CANARY_MEMORY_SIZE_REQUESTED_TO_RESERVE_VARIABLE_NAME = "CANARY_MEMORY_SIZE_REQUESTED_TO_RESERVE";
  private static final String ENABLE_USE_OF_CANARY_MEMORY_VARIABLE_NAME = "ENABLE_USE_OF_CANARY_MEMORY";
  private static final String RESTART_CANARY_AFTER_OUT_OF_MEMORY_KILL_VARIABLE_NAME = "RESTART_CANARY_AFTER_OUT_OF_MEMORY_KILL";
  private static final String IMMEDIATELY_ATTEMPT_TERMINATION_AFTER_CANARY_IS_KILLED_VARIABLE_NAME = "IMMEDIATELY_ATTEMPT_TERMINATION_AFTER_CANARY_IS_KILLED";

  private static final String DELAY_BETWEEN_MONITOR_CYCLES_DEFAULT_VALUE = "1000";
  private static final String TRIM_GLIBC_HEAP_DEFAULT_VALUE = "true";
  private static final String DELAY_BETWEEN_TRIM_GLIBC_HEAP_CYCLES_DEFAULT_VALUE = "10000";
  private static final String CANARY_COMMAND_DEFAULT_VALUE = "memory-cushion";
  private static final String CANARY_MEMORY_SIZE_REQUESTED_TO_RESERVE_DEFAULT_VALUE = "16777216";
  private static final String ENABLE_USE_OF_CANARY_MEMORY_DEFAULT_VALUE = "false";
  private static final String RESTART_CANARY_AFTER_OUT_OF_MEMORY_KILL_DEFAULT_VALUE = "false";
  private static final String IMMEDIATELY_ATTEMPT_TERMINATION_AFTER_CANARY_IS_KILLED_DEFAULT_VALUE = "true";

  private static final int    SIGKILL_EXIT_VALUE = 137;
  private static final int    SIGTERM_SIGNAL_NUMBER = 15;
  private static final int    MAXIMUM_OOM_SCORE = 1000;
  private static final int    MINIMUM_OOM_SCORE = 0;

  private static final Thread MONITOR_THREAD = new Thread(Agent::monitorResidentMemory);
  private static final long   CURRENT_PROCESS_ID = ProcessHandle.current().pid();

  private static Process      memoryCanaryProcess;
  private static ObjectName   diagnosticCommandObjectName;
  private static MBeanServer  mBeanServer;
  private static long         delayBetweenMonitorCycles;
  private static boolean      trimGlibcHeap;
  private static long         delayBetweenTrimGlibcHeapCycles;
  private static Instant      timeSinceLastTrimGlibcHeapCycle = Instant.now();
  private static String       memoryCanaryCommand;
  private static long         memoryCanarySizeRequestedToReserve;
  private static boolean      enableMemoryCanary;
  private static boolean      restartCanaryAfterOutOfMemoryKill;
  private static boolean      immediatelyAttemptTerminationAfterCanaryIsKilled;

  private interface CLibrary extends Library {
    CLibrary INSTANCE = Native.load("c", CLibrary.class);
  
    int kill(long pid, int signal);
  }

  public static void premain(final String agentArgs, final Instrumentation instrumentation) throws IOException {
    delayBetweenMonitorCycles = Long.parseLong(System.getenv().getOrDefault(DELAY_BETWEEN_MONITOR_CYCLES_VARIABLE_NAME, DELAY_BETWEEN_MONITOR_CYCLES_DEFAULT_VALUE).trim());
    trimGlibcHeap = Boolean.parseBoolean(System.getenv().getOrDefault(TRIM_GLIBC_HEAP_VARIABLE_NAME, TRIM_GLIBC_HEAP_DEFAULT_VALUE).trim().toLowerCase());
    delayBetweenTrimGlibcHeapCycles = Long.parseLong(System.getenv().getOrDefault(DELAY_BETWEEN_TRIM_GLIBC_HEAP_CYCLES_VARIABLE_NAME, DELAY_BETWEEN_TRIM_GLIBC_HEAP_CYCLES_DEFAULT_VALUE).trim().toLowerCase());
    memoryCanaryCommand = System.getenv().getOrDefault(CANARY_COMMAND_VARIABLE_NAME, CANARY_COMMAND_DEFAULT_VALUE).trim();
    memoryCanarySizeRequestedToReserve = Long.parseLong(System.getenv().getOrDefault(CANARY_MEMORY_SIZE_REQUESTED_TO_RESERVE_VARIABLE_NAME, CANARY_MEMORY_SIZE_REQUESTED_TO_RESERVE_DEFAULT_VALUE).trim());
    enableMemoryCanary = Boolean.parseBoolean(System.getenv().getOrDefault(ENABLE_USE_OF_CANARY_MEMORY_VARIABLE_NAME, ENABLE_USE_OF_CANARY_MEMORY_DEFAULT_VALUE).trim().toLowerCase());
    restartCanaryAfterOutOfMemoryKill = Boolean.parseBoolean(System.getenv().getOrDefault(RESTART_CANARY_AFTER_OUT_OF_MEMORY_KILL_VARIABLE_NAME, RESTART_CANARY_AFTER_OUT_OF_MEMORY_KILL_DEFAULT_VALUE).trim().toLowerCase());
    immediatelyAttemptTerminationAfterCanaryIsKilled = Boolean.parseBoolean(System.getenv().getOrDefault(IMMEDIATELY_ATTEMPT_TERMINATION_AFTER_CANARY_IS_KILLED_VARIABLE_NAME, IMMEDIATELY_ATTEMPT_TERMINATION_AFTER_CANARY_IS_KILLED_DEFAULT_VALUE).trim().toLowerCase());

    launchMemoryCanary();
    MONITOR_THREAD.setDaemon(true);
    MONITOR_THREAD.start();
  }

  private static void launchMemoryCanary() throws IOException {
    if (enableMemoryCanary) {
      memoryCanaryProcess = Runtime.getRuntime().exec(new String[]{memoryCanaryCommand, String.valueOf(memoryCanarySizeRequestedToReserve)});
      adjustOutOfMemoryScore(memoryCanaryProcess.pid(), MAXIMUM_OOM_SCORE); // Paint a target on the canary
      adjustOutOfMemoryScore(CURRENT_PROCESS_ID, MINIMUM_OOM_SCORE); // Divert itself from the OoM killer
    }
  }

  private static void monitorResidentMemory() {
    try {
      diagnosticCommandObjectName = new ObjectName("com.sun.management:type=DiagnosticCommand");
      mBeanServer = ManagementFactory.getPlatformMBeanServer();

      while (true) {
        trimGlibcHeapIfNecessary(false);
        checkMemoryCanary();
        Thread.sleep(delayBetweenMonitorCycles);
      }
    } catch (MalformedObjectNameException | InterruptedException | ReflectionException | InstanceNotFoundException |
             MBeanException | IOException exception) {
      System.err.println("[ERROR] Failed to monitor or manage resident memory of the application : " + exception.getMessage());
    }
  }

  private static void trimGlibcHeapIfNecessary(boolean forceTrimGlibcHeap) throws ReflectionException, InstanceNotFoundException, MBeanException {
    if (trimGlibcHeap && (forceTrimGlibcHeap ||
            (Instant.now().toEpochMilli() - delayBetweenTrimGlibcHeapCycles) > timeSinceLastTrimGlibcHeapCycle.toEpochMilli())) {
      mBeanServer.invoke(diagnosticCommandObjectName, "systemTrimNativeHeap", null, null);
      timeSinceLastTrimGlibcHeapCycle = Instant.now();
    }
  }

  private static void checkMemoryCanary() throws IOException, ReflectionException, InstanceNotFoundException, MBeanException {
    if (enableMemoryCanary && !memoryCanaryProcess.isAlive()) {
      System.err.println("[WARNING] Memory canary processed is terminated!");
      if (memoryCanaryProcess.exitValue() == SIGKILL_EXIT_VALUE) {
        System.err.println("[WARNING] Memory canary was forcefully killed, possibly by the OoM killer!");
      }
      System.gc();
      trimGlibcHeapIfNecessary(true);
      restartMemoryCanaryIfNecessary();
      sendTerminationSignalIfNecessary();
    }
  }

  private static void restartMemoryCanaryIfNecessary() throws IOException {
    if (restartCanaryAfterOutOfMemoryKill) {
      launchMemoryCanary();
    }
  }

  private static void sendTerminationSignalIfNecessary() throws IOException {
    if (immediatelyAttemptTerminationAfterCanaryIsKilled) {
      CLibrary.INSTANCE.kill(CURRENT_PROCESS_ID, SIGTERM_SIGNAL_NUMBER);
    }
  }

  private static void adjustOutOfMemoryScore(long pid, int score) throws IOException {
    Files.write(Paths.get(String.format("/proc/%s/oom_score_adj", pid)), (score + "\n").getBytes(), StandardOpenOption.APPEND);
  }
}