// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.indexing;

import com.intellij.diagnostic.PerformanceWatcher;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.ControlFlowException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.impl.ProgressSuspender;
import com.intellij.openapi.project.DumbModeTask;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ExceptionUtil;
import com.intellij.util.SystemProperties;
import com.intellij.util.gist.GistManager;
import com.intellij.util.gist.GistManagerImpl;
import com.intellij.util.indexing.contentQueue.IndexUpdateRunner;
import com.intellij.util.indexing.diagnostic.IndexDiagnosticDumper;
import com.intellij.util.indexing.diagnostic.ProjectDumbIndexingHistoryImpl;
import com.intellij.util.indexing.diagnostic.ProjectIndexingHistoryImpl;
import com.intellij.util.indexing.diagnostic.ScanningType;
import com.intellij.util.indexing.roots.IndexableFilesIterator;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.time.Instant;
import java.util.*;

/**
 * UnindexedFilesIndexer is to index files: explicitly provided (see providerToFiles in constructor), and implicitly marked as dirty, e.g.
 * by VFS (as reported by FileBasedIndexImpl#getFilesToUpdate).
 */
final class UnindexedFilesIndexer extends DumbModeTask {
  private static final Logger LOG = Logger.getInstance(UnindexedFilesIndexer.class);
  private final @NotNull Project myProject;
  private final FileBasedIndexImpl myIndex;
  private final @NotNull Map<@NotNull IndexableFilesIterator, @NotNull Collection<@NotNull VirtualFile>> providerToFiles;
  private final @NonNls @NotNull String indexingReason;
  private final @NotNull LongSet scanningIds;

  UnindexedFilesIndexer(@NotNull Project project,
                        @NonNls @NotNull String indexingReason) {
    this(project, Collections.emptyMap(), indexingReason, LongSets.emptySet());
  }

  /**
   * if providerToFiles is empty, only FileBasedIndexImpl#getFilesToUpdate files will be indexed.
   * <p>
   * if providerToFiles is not empty, providerToFiles files will be indexed in the first order, then files reported by FileBasedIndexImpl#getFilesToUpdate
   */
  UnindexedFilesIndexer(@NotNull Project project,
                        @NotNull Map<@NotNull IndexableFilesIterator, @NotNull Collection<@NotNull VirtualFile>> providerToFiles,
                        @NonNls @NotNull String indexingReason,
                        @NotNull LongSet scanningIds) {
    myProject = project;
    myIndex = (FileBasedIndexImpl)FileBasedIndex.getInstance();
    this.providerToFiles = providerToFiles;
    this.indexingReason = indexingReason;
    this.scanningIds = scanningIds;
  }

  void indexFiles(@NotNull ProjectIndexingHistoryImpl projectIndexingHistory,
                  @NotNull ProjectDumbIndexingHistoryImpl projectDumbIndexingHistory,
                  @NotNull ProgressIndicator indicator) {
    if (SystemProperties.getBooleanProperty("idea.indexes.pretendNoFiles", false)) {
      LOG.info("Finished for " + myProject.getName() + ". System property 'idea.indexes.pretendNoFiles' is enabled.");
      return;
    }

    projectDumbIndexingHistory.setScanningIds(scanningIds);

    PerformanceWatcher.Snapshot snapshot = PerformanceWatcher.takeSnapshot();

    ProgressIndicator poweredIndicator =
      PoweredProgressIndicator.wrap(indicator, getPowerForSmoothProgressIndicator());
    poweredIndicator.setIndeterminate(false);
    poweredIndicator.setFraction(0);
    poweredIndicator.setText(IndexingBundle.message("progress.indexing.updating"));

    projectIndexingHistory.startStage(ProjectIndexingHistoryImpl.Stage.Indexing, Instant.now());
    try {
      doIndexFiles(projectIndexingHistory, projectDumbIndexingHistory, poweredIndicator);
    }
    finally {
      projectIndexingHistory.stopStage(ProjectIndexingHistoryImpl.Stage.Indexing, Instant.now());
    }

    LOG.info(
      snapshot.getLogResponsivenessSinceCreationMessage("Finished for " + myProject.getName() + ". Unindexed files update"));
  }

