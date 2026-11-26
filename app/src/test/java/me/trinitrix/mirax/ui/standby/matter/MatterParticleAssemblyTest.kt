package me.trinitrix.mirax.ui.standby.matter

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cross-checks [MatterParticleAssembly] against concrete vectors produced by
 * the approved JS prototype (`design/big-standby-c-particle-assembly.js`).
 *
 * Vector source: a throwaway `node` process instantiating
 * `MiraxCParticleAssembly(1800)` and calling `setBroadcast`/`progress`/`project`
 * with the same arguments below; outputs were pasted as literals, never
 * computed by this implementation.
 *
 * Tolerance: 1e-9 absolute. V8 and JVM libm (`sin`, `expm1`, `hypot`) may
 * differ by a few ulps (~1e-13 at these magnitudes), so exact bit equality is
 * not portable; 1e-9 keeps sub-pixel fidelity while absorbing libm drift.
 */
class MatterParticleAssemblyTest {

    private fun assemblyGathered(): MatterParticleAssembly {
        val assembly = MatterParticleAssembly(1800)
        assembly.setBroadcast(true, 0.0)
        return assembly
    }

    private fun assertPoint(actual: MatterParticleAssembly.Point, expectedX: Double, expectedY: Double) {
        assertThat(actual.x).isWithin(TOLERANCE).of(expectedX)
        assertThat(actual.y).isWithin(TOLERANCE).of(expectedY)
    }

    @Test
    fun gather_usesSixSecondExponentialOut() {
        val assembly = MatterParticleAssembly(1800)
        assembly.setBroadcast(true, 0.0)

        assertThat(assembly.progress(0.0)).isWithin(TOLERANCE).of(0.0)
        assertThat(assembly.progress(3.0)).isWithin(TOLERANCE).of(0.8807970779778824)
        assertThat(assembly.progress(6.0)).isWithin(TOLERANCE).of(1.0)
    }

    @Test
    fun release_usesEightSecondSmoother() {
        val assembly = assemblyGathered()
        assembly.setBroadcast(false, 6.0)

        assertThat(assembly.progress(10.0)).isWithin(TOLERANCE).of(0.5)
        assertThat(assembly.progress(14.0)).isWithin(TOLERANCE).of(0.0)
    }

    @Test
    fun reverseMidGather_resumesFromCurrentProgress() {
        val assembly = MatterParticleAssembly(1800)
        assembly.setBroadcast(true, 0.0)
        val mid = assembly.progress(2.0)
        assertThat(mid).isWithin(TOLERANCE).of(0.7501421954513627)

        assembly.setBroadcast(false, 2.0)
        assertThat(assembly.progress(6.0)).isWithin(TOLERANCE).of(0.15757771746563132)
        assertPoint(
            assembly.project(899, 100.0, 200.0, MatterParticleAssembly.Frame(800.0, 600.0, 6.0)),
            124.80648504270454,
            224.65523965877804,
        )
    }

    @Test
    fun project_midGather_matchesJsVectors() {
        val assembly = assemblyGathered()
        val frame = MatterParticleAssembly.Frame(800.0, 600.0, 3.0)

        assertPoint(assembly.project(1, 10.0, 20.0, frame), 441.49774242758616, 94.60535670465475)
        // Index 9 rides the hollow diamond (contour 4); non-free by design (9 % 5 != 0).
        assertPoint(assembly.project(9, 0.0, 0.0, frame), 274.314018942733, 267.8657011265929)
        assertPoint(assembly.project(899, 100.0, 200.0, frame), 316.6645735393045, 295.0030595833871)
        assertPoint(assembly.project(1799, 7.0, 8.0, frame), 345.5931726754212, 228.7324672413942)
    }

    @Test
    fun project_fullyGathered_matchesJsVectorsAcrossSizes() {
        val assembly = assemblyGathered()
        val small = MatterParticleAssembly.Frame(800.0, 600.0, 6.0)
        val tall = MatterParticleAssembly.Frame(1080.0, 1920.0, 6.0)

        assertPoint(assembly.project(1, 10.0, 20.0, small), 544.4259502922243, 116.10809548166701)
        assertPoint(assembly.project(9, 0.0, 0.0, small), 389.50858860014705, 263.50108138582453)
        assertPoint(assembly.project(899, 100.0, 200.0, tall), 522.1155322878975, 890.4867910302726)
    }

    @Test
    fun project_midRelease_matchesJsVector() {
        val assembly = assemblyGathered()
        assembly.setBroadcast(false, 6.0)

        assertPoint(
            assembly.project(1, 10.0, 20.0, MatterParticleAssembly.Frame(800.0, 600.0, 10.0)),
            264.7931301496089,
            -4.573429503405279,
        )
    }

    @Test
    fun project_unassembledOrFifthParticle_returnsFreeCloud() {
        val idle = MatterParticleAssembly(1800)
        assertPoint(
            idle.project(1, 10.0, 20.0, MatterParticleAssembly.Frame(800.0, 600.0, 0.0)),
            10.0,
            20.0,
        )

        val gathered = assemblyGathered()
        val frame = MatterParticleAssembly.Frame(800.0, 600.0, 6.0)
        // Index 0 and every fifth particle stay in the free-flowing cloud even after assembly.
        assertPoint(gathered.project(0, 10.0, 20.0, frame), 10.0, 20.0)
        assertPoint(gathered.project(5, 10.0, 20.0, frame), 10.0, 20.0)
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
