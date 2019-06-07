@file:UseExperimental(ExperimentalContracts::class)
package net.aquadc.properties.sql

import android.support.annotation.RestrictTo
import net.aquadc.persistence.New
import net.aquadc.persistence.array
import net.aquadc.persistence.struct.BaseStruct
import net.aquadc.persistence.struct.FieldDef
import net.aquadc.persistence.struct.FieldSet
import net.aquadc.persistence.struct.Lens
import net.aquadc.persistence.struct.NamedLens
import net.aquadc.persistence.struct.PartialStruct
import net.aquadc.persistence.struct.Schema
import net.aquadc.persistence.struct.Struct
import net.aquadc.persistence.struct.allFieldSet
import net.aquadc.persistence.struct.forEach
import net.aquadc.persistence.struct.forEachIndexed
import net.aquadc.persistence.struct.indexOf
import net.aquadc.persistence.struct.intersectMutable
import net.aquadc.persistence.struct.mapIndexed
import net.aquadc.persistence.type.DataType
import net.aquadc.properties.Property
import net.aquadc.properties.TransactionalProperty
import net.aquadc.properties.bind
import net.aquadc.properties.internal.ManagedProperty
import net.aquadc.properties.internal.Manager
import net.aquadc.properties.internal.Unset
import net.aquadc.properties.persistence.PropertyStruct
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract


/**
 * Common supertype for all primary keys.
 */
typealias IdBound = Any // Serializable in some frameworks

/**
 * A shorthand for properties backed by RDBMS column & row.
 */
typealias SqlProperty<T> = TransactionalProperty<Transaction, T>

/**
 * A gateway into RDBMS.
 */
interface Session {

    /**
     * Lazily creates and returns DAO for the given table.
     */
    operator fun <SCH : Schema<SCH>, ID : IdBound, REC : Record<SCH, ID>> get(
            table: Table<SCH, ID, REC>
    ): Dao<SCH, ID, REC>

    /**
     * Opens a transaction, allowing mutation of data.
     */
    fun beginTransaction(): Transaction

}

/**
 * Represents a database session specialized for a certain [Table].
 * {@implNote [Manager] supertype is used by [ManagedProperty] instances}
 */
interface Dao<SCH : Schema<SCH>, ID : IdBound, REC : Record<SCH, ID>> : Manager<SCH, Transaction, ID> {
    fun find(id: ID /* TODO fields to prefetch */): REC?
    fun select(condition: WhereCondition<SCH>, order: Array<out Order<SCH>>/* TODO: prefetch */): Property<List<REC>> // TODO DiffProperty | group by | having
    // TODO: selectWhole(...): Property<List<Property<StructSnapshot<SCH>>>>
    // TODO: fetch(...): List<StructSnapshot<SCH>>
    // todo raw queries, joins
    fun count(condition: WhereCondition<SCH>): Property<Long>
    // why do they have 'out' variance? Because we want to use a single WhereCondition<Nothing> when there's no condition
}

/**
 * Calls [block] within transaction passing [Transaction] which has functionality to create, mutate, remove [Record]s.
 * In future will retry conflicting transaction by calling [block] more than once.
 */
inline fun <R> Session.withTransaction(block: Transaction.() -> R): R {
    contract {
        callsInPlace(block, InvocationKind.AT_LEAST_ONCE)
    }

    val transaction = beginTransaction()
    try {
        val r = block(transaction)
        transaction.setSuccessful()
        return r
    } finally {
        transaction.close()
    }
}

fun <SCH : Schema<SCH>, ID : IdBound, REC : Record<SCH, ID>> Dao<SCH, ID, REC>.require(id: ID): REC =
        find(id) ?: throw NoSuchElementException("No record found in `$this` for ID $id")

@Suppress("NOTHING_TO_INLINE")
inline fun <SCH : Schema<SCH>, ID : IdBound, REC : Record<SCH, ID>> Dao<SCH, ID, REC>.select(
        condition: WhereCondition<SCH>, vararg order: Order<SCH>/* TODO: prefetch */
): Property<List<REC>> =
        select(condition, order)

fun <SCH : Schema<SCH>, ID : IdBound, REC : Record<SCH, ID>> Dao<SCH, ID, REC>.selectAll(vararg order: Order<SCH>): Property<List<REC>> =
        select(emptyCondition(), order)

