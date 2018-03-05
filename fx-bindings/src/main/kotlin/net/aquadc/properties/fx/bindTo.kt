package net.aquadc.properties.fx

import net.aquadc.properties.Property
import javafx.beans.property.Property as FxProperty

fun <T> FxProperty<in T>.bindTo(that: Property<T>) {
    this.value = that.getValue()
    that.addChangeListener { _, new ->
        this.value = new
    }
}
