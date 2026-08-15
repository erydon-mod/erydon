package com.oliver.erydon.client.model;

import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.render.model.json.Transformation;
import org.joml.Vector3f;

final class ErydonSlopeItemTransforms {
    private static final Transformation GROUND = transform(0.0F, 0.0F, 0.0F, 0.0F, 3.0F, 0.0F, 0.25F);
    private static final Transformation FIRST_PERSON_RIGHT_HAND = transform(0.0F, 45.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.8F);
    private static final Transformation FIRST_PERSON_LEFT_HAND = transform(0.0F, -135.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.8F);
    private static final Transformation THIRD_PERSON_RIGHT_HAND = transform(75.0F, 45.0F, 0.0F, 0.0F, 2.5F, 0.0F, 0.375F);
    private static final Transformation THIRD_PERSON_LEFT_HAND = transform(75.0F, -135.0F, 0.0F, 0.0F, 2.5F, 0.0F, 0.375F);

    private static final ModelTransformation STANDARD = create(transform(20.0F, -45.0F, 0.0F, 0.75F, 0.0F, 0.0F, 0.6F));
    private static final ModelTransformation SHALLOW_LOWER = create(transform(20.0F, -45.0F, 0.0F, 0.75F, 2.25F, 0.0F, 0.6F));
    private static final ModelTransformation SHALLOW_UPPER = create(transform(20.0F, -45.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.5F));
    private static final ModelTransformation STEEP_LOWER = create(transform(20.0F, -45.0F, 0.0F, 2.25F, -1.25F, 0.0F, 0.6F));
    private static final ModelTransformation STEEP_UPPER = create(transform(20.0F, -45.0F, 0.0F, 0.25F, 0.0F, 0.0F, 0.6F));
    private static final ModelTransformation VERTICAL = create(transform(20.0F, 23.5F, 0.0F, 1.75F, -0.5F, 0.0F, 0.6F));
    private static final ModelTransformation VERTICAL_SHALLOW_BROAD = create(transform(20.0F, -69.0F, 0.0F, 1.25F, -0.75F, 0.0F, 0.6F));
    private static final ModelTransformation VERTICAL_SHALLOW_NARROW = create(transform(20.0F, -55.0F, 0.0F, 3.0F, -1.25F, 0.0F, 0.6F));

    private ErydonSlopeItemTransforms() {
    }

    static ModelTransformation forFamily(ErydonSlopeModelClassifier.Family family, ModelTransformation fallback) {
        if (family == null) {
            return fallback == null ? ModelTransformation.NONE : fallback;
        }

        ModelTransformation transformation = switch (family) {
            case STANDARD -> STANDARD;
            case SHALLOW_LOWER -> SHALLOW_LOWER;
            case SHALLOW_UPPER -> SHALLOW_UPPER;
            case STEEP_LOWER -> STEEP_LOWER;
            case STEEP_UPPER -> STEEP_UPPER;
            case VERTICAL -> VERTICAL;
            case VERTICAL_SHALLOW_BROAD -> VERTICAL_SHALLOW_BROAD;
            case VERTICAL_SHALLOW_NARROW -> VERTICAL_SHALLOW_NARROW;
            case NONE -> fallback;
        };
        return transformation == null ? ModelTransformation.NONE : transformation;
    }

    private static ModelTransformation create(Transformation guiAndFixed) {
        return new ModelTransformation(
                THIRD_PERSON_LEFT_HAND,
                THIRD_PERSON_RIGHT_HAND,
                FIRST_PERSON_LEFT_HAND,
                FIRST_PERSON_RIGHT_HAND,
                Transformation.IDENTITY,
                guiAndFixed,
                GROUND,
                guiAndFixed
        );
    }

    private static Transformation transform(float rotationX,
                                            float rotationY,
                                            float rotationZ,
                                            float translationX,
                                            float translationY,
                                            float translationZ,
                                            float scale) {
        return new Transformation(
                new Vector3f(rotationX, rotationY, rotationZ),
                new Vector3f(translationX / 16.0F, translationY / 16.0F, translationZ / 16.0F),
                new Vector3f(scale, scale, scale)
        );
    }
}
