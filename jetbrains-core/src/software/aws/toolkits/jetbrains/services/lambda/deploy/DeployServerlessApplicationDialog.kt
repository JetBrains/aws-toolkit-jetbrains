// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.lambda.deploy

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.module.ModuleUtilCore.findModuleForFile
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.text.nullize
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.ecr.EcrClient
import software.amazon.awssdk.services.lambda.model.PackageType
import software.amazon.awssdk.services.s3.S3Client
import software.aws.toolkits.jetbrains.core.Resource
import software.aws.toolkits.jetbrains.core.awsClient
import software.aws.toolkits.jetbrains.core.help.HelpIds
import software.aws.toolkits.jetbrains.core.map
import software.aws.toolkits.jetbrains.services.cloudformation.CloudFormationTemplate
import software.aws.toolkits.jetbrains.services.cloudformation.Parameter
import software.aws.toolkits.jetbrains.services.cloudformation.SamFunction
import software.aws.toolkits.jetbrains.services.cloudformation.describeStack
import software.aws.toolkits.jetbrains.services.cloudformation.mergeRemoteParameters
import software.aws.toolkits.jetbrains.services.cloudformation.resources.CloudFormationResources
import software.aws.toolkits.jetbrains.services.ecr.CreateEcrRepoDialog
import software.aws.toolkits.jetbrains.services.lambda.sam.SamTemplateUtils
import software.aws.toolkits.jetbrains.services.s3.CreateS3BucketDialog
import software.aws.toolkits.jetbrains.settings.DeploySettings
import software.aws.toolkits.jetbrains.settings.relativeSamPath
import software.aws.toolkits.jetbrains.utils.ui.find
import software.aws.toolkits.jetbrains.utils.ui.validationInfo
import software.aws.toolkits.resources.message
import java.util.regex.PatternSyntaxException
import javax.swing.JComponent

