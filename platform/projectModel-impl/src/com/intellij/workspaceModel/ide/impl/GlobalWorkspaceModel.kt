// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.workspaceModel.ide.impl

import com.intellij.diagnostic.StartUpMeasurer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.registry.Registry
import com.intellij.platform.backend.workspace.GlobalWorkspaceModelCache
import com.intellij.workspaceModel.ide.JpsGlobalModelSynchronizer
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.diagnostic.telemetry.helpers.addElapsedTimeMs
import com.intellij.platform.diagnostic.telemetry.helpers.addMeasuredTimeMs
import com.intellij.platform.workspace.jps.JpsGlobalFileEntitySource
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import com.intellij.workspaceModel.ide.*
import com.intellij.workspaceModel.ide.impl.legacyBridge.library.ProjectLibraryTableBridgeImpl.Companion.libraryMap
import com.intellij.workspaceModel.ide.impl.legacyBridge.library.ProjectLibraryTableBridgeImpl.Companion.mutableLibraryMap
import com.intellij.workspaceModel.ide.legacyBridge.GlobalLibraryTableBridge
import com.intellij.platform.workspace.storage.*
import com.intellij.platform.workspace.jps.entities.ExcludeUrlEntity
import com.intellij.platform.workspace.jps.entities.LibraryEntity
import com.intellij.platform.workspace.jps.entities.LibraryPropertiesEntity
import com.intellij.platform.workspace.jps.entities.LibraryRoot
import com.intellij.platform.workspace.storage.impl.VersionedEntityStorageImpl
import com.intellij.platform.workspace.storage.impl.assertConsistency
import com.intellij.platform.workspace.storage.url.VirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import io.opentelemetry.api.metrics.Meter
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.NonNls
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis

@ApiStatus.Internal
class GlobalWorkspaceModel : Disposable {
  /**
   * Store link to the project from which changes came from. It's needed to avoid redundant changes application at [applyStateToProject]
   */
  private var filteredProject: Project? = null

  // Marker indicating that changes came from global storage
  internal var isFromGlobalWorkspaceModel: Boolean = false
  private val globalWorkspaceModelCache = GlobalWorkspaceModelCache.getInstance()
  private val globalEntitiesFilter = { entitySource: EntitySource -> entitySource is JpsGlobalFileEntitySource }

  val entityStorage: VersionedEntityStorageImpl
  val currentSnapshot: EntityStorageSnapshot
    get() = entityStorage.current

  var loadedFromCache = false
    private set

  private val modelVersionUpdate = AtomicLong(-1)

  init {
    LOG.debug { "Loading global workspace model" }

    val cache = globalWorkspaceModelCache
    val mutableEntityStorage: MutableEntityStorage = when {
      cache != null -> {
        val activity = StartUpMeasurer.startActivity("global cache loading")
        val previousStorage: MutableEntityStorage?
        val loadingCacheTime = measureTimeMillis {
          previousStorage = cache.loadCache()?.toBuilder()
        }
        val storage = if (previousStorage == null) {
          MutableEntityStorage.create()
        }
        else {
          LOG.info("Load global workspace model from cache in $loadingCacheTime ms")
          loadedFromCache = true
          previousStorage
        }
        activity.end()
        storage
      }
      else -> MutableEntityStorage.create()
    }
    entityStorage = VersionedEntityStorageImpl(EntityStorageSnapshot.empty())

    val callback = JpsGlobalModelSynchronizer.getInstance().loadInitialState(mutableEntityStorage, entityStorage, loadedFromCache)
    val changes = mutableEntityStorage.collectChanges(EntityStorageSnapshot.empty())
    entityStorage.replace(mutableEntityStorage.toSnapshot(), changes, {}, {})
    callback.invoke()
  }

