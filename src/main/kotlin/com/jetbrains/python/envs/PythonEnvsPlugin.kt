package com.jetbrains.python.envs

import groovy.lang.Closure
import org.apache.tools.ant.filters.StringInputStream
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.util.VersionNumber
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.net.URL
import java.nio.file.Paths

class PythonEnvsPlugin : Plugin<Project> {

    companion object {
        private val osName = System.getProperty("os.name").replace(" ", "").let {
            if (it.contains("Windows")) "Windows" else it
        }

        private val isWindows = Os.isFamily(Os.FAMILY_WINDOWS)
        private val isUnix = Os.isFamily(Os.FAMILY_UNIX)
        private val isMacOsX = Os.isFamily(Os.FAMILY_MAC)

        private const val PIP_MINIMAL_SUPPORTED_VERSION = "3.9"

        private fun getUrlToDownloadConda(conda: Conda): URL {
            val repository = if (conda.version!!.lowercase().contains("miniconda")) "miniconda" else "archive"
            val arch = getArch()
            val ext = if (isWindows) "exe" else "sh"

            return URL("https://repo.continuum.io/$repository/${conda.version}-$osName-$arch.$ext")
        }

        private fun getArch(): String {
            var arch = System.getProperty("os.arch")
            arch = when {
                arch.matches(Regex("x86|i386|ia-32|i686")) -> "x86"
                arch.matches(Regex("x86_64|amd64|x64|x86-64")) -> "x86_64"
                arch.matches(Regex("arm|arm-v7|armv7|arm32")) -> "armv7l"
                arch.matches(Regex("aarch64|arm64|arm-v8")) -> if (isMacOsX) "arm64" else "aarch64"
                else -> arch
            }
            return arch
        }

        private fun getExecutable(executable: String, env: Python? = null, dir: File? = null, type: EnvType? = null): File {
            val pathString: String = when (type ?: env?.type) {
                EnvType.PYTHON, EnvType.CONDA -> {
                    when {
                        executable in listOf("pip", "virtualenv", "conda") ->
                            if (isWindows) "Scripts/$executable.exe" else "bin/$executable"
                        executable.startsWith("python") ->
                            if (isWindows) "$executable.exe" else "bin/$executable"
                        else ->
                            throw RuntimeException("$executable is not supported for ${env?.type} yet")
                    }
                }
                EnvType.JYTHON, EnvType.PYPY -> {
                    val exec = if (env?.type == EnvType.JYTHON && executable == "python") "jython" else executable
                    "bin/$exec${if (isWindows) ".exe" else ""}"
                }
                EnvType.IRONPYTHON -> {
                    if (executable in listOf("ipy", "python")) {
                        "net45/${if (env?.is64 == true) "ipy.exe" else "ipy32.exe"}"
                    } else {
                        "Scripts/$executable.exe"
                    }
                }
                EnvType.VIRTUALENV -> {
                    if (isWindows) "Scripts/$executable.exe" else "bin/$executable"
                }
                else -> throw RuntimeException("${env?.type} env type is not supported yet")
            }

            return File(dir ?: env?.envDir, pathString)
        }

        private fun getPipFile(project: Project, versionStr: String): File {
            val version = VersionNumber.parse(versionStr)
            val name: String
            val remoteUrl: String
            if (version < VersionNumber.parse(PIP_MINIMAL_SUPPORTED_VERSION)) {
                // use version-specific script
                val shortVersion = "${version.major}.${version.minor}"
                name = "get-pip-$shortVersion.py"
                remoteUrl = "https://bootstrap.pypa.io/pip/3.8/get-pip.py"
            } else {
                name = "get-pip.py"
                remoteUrl = "https://bootstrap.pypa.io/get-pip.py"
            }

            val file = File(project.buildDir, name)
            if (!file.exists()) {
                @Suppress("UNCHECKED_CAST")
                project.ant.invokeMethod("get", mapOf("dest" to file) as Any)
                project.ant.invokeMethod("url", mapOf("url" to remoteUrl) as Any)
            }
            return file
        }
    }

