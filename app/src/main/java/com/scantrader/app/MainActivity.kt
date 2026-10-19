package com.scantrader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.scantrader.app.ui.navigation.AppNav
import com.scantrader.app.ui.theme.ScanTraderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScanTraderTheme {
                AppNav()
            }
        }
    }
}
