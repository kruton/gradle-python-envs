package com.jetbrains.python.envs

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCopyDetails
import org.gradle.kotlin.dsl.* // Import Kotlin DSL extensions
import org.gradle.util.VersionNumber
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Paths
import org.gradle.api.Action

class PythonEnvsPlugin : Plugin<Project> {

    // Companion object for static-like members
    companion object {
        private val osName: String = System.getProperty("os.name").replace(" ", "").let {
            if (it.contains("Windows", ignoreCase = true)) "Windows" else it
        }

        private val isWindows: Boolean = Os.isFamily(Os.FAMILY_WINDOWS)
        private val isUnix: Boolean = Os.isFamily(Os.FAMILY_UNIX)
        private val isMacOsX: Boolean = Os.isFamily(Os.FAMILY_MAC)

        private fun getUrlToDownloadConda(conda: Conda): URL {
            val repository = if (conda.version?.contains("miniconda", ignoreCase = true) == true) "miniconda" else "archive"
            val arch = getArch()
            val ext = if (isWindows) "exe" else "sh"

            return URI("https://repo.continuum.io/$repository/${conda.version}-$osName-$arch.$ext").toURL()
        }

        private fun getArch(): String {
            var arch = System.getProperty("os.arch")
            arch = when {
                arch.matches(Regex("x86|i386|ia-32|i686")) -> "x86"
                arch.matches(Regex("x86_64|amd64|x64|x86-64")) -> "x86_64"
                arch.matches(Regex("arm|arm-v7|armv7|arm32")) -> "armv7l"
                arch.matches(Regex("aarch64|arm64|arm-v8")) -> if (isMacOsX) "arm64" else "aarch64"
                else -> arch // Keep original if no match
            }
            return arch
        }

        private fun getExecutable(executable: String, env: Python? = null, dir: File? = null, type: EnvType? = null): File {
            val actualEnv = env ?: throw IllegalArgumentException("Environment must be provided if dir and type are not")
            val actualDir = dir ?: actualEnv.envDir
            val actualType = type ?: actualEnv.type

            val pathString = when (actualType) {
                EnvType.PYTHON, EnvType.CONDA -> when (executable) {
                    "pip", "virtualenv", "conda" -> if (isWindows) "Scripts/${executable}.exe" else "bin/${executable}"
                    else -> if (executable.startsWith("python")) {
                        if (isWindows) "${executable}.exe" else "bin/${executable}"
                    } else {
                        throw RuntimeException("$executable is not supported for $actualType yet")
                    }
                }
                EnvType.JYTHON, EnvType.PYPY -> {
                    val execName = if (actualType == EnvType.JYTHON && executable == "python") "jython" else executable
                    "bin/$execName${if (isWindows) ".exe" else ""}"
                }
                EnvType.IRONPYTHON -> when (executable) {
                    "ipy", "python" -> "net45/${if (actualEnv.is64 == true) "ipy.exe" else "ipy32.exe"}" // Assuming is64 defaults or is set
                    else -> "Scripts/${executable}.exe"
                }
                EnvType.VIRTUALENV -> if (isWindows) "Scripts/${executable}.exe" else "bin/${executable}"
                null -> throw RuntimeException("Environment type is null, cannot determine executable path")
            }

            return File(actualDir, pathString)
        }

        private fun getPipFile(project: Project): File {
            val file = project.buildDir.resolve("get-pip.py")
            if (!file.exists()) {
                project.ant.invokeMethod("get", mapOf(
                    "dest" to file,
                    "src" to URI("https://bootstrap.pypa.io/get-pip.py").toURL()
                ))
            }
            return file
        }

        // Helper to check if python executable exists and runs
        private fun isPythonValid(project: Project, env: Python): Boolean {
            val exec = try {
                getExecutable("python", env)
            } catch (e: Exception) {
                return false // Cannot determine executable
            }

            if (!exec.exists()) return false

            val result = project.exec {
                commandLine(exec.absolutePath, "-c", "print(1)")
                isIgnoreExitValue = true // Don't throw exception on non-zero exit
            }

            return result.exitValue == 0
        }

        // Renamed for clarity in Kotlin
        private fun isPythonInvalid(project: Project, env: Python): Boolean {
            return !isPythonValid(project, env)
        }
    }

