package com.katonori.gitmobile.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/** Small helper to construct a [ViewModelProvider.Factory] for manually-wired ViewModels. */
inline fun <reified T : ViewModel> vmFactory(crossinline create: () -> T): ViewModelProvider.Factory =
    viewModelFactory { initializer { create() } }
