package dev.brikk.chill.opensearch

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import kotlin.jvm.javaObjectType

/**
 * A bound input slot of a chill script lambda. The slot you pass to [ChillOpenSearch.script]
 * decides both the lambda's parameter type and the script kind produced:
 *
 *  - [paramOf]     params with a value now -> ready-to-run [ChillScript]
 *  - [paramType]   params type only        -> reusable [ChillScriptTemplate]
 *  - [docType]     doc values binding      (decoded per document)
 *  - [sourceType]  `_source` binding       (decoded per document; forces source loading - costly)
 *  - [scoreType]   base query score         (bound as the final lambda parameter)
 *
 * Slot values are never part of the frozen payload: the same lambda always freezes to the same
 * source string, so OpenSearch's compile cache sees one script regardless of param values.
 */
sealed class ChillSlot(val kind: String, val boundClass: Class<*>) {
    companion object {
        const val KIND_PARAMS = "params"
        const val KIND_DOC = "doc"
        const val KIND_SOURCE = "source"
        const val KIND_SCORE = "score"
    }
}

class ParamValueSlot<P : Any> @PublishedApi internal constructor(
    boundClass: Class<*>,
    val value: P,
    val serializer: KSerializer<P>,
) : ChillSlot(KIND_PARAMS, boundClass)

class ParamTypeSlot<P : Any> @PublishedApi internal constructor(
    boundClass: Class<*>,
    val serializer: KSerializer<P>,
) : ChillSlot(KIND_PARAMS, boundClass)

class DocSlot<D : Any> @PublishedApi internal constructor(boundClass: Class<*>) : ChillSlot(KIND_DOC, boundClass)

class SourceSlot<S : Any> @PublishedApi internal constructor(boundClass: Class<*>) : ChillSlot(KIND_SOURCE, boundClass)

class ScoreSlot @PublishedApi internal constructor() : ChillSlot(KIND_SCORE, Double::class.javaObjectType)

inline fun <reified P : Any> paramOf(value: P): ParamValueSlot<P> =
    ParamValueSlot(P::class.java, value, serverResolvable(P::class.java, serializer<P>()))

inline fun <reified P : Any> paramType(): ParamTypeSlot<P> =
    ParamTypeSlot(P::class.java, serverResolvable(P::class.java, serializer<P>()))

inline fun <reified D : Any> docType(): DocSlot<D> =
    DocSlot(D::class.java.also { serverResolvable(it, serializer<D>()) })

inline fun <reified S : Any> sourceType(): SourceSlot<S> =
    SourceSlot(S::class.java.also { serverResolvable(it, serializer<S>()) })

/**
 * The frozen payload carries a bound slot as a class *name*; the server rebuilds its serializer
 * from that class alone. A generic type (`Wrapper<String>`) or a type needing a contextual /
 * custom serializer at the call site has a fine client-side serializer but no server-side one -
 * so it would freeze happily and fail at first use on the node. Catch that here, where the
 * reified type is still known, by resolving exactly as the server will and comparing.
 */
@PublishedApi
internal fun <T> serverResolvable(clazz: Class<*>, reified: KSerializer<T>): KSerializer<T> {
    val fromClass = try {
        serializer(clazz)
    } catch (ex: SerializationException) {
        throw IllegalArgumentException(
            "${clazz.name} cannot be a bound slot type: the server rebuilds the serializer from the class name " +
                "alone and cannot (${ex.message}). Use a non-generic @Serializable class.",
            ex,
        )
    }
    if (!fromClass.descriptor.sameShapeAs(reified.descriptor)) {
        throw IllegalArgumentException(
            "${clazz.name} cannot be a bound slot type: its serializer depends on type arguments the frozen payload " +
                "does not carry (client sees ${reified.descriptor.serialName}, server would see ${fromClass.descriptor.serialName}). " +
                "Use a non-generic @Serializable class.",
        )
    }
    return reified
}

private fun SerialDescriptor.sameShapeAs(other: SerialDescriptor): Boolean =
    serialName == other.serialName && kind == other.kind && elementsCount == other.elementsCount &&
        (0 until elementsCount).all { i ->
            getElementName(i) == other.getElementName(i) &&
                getElementDescriptor(i).let { a -> other.getElementDescriptor(i).let { b -> a.serialName == b.serialName && a.kind == b.kind } }
        }

fun scoreType(): ScoreSlot = ScoreSlot()
