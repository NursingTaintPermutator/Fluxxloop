package com.example.fluxxloop

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView

/** Farbauswahl (ersetzt die Einstellungs-Activity des Originals). */
class LoopsSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Farbe wählen"

        // Gleiche Preferences wie der Wallpaper-Dienst (geräteschützter Speicher).
        val prefs = createDeviceProtectedStorageContext()
            .getSharedPreferences(LoopsWallpaperService.PREFS, MODE_PRIVATE)

        val names = LoopPalettes.all.keys.toList()
        val list = ListView(this)
        list.fitsSystemWindows = true
        list.choiceMode = ListView.CHOICE_MODE_SINGLE
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, names)

        val current = names.indexOf(prefs.getString(LoopsWallpaperService.KEY_COLOR, LoopPalettes.DEFAULT))
        list.setItemChecked(current.coerceAtLeast(0), true)

        list.setOnItemClickListener { _, _, position, _ ->
            prefs.edit().putString(LoopsWallpaperService.KEY_COLOR, names[position]).apply()
        }
        setContentView(list)
    }
}