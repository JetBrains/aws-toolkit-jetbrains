// Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.datagrip.actions

import com.intellij.database.autoconfig.DataSourceRegistry
import com.intellij.testFramework.ProjectRule
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import software.aws.toolkits.core.utils.RuleUtils
import software.aws.toolkits.jetbrains.core.credentials.DUMMY_PROVIDER_IDENTIFIER
import software.aws.toolkits.jetbrains.core.region.getDefaultRegion
import software.aws.toolkits.jetbrains.datagrip.CREDENTIAL_ID_PROPERTY
import software.aws.toolkits.jetbrains.datagrip.REGION_ID_PROPERTY
import software.aws.toolkits.jetbrains.datagrip.auth.SECRET_ID_PROPERTY
import software.aws.toolkits.jetbrains.datagrip.auth.SecretsManagerAuth
import software.aws.toolkits.jetbrains.datagrip.auth.SecretsManagerDbSecret

class AddSecretsManagerConnectionTest {
    @Rule
    @JvmField
    val projectRule = ProjectRule()

    @Test
    fun `Add data source`() {
        val port = RuleUtils.randomNumber()
        val address = RuleUtils.randomName()
        val username = RuleUtils.randomName()
        val password = RuleUtils.randomName()
        val secretArn = RuleUtils.randomName()
        val engine = RuleUtils.randomName()
        val registry = DataSourceRegistry(projectRule.project)
        registry.createDatasource(
            projectRule.project,
            SecretsManagerDbSecret(username, password, engine, address, port.toString()),
            secretArn,
            "adapter"
        )
        assertThat(registry.newDataSources).singleElement().satisfies {
            assertThat(it.isTemporary).isFalse()
            assertThat(it.sslCfg?.myEnabled).isTrue()
            assertThat(it.url).isEqualTo("jdbc:adapter://$address:$port")
            assertThat(it.additionalJdbcProperties[CREDENTIAL_ID_PROPERTY]).isEqualTo(DUMMY_PROVIDER_IDENTIFIER.id)
            assertThat(it.additionalJdbcProperties[REGION_ID_PROPERTY]).isEqualTo(getDefaultRegion().id)
            assertThat(it.additionalJdbcProperties[SECRET_ID_PROPERTY]).isEqualTo(secretArn)
            assertThat(it.authProviderId).isEqualTo(SecretsManagerAuth.providerId)
        }
    }
}
