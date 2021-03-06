@file:OptIn(ExperimentalContracts::class)
@file:JvmName("Build")
package net.aquadc.persistence.struct

import androidx.annotation.RestrictTo
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Builds a [StructSnapshot] or throws if field value neither specified explicitly nor has a default.
 */
@JvmName("newStruct")
inline operator fun <SCH : Schema<SCH>> SCH.invoke(build: SCH.(StructBuilder<SCH>) -> Unit): StructSnapshot<SCH> {
/* CONTRACT_NOT_ALLOWED for operator fun
    contract { callsInPlace(build, InvocationKind.EXACTLY_ONCE) }
 */

    val builder = newBuilder<SCH>(this)
    build(this, builder)
    return builder.finish(this, searchForDefaults = true)
}

/**
 * Builds a [StructSnapshot] filled with data from [this] and applies changes via [mutate].
 */
inline fun <SCH : Schema<SCH>> Struct<SCH>.copy(mutate: SCH.(StructBuilder<SCH>) -> Unit): StructSnapshot<SCH> {
    contract { callsInPlace(mutate, InvocationKind.EXACTLY_ONCE) }

    val builder = buildUpon(this, schema.allFieldSet)
    mutate(schema, builder)
    return builder.finish(schema, searchForDefaults = false)
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun <SCH : Schema<SCH>> newBuilder(schema: SCH): StructBuilder<SCH> {
    val fldCnt = schema.allFieldSet.size
    val array: Array<Any?> = arrayOfNulls(fldCnt + 1)
    array.fill(Unset, 0, fldCnt)
    array[fldCnt] = schema
    return StructBuilder<SCH>(array)
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
fun <SCH : Schema<SCH>> buildUpon(source: PartialStruct<SCH>, fields: FieldSet<SCH, FieldDef<SCH, *, *>>): StructBuilder<SCH> {
    val fs = source.schema.allFieldSet
    val fldCnt = fs.size
    val array: Array<Any?> = arrayOfNulls(fldCnt + 1)
    val actualFields = source.fields intersect fields
    source.schema.forEachIndexed(fs) { i, field ->
        array[i] = if (field in actualFields) source.getOrThrow(field) else Unset
    }
    array[fldCnt] = source.schema
    return StructBuilder<SCH>(array)
}

/**
 * A temporary wrapper around [Array] for instantiating [StructSnapshot]s.
 */
@Suppress("NON_PUBLIC_PRIMARY_CONSTRUCTOR_OF_INLINE_CLASS")
inline class StructBuilder<SCH : Schema<SCH>> internal constructor(
        private val values: Array<Any?>
) {

    /**
     * Asserts that the given field is set and gets its value.
     * Useful for patching structures deeply:
     * ```
     * struct.copy {
     *     it[Nested] = it[Nested].copy {
     *         it[SomeField] = newValue
     *     }
     * }
     * ```
     */
    operator fun <T> get(key: FieldDef<SCH, T, *>): T {
        val v = values[key.ordinal.toInt()]
        if (v === Unset) throw NoSuchElementException()
        else return v as T
    }

    /**
     * Assigns the given [value] to the specified [field].
     */
    operator fun <T> set(field: FieldDef<SCH, T, *>, value: T) {
        values[field.ordinal.toInt()] = value
    }

    /**
     * Assigns field values from [source].
     * @return a set of updated fields
     *   = intersection of requested [fields] and [PartialStruct.fields] present in [source]
     */
    fun setFrom(
            source: PartialStruct<SCH>,
            fields: FieldSet<SCH, FieldDef<SCH, *, *>> = source.schema.allFieldSet
    ): FieldSet<SCH, FieldDef<SCH, *, *>> =
            source.fields.intersect(fields).also { intersect ->
                source.schema.forEach(intersect) { field ->
                    values[field.ordinal.toInt()] = source.getOrThrow(field)
                }
            }

    /**
     * Create a [StructSnapshot] unsafely capturing [values] array.
     * [searchForDefaults]=false unsafely assumes that all fields have according values!
     */
    @PublishedApi internal fun finish(schema: SCH, searchForDefaults: Boolean): StructSnapshot<SCH> {
        if (searchForDefaults) {
            for (i in 0.until(values.size - 1)) {
                //     don't touch schema ^^^
                if (values[i] === Unset)
                    values[i] = schema.defaultOrElse(schema.fieldAt(i)) {
                        throw NoSuchElementException(schema.fieldAt(i).toString())
                    }
            }
        }

        return StructSnapshot(values)
    }

    fun fieldsPresent(): FieldSet<SCH, FieldDef<SCH, *, *>> {
        var set = 0L
        var field = 1L
        for (i in 0.until(values.size - 1)) {
            if (values[i] !== Unset) {
                set = set or field
            }
            field = field shl 1
        }
        return FieldSet(set)
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    fun expose(): Array<Any?> =
            values

}
