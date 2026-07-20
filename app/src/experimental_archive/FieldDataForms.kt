package com.viewshed.app.viewshed

data class FieldForm(val location: GeoPoint, val fields: Map<String, String>)

object FieldDataForms {
    private val forms = mutableListOf<FieldForm>()
    fun saveForm(form: FieldForm) { forms.add(form) }
    fun getFormsForLocation(loc: GeoPoint) = forms.filter { it.location == loc }
}
