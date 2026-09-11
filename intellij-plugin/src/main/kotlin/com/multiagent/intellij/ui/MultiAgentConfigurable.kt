package com.multiagent.intellij.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.multiagent.intellij.core.model.ProviderType
import com.multiagent.intellij.service.MultiAgentService
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings -> Tools -> MultiAgent. Phase 1: the single active-connection fields on
 * `AppSettings` directly (provider / base URL / API key / model) - no multi-server profile
 * list yet (that's `ServerProfile`, already in the forked core, just not wired to UI here).
 */
class MultiAgentConfigurable : Configurable {

    private val service = ApplicationManager.getApplication().getService(MultiAgentService::class.java)

    private val providerCombo = JComboBox(ProviderType.entries.toTypedArray())
    private val baseUrlField = JBTextField()
    private val apiKeyField = JBPasswordField()
    private val modelField = JBTextField()
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "MultiAgent"

    override fun createComponent(): JComponent {
        val settings = service.settings()
        providerCombo.selectedItem = settings.providerType
        baseUrlField.text = settings.baseUrl
        apiKeyField.text = settings.apiKey
        modelField.text = settings.model

        val built = FormBuilder.createFormBuilder()
            .addLabeledComponent("Provider:", providerCombo)
            .addLabeledComponent("Base URL:", baseUrlField)
            .addLabeledComponent("API key:", apiKeyField)
            .addLabeledComponent("Model:", modelField)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        panel = built
        return built
    }

    override fun isModified(): Boolean {
        val s = service.settings()
        return providerCombo.selectedItem != s.providerType ||
            baseUrlField.text != s.baseUrl ||
            String(apiKeyField.password) != s.apiKey ||
            modelField.text != s.model
    }

    override fun apply() {
        service.configService.updateSettings { s ->
            s.providerType = providerCombo.selectedItem as ProviderType
            s.baseUrl = baseUrlField.text.trim()
            s.apiKey = String(apiKeyField.password)
            s.model = modelField.text.trim()
        }
        service.invalidateClient()
    }

    override fun reset() {
        val s = service.settings()
        providerCombo.selectedItem = s.providerType
        baseUrlField.text = s.baseUrl
        apiKeyField.text = s.apiKey
        modelField.text = s.model
    }

    override fun disposeUIResources() {
        panel = null
    }
}
