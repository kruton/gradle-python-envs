package com.jetbrains.python.envs

import org.gradle.api.InvalidUserDataException
import java.io.File
import java.net.URI
import java.net.URL

/**
 * Project extension to configure Python build environment.
 *
 */
open class PythonEnvsExtension {
    var bootstrapDirectory: File? = null
    var envsDirectory: File? = null

    var zipRepository: URL? = null
    var shouldUseZipsFromRepository: Boolean = false

    var is64Bits: Boolean = true  // By default 64 bit envs should be installed
    var condaDefaultVersion: String = "Miniconda2-latest"
    var pypyDefaultVersion: String = "pypy2.7-5.8.0"
    // No direct equivalent for @SuppressWarnings("unused") in Kotlin for unused properties in this context,
    // but the compiler will warn if it's truly unused elsewhere.
    var pipInstallOptions: String = "--trusted-host pypi.python.org --trusted-host pypi.org --trusted-host files.pythonhosted.org"

    val pythons: MutableList<Python> = mutableListOf()
    val condas: MutableList<Conda> = mutableListOf()
    val condaEnvs: MutableList<CondaEnv> = mutableListOf()
    val virtualEnvs: MutableList<VirtualEnv> = mutableListOf()
    val pythonsFromZip: MutableList<Python> = mutableListOf()

    val CONDA_PREFIX: String = "CONDA_"

    /**
     * @param envName name of environment like "env_for_django"
     * @param version py version like "3.4"
     * @param packages collection of py packages to install
     * @param patchFileUri URI of a patch to apply when building Python (see the `python-build`'s `-p` option). Absolute paths are also accepted.
     */
    @JvmOverloads
    fun python(envName: String,
               version: String,
               architecture: String? = null,
               packages: List<String>? = null,
               patchFileUri: String? = null) {
        val localBootstrapDirectory = bootstrapDirectory ?: throw IllegalStateException("bootstrapDirectory must be set")
        if (zipRepository != null && shouldUseZipsFromRepository) {
            if (patchFileUri != null) {
                throw InvalidUserDataException("A patch is defined for a pre-built Python")
            }
            val url = getUrlFromRepository("python", version, architecture)
                ?: throw InvalidUserDataException("Could not determine URL for pre-built python $version ($architecture)")
            pythonFromZip(envName, url, "python", packages)
        } else {
            pythons.add(Python(envName, localBootstrapDirectory, EnvType.PYTHON, version, is64(architecture), packages, null, patchFileUri))
        }
    }

    // Overload provided by @JvmOverloads now
    // fun python(envName: String, version: String, packages: List<String>? = null) {
    //     python(envName, version, null, packages)
    // }

    /**
     * @see python
     * @param urlToArchive URL link to archive with environment
     */
    @JvmOverloads
    fun pythonFromZip(envName: String,
                      urlToArchive: URL,
                      type: String? = null,
                      packages: List<String>? = null) {
        val localBootstrapDirectory = bootstrapDirectory ?: throw IllegalStateException("bootstrapDirectory must be set")
        pythonsFromZip.add(Python(
            envName,
            localBootstrapDirectory,
            EnvType.fromString(type),
            null,
            null,
            packages,
            urlToArchive
        ))
    }

    /**
     * @see python
     * @param sourceEnvName name of inherited environment like "env_for_django"
     */
    @JvmOverloads
    fun virtualenv(envName: String, sourceEnvName: String, packages: List<String>? = null) {
        val localEnvsDirectory = envsDirectory ?: throw IllegalStateException("envsDirectory must be set")
        val pythonEnv = (pythons + pythonsFromZip).find { it.name == sourceEnvName }
        if (pythonEnv != null) {
            virtualEnvs.add(VirtualEnv(envName, localEnvsDirectory, pythonEnv, packages))
        } else {
            println("Specified environment '$sourceEnvName' for virtualenv '$envName' isn't found")
        }
    }

    /**
     * @see python
     */
    @JvmOverloads
    fun conda(envName: String,
              version: String,
              architecture: String? = null,
              packages: List<String>? = null) {
        val localBootstrapDirectory = bootstrapDirectory ?: throw IllegalStateException("bootstrapDirectory must be set")
        val pipPackages = packages?.filter { !it.startsWith(CONDA_PREFIX) }
        val condaPackages = packages?.filter { it.startsWith(CONDA_PREFIX) }
            ?.map { it.substring(CONDA_PREFIX.length) }
        condas.add(Conda(envName, localBootstrapDirectory, version, is64(architecture), pipPackages, condaPackages))
    }

    // Overload provided by @JvmOverloads
    // fun conda(envName: String, version: String, packages: List<String>? = null) {
    //     conda(envName, version, null, packages)
    // }

    @JvmOverloads
    fun conda(envName: String, packages: List<String>? = null) {
        conda(envName, condaDefaultVersion, null, packages)
    }

