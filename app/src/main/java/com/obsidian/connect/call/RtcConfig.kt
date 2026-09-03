package com.obsidian.connect.call

import org.webrtc.PeerConnection

/**
 * How the two phones find each other.
 *
 * STUN alone is enough when both sides have a reachable address - typically the
 * same wifi. It is not enough on Indian mobile networks, where carrier-grade
 * NAT means neither phone has an address the other can dial. In that case the
 * only way through is a relay: a machine with a public address that both can
 * reach, which forwards the stream.
 *
 * Metered's open relay is the free one. It is rate limited and its terms are
 * theirs to change, so a call can fail for reasons nothing here controls -
 * which is the honest cost of not paying for infrastructure.
 */
object RtcConfig {

    fun iceServers(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),

        // Relays, for when a direct path cannot be found. Several ports and
        // protocols because restrictive networks block some of them - 443 over
        // TCP is the one that survives almost anywhere, at the cost of latency.
        relay("turn:openrelay.metered.ca:80"),
        relay("turn:openrelay.metered.ca:443"),
        relay("turn:openrelay.metered.ca:443?transport=tcp"),
    )

    private fun relay(url: String): PeerConnection.IceServer =
        PeerConnection.IceServer.builder(url)
            .setUsername(RELAY_USER)
            .setPassword(RELAY_PASSWORD)
            .createIceServer()

    /** Public credentials, published by the relay for open use. */
    private const val RELAY_USER = "openrelayproject"
    private const val RELAY_PASSWORD = "openrelayproject"

    fun configuration(): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(iceServers()).apply {
            // Unified Plan is the current standard and the only one that
            // handles more than one video track cleanly - which this needs,
            // since a screen share rides alongside the camera.
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
}
