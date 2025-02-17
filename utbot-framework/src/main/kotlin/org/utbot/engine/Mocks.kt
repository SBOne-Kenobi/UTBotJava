package org.utbot.engine

import org.utbot.api.mock.UtMock
import org.utbot.engine.overrides.UtArrayMock
import org.utbot.engine.overrides.UtLogicMock
import org.utbot.engine.overrides.UtOverrideMock
import org.utbot.engine.pc.UtAddrExpression
import org.utbot.framework.plugin.api.ClassId
import org.utbot.framework.plugin.api.FieldId
import org.utbot.framework.plugin.api.MethodId
import org.utbot.framework.plugin.api.UtModel
import org.utbot.framework.plugin.api.id
import org.utbot.framework.plugin.api.util.id
import org.utbot.framework.util.executableId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KFunction2
import kotlin.reflect.KFunction5
import kotlinx.collections.immutable.persistentListOf
import org.utbot.common.nameOfPackage
import org.utbot.engine.types.OBJECT_TYPE
import org.utbot.engine.util.mockListeners.MockListenerController
import org.utbot.framework.plugin.api.ApplicationContext
import org.utbot.framework.plugin.api.util.isInaccessibleViaReflection
import soot.BooleanType
import soot.RefType
import soot.Scene
import soot.SootClass
import soot.SootMethod

/**
 * Generates mock with address provided.
 *
 * Sometimes we know mock information but don't know address yet. Or vice versa.
 */
class UtMockInfoGenerator(private val generator: (UtAddrExpression) -> UtMockInfo) {
    fun generate(mockAddr: UtAddrExpression) = generator(mockAddr)
}

/**
 * Information about mock instance.
 *
 * Mocks could be:
 * - static/non-static field,
 * - method under test parameter,
 * - object returned by another mock' method call,
 * - mock created by "new" instruction.
 *
 * Contains mock class id and mock address to work with object cache.
 *
 * Note: addr for static method mocks contains addr of the "host" object
 * received by [Traverser.locateStaticObject].
 *
 * @property classId classId of the object this mock represents.
 * @property addr address of the mock object.
 */
sealed class UtMockInfo(
    open val classId: ClassId,
    open val addr: UtAddrExpression
) {
    fun copyWithClassId(classId: ClassId = this.classId): UtMockInfo = when (this) {
        is UtFieldMockInfo -> this.copy(classId, addr)
        is UtNewInstanceMockInfo -> this.copy(classId, addr)
        is UtObjectMockInfo -> this.copy(classId, addr)
        is UtStaticMethodMockInfo -> error("Unsupported operation")
        is UtStaticObjectMockInfo -> this.copy(classId, addr)
    }
}

/**
 * Static and non-static field mock.
 * Contains field id and owner object address (null for static).
 *
 * @property fieldId fieldId of the field this MockInfo represents.
 * @property ownerAddr address of the object containing this field. Null if the field is static.
 */
data class UtFieldMockInfo(
    override val classId: ClassId,
    override val addr: UtAddrExpression,
    val fieldId: FieldId,
    val ownerAddr: UtAddrExpression?
) : UtMockInfo(classId, addr)

/**
 * Mock object. Represents:
 * - method under test' parameter
 * - "mock as result", when mock returns object Engine decides to mock too
 */
data class UtObjectMockInfo(
    override val classId: ClassId,
    override val addr: UtAddrExpression
) : UtMockInfo(classId, addr)

/**
 * Mock for the "host" object for static methods and fields with [classId] declaringClass.
 * [addr] is a value received by [Traverser.locateStaticObject].
 *
 * @see Traverser.locateStaticObject
 */
data class UtStaticObjectMockInfo(
    override val classId: ClassId,
    override val addr: UtAddrExpression
) : UtMockInfo(classId, addr)

/**
 * Represents mocks created by "new" instruction.
 * Contains call site (in which class creation takes place).
 *
 * Note: call site required by mock framework to know which classes to instrument.
 */
