package me.trinitrix.mirax.ui.standby.matter

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cross-checks [MatterParticleField] against independent numeric references
 * derived from the approved v41 prototype, not from this implementation.
 *
 * Vector provenance: throwaway `node` processes evaluating raw prototype logic
 * (`design/big-standby-prototype.html` lines 2436-2438 seeds, 2742-2767 free
 * cloud, 2506-2518 frozen lattice) plus `MiraxCParticleAssembly` from
 * `design/big-standby-c-particle-assembly.js` for assembled projections.
 * Pasted below as literals. Cases use `new MiraxCParticleAssembly(1800)` with
 * `setBroadcast(true, 0)` unless noted, particle time in ms converted with
 * `* 0.001`, and sizes 800x600 / 1080x1920.
 *
 * Tolerance: 1e-9 absolute. V8 and JVM libm (`sin`, `cos`) may differ by a few
 * ulps (~1e-13 at these magnitudes), so exact bit equality is not portable;
 * 1e-9 keeps sub-pixel fidelity while absorbing cross-engine drift.
 */
class MatterParticleFieldTest {

    private fun gathered(): MatterParticleField {
        val field = MatterParticleField()
        field.setBroadcast(true, false, 0.0)
        return field
    }

    private fun assertSample(actual: MatterParticleField.Sample, expected: DoubleArray) {
        assertThat(actual.x).isWithin(TOLERANCE).of(expected[0])
        assertThat(actual.y).isWithin(TOLERANCE).of(expected[1])
        assertThat(actual.radiusPx).isWithin(TOLERANCE).of(expected[2])
        assertThat(actual.alpha).isWithin(TOLERANCE).of(expected[3])
        assertThat(actual.depth).isWithin(TOLERANCE).of(expected[4])
    }

    @Test
    fun seeds_matchLcg74321() {
        val field = MatterParticleField()
        assertThat(field.count).isEqualTo(1800)

        val first = field.seedAt(0)
        assertThat(first.angle).isWithin(TOLERANCE).of(3.654700119487447)
        assertThat(first.tube).isWithin(TOLERANCE).of(0.1253658065172277)
        assertThat(first.radius).isWithin(TOLERANCE).of(0.2683356456489634)
        assertThat(first.size).isWithin(TOLERANCE).of(0.7864546823654833)
        assertThat(first.alpha).isWithin(TOLERANCE).of(0.7660036113262211)

        val second = field.seedAt(1)
        assertThat(second.angle).isWithin(TOLERANCE).of(1.6693612180643258)
        assertThat(second.tube).isWithin(TOLERANCE).of(2.5316315580137894)
        assertThat(second.radius).isWithin(TOLERANCE).of(0.3637858029490204)
        assertThat(second.size).isWithin(TOLERANCE).of(1.4381781828479638)
        assertThat(second.alpha).isWithin(TOLERANCE).of(0.4519674916537176)

        val sixth = field.seedAt(5)
        assertThat(sixth.angle).isWithin(TOLERANCE).of(5.489486940849068)
        assertThat(sixth.radius).isWithin(TOLERANCE).of(0.36490349223362606)
        assertThat(sixth.alpha).isWithin(TOLERANCE).of(0.3710103161595858)

        val late = field.seedAt(899)
        assertThat(late.angle).isWithin(TOLERANCE).of(0.6591648647448832)
        assertThat(late.alpha).isWithin(TOLERANCE).of(0.8653744539389149)

        val last = field.seedAt(1799)
        assertThat(last.angle).isWithin(TOLERANCE).of(3.5867248818569353)
        assertThat(last.radius).isWithin(TOLERANCE).of(0.21109496697885446)
        assertThat(last.alpha).isWithin(TOLERANCE).of(0.3731791609648421)
    }

    @Test
    fun freeCloud_withoutBroadcast_matchesJsVectors() {
        val field = MatterParticleField()

        assertSample(
            field.sampleAt(0, 800.0, 600.0, 0.0, false),
            doubleArrayOf(-13.177139620053424, 352.95691146918585, 0.8880006624585428, 0.63269642847628, 0.027138889313758907),
        )
        assertSample(
            field.sampleAt(1, 800.0, 600.0, 0.0, false),
            doubleArrayOf(422.7876512867451, 477.432994732565, 1.7281347973839967, 0.3947654138847901, 0.2428983095098574),
        )
        assertSample(
            field.sampleAt(37, 800.0, 600.0, 0.0, false),
            doubleArrayOf(-85.77491514594027, 488.49820088767564, 1.269161418029537, 0.7802049963454101, 0.31874605056912275),
        )
        assertSample(
            field.sampleAt(899, 1080.0, 1920.0, 2500.0, false),
            doubleArrayOf(817.2201020533996, 1221.0651161875614, 1.1724727972612885, 0.7799356851770153, 0.3694072581527678),
        )
        assertSample(
            field.sampleAt(5, 800.0, 600.0, 12345.0, false),
            doubleArrayOf(362.4437346250993, 561.3433056264224, 1.1710058301451616, 0.33535672631285124, 0.38136978096368745),
        )
    }

