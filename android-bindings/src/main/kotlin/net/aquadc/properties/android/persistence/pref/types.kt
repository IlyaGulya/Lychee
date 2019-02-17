package net.aquadc.properties.android.persistence.pref

import android.content.SharedPreferences
import android.util.Base64
import net.aquadc.persistence.struct.FieldDef
import net.aquadc.persistence.type.DataType
import net.aquadc.persistence.type.DataTypeVisitor
import net.aquadc.persistence.type.match
import net.aquadc.persistence.type.serialized
import net.aquadc.properties.android.persistence.assertFitsByte
import net.aquadc.properties.android.persistence.assertFitsShort
import net.aquadc.properties.internal.Unset
import java.lang.Double as JavaLangDouble
import java.util.EnumSet


internal fun <T> FieldDef<*, T>.get(prefs: SharedPreferences): T {
    val value = type.get(prefs, name)
    return if (value === Unset) default else value
}

internal fun <T> DataType<T>.get(prefs: SharedPreferences, name: String, default: T): T {
    val value = get(prefs, name)
    return if (value === Unset) default else value
}

/**
 * SharedPrefs do not support storing null values. Null means 'absent' in this context.
 * To preserve consistent behaviour of default field values amongst nullable and non-nullable fields,
 * we store 'null' ourselves. If a field has String type, 'null' is stored as Boolean 'false'.
 * Otherwise 'null' is stored as a String "null".
 */
@JvmSynthetic internal val storedAsString =
        EnumSet.of(DataType.Simple.Kind.Str, DataType.Simple.Kind.Blob)

@Suppress("UNCHECKED_CAST")
private fun <T> DataType<T>.get(prefs: SharedPreferences, key: String): T {
    if (!prefs.contains(key)) return Unset as T

    val map = prefs.all // sadly, copying prefs fully is the only way to achieve correctness concurrently

    val value = map[key]
    if (value === null) return Unset as T

    return (readerVis as PrefReaderVisitor<T>).match(this, null, value)
}
private val readerVis = PrefReaderVisitor<Any?>()
private class PrefReaderVisitor<T> : DataTypeVisitor<Nothing?, Any, T, T> {

    override fun Nothing?.simple(arg: Any, raw: DataType<T>, kind: DataType.Simple.Kind): T =
            if (raw is DataType.Nullable<*> && if (kind in storedAsString) arg == false else arg == "null")
                null as T
            else
                raw.decode(when (kind) {
                    DataType.Simple.Kind.Bool -> arg as Boolean
                    DataType.Simple.Kind.I8 -> (arg as Int).assertFitsByte()
                    DataType.Simple.Kind.I16 -> (arg as Int).assertFitsShort()
                    DataType.Simple.Kind.I32 -> arg as Int
                    DataType.Simple.Kind.I64 -> arg as Long
                    DataType.Simple.Kind.F32 -> arg as Float
                    DataType.Simple.Kind.F64 -> JavaLangDouble.longBitsToDouble(arg as Long)
                    DataType.Simple.Kind.Str -> arg as String
                    DataType.Simple.Kind.Blob -> Base64.decode(arg as String, Base64.DEFAULT)
                })

    override fun <E> Nothing?.collection(arg: Any, raw: DataType<T>, type: DataType.Collect<T, E>): T =
            if (raw is DataType.Nullable<*> && arg == false)
                null as T
            else
                type.elementType.let { elementType ->
                    if (elementType is DataType.Simple<*> && elementType.kind == DataType.Simple.Kind.Str)
                        raw.decode(arg as Set<String>)
                    else /* here we have a Collection<Whatever>, including potentially a collection of collections, etc */
                        serialized/*allocation here*/(type).decode(Base64.decode(arg as String, Base64.DEFAULT))
                }

}

internal fun <T> DataType<T>.put(editor: SharedPreferences.Editor, key: String, value: T) {
    PrefWriterVisitor<T>(key).match(this, editor, value)
}
private class PrefWriterVisitor<T>(
        private val key: String
) : DataTypeVisitor<SharedPreferences.Editor, T, T, Unit> {

    override fun SharedPreferences.Editor.simple(arg: T, raw: DataType<T>, kind: DataType.Simple.Kind) =
            if (raw is DataType.Nullable<*> && arg === null) {
                if (kind in storedAsString) putBoolean(key, false)
                else putString(key, "null")
            } else {
                val v = raw.encode(arg)
                when (kind) {
                    DataType.Simple.Kind.Bool -> putBoolean(key, v as Boolean)
                    DataType.Simple.Kind.I8 -> putInt(key, (v as Byte).toInt())
                    DataType.Simple.Kind.I16 -> putInt(key, (v as Short).toInt())
                    DataType.Simple.Kind.I32 -> putInt(key, v as Int)
                    DataType.Simple.Kind.I64 -> putLong(key, v as Long)
                    DataType.Simple.Kind.F32 -> putFloat(key, v as Float)
                    DataType.Simple.Kind.F64 -> putLong(key, java.lang.Double.doubleToLongBits(v as Double))
                    DataType.Simple.Kind.Str -> putString(key, v as String)
                    DataType.Simple.Kind.Blob -> putString(key, Base64.encodeToString(v as ByteArray, Base64.DEFAULT))
                }
            }.let { }

    override fun <E> SharedPreferences.Editor.collection(arg: T, raw: DataType<T>, type: DataType.Collect<T, E>) =
            if (raw is DataType.Nullable<*> && arg === null) {
                putBoolean(key, false)
            } else {
                type.elementType.let { elementType ->
                    if (elementType is DataType.Simple<*> && elementType.kind == DataType.Simple.Kind.Str)
                        putStringSet(key, (type.encode(arg) as Collection<String>).toSet())
                    else
                        putString(key, Base64.encodeToString(serialized(type).encode(arg) as ByteArray, Base64.DEFAULT))
                }
            }.let { }

}
