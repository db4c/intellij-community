// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.workspace.jps

import com.intellij.configurationStore.*
import com.intellij.diagnostic.StartUpMeasurer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.StateSplitterEx
import com.intellij.openapi.components.impl.stores.FileStorageCoreUtil
import com.intellij.openapi.components.impl.stores.IProjectStore
import com.intellij.openapi.module.impl.AutomaticModuleUnloader
import com.intellij.openapi.module.impl.ModulePath
import com.intellij.openapi.module.impl.UnloadedModuleDescriptionImpl
import com.intellij.openapi.module.impl.UnloadedModulesListStorage
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.impl.ProjectLifecycleListener
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.project.stateStore
import com.intellij.util.PathUtil
import com.intellij.workspace.api.EntityChange
import com.intellij.workspace.api.EntitySource
import com.intellij.workspace.api.EntityStoreChanged
import com.intellij.workspace.api.TypedEntityStorageBuilder
import com.intellij.workspace.ide.*
import com.intellij.workspace.legacyBridge.intellij.LegacyBridgeModuleManagerComponent
import org.jdom.Element
import org.jetbrains.jps.util.JpsPathUtil
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashSet

internal class JpsProjectModelSynchronizer(private val project: Project) : Disposable {
  private val incomingChanges = Collections.synchronizedList(ArrayList<JpsConfigurationFilesChange>())
  private lateinit var fileContentReader: StorageJpsConfigurationReader
  private val serializationData = AtomicReference<JpsEntitiesSerializationData?>()
  private val sourcesToSave = Collections.synchronizedSet(HashSet<EntitySource>())

  init {
    if (!project.isDefault && enabled) {
      project.messageBus.connect(this).subscribe(ProjectLifecycleListener.TOPIC, object : ProjectLifecycleListener {
        override fun projectComponentsInitialized(project: Project) {
          if (project === this@JpsProjectModelSynchronizer.project) {
            loadInitialProject(project.storagePlace!!)
          }
        }
      })
    }
  }

  internal fun needToReloadProjectEntities(): Boolean {
    if (!enabled) return false
    if (StoreReloadManager.getInstance().isReloadBlocked()) return false
    if (serializationData.get() == null) return false

    synchronized(incomingChanges) {
      return incomingChanges.isNotEmpty()
    }
  }

  internal fun reloadProjectEntities() {
    if (!enabled) return

    if (StoreReloadManager.getInstance().isReloadBlocked()) return
    val data = serializationData.get() ?: return
    val changes = getAndResetIncomingChanges() ?: return

    val (changedEntities, builder) = data.reloadFromChangedFiles(changes, fileContentReader)
    if (changedEntities.isEmpty() && builder.isEmpty()) return

    ApplicationManager.getApplication().invokeAndWait(Runnable {
      runWriteAction {
        WorkspaceModel.getInstance(project).updateProjectModel { updater ->
          updater.replaceBySource({ it in changedEntities }, builder.toStorage())
        }
        sourcesToSave.removeAll(changedEntities)
      }
    })
  }

