package com.fish5.clickgui.settings

import com.fish5.features.Module
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Superclass of Settings.
 * @author Aton
 */
abstract class Setting<T>(
    val name: String,
    var description: String = "",
) : ReadWriteProperty<Module, T>, PropertyDelegateProvider<Module, ReadWriteProperty<Module, T>> {

    /**
     * Default value of the setting
     */
    abstract val default: T

    /**
     * Value of the setting
     */
    abstract var value: T

    protected var hidden = false

    fun hide(): Setting<T> {
        hidden = true
        return this
    }

    /**
     * Dependency for if it should be shown in the [click gui][Module].
     */
    protected var visibilityDependency: (() -> Boolean)? = null

    /**
     * Resets the setting to the default value
     */
    open fun reset() {
        value = default
    }

    /**
     * Card this setting is drawn inside on a module's settings page (grouping): it renders
     * stacked under [groupParent] in the parent's card instead of as its own card. Presentation only.
     */
    var groupParent: Setting<*>? = null

    val isVisible: Boolean
        get() {
            return (visibilityDependency?.invoke() ?: true) && !hidden
        }

    override operator fun provideDelegate(thisRef: Module, property: KProperty<*>): ReadWriteProperty<Module, T> =
        thisRef.registerSetting(this)

    override operator fun getValue(thisRef: Module, property: KProperty<*>): T =
        value

    override operator fun setValue(thisRef: Module, property: KProperty<*>, value: T) {
        this.value = value
    }

    companion object {

        fun <K : Setting<T>, T> K.withDependency(dependency: () -> Boolean): K {
            visibilityDependency = dependency
            return this
        }

        /** Draw this setting inside [parent]'s card (see [groupParent]). */
        fun <K : Setting<T>, T> K.inGroup(parent: Setting<*>): K {
            groupParent = parent
            return this
        }
    }
}