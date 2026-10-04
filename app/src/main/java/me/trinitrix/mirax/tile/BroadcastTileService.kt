package me.trinitrix.mirax.tile

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import me.trinitrix.mirax.MiraxApp
import me.trinitrix.mirax.R
import me.trinitrix.mirax.SessionHost
import me.trinitrix.mirax.session.TileState

/**
 * Quick Settings tile that renders [me.trinitrix.mirax.session.MiraxSession]
 * tile state and forwards clicks through [SessionHost].
 *
 * Gray (unable to broadcast) must not use [Tile.STATE_UNAVAILABLE]: that
 * platform state often drops clicks. Use inactive + muted label instead so
 * the probe tap still arrives.
 *
 * Long-press uses the default platform behaviour (opens Mirax) and does not
 * toggle broadcast.
 */
class BroadcastTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        render(MiraxApp.instance.session.snapshot().tileState)
    }

    override fun onClick() {
        super.onClick()
        val snapshot = SessionHost.onTileClick(this)
        render(snapshot.tileState)
    }

    private fun render(tileState: TileState) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        when (tileState) {
            TileState.GRAY -> {
                // Looks disabled but remains clickable for the Shizuku probe.
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.tile_label)
                tile.subtitle = getString(R.string.tile_subtitle_unavailable)
            }
            TileState.OFF -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.tile_label)
                tile.subtitle = getString(R.string.tile_subtitle_off)
            }
            TileState.ARMING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.tile_label)
                tile.subtitle = getString(R.string.tile_subtitle_arming)
            }
            TileState.ADVERTISING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.tile_label)
                tile.subtitle = getString(R.string.tile_subtitle_advertising)
            }
            TileState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.tile_label)
                tile.subtitle = getString(R.string.tile_subtitle_connected)
            }
        }
        tile.updateTile()
    }

    companion object {
        fun requestListening(context: Context) {
            val component = ComponentName(context, BroadcastTileService::class.java)
            try {
                requestListeningState(context, component)
            } catch (err: IllegalArgumentException) {
                // Tile may not be bound yet; next onStartListening will render.
            }
        }
    }
}
