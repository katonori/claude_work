package com.katonori.gitmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.katonori.gitmobile.ui.navigation.GitMobileNavGraph
import com.katonori.gitmobile.ui.theme.GitMobileTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as GitMobileApp).container
        setContent {
            GitMobileTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GitMobileNavGraph(container = container)
                }
            }
        }
    }
}
