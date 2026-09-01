package dev.brikk.chill.opensearch

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * A bound input slot of a chill script lambda. The slot you pass to [ChillOpenSearch.script]
 * decides both the lambda's parameter type and the script kind produced:
 *
 *  - [paramOf]     params with a value now -> ready-to-run [ChillScript]
 *  - [paramType]   params type only        -> reusable [ChillScriptTemplate]
 *  - [docType]     doc values binding      (decoded per document)
 *  - [sourceType]  `_source` binding       (decoded per document; forces source loading - costly)
 *
 * Slot values are never part of the frozen payload: the same lambda always freezes to the same
 * source string, so OpenSearch's compile cache sees one script regardless of param values.
 */
sealed class ChillSlot(val kind: String, val boundClass: Class<*>) {
    companion object {
        const val KIND_PARAMS = "params"
        const val KIND_DOC = "doc"
        const val KIND_SOURCE = "source"
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

inline fun <reified P : Any> paramOf(value: P): ParamValueSlot<P> = ParamValueSlot(P::class.java, value, serializer<P>())

inline fun <reified P : Any> paramType(): ParamTypeSlot<P> = ParamTypeSlot(P::class.java, serializer<P>())

inline fun <reified D : Any> docType(): DocSlot<D> = DocSlot(D::class.java)

inline fun <reified S : Any> sourceType(): SourceSlot<S> = SourceSlot(S::class.java)
