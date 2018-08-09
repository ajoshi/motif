package motif.compiler.ir

import motif.DoNotCache
import motif.Expose
import motif.Objects
import motif.Spread
import motif.compiler.errors.CompilerError
import motif.compiler.javax.Executable
import motif.compiler.javax.JavaxUtil
import motif.ir.source.base.Dependency
import motif.ir.source.objects.FactoryMethod
import motif.ir.source.objects.ObjectsClass
import motif.ir.source.objects.SpreadDependency
import motif.ir.source.objects.SpreadMethod
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.type.DeclaredType

class ObjectsImplFactory(override val env: ProcessingEnvironment) : JavaxUtil {

    fun create(scopeType: DeclaredType): ObjectsClass? {
        val objectsType = scopeType.annotatedInnerType(Objects::class) ?: return null
        val methods = objectsType.methods()
                .onEach {
                    if (it.isVoid) throw CompilerError(it.element, "Factory method must not return void")
                    if (it.isPrivate) throw CompilerError(it.element, "Factory method cannot be private")
                }
                .map {
                    // Order matters here.
                    val method = basic(it)
                            ?: constructor(it)
                            ?: binds(it)
                            ?: throw CompilerError(it.element, "Invalid Objects method")
                    Pair(it, method)
                }
                .map { (executable, method) ->
                    val providedDependency = executable.returnedDependency
                    val isExposed = executable.hasAnnotation(Expose::class)
                    val isCached = !executable.hasAnnotation(DoNotCache::class)
                    FactoryMethod(
                            userData = executable,
                            kind = method.kind,
                            isExposed = isExposed,
                            isCached = isCached,
                            consumedDependencies = method.consumedDependencies,
                            providedDependency = providedDependency,
                            spreadDependency = spread(executable))
                }
        return ObjectsClass(objectsType, methods)
    }

    private fun basic(executable: Executable): Method? {
        if (executable.isAbstract) {
            return null
        }

        val consumedDependencies = executable.parameters.map { it.dependency }
        return Method(FactoryMethod.Kind.BASIC, consumedDependencies)
    }

    private fun constructor(executable: Executable): Method? {
        if (!executable.parameters.isEmpty()) {
            return null
        }

        val providedType = executable.returnType as DeclaredType
        val constructors = providedType.constructors()
                .map { Executable(providedType, it.toType(providedType), it) }

        if (constructors.isEmpty()) {
            throw CompilerError(providedType.asElement(), "Unable to find a constructor for type")
        }

        // TODO Better handling of multiple constructors.
        val constructor = constructors[0]

        val consumedDependencies: List<Dependency> = constructor.parameters.map { it.dependency }
        return Method(FactoryMethod.Kind.CONSTRUCTOR, consumedDependencies)
    }

    private fun binds(executable: Executable): Method? {
        if (executable.parameters.size != 1) {
            return null
        }

        val parameter = executable.parameters[0]
        if (!parameter.type.isAssignableTo(executable.returnType)) {
            throw CompilerError(executable.element, "Invalid binds method. Parameter is not assignable to return type.")
        }

        return Method(FactoryMethod.Kind.BINDS, listOf(parameter.dependency))
    }

    private fun spread(executable: Executable): SpreadDependency? {
        if (!executable.hasAnnotation(Spread::class)) {
            return null
        }

        val providedType = executable.returnType as DeclaredType
        val methods = providedType.methods()
                .filter { !it.isVoid && it.isPublic  && it.parameters.isEmpty()}
                .map { SpreadMethod(it, executable.returnedDependency, it.returnedDependency) }

        return SpreadDependency(methods)
    }

    private data class Method(val kind: FactoryMethod.Kind, val consumedDependencies: List<Dependency>)
}