package com.scantrader.app.data.db

import androidx.room.TypeConverter
import com.scantrader.app.data.model.Game

class Converters {
    @TypeConverter
    fun toGame(value: String): Game = Game.valueOf(value)

    @TypeConverter
    fun fromGame(game: Game): String = game.name
}
