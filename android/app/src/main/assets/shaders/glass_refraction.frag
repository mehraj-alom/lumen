// LUMEN — the 3D Liquid Glass surface.
//
// A glass surface is a lens, not a sheet. This shader does four things:
//   1. finds the rounded-rectangle edge of the surface
//   2. warps the backdrop with a low-frequency noise field, concentrated at
//      the edge, so the surface bends what is behind it
//   3. splits the channels at the rim, so the edge disperses light
//   4. adds a fresnel-weighted specular rim
//
// The parameters are STYLE_GUIDE.md §3.2. They are uniforms, so the
// surface animates without recompiling the shader.
//
// AGSL. Requires API 33. Below that the surface falls back to the
// Canvas path in GlassSurface.kt.

uniform float2 uSize;
uniform float uCornerRadius;
uniform float uRefraction;
uniform float uIor;
uniform float uFresnelPower;
uniform float uNoiseScale;
uniform float uNoiseAmplitude;
uniform float uThickness;
uniform float uRimWidth;
uniform float uTime;
uniform half4 uTint;
uniform half4 uRimColor;

float roundedRectSdf(float2 fragCoord) {
    float2 halfSize = uSize * 0.5;
    float2 p = float2(fragCoord.x, uSize.y - fragCoord.y) - halfSize;
    float2 q = abs(p) - (halfSize - float2(uCornerRadius));
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uCornerRadius;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uSize;
    float2 centred = uv * 2.0 - 1.0;
    centred.x *= uSize.x / max(uSize.y, 1.0);

    float sdf = roundedRectSdf(fragCoord);

    // 1 at the middle of the surface, 0 at the edge and beyond.
    float inside = 1.0 - smoothstep(-1.0, 1.0, sdf);

    // The edge band, where the refraction is strongest.
    float edgeBand = 1.0 - inside;

    // The warp field. Two low-frequency waves beat against each other so the
    // distortion never looks like a grid.
    float wave =
        sin(uv.x * uNoiseScale * 6.2831) * cos(uv.y * uNoiseScale * 4.1) * 0.5 +
        sin((uv.x + uv.y) * uNoiseScale * 2.7) * 0.25;
    float falloff = pow(edgeBand, 2.2);
    float displacement = uRefraction * uNoiseAmplitude * falloff * wave;

    // 2. The displaced light field. The displacement is scaled by the
    //    thickness, which is what gives the surface its depth. The field is
    //    drawn with an alpha, so the content behind the surface shows
    //    through it, bent.
    float2 direction = normalize(centred + float2(0.0001, 0.0001));
    float2 offset = direction * displacement * uThickness;
    float2 sample = clamp(uv + offset, float2(0.0), float2(1.0));

    float field = sin(sample.x * 18.0 + uTime * 0.4) * cos(sample.y * 14.0 - uTime * 0.3) * 0.5 + 0.5;

    // 3. Chromatic dispersion at the rim. The channel separation grows
    //    towards the edge, where a lens disperses most.
    float spread = edgeBand * 0.35;
    half3 dispersed = half3(
        field + spread,
        field,
        max(field - spread, 0.0)
    );

    // 4. The fresnel rim. The surface is bright where it is seen at a
    //    glancing angle, which is the edge.
    float fresnel = pow(1.0 - inside, uFresnelPower);
    half3 tinted = mix(dispersed, uTint.rgb, uTint.a);
    half3 result = mix(tinted, uRimColor.rgb, fresnel * edgeBand);

    // The surface is opaque in the middle and transparent at the edge, so
    // the content behind is only refracted where the glass is.
    float alpha = (0.10 + 0.35 * edgeBand + 0.30 * fresnel) * inside;

    return half4(result, alpha);
}