  fun updateModel(description: @NonNls String, updater: (MutableEntityStorage) -> Unit) {
    ApplicationManager.getApplication().assertWriteAccessAllowed()
    if (modelVersionUpdate.get() == entityStorage.pointer.version) {
      LOG.error("Trying to update global model twice from the same version. Maybe recursive call of 'updateModel'?")
    }
    modelVersionUpdate.set(entityStorage.pointer.version)

    val updateTimeMillis: Long
    val collectChangesTimeMillis: Long
    val initializingTimeMillis: Long
    val toSnapshotTimeMillis: Long
    val generalTime = measureTimeMillis {
      val before = entityStorage.current
      val builder = MutableEntityStorage.from(before)
      updateTimeMillis = measureTimeMillis {
        updater(builder)
      }
      val changes: Map<Class<*>, List<EntityChange<*>>>
      collectChangesTimeMillis = measureTimeMillis {
        changes = builder.collectChanges(before)
      }
      initializingTimeMillis = measureTimeMillis {
        this.initializeBridges(changes, builder)
      }

      val newStorage: EntityStorageSnapshot
      toSnapshotTimeMillis = measureTimeMillis {
        newStorage = builder.toSnapshot()
      }
      if (Registry.`is`("ide.workspace.model.assertions.on.update", false)) {
        before.assertConsistency()
        newStorage.assertConsistency()
      }
      entityStorage.replace(newStorage, changes, this::onBeforeChanged, this::onChanged)
    }.apply {
      updatesCounter.incrementAndGet()
      totalUpdatesTimeMs.addAndGet(this)
    }

    LOG.info("Global model updated to version ${entityStorage.pointer.version} in $generalTime ms: $description")
    if (generalTime > 1000) {
      LOG.info("Global model update details: Updater code: $updateTimeMillis ms, Collect changes: $collectChangesTimeMillis ms")
      LOG.info("Bridge initialization: $initializingTimeMillis ms, To snapshot: $toSnapshotTimeMillis ms")
    }
    else {
      LOG.debug { "Global model update details: Updater code: $updateTimeMillis ms, Collect changes: $collectChangesTimeMillis ms" }
      LOG.debug { "Bridge initialization: $initializingTimeMillis ms, To snapshot: $toSnapshotTimeMillis ms" }
    }
  }

  override fun dispose() = Unit

  @RequiresWriteLock
  private fun initializeBridges(change: Map<Class<*>, List<EntityChange<*>>>, builder: MutableEntityStorage) {
    ApplicationManager.getApplication().assertWriteAccessAllowed()
    logErrorOnEventHandling {
      GlobalLibraryTableBridge.getInstance().initializeLibraryBridges(change, builder)
    }
  }

  private fun onBeforeChanged(change: VersionedStorageChange) {
    ApplicationManager.getApplication().assertWriteAccessAllowed()

    GlobalLibraryTableBridge.getInstance().handleBeforeChangeEvents(change)
  }

  @RequiresWriteLock
  private fun onChanged(change: VersionedStorageChange) {
    ApplicationManager.getApplication().assertWriteAccessAllowed()

    GlobalLibraryTableBridge.getInstance().handleChangedEvents(change)
    globalWorkspaceModelCache?.scheduleCacheSave()
    isFromGlobalWorkspaceModel = true
    ProjectManager.getInstance().openProjects.forEach { project ->
      if (!project.isDisposed) applyStateToProject(project)
    }
    isFromGlobalWorkspaceModel = false
  }

  @RequiresWriteLock
  fun applyStateToProject(targetProject: Project) {
    val start = System.currentTimeMillis()

    ApplicationManager.getApplication().assertWriteAccessAllowed()

    if (targetProject === filteredProject) {
      return
    }

    val workspaceModel = WorkspaceModel.getInstance(targetProject)
    val entitiesCopyAtBuilder = copyEntitiesToEmptyStorage(entityStorage.current,
                                                           VirtualFileUrlManager.getInstance(targetProject))
    workspaceModel.updateProjectModel("Sync global entities with project: ${targetProject.name}") { builder ->
      builder.replaceBySource(globalEntitiesFilter, entitiesCopyAtBuilder)
    }

    applyStateToProjectTimeMs.addElapsedTimeMs(start)
  }

  fun applyStateToProjectBuilder(project: Project, targetBuilder: MutableEntityStorage) {
    applyStateToProjectBuilderTimeMs.addMeasuredTimeMs {
      LOG.info("Sync global entities with mutable entity storage")
      targetBuilder.replaceBySource(globalEntitiesFilter,
                                    copyEntitiesToEmptyStorage(entityStorage.current, VirtualFileUrlManager.getInstance(project)))
    }
  }

  @RequiresWriteLock
  fun syncEntitiesWithProject(sourceProject: Project) {
    val start = System.currentTimeMillis()

    ApplicationManager.getApplication().assertWriteAccessAllowed()
    filteredProject = sourceProject
    val entitiesCopyAtBuilder = copyEntitiesToEmptyStorage(WorkspaceModel.getInstance(sourceProject).currentSnapshot,
                                                           VirtualFileUrlManager.getGlobalInstance())
    updateModel("Sync entities from project ${sourceProject.name} with global storage") { builder ->
      builder.replaceBySource(globalEntitiesFilter, entitiesCopyAtBuilder)
    }
    filteredProject = null
    syncEntitiesWithProjectTimeMs.addElapsedTimeMs(start)
  }

