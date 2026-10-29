package dev.angelminga.reactpropsdoc

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

@Service(Service.Level.APP)
@State(name = "ReactPropsDocSettings", storages = [Storage("reactPropsDoc.xml")])
class ReactPropsDocSettings : SimplePersistentStateComponent<ReactPropsDocSettings.Options>(Options()) {

    class Options : BaseState() {
        var hideSignature by property(true)
        var showDescriptions by property(true)
        var showInherited by property(true)
        var showWarnings by property(true)
        var showHooks by property(true)
        var showPropHover by property(true)
        var maxTypeLength by property(70)
        var showTranslations by property(true)
        var translationFunction by string("getLanguagePage")
        var baseLanguage by string("es")
        var markCopiedTranslations by property(true)
    }

    companion object {
        val options: Options get() = service<ReactPropsDocSettings>().state
    }
}

/** Settings → Tools → React Props Doc */
class ReactPropsDocConfigurable : BoundConfigurable("React Props Doc") {

    override fun createPanel(): DialogPanel {
        val options = ReactPropsDocSettings.options
        return panel {
            group("Componentes") {
                row { checkBox("Ocultar la firma del IDE cuando se muestran las props").bindSelected(options::hideSignature) }
                row { checkBox("Mostrar descripciones de librerías (MUI)").bindSelected(options::showDescriptions) }
                row { checkBox("Mostrar props heredadas de MUI").bindSelected(options::showInherited) }
                row { checkBox("Marcar inconsistencias con propTypes").bindSelected(options::showWarnings) }
                row("Largo máximo de los tipos:") {
                    intTextField(20..400).bindIntText(options::maxTypeLength)
                }
            }
            group("Otros") {
                row { checkBox("Ficha de hooks (parámetros y lo que devuelven)").bindSelected(options::showHooks) }
                row { checkBox("Detalle al pasar el mouse sobre una prop").bindSelected(options::showPropHover) }
            }
            group("Traducciones") {
                row { checkBox("Mostrar los textos por idioma al pasar el mouse").bindSelected(options::showTranslations) }
                row("Función que devuelve los textos de una página:") {
                    textField().bindText({ options.translationFunction ?: "" }, { options.translationFunction = it.trim() })
                }
                row {
                    checkBox("Marcar con [ES] los textos copiados del idioma base (para encontrar lo que falta traducir)")
                        .bindSelected(options::markCopiedTranslations)
                }
                row("Idioma base (archivo <idioma>.json):") {
                    textField().columns(6).bindText({ options.baseLanguage ?: "" }, { options.baseLanguage = it.trim().lowercase() })
                }
                row {
                    comment("Los avisos de claves y páginas se activan en Settings → Editor → Inspections → React Props Doc.")
                }
            }
        }
    }
}