    override fun apply(project: Project) {
        val envs = project.extensions.create<PythonEnvsExtension>("envs")

        // Configure default directories relative to the project
        envs.bootstrapDirectory = envs.bootstrapDirectory ?: project.layout.buildDirectory.dir("python-envs/bootstrap").get().asFile
        envs.envsDirectory = envs.envsDirectory ?: project.layout.projectDirectory.dir("python-envs/envs").asFile


        project.repositories {
            mavenCentral()
        }

        project.configurations.create("jython")

        project.afterEvaluate {
            // Configure Jython dependency only if needed
            if (envs.pythons.any { it.type == EnvType.JYTHON }) {
                project.dependencies {
                    add("jython", "org.python:jython-installer:2.7.1")
                }
            }

            val pythonBuildDir = project.layout.buildDirectory.dir("python-build").get().asFile
            val installPythonBuildTask = createInstallPythonBuildTask(project, pythonBuildDir)

            val pythonTask = tasks.register("build_pythons") {
                group = "Build Environment"
                description = "Builds configured Python/Jython/PyPy environments from source or distributions."
                onlyIf { envs.pythons.isNotEmpty() }

                envs.pythons.forEach { env ->
                    when (env.type) {
                        EnvType.PYTHON -> {
                            if (isUnix) {
                                dependsOn(createPythonUnixTask(project, env, installPythonBuildTask))
                            } else if (isWindows) {
                                dependsOn(createPythonWindowsTask(project, env))
                            } else {
                                logger.error("Unsupported OS for Python build: $osName")
                            }
                        }
                        EnvType.JYTHON -> dependsOn(createJythonTask(project, env))
                        EnvType.PYPY -> {
                            if (isUnix) {
                                dependsOn(createPythonUnixTask(project, env, installPythonBuildTask)) // Assuming PyPy uses python-build
                            } else {
                                logger.warn("PyPy installation via build isn't supported on $osName, consider using pythonFromZip.")
                            }
                        }
                        else -> logger.error("${env.type} is not supported in the 'python' block.") // Should not happen based on extension
                    }
                }
            }

            val pythonFromZipTask = tasks.register("build_pythons_from_zip") {
                group = "Build Environment"
                description = "Builds Python/IronPython environments from pre-built zip archives."
                onlyIf { envs.pythonsFromZip.isNotEmpty() }

                envs.pythonsFromZip.forEach { env ->
                    dependsOn(tasks.register("Bootstrap_${env.type ?: "Unknown"}_${env.name}_from_archive") {
                        onlyIf { env.url != null && (!env.envDir.exists() || isPythonInvalid(project, env)) }

                        doFirst {
                            project.buildDir.mkdirs()
                            if (env.envDir.exists()) env.envDir.deleteRecursively()
                            env.envDir.mkdirs()
                        }

                        doLast {
                            val url = env.url ?: return@doLast // Should be caught by onlyIf
                            try {
                                val archiveName = url.path.substring(url.path.lastIndexOf('/') + 1)
                                if (!archiveName.endsWith(".zip", ignoreCase = true)) {
                                    throw GradleException("Wrong archive extension, only zip is supported (URL: $url)")
                                }

                                val zipArchive = project.buildDir.resolve(archiveName)
                                logger.quiet("Downloading $archiveName archive from $url")
                                project.ant.invokeMethod("get", mapOf("dest" to zipArchive, "src" to url, "verbose" to true))

                                logger.quiet("Unzipping downloaded $archiveName archive to ${env.envDir}")
                                // Ensure target exists and is a directory
                                if (!env.envDir.exists()) env.envDir.mkdirs()
                                else if (!env.envDir.isDirectory) throw GradleException("Target unzip path is not a directory: ${env.envDir}")

                                project.copy {
                                    from(zipTree(zipArchive))
                                    into(env.envDir)
                                }

                                // Handle archives containing a single top-level directory
                                env.envDir.listFiles()?.let { files ->
                                    if (files.size == 1 && files[0].isDirectory) {
                                        val intermediateDir = files[0]
                                        logger.quiet("Moving contents from intermediate directory ${intermediateDir.name}")
                                        // Move contents, then delete the now-empty intermediate directory
                                        intermediateDir.listFiles()?.forEach { fileToMove ->
                                            val target = File(env.envDir, fileToMove.name)
                                            if (!fileToMove.renameTo(target)) {
                                                logger.warn("Could not move ${fileToMove.path} to ${target.path}")
                                                // Consider adding copy/delete fallback if rename fails
                                            }
                                        }
                                        intermediateDir.delete()
                                    }
                                }

                                if (env.type != null) {
                                     // Install pip if necessary (e.g., for IronPython or minimal zips)
                                    if (!getExecutable("pip", env).exists()) {
                                        logger.quiet("Attempting to install pip and setuptools")
                                        val pythonExec = getExecutable(if(env.type == EnvType.IRONPYTHON) "ipy" else "python", env)
                                        if (pythonExec.exists()) {
                                             if (env.type == EnvType.IRONPYTHON) {
                                                 project.exec {
                                                     executable(pythonExec.absolutePath)
                                                     args("-m", "ensurepip")
                                                     isIgnoreExitValue = true // ensurepip might fail if already present
                                                 }
                                            } else {
                                                project.exec {
                                                    executable(pythonExec.absolutePath)
                                                    args(getPipFile(project).absolutePath)
                                                    isIgnoreExitValue = true
                                                }
                                            }
                                        } else {
                                            logger.warn("Could not find python executable at ${pythonExec.path} to install pip.")
                                        }
                                    }
                                    // Upgrade pip even if it exists, as it might be outdated
                                    if (getExecutable("pip", env).exists()) {
                                        upgradePipAndSetuptools(project, envs, env)
                                    }
                                }

                                logger.quiet("Deleting $archiveName archive")
                                zipArchive.delete()

                                // Install packages specified for this env
                                pipInstall(project, envs, env, env.packages)

                            } catch (e: Exception) {
                                logger.error("Error bootstrapping ${env.name} from zip: ${e.message}", e)
                                throw GradleException("Failed to bootstrap ${env.name} from zip: ${e.message}", e)
                            }
                        }
                    })
                }
            }

            val virtualenvsTask = tasks.register("build_virtual_envs") {
                group = "Build Environment"
                description = "Creates virtual environments based on existing Python environments."
                mustRunAfter(pythonTask, pythonFromZipTask)
                onlyIf { envs.virtualEnvs.isNotEmpty() }

                envs.virtualEnvs.forEach { env ->
                    if (env.sourceEnv.type == EnvType.IRONPYTHON) {
                        logger.warn("IronPython does not support standard virtualenvs. Skipping ${env.name}.")
                        return@forEach // Continue to next env
                    }
                    if (env.sourceEnv.type == null) {
                         logger.warn("Source environment ${env.sourceEnv.name} for virtualenv ${env.name} has an unknown type. Skipping.")
                         return@forEach
                    }

                    dependsOn(tasks.register("Create_virtualenv_${env.name}") {
                        // Depend on the task that creates the source environment
                        val sourceTaskName = "Bootstrap_${env.sourceEnv.type ?: "Unknown"}_${env.sourceEnv.name}" + if(envs.pythonsFromZip.contains(env.sourceEnv)) "_from_archive" else ""
                        val sourceTask = tasks.findByName(sourceTaskName)
                        if(sourceTask != null) {
                           dependsOn(sourceTask)
                        } else {
                            logger.warn("Could not find source task '$sourceTaskName' for virtualenv '${env.name}' dependency.")
                        }

                        onlyIf { (!env.envDir.exists() || isPythonInvalid(project, env)) && env.sourceEnv.type != null }

                        doFirst {
                             if (env.envDir.exists()) env.envDir.deleteRecursively()
                             env.envDir.mkdirs()
                        }

                        doLast {
                            logger.quiet("Installing 'virtualenv' package into source environment ${env.sourceEnv.name}")
                            pipInstall(project, envs, env.sourceEnv, listOf("virtualenv"))

                            logger.quiet("Creating virtualenv ${env.name} from ${env.sourceEnv.name} at ${env.envDir}")
                            project.exec {
                                workingDir = env.sourceEnv.envDir
                                executable = getExecutable("virtualenv", env.sourceEnv).absolutePath
                                // Use --python for clarity if possible, or rely on executable path
                                args = listOf(env.envDir.absolutePath, "--always-copy")
                            }

                            pipInstall(project, envs, env, env.packages)
                        }
                    })
                }
            }

            val condaTask = tasks.register("build_condas") {
                group = "Build Environment"
                description = "Bootstraps base Conda (Miniconda/Anaconda) environments."
                onlyIf { envs.condas.isNotEmpty() }

                envs.condas.forEach { env ->
                    dependsOn(tasks.register("Bootstrap_${env.type}_${env.name}") {
                        onlyIf { !env.envDir.exists() || isPythonInvalid(project, env) } // Check python inside conda

                        doFirst {
                            project.buildDir.mkdirs()
                             if (env.envDir.exists()) env.envDir.deleteRecursively()
                             env.envDir.mkdirs()
                        }

                        doLast {
                            val urlToConda = getUrlToDownloadConda(env)
                            val installerName = urlToConda.path.substring(urlToConda.path.lastIndexOf('/') + 1)
                            val installer = project.buildDir.resolve(installerName)

                            if (!installer.exists()) {
                                logger.quiet("Downloading $installerName")
                                project.ant.invokeMethod("get", mapOf("dest" to installer, "src" to urlToConda))
                            }

                            logger.quiet("Bootstrapping Conda to ${env.envDir}")
                            project.exec {
                                if (isWindows) {
                                    commandLine(installer.absolutePath, "/InstallationType=JustMe", "/AddToPath=0", "/RegisterPython=0", "/S", "/D=${env.envDir.absolutePath}")
                                } else {
                                    commandLine("bash", installer.absolutePath, "-b", "-p", env.envDir.absolutePath)
                                }
                            }
                            // Make installer executable on Unix if needed (though bash execution might not require it)
                            if(isUnix) installer.setExecutable(true)

                            // Need to install Python explicitly potentially?
                            // Conda might come with a base python, check version?

                            pipInstall(project, envs, env, env.packages) // Install pip packages
                            condaInstall(project, envs, env, env.condaPackages) // Install conda packages
                        }
                    })
                }
            }

            val condaEnvsTask = tasks.register("build_conda_envs") {
                group = "Build Environment"
                description = "Creates Conda environments based on existing Conda installations."
                mustRunAfter(condaTask)
                onlyIf { envs.condaEnvs.isNotEmpty() }

                envs.condaEnvs.forEach { env ->
                    dependsOn(tasks.register("Create_conda_env_${env.name}") {
                         // Depend on the task that creates the source Conda environment
                        val sourceTaskName = "Bootstrap_${env.sourceEnv.type}_${env.sourceEnv.name}"
                        val sourceTask = tasks.findByName(sourceTaskName)
                         if(sourceTask != null) {
                           dependsOn(sourceTask)
                        } else {
                            logger.warn("Could not find source task '$sourceTaskName' for condaenv '${env.name}' dependency.")
                        }

                        onlyIf { !env.envDir.exists() || isPythonInvalid(project, env) }

                        doFirst {
                            if (env.envDir.exists()) env.envDir.deleteRecursively()
                            env.envDir.mkdirs()
                        }

                        doLast {
                            logger.quiet("Creating conda env '${env.name}' with Python ${env.version} at ${env.envDir}")
                            project.exec {
                                executable = getExecutable("conda", env.sourceEnv).absolutePath
                                // Base conda packages + python version + specified conda packages
                                args = listOf(
                                    "create", "-p", env.envDir.absolutePath, "-y", "python=${env.version}"
                                ) + (env.condaPackages ?: emptyList())
                            }

                            // Install pip packages into the created conda env
                            pipInstall(project, envs, env, env.packages)
                        }
                    })
                }
            }

            tasks.register("build_envs") {
                group = "Build Environment"
                description = "Builds all configured Python environments (Python, Conda, Virtualenv, etc.)."
                dependsOn(pythonTask, pythonFromZipTask, virtualenvsTask, condaTask, condaEnvsTask)
            }
        }
    }

