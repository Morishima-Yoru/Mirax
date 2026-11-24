package me.trinitrix.mirax.host

/** Selects the production platform without accessing hardware during construction. */
internal object SessionPlatformBinding {
    val platform: SessionPlatform = AndroidSessionPlatform()
}
