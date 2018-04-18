@file:Suppress("NOTHING_TO_INLINE")
package net.aquadc.properties

import android.os.Looper
import javafx.application.Platform
import net.aquadc.properties.internal.DistinctPropertyWrapper
import net.aquadc.properties.internal.DebouncedProperty
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit


@Suppress("UNCHECKED_CAST")
inline fun <T> Property<T>.readOnlyView() = map(Just as (T) -> T)

inline fun <T> Property<T>.distinct(noinline areEqual: (T, T) -> Boolean) =
        if (this.mayChange) DistinctPropertyWrapper(this, areEqual) else this

object Just : (Any?) -> Any? {
    override fun invoke(p1: Any?): Any? = p1
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

/**
 * Returns a debounced wrapper around this property.
 * Will work only on threads which can accept tasks:
 * * JavaFX Application thread (via [Platform]);
 * * Android [Looper] thread;
 * * a thread of a [ForkJoinPool].
 *
 * Single-threaded debounced wrapper will throw exception when created on inappropriate thread.
 * Concurrent debounced wrapper will throw exception when listener gets subscribed from inappropriate thread.
 */
fun <T> Property<T>.debounced(delay: Long, unit: TimeUnit) =
        if (mayChange) DebouncedProperty(this, delay, unit)
        else this

/**
 * Inline copy of [java.util.concurrent.atomic.AtomicReference.getAndUpdate].
 */
inline fun <T> MutableProperty<T>.getAndUpdate(updater: (old: T) -> T): T {
    var prev: T
    var next: T
    do {
        prev = value
        next = updater(prev)
    } while (!casValue(prev, next))
    return prev
}

/**
 * Inline copy of [java.util.concurrent.atomic.AtomicReference.updateAndGet].
 */
inline fun <T> MutableProperty<T>.updateAndGet(updater: (old: T) -> T): T {
    var prev: T
    var next: T
    do {
        prev = value
        next = updater(prev)
    } while (!casValue(prev, next))
    return next
}

/**
 * Simplified version of [getAndUpdate] or [updateAndGet].
 */
inline fun <T> MutableProperty<T>.update(updater: (old: T) -> T) {
    var prev: T
    var next: T
    do {
        prev = value
        next = updater(prev)
    } while (!casValue(prev, next))
}