fun <SCH : Schema<SCH>, ID : IdBound, REC : Record<SCH, ID>> Dao<SCH, ID, REC>.count(): Property<Long> =
        count(emptyCondition())

@JvmField val NoOrder = emptyArray<Order<Nothing>>()


interface Transaction : AutoCloseable {

    /**
     * Insert [data] into a [table].
     */
    fun <REC : Record<SCH, ID>, SCH : Schema<SCH>, ID : IdBound> insert(table: Table<SCH, ID, REC>, data: Struct<SCH>): REC

    @Deprecated("this cannot be done safely, with respect to mutability", ReplaceWith("insert(table, data)"))
    fun <REC : Record<SCH, ID>, SCH : Schema<SCH>, ID : IdBound> replace(table: Table<SCH, ID, REC>, data: Struct<SCH>): REC =
            insert(table, data)

    fun <SCH : Schema<SCH>, ID : IdBound, REC : Record<SCH, ID>, T> update(table: Table<SCH, ID, REC>, id: ID, column: NamedLens<SCH, Struct<SCH>, T>, previous: T, value: T)

    fun <SCH : Schema<SCH>, ID : IdBound> delete(record: Record<SCH, ID>)

    /**
     * Clear the whole table.
     * This may be implemented either as `DELETE FROM table` or `TRUNCATE table`.
     */
    fun truncate(table: Table<*, *, *>)

    fun setSuccessful()

    operator fun <REC : Record<SCH, ID>, SCH : Schema<SCH>, ID : IdBound, T> REC.set(field: FieldDef.Mutable<SCH, T>, new: T) {
        (this prop field).setValue(this@Transaction, new)
    }

    /**
     * Updates field values from [source].
     * @return a set of updated fields
     *   = intersection of requested [fields] and [PartialStruct.fields] present in [source]
     */
    fun <REC : Record<SCH, ID>, SCH : Schema<SCH>, ID : IdBound, T> REC.setFrom(
            source: PartialStruct<SCH>, fields: FieldSet<SCH, FieldDef.Mutable<SCH, *>>
    ): FieldSet<SCH, FieldDef.Mutable<SCH, *>> =
            source.fields.intersectMutable(fields).also { intersect ->
                source.schema.forEach(intersect) { field ->
                    mutateFrom(source, field) // capture type
                }
            }
    @Suppress("NOTHING_TO_INLINE")
    private inline fun <REC : Record<SCH, ID>, SCH : Schema<SCH>, ID : IdBound, T> REC.mutateFrom(
            source: PartialStruct<SCH>, field: FieldDef.Mutable<SCH, T>
    ) {
        this[field] = source.getOrThrow(field)
    }

}

class Order<SCH : Schema<SCH>>(
        @JvmField internal val col: FieldDef<SCH, *>,
        @JvmField internal val desc: Boolean
) {
    // may become an inline-class when hashCode/equals will be allowed

    override fun hashCode(): Int = // yep, orders on different structs may have interfering hashes
            (if (desc) 0x100 else 0) or col.ordinal.toInt()

    override fun equals(other: Any?): Boolean =
            other === this ||
                    (other is Order<*> && other.col === col && other.desc == desc)

}

val <SCH : Schema<SCH>> FieldDef<SCH, *>.asc: Order<SCH>
    get() = Order(this, false)

val <SCH : Schema<SCH>> FieldDef<SCH, *>.desc: Order<SCH>
    get() = Order(this, true)


/**
 * Represents a table, i. e. defines structs which can be persisted in a database.
 * @param SCH self, i. e. this table
 * @param ID  primary key type
 * @param REC type of record, which can be simply `Record<SCH>` or a custom class extending [Record]
 */
