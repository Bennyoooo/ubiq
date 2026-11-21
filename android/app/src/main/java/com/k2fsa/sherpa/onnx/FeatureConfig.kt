// Vendored from sherpa-onnx v2.0.1 (Apache-2.0), sherpa-onnx/kotlin-api. The JNI library reads these
// classes and fields by name: keep the package, names and field order as upstream (proguard keeps them).
package com.k2fsa.sherpa.onnx

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
    var dither: Float = 0.0f
)

fun getFeatureConfig(sampleRate: Int, featureDim: Int): FeatureConfig {
    return FeatureConfig(sampleRate = sampleRate, featureDim = featureDim)
}
