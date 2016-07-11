package org.jetbrains.kotlin.jupyter

import com.google.common.base.Throwables
import com.intellij.openapi.vfs.CharsetToolkit
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.PsiFileFactoryImpl
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.KotlinVersion
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.repl.CliReplAnalyzerEngine
import org.jetbrains.kotlin.cli.jvm.repl.EarlierLine
import org.jetbrains.kotlin.cli.jvm.repl.ReplClassLoader
import org.jetbrains.kotlin.cli.jvm.repl.messages.DiagnosticMessageHolder
import org.jetbrains.kotlin.cli.jvm.repl.messages.ReplTerminalDiagnosticMessageHolder
import org.jetbrains.kotlin.codegen.ClassBuilderFactories
import org.jetbrains.kotlin.codegen.CompilationErrorHandler
import org.jetbrains.kotlin.codegen.KotlinCodegenFacade
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.descriptors.ScriptDescriptor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.parsing.KotlinParserDefinition
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtScript
import org.jetbrains.kotlin.resolve.jvm.JvmClassName
import org.jetbrains.kotlin.script.KotlinScriptDefinition
import org.jetbrains.kotlin.script.ScriptParameter
import org.jetbrains.kotlin.script.StandardScriptDefinition
import org.jetbrains.kotlin.utils.PathUtil
import java.io.PrintWriter
import java.net.URLClassLoader


class ReplForJupyter(val conn: JupyterConnection) {

    private val compilerConfiguration = CompilerConfiguration().apply {
        addJvmClasspathRoots(PathUtil.getJdkClassesRoots())
        addJvmClasspathRoots(conn.config.classpath)
        put(CommonConfigurationKeys.MODULE_NAME, "jupyter")
        put<MessageCollector>(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, PrintingMessageCollector(conn.iopubErr, MessageRenderer.WITHOUT_PATHS, false))
        put(JVMConfigurationKeys.INCLUDE_RUNTIME, true)
    }