abstract class Table<SCH : Schema<SCH>, ID : IdBound, REC : Record<SCH, ID>>
private constructor(
        val schema: SCH,
        val name: String,
        val idColName: String,
        val idColType: DataType.Simple<ID>,
        val pkField: FieldDef.Immutable<SCH, ID>?
// TODO: [unique] indices
// TODO: auto increment
) {

    @Deprecated("this constructor uses Javanese order for id col — 'type name', use Kotlinese 'name type'",
            ReplaceWith("Table(schema, name, idColName, idColType)"))
    constructor(schema: SCH, name: String, idColType: DataType.Simple<ID>, idColName: String) :
            this(schema, name, idColName, idColType, null)

    constructor(schema: SCH, name: String, idColName: String, idColType: DataType.Simple<ID>) :
            this(schema, name, idColName, idColType, null)

    constructor(schema: SCH, name: String, idCol: FieldDef.Immutable<SCH, ID>) :
            this(schema, name, idCol.name, idCol.type as? DataType.Simple<ID>
                    ?: throw IllegalArgumentException("PK column must have simple type"),
                    idCol)

    /**
     * Instantiates a record. Typically consists of a single constructor call.
     */
    @Deprecated("Stop overriding this! Will become final.")
    abstract fun newRecord(session: Session, primaryKey: ID): REC

    /**
     * Returns all relations for this table.
     * This must describe how to store all [Struct] columns relationally.
     */
    protected open fun relations(): Array<out Relation<SCH, ID, *>> = noRelations as Array<Relation<SCH, ID, *>>

    @JvmSynthetic @JvmField internal var _delegates: Map<Lens<SCH, REC, *>, SqlPropertyDelegate>? = null // fixme: replace with Array
    private val _columns: Lazy<Array<out NamedLens<SCH, REC, *>>> = lazy {
        val rels = relations().let { rels ->
            rels.associateByTo(New.map<Lens<SCH, REC, *>, Relation<SCH, ID, *>>(rels.size), Relation<SCH, ID, *>::path)
        }
        val columns = CheckNamesList<NamedLens<SCH, REC, *>>(schema.fields.size)
        if (pkField == null) {
            columns.add(PkLens(this))
        }
        val delegates = New.map<Lens<SCH, REC, *>, SqlPropertyDelegate>()
        embed(rels, schema, null, null, columns, delegates)

        if (rels.isNotEmpty()) throw RuntimeException("cannot consume relations: $rels")

        this._delegates = delegates
        columns.array()
    }

    private class CheckNamesList<E : NamedLens<* , *, *>>(initialCapacity: Int) : ArrayList<E>(initialCapacity) {
        private val names = New.set<String>(initialCapacity)
        override fun add(element: E): Boolean {
            val name = element.name
            check(name.isNotBlank()) { "column has blank name: $element" }
            check(names.add(name)) { "duplicate name '$name' assigned to both [${first { it.name == name }}, $element]" }
            return super.add(element)
        }
    }

    // some bad code with raw types here
    @Suppress("UPPER_BOUND_VIOLATED") @JvmSynthetic internal fun embed(
            rels: MutableMap<Lens<SCH, REC, *>, Relation<SCH, ID, *>>, schema: Schema<*>,
            naming: NamingConvention?, prefix: NamedLens<SCH, Struct<SCH>, PartialStruct<Schema<*>>?>?,
            outColumns: ArrayList<NamedLens<SCH, REC, *>>, outDelegates: MutableMap<Lens<SCH, REC, *>, SqlPropertyDelegate>
    ): Array<NamedLens<SCH, REC, *>>? {
        val fields = schema.fields
        val fieldCount = fields.size
        val outLenses = naming?.let { arrayOfNulls<NamedLens<SCH, REC, *>>(fieldCount) }
        for (i in 0 until fieldCount) {
            val field = fields[i]
            val path: NamedLens<SCH, Struct<SCH>, out Any?> =
                    if (prefix == null/* implies naming == null*/) field as FieldDef<SCH, *>
                    else /* implies naming != null */ naming!!.concatErased(prefix, field) as NamedLens<SCH, Struct<SCH>, out Any?>

            val type = field.type
            val relSchema = if (type is DataType.Partial<*, *>) {
                type.schema
            } else if (type is DataType.Nullable<*>) {
                val actualType = type.actualType
                if (actualType is DataType.Partial<*, *>) actualType.schema else null
            } else {
                null
            }

            if (relSchema != null) {
                // got a struct type, a relation must be declared
                val rel = rels.remove(path)
                        ?: throw NoSuchElementException("a Relation must be declared for table $name, path $path")

                when (rel) {
                    is Relation.Embedded<*, *, *, *> -> {
                        val start = outColumns.size
                        val fieldSetCol = rel.fieldSetColName?.let { fieldSetColName ->
                            (rel.naming.concatErased(path, FieldSetLens<Schema<*>>(fieldSetColName)) as NamedLens<SCH, REC, out Long?>)
                                    .also { outColumns.add(it) }
                        }
                        val nestedLenses = embed(rels, relSchema, rel.naming,
                                path as NamedLens<SCH, Struct<SCH>, PartialStruct<Schema<*>>?>? /* assert it has struct type */,
                                outColumns, outDelegates
                        )!!
                        val nestedCols = outColumns.subList(start, outColumns.size)
                        check(outDelegates.put(path, Embedded<SCH, Schema<*>, ID, REC>(
                                relSchema, nestedLenses, nestedCols.array(), fieldSetCol
                                //     ArrayList$SubList ^^^^^^^^^^ checks for concurrent modifications and cannot be passed as is
                        )) === null)
                    }
                    is Relation.ToOne<*, *, *, *, *> -> TODO()
                    is Relation.ToMany<*, *, *, *, *, *, *> -> TODO()
                    is Relation.ManyToMany<*, *, *, *, *> -> TODO()
                }.also { }
            } else {
                outColumns.add(path)
            }
            if (outLenses != null) outLenses[i] = path
        }
        return outLenses as Array<NamedLens<SCH, REC, *>/*!!*/>?
    }

    val columns: Array<out NamedLens<SCH, REC, *>>
        get() = _columns.value

    val pkColumn: NamedLens<SCH, REC, ID>
        get() = columns[0] as NamedLens<SCH, REC, ID>


    private var _columnsByName: Map<String, NamedLens<SCH, REC, *>>? = null

    val columnsByName: Map<String, NamedLens<SCH, REC, *>>
        get() = _columnsByName
                ?: columns.let { cols ->
                    cols.associateByTo(New.map(cols.size), NamedLens<SCH, REC, *>::name)
                }.also { _columnsByName = it }


    private var _columnIndices: Map<NamedLens<SCH, REC, *>, Int>? = null

    val columnIndices: Map<NamedLens<SCH, REC, *>, Int>
        get() = _columnIndices
                ?: columns.let { cols ->
                    New.map<NamedLens<SCH, REC, *>, Int>(cols.size).also { map ->
                        columns.forEachIndexed { i, col -> map[col] = i }
                    }
                }.also { _columnIndices = it }

    internal fun delegateFor(lens: Lens<SCH, REC, *>): SqlPropertyDelegate {
        val delegates = _delegates ?: _columns.value.let { _ -> _delegates!! /* unwrap lazy */ }
        return delegates[lens] ?: Simple
    }
    internal fun <T> columnByLens(lens: Lens<SCH, Record<SCH, ID>, T>): NamedLens<SCH, REC, T>? =
            (columnIndices as Map<Lens<SCH, Record<SCH, ID>, *>, Int>)[lens]?.let { columns[it] as NamedLens<SCH, REC, T> }
    // fixme ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ looks like inference bug

    internal fun preCommitValues(record: Record<SCH, ID>, columnValues: Array<Any?>, tmpSet: HashSet<Any?>) {
        val prevFieldValues = columnValues.last() as Array<out Any?>?
        for (i in columns.indices) {
            val value = columnValues[i]
            if (value !== Unset) {
                val col = columns[i]
                // we generate lenses ourselves, so we can be sure about their types:
                val firstLens = col[0]
                when {
                    col is FieldDef.Mutable -> { /* nothing to do here, will change on next pass */ }
                    col is Telescope<*, *, *, *, *> && firstLens is FieldDef.Mutable -> {
                        if (tmpSet.add(firstLens)) { // deduplication
                            if (prevFieldValues !== null) {
                                val idx = firstLens.ordinal.toInt()
                                if (prevFieldValues[idx] !== Unset) {
                                    val evicted = (record.values[idx] as ManagedProperty<SCH, *, Any?, ID>).swapSilentlyLocked(prevFieldValues[idx])
                                    (evicted as PartialRecord<*, *>?)?.let {
                                        it.isManaged = false
                                        it.dropManagement()
                                        // ^^ not sure whether this pair is good
                                    }
                                }
                            }
                        }
                    }
                    else -> throw AssertionError("column $col does not seem to be mutable")
                }
            }
        }
        tmpSet.clear()
    }
    internal fun commitValues(record: Record<SCH, ID>, columnValues: Array<Any?>, tmpSet: HashSet<Any?>) {
        for (i in columns.indices) {
            val value = columnValues[i]
            if (value !== Unset) {
                val col = columns[i]
                val firstLens = col[0]
                when {
                    col is FieldDef.Mutable -> {
                        (record.values[col.ordinal.toInt()] as ManagedProperty<SCH, *, Any?, ID>).commit(value)
                    }
                    col is Telescope<*, *, *, *, *> && firstLens is FieldDef.Mutable -> {
                        if (tmpSet.add(firstLens)) {
                            val idx = firstLens.ordinal.toInt()
                            (record.values[idx] as ManagedProperty<SCH, *, Any?, ID>).refreshLocked()
                        }
                    }
                    else -> throw AssertionError("column $col does not seem to be mutable")
                }
            }
        }
        tmpSet.clear()
    }

    override fun toString(): String =
            "Table(schema=$schema, name=$name, ${columns.size} columns)"

}