    private fun createInstallPythonBuildTask(project: Project, installDir: File): Task {
        return project.tasks.register("install_python_build") {
            group = "Build Environment Setup"
            description = "Downloads and installs python-build (from pyenv) if needed on Unix systems."
            onlyIf { isUnix && !installDir.exists() }

            doFirst {
                project.buildDir.mkdirs()
                installDir.mkdirs() // Ensure install dir parent exists
            }

            doLast {
                val pyenvZip = project.buildDir.resolve("pyenv.zip")
                val unzipFolder = project.buildDir.resolve("python-build-tmp")
                try {
                    project.logger.quiet("Downloading latest pyenv from github")
                    project.ant.invokeMethod("get", mapOf(
                        "dest" to pyenvZip,
                        "src" to URI("https://github.com/pyenv/pyenv/archive/master.zip").toURL(),
                        "verbose" to true
                    ))

                    val pathToPythonBuildInPyenv = "pyenv-master/plugins/python-build/"
                    project.logger.quiet("Unzipping python-build to $unzipFolder")
                    project.copy {
                        from(project.zipTree(pyenvZip))
                        into(unzipFolder)
                        include("$pathToPythonBuildInPyenv**")
                        eachFile(object : Action<FileCopyDetails> {
                            override fun execute(details: FileCopyDetails) {
                                details.path = details.path.removePrefix(pathToPythonBuildInPyenv)
                            }
                        })
                        includeEmptyDirs = false // Avoid potential issues with empty dirs
                    }

                    val installScript = unzipFolder.resolve("install.sh")
                    if (installScript.exists()) {
                        installScript.setExecutable(true)
                        project.logger.quiet("Installing python-build via bash to $installDir")
                        project.exec {
                            // Use environment variable correctly
                            environment("PREFIX", installDir.absolutePath)
                            commandLine("bash", installScript.absolutePath)
                        }
                    } else {
                        throw GradleException("install.sh not found in extracted python-build")
                    }

                    project.logger.quiet("Successfully installed python-build to $installDir")
                } finally {
                    project.logger.quiet("Removing temporary files")
                    unzipFolder.deleteRecursively()
                    pyenvZip.delete()
                }
            }
        }.get() // Get the configured task
    }

