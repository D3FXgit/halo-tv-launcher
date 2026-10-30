package app.halo.launcher

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Holds the one [HaloViewModel] of the process, so the home screen (MainActivity) and the
 * assistant overlay (AssistantOverlayActivity) work on the same live state: what the assistant
 * changes from the overlay shows up on the home screen at once.
 */
class HaloApp : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    val vm: HaloViewModel by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[HaloViewModel::class.java]
    }
}