    private fun createInstallPythonBuildTask(project: Project, installDir: File): Task {
        return project.tasks.create("install_python_build").apply {
            onlyIf {
                isUnix && !installDir.exists()
            }

            doFirst {
                project.buildDir.mkdirs()
            }

            doLast {
                val pyenvZip = File(project.buildDir, "pyenv.zip")
                project.logger.quiet("Downloading latest pyenv from github")
                @Suppress("UNCHECKED_CAST")
                project.ant.invokeMethod("get", mapOf("dest" to pyenvZip) as Any)
                project.ant.invokeMethod("url", mapOf("url" to "https://github.com/pyenv/pyenv/archive/master.zip") as Any)

                val unzipFolder = File(project.buildDir, "python-build-tmp")
                val pathToPythonBuildInPyenv = "pyenv-master/plugins/python-build"

                project.logger.quiet("Unzipping python-build to $unzipFolder")
                project.copy {
                    it.from(project.zipTree(pyenvZip))
                    it.into(unzipFolder)
                    it.include("$pathToPythonBuildInPyenv/**")
                    it.eachFile { file ->
                        file.path = file.path.replaceFirst(pathToPythonBuildInPyenv, "")
                    }
                }

                project.logger.quiet("Installing python-build via bash to $installDir")
                project.exec {
                    it.commandLine = listOf("bash", File(unzipFolder, "install.sh").toString())
                    it.environment("PREFIX", installDir.toString())
                }

                project.logger.quiet("Removing garbage")
                unzipFolder.deleteRecursively()
                pyenvZip.delete()
            }
        }
    }

    private fun createPythonUnixTask(project: Project, env: Python): Task {
        return project.tasks.create("Bootstrap_${env.type}_'${env.name}'").apply {
            dependsOn("install_python_build")

            onlyIf {
                isUnix && (!env.envDir.exists() || isPythonInvalid(project, env))
            }

            doFirst {
                env.envDir.mkdirs()
                env.envDir.deleteRecursively()
            }

            doLast {
                project.logger.quiet("Creating ${env.type} '${env.name}' at ${env.envDir} directory")
                try {
                    project.exec {
                        it.executable = File(project.buildDir, "python-build/bin/python-build").toString()
                        if (env.patchFileUri != null) {
                            project.logger.quiet("Applying patch from ${env.patchFileUri} to ${env.name}")
                            it.standardInput = if (Paths.get(env.patchFileUri).isAbsolute) {
                                FileInputStream(env.patchFileUri)
                            } else {
                                StringInputStream(URI(env.patchFileUri).toURL().readText())
                            }
                            it.args = listOf("-p", env.version!!, env.envDir.toString())
                        } else {
                            it.args = listOf(env.version!!, env.envDir.toString())
                        }
                    }
                    project.logger.quiet("Successfully")
                } catch (e: Exception) {
                    if (isPythonInvalid(project, env)) {
                        project.logger.error(e.message)
                        throw GradleException(e.message ?: "Unknown error")
                    } else {
                        project.logger.warn(e.message)
                    }
                }

                upgradePipAndSetuptools(project, env)
                pipInstall(project, env, env.packages)
            }
        }
    }

    private fun createPythonWindowsTask(project: Project, env: Python): Task {
        return project.tasks.create("Bootstrap_${env.type}_'${env.name}'").apply {
            onlyIf {
                isWindows && (!env.envDir.exists() || isPythonInvalid(project, env))
            }

            doFirst {
                project.buildDir.mkdir()
                env.envDir.mkdirs()
                env.envDir.deleteRecursively()
            }

            doLast {
                project.logger.quiet("Creating ${env.type} '${env.name}' at ${env.envDir} directory")

                try {
                    val extension = if (VersionNumber.parse(env.version) >= VersionNumber.parse("3.5.0")) "exe" else "msi"
                    val filename = "python-${env.version}${if (env.is64 == true) (if (extension == "msi") "." else "-") + "amd64" else ""}.$extension"
                    val installer = File(project.buildDir, filename)

                    project.logger.quiet("Downloading $installer")
                    @Suppress("UNCHECKED_CAST")
                    project.ant.invokeMethod("get", mapOf("dest" to installer) as Any)
                    project.ant.invokeMethod("url", mapOf("url" to "https://www.python.org/ftp/python/${env.version}/$filename") as Any)

                    project.logger.quiet("Installing ${env.name}")
                    if (extension == "msi") {
                        project.exec {
                            it.commandLine = listOf("msiexec", "/i", installer.toString(), "/quiet", "TARGETDIR=${env.envDir.absolutePath}")
                        }
                    } else if (extension == "exe") {
                        project.mkdir(env.envDir)
                        project.exec {
                            it.executable = installer.toString()
                            it.args = listOf(installer.toString(), "/i", "/quiet", "TargetDir=${env.envDir.absolutePath}", "Include_launcher=0",
                                "InstallLauncherAllUsers=0", "Shortcuts=0", "AssociateFiles=0")
                        }
                    }

                    if (!getExecutable("pip", env).exists()) {
                        project.logger.quiet("Downloading & installing pip and setuptools")
                        project.exec {
                            it.executable = getExecutable("python", env).toString()
                            it.args = listOf(getPipFile(project, env.version!!).toString())
                        }
                    }
                    // It's better to save installer for good uninstall
                    // installer.delete()
                } catch (e: Exception) {
                    project.logger.error(e.message)
                    throw GradleException(e.message ?: "Unknown error")
                }

                pipInstall(project, env, env.packages)
            }
        }
    }

