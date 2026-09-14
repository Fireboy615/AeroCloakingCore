package net.fireboy.aerocloakingcore.client;

/**
 * Easing curves used when transitioning cloak strength.
 *
 * Input and output values are both in the range 0.0 -> 1.0.
 */
public enum CloakEasing {

    LINEAR {
        @Override
        public float apply(float t) {
            return clamp(t);
        }
    },

    EASE_IN {
        @Override
        public float apply(float t) {
            t = clamp(t);
            return t * t;
        }
    },

    EASE_OUT {
        @Override
        public float apply(float t) {
            t = clamp(t);
            float inverse = 1.0F - t;
            return 1.0F - inverse * inverse;
        }
    },

    EASE_IN_OUT {
        @Override
        public float apply(float t) {
            t = clamp(t);

            if (t < 0.5F) {
                return 4.0F * t * t * t;
            }

            float f = -2.0F * t + 2.0F;
            return 1.0F - (f * f * f) / 2.0F;
        }
    },

    SMOOTHSTEP {
        @Override
        public float apply(float t) {
            t = clamp(t);
            return t * t * (3.0F - 2.0F * t);
        }
    };

    public abstract float apply(float t);

    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}