package com.oliver.erydon.block;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Source mappings checked against authored geometry, without bootstrapping Minecraft registries. */
class SliceTransformTest {
    private static final String[] FACINGS = {"NORTH", "EAST", "SOUTH", "WEST"};
    private static final Path JAVA = Path.of("src/main/java/com/oliver/erydon");
    private static final Path RESOURCES = Path.of("src/main/resources");

    @Test
    void verticalMirrorsReflectActualCornersAtEveryThickness() throws IOException {
        String source = Files.readString(JAVA.resolve("block/VerticalSliceBlock.java"));
        for (String mirror : new String[]{"LEFT_RIGHT", "FRONT_BACK"}) {
            var branch = Pattern.compile("case " + mirror + " -> state.with\\(FACING, switch \\(facing\\) \\{(.*?)\\}\\);",
                    Pattern.DOTALL).matcher(source);
            assertTrue(branch.find(), "Missing corner mapping: " + mirror);
            int[] mapping = new int[4];
            for (int facing = 0; facing < 4; facing++) {
                var assignment = Pattern.compile("case " + FACINGS[facing] + " -> Direction.([A-Z]+);")
                        .matcher(branch.group(1));
                assertTrue(assignment.find());
                mapping[facing] = index(assignment.group(1));
                for (int layers = 1; layers <= 8; layers++) {
                    int[] original = bounds(true, facing, false, layers);
                    int[] expected = reflect(original, mirror.equals("LEFT_RIGHT") ? 2 : 0);
                    assertArrayEquals(expected, bounds(true, mapping[facing], false, layers),
                            mirror + " " + FACINGS[facing] + " layers=" + layers);
                }
            }
            for (int facing = 0; facing < 4; facing++) assertEquals(facing, mapping[mapping[facing]]);
        }
        assertTrue(source.contains("case NONE -> state;"));
    }

    @Test
    void yawRotationsMatchModelsForBothSliceFamilies() throws IOException {
        for (boolean vertical : new boolean[]{false, true}) {
            String source = source(vertical);
            assertTrue(method(source, "rotate").contains("return state.with(FACING, rotation.rotate(state.get(FACING)));"));
            for (int facing = 0; facing < 4; facing++) {
                for (int layers = 1; layers <= 8; layers++) {
                    for (boolean top : new boolean[]{false, true}) {
                        int[] expected = bounds(vertical, facing, top, layers);
                        for (int turns = 0; turns < 4; turns++) {
                            assertArrayEquals(expected, bounds(vertical, (facing + turns) % 4, top, layers));
                            expected = rotate(expected);
                        }
                    }
                }
            }
        }
    }

    @Test
    void horizontalMirrorsKeepHeightAndReflectTheOccupiedEdge() throws IOException {
        assertTrue(method(source(false), "mirror").contains("return state.rotate(mirror.getRotation(state.get(FACING)));"));
        for (int axis : new int[]{0, 2}) {
            for (int facing = 0; facing < 4; facing++) {
                int mirroredFacing = axis == 0 ? (4 - facing) % 4 : (2 - facing + 4) % 4;
                for (int layers = 1; layers <= 8; layers++) {
                    for (boolean top : new boolean[]{false, true}) {
                        assertArrayEquals(reflect(bounds(false, facing, top, layers), axis),
                                bounds(false, mirroredFacing, top, layers));
                    }
                }
            }
        }
    }

    @Test
    void upsideDownFlipSwapsOnlyHorizontalSliceTopAndBottom() throws IOException {
        String mixin = Files.readString(JAVA.resolve("mixin/client/compat/axiom/SliceFlipMixin.java"));
        assertTrue(mixin.contains("method = \"flipY\", at = @At(\"TAIL\")"));
        assertTrue(mixin.contains("state.getBlock() instanceof HorizontalSliceBlock"));
        assertTrue(mixin.contains("cir.setReturnValue(state.cycle(HorizontalSliceBlock.TOP));"));
        assertTrue(Files.readString(RESOURCES.resolve("erydon.mixins.json"))
                .contains("client.compat.axiom.SliceFlipMixin"));
        for (int facing = 0; facing < 4; facing++) {
            for (int layers = 1; layers <= 8; layers++) {
                for (boolean top : new boolean[]{false, true}) {
                    assertArrayEquals(reflect(bounds(false, facing, top, layers), 1),
                            bounds(false, facing, !top, layers));
                }
                assertArrayEquals(reflect(bounds(true, facing, false, layers), 1),
                        bounds(true, facing, false, layers));
            }
        }
    }

