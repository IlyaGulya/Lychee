package net.aquadc.properties.sql.dialect

import net.aquadc.properties.sql.Col
import net.aquadc.properties.sql.Record
import net.aquadc.properties.sql.Table
import net.aquadc.properties.sql.WhereCondition
import net.aquadc.struct.converter.DataType
import java.lang.StringBuilder

/**
 * Represents an SQL dialect. Provides functions for building queries.
 */
interface Dialect {

    /**
     * Constructs an SQL query like `INSERT INTO <table> (<col>, <col>, ...) VALUES (?, ?, ...)`
     */
    fun <REC : Record<REC, *>> insertQuery(table: Table<REC, *>, cols: Array<Col<REC, *>>): String

    /**
     * Constructs an SQL query like `SELECT <col> from <table> WHERE <condition>`
     */
    fun <REC : Record<REC, *>> selectFieldQuery(columnName: String, table: Table<REC, *>, condition: WhereCondition<out REC>): String

    /**
     * Constructs an SQL query like `SELECT COUNT(*) from <table> WHERE <condition>`
     */
    fun <REC : Record<REC, *>> selectCountQuery(table: Table<REC, *>, condition: WhereCondition<out REC>): String

    /**
     * Appends WHERE clause (without WHERE itself) to the [builder].
     */
    fun <REC : Record<REC, *>> appendWhereClause(builder: StringBuilder, condition: WhereCondition<out REC>): StringBuilder

    /**
     *  Construcs an SQL query like `UPDATE <table> SET <col> = ?`
     */
    fun <REC : Record<REC, *>> updateFieldQuery(table: Table<REC, *>, col: Col<REC, *>): String

    /**
     * Constructs an SQL query like `DELETE FROM <table> WHERE <idCol> = ?`
     */
    fun deleteRecordQuery(table: Table<*, *>): String

    /**
     * Appends quoted and escaped table or column name.
     */
    fun StringBuilder.appendName(name: String): StringBuilder

    /**
     * Returns SQL data type for the given [DataType] instance.
     */
    fun nameOf(dataType: DataType): String

    /**
     * Returns an SQL query to create the given [table].
     */
    fun createTable(table: Table<*, *>): String

}