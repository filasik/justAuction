package me.skerik.justauction.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Last-resort, append-only record for situations the database could not absorb. */
public interface RecoveryLog {

    /** An item that could neither be returned to the player nor stored. */
    void writeItem(UUID owner, byte[] item, String why);

    /** Money problem an admin has to look at. */
    void writeIncident(String message);

    /** The money for this listing was already refunded in-process; startup recovery must not refund it again. */
    void markCompensated(long listingId);

    boolean isCompensated(long listingId);

    final class InMemory implements RecoveryLog {
        public final java.util.List<String> incidents = new java.util.concurrent.CopyOnWriteArrayList<>();
        public final java.util.List<UUID> lostItemOwners = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final Set<Long> compensated = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override public void writeItem(UUID owner, byte[] item, String why) { lostItemOwners.add(owner); }
        @Override public void writeIncident(String message) { incidents.add(message); }
        @Override public void markCompensated(long listingId) { compensated.add(listingId); }
        @Override public boolean isCompensated(long listingId) { return compensated.contains(listingId); }
    }

    final class File implements RecoveryLog {
        private final Path dir;
        private final Set<Long> compensated = new HashSet<>();

        public File(Path dir) {
            this.dir = dir;
            try {
                Files.createDirectories(dir);
                Path file = dir.resolve("compensated.txt");
                if (Files.exists(file)) {
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        if (!line.isBlank()) compensated.add(Long.parseLong(line.strip()));
                    }
                }
            } catch (IOException | NumberFormatException e) {
                throw new IllegalStateException("cannot read recovery directory " + dir, e);
            }
        }

        @Override
        public synchronized void writeItem(UUID owner, byte[] item, String why) {
            append(dir.resolve("items.log"), Instant.now() + " owner=" + owner + " why=" + why
                    + " item(base64 ItemStack.serializeAsBytes)=" + Base64.getEncoder().encodeToString(item));
        }

        @Override
        public synchronized void writeIncident(String message) {
            append(dir.resolve("incidents.log"), Instant.now() + " " + message);
        }

        @Override
        public synchronized void markCompensated(long listingId) {
            compensated.add(listingId);
            append(dir.resolve("compensated.txt"), Long.toString(listingId));
        }

        @Override
        public synchronized boolean isCompensated(long listingId) {
            return compensated.contains(listingId);
        }

        private static void append(Path file, String line) {
            try {
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                // Nothing left to fall back on; the caller also logs at SEVERE
                System.err.println("[JustAuction] cannot write recovery file " + file + ": " + e);
            }
        }
    }
}