@JvmField internal val noRelations = emptyArray<Relation<Nothing, Nothing, Nothing>>()

/**
 * The simplest case of [Table] which stores [Record] instances, not ones of its subclasses.
 */
open class SimpleTable<SCH : Schema<SCH>, ID : IdBound> : Table<SCH, ID, Record<SCH, ID>> {

    @Deprecated("this constructor uses Javanese order for id col — 'type name', use Kotlinese 'name type'",
            ReplaceWith("SimpleTable(schema, name, idColName, idColType)"))
    constructor(schema: SCH, name: String, idColType: DataType.Simple<ID>, idColName: String) : super(schema, name, idColName, idColType)

    constructor(schema: SCH, name: String, idColName: String, idColType: DataType.Simple<ID>) : super(schema, name, idColName, idColType)

    constructor(schema: SCH, name: String, idCol: FieldDef.Immutable<SCH, ID>) : super(schema, name, idCol)

    @Deprecated("Stop overriding this! Will become final.")
    override fun newRecord(session: Session, primaryKey: ID): Record<SCH, ID> =
            Record(this, session, primaryKey)

}


/**
 * Represents an active record — a container with some values and properties backed by an RDBMS row.
 */
open class Record<SCH : Schema<SCH>, ID : IdBound> : PartialRecord<SCH, ID>, PropertyStruct<SCH> {

