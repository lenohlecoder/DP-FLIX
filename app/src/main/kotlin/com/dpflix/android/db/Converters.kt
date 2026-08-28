package com.dpflix.android.db

import androidx.room.TypeConverter
import com.dpflix.android.model.PlaylistType

/**
 * Room ne sait persister nativement que des types primitifs (+ String, ByteArray...).
 * `PlaylistType` (enum, modèle métier 3a) est donc stocké tel quel en base sous forme
 * de texte (son nom), plutôt que de dupliquer les valeurs M3U/XTREAM sous forme d'entiers
 * dont le sens ne serait pas lisible directement dans la base de données.
 */
class Converters {

    @TypeConverter
    fun fromPlaylistType(type: PlaylistType): String = type.name

    @TypeConverter
    fun toPlaylistType(value: String): PlaylistType = PlaylistType.valueOf(value)
}
