/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 
package org.apache.xtable.delta;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.Builder;
import lombok.extern.log4j.Log4j2;

import org.apache.spark.sql.SparkSession;

import org.apache.spark.sql.delta.DeltaHistoryManager;
import org.apache.spark.sql.delta.DeltaLog;
import org.apache.spark.sql.delta.Snapshot;
import org.apache.spark.sql.delta.actions.Action;
import org.apache.spark.sql.delta.actions.AddFile;
import org.apache.spark.sql.delta.actions.RemoveFile;

import scala.Option;

import io.delta.tables.DeltaTable;

import org.apache.xtable.exception.ReadException;
import org.apache.xtable.model.CommitsBacklog;
import org.apache.xtable.model.InstantsForIncrementalSync;
import org.apache.xtable.model.InternalSnapshot;
import org.apache.xtable.model.InternalTable;
import org.apache.xtable.model.TableChange;
import org.apache.xtable.model.schema.InternalSchema;
import org.apache.xtable.model.storage.FileFormat;
import org.apache.xtable.model.storage.InternalDataFile;
import org.apache.xtable.model.storage.InternalFilesDiff;
import org.apache.xtable.model.storage.PartitionFileGroup;
import org.apache.xtable.spi.extractor.ConversionSource;
import org.apache.xtable.spi.extractor.DataFileIterator;

@Log4j2
@Builder
public class DeltaConversionSource implements ConversionSource<Long> {
  @Builder.Default
  private final DeltaDataFileExtractor dataFileExtractor = DeltaDataFileExtractor.builder().build();

  @Builder.Default
  private final DeltaActionsConverter actionsConverter = DeltaActionsConverter.getInstance();

  @Builder.Default
  private final DeltaTableExtractor tableExtractor = DeltaTableExtractor.builder().build();

  private Optional<DeltaIncrementalChangesState> deltaIncrementalChangesState = Optional.empty();

  private final SparkSession sparkSession;
  private final DeltaLog deltaLog;
  private final DeltaTable deltaTable;
  private final String tableName;
  private final String basePath;

  @Override
  public InternalTable getTable(Long version) {
    return tableExtractor.table(deltaLog, tableName, version);
  }

  @Override
  public InternalTable getCurrentTable() {
    DeltaLog deltaLog = DeltaLog.forTable(sparkSession, basePath);
    Snapshot snapshot = deltaLog.snapshot();
    return getTable(snapshot.version());
  }

  @Override
  public InternalSnapshot getCurrentSnapshot() {
    DeltaLog deltaLog = DeltaLog.forTable(sparkSession, basePath);
    Snapshot snapshot = deltaLog.snapshot();
    InternalTable table = getTable(snapshot.version());
    return InternalSnapshot.builder()
        .table(table)
        .partitionedDataFiles(getInternalDataFiles(snapshot, table.getReadSchema()))
        .sourceIdentifier(getCommitIdentifier(snapshot.version()))
        .build();
  }