  private void doIndexFiles(@NotNull ProjectIndexingHistoryImpl projectIndexingHistory,
                            @NotNull ProjectDumbIndexingHistoryImpl projectDumbIndexingHistory,
                            @NotNull ProgressIndicator progressIndicator) {
    int numberOfIndexingThreads = UnindexedFilesUpdater.getNumberOfIndexingThreads();
    LOG.info(
      "Use " + numberOfIndexingThreads + " indexing " + StringUtil.pluralize("thread", numberOfIndexingThreads) +
      " for indexing of " + myProject.getName());
    IndexUpdateRunner indexUpdateRunner = new IndexUpdateRunner(myIndex, numberOfIndexingThreads);

    List<IndexUpdateRunner.FileSet> fileSets = getExplicitlyRequestedFilesSets();
    if (!fileSets.isEmpty()) {
      doIndexFiles(projectIndexingHistory, projectDumbIndexingHistory, progressIndicator, indexUpdateRunner, fileSets);
    }

    // Order is important: getRefreshedFiles may return some subset of getExplicitlyRequestedFilesSets files (e.g. new files)
    // We first index explicitly requested files, this will also mark indexed files as "up-to-date", then we index remaining dirty files
    fileSets = getRefreshedFiles(projectIndexingHistory, projectDumbIndexingHistory);
    if (!fileSets.isEmpty()) {
      doIndexFiles(projectIndexingHistory, projectDumbIndexingHistory, progressIndicator, indexUpdateRunner, fileSets);
    }
  }

  private List<IndexUpdateRunner.FileSet> getRefreshedFiles(@NotNull ProjectIndexingHistoryImpl projectIndexingHistory,
                                                            @NotNull ProjectDumbIndexingHistoryImpl projectDumbIndexingHistory) {
    String filesetName = "Refreshed files";
    Collection<VirtualFile> files =
      new ProjectChangedFilesScanner(myProject).scan(projectIndexingHistory, projectDumbIndexingHistory, filesetName);
    return Collections.singletonList(new IndexUpdateRunner.FileSet(myProject, filesetName, files));
  }

  @NotNull
  private List<IndexUpdateRunner.FileSet> getExplicitlyRequestedFilesSets() {
    ArrayList<IndexableFilesIterator> providers = new ArrayList<>(providerToFiles.keySet());
    List<IndexUpdateRunner.FileSet> fileSets = new ArrayList<>();
    for (IndexableFilesIterator provider : providers) {
      Collection<VirtualFile> providerFiles = providerToFiles.getOrDefault(provider, Collections.emptyList());
      if (!providerFiles.isEmpty()) {
        String progressText = provider.getIndexingProgressText();
        fileSets.add(new IndexUpdateRunner.FileSet(myProject, provider.getDebugName(), providerFiles, progressText));
      }
    }
    return fileSets;
  }

  private void doIndexFiles(@NotNull ProjectIndexingHistoryImpl projectIndexingHistory,
                            @NotNull ProjectDumbIndexingHistoryImpl projectDumbIndexingHistory,
                            @NotNull ProgressIndicator progressIndicator,
                            IndexUpdateRunner indexUpdateRunner,
                            List<IndexUpdateRunner.FileSet> fileSets) {
    IndexUpdateRunner.IndexingInterruptedException exception = null;
    try {
      indexUpdateRunner.indexFiles(myProject, fileSets, progressIndicator, projectIndexingHistory, projectDumbIndexingHistory);
    }
    catch (IndexUpdateRunner.IndexingInterruptedException e) {
      exception = e;
    }

    try {
      fileSets.forEach(b -> {
        projectIndexingHistory.addProviderStatistics(b.statistics);
        projectDumbIndexingHistory.addProviderStatistics(b.statistics);
      });
    }
    catch (Exception e) {
      LOG.error("Failed to add indexing statistics", e);
    }

    if (exception != null) {
      ExceptionUtil.rethrow(exception.getCause());
    }
  }

