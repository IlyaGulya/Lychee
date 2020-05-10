package net.aquadc.persistence.struct

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SchemaPropsTest {

    @Test fun fields() =
            assertArrayEquals(arrayOf(SomeSchema.A, SomeSchema.B, SomeSchema.C, SomeSchema.D), SomeSchema.fields)

    @Test fun mutableFields() =
            assertArrayEquals(
                arrayOf(SomeSchema.B, SomeSchema.C),
                SomeSchema.mapIndexed(SomeSchema.mutableFieldSet) { _, it -> it }
            )



    @Test fun ordinals() {
        SomeSchema.fields.forEachIndexed { index, fieldDef ->
            assertEquals(index, fieldDef.ordinal.toInt())
        }
    }

    @Test fun mutableOrdinals() {
        SomeSchema.forEachIndexed(SomeSchema.mutableFieldSet) { index, fieldDef ->
            assertEquals(index, fieldDef.mutableOrdinal.toInt())
        }
    }

    @Test fun immutableOrdinals() {
        SomeSchema.forEachIndexed(SomeSchema.immutableFieldSet) { index, fieldDef ->
            assertEquals(index, fieldDef.immutableOrdinal.toInt())
        }
    }

}
