import org.gradle.api.GradleException
import org.gradle.api.Project
import java.util.Properties

/**
 * The Crown fork's identity (fork.properties): package name, version, update repository, changelog and APK name.
 * nb4a.properties stays identical to upstream; its VERSION_NAME is the Throne base shown next to the fork version.
 */
class ForkIdentity(
    val packageName: String,
    val versionName: String,
    val updateRepository: String,
    val changelog: String,
    val artifactName: String,
) {
    private val parts: List<Int>

    init {
        val match = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(versionName)
            ?: throw GradleException("fork.properties: VERSION_NAME must be MAJOR.MINOR.PATCH, got $versionName")
        parts = match.groupValues.drop(1).map { it.toInt() }
        if (parts[0] > 20 || parts[1] > 99 || parts[2] > 99) {
            throw GradleException("fork.properties: VERSION_NAME $versionName is out of range (MAJOR 0..20, MINOR/PATCH 0..99)")
        }
        if (!updateRepository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
            throw GradleException("fork.properties: UPDATE_REPOSITORY must be owner/name, got $updateRepository")
        }
    }

    /**
     * ((MAJOR·100 + MINOR)·100 + PATCH)·1000 + [build]: 999 for a stable release, 1..998 for its previews, so every
     * version outranks the previous one and its own previews without a separately maintained VERSION_CODE.
     */
    fun versionCode(build: Int): Int = ((parts[0] * 100 + parts[1]) * 100 + parts[2]) * 1000 + build
}

/** Read on every call, like requireMetadata: buildSrc statics live as long as the Gradle daemon. */
fun Project.forkIdentity(): ForkIdentity {
    val properties = Properties().apply {
        rootProject.file("fork.properties").inputStream().use { load(it) }
    }
    fun require(name: String) = properties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("fork.properties: $name is missing")
    return ForkIdentity(
        packageName = require("PACKAGE_NAME"),
        versionName = require("VERSION_NAME"),
        updateRepository = require("UPDATE_REPOSITORY"),
        changelog = require("CHANGELOG"),
        artifactName = require("ARTIFACT_NAME"),
    )
}
