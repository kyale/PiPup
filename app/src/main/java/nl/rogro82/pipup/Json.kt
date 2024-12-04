package nl.rogro82.pipup

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule

object Json : ObjectMapper(JsonFactory()) {
    private fun readResolve(): Any = Json

    init {
        registerModule(KotlinModule())
    }
}