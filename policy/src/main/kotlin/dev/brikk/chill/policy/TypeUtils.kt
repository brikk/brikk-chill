package dev.brikk.chill.policy

import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

@Suppress("UNCHECKED_CAST")
fun Type.erasedType(): Class<Any> {
    return when (this) {
        is Class<*> -> this as Class<Any>
        is ParameterizedType -> this.rawType.erasedType()
        is GenericArrayType -> {
            val elementType = this.genericComponentType.erasedType()
            val testArray = java.lang.reflect.Array.newInstance(elementType, 0)
            testArray.javaClass
        }
        is TypeVariable<*> -> this.bounds.firstOrNull()?.erasedType() ?: Any::class.java
        is WildcardType -> this.upperBounds[0].erasedType()
        else -> throw IllegalStateException("Unable to erase type $this")
    }
}
