// Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.jetbrains.intellij.IntelliJPluginExtension

/* When we dynamically apply(plugin = "org.jetbrains.intellij"), we do not get the nice extension functions
 * pulled into scope. This function hides that fact, and gives a better error message when it fails.
 */
fun Project.intellij(block: IntelliJPluginExtension.() -> Unit) {
    val intellij = try {
        project.extensions.getByType(IntelliJPluginExtension::class.java)
    } catch (e: Exception) {
        throw GradleException("Unable to get extension intellij, did you apply(plugin = \"org.jetbrains.intellij\")?", e)
    }
    intellij.block()
}

/**
 * Only run the given block if this build is running within a CI system (e.g. GitHub actions, CodeBuild etc)
 */
fun Project.ciOnly(block: () -> Unit) {
    if (providers.environmentVariable("CI").forUseAtConfigurationTime().isPresent) {
        block()
    }
}