    private fun createPythonUnixTask(project: Project, env: Python, installPythonBuildTask: Task): Task {
        return project.tasks.register("Bootstrap_${env.type}_${env.name}") {
            dependsOn(installPythonBuildTask)
            onlyIf { isUnix && (!env.envDir.exists() || isPythonInvalid(project, env)) }

            doFirst {
                if (env.envDir.exists()) env.envDir.deleteRecursively()
                env.envDir.mkdirs()
            }

            doLast {
                project.logger.quiet("Creating ${env.type} '${env.name}' at ${env.envDir} using python-build")
                val pythonBuildExecutable = project.layout.buildDirectory.file("python-build/bin/python-build").get().asFile
                if (!pythonBuildExecutable.exists()) {
                    throw GradleException("python-build executable not found at ${pythonBuildExecutable.path}. Ensure install_python_build task ran successfully.")
                }

                try {
                    project.exec {
                        executable(pythonBuildExecutable.absolutePath)
                        if (env.patchFileUri != null) {
                            project.logger.quiet("Applying patch from ${env.patchFileUri} to ${env.name}")
                            // Try to resolve URI/Path for patch file
                            val patchInput = try {
                                val path = Paths.get(env.patchFileUri)
                                if (Files.isRegularFile(path)) {
                                    path.toFile().inputStream()
                                } else {
                                    throw InvalidPathException(env.patchFileUri, "Path is not a regular file")
                                }
                            } catch (e: InvalidPathException) {
                                try {
                                    URI(env.patchFileUri).toURL().openStream()
                                } catch (urlE: Exception) {
                                    throw GradleException("Patch file URI '${env.patchFileUri}' is not a valid file path or URL", urlE)
                                }
                            }
                            standardInput = patchInput
                            args("-p", env.version ?: "", env.envDir.absolutePath)
                        } else {
                            args(env.version ?: "", env.envDir.absolutePath)
                        }
                    }
                    project.logger.quiet("Successfully created environment ${env.name}.")
                } catch (e: Exception) {
                    // Check if the environment is actually invalid *after* the attempt
                    if (isPythonInvalid(project, env)) {
                        project.logger.error("Failed to create Python environment ${env.name}: ${e.message}", e)
                        throw GradleException("Python environment creation failed for ${env.name}: ${e.message}", e)
                    } else {
                        project.logger.warn("python-build execution for ${env.name} finished with an error, but the resulting environment seems valid. Warning: ${e.message}")
                    }
                }

                // Upgrade pip/setuptools and install packages regardless of minor build errors if env looks valid
                upgradePipAndSetuptools(project, project.extensions.getByType(), env)
                pipInstall(project, project.extensions.getByType(), env, env.packages)
            }
        }.get()
    }