    private fun createJythonTask(project: Project, env: Python): Task {
        return project.tasks.create("Bootstrap_${env.type}_'${env.name}'").apply {
            onlyIf {
                !env.envDir.exists() || isPythonInvalid(project, env)
            }

            doFirst {
                env.envDir.deleteRecursively()
            }

            doLast {
                project.logger.quiet("Creating ${env.type} '${env.name}' at ${env.envDir} directory")

                project.javaexec {
                    it.mainClass.set("-jar")
                    it.args = listOf(project.configurations.getByName("jython").singleFile.toString(), "-s", "-d", env.envDir.toString(), "-t", "standard")
                }

                pipInstall(project, env, env.packages)
            }
        }
    }

    override fun apply(project: Project) {
        val envs = project.extensions.create("envs", PythonEnvsExtension::class.java)

        project.repositories.mavenCentral()

        project.configurations.create("jython")

        project.afterEvaluate {
            project.configurations.getByName("jython").incoming.beforeResolve {
                project.dependencies.add("jython", mapOf(
                    "group" to "org.python",
                    "name" to "jython-installer",
                    "version" to "2.7.1"
                ))
            }

            createInstallPythonBuildTask(project, File(project.buildDir, "python-build"))

            val pythonTask = project.tasks.create("build_pythons").apply {
                onlyIf { envs.pythons.isNotEmpty() }

                envs.pythons.forEach { env ->
                    when (env.type) {
                        EnvType.PYTHON -> {
                            when {
                                isUnix -> dependsOn(createPythonUnixTask(project, env))
                                isWindows -> dependsOn(createPythonWindowsTask(project, env))
                                else -> project.logger.error("Something is wrong with os: $osName")
                            }
                        }
                        EnvType.JYTHON -> {
                            dependsOn(createJythonTask(project, env))
                        }
                        EnvType.PYPY -> {
                            if (isUnix) {
                                dependsOn(createPythonUnixTask(project, env))
                            } else {
                                project.logger.warn("PyPy installation isn't supported for $osName, please use envFromZip instead")
                            }
                        }
                        else -> project.logger.error("${env.type} isn't supported yet")
                    }
                }
            }

            val pythonFromZipTask = project.tasks.create("build_pythons_from_zip").apply {
                onlyIf { envs.pythonsFromZip.isNotEmpty() }

                envs.pythonsFromZip.forEach { env ->
                    dependsOn(project.tasks.create("Bootstrap_${env.type ?: ""}_'${env.name}'_from_archive").apply {
                        onlyIf {
                            !env.envDir.exists() || isPythonInvalid(project, env)
                        }

                        doFirst {
                            project.buildDir.mkdir()
                            env.envDir.mkdirs()
                            env.envDir.deleteRecursively()
                        }

                        doLast {
                            try {
                                val urlString = env.url.toString()
                                val archiveName = urlString.substring(urlString.lastIndexOf('/') + 1)
                                if (!archiveName.endsWith("zip")) {
                                    throw RuntimeException("Wrong archive extension, only zip is supported")
                                }

                                val zipArchive = File(project.buildDir, archiveName)
                                project.logger.quiet("Downloading $archiveName archive from ${env.url}")
                                @Suppress("UNCHECKED_CAST")
                                project.ant.invokeMethod("get", mapOf("dest" to zipArchive) as Any)
                                project.ant.invokeMethod("url", mapOf("url" to env.url) as Any)

                                project.logger.quiet("Unzipping downloaded $archiveName archive")
                                @Suppress("UNCHECKED_CAST")
                                project.ant.invokeMethod("unzip", mapOf("src" to zipArchive, "dest" to env.envDir) as Any)

                                val files = env.envDir.listFiles()
                                if (files != null && files.size == 1) {
                                    val intermediateDir = files.first()
                                    if (!intermediateDir.isDirectory) {
                                        throw RuntimeException("Archive is wrong, ${env.url}")
                                    }
                                    @Suppress("UNCHECKED_CAST")
                                    project.ant.invokeMethod("move", mapOf("todir" to env.envDir) as Any)
                                    project.ant.invokeMethod("fileset", mapOf("dir" to intermediateDir) as Any)
                                }

                                if (env.type != null) {
                                    if (!getExecutable("pip", env).exists()) {
                                        project.logger.quiet("Downloading & installing pip and setuptools")
                                        project.exec {
                                            if (env.type == EnvType.IRONPYTHON) {
                                                it.executable = getExecutable("ipy", env).toString()
                                                it.args = listOf("-m", "ensurepip")
                                            } else {
                                                it.executable = getExecutable("python", env).toString()
                                                it.args = listOf(getPipFile(project, env.version!!).toString())
                                            }
                                        }
                                    }
                                    upgradePipAndSetuptools(project, env)
                                }

                                project.logger.quiet("Deleting $archiveName archive")
                                zipArchive.delete()
                            } catch (e: Exception) {
                                project.logger.error(e.message)
                                throw GradleException(e.message ?: "Unknown error")
                            }

                            pipInstall(project, env, env.packages)
                        }
                    })
                }
            }

            val virtualenvsTask = project.tasks.create("build_virtual_envs").apply {
                shouldRunAfter(pythonTask, pythonFromZipTask)

                onlyIf { envs.virtualEnvs.isNotEmpty() }

                envs.virtualEnvs.forEach { env ->
                    if (env.sourceEnv.type == EnvType.IRONPYTHON) {
                        project.logger.warn("IronPython doesn't support virtualenvs")
                        return@forEach
                    }

                    dependsOn(project.tasks.create("Create_virtualenv_'${env.name}'").apply {
                        onlyIf {
                            (!env.envDir.exists() || isPythonInvalid(project, env)) && env.sourceEnv.type != null
                        }

                        doFirst {
                            env.envDir.mkdirs()
                            env.envDir.deleteRecursively()
                        }

                        doLast {
                            project.logger.quiet("Installing needed virtualenv package")

                            pipInstall(project, env.sourceEnv, listOf("virtualenv"))

                            project.logger.quiet("Creating virtualenv from ${env.sourceEnv.name} at ${env.envDir}")
                            project.exec {
                                it.executable = getExecutable("virtualenv", env.sourceEnv).toString()
                                it.args = listOf(env.envDir.toString(), "--always-copy")
                                it.workingDir = env.sourceEnv.envDir
                            }

                            pipInstall(project, env, env.packages)
                        }
                    })
                }
            }

            val condaTask = project.tasks.create("build_condas").apply {
                onlyIf { envs.condas.isNotEmpty() }

                envs.condas.forEach { env ->
                    dependsOn(project.tasks.create("Bootstrap_${env.type}_'${env.name}'").apply {
                        onlyIf {
                            !env.envDir.exists() || isPythonInvalid(project, env)
                        }

                        doFirst {
                            project.buildDir.mkdir()
                            env.envDir.mkdirs()
                            env.envDir.deleteRecursively()
                        }

                        doLast {
                            val urlToConda = getUrlToDownloadConda(env)
                            val installer = File(project.buildDir, urlToConda.toString().split("/").last())

                            if (!installer.exists()) {
                                project.logger.quiet("Downloading ${installer.name}")
                                @Suppress("UNCHECKED_CAST")
                                project.ant.invokeMethod("get", mapOf("dest" to installer) as Any)
                                project.ant.invokeMethod("url", mapOf("url" to urlToConda) as Any)
                            }

                            project.logger.quiet("Bootstraping to ${env.envDir}")
                            project.exec {
                                if (isWindows) {
                                    it.commandLine = listOf(installer.toString(), "/InstallationType=JustMe", "/AddToPath=0", "/RegisterPython=0", "/S", "/D=${env.envDir}")
                                } else {
                                    it.commandLine = listOf("bash", installer.toString(), "-b", "-p", env.envDir.toString())
                                }
                            }

                            pipInstall(project, env, env.packages)
                            condaInstall(project, env, env.condaPackages)
                        }
                    })
                }
            }

            val condaEnvsTask = project.tasks.create("build_conda_envs").apply {
                shouldRunAfter(condaTask)

                onlyIf { envs.condaEnvs.isNotEmpty() }

                envs.condaEnvs.forEach { env ->
                    dependsOn(project.tasks.create("Create_conda_env_'${env.name}'").apply {
                        onlyIf {
                            !env.envDir.exists() || isPythonInvalid(project, env)
                        }

                        doFirst {
                            env.envDir.mkdirs()
                            env.envDir.deleteRecursively()
                        }

                        doLast {
                            project.logger.quiet("Creating condaenv '${env.name}' at ${env.envDir} directory")
                            project.exec {
                                it.executable = getExecutable("conda", env.sourceEnv).toString()
                                it.args = listOf("create", "-p", env.envDir.toString(), "-y", "python=${env.version}") +
                                        (env.condaPackages ?: emptyList())
                            }

                            pipInstall(project, env, env.packages)
                        }
                    })
                }
            }

            project.tasks.create("build_envs").apply {
                dependsOn(
                    pythonTask,
                    pythonFromZipTask,
                    virtualenvsTask,
                    condaTask,
                    condaEnvsTask
                )
            }
        }
    }