data class UtNewInstanceMockInfo(
    override val classId: ClassId,
    override val addr: UtAddrExpression,
    val callSite: ClassId
) : UtMockInfo(classId, addr)

/**
 * Represents mocks for static methods.
 * Contains the methodId.
 *
 * Used only in [Traverser.mockStaticMethod] method to pass information into [Mocker] about the method.
 * All the executables will be stored in executables of the object with [UtStaticObjectMockInfo] and the same addr.
 *
 * Note: we use non null addr here because of [createMockObject] method. We have to know address of the object
 * that we want to make. Although static method doesn't have "caller", we still can use address of the object
 * received by [Traverser.locateStaticObject].
 */
data class UtStaticMethodMockInfo(
    override val addr: UtAddrExpression,
    val methodId: MethodId
) : UtMockInfo(methodId.classId, addr)

/**
 * A wrapper for [ObjectValue] to store additional info.
 */
sealed class MockedObjectInfo {
    abstract val value: ObjectValue?
}

object NoMock: MockedObjectInfo() {
    override val value: ObjectValue? = null
}

/**
 * Represents a mock that occurs when mock strategy allows it
 * or when an object type requires always requires mocking.
 *
 * See [Mocker.mockAlways] for more details.
 */
class ExpectedMock(objectValue: ObjectValue): MockedObjectInfo() {
    override val value: ObjectValue = objectValue
}

/**
 * Represents a mock that occurs when it is not allowed.
 * E.g. mock framework is not installed or
 * mock strategy is [MockStrategy.NO_MOCKS] and class is not in [Mocker.mockAlways] set.
 */
class UnexpectedMock(objectValue: ObjectValue): MockedObjectInfo() {
    override val value: ObjectValue = objectValue
}

/**
 * Service to mock things. Knows mock strategy, class under test and class hierarchy.
 */
