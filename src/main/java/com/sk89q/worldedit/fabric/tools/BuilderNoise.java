package com.sk89q.worldedit.fabric.tools;

import com.fastasyncworldedit.core.math.random.SimplexNoise;
import java.util.Locale;

/** Seeded procedural fields used by terrain, masks and patterns; values in [0,1]. */
public final class BuilderNoise {
    private BuilderNoise() { }

    public static String type(String type) {
        String value = type.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "simplex", "perlin", "cubic" -> "simplex";
            case "fractal", "frac", "perlinfractal", "simplexfractal", "cubicfractal" -> "fractal";
            case "billowy", "billow", "ridged", "turbulence", "turb", "electric", "elec", "cell", "cellular",
                 "voronoi", "vor", "arcvor", "crack", "euclidean", "manhattan", "natural", "poly" -> value;
            default -> throw new IllegalArgumentException("Unknown noise type: " + type);
        };
    }

    public static double sample(String kind, double x, double y, double z, long seed, double jitter, int octaves) {
        String type = type(kind);
        if (type.equals("cell") || type.equals("cellular") || type.equals("voronoi") || type.equals("vor")
            || type.equals("arcvor") || type.equals("crack") || type.equals("euclidean")
            || type.equals("manhattan") || type.equals("natural") || type.equals("poly")) {
            return cellular(type, x, y, z, seed, jitter);
        }
        double offset = unit(seed) * 10000;
        double sum = 0, weight = 0, amplitude = 1;
        int layers = type.equals("simplex") ? 1 : octaves;
        for (int octave = 0; octave < layers; octave++) {
            double noise = SimplexNoise.noise(x + offset, y - offset, z + offset * 0.31);
            if (type.equals("billowy") || type.equals("billow")) noise = Math.abs(noise) * 2 - 1;
            if (type.equals("ridged")) noise = 1 - Math.abs(noise) * 2;
            sum += noise * amplitude;
            weight += amplitude;
            amplitude *= 0.5;
            x *= 2; y *= 2; z *= 2;
        }
        double n = sum / weight;
        if (type.equals("turbulence") || type.equals("turb")) n = Math.sin(5 * n + x * 0.15);
        if (type.equals("electric") || type.equals("elec")) n = 1 - 2 * Math.sqrt(Math.abs(n));
        return Math.clamp((n + 1) * 0.5, 0, 1);
    }

    private static double cellular(String type, double x, double y, double z, long seed, double jitter) {
        String mode=(type.equals("cell")||type.equals("cellular")||type.equals("poly"))?"cellvalue":type.equals("crack")?"gap":"distance";
        return cellularSample(type,mode,x,y,z,seed,jitter);
    }

    public static double cellularSample(String metric,String mode,double x,double y,double z,long seed,double jitter) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double first = Double.POSITIVE_INFINITY, second = first;
        long nearest = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            long hash = hash(ix + dx, iy + dy, iz + dz, seed);
            double a = ix + dx + 0.5 + (unit(hash) - 0.5) * jitter - x;
            double b = iy + dy + 0.5 + (unit(hash + 1) - 0.5) * jitter - y;
            double c = iz + dz + 0.5 + (unit(hash + 2) - 0.5) * jitter - z;
            double squared = a*a + b*b + c*c;
            double distance = metric.equals("manhattan") ? Math.abs(a)+Math.abs(b)+Math.abs(c)
                : metric.equals("natural") ? squared + Math.abs(a)+Math.abs(b)+Math.abs(c) : squared;
            if (distance < first) { second = first; first = distance; nearest = hash; }
            else if (distance < second) second = distance;
        }
        return switch (mode) {
            case "cellvalue" -> unit(nearest);
            case "gap" -> Math.clamp((second - first) * 3, 0, 1);
            case "distance2" -> distance(second,metric);
            default -> distance(first,metric);
        };
    }

    private static double distance(double value,String metric) {
        return Math.clamp(metric.equals("manhattan")||metric.equals("natural")?value:Math.sqrt(value),0,1);
    }

    public static long hash(int x, int y, int z, long seed) {
        long h = seed ^ x * 0x9e3779b97f4a7c15L ^ y * 0xc2b2ae3d27d4eb4fL ^ z * 0x165667b19e3779f9L;
        h = (h ^ h >>> 30) * 0xbf58476d1ce4e5b9L;
        h = (h ^ h >>> 27) * 0x94d049bb133111ebL;
        return h ^ h >>> 31;
    }

    public static double unit(long value) { return (hash(0, 0, 0, value) >>> 11) * 0x1.0p-53; }
}
