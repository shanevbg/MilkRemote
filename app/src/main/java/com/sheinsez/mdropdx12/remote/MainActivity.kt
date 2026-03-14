package com.sheinsez.mdropdx12.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sheinsez.mdropdx12.remote.ui.navigation.AppNavigation
import com.sheinsez.mdropdx12.remote.ui.theme.MdrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MdrTheme {
                AppNavigation()
            }
        }
    }
}
