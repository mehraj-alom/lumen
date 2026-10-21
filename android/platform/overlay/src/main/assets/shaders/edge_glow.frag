// LUMEN — the edge glow, Mode A of the active-status indicator.
//
// A luminous band along the screen perimeter, with a travelling highlight.
// The trail is a comet: a bright head with a fading tail behind it, drawn
// as a series of halo nodes along the perimeter.
//
// The position along the perimeter is computed analytically, so the shader
// needs no path data and the band follows the display edges exactly.
//
// AGSL. Requires API 33. Below that the indicator falls back to the
// Canvas path in GlassOverlayView.kt.

uniform float2 uSize;
uniform float uPhase;       // 0..1, the position of the head
uniform float uThickness;   // the band thickness, in pixels
uniform float uTailLength;  // 0..1, the length of the tail around the perimeter
uniform float uNodeCount;   // the number of halo nodes in the trail
uniform float uSpeed;       // the travel speed, in cycles per second
uniform float uTime;
uniform half4 uColor;
uniform half4 uCoreColor;

// The perimeter of a rectangle, parameterised from 0 at the top-left corner
// going clockwise.
float perimeterPosition(float2 fragCoord) {
    float2 size = uSize;
    float2 p = float2(fragCoord.x, size.y - fragCoord.y);

    float top = p.x;
    float right = size.x + p.y;
    float bottom = size.x * 2.0 + (size.y - p.y);
    float left = size.x * 2.0 + size.y + (size.x - p.x);

    float total = size.x * 2.0 + size.y * 2.0;

    if (p.y <= 0.0) return top / total;
    if (p.x >= size.x) return right / total;
    if (p.y >= size.y) return bottom / total;
    return left / total;
}

// The distance from the nearest edge, in pixels.
float edgeDistance(float2 fragCoord) {
    float2 size = uSize;
    float2 p = float2(fragCoord.x, size.y - fragCoord.y);
    return min(min(p.x, size.x - p.x), min(p.y, size.y - p.y));
}

half4 main(float2 fragCoord) {
    float distance = edgeDistance(fragCoord);
    if (distance > uThickness * 4.0) {
        return half4(0.0);
    }

    float position = perimeterPosition(fragCoord);

    // How far behind the head this pixel is, 0 at the head and 1 at the tail.
    float behind = uPhase - position;
    behind = behind - floor(behind);
    if (behind > uTailLength) {
        return half4(0.0);
    }

    float along = 1.0 - behind / max(uTailLength, 0.0001);

    // The band itself: a bright core with a soft falloff outwards.
    float band = 1.0 - smoothstep(0.0, uThickness, distance);
    float core = 1.0 - smoothstep(0.0, uThickness * 0.25, distance);

    // The comet head is brighter and slightly wider.
    float head = pow(along, 3.0);

    float alpha = (band * 0.75 + core * 0.25) * (0.25 + 0.75 * along);
    half3 colour = mix(uColor.rgb, uCoreColor.rgb, core * 0.8 + head * 0.2);

    return half4(colour, alpha * uColor.a);
}