    private fun createPythonWindowsTask(project: Project, env: Python): Task {
        return project.tasks.register("Bootstrap_${env.type}_${env.name}") {
            onlyIf { isWindows && (!env.envDir.exists() || isPythonInvalid(project, env)) }

            doFirst {
                project.buildDir.mkdirs()
                if (env.envDir.exists()) env.envDir.deleteRecursively()
                env.envDir.mkdirs()
            }

            doLast {
                project.logger.quiet("Creating ${env.type} '${env.name}' at ${env.envDir} directory on Windows")
                val pythonVersion = env.version ?: throw GradleException("Python version must be specified for Windows installation")
                try {
                    val versionNumber = VersionNumber.parse(pythonVersion)
                    val isExe = versionNumber >= VersionNumber.parse("3.5.0")
                    val extension = if(isExe) "exe" else "msi"
                    val archSuffix = if (env.is64 != false) (if (extension == "msi") "." else "-") + "amd64" else ""
                    val filename = "python-$pythonVersion$archSuffix.$extension"
                    val installer = project.buildDir.resolve(filename)

                    project.logger.quiet("Downloading $filename")
                    project.ant.invokeMethod("get", mapOf(
                        "dest" to installer,
                        "src" to URI("https://www.python.org/ftp/python/$pythonVersion/$filename").toURL(),
                        "verbose" to true // Helps debug download issues
                    ))

                    project.logger.quiet("Installing ${env.name} using $filename")
                    if (extension == "msi") {
                        project.exec {
                            commandLine("msiexec", "/i", installer.absolutePath, "/quiet", "/qn", "TARGETDIR=${env.envDir.absolutePath}")
                            isIgnoreExitValue = true // MSI quiet installs might return non-zero on success sometimes
                        }
                    } else { // exe
                        project.exec {
                            // Args based on Python 3.5+ installer
                            // Ensure no user interaction prompts
                            commandLine(installer.absolutePath, "/quiet", "InstallAllUsers=0", "Include_launcher=0", "TargetDir=${env.envDir.absolutePath}", "PrependPath=0", "Shortcuts=0", "AssociateFiles=0", "Include_doc=0", "Include_pip=1", "Include_tcltk=0", "Include_test=0")
                            isIgnoreExitValue = true // Similar reason as MSI
                        }
                    }

                    // Check if pip was installed correctly by the installer
                    if (!getExecutable("pip", env).exists()) {
                        project.logger.quiet("Pip not found after installation, attempting manual install with get-pip.py")
                        val pythonExec = getExecutable("python", env)
                        if (pythonExec.exists()) {
                            project.exec {
                                executable(pythonExec.absolutePath)
                                args(getPipFile(project).absolutePath)
                            }
                        } else {
                             project.logger.warn("Could not find python executable at ${pythonExec.path} to install pip.")
                        }
                    }

                    // It's better to save installer for potential uninstall/repair, don't delete
                    // installer.delete()
                } catch (e: Exception) {
                    project.logger.error("Error installing Python ${env.name} on Windows: ${e.message}", e)
                    throw GradleException("Failed to install Python ${env.name} on Windows: ${e.message}", e)
                }

                // Upgrade pip/setuptools and install packages
                 if (getExecutable("pip", env).exists()) {
                    upgradePipAndSetuptools(project, project.extensions.getByType(), env)
                    pipInstall(project, project.extensions.getByType(), env, env.packages)
                 } else {
                     project.logger.warn("Skipping pip upgrade and package install for ${env.name} because pip executable was not found.")
                 }
            }
        }.get()
    }