class DeployServerlessApplicationDialog(
    private val project: Project,
    private val templateFile: VirtualFile
) : DialogWrapper(project) {

    private val module = findModuleForFile(templateFile, project)
    private val settings: DeploySettings? = module?.let { DeploySettings.getInstance(it) }
    private val samPath: String = module?.let { relativeSamPath(it, templateFile) } ?: templateFile.name

    /*
     * We save before opening this dialog so this should be the most updated view
     */
    private val templateFunctions = SamTemplateUtils.findFunctionsFromTemplate(project, templateFile)
    private val hasImageFunctions: Boolean = templateFunctions.any { (it as? SamFunction)?.packageType() == PackageType.IMAGE }

    private val view = DeployServerlessApplicationPanel(project)
    private val templateParameters = CloudFormationTemplate.parse(project, templateFile).parameters().toList()
    private val validator = DeploySamApplicationValidator(view, hasImageFunctions, templateParameters)
    private val s3Client: S3Client = project.awsClient()
    private val ecrClient: EcrClient = project.awsClient()
    private val cloudFormationClient: CloudFormationClient = project.awsClient()

    init {
        super.init()

        title = message("serverless.application.deploy.title")
        setOKButtonText(message("serverless.application.deploy.action.name"))
        setOKButtonTooltip(message("serverless.application.deploy.action.description"))

        view.createStack.addChangeListener {
            updateStackEnabledStates()
            updateTemplateParameters()
        }

        view.updateStack.addChangeListener {
            updateStackEnabledStates()
            updateTemplateParameters()
        }

        // If the module has been deployed once, select the updateStack radio instead
        if (settings?.samStackName(samPath) != null) {
            view.updateStack.isSelected = true
            updateStackEnabledStates()
        }

        view.stacks.addActionListener {
            updateTemplateParameters()
        }

        settings?.samStackName(samPath)?.let {
            view.stacks.selectedItem { s: Stack -> it == s.name }
        }

        updateTemplateParameters()

        view.s3Bucket.selectedItem = settings?.samBucketName(samPath)
        settings?.samEcrRepoUri(samPath)?.let { repoPath ->
            view.ecrRepo.selectedItem { it.repositoryUri == repoPath }
        }

        view.createS3BucketButton.addActionListener {
            val bucketDialog = CreateS3BucketDialog(
                project = project,
                s3Client = s3Client,
                parent = view.content
            )

            if (bucketDialog.showAndGet()) {
                bucketDialog.bucketName().let {
                    view.s3Bucket.reload(forceFetch = true)
                    view.s3Bucket.selectedItem = it
                }
            }
        }

        view.createEcrRepoButton.addActionListener {
            val ecrDialog = CreateEcrRepoDialog(
                project = project,
                ecrClient = ecrClient,
                parent = view.content
            )

            if (ecrDialog.showAndGet()) {
                view.ecrRepo.reload(forceFetch = true)
                view.ecrRepo.selectedItem { it.repositoryName == ecrDialog.repoName }
            }
        }

        view.requireReview.isSelected = !(settings?.samAutoExecute(samPath) ?: true)

        view.useContainer.isSelected = (settings?.samUseContainer(samPath) ?: false)
        view.capabilities.selected = settings?.enabledCapabilities(samPath) ?: CreateCapabilities.values().filter { it.defaultEnabled }

        view.showImageOptions(hasImageFunctions)
    }

    override fun createCenterPanel(): JComponent = view.content

    override fun getPreferredFocusedComponent(): JComponent =
        if (settings?.samStackName(samPath) == null) view.newStackName else view.updateStack

    override fun doValidate(): ValidationInfo? = validator.validateSettings()

    override fun getHelpId(): String = HelpIds.DEPLOY_SERVERLESS_APPLICATION_DIALOG.id

    val stackName: String
        get() = if (view.createStack.isSelected) {
            view.newStackName.text.nullize()
        } else {
            view.stacks.selected()?.name
        } ?: throw RuntimeException(message("serverless.application.deploy.validation.stack.missing"))

    val bucket: String
        get() = view.s3Bucket.selected()
            ?: throw RuntimeException(message("serverless.application.deploy.validation.s3.bucket.empty"))

    val ecrRepo: String?
        get() = if (hasImageFunctions) {
            view.ecrRepo.selected()?.repositoryUri ?: throw RuntimeException("serverless.application.deploy.validation.ecr.repo.empty")
        } else {
            null
        }

    val autoExecute: Boolean
        get() = !view.requireReview.isSelected

    val parameters: Map<String, String>
        get() = view.templateParameters

    val useContainer: Boolean
        get() = view.useContainer.isSelected

    val capabilities: List<CreateCapabilities>
        get() = view.capabilities.selected

    private fun updateStackEnabledStates() {
        view.newStackName.isEnabled = view.createStack.isSelected
        view.stacks.isEnabled = view.updateStack.isSelected
    }

    private fun updateTemplateParameters() {
        if (view.createStack.isSelected) {
            view.withTemplateParameters(templateParameters)
        } else if (view.updateStack.isSelected) {
            val stackName = view.stacks.selected()?.name
            if (stackName == null) {
                view.withTemplateParameters(emptyList())
            } else {
                cloudFormationClient.describeStack(stackName) {
                    it?.let {
                        runInEdt(ModalityState.any()) {
                            // This check is here in-case createStack was selected before we got this update back
                            // TODO: should really create a queuing pattern here so we can cancel on user-action
                            if (view.updateStack.isSelected) {
                                view.withTemplateParameters(templateParameters.mergeRemoteParameters(it.parameters()))
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        @JvmField
        internal val ACTIVE_STACKS: Resource<List<Stack>> = CloudFormationResources.ACTIVE_STACKS.map { Stack(it.stackName(), it.stackId()) }
    }
}

class DeploySamApplicationValidator(
    private val view: DeployServerlessApplicationPanel,
    private val hasImageFunctions: Boolean,
    templateParameters: Collection<Parameter>
) {
    private val parameterDeclarations: Map<String, Parameter> by lazy {
        templateParameters.associateBy { it.logicalName }
    }

    fun validateSettings(): ValidationInfo? {
        if (view.createStack.isSelected) {
            validateStackName(view.newStackName.text)?.let {
                return view.newStackName.validationInfo(it)
            }
        } else if (view.updateStack.isSelected && view.stacks.selected() == null) {
            return view.stacks.validationInfo(message("serverless.application.deploy.validation.stack.missing"))
        }

        // Are any Template Parameters missing
        validateParameters(view)?.let {
            return it
        }

        // Is the bucket loading
        if (view.s3Bucket.isLoading) {
            return view.s3Bucket.validationInfo(message("serverless.application.deploy.validation.s3.bucket.loading"))
        }
        // Has the user selected a bucket
        view.s3Bucket.selected() ?: return view.s3Bucket.validationInfo(message("serverless.application.deploy.validation.s3.bucket.empty"))

        if (hasImageFunctions) {
            if (view.ecrRepo.isLoading) {
                return view.ecrRepo.validationInfo(message("serverless.application.deploy.validation.ecr.repo.loading"))
            }

            view.ecrRepo.selected() ?: return view.ecrRepo.validationInfo(message("serverless.application.deploy.validation.ecr.repo.empty"))
        }

        return null
    }

    private fun validateParameters(view: DeployServerlessApplicationPanel): ValidationInfo? {
        val parameters = view.templateParameters

        val invalidParameters = parameters.entries.mapNotNull { (name, value) ->
            val cfnParameterDeclaration = parameterDeclarations[name] ?: return ValidationInfo("parameter declared but not in template")
            when (cfnParameterDeclaration.getOptionalScalarProperty("Type")) {
                "String" -> validateStringParameter(name, value, cfnParameterDeclaration)
                "Number" -> validateNumberParameter(name, value, cfnParameterDeclaration)
                // not implemented: List<Number>, CommaDelimitedList, AWS-specific parameters, SSM parameters
                else -> null
            }
        }

        return invalidParameters.firstOrNull()
    }

    private fun validateStringParameter(name: String, value: String, parameterDeclaration: Parameter): ValidationInfo? {
        val minValue = parameterDeclaration.getOptionalScalarProperty("MinLength")
        val maxValue = parameterDeclaration.getOptionalScalarProperty("MaxLength")
        val allowedPattern = parameterDeclaration.getOptionalScalarProperty("AllowedPattern")

        minValue?.toIntOrNull()?.let {
            if (value.length < it) {
                return ValidationInfo(message("serverless.application.deploy.validation.template.values.tooShort", name, minValue))
            }
        }

        maxValue?.toIntOrNull()?.let {
            if (value.length > it) {
                return ValidationInfo(message("serverless.application.deploy.validation.template.values.tooLong", name, maxValue))
            }
        }

        allowedPattern?.let {
            try {
                val regex = it.toRegex()
                if (!regex.matches(value)) {
                    return ValidationInfo(message("serverless.application.deploy.validation.template.values.failsRegex", name, regex))
                }
            } catch (e: PatternSyntaxException) {
                return ValidationInfo(message("serverless.application.deploy.validation.template.values.badRegex", name, e.message ?: it))
            }
        }

        return null
    }

    private fun validateNumberParameter(name: String, value: String, parameterDeclaration: Parameter): ValidationInfo? {
        // cfn numbers can be integer or float. assume real implementation refers to java floats
        val number = value.toFloatOrNull() ?: return ValidationInfo(message("serverless.application.deploy.validation.template.values.notANumber", name, value))
        val minValue = parameterDeclaration.getOptionalScalarProperty("MinValue")
        val maxValue = parameterDeclaration.getOptionalScalarProperty("MaxValue")

        minValue?.toFloatOrNull()?.let {
            if (number < it) {
                return ValidationInfo(message("serverless.application.deploy.validation.template.values.tooSmall", name, minValue))
            }
        }

        maxValue?.toFloatOrNull()?.let {
            if (number > it) {
                return ValidationInfo(message("serverless.application.deploy.validation.template.values.tooBig", name, maxValue))
            }
        }

        return null
    }

    private fun validateStackName(name: String?): String? {
        if (name == null || name.isEmpty()) {
            return message("serverless.application.deploy.validation.new.stack.name.missing")
        }
        if (!STACK_NAME_PATTERN.matches(name)) {
            return message("serverless.application.deploy.validation.new.stack.name.invalid")
        }
        if (name.length > MAX_STACK_NAME_LENGTH) {
            return message("serverless.application.deploy.validation.new.stack.name.too.long", MAX_STACK_NAME_LENGTH)
        }
        // Check if the new stack name is same as an existing stack name
        view.stacks.model.find { it.name == name }?.let {
            return message("serverless.application.deploy.validation.new.stack.name.duplicate")
        }
        return null
    }

    companion object {
        // https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/cfn-using-console-create-stack-parameters.html
        //  A stack name can contain only alphanumeric characters (case-sensitive) and hyphens. It must start with an alphabetic character and can't be longer than 128 characters.
        private val STACK_NAME_PATTERN = "[a-zA-Z][a-zA-Z0-9-]*".toRegex()
        const val MAX_STACK_NAME_LENGTH = 128
    }
}

data class Stack(val name: String, val id: String? = null) {
    override fun toString() = name
}
