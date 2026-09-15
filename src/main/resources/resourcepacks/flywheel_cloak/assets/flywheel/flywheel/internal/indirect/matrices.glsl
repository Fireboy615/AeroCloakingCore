// Keep this layout byte-for-byte compatible with Sable 2.0.5's 192-byte
// embedded matrix record.
struct Matrices {
    mat4 pose;
    vec4 normalA;
    vec4 normalB;
    vec4 normalC;
    float skyLightScale;
    uint sceneID;
    float _padding1;
    float _padding2;
    mat4 lightingSceneMatrix;
};

void _flw_unpackMatrices(
        in Matrices mats,
        out mat4 pose,
        out mat3 normal,
        out uint lightingSceneId,
        out float skyLightScale,
        out float aeroCloakStrength,
        out mat4 lightingSceneMatrix
) {
    pose = mats.pose;

    // AeroCloakingCore indirect transport. Sable's normal matrix is a pure
    // rotation, so normalA.xyz has unit length before we encode the cloak.
    //
    //   1..2   = DITHER signal  0..+1
    //   1..0.5 = ALPHA signal   0..-1
    float encodedScale = max(length(mats.normalA.xyz), 0.000001);
    float scaleDelta = encodedScale - 1.0;

    if (abs(scaleDelta) < 0.0001) {
        aeroCloakStrength = 0.0;
    } else if (scaleDelta > 0.0) {
        aeroCloakStrength = clamp(scaleDelta, 0.0, 1.0);
    } else {
        aeroCloakStrength = clamp(scaleDelta * 2.0, -1.0, 0.0);
    }

    // Remove the transport scale before using the matrix. Flywheel normalizes
    // vertex normals later as well, but restoring it here keeps this mat3
    // semantically identical to Sable's original normal matrix.
    normal = mat3(
        mats.normalA.xyz / encodedScale,
        mats.normalB.xyz / encodedScale,
        mats.normalC.xyz / encodedScale
    );

    lightingSceneId = mats.sceneID;
    skyLightScale = mats.skyLightScale;
    lightingSceneMatrix = mats.lightingSceneMatrix;
}
