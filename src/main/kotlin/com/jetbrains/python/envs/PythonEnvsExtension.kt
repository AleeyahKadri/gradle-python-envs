package com.jetbrains.python.envs

import org.gradle.api.InvalidUserDataException
import java.io.File
import java.net.URL

/**
 * Project extension to configure Python build environment.
 */
open class PythonEnvsExtension {
    var bootstrapDirectory: File? = null
    var envsDirectory: File? = null

    var zipRepository: URL? = null
    var shouldUseZipsFromRepository: Boolean = false

    var _64Bits: Boolean = true  // By default 64 bit envs should be installed
    var condaDefaultVersion: String = "Miniconda2-latest"
    var pypyDefaultVersion: String = "pypy2.7-5.8.0"
    @Suppress("unused")
    var pipInstallOptions: String = "--trusted-host pypi.python.org --trusted-host pypi.org --trusted-host files.pythonhosted.org"

    val pythons: MutableList<Python> = mutableListOf()
    val condas: MutableList<Conda> = mutableListOf()
    val condaEnvs: MutableList<CondaEnv> = mutableListOf()
    val virtualEnvs: MutableList<VirtualEnv> = mutableListOf()
    val pythonsFromZip: MutableList<Python> = mutableListOf()

    val CONDA_PREFIX = "CONDA_"

    /**
     * @param envName name of environment like "env_for_django"
     * @param version py version like "3.4"
     * @param packages collection of py packages to install
     * @param patchFileUri URI of a patch to apply when building Python (see the `python-build`'s `-p` option). Absolute paths are also accepted.
     */
    @JvmOverloads
    fun python(
        envName: String,
        version: String,
        architecture: String? = null,
        packages: List<String>? = null,
        patchFileUri: String? = null
    ) {
        if (zipRepository != null && shouldUseZipsFromRepository) {
            if (patchFileUri != null) {
                throw InvalidUserDataException("A patch is defined for a pre-built Python")
            }
            pythonFromZip(envName, getUrlFromRepository("python", version, architecture), "python", packages)
        } else {
            pythons.add(Python(envName, bootstrapDirectory!!, EnvType.PYTHON, version, is64(architecture), packages, null, patchFileUri))
        }
    }

    fun python(envName: String, version: String, packages: List<String>?) {
        python(envName, version, null, packages)
    }

    /**
     * @see python
     * @param urlToArchive URL link to archive with environment
     */
    @JvmOverloads
    fun pythonFromZip(
        envName: String,
        urlToArchive: URL,
        type: String? = null,
        packages: List<String>? = null
    ) {
        pythonsFromZip.add(
            Python(
                envName,
                bootstrapDirectory!!,
                EnvType.fromString(type),
                null,
                null,
                packages,
                urlToArchive
            )
        )
    }

    /**
     * @see python
     * @param sourceEnvName name of inherited environment like "env_for_django"
     */
    @JvmOverloads
    fun virtualenv(envName: String, sourceEnvName: String, packages: List<String>? = null) {
        val pythonEnv = (pythons + pythonsFromZip).find { it.name == sourceEnvName }
        if (pythonEnv != null) {
            virtualEnvs.add(VirtualEnv(envName, envsDirectory!!, pythonEnv, packages))
        } else {
            println("Specified environment '$sourceEnvName' for virtualenv '$envName' isn't found")
        }
    }

    /**
     * @see python
     */
    @JvmOverloads
    fun conda(
        envName: String,
        version: String,
        architecture: String? = null,
        packages: List<String>? = null
    ) {
        val pipPackages = packages?.filter { !it.startsWith(CONDA_PREFIX) }
        val condaPackages = packages?.filter { it.startsWith(CONDA_PREFIX) }
            ?.map { it.substring(CONDA_PREFIX.length) }
        condas.add(Conda(envName, bootstrapDirectory!!, version, is64(architecture), pipPackages, condaPackages))
    }

    fun conda(envName: String, version: String, packages: List<String>?) {
        conda(envName, version, null, packages)
    }

