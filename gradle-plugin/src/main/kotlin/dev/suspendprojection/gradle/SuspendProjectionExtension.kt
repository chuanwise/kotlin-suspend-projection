package dev.suspendprojection.gradle

import javax.inject.Inject
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

public enum class DirectImplementationEnforcement(
    internal val compilerValue: String,
) {
    GUARDED_DEFAULT("guarded-default"),
    STRICT("strict"),
}

public abstract class SuspendProjectionExtension @Inject constructor(
    objects: ObjectFactory,
) {
    public val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

    public val directImplementationEnforcement: Property<DirectImplementationEnforcement> =
        objects.property(DirectImplementationEnforcement::class.java)
            .convention(DirectImplementationEnforcement.GUARDED_DEFAULT)

    public val addDependencies: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    public val verifyArtifacts: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    public val emitCompatibilityGuard: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)

    public val emitInvalidPathGuard: Property<Boolean> =
        objects.property(Boolean::class.java).convention(true)
}
