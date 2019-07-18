package motif.intellij

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ProjectComponent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.parents
import motif.ast.IrClass
import motif.ast.IrType
import motif.ast.intellij.IntelliJClass
import motif.ast.intellij.IntelliJType
import motif.core.ResolvedGraph
import motif.models.ConstructorFactoryMethod
import motif.models.Scope
import kotlin.system.measureTimeMillis

class GraphManager(private val project: Project) : ProjectComponent {

    private val graphFactory = GraphFactory(project)

    private val listeners = mutableListOf<Listener>()

    private var graphState: GraphState = GraphState.Uninitialized

    override fun initComponent() {

    }

    override fun disposeComponent() {

    }

    fun refresh() {
        setGraphState(GraphState.Loading)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Refresh") {

            override fun run(indicator: ProgressIndicator) {
                ApplicationManager.getApplication().runReadAction {
                    val graph = graphFactory.compute()
                    val state = if (graph.errors.isEmpty()) {
                        GraphState.Valid(project, graph)
                    } else {
                        GraphState.Error(graph)
                    }
                    setGraphState(state)
                }
            }
        })
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onStateChange(graphState)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun setGraphState(graphState: GraphState) {
        this.graphState = graphState
        listeners.forEach { it.onStateChange(graphState) }
    }

    interface Listener {

        fun onStateChange(state: GraphState)
    }
}

sealed class GraphState {

    object Uninitialized : GraphState()

    object Loading : GraphState()

    class Error(val graph: ResolvedGraph): GraphState()

    class Valid(project: Project, val graph: ResolvedGraph): GraphState() {

        val invalidator = GraphInvalidator(project, graph)
    }
}

class GraphFactory(private val project: Project) {

    private val psiManager = PsiManager.getInstance(project)
    private val psiElementFactory = PsiElementFactory.SERVICE.getInstance(project)

    fun compute(): ResolvedGraph {
        val scopeClasses = { getScopeClasses() }.runAndLog("Found Scope classes.")
        return { ResolvedGraph.create(scopeClasses) }.runAndLog("Processed graph.")
    }

    private fun getScopeClasses(): List<IrClass> {
        val scopeClasses = mutableListOf<IrClass>()
        ProjectFileIndex.SERVICE.getInstance(project).iterateContent { file ->
            scopeClasses.addAll(getScopeClasses(file))
            true
        }
        return scopeClasses
    }

    private fun getScopeClasses(virtualFile: VirtualFile): List<IrClass> {
        val psiFile = psiManager.findFile(virtualFile) ?: return emptyList()
        val javaFile = psiFile as? PsiJavaFile ?: return emptyList()
        return javaFile.classes
                .filter(this::isScopeClass)
                .map(psiElementFactory::createType)
                .map { type ->
                    IntelliJClass(project, type)
                }
    }

    private fun isScopeClass(psiClass: PsiClass): Boolean {
        return psiClass.annotations.find { it.qualifiedName == motif.Scope::class.qualifiedName} != null
    }

    private fun <T : Any> (() -> T).runAndLog(message: String): T {
        lateinit var result: T
        val duration = measureTimeMillis { result = this() }
        log("$message (${duration}ms)")
        return result
    }

    private fun log(message: String) {
        Notifications.Bus.notify(
                Notification("Motif", "Motif Graph", message, NotificationType.INFORMATION))
    }
}

class GraphInvalidator(private val project: Project, private val graph: ResolvedGraph) {

    private val psiElementFactory = PsiElementFactory.SERVICE.getInstance(project)

    private val relevantTypes: Set<IrType> by lazy {
        graph.scopes.flatMap { scope ->
            (listOfNotNull(scope.objects?.clazz)
                    + scope.clazz
                    + spreadClasses(scope)
                    + constructorClasses(scope))
                    .flatMap { clazz -> typeAndSupertypes((clazz as IntelliJClass).psiClass) }
                    .map { IntelliJType(project, psiElementFactory.createType(it)) }
        }.toSet()
    }

    fun shouldInvalidate(changedElement: PsiElement): Boolean {
        return (sequenceOf(changedElement) + changedElement.parents())
                .mapNotNull { it as? PsiClass }
                .map { psiElementFactory.createType(it) }
                .any { IntelliJType(project, it) in relevantTypes }
    }

    private fun spreadClasses(scope: Scope): List<IrClass> {
        return scope.factoryMethods.mapNotNull { it.spread }
                .map { spread -> spread.clazz }
    }

    private fun constructorClasses(scope: Scope): List<IrClass> {
        return scope.factoryMethods
                .filterIsInstance<ConstructorFactoryMethod>()
                .mapNotNull { it.returnType.type.type.resolveClass() }
    }

    private fun typeAndSupertypes(psiClass: PsiClass): Set<PsiClass> {
        if (psiClass.qualifiedName == "java.lang.Object") {
            return emptySet()
        }
        return setOf(psiClass) + psiClass.supers.flatMap { typeAndSupertypes(it) }
    }
}