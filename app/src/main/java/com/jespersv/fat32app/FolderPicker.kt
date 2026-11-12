package com.jespersv.fat32app

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import com.jespersv.fat32app.core.Diag
import com.jespersv.fat32app.core.FatNames
import com.jespersv.fat32app.core.Volume

/** A small dialog for walking the folders of a directly opened drive and choosing one. */
class FolderPicker(
    private val activity: Activity,
    private val volume: Volume,
    private var path: List<String>,
    private val onPicked: (List<String>) -> Unit,
) {
    private val rows = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
    private var folders: List<String> = emptyList()
    private lateinit var dialog: AlertDialog

    fun show() {
        val list = ListView(activity)
        list.adapter = rows
        list.setOnItemClickListener { _, _, position, _ ->
            val hasUp = path.isNotEmpty()
            path = if (hasUp && position == 0) path.dropLast(1) else path + folders[position - if (hasUp) 1 else 0]
            load()
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle("/")
            .setView(list)
            .setPositiveButton("Use this folder") { _, _ -> onPicked(path) }
            .setNeutralButton("New folder", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            // Set here so that tapping it does not close the dialog.
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener { askNewFolder() }
        }
        dialog.show()
        load()
    }

    private fun load() {
        val showing = path
        dialog.setTitle("/" + showing.joinToString("/"))
        Session.io.execute {
            val result = runCatching {
                volume.list(showing).filter { it.isDir }.map { it.name }.sortedBy { it.lowercase() }
            }
            Session.main.post {
                if (!dialog.isShowing || showing != path) return@post
                rows.clear()
                folders = result.getOrDefault(emptyList())
                if (showing.isNotEmpty()) rows.add("↑  Up one level")
                rows.addAll(folders.map { "📁  $it" })
                result.exceptionOrNull()?.let {
                    Diag.e("Listing /${showing.joinToString("/")} failed", it)
                    toast("Cannot read this folder: ${it.message}")
                }
            }
        }
    }

    private fun askNewFolder() {
        val input = EditText(activity)
        input.hint = "Folder name"
        AlertDialog.Builder(activity)
            .setTitle("New folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = FatNames.sanitize(input.text.toString())
                val parent = path
                Session.io.execute {
                    val result = runCatching { volume.mkdir(parent, name) }
                    Session.main.post {
                        if (result.isSuccess) {
                            if (path == parent) path = parent + name
                        } else {
                            Diag.e("Creating folder '$name' failed", result.exceptionOrNull())
                            toast("Could not create the folder: ${result.exceptionOrNull()?.message}")
                        }
                        if (dialog.isShowing) load()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}
