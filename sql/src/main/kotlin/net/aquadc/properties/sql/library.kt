@file:Suppress("KDocMissingDocumentation") // fixme add later

package net.aquadc.properties.sql

import net.aquadc.properties.MutableProperty
import net.aquadc.properties.Property
import java.util.*


typealias IdBound = Any // Serializable in some frameworks

interface Session {
    fun beginTransaction(): Transaction
    fun <REC : Record<REC, ID>, ID : IdBound, T> fieldOf(col: Col<REC, T>, id: ID): MutableProperty<T>
}

inline fun Session.transaction(block: (Transaction) -> Unit) {
    val transaction = beginTransaction()
    try {
        block(transaction)
        transaction.setSuccessful()
    } finally {
        transaction.close()
    }
}

interface Transaction : AutoCloseable {

    val session: Session

    fun <REC : Record<REC, ID>, ID : IdBound> insert(table: Table<REC, ID>, vararg contentValues: ColValue<REC, *>): ID

    fun setSuccessful()

}

class ColValue<REC : Record<REC, *>, T>(val col: Col<REC, T>, val value: T)

/**
 * Creates a type-safe mapping from a column to its value.
 */
@Suppress("NOTHING_TO_INLINE")
inline operator fun <REC : Record<REC, *>, T> Col<REC, T>.minus(value: T) = ColValue(this, value)

abstract class Table<REC : Record<REC, ID>, ID : IdBound>(
        val name: String,
        type: Class<ID>
) {

    private var tmp: Pair<ArrayList<Col<REC, *>>, Class<ID>>? = Pair(ArrayList(), type)
    // todo: check what's more mem-efficient — one or two fields

    /**
     * {@implNote
     *   on concurrent access, we might null out [tmp] while it's getting accessed,
     *   so let it be synchronized (it's default [lazy] mode).
     * }
     */
    val columns: List<Col<REC, *>> by lazy {
        var idCol: Col<REC, ID>? = null
        val set = HashSet<Col<REC, *>>()
        val tmpCols = tmp!!.first
        for (i in tmpCols.indices) {
            val col = tmpCols[i]
            if (col.isPrimaryKey) {
                if (idCol != null) {
                    throw  IllegalStateException("duplicate primary key `$name`.`${col.name}`, already have `${idCol.name}`")
                }
                idCol = col as Col<REC, ID>
            }
            if (!set.add(col)) {
                throw IllegalStateException("duplicate column: `$name`.`${col.name}`")
            }
            // TODO: check whether this col type supported by the given database
        }

        _idCol = idCol ?: throw IllegalStateException("table `$name` must have a primary key column")
        val frozen = Collections.unmodifiableList(tmpCols)
        tmp = null
        frozen
    }

    abstract fun create(session: Session, id: ID): REC

    private var _idCol: Col<REC, ID>? = null
    val idCol: Col<REC, ID>
        get() = _idCol ?: columns.let { _ -> _idCol!! }


    protected inline fun <reified T> nullableCol(name: String): Col<REC, T?> =
            col0(false, name, T::class.java as Class<T?>, true)

    protected inline fun <reified T : Any> col(name: String): Col<REC, T>
            = col0(false, name, T::class.java, false)

    protected fun idCol(name: String): Col<REC, ID> =
            col0(pk = true, name = name, type = tmp().second, nullable = false)

    @PublishedApi internal fun <T> col0(pk: Boolean, name: String, type: Class<T>, nullable: Boolean): Col<REC, T> {
        val cols = tmp().first
        val col = Col<REC, T>(pk, name, type, nullable)
        cols.add(col)
        return col
    }

    private fun tmp() = tmp ?: throw IllegalStateException("table `$name` is already initialized")

}

class Col<REC : Record<REC, *>, T>(
        val isPrimaryKey: Boolean,
        val name: String,
        val javaType: Class<T>,
        val isNullable: Boolean
)

abstract class Record<REC : Record<REC, ID>, ID : IdBound>(
        private val table: Table<REC, ID>,
        private val session: Session,
        private val primaryKey: ID
) {
    infix fun <ForeREC : Record<ForeREC, ForeID>, ForeID : IdBound>
            Col<REC, ForeID>.toOne(foreignTable: Table<ForeREC, ForeID>): MutableProperty<ForeREC> =
            TODO()

    infix fun <ForeREC : Record<ForeREC, ForeID>, ForeID : IdBound>
            Col<REC, ForeID?>.toOneNullable(foreignTable: Table<ForeREC, ForeID>): MutableProperty<ForeREC?> {
        val joinField = session.fieldOf(this, primaryKey)
//        val target = joinField.map { pk -> session.select(foreignTable, pk) }
        TODO()
    }

    infix fun <ForeREC : Record<ForeREC, ForeID>, ForeID : IdBound>
            Col<ForeREC, ForeID>.toMany(foreignTable: Table<ForeREC, ForeID>): Property<List<ForeREC>> =
            TODO()

    operator fun <U> Col<REC, U>.invoke(): MutableProperty<U> =
            session.fieldOf(this, primaryKey)

}

// fixme may not be part of lib API

inline fun <reified T> t(): Class<T> = T::class.java