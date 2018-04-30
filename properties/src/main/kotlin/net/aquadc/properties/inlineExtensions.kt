@file:Suppress("NOTHING_TO_INLINE")
@file:JvmName("PropertiesInline")
package net.aquadc.properties

import android.os.Looper
import javafx.application.Platform
import net.aquadc.properties.internal.*
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import kotlin.reflect.KProperty

/**
 * Observer for [Property]<T>.
 */
typealias ChangeListener<T> = (old: T, new: T) -> Unit
// typealias just takes place in metadata, let it live with inline functions


//
// Boolean
//

/**
 * Returns inverted view on this property.
 */
@Suppress("UNCHECKED_CAST")
inline operator fun Property<Boolean>.not(): Property<Boolean> =
        map(UnaryNotBinaryAnd)

/**
 * Returns a view on [this] && [that].
 */
@Suppress("UNCHECKED_CAST")
inline infix fun Property<Boolean>.and(that: Property<Boolean>): Property<Boolean> =
        mapWith(that, UnaryNotBinaryAnd)

/**
 * Returns a view on [this] || [that].
 */
@Suppress("UNCHECKED_CAST")
inline infix fun Property<Boolean>.or(that: Property<Boolean>): Property<Boolean> =
        mapWith(that, OrBooleans as (Boolean, Boolean) -> Boolean)

/**
 * Returns a view on [this] ^ [that].
 */
@Suppress("UNCHECKED_CAST")
inline infix fun Property<Boolean>.xor(that: Property<Boolean>): Property<Boolean> =
        mapWith(that, XorBooleans as (Boolean, Boolean) -> Boolean)

/**
 * Sets [this] value to `true`.
 */
inline fun MutableProperty<Boolean>.set() { value = true }


/**
 * Sets [this] value to `false`.
 */
inline fun MutableProperty<Boolean>.clear() { value = false }

/**
 * Every time property becomes set (`true`),
 * it will be unset (`false`) and [action] will be performed.
 */
inline fun MutableProperty<Boolean>.clearEachAnd(crossinline action: () -> Unit): Unit = onEach {
    if (it) {
        value = false
        action()
    }
}


//
// CharSequence
//

/**
 * Returns a property representing [CharSequence.length].
 */
@Suppress("UNCHECKED_CAST")
inline val Property<CharSequence>.length: Property<Int> get() = map(CharSeqLength as (CharSequence) -> Int)

/**
 * Returns a property representing emptiness ([CharSequence.isEmpty]).
 */
inline val Property<CharSequence>.isEmpty: Property<Boolean> get() = map(CharSeqBooleanFunc.Empty)

/**
 * Returns a property representing non-emptiness ([CharSequence.isNotEmpty]).
 */
inline val Property<CharSequence>.isNotEmpty: Property<Boolean> get() = map(CharSeqBooleanFunc.NotEmpty)

/**
 * Returns a property representing blankness ([CharSequence.isBlank]).
 */
inline val Property<CharSequence>.isBlank: Property<Boolean> get() = map(CharSeqBooleanFunc.Blank)

/**
 * Returns a property representing non-blankness ([CharSequence.isNotBlank]).
 */
inline val Property<CharSequence>.isNotBlank: Property<Boolean> get() = map(CharSeqBooleanFunc.NotBlank)

/**
 * Returns a property representing a trimmed CharSequence ([CharSequence.trim]).
 */
@Suppress("UNCHECKED_CAST")
inline val Property<CharSequence>.trimmed: Property<CharSequence> get() = map(TrimmedCharSeq as (CharSequence) -> CharSequence)


//
// common
//

/**
 * Returns a read-only view on this property hiding its original type.
 */
@Suppress("UNCHECKED_CAST")
inline fun <T> Property<T>.readOnlyView() = map(Just as (T) -> T)

/**
 * Returns a property which notifies its subscribers only when old and new values are not equal.
 */
inline fun <T> Property<T>.distinct(noinline areEqual: (T, T) -> Boolean) =
        if (this.mayChange) DistinctPropertyWrapper(this, areEqual) else this

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
inline fun <T> Property<T>.debounced(delay: Long, unit: TimeUnit) =
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


//
// factories
//

/**
 * Returns new [MutableProperty] with value [value].
 * If [concurrent] is true, the property can be used from many threads.
 */
inline fun <T> mutablePropertyOf(value: T, concurrent: Boolean): MutableProperty<T> =
        if (concurrent) ConcMutableProperty(value)
        else UnsMutableProperty(value)