    private fun createJythonTask(project: Project, env: Python): Task {
        return project.tasks.register("Bootstrap_${env.type}_${env.name}") {
            // Depend on the Jython configuration being resolved
            dependsOn(project.configurations.getByName("jython"))
            onlyIf { !env.envDir.exists() || isPythonInvalid(project, env) } // Jython might create a 'python' link

            doFirst {
                if (env.envDir.exists()) env.envDir.deleteRecursively()
                // Don't mkdir here, Jython installer does it
            }

            doLast {
                project.logger.quiet("Creating ${env.type} '${env.name}' at ${env.envDir} directory")
                val jythonInstallerJar = project.configurations.getByName("jython").singleFile

                project.javaexec {
                    mainClass.set("-jar")
                    args = listOf(jythonInstallerJar.absolutePath, "-s", "-d", env.envDir.absolutePath, "-t", "standard")
                    classpath = project.files(jythonInstallerJar) // Define classpath explicitly
                }

                // Assuming Jython install includes pip or similar mechanism
                pipInstall(project, project.extensions.getByType(), env, env.packages)
            }
        }.get()
    }

    // Helper for common pip upgrade logic
    private fun upgradePipAndSetuptools(project: Project, envs: PythonEnvsExtension, env: Python) {
        val pipExec = try { getExecutable("pip", env) } catch (e: Exception) { null }
        if (pipExec == null || !pipExec.exists()) {
            project.logger.warn("Cannot upgrade pip/setuptools for ${env.name}: pip executable not found.")
            return
        }
        project.logger.quiet("Force upgrading pip and setuptools for ${env.name}")
        val command = mutableListOf<String>(
            getExecutable("python", env).absolutePath, // Use python -m pip
             "-m",
            "pip",
            "install",
            "--upgrade",
            "--force-reinstall" // Use force-reinstall for robustness
        )
        command.addAll(envs.pipInstallOptions.split(" ").filter { it.isNotBlank() })
        command.addAll(listOf("pip", "setuptools"))

        project.logger.quiet("Executing: ${command.joinToString(" ")}")
        val result = project.exec {
            commandLine = command
            isIgnoreExitValue = true // Allow non-zero if already up-to-date or minor issues
        }
        if (result.exitValue != 0) {
            project.logger.warn("Pip/setuptools upgrade command for ${env.name} exited with code ${result.exitValue}. Check logs if packages fail to install.")
            // Don't throw GradleException here, allow proceeding if possible
        }
    }