    @Test
    fun assembled_matchesJsAssemblyProjection() {
        val field = gathered()

        assertSample(
            field.sampleAt(1, 800.0, 600.0, 3000.0, false),
            doubleArrayOf(458.6765297672944, 173.4130892475875, 1.6480803855327946, 0.37829277502867487, 0.0772323433798705),
        )
        assertSample(
            field.sampleAt(37, 800.0, 600.0, 3000.0, false),
            doubleArrayOf(233.20173468460425, 407.75499855085985, 1.1614232368295117, 0.7204225310249015, 0.008723712330503813),
        )
        assertSample(
            field.sampleAt(899, 800.0, 600.0, 3000.0, false),
            doubleArrayOf(423.277887110208, 297.8378133149594, 1.1703051446731656, 0.7786324075445059, 0.3625616790953847),
        )
        assertSample(
            field.sampleAt(899, 1080.0, 1920.0, 2500.0, false),
            doubleArrayOf(592.0409485428429, 958.5019556632793, 1.1724727972612885, 0.7799356851770153, 0.3694072581527678),
        )
        assertSample(
            field.sampleAt(1, 800.0, 600.0, 6000.0, false),
            doubleArrayOf(544.4259502922243, 116.10809548166702, 1.5360294353777924, 0.3552362714243678, -0.15464780518351962),
        )
    }

    @Test
    fun fifthParticle_staysFreeEvenWhenGathered() {
        val field = gathered()
        // Assembly is complete at t=6000, but index 0 and index 5 keep free-cloud output.
        assertSample(
            field.sampleAt(0, 800.0, 600.0, 6000.0, false),
            doubleArrayOf(461.3885430533645, 172.20210807092923, 0.7895923063221257, 0.5699379370925122, -0.34526910682130163),
        )
        assertSample(
            field.sampleAt(5, 800.0, 600.0, 6000.0, false),
            doubleArrayOf(856.4372642711135, 316.1490941445753, 1.1873552024800567, 0.3395899849525406, 0.433233796296307),
        )
    }

    @Test
    fun frozen_matchesJsLatticeIncludingNegativeRow() {
        val field = gathered()

        assertSample(
            field.sampleAt(1, 800.0, 600.0, 3000.0, true),
            doubleArrayOf(-209.58633598318028, -78.99020799159021, 1.6480803855327946, 0.37829277502867487, 0.0772323433798705),
        )
        // Index 36 hits negative odd row -19, exercising JS remainder semantics.
        assertSample(
            field.sampleAt(36, 800.0, 600.0, 1500.0, true),
            doubleArrayOf(-262.72038785102654, -59.46151392551326, 1.5305896181014533, 0.7048338804522358, -0.13235785978385606),
        )
        assertSample(
            field.sampleAt(899, 800.0, 600.0, 2500.0, true),
            doubleArrayOf(1019.4903152012361, 388.223797600618, 1.1724727972612885, 0.7799356851770153, 0.3694072581527678),
        )
        assertSample(
            field.sampleAt(1799, 1080.0, 1920.0, 6000.0, true),
            doubleArrayOf(1402.1279508164257, 1740.1151454082128, 0.6449214412276838, 0.25630927261075725, -0.6053348751736507),
        )
    }

    @Test
    fun frozenFifthParticle_staysFree() {
        val field = gathered()

        assertSample(
            field.sampleAt(0, 800.0, 600.0, 1500.0, true),
            doubleArrayOf(97.60192921488726, 293.1471131811665, 0.8530019449376047, 0.6103765077071951, -0.1053072033859474),
        )
        assertSample(
            field.sampleAt(5, 800.0, 600.0, 1500.0, true),
            doubleArrayOf(812.2260464382366, 91.43347966178433, 1.0643152771475661, 0.3077318917932812, 0.04292250829971658),
        )
        assertSample(
            field.sampleAt(35, 800.0, 600.0, 1500.0, true),
            doubleArrayOf(219.50466050657934, 425.1666142155333, 1.3108013389629787, 0.6775311395444193, -0.017838093239043257),
        )
    }

    @Test
    fun wifiPaused_suppressesBroadcast() {
        val field = MatterParticleField()
        field.setBroadcast(true, true, 0.0)
        assertThat(field.progress(3000.0)).isWithin(TOLERANCE).of(0.0)
        assertSample(
            field.sampleAt(1, 800.0, 600.0, 3000.0, false),
            // Free-cloud point at the same clock; broadcast suppressed so no assembly blend.
            doubleArrayOf(294.3434523591927, 427.18255669174687, 1.6480803855327946, 0.37829277502867487, 0.0772323433798705),
        )
    }

    @Test
    fun releaseMidMotion_respectsAssembly() {
        val field = MatterParticleField()
        field.setBroadcast(true, false, 0.0)
        field.setBroadcast(false, false, 3000.0)
        assertThat(field.progress(7000.0)).isWithin(TOLERANCE).of(0.33000285036607857)
        assertSample(
            field.sampleAt(1, 800.0, 600.0, 7000.0, false),
            doubleArrayOf(220.57060994854695, 213.32704682233543, 1.4998647702896866, 0.34779473942665595, -0.22948758031951652),
        )
        assertSample(
            field.sampleAt(899, 800.0, 600.0, 7000.0, false),
            doubleArrayOf(330.54386957847004, 424.82670326446896, 1.1034262336164333, 0.7384221868918099, 0.1513539996826192),
        )
    }

    @Test
    fun frozenSwitch_doesNotCorruptAssembly() {
        val field = gathered()
        val before = field.progress(3000.0)
        field.sampleAt(36, 800.0, 600.0, 1500.0, true)
        field.sampleAt(899, 800.0, 600.0, 2500.0, true)
        assertThat(field.progress(3000.0)).isWithin(TOLERANCE).of(before)
        assertSample(
            field.sampleAt(1, 800.0, 600.0, 3000.0, false),
            doubleArrayOf(458.6765297672944, 173.4130892475875, 1.6480803855327946, 0.37829277502867487, 0.0772323433798705),
        )
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