    internal val _session get() = session

    // overrides multi-inhrit

    override val fields: FieldSet<SCH, FieldDef<SCH, *>>
        get() = schema.allFieldSet()

    override fun <T> getOrThrow(field: FieldDef<SCH, T>): T =
            get(field)

    // end

    internal fun copyValues(): Array<Any?> {
        val size = values.size
        val out = arrayOfNulls<Any>(size)
        val flds = schema.fields
        repeat(size) { i ->
            out[i] = this[flds[i]]
        }
        return out
    }

    /**
     * Creates new record.
     * Note that such a record is managed and alive (will receive updates) only if created by [Dao].
     */
    @Deprecated("Will become internal soon, making the whole class effectively final")
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    constructor(table: Table<SCH, ID, *>, session: Session, primaryKey: ID) :
            this(session, table, table.schema, primaryKey, table.schema.fields as Array<NamedLens<*, Record<*, ID>, *>>)

    /**
     * [fields] and [columns] are actually keys and values of a map; [fields] must be in their natural order
     */
    internal constructor(
            session: Session,
            table: Table<*, ID, *>, schema: SCH, primaryKey: ID,
            columns: Array<NamedLens<*, Record<*, ID>, *>>
    ) : super(session, table, schema, primaryKey, columns, schema.allFieldSet())


