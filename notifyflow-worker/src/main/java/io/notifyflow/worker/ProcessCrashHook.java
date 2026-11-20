package io.notifyflow.worker;

import io.notifyflow.contracts.NotificationEvent;
import java.nio.file.*;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;

@Primary @Profile("failure-test") @org.springframework.stereotype.Component
public class ProcessCrashHook extends CrashHook {
    private final Path marker;
    public ProcessCrashHook(@Value("${notifyflow.crash-marker:/tmp/notifyflow-crashed}") String marker) { this.marker=Path.of(marker); }
    @Override public void afterProvider(NotificationEvent event) {
        if(!event.notification().subject().equals("CRASH_AFTER_PROVIDER")) return;
        try { Files.createFile(marker); } catch(FileAlreadyExistsException alreadyCrashed) { return; }
        catch(Exception error) { throw new IllegalStateException("Crash marker unavailable",error); }
        Runtime.getRuntime().halt(91);
    }
}
