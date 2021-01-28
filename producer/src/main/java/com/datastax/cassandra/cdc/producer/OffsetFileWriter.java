/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.datastax.cassandra.cdc.producer;

import com.datastax.cassandra.cdc.Metrics;
import com.datastax.cassandra.cdc.producer.exceptions.CassandraConnectorConfigException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.cassandra.db.commitlog.CommitLogPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.io.*;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;

@Singleton
public class OffsetFileWriter implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(OffsetFileWriter.class);

    public static final String COMMITLOG_OFFSET_FILE = "commitlog_offset.dat";

    private final File offsetFile;
    private final CassandraCdcConfiguration config;
    private final MeterRegistry meterRegistry;
    private AtomicReference<CommitLogPosition> fileOffsetRef = new AtomicReference<>(new CommitLogPosition(0,0));

    private final OffsetFlushPolicy offsetFlushPolicy;
    volatile long timeOfLastFlush = System.currentTimeMillis();
    volatile Long notCommittedEvents = 0L;

    public OffsetFileWriter(CassandraCdcConfiguration config,
                            MeterRegistry meterRegistry) throws IOException {
        if (config.offsetBackingStoreDir == null) {
            throw new CassandraConnectorConfigException("Offset file directory must be configured at the start");
        }
        this.config = config;
        this.offsetFlushPolicy = new OffsetFlushPolicy.AlwaysFlushOffsetPolicy();
        this.meterRegistry = meterRegistry;
        this.meterRegistry.gauge("committed_segment", fileOffsetRef, new ToDoubleFunction<AtomicReference<CommitLogPosition>>() {
            @Override
            public double applyAsDouble(AtomicReference<CommitLogPosition> offsetPositionRef) {
                return offsetPositionRef.get().segmentId;
            }
        });
        this.meterRegistry.gauge("committed_position", fileOffsetRef, new ToDoubleFunction<AtomicReference<CommitLogPosition>>() {
            @Override
            public double applyAsDouble(AtomicReference<CommitLogPosition> offsetPositionRef) {
                return offsetPositionRef.get().position;
            }
        });
        this.offsetFile = new File(config.offsetBackingStoreDir, COMMITLOG_OFFSET_FILE);
        init();
    }

    public CommitLogPosition offset() {
        return this.fileOffsetRef.get();
    }

    public void markOffset(String sourceTable, CommitLogPosition sourceOffset) {
        this.fileOffsetRef.set(sourceOffset);
    }

    public void flush() throws IOException {
        saveOffset();
    }

    @Override
    public void close() throws IOException {
        saveOffset();
    }

    public static String serializePosition(CommitLogPosition commitLogPosition) {
        return Long.toString(commitLogPosition.segmentId) + File.pathSeparatorChar + Integer.toString(commitLogPosition.position);
    }

    public static CommitLogPosition deserializePosition(String s) {
        String[] segAndPos = s.split(Character.toString(File.pathSeparatorChar));
        return new CommitLogPosition(Long.parseLong(segAndPos[0]), Integer.parseInt(segAndPos[1]));
    }

    private synchronized void saveOffset() throws IOException {
        try(FileWriter out = new FileWriter(this.offsetFile)) {
            out.write(serializePosition(fileOffsetRef.get()));
        } catch (IOException e) {
            logger.error("Failed to save offset for file " + offsetFile.getName(), e);
            throw e;
        }
    }

    private synchronized void loadOffset() throws IOException {
        try(BufferedReader br = new BufferedReader(new FileReader(offsetFile)))
        {
            fileOffsetRef.set(deserializePosition(br.readLine()));
            logger.debug("file offset={}", fileOffsetRef.get());
        } catch (IOException e) {
            logger.error("Failed to load offset for file " + offsetFile.getName(), e);
            throw e;
        }
    }

    private synchronized void init() throws IOException {
        if (offsetFile.exists()) {
            loadOffset();
        } else {
            Files.createDirectories( new File(config.offsetBackingStoreDir).toPath());
            saveOffset();
        }
    }

    void maybeCommitOffset(Mutation record) {
        try {
            long now = System.currentTimeMillis();
            long timeSinceLastFlush = now - timeOfLastFlush;
            if(offsetFlushPolicy.shouldFlush(Duration.ofMillis(timeSinceLastFlush), notCommittedEvents)) {
                SourceInfo source = record.getSource();
                markOffset(source.keyspaceTable.name(), source.commitLogPosition);
                flush();
                this.meterRegistry.counter(Metrics.METRICS_PREFIX + "commit").increment();
                this.meterRegistry.counter(Metrics.METRICS_PREFIX + "committed").increment(notCommittedEvents);
                notCommittedEvents = 0L;
                timeOfLastFlush = now;
                logger.debug("Offset flushed source=" + source);
            }
        } catch(IOException e) {
            logger.warn("error:", e);
        }
    }
}