    override fun <T> get(field: FieldDef<SCH, T>): T = when (field) {
        is FieldDef.Mutable -> prop(field).value
        is FieldDef.Immutable -> {
            val index = field.ordinal.toInt()
            val value = values[index]

            if (value === Unset) {
                @Suppress("UNCHECKED_CAST", "UPPER_BOUND_VIOLATED")
                val freshValue = dao.getClean(columns[index] as NamedLens<Schema<*>, Struct<Schema<*>>, T>, primaryKey)
                values[index] = freshValue
                freshValue
            } else value as T
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> prop(field: FieldDef.Mutable<SCH, T>): SqlProperty<T> =
            values[field.ordinal.toInt()] as SqlProperty<T>

    @Deprecated("now we have normal relations")
    @Suppress("UNCHECKED_CAST") // id is not nullable, so Record<ForeSCH> won't be, too
    infix fun <ForeSCH : Schema<ForeSCH>, ForeID : IdBound, ForeREC : Record<ForeSCH, ForeID>>
            FieldDef.Mutable<SCH, ForeID>.toOne(foreignTable: Table<ForeSCH, ForeID, ForeREC>): SqlProperty<ForeREC> =
            (this as FieldDef.Mutable<SCH, ForeID?>).toOneNullable(foreignTable) as SqlProperty<ForeREC>

    @Deprecated("now we have normal relations")
    infix fun <ForeSCH : Schema<ForeSCH>, ForeID : IdBound, ForeREC : Record<ForeSCH, ForeID>>
            FieldDef.Mutable<SCH, ForeID?>.toOneNullable(foreignTable: Table<ForeSCH, ForeID, ForeREC>): SqlProperty<ForeREC?> =
            (this@Record prop this@toOneNullable).bind(
                    { id: ForeID? -> if (id == null) null else session[foreignTable].require(id) },
                    { it: ForeREC? -> it?.primaryKey }
            )

    @Deprecated("now we have normal relations")
    infix fun <ForeSCH : Schema<ForeSCH>, ForeID : IdBound, ForeREC : Record<ForeSCH, ForeID>>
            FieldDef.Mutable<ForeSCH, ID>.toMany(foreignTable: Table<ForeSCH, ForeID, ForeREC>): Property<List<ForeREC>> =
            session[foreignTable].select(this eq primaryKey)

    }

open class PartialRecord<SCH : Schema<SCH>, ID : IdBound> internal constructor(
        protected val session: Session,
        internal val table: Table<*, ID, *>, schema: SCH,
        val primaryKey: ID,
        internal val columns: Array<NamedLens<*, Record<*, ID>, *>>,
        override val fields: FieldSet<SCH, FieldDef<SCH, *>> // fixme: the field is unused by Record
) : BaseStruct<SCH>(schema) {

    @Suppress("UNCHECKED_CAST", "UPPER_BOUND_VIOLATED")
    internal val dao
        get() = session.get<Schema<*>, ID, Record<*, ID>>(table as Table<Schema<*>, ID, Record<*, ID>>)

    @JvmField @JvmSynthetic @Suppress("UNCHECKED_CAST")
    internal val values: Array<Any?/* = ManagedProperty<Transaction, T> | T */> =
            session[table as Table<SCH, ID, Record<SCH, ID>>].let { dao ->
                schema.mapIndexed(fields) { i, field ->
                    when (field) {
                        is FieldDef.Mutable -> ManagedProperty(
                                dao, columns[field.ordinal.toInt()] as NamedLens<SCH, Struct<SCH>, Any?>, primaryKey, Unset
                        )
                        is FieldDef.Immutable -> Unset
                    }
                }
            }

    override fun <T> getOrThrow(field: FieldDef<SCH, T>): T {
        val index = fields.indexOf(field).toInt()
        val value = try {
            values[index]
        } catch (_: ArrayIndexOutOfBoundsException) {
            throw NoSuchElementException(field.toString())
        }
        return when (field) {
            is FieldDef.Mutable -> (value as SqlProperty<T>).value
            is FieldDef.Immutable -> {
                if (value === Unset) {
                    @Suppress("UNCHECKED_CAST", "UPPER_BOUND_VIOLATED")
                    val freshValue = dao.getClean(columns[field.ordinal.toInt()] as NamedLens<Schema<*>, Struct<Schema<*>>, T>, primaryKey)
                    values[index] = freshValue
                    freshValue
                } else value as T
            }
        }
    }

    var isManaged: Boolean = true
        @JvmSynthetic internal set // cleared **before** real property unmanagement occurs

    @JvmSynthetic internal fun dropManagement() {
        val vals = values
        schema.forEachIndexed(fields) { i, field ->
            when (field) {
                is FieldDef.Mutable -> (vals[i] as ManagedProperty<*, *, *, *>).dropManagement()
                is FieldDef.Immutable -> { /* no-op */ }
            }.also { }
        }
    }

    override fun toString(): String =
            if (isManaged) super.toString()
            else buildString {
                append(this@PartialRecord.javaClass.simpleName).append(':')
                        .append(schema.javaClass.simpleName).append("(isManaged=false)")
            }

}


/**
 * Creates a property getter, i. e. a function which returns a property of a pre-set [field] of a given [SCH].
 */
fun <SCH : Schema<SCH>, T> propertyGetterOf(field: FieldDef.Mutable<SCH, T>): (Record<SCH, *>) -> Property<T> =
        { it prop field }
