/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package parser

import org.gradle.internal.jvm.Jvm

import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoots
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.utils.PathUtil

import java.io.File


class KotlinSourceParser {

    data class ParsedKotlinFiles(

        val ktFiles: List<KtFile>,

        private
        val disposable: Disposable

    ) : AutoCloseable {

        override fun close() {
            Disposer.dispose(disposable)
        }
    }

    private
    val messageCollector: MessageCollector
        get() = PrintingMessageCollector(System.out, MessageRenderer.PLAIN_RELATIVE_PATHS, false)

    fun <T : Any> mapParsedKotlinFiles(vararg sourceRoots: File, block: (KtFile) -> T): List<T> =
        withParsedKotlinSource(sourceRoots.toList()) { ktFiles ->
            ktFiles.map(block)
        }

    fun parseSourceRoots(sourceRoots: List<File>, compilationClasspath: List<File>): ParsedKotlinFiles =
        Disposer.newDisposable().let { disposable ->
            ParsedKotlinFiles(disposable.parseKotlinFiles(sourceRoots, compilationClasspath), disposable)
        }

    private
    fun <T : Any?> withParsedKotlinSource(sourceRoots: List<File>, block: (List<KtFile>) -> T) =
        Disposer.newDisposable().use {
            parseKotlinFiles(sourceRoots, emptyList()).let(block)
        }

    private
    fun Disposable.parseKotlinFiles(sourceRoots: List<File>, compilationClasspath: List<File>): List<KtFile> {
        val configuration = CompilerConfiguration().apply {

            put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messageCollector)
            put(JVMConfigurationKeys.RETAIN_OUTPUT_IN_MEMORY, false)
            put(JVMConfigurationKeys.DISABLE_OPTIMIZATION, true)
            put(CommonConfigurationKeys.MODULE_NAME, "parser")

            // TODO:kotlin-dsl dedupe
            put(JVMConfigurationKeys.JVM_TARGET, JvmTarget.JVM_1_8)
            put(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS, LanguageVersionSettingsImpl(
                languageVersion = LanguageVersion.KOTLIN_1_3,
                apiVersion = ApiVersion.KOTLIN_1_3,
                specificFeatures = mapOf(
                    LanguageFeature.NewInference to LanguageFeature.State.ENABLED,
                    LanguageFeature.SamConversionForKotlinFunctions to LanguageFeature.State.ENABLED
                ),
                analysisFlags = mapOf(
                    AnalysisFlags.skipMetadataVersionCheck to true
                )
            ))

            addJvmClasspathRoots(PathUtil.getJdkClassesRoots(Jvm.current().javaHome))
            addJvmClasspathRoots(compilationClasspath)
            addKotlinSourceRoots(sourceRoots.map { it.canonicalPath })
        }
        val environment = KotlinCoreEnvironment.createForProduction(this, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
        return environment.getSourceFiles()
    }
}


private
inline fun <T : Any?> Disposable.use(action: Disposable.() -> T) =
    try {
        action(this)
    } finally {
        Disposer.dispose(this)
    }
