@file:JvmName("Properties")
package net.aquadc.properties

import net.aquadc.properties.executor.InPlaceWorker
import net.aquadc.properties.executor.Worker
import net.aquadc.properties.internal.BiMappedProperty
import net.aquadc.properties.internal.ImmutableReferenceProperty
import net.aquadc.properties.internal.MappedProperty

/**
 * Returns new property with [transform]ed value which depends on [this] property value.
 */
fun <T, R> Property<T>.map(transform: (T) -> R): Property<R> = when {
    this.mayChange -> MappedProperty(this, transform, InPlaceWorker)
    else -> immutablePropertyOf(transform(value))
}

/**
 * Returns new property with [transform]ed value.
 * Calling [transform] from [worker] thread is not guaranteed:
 * it will be called in-place if there's no pre-mapped value.
 */
fun <T, R> Property<T>.mapOn(worker: Worker, transform: (T) -> R): Property<R> = when {
    this.mayChange -> MappedProperty(this, transform, worker)
    else -> immutablePropertyOf(transform(value))
}

/**
 * Returns new property with [transform]ed value depending on two properties' values.
 */
fun <T, U, R> Property<T>.mapWith(that: Property<U>, transform: (T, U) -> R): Property<R> = when {
    this.mayChange && that.mayChange -> {
        BiMappedProperty(this, that, transform)
    }
    !this.mayChange -> {
        val thisValue = this.value
        that.map { transform(thisValue, it) }
    }
    !that.mayChange -> {
        val thatValue = that.value
        this.map { transform(it, thatValue) }
    }
    else -> ImmutableReferenceProperty(transform(this.value, that.value))
}

/**
 * Calls [func] for each [Property.value] including initial.
 */
fun <T> Property<T>.onEach(func: (T) -> Unit) {
    if (isConcurrent) {
        val proxy = object : OnEach<T>() {

            override fun invoke(p1: T) =
                    func(p1)

        }
        addChangeListener(proxy)

        /*
          In the worst case scenario, change will happen in parallel just after subscription,
          invoke(T, T) will start running;
          we will CAS successfully and func will run in parallel.
        */

        // if calledRef is still not null
        // and has value of 'false',
        // then our function was not called yet.
        if (proxy.calledRef?.compareAndSet(false, true) == true) {
            func(value) // run function, ASAP!
            proxy.calledRef = null
        } // else we have more fresh value, don't call func
    } else {
        addChangeListener { _, new -> func(new) }
        func(value)
    }
}