  @Override
  public void performInDumbMode(@NotNull ProgressIndicator indicator) {
    myIndex.loadIndexes(); // make sure that indexes are loaded, because we can get here without scanning (e.g. from VFS refresh)
    if (!IndexInfrastructure.hasIndices()) {
      return;
    }
    ProjectIndexingHistoryImpl projectIndexingHistory = new ProjectIndexingHistoryImpl(myProject, indexingReason, ScanningType.REFRESH);
    ProjectDumbIndexingHistoryImpl projectDumbIndexingHistory = new ProjectDumbIndexingHistoryImpl(myProject);
    IndexDiagnosticDumper.getInstance().onIndexingStarted(projectIndexingHistory);
    IndexDiagnosticDumper.getInstance().onDumbIndexingStarted(projectDumbIndexingHistory);
    ProgressSuspender suspender = ProgressSuspender.getSuspender(indicator);
    if (suspender != null) {
      ApplicationManager.getApplication().getMessageBus().connect(this)
        .subscribe(ProgressSuspender.TOPIC, new ProgressSuspender.SuspenderListener() {
          @Override
          public void suspendedStatusChanged(@NotNull ProgressSuspender changedSuspender) {
            if (suspender == changedSuspender) {
              Instant now = Instant.now();
              if (suspender.isSuspended()) {
                projectIndexingHistory.suspendStages(now);
                projectDumbIndexingHistory.suspendStages(now);
              }
              else {
                projectIndexingHistory.stopSuspendingStages(now);
                projectDumbIndexingHistory.stopSuspendingStages(now);
              }
            }
          }
        });
    }

    try {
      ((GistManagerImpl)GistManager.getInstance()).
        runWithMergingDependentCacheInvalidations(() -> indexFiles(projectIndexingHistory, projectDumbIndexingHistory, indicator));
    }
    catch (Throwable e) {
      projectIndexingHistory.setWasInterrupted(true);
      projectDumbIndexingHistory.setWasInterrupted();
      if (e instanceof ControlFlowException) {
        LOG.info("Cancelled indexing of " + myProject.getName());
      }
      throw e;
    }
    finally {
      projectIndexingHistory.finishTotalUpdatingTime();
      IndexDiagnosticDumper.getInstance().onIndexingFinished(projectIndexingHistory);
      IndexDiagnosticDumper.getInstance().onDumbIndexingFinished(projectDumbIndexingHistory);
    }
  }

  @Override
  public @Nullable UnindexedFilesIndexer tryMergeWith(@NotNull DumbModeTask taskFromQueue) {
    if (!(taskFromQueue instanceof UnindexedFilesIndexer otherIndexingTask)) return null;

    Map<IndexableFilesIterator, Collection<VirtualFile>> largeMap =
      otherIndexingTask.providerToFiles.size() > providerToFiles.size() ? otherIndexingTask.providerToFiles : providerToFiles;
    Map<IndexableFilesIterator, Collection<VirtualFile>> smallMap =
      largeMap == providerToFiles ? otherIndexingTask.providerToFiles : providerToFiles;

    Map<IndexableFilesIterator, Collection<VirtualFile>> mergedFilesToIndex = new HashMap<>(largeMap);
    for (Map.Entry<IndexableFilesIterator, Collection<VirtualFile>> e : smallMap.entrySet()) {
      Collection<VirtualFile> mergedList;
      if (mergedFilesToIndex.containsKey(e.getKey())) {
        // merge virtual files removing duplicates
        Set<VirtualFile> mergedSet = new HashSet<>(mergedFilesToIndex.get(e.getKey()));
        mergedSet.addAll(e.getValue());
        mergedList = new ArrayList<>(mergedSet);
      } else {
        mergedList = e.getValue();
      }
      mergedFilesToIndex.put(e.getKey(), mergedList);
    }

    String mergedReason = mergeReasons(otherIndexingTask);
    LongArraySet ids = new LongArraySet(scanningIds.size() + otherIndexingTask.scanningIds.size());
    ids.addAll(scanningIds);
    ids.addAll(otherIndexingTask.scanningIds);
    return new UnindexedFilesIndexer(myProject, mergedFilesToIndex, mergedReason, ids);
  }

  @NotNull
  private String mergeReasons(@NotNull UnindexedFilesIndexer otherIndexingTask) {
    String trimmedReason = StringUtil.trimStart(indexingReason, "Merged ");
    String trimmedOtherReason = StringUtil.trimStart(otherIndexingTask.indexingReason, "Merged ");
    if (otherIndexingTask.providerToFiles.isEmpty() && trimmedReason.endsWith(trimmedOtherReason)) {
      return indexingReason;
    }
    else {
      return "Merged " + trimmedReason + " with " + trimmedOtherReason;
    }
  }

  private static double getPowerForSmoothProgressIndicator() {
    String rawValue = Registry.stringValue("indexing.progress.indicator.power");
    if ("-".equals(rawValue)) {
      return 1.0;
    }
    try {
      return Double.parseDouble(rawValue);
    }
    catch (NumberFormatException e) {
      return 1.0;
    }
  }

  @TestOnly
  @NotNull Map<@NotNull IndexableFilesIterator, @NotNull Collection<@NotNull VirtualFile>> getProviderToFiles() {
    return providerToFiles;
  }

  public final @NotNull String getIndexingReason() {
    return indexingReason;
  }

  @Override
  public String toString() {
    return "UnindexedFilesIndexer[" + myProject.getName() + ", " + providerToFiles.size() + " iterators, reason: " + indexingReason + "]";
  }
}