    private fun upgradePipAndSetuptools(project: Project, env: Python) {
        project.logger.quiet("Force upgrade pip and setuptools")
        val envs = project.extensions.findByName("envs") as PythonEnvsExtension
        val command = mutableListOf(
            getExecutable("python", env).toString(),
            "-m", "pip", "install"
        ).apply {
            addAll(envs.pipInstallOptions.split(" "))
            addAll(listOf("--upgrade", "--force", "pip", "setuptools"))
        }

        project.logger.quiet("Executing '${command.joinToString(" ")}'")
        val result = project.exec {
            it.commandLine = command
        }
        if (result.exitValue != 0) throw GradleException("pip & setuptools upgrade failed")
    }

    private fun pipInstall(project: Project, env: Python, packages: List<String>?) {
        if (packages == null || packages.isEmpty() || env.type == null) {
            return
        }
        project.logger.quiet("Installing packages via pip: $packages")

        val envs = project.extensions.findByName("envs") as PythonEnvsExtension
        val command = mutableListOf(
            getExecutable("pip", env).toString(),
            "install"
        ).apply {
            addAll(envs.pipInstallOptions.split(" "))
            addAll(packages)
        }
        project.logger.quiet("Executing '${command.joinToString(" ")}'")

        val result = project.exec {
            it.commandLine = command
        }
        if (result.exitValue != 0) throw GradleException("pip install failed")
    }

    private fun condaInstall(project: Project, conda: Conda, packages: List<String>?) {
        if (packages == null || packages.isEmpty()) {
            return
        }
        project.logger.quiet("Installing packages via conda: $packages")

        val command = mutableListOf(
            getExecutable("conda", conda).toString(),
            "install", "-y",
            "-p", conda.envDir.toString()
        ).apply {
            addAll(packages)
        }
        project.logger.quiet("Executing '${command.joinToString(" ")}'")

        val result = project.exec {
            it.commandLine = command
        }
        if (result.exitValue != 0) throw GradleException("conda install failed")
    }

    private fun isPythonValid(project: Project, env: Python): Boolean {
        val exec = getExecutable("python", env)
        if (!exec.exists()) return false

        val exitValue: Int
        try {
            exitValue = project.exec {
                it.commandLine = listOf(exec.toString(), "-c", "'print(1)'")
            }.exitValue
        } catch (ignored: Exception) {
            return false
        }

        return exitValue == 0
    }

    private fun isPythonInvalid(project: Project, env: Python): Boolean {
        return !isPythonValid(project, env)
    }
}