    // Helper for common pip install logic
    private fun pipInstall(project: Project, envs: PythonEnvsExtension, env: Python, packages: List<String>?) {
        if (packages.isNullOrEmpty() || env.type == null) {
            return
        }
        val pipExec = try { getExecutable("pip", env) } catch (e: Exception) { null }
        if (pipExec == null || !pipExec.exists()) {
            project.logger.error("Cannot install packages for ${env.name}: pip executable not found at expected location.")
            throw GradleException("pip executable not found for ${env.name}")
        }

        project.logger.quiet("Installing packages via pip for ${env.name}: $packages")
        val command = mutableListOf<String>(
            pipExec.absolutePath,
            "install"
        )
        command.addAll(envs.pipInstallOptions.split(" ").filter { it.isNotBlank() })
        command.addAll(packages)

        project.logger.quiet("Executing: ${command.joinToString(" ")}")
        val result = project.exec {
            commandLine = command
        }
        if (result.exitValue != 0) {
            throw GradleException("pip install failed for ${env.name} with packages $packages. Exit code: ${result.exitValue}")
        }
    }

    // Helper for common conda install logic
    private fun condaInstall(project: Project, envs: PythonEnvsExtension, conda: Conda, packages: List<String>?) {
        if (packages.isNullOrEmpty()) {
            return
        }
        val condaExec = try { getExecutable("conda", conda) } catch (e: Exception) { null }
         if (condaExec == null || !condaExec.exists()) {
            project.logger.error("Cannot install conda packages for ${conda.name}: conda executable not found at expected location.")
            throw GradleException("conda executable not found for ${conda.name}")
        }

        project.logger.quiet("Installing packages via conda for ${conda.name}: $packages")
        val command = mutableListOf<String>(
            condaExec.absolutePath,
            "install", "-y",
            "-p", conda.envDir.absolutePath // Specify target environment explicitly
        )
        command.addAll(packages)

        project.logger.quiet("Executing: ${command.joinToString(" ")}")
        val result = project.exec {
            commandLine = command
        }
        if (result.exitValue != 0) {
             throw GradleException("conda install failed for ${conda.name} with packages $packages. Exit code: ${result.exitValue}")
        }
    }
} 
