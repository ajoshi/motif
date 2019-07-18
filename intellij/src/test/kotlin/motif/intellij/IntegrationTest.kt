package motif.intellij

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.roots.libraries.LibraryUtil
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import motif.Scope
import org.assertj.core.api.Assertions.assertThat
import java.io.File

class IntegrationTest : LightCodeInsightFixtureTestCase() {

//    private val projectDescriptor = object : ProjectDescriptor(LanguageLevel.JDK_1_8) {
//
//        override fun getSdk() = IdeaTestUtil.getMockJdk17()
//    }

    override fun getTestDataPath() = "testData"

//    override fun getProjectDescriptor() = projectDescriptor

    override fun setUp() {
        super.setUp()
        val requiredJarClasses = listOf(Scope::class)
        requiredJarClasses
                .map { jarClass -> jarClass.java.protectionDomain.codeSource.location.path }
                .forEach { jarUri ->
                    PsiTestUtil.addLibrary(myFixture.module, jarUri)
                }
    }

    fun testUninitialized() = test {
        val listener = Listener()

        val manager = GraphManager(project)
        manager.addListener(listener)

        assertThat(listener.receive()).isEqualTo(GraphState.Uninitialized)
    }

    fun testRefresh() = test {
        val listener = Listener()

        val manager = GraphManager(project)
        manager.addListener(listener)
        manager.refresh()

        assertThat(listener.receive()).isEqualTo(GraphState.Uninitialized)
        assertThat(listener.receive()).isEqualTo(GraphState.Loading)
        assertThat(listener.receive()).isInstanceOf(GraphState.Valid::class.java)
    }

    fun testSimpleGraph() = test {
        val projectTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
        val path = Scope::class.java.protectionDomain.codeSource.location.toString()
//        val library = LibraryUtil.createLibrary(projectTable, "test")

        myFixture.addClass("""
            package test;
            
            import motif.Scope;

            @Scope
            interface FooScope {
            
                String string();
                
                @motif.Dependencies
                interface Dependencies {}
            }
        """.trimIndent())

        val listener = Listener()

        val manager = GraphManager(project)
        manager.addListener(listener)
        manager.refresh()

        assertThat(listener.receive()).isEqualTo(GraphState.Uninitialized)
        assertThat(listener.receive()).isEqualTo(GraphState.Loading)
        assertThat(listener.receive()).isInstanceOf(GraphState.Valid::class.java)
    }

    private fun test(block: suspend CoroutineScope.() -> Any) {
        runBlocking { block() }
    }
}

private class Listener : GraphManager.Listener {

    private val context = newSingleThreadContext(Listener::class.java.name)
    private val stateChannel = Channel<GraphState>()

    suspend fun receive(): GraphState {
        return stateChannel.receive()
    }

    override fun onStateChange(state: GraphState) {
        GlobalScope.launch(context) {
            stateChannel.send(state)
        }
    }
}