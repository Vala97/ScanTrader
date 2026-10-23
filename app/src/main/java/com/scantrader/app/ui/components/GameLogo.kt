package com.scantrader.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.scantrader.app.R
import com.scantrader.app.data.model.Game

@Composable
fun GameLogo(
    game: Game,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val res = when (game) {
        Game.MTG -> R.drawable.logo_magic
        Game.POKEMON -> R.drawable.logo_pokemon
    }
    Image(
        painter = painterResource(res),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
