package com.autocrop.album.ui.common

import android.content.Context
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.autocrop.album.R

fun showNumberInputDialog(context: Context, title: String, initial: Int, min: Int, max: Int,
                          onConfirm: (Int) -> Unit) {
    val input = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        setText(initial.toString())
        selectAll()
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding / 2, padding, padding / 2)
        contentDescription = title
    }
    val dialog = AlertDialog.Builder(context).setTitle(title).setView(input)
        .setPositiveButton(R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().toIntOrNull()
            if (value == null || value !in min..max) {
                input.error = context.getString(R.string.number_out_of_range, min, max)
            } else {
                onConfirm(value)
                dialog.dismiss()
            }
        }
    }
    dialog.show()
}