  private fun copyEntitiesToEmptyStorage(storage: EntityStorage, vfuManager: VirtualFileUrlManager): MutableEntityStorage {
    val mutableEntityStorage = MutableEntityStorage.create()
    storage.entities(LibraryEntity::class.java).forEach { libraryEntity ->
      if (!globalEntitiesFilter.invoke(libraryEntity.entitySource)) return@forEach
      val libraryRootsCopy = libraryEntity.roots.map { root ->
        LibraryRoot(root.url.createCopyAtManager(vfuManager), root.type, root.inclusionOptions)
      }

      val entitySourceCopy = (libraryEntity.entitySource as JpsGlobalFileEntitySource).copy(vfuManager)
      val excludedRootsCopy = libraryEntity.excludedRoots.map { it.copy(entitySourceCopy, vfuManager) }
      val libraryPropertiesCopy = libraryEntity.libraryProperties?.copy(entitySourceCopy)
      val libraryEntityCopy = LibraryEntity(libraryEntity.name, libraryEntity.tableId, libraryRootsCopy, entitySourceCopy) {
        excludedRoots = excludedRootsCopy
        libraryProperties = libraryPropertiesCopy
      }
      mutableEntityStorage.addEntity(libraryEntityCopy)
      val libraryBridge = storage.libraryMap.getDataByEntity(libraryEntity)
      if (libraryBridge != null) mutableEntityStorage.mutableLibraryMap.addIfAbsent(libraryEntityCopy, libraryBridge)
    }
    return mutableEntityStorage
  }

  private fun logErrorOnEventHandling(action: () -> Unit) {
    try {
      action.invoke()
    }
    catch (e: Throwable) {
      val message = "Exception at Workspace Model event handling"
      LOG.error(message, e)
    }
  }

  companion object {
    private val LOG = logger<GlobalWorkspaceModel>()
    fun getInstance(): GlobalWorkspaceModel = ApplicationManager.getApplication().service()

    private val updatesCounter: AtomicLong = AtomicLong()
    private val totalUpdatesTimeMs: AtomicLong = AtomicLong()
    private val applyStateToProjectTimeMs: AtomicLong = AtomicLong()
    private val applyStateToProjectBuilderTimeMs: AtomicLong = AtomicLong()
    private val syncEntitiesWithProjectTimeMs: AtomicLong = AtomicLong()

    private fun setupOpenTelemetryReporting(meter: Meter): Unit {
      val updatesGauge = meter.gaugeBuilder("workspaceModel.global.updates.count")
        .ofLongs().setDescription("How many times global workspace model was updated").buildObserver()

      val totalUpdatesTimeGauge = meter.gaugeBuilder("workspaceModel.global.updates.ms")
        .ofLongs().setDescription("Total time spent on global workspace model updates").buildObserver()

      val applyStateToProjectTimeGauge = meter.gaugeBuilder("workspaceModel.global.apply.state.to.project.ms")
        .ofLongs().setDescription("Total time spent in method").buildObserver()

      val applyStateToProjectBuilderTimeGauge = meter.gaugeBuilder("workspaceModel.global.apply.state.to.project.builder.ms")
        .ofLongs().setDescription("Total time spent in method").buildObserver()

      val syncEntitiesWithProjectTimeGauge = meter.gaugeBuilder("workspaceModel.sync.entities.ms")
        .ofLongs().setDescription("Total time spent in method").buildObserver()

      meter.batchCallback(
        {
          updatesGauge.record(updatesCounter.get())
          totalUpdatesTimeGauge.record(totalUpdatesTimeMs.get())
          applyStateToProjectTimeGauge.record(applyStateToProjectTimeMs.get())
          applyStateToProjectBuilderTimeGauge.record(applyStateToProjectBuilderTimeMs.get())
          syncEntitiesWithProjectTimeGauge.record(syncEntitiesWithProjectTimeMs.get())
        },
        updatesGauge, totalUpdatesTimeGauge, applyStateToProjectTimeGauge,
        applyStateToProjectBuilderTimeGauge, syncEntitiesWithProjectTimeGauge
      )
    }

    init {
      setupOpenTelemetryReporting(jpsMetrics.meter)
    }
  }
}

private fun VirtualFileUrl.createCopyAtManager(manager: VirtualFileUrlManager): VirtualFileUrl = manager.fromUrl(url)

private fun ExcludeUrlEntity.copy(entitySource: EntitySource, manager: VirtualFileUrlManager): ExcludeUrlEntity =
  ExcludeUrlEntity(url.createCopyAtManager(manager), entitySource)

private fun LibraryPropertiesEntity.copy(entitySource: EntitySource): LibraryPropertiesEntity {
  val originalPropertiesXmlTag = propertiesXmlTag
  return LibraryPropertiesEntity(libraryType, entitySource) {
    this.propertiesXmlTag = originalPropertiesXmlTag
  }
}

private fun JpsGlobalFileEntitySource.copy(manager: VirtualFileUrlManager): JpsGlobalFileEntitySource =
  JpsGlobalFileEntitySource(file.createCopyAtManager(manager))