    private val environment = run {
        compilerConfiguration.add(JVMConfigurationKeys.SCRIPT_DEFINITIONS, REPL_LINE_AS_SCRIPT_DEFINITION)
        KotlinCoreEnvironment.createForProduction(conn.disposable, compilerConfiguration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
    }
    private val psiFileFactory: PsiFileFactoryImpl = PsiFileFactory.getInstance(environment.project) as PsiFileFactoryImpl
    private val analyzerEngine = CliReplAnalyzerEngine(environment)


    private class ChunkState(
            val code: String,
            val psiFile: KtFile,
            val errorHolder: DiagnosticMessageHolder)

    sealed class EvalResult {
        class ValueResult(val valueAsString: String): EvalResult()

        object UnitResult: EvalResult()
        object Ready: EvalResult()
        object Incomplete : EvalResult()

        sealed class Error(val errorText: String): EvalResult() {
            class Runtime(errorText: String): Error(errorText)
            class CompileTime(errorText: String): Error(errorText)
        }
    }

    private var chunkState: ChunkState? = null
    private var classLoader: ReplClassLoader = run {
        val classpath = compilerConfiguration.jvmClasspathRoots.map { it.toURI().toURL() }
        ReplClassLoader(URLClassLoader(classpath.toTypedArray(), null))
    }
    private val earlierLines = arrayListOf<EarlierLine>()

    fun createDiagnosticHolder() = ReplTerminalDiagnosticMessageHolder()

    fun checkComplete(executionNumber: Long, code: String): EvalResult {
        synchronized(this) {
            val virtualFile =
                    LightVirtualFile("line$executionNumber${KotlinParserDefinition.STD_SCRIPT_EXT}", KotlinLanguage.INSTANCE, code).apply {
                        charset = CharsetToolkit.UTF8_CHARSET
                    }
            val psiFile: KtFile = psiFileFactory.trySetupPsiForFile(virtualFile, KotlinLanguage.INSTANCE, true, false) as KtFile?
                    ?: error("Script file not analyzed at line $executionNumber: $code")

            val errorHolder = createDiagnosticHolder()

            val syntaxErrorReport = AnalyzerWithCompilerReport.Companion.reportSyntaxErrors(psiFile, errorHolder)

            if (!syntaxErrorReport.isHasErrors) {
                chunkState = ChunkState(code, psiFile, errorHolder)
            }

            return when {
                syntaxErrorReport.isHasErrors && syntaxErrorReport.isAllErrorsAtEof -> EvalResult.Incomplete
                syntaxErrorReport.isHasErrors -> EvalResult.Error.CompileTime(syntaxErrorReport.toString())
                else -> EvalResult.Ready
            }
        }
    }

    fun eval(executionNumber: Long, code: String): EvalResult {
        synchronized(this) {
            val (psiFile, errorHolder) = run {
                if (chunkState == null || chunkState!!.code != code) {
                    val res = checkComplete(executionNumber, code)
                    if (res != EvalResult.Ready) return@eval res
                }
                Pair(chunkState!!.psiFile, chunkState!!.errorHolder)
            }
            val analysisResult = analyzerEngine.analyzeReplLine(psiFile, executionNumber.toInt())
            AnalyzerWithCompilerReport.Companion.reportDiagnostics(analysisResult.diagnostics, errorHolder, false)
            val scriptDescriptor = when (analysisResult) {
                is CliReplAnalyzerEngine.ReplLineAnalysisResult.WithErrors -> return EvalResult.Error.CompileTime(errorHolder.renderedDiagnostics)
                is CliReplAnalyzerEngine.ReplLineAnalysisResult.Successful -> analysisResult.scriptDescriptor
                else -> error("Unexpected result ${analysisResult.javaClass}")
            }

            val state = GenerationState(
                    psiFile.project, ClassBuilderFactories.BINARIES, analyzerEngine.module,
                    analyzerEngine.trace.bindingContext, listOf(psiFile), compilerConfiguration
            )
            state.replSpecific.scriptResultFieldName = SCRIPT_RESULT_FIELD_NAME
            state.replSpecific.earlierScriptsForReplInterpreter = earlierLines.map(EarlierLine::getScriptDescriptor)
            state.beforeCompile()
            KotlinCodegenFacade.generatePackage(
                    state,
                    psiFile.script!!.getContainingKtFile().packageFqName,
                    setOf(psiFile.script!!.getContainingKtFile()),
                    CompilationErrorHandler.THROW_EXCEPTION)

            for (outputFile in state.factory.asList()) {
                if (outputFile.relativePath.endsWith(".class")) {
                    classLoader.addClass(JvmClassName.byInternalName(outputFile.relativePath.replaceFirst("\\.class$".toRegex(), "")),
                            outputFile.asByteArray())
                }
            }

            try {
                val scriptClass = classLoader.loadClass("Line$executionNumber")

                val constructorParams = earlierLines.map(EarlierLine::getScriptClass).toTypedArray()
                val constructorArgs = earlierLines.map(EarlierLine::getScriptInstance).toTypedArray()

                val scriptInstanceConstructor = scriptClass.getConstructor(*constructorParams)
                val scriptInstance = try {
                    conn.evalWithIO { scriptInstanceConstructor.newInstance(*constructorArgs) }
                }
                catch (e: Throwable) {
                    // ignore everything in the stack trace until this constructor call
                    return EvalResult.Error.Runtime(renderStackTrace(e.cause!!, startFromMethodName = "${scriptClass.name}.<init>"))
                }

                val rvField = scriptClass.getDeclaredField(SCRIPT_RESULT_FIELD_NAME).apply { isAccessible = true }
                val rv: Any? = rvField.get(scriptInstance)

                earlierLines.add(EarlierLine(code, scriptDescriptor, scriptClass, scriptInstance))

                if (!state.replSpecific.hasResult) {
                    return EvalResult.UnitResult
                }
                val valueAsString: String = try {
                    conn.evalWithIO  { rv.toString() }
                }
                catch (e: Throwable) {
                    return EvalResult.Error.Runtime(renderStackTrace(e, startFromMethodName = "java.lang.String.valueOf"))
                }
                return EvalResult.ValueResult(valueAsString)
            }
            catch (e: Throwable) {
                val writer = PrintWriter(System.err)
                writer.flush()
                throw e
            }
        }
    }

    init {
        log.info("Starting kotlin repl ${KotlinVersion.VERSION}")
        log.info("Using classpath:\n${compilerConfiguration.jvmClasspathRoots.joinToString("\n") { it.canonicalPath }}")
    }

    companion object {
        private val SCRIPT_RESULT_FIELD_NAME = "\$\$result"
        private val REPL_LINE_AS_SCRIPT_DEFINITION = object : KotlinScriptDefinition {
            override val name = "Kotlin REPL"

            override fun getScriptParameters(scriptDescriptor: ScriptDescriptor): List<ScriptParameter> = emptyList()

            override fun <TF> isScript(file: TF): Boolean = StandardScriptDefinition.isScript(file)

            override fun getScriptName(script: KtScript): Name = StandardScriptDefinition.getScriptName(script)
        }
        private fun renderStackTrace(cause: Throwable, startFromMethodName: String): String {
            val newTrace = arrayListOf<StackTraceElement>()
            var skip = true
            for ((i, element) in cause.stackTrace.withIndex().reversed()) {
                if ("${element.className}.${element.methodName}" == startFromMethodName) {
                    skip = false
                }
                if (!skip) {
                    newTrace.add(element)
                }
            }

            val resultingTrace = newTrace.reversed().dropLast(1)

            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "UsePropertyAccessSyntax")
            (cause as java.lang.Throwable).setStackTrace(resultingTrace.toTypedArray())

            return Throwables.getStackTraceAsString(cause)
        }
    }
}

fun<T> JupyterConnection.evalWithIO(body: () -> T): T {
    val out = System.out
    System.setOut(iopubOut)
    val err = System.err
    System.setErr(iopubErr)
    val `in` = System.`in`
    System.setIn(stdinIn)
    val res = body()
    System.setIn(`in`)
    System.setErr(err)
    System.setOut(out)
    return res
}
