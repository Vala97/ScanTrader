package com.scantrader.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
        Game.RIFTBOUND, Game.ONE_PIECE, Game.DRAGON_BALL, Game.YUGIOH, Game.UNION_ARENA, Game.OTHER -> null // nessun logo ufficiale nel progetto: solo il nome
    }
    if (res == null) {
        Box(modifier.semantics { contentDescription?.let { this.contentDescription = it } }, contentAlignment = Alignment.Center) {
            Text(game.shareLabel.uppercase(), color = Color(0xFFC89B3C), fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        return
    }
    Image(
        painter = painterResource(res),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