/**
 * Returns new multi-threaded [MutableProperty] with initial value [value].
 */
inline fun <T> concurrentMutablePropertyOf(value: T): MutableProperty<T> =
        ConcMutableProperty(value)

/**
 * Returns new single-threaded [MutableProperty] with initial value [value].
 */
inline fun <T> unsynchronizedMutablePropertyOf(value: T): MutableProperty<T> =
        UnsMutableProperty(value)

/**
 * Returns an immutable [Property] representing either `true` or `false`.
 */
inline fun immutablePropertyOf(value: Boolean): Property<Boolean> = when (value) {
    true -> ImmutableReferenceProperty.TRUE
    false -> ImmutableReferenceProperty.FALSE
}

/**
 * Returns an immutable property representing [value].
 */
inline fun <T> immutablePropertyOf(value: T): Property<T> =
        ImmutableReferenceProperty(value)


//
// bulk
//


/**
 * Maps a list of properties into a single [Property].
 */
inline fun <T, R> Collection<Property<T>>.mapValueList(noinline transform: (List<T>) -> R): Property<R> =
        if (all { it.isConcurrent }) MultiMappedProperty(this, transform)
        else MultiMappedProperty(this, transform)

/**
 * Folds a list of properties into a single [Property].
 */
@Suppress("UNCHECKED_CAST")
inline fun <T, R> Collection<Property<T>>.foldValues(initial: R, crossinline operation: (acc: R, T) -> R): Property<R> =
        mapValueList({ it: Any ->
            it as List<T>

            var accumulator = initial
            for (element in it) accumulator = operation(accumulator, element)
            accumulator as Any?
        } as (List<T>) -> R)

/**
 * Returns a property which holds first non-null property's value, or null, if all are nulls.
 */
@Suppress("UNCHECKED_CAST")
inline fun <T> Collection<Property<T>>.firstValueOrNull(crossinline predicate: (T) -> Boolean): Property<T?> =
        mapValueList({ it: Any ->
            it as List<T>

            it.firstOrNull(predicate) as Any?
        } as (List<T>) -> T?)

/**
 * Returns a property which holds a filtered collection.
 */
@Suppress("UNCHECKED_CAST")
inline fun <T> Collection<Property<T>>.filterValues(crossinline predicate: (T) -> Boolean): Property<List<T>> =
        mapValueList({ it: Any ->
            it as List<T>

            it.filter(predicate) as Any?
        } as (List<T>) -> List<T>)

/**
 * Returns a property which holds `true` if [this] contains a property holding [value].
 */
@Suppress("UNCHECKED_CAST")
inline fun <T> Collection<Property<T>>.containsValue(value: T): Property<Boolean> =
        mapValueList({ it: Any ->
            it as List<T>

            it.contains(value) as Any?
        } as (List<T>) -> Boolean)

/**
 * Returns a property which holds `true` if [this] contains a property holding all [values].
 */
@Suppress("UNCHECKED_CAST")
inline fun <T> Collection<Property<T>>.containsAllValues(values: Collection<T>): Property<Boolean> =
        mapValueList({ it: Any ->
            it as List<T>

            it.containsAll(values) as Any?
        } as (List<T>) -> Boolean)

/**
 * Returns a property which holds `true` if all properties' values in [this] are conforming to [predicate].
 */
@Suppress("UNCHECKED_CAST")
inline fun <T> Collection<Property<T>>.allValues(crossinline predicate: (T) -> Boolean): Property<Boolean> =
        mapValueList({ it: Any ->
            it as List<T>

            it.all(predicate) as Any?
        } as (List<T>) -> Boolean)

/**
 * Returns a property which holds `true` if any of properties' values in [this] conforms to [predicate].
 */
@Suppress("UNCHECKED_CAST")
inline fun <T> Collection<Property<T>>.anyValue(crossinline predicate: (T) -> Boolean): Property<Boolean> =
        mapValueList({ it: Any ->
            @Suppress("UNCHECKED_CAST")
            it as List<T>

            it.any(predicate) as Any?
        } as (List<T>) -> Boolean)


//
// Delegates
//

/**
 * Returns value of this [Property] when used as a Kotlin property delegate.
 */
inline operator fun <T> Property<T>.getValue(thisRef: Any?, property: KProperty<*>): T {
    return value
}

/**
 * Sets value of this [MutableProperty] when used as a Kotlin property delegate.
 */
inline operator fun <T> MutableProperty<T>.setValue(thisRef: Any?, property: KProperty<*>, value: T) {
    this.value = value
}
