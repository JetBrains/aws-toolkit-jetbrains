// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

object AwsToolkit {
    const val PLUGIN_ID = "aws.toolkit"

    const val PLUGIN_NAME = "AWS Toolkit For JetBrains"

    // PluginManagerCore.getPlugin Requires MIN 193.2252. However we cannot set our IDE min to that because not all JB IDEs use the same build numbers
    val plugin: IdeaPluginDescriptor? by lazy {
        PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
    }

    val PLUGIN_VERSION: String by lazy {
        plugin?.version ?: "Unknown"
    }
}
