package com.code2hack.eyeprojector.directhost.anchor.execution

import java.lang.reflect.InvocationTargetException

internal sealed interface AnchorProviderInstallation {
    data object Missing : AnchorProviderInstallation
    data object Installed : AnchorProviderInstallation
    data class Failed(val cause: Throwable) : AnchorProviderInstallation
}

/** Actual optional-provider reflection, shared by the runtime and named-class fixture checks. */
internal object AnchorRegistrationLoader {
    fun <T : Any> install(name: String, expected: Class<T>, loader: ClassLoader? = expected.classLoader,
        install: (T) -> Unit): AnchorProviderInstallation {
        // Only this direct, non-initializing lookup may classify the optional provider as absent.
        val provider = try { Class.forName(name, false, loader) }
        catch (_: ClassNotFoundException) { return AnchorProviderInstallation.Missing }
        catch (error: Throwable) { return failure(error) }
        return try {
            val instance = expected.cast(provider.getDeclaredConstructor().newInstance())!!
            install(instance)
            AnchorProviderInstallation.Installed
        } catch (error: Throwable) { failure(error) }
    }

    private fun failure(error: Throwable): AnchorProviderInstallation.Failed {
        var cause = error
        while (true) {
            // Unwrap framework wrappers only; an ordinary exception's cause remains its context.
            cause = when (cause) {
                is InvocationTargetException -> cause.targetException ?: break
                is ExceptionInInitializerError -> cause.exception ?: break
                else -> break
            }
        }
        // Reflection can wrap these too. Propagate the original fatal object, never unavailable.
        if (cause is VirtualMachineError) throw cause
        if (cause is ThreadDeath) throw cause
        return AnchorProviderInstallation.Failed(cause)
    }
}
