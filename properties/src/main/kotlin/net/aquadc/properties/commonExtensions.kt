@file:Suppress("NOTHING_TO_INLINE")
package net.aquadc.properties

import net.aquadc.properties.internal.ConcDistinctPropertyWrapper
import net.aquadc.properties.internal.UnsDistinctPropertyWrapper

inline fun <T> Property<T>.readOnlyView() = map { it }

inline fun <T> Property<T>.distinct(noinline areEqual: (T, T) -> Boolean) = when {
    !this.mayChange -> this
    !isConcurrent -> UnsDistinctPropertyWrapper(this, areEqual)
    else -> ConcDistinctPropertyWrapper(this, areEqual)
}

object Identity : (Any?, Any?) -> Boolean {
    override fun invoke(p1: Any?, p2: Any?): Boolean = p1 === p2
}

object Equals : (Any?, Any?) -> Boolean {
    override fun invoke(p1: Any?, p2: Any?): Boolean = p1 == p2
}

/**
 * Calls [func] for each [Property.value] including initial.
 */
fun <T> Property<T>.onEach(func: (T) -> Unit) {
    addChangeListener { _, new -> func(new) }
    // *
    func(value)

    /*
    What's better — call func first or add listener first?
    a) if we call function first, in (*) place value may change and we won't see new one
    b) if we add listener first, in (*) place value may change and we'll call func after that, in wrong order
     */
}