  @Override
  public TableChange getTableChangeForCommit(Long versionNumber) {
    InternalTable tableAtVersion = tableExtractor.table(deltaLog, tableName, versionNumber);
    List<Action> actionsForVersion = getChangesState().getActionsForVersion(versionNumber);
    Snapshot snapshotAtVersion = deltaLog.getSnapshotAt(versionNumber, Option.empty());
    FileFormat fileFormat =
        actionsConverter.convertToFileFormat(snapshotAtVersion.metadata().format().provider());

    // All 3 of the following data structures use data file's absolute path as the key
    Map<String, InternalDataFile> addedFiles = new HashMap<>();
    Map<String, InternalDataFile> removedFiles = new HashMap<>();
    // Set of data file paths for which deletion vectors exists.
    Set<String> deletionVectors = new HashSet<>();

    for (Action action : actionsForVersion) {
      if (action instanceof AddFile) {
        InternalDataFile dataFile =
            actionsConverter.convertAddActionToInternalDataFile(
                (AddFile) action,
                snapshotAtVersion,
                fileFormat,
                tableAtVersion.getPartitioningFields(),
                tableAtVersion.getReadSchema().getFields(),
                true,
                DeltaPartitionExtractor.getInstance(),
                DeltaStatsExtractor.getInstance());
        addedFiles.put(dataFile.getPhysicalPath(), dataFile);
        String deleteVectorPath =
            actionsConverter.extractDeletionVectorFile(snapshotAtVersion, (AddFile) action);
        if (deleteVectorPath != null) {
          deletionVectors.add(deleteVectorPath);
        }
      } else if (action instanceof RemoveFile) {
        InternalDataFile dataFile =
            actionsConverter.convertRemoveActionToInternalDataFile(
                (RemoveFile) action,
                snapshotAtVersion,
                fileFormat,
                tableAtVersion.getPartitioningFields(),
                DeltaPartitionExtractor.getInstance());
        removedFiles.put(dataFile.getPhysicalPath(), dataFile);
      }
    }

    // In Delta Lake if delete vector information is added for an existing data file, as a result of
    // a delete operation, then a new RemoveFile action is added to the commit log to remove the old
    // entry which is replaced by a new entry, AddFile with delete vector information. Since the
    // same data file is removed and added, we need to remove it from the added and removed file
    // maps which are used to track actual added and removed data files.
    for (String deletionVector : deletionVectors) {
      // validate that a Remove action is also added for the data file
      if (removedFiles.containsKey(deletionVector)) {
        addedFiles.remove(deletionVector);
        removedFiles.remove(deletionVector);
      } else {
        log.warn(
            "No Remove action found for the data file for which deletion vector is added {}. This is unexpected.",
            deletionVector);
      }
    }

    InternalFilesDiff internalFilesDiff =
        InternalFilesDiff.builder()
            .filesAdded(addedFiles.values())
            .filesRemoved(removedFiles.values())
            .build();
    return TableChange.builder()
        .tableAsOfChange(tableAtVersion)
        .filesDiff(internalFilesDiff)
        .sourceIdentifier(getCommitIdentifier(versionNumber))
        .build();
  }

  @Override
  public CommitsBacklog<Long> getCommitsBacklog(
      InstantsForIncrementalSync instantsForIncrementalSync) {
    DeltaHistoryManager.Commit deltaCommitAtLastSyncInstant =
        deltaLog
            .history()
            .getActiveCommitAtTime(
                Timestamp.from(instantsForIncrementalSync.getLastSyncInstant()), true, false, true);
    long versionNumberAtLastSyncInstant = deltaCommitAtLastSyncInstant.version();
    resetState(versionNumberAtLastSyncInstant + 1);
    return CommitsBacklog.<Long>builder()
        .commitsToProcess(getChangesState().getVersionsInSortedOrder())
        .build();
  }

  /*
   * In Delta Lake, each commit is a self-describing one i.e. it contains list of new files while
   * also containing list of files that were deleted. So, vacuum has no special effect on the
   * incremental sync. Hence, existence of commit is the only check required.
   */
  @Override
  public boolean isIncrementalSyncSafeFrom(Instant instant) {
    DeltaHistoryManager.Commit deltaCommitAtOrBeforeInstant =
        deltaLog.history().getActiveCommitAtTime(Timestamp.from(instant), true, false, true);
    // There is a chance earliest commit of the table is returned if the instant is before the
    // earliest commit of the table, hence the additional check.
    Instant deltaCommitInstant = Instant.ofEpochMilli(deltaCommitAtOrBeforeInstant.getTimestamp());
    return deltaCommitInstant.equals(instant) || deltaCommitInstant.isBefore(instant);
  }

  @Override
  public String getCommitIdentifier(Long commit) {
    return String.valueOf(commit);
  }

  private DeltaIncrementalChangesState getChangesState() {
    return deltaIncrementalChangesState.orElseThrow(
        () -> new IllegalStateException("DeltaIncrementalChangesState is not initialized"));
  }

  private void resetState(long versionToStartFrom) {
    deltaIncrementalChangesState =
        Optional.of(
            DeltaIncrementalChangesState.builder()
                .deltaLog(deltaLog)
                .versionToStartFrom(versionToStartFrom)
                .build());
  }

  private List<PartitionFileGroup> getInternalDataFiles(Snapshot snapshot, InternalSchema schema) {
    try (DataFileIterator fileIterator = dataFileExtractor.iterator(snapshot, schema)) {
      List<InternalDataFile> dataFiles = new ArrayList<>();
      fileIterator.forEachRemaining(dataFiles::add);
      return PartitionFileGroup.fromFiles(dataFiles);
    } catch (Exception e) {
      throw new ReadException("Failed to iterate through Delta data files", e);
    }
  }

  @Override
  public void close() {
    // nothing to close
  }
}
