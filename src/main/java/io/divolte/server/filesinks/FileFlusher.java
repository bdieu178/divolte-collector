/*
 * Copyright 2018 GoDataDriven B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.divolte.server.filesinks;

import static io.divolte.server.processing.ItemProcessor.ProcessingDirective.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.ParametersAreNonnullByDefault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import io.divolte.server.AvroRecordBuffer;
import io.divolte.server.config.FileStrategyConfiguration;
import io.divolte.server.filesinks.FileManager.DivolteFile;
import io.divolte.server.processing.Item;
import io.divolte.server.processing.ItemProcessor;

@ParametersAreNonnullByDefault
public class FileFlusher implements ItemProcessor<AvroRecordBuffer> {
    private static final Logger logger = LoggerFactory.getLogger(FileFlusher.class);

    private final static long DEFAULT_FILE_SYSTEM_RECONNECT_DELAY_NANOS = 15000 * 1000000L;
    private final long reconnectDelayNanos;

    private final static AtomicInteger INSTANCE_COUNTER = new AtomicInteger();
    private final int instanceNumber;
    private final String hostString;
    private final DateFormat datePartFormat = new SimpleDateFormat("yyyyLLddHHmmss");

    private final long syncEveryNanos;
    private final int syncEveryRecords;
    private final long newFileEveryNanos;

    private final FileManager manager;

    /*
     * We use a single non-final field to keep track of the currently writable file.
     * The file system is considered unhealthy / unwritable when equal to
     * Optional.empty() and healthy otherwise. The file system is marked unhealthy
     * on any IOException that is thrown by the used file manager for this sink.
     * File system recovery is attempted on heart beats of the processor. Processing
     * is paused as soon as the file system goes into unhealthy state. As a result,
     * process(...) can assume the Optional to be populated; all other methods need
     * to check for file system health by inspecting the state of the optional
     * before performing any operation. The lastFixAttemptNanoTime field is used to
     * keep track of the time of the recent most reconnect attempt in order to
     * implement a back off larger than the heart beat frequency.
     */
    private Optional<TrackedFile> currentTrackedFile;
    private long lastFixAttemptNanoTime;

    public FileFlusher(final FileStrategyConfiguration configuration, final FileManager manager) {
        this(configuration, manager, DEFAULT_FILE_SYSTEM_RECONNECT_DELAY_NANOS);
    }

    public FileFlusher(final FileStrategyConfiguration configuration, final FileManager manager, final long reconnectDelayNanos) {
        /*
         * Constructor with configurable reconnect delay for testability.
         */
        this.reconnectDelayNanos = reconnectDelayNanos;

        syncEveryNanos = configuration.syncFileAfterDuration.toNanos();
        syncEveryRecords = configuration.syncFileAfterRecords;
        newFileEveryNanos = configuration.rollEvery.toNanos();

        instanceNumber = INSTANCE_COUNTER.incrementAndGet();
        hostString = findLocalHostName();

        this.manager = Objects.requireNonNull(manager);

        try {
            currentTrackedFile = Optional.of(new TrackedFile(manager.createFile(newFileName())));
        } catch(final IOException ioe) {
            // Postpone throwing the exception to force going into heartbeat / recover
            // cycle. Potentially drops a record too many, but avoids a additional branch in
            // process(...).
            currentTrackedFile = Optional.of(brokenTrackedFile(ioe));
        }
    }

    @Override
    public ProcessingDirective process(final Item<AvroRecordBuffer> item) {
        final long nanoTime = System.nanoTime();
        try {
            final TrackedFile trackedFile = currentTrackedFile.orElseThrow(IllegalStateException::new);
            trackedFile.divolteFile.append(item.payload);
            trackedFile.recordsSinceLastSync += 1;

            possiblySyncAndOrRoll(nanoTime);

            return CONTINUE;
        } catch(final IOException ioe) {
            markFileSystemUnavailable(nanoTime);
            logger.error("File system connection error. Marking file system as unavailable. Attempting reconnect after " + reconnectDelayNanos + " ns.", ioe);

            return PAUSE;
        }
    }

    @Override
    public ProcessingDirective heartbeat() {
        final long nanoTime = System.nanoTime();

        return currentTrackedFile
                .map(trackedFile -> handleHeartbeatWithHealthyFileSystem(nanoTime))
                .orElseGet(() -> nanoTime - lastFixAttemptNanoTime > reconnectDelayNanos ? attemptRecovery(nanoTime) : PAUSE);
    }

    private ProcessingDirective handleHeartbeatWithHealthyFileSystem(final long nanoTime) {
        try {
            possiblySyncAndOrRoll(nanoTime);
            return CONTINUE;
        } catch (final IOException e) {
            markFileSystemUnavailable(nanoTime);
            logger.error("File system connection error. Marking file system as unavailable. Attempting reconnect after "
                    + reconnectDelayNanos + " ns.", e);
            return PAUSE;
        }
    }

    private ProcessingDirective attemptRecovery(final long nanoTime) {
        logger.info("Attempting file system reconnect.");
        try {
            final TrackedFile trackedFile = new TrackedFile(manager.createFile(newFileName()));
            currentTrackedFile = Optional.of(trackedFile);
            logger.info("Recovered file system connection when creating file: {}", trackedFile);
            return CONTINUE;
        } catch (final IOException e) {
            logger.error("File system connection error. Marking file system as unavailable. Attempting reconnect after "
                    + reconnectDelayNanos + " ns.", e);
            markFileSystemUnavailable(nanoTime);
            return PAUSE;
        }
    }

    @Override
    public void cleanup() {
        currentTrackedFile.ifPresent(trackedFile -> {
            try {
                if (trackedFile.totalRecords + trackedFile.recordsSinceLastSync > 0) {
                    trackedFile.divolteFile.closeAndPublish();
                } else {
                    trackedFile.divolteFile.discard();
                }
            } catch (final IOException ioe) {
                logger.error("Failed to close and publish file " + trackedFile + " during cleanup.", ioe);
            }
        });
    }

    private void markFileSystemUnavailable(final long nanoTime) {
        lastFixAttemptNanoTime = nanoTime;
        discardCurrentTrackedFileQuietly();
        currentTrackedFile = Optional.empty();
    }

    private void possiblySyncAndOrRoll(final long nanoTime) throws IOException {
        final TrackedFile trackedFile = currentTrackedFile.orElseThrow(IllegalStateException::new);
        if (nanoTime > trackedFile.projectedCloseNanoTime) {
            // roll file
            if (trackedFile.totalRecords > 0) {
                // Assumes closeAndPublish performs an implicit sync / flush of any remaining
                // internal buffers. There no additional sync call here, as file manager
                // implementations may have more optimal ways of performing a sync + close +
                // move on the file in one go.
                logger.debug("Rolling file: {}", trackedFile);
                trackedFile.divolteFile.closeAndPublish();
            } else {
                logger.debug("Discarding empty file: {}", trackedFile);
                trackedFile.divolteFile.discard();
            }

            currentTrackedFile = Optional.of(new TrackedFile(manager.createFile(newFileName())));
        } else if (trackedFile.recordsSinceLastSync >= syncEveryRecords ||
                nanoTime - trackedFile.lastSyncNanoTime >= syncEveryNanos && trackedFile.recordsSinceLastSync > 0) {
            // sync
            sync(nanoTime, trackedFile);
        } else if (trackedFile.recordsSinceLastSync == 0) {
            // if nothing was written and we didn't roll, reset sync timing
            trackedFile.lastSyncNanoTime = nanoTime;
        }
    }

    private void sync(final long nanoTime, final TrackedFile trackedFile) throws IOException {
        logger.debug("Syncing file: {}", trackedFile.divolteFile);
        trackedFile.divolteFile.sync();
        trackedFile.totalRecords += trackedFile.recordsSinceLastSync;
        trackedFile.recordsSinceLastSync = 0;
        trackedFile.lastSyncNanoTime = nanoTime;
    }

    private final class TrackedFile {
        final long openNanoTime;
        final long projectedCloseNanoTime;

        final DivolteFile divolteFile;

        long lastSyncNanoTime;
        int recordsSinceLastSync;
        long totalRecords;


        public TrackedFile(final DivolteFile file) {
            this.divolteFile = file;

            this.openNanoTime = this.lastSyncNanoTime = System.nanoTime();
            this.recordsSinceLastSync = 0;
            this.totalRecords = 0;
            this.projectedCloseNanoTime = openNanoTime + newFileEveryNanos;
        }

        @Override
        public String toString() {
            return MoreObjects
                .toStringHelper(getClass())
                .add("file", divolteFile.toString())
                .add("open nanotime", openNanoTime)
                .add("last sync nanotime", lastSyncNanoTime)
                .add("records since last sync", recordsSinceLastSync)
                .add("total records", totalRecords)
                .toString();
        }
    }

    private void discardCurrentTrackedFileQuietly() {
        currentTrackedFile.ifPresent(trackedFile -> {
            try {
                trackedFile.divolteFile.discard();
            } catch (final IOException e) {
                logger.warn("Failed to discard / delete file: " + trackedFile);
            }
        });
    }

    private String newFileName() {
        return String.format("%s-divolte-tracking-%s-%d.avro", datePartFormat.format(new Date()), hostString, instanceNumber);
    }

    private static String findLocalHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (final UnknownHostException e) {
            return "localhost";
        }
    }

    private TrackedFile brokenTrackedFile(final IOException error) {
        return new TrackedFile(new DivolteFile() {
            @Override
            public void closeAndPublish() throws IOException {
                throw error;
            }

            @Override
            public void append(final AvroRecordBuffer buffer) throws IOException {
                throw error;
            }

            @Override
            public void sync() throws IOException {
                throw error;
            }

            @Override
            public void discard() throws IOException {
                throw error;
            }
        });
    }
}