    fun conda(envName: String, packages: List<String>?) {
        conda(envName, condaDefaultVersion, null, packages)
    }

    /**
     * @see python
     * @param sourceEnvName name of inherited environment like "env_for_django"
     */
    @JvmOverloads
    fun condaenv(
        envName: String,
        version: String,
        sourceEnvName: String? = null,
        packages: List<String>? = null
    ) {
        val pipPackages = packages?.filter { !it.startsWith(CONDA_PREFIX) }
        val condaPackages = packages?.filter { it.startsWith(CONDA_PREFIX) }
            ?.map { it.substring(CONDA_PREFIX.length) }
        if (sourceEnvName == null) {
            conda(condaDefaultVersion, null as List<String>?)
        }
        val condaEnv = condas.find { it.name == (sourceEnvName ?: condaDefaultVersion) }

        if (condaEnv != null) {
            condaEnvs.add(CondaEnv(envName, envsDirectory!!, condaEnv, version, pipPackages, condaPackages))
        } else {
            println("Specified environment '$sourceEnvName' for condaenv '$envName' isn't found")
        }
    }

    fun condaenv(envName: String, version: String, packages: List<String>) {
        condaenv(envName, version, null, packages)
    }

    /**
     * @see python
     */
    @JvmOverloads
    fun jython(envName: String, packages: List<String>? = null) {
        pythons.add(Python(envName, bootstrapDirectory!!, EnvType.JYTHON, null, null, packages))
    }

    /**
     * @see python
     */
    @JvmOverloads
    fun pypy(envName: String, version: String? = null, packages: List<String>? = null) {
        pythons.add(
            Python(
                envName,
                bootstrapDirectory!!,
                EnvType.PYPY,
                version ?: pypyDefaultVersion,
                null,
                packages
            )
        )
    }

    fun pypy(envName: String, packages: List<String>) {
        pypy(envName, null, packages)
    }

    /**
     * @see python
     */
    @JvmOverloads
    fun ironpython(
        envName: String,
        architecture: String? = null,
        packages: List<String>? = null,
        urlToArchive: URL? = null
    ) {
        val urlToIronPythonZip = URL("https://github.com/IronLanguages/ironpython2/releases/download/ipy-2.7.9/IronPython.2.7.9.zip")
        pythonsFromZip.add(
            Python(
                envName,
                bootstrapDirectory!!,
                EnvType.IRONPYTHON,
                null,
                is64(architecture),
                packages,
                urlToArchive ?: urlToIronPythonZip
            )
        )
    }

    fun ironpython(envName: String, packages: List<String>, urlToArchive: URL? = null) {
        ironpython(envName, null, packages, urlToArchive)
    }

    fun condaPackage(packageName: String): String {
        return CONDA_PREFIX + packageName
    }

    private fun is64(architecture: String?): Boolean {
        return if (architecture == null) _64Bits else !(architecture == "32")
    }

    private fun getUrlFromRepository(type: String, version: String, architecture: String? = null): URL {
        if (zipRepository == null) throw IllegalStateException("zipRepository is not set")
        return zipRepository!!.toURI().resolve("$type-$version-${architecture ?: if (_64Bits) "64" else "32"}.zip").toURL()
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
        fun fromString(type: String?): EnvType? {
            return if (type == null) null else valueOf(type.uppercase())
        }
    }
}


open class Python(
    val name: String,
    dir: File,
    val type: EnvType? = null,
    val version: String? = null,
    val is64: Boolean? = true,
    val packages: List<String>? = null,
    val url: URL? = null,
    val patchFileUri: String? = null
) {
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
    version: String,
    is64: Boolean?,
    pipPackages: List<String>?,
    val condaPackages: List<String>?
) : Python(name, dir, EnvType.CONDA, version, is64, pipPackages)


class CondaEnv(
    name: String,
    dir: File,
    val sourceEnv: Conda,
    version: String,
    pipPackages: List<String>?,
    condaPackages: List<String>?
) : Conda(name, dir, version, sourceEnv.is64, pipPackages, condaPackages)
