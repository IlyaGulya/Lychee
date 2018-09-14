package net.aquadc.struct

import net.aquadc.struct.converter.Converter


/**
 * Declares a struct (or DTO).
 * `struct`s in C, Rust, Swift, etc are similar to final classes with only public fields, no methods and no supertypes.
 */
interface StructDef<STRUCT : Struct<STRUCT>> {
    val name: String
    val fields: List<Field<STRUCT, *>>
}

/**
 * Represents an instance of a struct.
 */
interface Struct<THIS : Struct<THIS>> {
    fun <T> getValue(field: Field<THIS, T>): T
}

/**
 * Struct field is a single key-value mapping.
 */
class Field<STRUCT : Struct<STRUCT>, T>(
        val structDef: StructDef<STRUCT>,
        val name: String,
        val converter: Converter<T>,
        val ordinal: Int
) {
    override fun toString(): String = structDef.name + '.' + name
}

internal inline fun <reified T> t(): Class<T> = T::class.java