class Mocker(
    private val strategy: MockStrategy,
    private val classUnderTest: ClassId,
    private val hierarchy: Hierarchy,
    chosenClassesToMockAlways: Set<ClassId>,
    internal val mockListenerController: MockListenerController? = null,
    private val applicationContext: ApplicationContext,
) {
    private val mocksAreDesired: Boolean = strategy != MockStrategy.NO_MOCKS

    /**
     * Creates mocked instance (if it should be mocked by the mocker) of the [type] using [mockInfo]
     * otherwise returns [NoMock].
     *
     * @see shouldMock
     */
    fun mock(type: RefType, mockInfo: UtMockInfo): MockedObjectInfo {
        val objectValue = if (shouldMock(type, mockInfo)) createMockObject(type, mockInfo) else null
        return construct(objectValue, mockInfo)
    }

    /**
     * Unlike to [mock], unconditionally creates a mocked instance of the [type] using [mockInfo].
     */
    fun forceMock(type: RefType, mockInfo: UtMockInfo): MockedObjectInfo {
        mockListenerController?.onShouldMock(strategy, mockInfo)

        val objectValue = createMockObject(type, mockInfo)
        return construct(objectValue, mockInfo)
    }

    /**
     * Checks if Engine should mock objects of particular type with current mock strategy and mock type.
     *
     * Answer is yes for always mocking classes, like java.util.Random and 'UtMock.makeSymbolic' call,
     * and it's always no for 'UtMock.assume' invocation.
     *
     * Answer is not for inner, local, anonymous classes (no reason to mock),
     * for private classes (mock frameworks cannot mock them) and for artificial entities (such as lambdas).
     *
     * Be careful with the order of conditions - checks like [isInner] cannot be performed for classes missing in classpath.
     *
     * For others, if mock is not a new instance mock, asks mock strategy for decision.
     */
    fun shouldMock(
        type: RefType,
        mockInfo: UtMockInfo,
    ): Boolean = checkIfShouldMock(type, mockInfo).also {
        //[utbotSuperClasses] are not involved in code generation, so
        //we shouldn't listen events that such mocks happened
        if (it && type.id !in utbotSuperClasses.map { it.id }) {
            mockListenerController?.onShouldMock(strategy, mockInfo)
        }
    }

    /**
     * Constructs [MockedObjectInfo]: enriches given [mockedValue] with an information if mocking is expected or not.
     */
    private fun construct(mockedValue: ObjectValue?, mockInfo: UtMockInfo): MockedObjectInfo {
        if (mockedValue == null) {
            return NoMock
        }

        val mockingIsPossible = when (mockInfo) {
            is UtFieldMockInfo,
            is UtObjectMockInfo -> applicationContext.mockFrameworkInstalled
            is UtNewInstanceMockInfo,
            is UtStaticMethodMockInfo,
            is UtStaticObjectMockInfo -> applicationContext.staticsMockingIsConfigured
        }
        val mockingIsForcedAndPossible = mockAlways(mockedValue.type) && mockingIsPossible

        return if (mocksAreDesired || mockingIsForcedAndPossible) {
            ExpectedMock(mockedValue)
        } else {
            UnexpectedMock(mockedValue)
        }
    }

    private fun checkIfShouldMock(
        type: RefType,
        mockInfo: UtMockInfo
    ): Boolean {
        if (isUtMockAssume(mockInfo)) return false // never mock UtMock.assume invocation
        if (isUtMockAssumeOrExecuteConcretely(mockInfo)) return false // never mock UtMock.assumeOrExecuteConcretely invocation
        if (isUtMockDisableClassCastExceptionCheck(mockInfo)) return false // never mock UtMock.disableClassCastExceptionCheck invocation
        if (isOverriddenClass(type)) return false  // never mock overriden classes
        if (type.isInaccessibleViaReflection) return false // never mock classes that we can't process with reflection
        if (isMakeSymbolic(mockInfo)) return true // support for makeSymbolic

        val sootClass = type.sootClass

        if (sootClass.isArtificialEntity) return false // never mock artificial types, i.e. Maps$lambda_computeValue_1__7

        if (!isEngineClass(type)) {
            // there is no reason (and maybe no possibility) to mock such classes
            if (sootClass.isInnerClass || sootClass.isLocal || sootClass.isAnonymous) {
                return false
            }

            // could not mock private classes (even if it is in mock always list)
            if (sootClass.isPrivate) {
                return false
            }
        }

        if (mockAlways(type)) return true // always mock randoms and loggers

        if (mockInfo is UtFieldMockInfo) {
            val fieldId = mockInfo.fieldId
            val declaringClass = fieldId.declaringClass
            val sootDeclaringClass = Scene.v().getSootClass(declaringClass.name)

            if (sootDeclaringClass.isArtificialEntity || sootDeclaringClass.isOverridden) {
                // Cannot load Java class for artificial classes, see BaseStreamExample::minExample for an example.
                // Wrapper classes that override system classes ([org.utbot.engine.overrides] package) are also
                // unavailable to the [UtContext] class loader used by the plugin.
                return false
            }

            val sootField = sootDeclaringClass
                .fields
                .firstOrNull { it.name == fieldId.name && it.declaringClass.name == sootDeclaringClass.name }
                ?: error("Unexpected $fieldId is provided into shouldMock function")

            val sootFieldType = sootField.type

            if (sootFieldType !is RefType) {
                return false
            }

            return strategy.eligibleToMock(sootFieldType.id, classUnderTest)
        }

        // Note that eligibleToMock can use information retrieved from jClass
        // Therefore, such classes should be already processed at this point
        return strategy.eligibleToMock(type.id, classUnderTest) // strategy to decide
    }

    /**
     * Checks whether [mockInfo] containing information about UtMock.makeSymbolic call or not.
     */
    private fun isMakeSymbolic(mockInfo: UtMockInfo): Boolean =
        mockInfo is UtStaticMethodMockInfo &&
                (mockInfo.methodId.signature == makeSymbolicBytecodeSignature ||
                        mockInfo.methodId.signature == nonNullableMakeSymbolicBytecodeSignature)

    private fun isUtMockAssume(mockInfo: UtMockInfo): Boolean =
        mockInfo is UtStaticMethodMockInfo && mockInfo.methodId.signature == assumeBytecodeSignature

    private fun isUtMockAssumeOrExecuteConcretely(mockInfo: UtMockInfo): Boolean =
        mockInfo is UtStaticMethodMockInfo && mockInfo.methodId.signature == assumeOrExecuteConcretelyBytecodeSignature

    private fun isUtMockDisableClassCastExceptionCheck(mockInfo: UtMockInfo): Boolean =
        mockInfo is UtStaticMethodMockInfo && mockInfo.methodId.signature == disableClassCastExceptionCheckBytecodeSignature

    private fun isEngineClass(type: RefType) = type.className in engineClasses

    private fun mockAlways(type: RefType) = type.className in classesToMockAlways

    // we cannot check modifiers for these classes because they are not in class loader
    private val engineClasses: Set<String> = setOf(UtMock::class.java.name)

    private val classesToMockAlways: Set<String> =
        (defaultSuperClassesToMockAlwaysIds + chosenClassesToMockAlways)
            // filtering out classes that are not represented in the project
            .filterNot { Scene.v().getSootClass(it.name).isPhantom }
            .flatMapTo(mutableSetOf()) { classId -> hierarchy.inheritors(classId).map { it.name } }

    companion object {

        val javaDefaultClasses: Set<Class<*>> = setOf(java.util.Random::class.java)

        private val loggerSuperClasses: Set<Class<*>> = setOf(
            org.slf4j.Logger::class.java,
            org.slf4j.LoggerFactory::class.java
        )

        private val utbotSuperClasses: Set<Class<*>> = setOf(
            // we must prevent situations when we have already created static object without mock because of UtMock.assume
            // and then are trying to mock makeSymbolic function
            org.utbot.api.mock.UtMock::class.java
        )

        private val defaultSuperClassesToMockAlways = javaDefaultClasses + loggerSuperClasses + utbotSuperClasses

        val defaultSuperClassesToMockAlwaysNames = defaultSuperClassesToMockAlways.mapTo(mutableSetOf()) { it.name }

        private val defaultSuperClassesToMockAlwaysIds: Set<ClassId> =
            defaultSuperClassesToMockAlways.mapTo(mutableSetOf()) { it.id }
    }
}

