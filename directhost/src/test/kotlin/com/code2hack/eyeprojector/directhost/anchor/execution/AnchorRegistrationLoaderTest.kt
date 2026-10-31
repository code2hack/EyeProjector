package com.code2hack.eyeprojector.directhost.anchor.execution

import org.junit.Assert.*
import org.junit.Test

/** Every failure below comes from a named class on the actual JVM reflection path. */
class AnchorRegistrationLoaderTest {
    private fun load(type: Class<*>) = AnchorRegistrationLoader.install(type.name, NamedProvider::class.java,
        install = NamedProvider::install)
    private fun failed(type: Class<*>, original: Throwable) {
        val result = load(type) as AnchorProviderInstallation.Failed
        assertSame(original, result.cause)
    }
    private fun fatal(type: Class<*>, original: Throwable) {
        val thrown = assertThrows(original.javaClass) { load(type) }
        assertSame(original, thrown)
    }

    @Test fun onlyAbsentOptionalClassIsMissing() {
        assertSame(AnchorProviderInstallation.Missing, AnchorRegistrationLoader.install(
            "com.code2hack.eyeprojector.AbsentRegistrationProviderForTest", NamedProvider::class.java,
            install = NamedProvider::install))
    }
    @Test fun actualConstructorAndInstallComplete() {
        ProviderFailures.successConstructed = false; ProviderFailures.successInstalled = false
        assertSame(AnchorProviderInstallation.Installed, load(SuccessfulNamedProvider::class.java))
        assertTrue(ProviderFailures.successConstructed); assertTrue(ProviderFailures.successInstalled)
    }
    @Test fun installClassNotFoundIsFailedWithItsExactCause() =
        failed(InstallMissingDependencyProvider::class.java, ProviderFailures.installMissing)
    @Test fun ordinaryConstructorFailureUnwrapsInvocationTarget() =
        failed(ConstructorFailureProvider::class.java, ProviderFailures.constructor)
    @Test fun ordinaryInstallFailureRetainsExceptionAndItsContext() =
        failed(InstallFailureProvider::class.java, ProviderFailures.install)
    @Test fun nonfatalStaticInitializerFailureRetainsOriginalCause() =
        failed(StaticFailureProvider::class.java, ProviderFailures.initializer)
    @Test fun initializerDependencyLookupIsNotOptionalProviderAbsence() {
        val result = load(StaticMissingDependencyProvider::class.java) as AnchorProviderInstallation.Failed
        assertTrue(result.cause is ClassNotFoundException)
        assertSame(ProviderFailures.observedDependencyFailure, result.cause)
    }
    @Test fun ordinaryConstructorClassNotFoundIsNotProviderAbsence() =
        failed(ConstructorMissingDependencyProvider::class.java, ProviderFailures.constructorMissing)
    @Test fun wrappedConstructorVmErrorPropagatesOriginalObject() =
        fatal(ConstructorVmProvider::class.java, ProviderFailures.constructorVm)
    @Test fun directInitializerVmErrorPropagatesOriginalObject() =
        fatal(StaticVmProvider::class.java, ProviderFailures.initializerVm)
    @Test fun directInstallVmErrorPropagatesOriginalObject() =
        fatal(InstallVmProvider::class.java, ProviderFailures.installVm)
    @Test fun wrappedConstructorThreadDeathPropagatesOriginalObject() =
        fatal(ConstructorThreadDeathProvider::class.java, ProviderFailures.constructorDeath)
    @Test fun directInitializerThreadDeathPropagatesOriginalObject() =
        fatal(StaticThreadDeathProvider::class.java, ProviderFailures.initializerDeath)
    @Test fun directInstallThreadDeathPropagatesOriginalObject() =
        fatal(InstallThreadDeathProvider::class.java, ProviderFailures.installDeath)
    @Test fun unsupportedProviderTypeFailsInsteadOfInstalling() {
        assertTrue((load(String::class.java) as AnchorProviderInstallation.Failed).cause is ClassCastException)
    }
    @Test fun missingDefaultConstructorRetainsReflectiveFailure() {
        assertTrue((load(NoDefaultProvider::class.java) as AnchorProviderInstallation.Failed).cause is NoSuchMethodException)
    }
}

internal interface NamedProvider { fun install() }
internal class SyntheticProviderVmFailure(message: String) : VirtualMachineError(message)
internal object ProviderFailures {
    var successConstructed = false
    var successInstalled = false
    val constructor = IllegalStateException("named constructor failed")
    val install = IllegalStateException("named install failed", ClassNotFoundException("retained exception context"))
    val initializer = IllegalStateException("named static initialization failed")
    val installMissing = ClassNotFoundException("dependency missing during install")
    val constructorMissing = ClassNotFoundException("dependency missing during construction")
    var observedDependencyFailure: ClassNotFoundException? = null
    val constructorVm = SyntheticProviderVmFailure("synthetic constructor VM failure")
    val initializerVm = SyntheticProviderVmFailure("synthetic initializer VM failure")
    val installVm = SyntheticProviderVmFailure("synthetic install VM failure")
    val constructorDeath = ThreadDeath()
    val initializerDeath = ThreadDeath()
    val installDeath = ThreadDeath()
}
internal class SuccessfulNamedProvider : NamedProvider {
    init { ProviderFailures.successConstructed = true }
    override fun install() { ProviderFailures.successInstalled = true }
}
internal class InstallMissingDependencyProvider : NamedProvider {
    override fun install(): Unit = throw ProviderFailures.installMissing
}
internal class ConstructorFailureProvider : NamedProvider {
    init { throw ProviderFailures.constructor }
    override fun install() = Unit
}
internal class ConstructorMissingDependencyProvider : NamedProvider {
    init { throw ProviderFailures.constructorMissing }
    override fun install() = Unit
}
internal class InstallFailureProvider : NamedProvider {
    override fun install(): Unit = throw ProviderFailures.install
}
internal class StaticFailureProvider : NamedProvider {
    companion object { init { throw ProviderFailures.initializer } }
    override fun install() = Unit
}
internal class StaticMissingDependencyProvider : NamedProvider {
    companion object {
        init {
            try { Class.forName("com.code2hack.eyeprojector.AbsentProviderDependencyForTest") }
            catch (failure: ClassNotFoundException) { ProviderFailures.observedDependencyFailure = failure; throw failure }
        }
    }
    override fun install() = Unit
}
internal class ConstructorVmProvider : NamedProvider {
    init { throw ProviderFailures.constructorVm }
    override fun install() = Unit
}
internal class StaticVmProvider : NamedProvider {
    companion object { init { throw ProviderFailures.initializerVm } }
    override fun install() = Unit
}
internal class InstallVmProvider : NamedProvider {
    override fun install(): Unit = throw ProviderFailures.installVm
}
internal class ConstructorThreadDeathProvider : NamedProvider {
    init { throw ProviderFailures.constructorDeath }
    override fun install() = Unit
}
internal class StaticThreadDeathProvider : NamedProvider {
    companion object { init { throw ProviderFailures.initializerDeath } }
    override fun install() = Unit
}
internal class InstallThreadDeathProvider : NamedProvider {
    override fun install(): Unit = throw ProviderFailures.installDeath
}
internal class NoDefaultProvider(val value: String) : NamedProvider { override fun install() = Unit }