    /**
     * @see python
     * @param sourceEnvName name of inherited environment like "env_for_django"
     */
    @JvmOverloads
    fun condaenv(envName: String,
                 version: String,
                 sourceEnvName: String? = null,
                 packages: List<String>? = null) {
        val localEnvsDirectory = envsDirectory ?: throw IllegalStateException("envsDirectory must be set")
        val pipPackages = packages?.filter { !it.startsWith(CONDA_PREFIX) }
        val condaPackages = packages?.filter { it.startsWith(CONDA_PREFIX) }
            ?.map { it.substring(CONDA_PREFIX.length) }

        val actualSourceEnvName = sourceEnvName ?: condaDefaultVersion
        if (sourceEnvName == null && condas.none { it.name == condaDefaultVersion }) {
            // If no source is specified and the default doesn't exist, create the default conda env first
            conda(condaDefaultVersion)
        }
        val condaEnv = condas.find { it.name == actualSourceEnvName }

        if (condaEnv != null) {
            condaEnvs.add(CondaEnv(envName, localEnvsDirectory, condaEnv, version, pipPackages, condaPackages))
        } else {
            // This case might be less likely now due to the check above, but kept for safety
            println("Specified environment '$actualSourceEnvName' for condaenv '$envName' isn't found")
        }
    }

    // Overload provided by @JvmOverloads
    // fun condaenv(envName: String, version: String, packages: List<String>?) {
    //     condaenv(envName, version, null, packages)
    // }

    /**
     * @see python
     */
    @JvmOverloads
    fun jython(envName: String, packages: List<String>? = null) {
        val localBootstrapDirectory = bootstrapDirectory ?: throw IllegalStateException("bootstrapDirectory must be set")
        pythons.add(Python(envName, localBootstrapDirectory, EnvType.JYTHON, null, null, packages))
    }

    /**
     * @see python
     */
    @JvmOverloads
    fun pypy(envName: String, version: String? = null, packages: List<String>? = null) {
        val localBootstrapDirectory = bootstrapDirectory ?: throw IllegalStateException("bootstrapDirectory must be set")
        pythons.add(Python(
            envName,
            localBootstrapDirectory,
            EnvType.PYPY,
            version ?: pypyDefaultVersion,
            null, // pypy-build does not support architecture specification AFAIK
            packages
        ))
    }

    // Overload provided by @JvmOverloads
    // fun pypy(envName: String, packages: List<String>?) {
    //     pypy(envName, null, packages)
    // }

    /**
     * @see python
     */
    @JvmOverloads
    fun ironpython(envName: String,
                   architecture: String? = null,
                   packages: List<String>? = null,
                   urlToArchive: URL? = null) {
        val localBootstrapDirectory = bootstrapDirectory ?: throw IllegalStateException("bootstrapDirectory must be set")
        // Consider making this URL configurable or checking its validity
        val urlToIronPythonZip = URI("https://github.com/IronLanguages/ironpython2/releases/download/ipy-2.7.9/IronPython.2.7.9.zip").toURL()
        pythonsFromZip.add(Python(
            envName,
            localBootstrapDirectory,
            EnvType.IRONPYTHON,
            null, // IronPython version is often tied to the zip
            is64(architecture),
            packages,
            urlToArchive ?: urlToIronPythonZip
        ))
    }

    // Overload provided by @JvmOverloads
    // fun ironpython(envName: String, packages: List<String>?, urlToArchive: URL? = null) {
    //     ironpython(envName, null, packages, urlToArchive)
    // }

    fun condaPackage(packageName: String): String {
        return CONDA_PREFIX + packageName
    }

    private fun is64(architecture: String?): Boolean {
        return architecture?.let { it != "32" } ?: is64Bits
    }

    private fun getUrlFromRepository(type: String, version: String, architecture: String? = null): URL? {
        val repoUri = zipRepository?.toURI() ?: return null
        val archSuffix = architecture ?: (if (is64Bits) "64" else "32")
        return repoUri.resolve("$type-$version-$archSuffix.zip").toURL()
    }
}


enum class EnvType {
    PYTHON,
    CONDA,
    JYTHON,
    PYPY,
    IRONPYTHON,
    VIRTUALENV;
    // TODO non-python virtualenv?

    companion object {
        // Making fromString safer against invalid inputs
        fun fromString(type: String?): EnvType? {
            return type?.let {
                try {
                    valueOf(it.uppercase())
                } catch (e: IllegalArgumentException) {
                    println("Warning: Unknown EnvType string '$type'")
                    null // Or throw an exception, depending on desired strictness
                }
            }
        }
    }
}


// Use 'open' to allow inheritance. Data classes are final by default.
// Made properties val as they seem immutable after creation.
// Use nullable types for optional parameters.
open class Python(
    val name: String,
    dir: File, // Base directory where envDir will be created
    val type: EnvType?,
    val version: String?,
    val is64: Boolean?, // Nullable because not all types use it (e.g., Jython, PyPy maybe)
    val packages: List<String>?,
    val url: URL? = null,
    val patchFileUri: String? = null
) {
    // Calculated property
    val envDir: File = File(dir, name)
}


class VirtualEnv(
    name: String,
    dir: File,
    val sourceEnv: Python,
    packages: List<String>?
) : Python(name, dir, EnvType.VIRTUALENV, sourceEnv.version, sourceEnv.is64, packages)


open class Conda(
    name: String,
    dir: File,
    version: String?, // Conda version itself (e.g., Miniconda version)
    is64: Boolean?,
    pipPackages: List<String>?,
    val condaPackages: List<String>?
) : Python(name, dir, EnvType.CONDA, version, is64, pipPackages) // Passing pipPackages as 'packages' to Python base


class CondaEnv(
    name: String,
    dir: File,
    val sourceEnv: Conda,
    version: String?, // Python version for the environment
    pipPackages: List<String>?,
    condaPackages: List<String>?
) : Conda(name, dir, version, sourceEnv.is64, pipPackages, condaPackages) // Version here is Python version 
