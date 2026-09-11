package com.multiagent.intellij.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.multiagent.intellij.core.model.Persona
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings for an orchestrator conversation: which model each specialist persona runs on.
 * Every non-"orchestrator" persona is always a *candidate* specialist - the coordinator's
 * plan step picks which ones actually run per request (see `OrchestratorService`) - this
 * dialog only overrides the model, never which personas participate.
 */
class SpecialistModelsDialog(
    project: Project,
    private val specialists: List<Persona>,
    current: Map<String, String>
) : DialogWrapper(project, false) {

    private val fields: Map<String, JBTextField> =
        specialists.associate { it.id to JBTextField(current[it.id].orEmpty()) }

    init {
        title = "Specialist Models"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val builder = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Optional per-specialist model override (blank = persona default, then the conversation's model):"))
        for (persona in specialists) {
            builder.addLabeledComponent("${persona.name}:", fields.getValue(persona.id))
        }
        return builder.addComponentFillVertically(JPanel(), 0).panel
    }

    /** Non-blank overrides only, keyed by persona id - ready for `SpecialistModels.write(...)`. */
    fun result(): Map<String, String> =
        fields.mapNotNull { (id, field) -> field.text.trim().takeIf { it.isNotEmpty() }?.let { id to it } }.toMap()
}