private fun createMockObject(type: RefType, mockInfo: UtMockInfo) =
    objectValue(type, mockInfo.addr, UtMockWrapper(type, mockInfo))

private val mockCounter = AtomicInteger(0)
private fun nextMockNumber() = mockCounter.incrementAndGet()
private fun createMockLabel(number: Int) = "mock#$number"

class UtMockWrapper(
    val type: RefType,
    private val mockInfo: UtMockInfo
) : WrapperInterface {
    override val wrappedMethods: Map<String, MethodSymbolicImplementation> =
        emptyMap()

    override fun isWrappedMethod(method: SootMethod): Boolean = true

    override fun Traverser.invoke(
        wrapper: ObjectValue,
        method: SootMethod,
        parameters: List<SymbolicValue>
    ): List<InvokeResult> {
        return when (method.name) {
            "<init>" -> listOf(MethodResult(voidValue))
            else -> {
                val mockNumber = nextMockNumber()
                val label = createMockLabel(mockNumber)
                val generator = (method.returnType as? RefType)?.let { type ->
                    UtMockInfoGenerator { mockAddr ->
                        UtObjectMockInfo(type.id, mockAddr)
                    }
                }
                // TODO it's a bug JIRA:1287
                val mockValue = createConst(method.returnType, label, generator)
                val updates =
                    MemoryUpdate(
                        mockInfos = persistentListOf(
                            MockInfoEnriched(
                                mockInfo,
                                mapOf(method.executableId to listOf(MockExecutableInstance(mockNumber, mockValue)))
                            )
                        )
                    )
                listOf(MethodResult(mockValue, memoryUpdates = updates))
            }
        }
    }

    override fun value(resolver: Resolver, wrapper: ObjectValue): UtModel =
        TODO("value on mock called: $this")

    override fun toString() = "UtMock(type=$type, target=$mockInfo)"
}