  private fun registerListener() {
    ApplicationManager.getApplication().messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
      override fun after(events: MutableList<out VFileEvent>) {
        //todo support move/rename
        //todo optimize: filter events before creating lists
        val toProcess = events.asSequence().filter { isFireStorageFileChangedEvent(it) }
        val addedUrls = toProcess.filterIsInstance<VFileCreateEvent>().mapTo(ArrayList()) { JpsPathUtil.pathToUrl(it.path) }
        val removedUrls = toProcess.filterIsInstance<VFileDeleteEvent>().mapTo(ArrayList()) { JpsPathUtil.pathToUrl(it.path) }
        val changedUrls = toProcess.filterIsInstance<VFileContentChangeEvent>().mapTo(ArrayList()) { JpsPathUtil.pathToUrl(it.path) }
        if (addedUrls.isNotEmpty() || removedUrls.isNotEmpty() || changedUrls.isNotEmpty()) {
          val change = JpsConfigurationFilesChange(addedUrls, removedUrls, changedUrls)
          incomingChanges.add(change)

          StoreReloadManager.getInstance().scheduleProcessingChangedFiles()
        }
      }
    })
    WorkspaceModelTopics.getInstance(project).subscribeImmediately(project.messageBus.connect(), object : WorkspaceModelChangeListener {
      override fun changed(event: EntityStoreChanged) {
        event.getAllChanges().forEach {
          when (it) {
            is EntityChange.Added -> sourcesToSave.add(it.entity.entitySource)
            is EntityChange.Removed -> sourcesToSave.add(it.entity.entitySource)
            is EntityChange.Replaced -> {
              sourcesToSave.add(it.oldEntity.entitySource)
              sourcesToSave.add(it.newEntity.entitySource)
            }
          }
        }
      }
    })
  }

  internal fun loadInitialProject(storagePlace: JpsProjectStoragePlace) {
    val activity = StartUpMeasurer.startActivity("(wm) Load initial project")
    val baseDirUrl = storagePlace.baseDirectoryUrl
    fileContentReader = StorageJpsConfigurationReader(project, baseDirUrl)
    val serializationData = JpsProjectEntitiesLoader.createProjectSerializers(storagePlace, fileContentReader, false, true)
    this.serializationData.set(serializationData)
    registerListener()
    val builder = TypedEntityStorageBuilder.create()

    val modulePaths = serializationData.fileSerializersByUrl.values().filterIsInstance<ModuleImlFileEntitiesSerializer>().map { it.modulePath }
    loadStateOfUnloadedModules(modulePaths)

    serializationData.loadAll(fileContentReader, builder)
    WriteAction.runAndWait<RuntimeException> {
      WorkspaceModel.getInstance(project).updateProjectModel { updater ->
        updater.replaceBySource({ it is JpsFileEntitySource }, builder.toStorage())
      }
    }
    activity.end()
  }

  // Logic from com.intellij.openapi.module.impl.ModuleManagerImpl.loadState(java.util.Set<com.intellij.openapi.module.impl.ModulePath>)
  private fun loadStateOfUnloadedModules(modulePaths: List<ModulePath>) {
    val unloadedModuleNames = UnloadedModulesListStorage.getInstance(project).unloadedModuleNames.toSet()

    val (unloadedModulePaths, modulePathsToLoad) = modulePaths.partition { it.moduleName in unloadedModuleNames }

    val unloaded = UnloadedModuleDescriptionImpl.createFromPaths(unloadedModulePaths, project).toMutableList()

    if (unloaded.isNotEmpty()) {
      val changeUnloaded = AutomaticModuleUnloader.getInstance(project).processNewModules(modulePathsToLoad.toSet(), unloaded)
      unloaded.addAll(changeUnloaded.toUnloadDescriptions)
    }

    val unloadedModules = LegacyBridgeModuleManagerComponent.getInstance(project).unloadedModules
    unloadedModules.clear()
    unloaded.associateByTo(unloadedModules) { it.name }
  }

  internal fun saveChangedProjectEntities(writer: JpsFileContentWriter) {
    if (!enabled) return

    val data = serializationData.get() ?: return
    val storage = WorkspaceModel.getInstance(project).entityStore.current
    val affectedSources = synchronized(sourcesToSave) {
      val copy = HashSet(sourcesToSave)
      sourcesToSave.clear()
      copy
    }
    data.saveEntities(storage, affectedSources, writer)
  }

  private fun getAndResetIncomingChanges(): JpsConfigurationFilesChange? {
    synchronized(incomingChanges) {
      if (incomingChanges.isEmpty()) return null
      val combinedChanges = combineChanges()
      incomingChanges.clear()
      return combinedChanges
    }
  }

  private fun combineChanges(): JpsConfigurationFilesChange {
    val singleChange = incomingChanges.singleOrNull()
    if (singleChange != null) {
      return singleChange
    }
    val allAdded = LinkedHashSet<String>()
    val allRemoved = LinkedHashSet<String>()
    val allChanged = LinkedHashSet<String>()
    for (change in incomingChanges) {
      allChanged.addAll(change.changedFileUrls)
      for (addedUrl in change.addedFileUrls) {
        if (allRemoved.remove(addedUrl)) {
          allChanged.add(addedUrl)
        }
        else {
          allAdded.add(addedUrl)
        }
      }
      for (removedUrl in change.removedFileUrls) {
        allChanged.remove(removedUrl)
        if (!allAdded.remove(removedUrl)) {
          allRemoved.add(removedUrl)
        }
      }
    }
    return JpsConfigurationFilesChange(allAdded, allRemoved, allChanged)
  }

  override fun dispose() {
  }

  companion object {
    fun getInstance(project: Project): JpsProjectModelSynchronizer? = project.getComponent(JpsProjectModelSynchronizer::class.java)

    var enabled = Registry.`is`("ide.workspace.model.jps.enabled")
  }
}

