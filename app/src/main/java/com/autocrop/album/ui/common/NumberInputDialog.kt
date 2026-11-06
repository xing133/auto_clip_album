package com.autocrop.album.ui.common

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import androidx.appcompat.app.AlertDialog
import com.autocrop.album.R

fun showNumberInputDialog(context: Context, title: String, initial: Int, min: Int, max: Int,
                          suffix: String? = null,
                          onConfirm: (Int) -> Unit): AlertDialog {
    val input = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        setText(initial.toString())
        selectAll()
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding / 2, padding, padding / 2)
        contentDescription = title
    }
    val body = if (suffix == null) input else LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(0, 0, padding, 0)
        addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply { text = suffix; textSize = 20f })
    }
    val dialog = AlertDialog.Builder(context).setTitle(title).setView(body)
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
    return dialog
}