    @Test
    void blockTransformsOnlyWriteFacingAndDoNotReadTheWorld() throws IOException {
        for (boolean vertical : new boolean[]{false, true}) {
            for (String name : new String[]{"rotate", "mirror"}) {
                String body = method(source(vertical), name);
                var writes = Pattern.compile("\\.with\\(([^,]+),").matcher(body);
                while (writes.find()) assertEquals("FACING", writes.group(1));
                assertFalse(body.contains("world"));
                assertFalse(body.contains("new "));
                assertFalse(body.contains("SHAPES"));
            }
        }
    }

    private static String source(boolean vertical) throws IOException {
        return Files.readString(JAVA.resolve("block/" + (vertical ? "Vertical" : "Horizontal") + "SliceBlock.java"));
    }

    private static String method(String source, String name) {
        int start = source.indexOf("public BlockState " + name + "(");
        assertTrue(start >= 0);
        return source.substring(start, source.indexOf("\n    @Override", start));
    }

    private static int index(String name) {
        for (int i = 0; i < FACINGS.length; i++) if (FACINGS[i].equals(name)) return i;
        throw new AssertionError(name);
    }

    private static int[] bounds(boolean vertical, int facing, boolean top, int layers) throws IOException {
        String family = vertical ? "vertical" : "horizontal";
        var states = JsonParser.parseString(Files.readString(RESOURCES.resolve(
                "assets/erydon/blockstates/aganite_slice_" + family + ".json"))).getAsJsonObject();
        String key = "facing=" + FACINGS[facing].toLowerCase(java.util.Locale.ROOT) + ",layers=" + layers
                + (vertical ? "" : ",top=" + top);
        var variant = states.getAsJsonObject("variants").getAsJsonObject(key);
        assertNotNull(variant, key);
        if (layers == 8) {
            assertEquals("erydon:block/block/aganite_block", variant.get("model").getAsString());
            return new int[]{0, 0, 0, 16, 16, 16};
        }
        String model = "slice_" + family + (vertical ? "" : "_bottom") + "_size" + (layers * 2);
        assertEquals("erydon:block/layer/slice_" + family + "/aganite_" + model,
                variant.get("model").getAsString());
        var json = JsonParser.parseString(Files.readString(RESOURCES.resolve(
                "assets/erydon/models/block/layer/slice_" + family + "/" + model + ".json"))).getAsJsonObject();
        var elements = json.getAsJsonArray("elements");
        assertEquals(1, elements.size());
        var element = elements.get(0).getAsJsonObject();
        int[] box = new int[6];
        for (int axis = 0; axis < 3; axis++) {
            box[axis] = element.getAsJsonArray("from").get(axis).getAsInt();
            box[axis + 3] = element.getAsJsonArray("to").get(axis).getAsInt();
        }
        int x = variant.has("x") ? variant.get("x").getAsInt() : 0;
        assertTrue(x == 0 || x == 180);
        if (x == 180) box = reflect(reflect(box, 1), 2);
        int turns = variant.has("y") ? variant.get("y").getAsInt() / 90 : 0;
        for (int i = 0; i < turns; i++) box = rotate(box);
        return box;
    }

    private static int[] rotate(int[] b) {
        return new int[]{16 - b[5], b[1], b[0], 16 - b[2], b[4], b[3]};
    }

    private static int[] reflect(int[] b, int axis) {
        int[] result = b.clone();
        result[axis] = 16 - b[axis + 3];
        result[axis + 3] = 16 - b[axis];
        return result;
    }
}