private class StorageJpsConfigurationReader(private val project: Project,
                                            private val baseDirUrl: String) : JpsFileContentReader {
  override fun loadComponent(fileUrl: String, componentName: String): Element? {
    val filePath = JpsPathUtil.urlToPath(fileUrl)
    if (FileUtil.extensionEquals(filePath, "iml")) {
      //todo fetch data from ModuleStore
      return CachingJpsFileContentReader(baseDirUrl).loadComponent(fileUrl, componentName)
    }
    else {
      val storage = getProjectStateStorage(filePath, project.stateStore)
      val stateMap = storage.getStorageData()
      return if (storage is DirectoryBasedStorageBase) {
        val elementContent = stateMap.getElement(PathUtil.getFileName(filePath))
        if (elementContent != null) {
          Element(FileStorageCoreUtil.COMPONENT).setAttribute(FileStorageCoreUtil.NAME, componentName).addContent(elementContent)
        }
        else {
          null
        }
      }
      else {
        stateMap.getElement(componentName)
      }
    }
  }
}

internal fun getProjectStateStorage(filePath: String, store: IProjectStore): StateStorageBase<StateMap> {
  val collapsedPath: String
  val splitterClass: Class<out StateSplitterEx>
  if (FileUtil.extensionEquals(filePath, "ipr")) {
    collapsedPath = "\$PROJECT_FILE$"
    splitterClass = StateSplitterEx::class.java
  }
  else {
    val fileName = PathUtil.getFileName(filePath)
    val parentPath = PathUtil.getParentPath(filePath)
    if (PathUtil.getFileName(parentPath) == Project.DIRECTORY_STORE_FOLDER) {
      collapsedPath = fileName
      splitterClass = StateSplitterEx::class.java
    }
    else {
      val grandParentPath = PathUtil.getParentPath(parentPath)
      if (PathUtil.getFileName(grandParentPath) != Project.DIRECTORY_STORE_FOLDER) error("$filePath is not under .idea directory")
      collapsedPath = PathUtil.getFileName(parentPath)
      splitterClass = FakeDirectoryBasedStateSplitter::class.java
    }
  }
  val storageSpec = FileStorageAnnotation(collapsedPath, false, splitterClass)
  @Suppress("UNCHECKED_CAST")
  return store.storageManager.getStateStorage(storageSpec) as StateStorageBase<StateMap>
}

/**
 * This fake implementation is used to force creating directory based storage in StateStorageManagerImpl.createStateStorage
 */
private class FakeDirectoryBasedStateSplitter : StateSplitterEx() {
  override fun splitState(state: Element): MutableList<Pair<Element, String>> {
    throw AssertionError()
  }
}

internal class LegacyBridgeStoreReloadManager : StoreReloadManagerImpl() {
  override fun mayHaveAdditionalConfigurations(project: Project): Boolean {
    return JpsProjectModelSynchronizer.getInstance(project)?.needToReloadProjectEntities() ?: false
  }

  override fun reloadAdditionalConfigurations(project: Project) {
    JpsProjectModelSynchronizer.getInstance(project)?.reloadProjectEntities()
  }
}