internal val utMockClass: SootClass
    get() = Scene.v().getSootClass(UtMock::class.qualifiedName)

internal val utOverrideMockClass: SootClass
    get() = Scene.v().getSootClass(UtOverrideMock::class.qualifiedName)

internal val utLogicMockClass: SootClass
    get() = Scene.v().getSootClass(UtLogicMock::class.qualifiedName)

internal val utArrayMockClass: SootClass
    get() = Scene.v().getSootClass(UtArrayMock::class.qualifiedName)

internal val makeSymbolicMethod: SootMethod
    get() = utMockClass.getMethod(MAKE_SYMBOLIC_NAME, listOf(BooleanType.v()))

internal val nonNullableMakeSymbolic: SootMethod
    get() = utMockClass.getMethod(MAKE_SYMBOLIC_NAME, emptyList())

internal val assumeMethod: SootMethod
    get() = utMockClass.getMethod(ASSUME_NAME, listOf(BooleanType.v()))

internal val assumeOrExecuteConcretelyMethod: SootMethod
    get() = utMockClass.getMethod(ASSUME_OR_EXECUTE_CONCRETELY_NAME, listOf(BooleanType.v()))

internal val disableClassCastExceptionCheckMethod: SootMethod
    get() = utMockClass.getMethod(DISABLE_CLASS_CAST_EXCEPTION_CHECK_NAME, listOf(OBJECT_TYPE))

val makeSymbolicBytecodeSignature: String
    get() = makeSymbolicMethod.executableId.signature

val nonNullableMakeSymbolicBytecodeSignature: String
    get() = nonNullableMakeSymbolic.executableId.signature

val assumeBytecodeSignature: String
    get() = assumeMethod.executableId.signature

val assumeOrExecuteConcretelyBytecodeSignature: String
    get() = assumeOrExecuteConcretelyMethod.executableId.signature

val disableClassCastExceptionCheckBytecodeSignature: String
    get() = disableClassCastExceptionCheckMethod.executableId.signature

internal val UTBOT_OVERRIDE_PACKAGE_NAME = UtOverrideMock::class.java.nameOfPackage

private val arraycopyMethod : KFunction5<Array<out Any>, Int, Array<out Any>, Int, Int, Unit> = UtArrayMock::arraycopy
internal val utArrayMockArraycopyMethodName = arraycopyMethod.name
private val copyOfMethod : KFunction2<Array<out Any>, Int, Array<out Any>> = UtArrayMock::copyOf
internal val utArrayMockCopyOfMethodName = copyOfMethod.name

internal val utOverrideMockAlreadyVisitedMethodName = UtOverrideMock::alreadyVisited.name
internal val utOverrideMockVisitMethodName = UtOverrideMock::visit.name
internal val utOverrideMockParameterMethodName = UtOverrideMock::parameter.name
internal val utOverrideMockDoesntThrowMethodName = UtOverrideMock::doesntThrow.name
internal val utOverrideMockExecuteConcretelyMethodName = UtOverrideMock::executeConcretely.name

internal val utLogicMockLessMethodName = UtLogicMock::less.name
internal val utLogicMockIteMethodName = UtLogicMock::ite.name

private const val MAKE_SYMBOLIC_NAME = "makeSymbolic"
private const val ASSUME_NAME = "assume"
private const val ASSUME_OR_EXECUTE_CONCRETELY_NAME = "assumeOrExecuteConcretely"
private const val DISABLE_CLASS_CAST_EXCEPTION_CHECK_NAME = "disableClassCastExceptionCheck"
