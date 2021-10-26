/**
 * Copyright DataStax, Inc 2021.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.cassandra.cdc.producer;

import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * Detect and read commitlogs files in the cdc_raw directory.
 */
@Slf4j
public class CommitLogProcessor extends AbstractProcessor implements AutoCloseable {
    private static final String NAME = "Commit Log Processor";

    private static final Set<WatchEvent.Kind<Path>> watchedEvents = Stream.of(ENTRY_CREATE, ENTRY_MODIFY).collect(Collectors.toSet());

    private final AbstractDirectoryWatcher newCommitLogWatcher;
    private final CommitLogTransfer commitLogTransfer;
    private final File cdcDir;
    private boolean initial = true;

    final CommitLogReaderService commitLogReaderService;
    final SegmentOffsetWriter segmentOffsetWriter;
    final ProducerConfig config;
    final boolean withNearRealTimeCdc;

    public CommitLogProcessor(String cdcLogDir,
                              ProducerConfig config,
                              CommitLogTransfer commitLogTransfer,
                              SegmentOffsetWriter segmentOffsetWriter,
                              CommitLogReaderService commitLogReaderService,
                              boolean withNearRealTimeCdc) throws IOException {
        super(NAME, 0);
        this.config = config;
        this.commitLogReaderService = commitLogReaderService;
        this.commitLogTransfer = commitLogTransfer;
        this.segmentOffsetWriter = segmentOffsetWriter;
        this.withNearRealTimeCdc = withNearRealTimeCdc;
        this.cdcDir = new File(cdcLogDir);
        if (!cdcDir.exists()) {
            if (!cdcDir.mkdir()) {
                throw new IOException("Failed to create " + cdcLogDir);
            }
        }
        this.newCommitLogWatcher = new AbstractDirectoryWatcher(cdcDir.toPath(),
                Duration.ofMillis(config.cdcDirPollIntervalMs),
                watchedEvents) {
            @Override
            void handleEvent(WatchEvent<?> event, Path path) {
                collectCommitlog(path);
            }
        };
    }

    private void collectCommitlog(Path path) {
        if (withNearRealTimeCdc) {
            // for Cassandra 4.x and DSE 6.8.16+ only
            if (path.toString().endsWith("_cdc.idx")) {
                commitLogReaderService.commitLogQueue.add(path.toFile());
            }
        } else {
            if (path.toString().endsWith(".log")) {
                commitLogReaderService.commitLogQueue.add(path.toFile());
            }
        }
    }

    /**
     * Override destroy to clean up resources after stopping the processor
     */
    @Override
    public void close() {
    }

    @Override
    public void process() throws IOException, InterruptedException {

        // load existing sorted commitlogs files when initializing
        if (initial) {
            if (config.errorCommitLogReprocessEnabled) {
                log.debug("Moving back error commitlogs for reprocessing into {}", cdcDir.getAbsolutePath());
                commitLogTransfer.recycleErrorCommitLogFiles(cdcDir.toPath());
            }

            File[] commitLogFiles = CommitLogUtil.getCommitLogs(cdcDir);
            Arrays.sort(commitLogFiles, CommitLogUtil::compareCommitLogs);
            log.debug("Reading existing commit logs in {}, files={}", cdcDir, Arrays.asList(commitLogFiles));
            for (File file : commitLogFiles) {
                collectCommitlog(file.toPath());
            }
            initial = false;
        }

        // collect new segment files
        newCommitLogWatcher.poll();
    }
}
